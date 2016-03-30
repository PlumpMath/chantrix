(ns ^:unit chantrix.http.kit-test
  (:require [clojure.test :refer :all]
            [org.httpkit.client :as httpkit]
            [chantrix.async :refer :all]
            [chantrix.junction :as junc :refer [>!!<]]
            [chantrix.http.kit :as http]))

(defn- mock-request
  [request handler]
  (future
    (handler (-> request
                 (:response)
                 (assoc :opts (dissoc request :response))))))

(use-fixtures :each
  (fn [test-fn]
    (with-redefs [httpkit/request mock-request]
      (test-fn))))

(deftest request-dispatch
  (testing "simple request"
    (let [http (http/start)]
      (is (= (>!!< http {:url      "http://www.opentable.com/"
                         :response {:status 200}})
             {:status 200
              :opts   {:url "http://www.opentable.com/"}}))
      (junc/close! http)))

  (testing "default options"
    (let [http (http/start :opts {:timeout 20})]
      (is (= (>!!< http {:url      "http://www.opentable.com/"
                         :response {:status 200}})
             {:status 200
              :opts   {:url     "http://www.opentable.com/"
                       :timeout 20}}))
      (is (= (>!!< http {:url      "http://www.opentable.com/"
                         :timeout  60
                         :response {:status 200}})
             {:status 200
              :opts   {:url     "http://www.opentable.com/"
                       :timeout 60}}))
      (junc/close! http)))

  (testing "close after last request"
    (let [[>http <http] (http/start)]
      (>!! >http {:url      "http://www.opentable.com/"
                  :response {:status 200}})
      (close! >http)
      (is (= (<!! <http)
             {:status 200
              :opts   {:url "http://www.opentable.com/"}}))
      (is (nil? (<!! <http))))))

(deftest json-transducers
  (testing "missing body"
    (is (= ((http/json-request) {})
           {}))
    (is (= ((http/json-response) {})
           {})))

  (testing "nil body"
    (is (= ((http/json-request) {:body nil})
           {:body nil}))
    (is (= ((http/json-response) {:body nil})
           {:body nil})))

  (testing "empty body"
    (is (= ((http/json-request) {:body ""})
           {:headers {"Content-Type" "application/json"}
            :body    "\"\""}))
    (is (= ((http/json-response) {:body "\"\""})
           {:body ""})))

  (testing "default keywordization"
    (is (= ((http/json-request) {:body {:k 1 "s" 2}})
           {:headers {"Content-Type" "application/json"}
            :body    "{\":k\":1,\"s\":2}"}))
    (is (= ((http/json-response) {:body "{\":k\":1,\"s\":2}"})
           {:body {":k" 1 "s" 2}})))

  (testing "automatic keywordization"
    (is (= ((http/json-request :keywords? true) {:body {:k 1 "s" 2}})
           {:headers {"Content-Type" "application/json"}
            :body    "{\"k\":1,\"s\":2}"}))
    (is (= ((http/json-response :keywords? true) {:body "{\"k\":1,\"s\":2}"})
           {:body {:k 1 :s 2}})))

  (testing "parse error"
    (let [response ((http/json-response) {:body "garbage"})]
      (is (= (:body response) "garbage"))
      (is (instance? Exception (:error response))))))

(deftest exception-transducer
  (testing "default fault detector"
    (is (= ((http/exception-response) {:status 200})
           {:status 200}))
    (let [response ((http/exception-response) {:error (ex-info "testing"
                                                               {:k 1})})]
      (is (instance? Exception response))
      (is (= (.getMessage (.getCause response)) "testing"))
      (is (= (ex-data (.getCause response)) {:k 1})))
    (let [response ((http/exception-response) {:status 300})]
      (is (instance? Exception response))
      (is (= (ex-data response) {:status 300}))))

  (testing "custom fault detector"
    (let [f? (fn [{:keys [status]}] (>= status 500))]
      (is (= ((http/exception-response :fn-fault? f?) {:status 499})
             {:status 499}))
      (let [response ((http/exception-response :fn-fault? f?) {:status 500})]
        (is (instance? Exception response))
        (is (= (ex-data response) {:status 500}))))))
