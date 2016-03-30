(ns chantrix.http.kit
  "asynchronous channel-based httpkit client
  . implements a core.async HTTP pipeline over httpkit
  . HTTP instances are represented by a channel junction,
    which consists of a request channel and a response channel
  . middleware is attached via transducers (json, exceptions, etc.)
  . response demultiplexing can be performed via mults/transducers"
  (:require [cheshire.core :as json]
            [org.httpkit.client :as http]
            [chantrix.async :refer :all]
            [chantrix.junction :as junc :refer [junc]]))

;;; http processes

(defn- handle-response
  "asynchronous response handler
  maps callback-based httpkit responses to a the HTTP response junction
  closes the output channel once all responses have been received"
  [junc refs response]
  (let [[_ >http] junc]
    (>!! >http response)
    (when (zero? (swap! refs dec))
      (close! >http))))

(defn- submit-requests
  "asynchronous request dispatcher
  sends incoming requests to httpkit, along with the handler callback
  uses reference counting to keep the response channel open until the
  last response has been received after the request channel is closed"
  [opts junc]
  (let [[<http >http] junc
        refs (atom 1)]
    (go-each
      (while<! [request <http]
        (swap! refs inc)
        (http/request (merge opts request)
                      (partial handle-response junc refs)))
      (when (zero? (swap! refs dec))
        (close! >http)))))



;;; json transducers

(defn json-request
  "JSON request transducer
  converts request bodies to JSON, optionally stringifying keyword keys
  :keywords? - stringify keywords during conversion? (default: false)"
  [& kwargs]
  (let [{:keys [keywords?] :or {keywords? false}} kwargs
        key-fn (if keywords? name str)]
    (fn [request]
      (if-let [body (:body request)]
        (-> request
            (assoc-in [:headers "Content-Type"] "application/json")
            (assoc :body (json/generate-string body {:key-fn key-fn})))
        request))))

(defn json-response
  "JSON response transducer
  converts response bodies to JSON, optionally keywordizing keys
  :keywords? - keywordize map keys during conversion (default: false)"
  [& kwargs]
  (let [{:keys [keywords?] :or {keywords? false}} kwargs]
    (fn [response]
      (try
        (if-let [body (:body response)]
          (assoc response :body (json/parse-string body keywords?))
          response)
        (catch Exception e
          (assoc response :error e))))))



;;; exception transducers

(defn- http-fault?
  "default fault detector for http responses"
  [response]
  (let [{:keys [status]} response]
    (>= status 300)))

(defn exception-response
  "exception response transducer
  maps failed HTTP responses to exceptions,
  with an optional custom fault detector"
  [& kwargs]
  (let [{:keys [fn-fault?] :or {fn-fault? http-fault?}} kwargs]
    (fn [response]
      (let [{:keys [error]} response
            response (dissoc response :error)]
        (cond
          error                (ex-info "http-error" response error)
          (fn-fault? response) (ex-info "http-error" response)
          :else                response)))))



;;; httpkit initialization

(defn start
  "initializes a new httpkit client
  returns a channel junction used to send requests
  and receive responses
  :opts - default httpkit request map
  remaining options are used as junction parameters"
  [& kwargs]
  (let [{:keys [opts]} kwargs
        junc (apply junc kwargs)
        http {:opts opts
              :junc junc}]
    (submit-requests opts junc)
    junc))

(defn context
  "creates a context for an http junction"
  [http]
  (junc/context http [:context] [:opts :context]))
