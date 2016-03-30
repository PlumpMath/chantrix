(ns chantrix.trace
  "channel/junction tracing support
  . taps into multiplexed channels
  . outputs messages onto stdout via trace"
  (:require [clojure.core.async]
            [clojure.tools.trace :as trace :refer [trace]]
            [chantrix.async :as async]
            [chantrix.junction :as junc]))

(defonce ^:private trace-chan
  (async/chan))

(defonce ^:private trace-task
  (async/go-while<! [[tag msg] trace-chan]
    (trace tag msg)))

(defn tap-mult
  "taps into a multiplexed channel for tracing"
  ([ch]
   (tap-mult nil ch))
  ([tag ch]
   (-> ch
       (async/tap-chan (async/dropping-buffer 100) (map (partial vector tag)))
       (async/pipe trace-chan))))

(defn tap-junc
  "taps into any multiplexed components of a junction for tracing"
  ([j]
   (tap-junc nil j))
  ([tag j]
   (let [[>j <j] j]
     (when (satisfies? clojure.core.async/Mult >j)
       (tap-mult (str ">" tag) >j))
     (when (satisfies? clojure.core.async/Mult <j)
       (tap-mult (str "<" tag) <j)))))

(defn junc
  "creates a channel junction that traces and forwards
  any messages read from/written to the junction
  useful for composing traced channel junctions"
  ([j]
   (junc nil j))
  ([tag j]
   (let [[>j <j] j
         [>t <t] (junc/junc)
         [>m <m] [(async/mult >t) (async/mult <j)]]
     (tap-junc tag [>m <m])
     (async/tap >m >j)
     (async/tap <m <t)
     [>t <t])))
