(ns chantrix.control
  "chantrix feedback control middleware
  . governor
    - restricts the number of messages that can be written to the
      write half of a junction but not yet read from the read half
    - supports junction composition by returning a junction that
      wraps a base junction with semaphore semantics
    - configuration
      :limit - maximum number of in-flight items"
  (:require [chantrix.async :refer :all]
            [chantrix.junction :as junc :refer [junc]]))

;;; throughput governor

(defn- governor-acquirer
  "acquires the limiting semaphore, reads from the
  governor input channel, and writes to the base input channel
  closes the base input channel when the governor input
  channel is closed"
  [<sem base this]
  (let [[<this _] this
        [>base _] base]
    (go-each
      (<! <sem)
      (while<! [msg <this]
        (>! >base msg)
        (<! <sem))
      (close! >base :drain? true))))

(defn- governor-releaser
  "reads from the base output channel, writes to the governor
  output channel, and releases the limiting semaphore
  closes the governor output channel when the base output
  channel is closed"
  [>sem base this]
  (let [[_ <base] base
        [_ >this] this]
    (go-each
      (while<! [msg <base]
        (>! >this msg)
        (>! >sem :release))
      (close! >this))))

(defn governor
  "composes a governor junction atop an existing junction,
  returning the composed junction"
  [base & kwargs]
  (let [{:keys [limit] :or {limit 1}} kwargs
        sem  (semaphore limit)
        this (junc)]
    (assert (pos? limit))
    (governor-acquirer sem base this)
    (governor-releaser sem base this)
    this))
