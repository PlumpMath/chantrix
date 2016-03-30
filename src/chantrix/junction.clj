(ns chantrix.junction
  "chantrix channel junction api
  . encapsulates a two-way communication mechanism via a channel pair
  . externally, writes go to the first channel, reads come from the second
  . useful for representing fully async request/response channels
  . middleware can be stacked by composing a junction onto another junction
  . either side of a junction can be independently buffered and/or transduced"
  (:require [chantrix.async :as async :refer [>! >!! <! <!! <!? <!!?]]))

;;; junction api

(defn- junc-chan
  "creates a channel for use in a junction"
  [buf xform]
  (if xform
    (async/chan (or buf 1) xform)
    (async/chan (or buf 0))))

(defn junc
  "creates a new channel junction
  :buf    default buffer for both channels
  :>buf   buffer for the write channel
  :<buf   buffer for the read channel
  :>xform transducer for the write channel
  :<xform transducer for the read channel"
  [& kwargs]
  (let [{:keys [buf >buf <buf >xform <xform]} kwargs]
    [(junc-chan (or >buf buf) >xform)
     (junc-chan (or <buf buf) <xform)]))

(defn close!
  "closes the channels in a junction
  drains the write side, but doesn't drain the read side,
  so that remaining messages can be retrieved successfully"
  [j]
  (let [[>ch <ch] j]
    (when >ch
      (async/close! >ch :drain? true))
    (when <ch
      (async/close! <ch :drain? false))))

(defn pipe
  "routes the write side of a junction to its read side,
  optionally buffering/transducing the messages flowing between"
  [j & kwargs]
  (let [{:keys [buf xform]} kwargs
        [>ch <ch] j
        p         (junc-chan buf xform)]
    (async/pipe >ch p)
    (async/pipe p <ch)
    j))

(defn pipe-junc
  "creates a new junction whose input is routed to its output,
  with an optional buffer/transducer"
  [& kwargs]
  (let [{:keys [buf xform]} kwargs
        ch (junc-chan buf xform)]
    [ch ch]))

(defn compose
  "composes a junction into a new junction,
  applying buffers/transducers to its channels"
  [j & kwargs]
  (let [[>j <j]       j
        [>x <x :as x] (apply junc kwargs)]
    (async/pipe >x >j)
    (async/pipe <j <x)
    x))

(defn mult
  "multiplexes the read side of a junction into a new junction"
  [j]
  (let [[>ch <ch] j]
    [>ch (async/mult <ch)]))

(defn mult-pipe
  "creates a new junction whose read side is piped and multiplexed"
  [& kwargs]
  (mult (apply pipe-junc kwargs)))

(defn pipe-tap
  "pipes the left side, and taps the right side of a multiplexed
  junction into a new junction"
  [j & kwargs]
  (let [[>j <j] j
        [>t <t] (apply junc kwargs)]
    (async/pipe >t >j false)
    (async/tap <j <t)
    [>t <t]))

(defmacro transact!
  "writes a request to the write side of a junction, and reads a
  response from the other side, throwing if the response is an exception
  returns nil if either channel was closed"
  [j input]
  `(let [[>ch# <ch#] ~j]
     (when (>! >ch# ~input)
       (<!? <ch#))))

(defmacro >!<
  "syntax sugar for transact!"
  [j input]
  `(transact! ~j ~input))

(defn transact!!
  "thread version of transact!"
  [j input]
  (let [[>ch <ch] j]
    (when (>!! >ch input)
      (<!!? <ch))))

(defn >!!<
  "syntax sugar for transact!!"
  [j input]
  (transact!! j input))



;;; context junctions

(defn- context-request
  "demultiplexing request transducer
  associates a context value with each request,
  which is used to filter responses
  typically used with mult junctions for routing all
  requests through a single request channel"
  [id path]
  (fn [request]
    (assoc-in request path id)))

(defn- context-response?
  "demultiplexing response transducer
  filters responses containing the specified context
  assumes exceptional responses contain the response
  as the exception data"
  [id path]
  (fn [response]
    (-> (if (instance? Exception response)
          (ex-data response)
          response)
        (get-in path)
        (identical? id))))

(defn context
  "creates a context junction, used to mux/demux
  requests and responses over an existing junction
  . assumes messages are clojure maps
  . specify the map traversal keys in the request-path and
    response-path parameters to indicate the location of
    the context id within the respective request and response
    (via assoc-in/get-in)
  . by default, the traversal keys are assumed to be the same
    in requests and responses - [:context]"
  ([j]
   (context j [:context] [:context]))
  ([j request-path response-path]
   (let [id (Object.)]
     (pipe-tap j :>xform (map (context-request id request-path))
                 :<xform (filter (context-response? id response-path))))))
