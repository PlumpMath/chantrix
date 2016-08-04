(ns chantrix.async
  "core.async wrapper and extensions for chantrix"
  (:import [clojure.lang Counted])
  (:require [clojure.core.async]
            [clojure.core.async.impl.protocols :as async-impl]
            [potemkin]))

;;; async wrappers/utilities

(defn- except-form? [form]
  "determines whether a form is a catch/finally handler"
  (when (seq? form)
    (#{'catch 'finally} (first form))))

(defn reflat
  "recursive map/sequence flatten"
  [l]
  (loop [l l r []]
    (cond
      (nil? l)          r
      (not (coll? l))   (conj r l)
      (nil? (first l))  (recur (next l) r)
      (coll? (first l)) (recur (concat (first l) (next l)) r)
      :else             (recur (next l) (conj r (first l))))))

(defn- nil-binding [binding]
  "generates a nil assignment for all lhs symbols in a binding"
  (interleave (filter symbol? (reflat binding))
              (repeat nil)))

;; eliminate the need to refer to both core.async and chantrix.async
(potemkin/import-vars [clojure.core.async
                       go
                       go-loop
                       thread
                       <!
                       >!
                       alts!
                       alts!!
                       chan
                       timeout
                       promise-chan
                       buffer
                       dropping-buffer
                       sliding-buffer
                       split
                       mult
                       tap
                       untap
                       untap-all
                       offer!
                       put!
                       poll!
                       pipe
                       <!!
                       >!!])

(defn close!
  "closes a channel, optionally freeing up any parked writers
  core.async/close! does not unblock writers
  :drain? - drain the channel/free up writers? (default false)"
  [ch & kwargs]
  (let [{:keys [drain?] :or {drain? false}} kwargs]
    (when (satisfies? async-impl/Channel ch)
      (clojure.core.async/close! ch)
      (when (and drain? (satisfies? async-impl/ReadPort ch))
        (while (poll! ch))))))

(defmacro <!?
  "throwing channel take
  . assumes that exceptions are placed on the in-band channel
  . throws when any are encountered
  . use only within a go block"
  [ch]
  `(let [e# (<! ~ch)]
     (if (instance? Exception e#)
       (throw e#)
       e#)))

(defn <!!?
  "throwing channel take, thread-blocking
  . assumes that exceptions are placed on the in-band channel
  . throws when any are encountered"
  [ch]
  (let [e (<!! ch)]
    (if (instance? Exception e)
      (throw e)
      e)))

(defmacro alts!?
  "throwing channel alts
  . throws if an exception is encountered as a result of any channel
  . use only within a go block"
  [chs & kwargs]
  `(let [[v# :as r#] (alts! ~chs ~@kwargs)]
     (if (instance? Exception v#)
       (throw v#)
       r#)))

(defn alts!!?
  "throwing channel alts, thread blocking
  throws if an exception is encountered as a result of any channel"
  [chs & kwargs]
  (let [[v :as r] (apply alts!! chs kwargs)]
     (if (instance? Exception v)
       (throw v)
       r)))

(defn onto
  "a replacement for async/onto-chan that doesn't suffer from
  the lack of a 'locals clearing' optimization in core.async
  aliases the sequence as a volatile, so clojure can clear it,
  preventing the reference from holding the head"
  ([ch s]
    (onto ch s true))
  ([ch s close?]
    (let [s (volatile! s)]
      (go-loop []
        (let [x (first @s)]
          (if (some? x)
            (when (>! ch x)
              (vswap! s next)
              (recur))
            (when close?
              (close! ch :drain? false))))))))

(defn onto-chan
  "returns a channel that will contain the contents of
  a list, and which will close after all elements are written,
  using the improved onto-chan above"
  [s & args]
  (let [ch (apply chan args)]
    (onto ch s)
    ch))

(defn to-seq
  "takes all elements from a channel, and returns them
  as a lazy sequence"
  [ch]
  (let [x (and ch (<!! ch))]
    (when (some? x)
      (cons x (lazy-seq (to-seq ch))))))

(defn tap-chan
  "similar to tap, but creates and returns the input channel"
  [m & args]
  (let [ch (apply chan args)]
    (tap m ch)
    ch))

(defn pipe-chan
  "creates a new channel that contains the piped and optionally
  buffered/transduced output of the specified channel"
  [ch-in & args]
  (let [ch-out (apply chan args)]
    (pipe ch-in ch-out)
    ch-out))



;;; go process utilities

(defmacro when<!
  "conditional wrapper for channel take"
  [[binding ch] & then]
  `(let [r# (<! ~ch)]
     (when (some? r#)
       (let [~binding r#]
         ~@then))))

(defmacro when<!?
  "conditional wrapper for channel take with try/throw"
  [[binding ch] & then]
  `(let [r# (<! ~ch)]
     (when (some? r#)
       (if (instance? Exception r#)
         (let [~@(nil-binding binding)]
           (try
             (throw r#)
             ~@(filter except-form? then)))
         (let [~binding r#]
           (try
             ~@then))))))

(defmacro if<!
  "conditional wrapper for channel take with alternative"
  [[binding ch] then else]
  `(let [r# (<! ~ch)]
     (if (some? r#)
       (let [~binding r#]
         ~then)
       ~else)))

(defmacro if<!?
  "conditional wrapper for channel take with alternative and try/throw"
  [[binding ch] then else & except]
  (when (not-every? except-form? except)
    (throw (ex-info "only catch and finally are allowed in the except form"
                    {})))
  `(let [r# (<! ~ch)]
     (if (some? r#)
       (if (instance? Exception r#)
         (let [~@(nil-binding binding)]
           (try
             (throw r#)
             ~@except))
         (let [~binding r#]
           (try
             ~then
             ~@except)))
       ~else)))

(defmacro while<!
  "drains a channel, invoking body for each message"
  [[binding ch] & body]
  `(let [ch# ~ch]
     (loop []
       (when<! [~binding ch#]
         ~@body
         (recur)))))

(defmacro while<!?
  "drains a channel, invoking body for each message, with try/throw"
  [[binding ch] & body]
  `(let [ch# ~ch]
     (loop []
       (when<! [r# ch#]
         (if (instance? Exception r#)
           (let [~@(nil-binding binding)]
             (try
               (throw r#)
               ~@(filter except-form? body)))
           (let [~binding r#]
             (try
               ~@body)))
         (recur)))))

(defmacro when-alts!
  "conditional wrapper for alt take"
  [[binding chs & kwargs] & then]
  `(let [[v# :as r#] (alts! ~chs ~@kwargs)]
     (when (some? v#)
       (let [~binding r#]
         ~@then))))

(defmacro when-alts!?
  "conditional wrapper for alt take with try/throw"
  [[binding chs & kwargs] & then]
  `(let [[v# :as r#] (alts! ~chs ~@kwargs)]
     (when (some? v#)
       (if (instance? Exception v#)
         (let [~@(nil-binding binding)]
           (try
             (throw v#)
             ~@(filter except-form? then)))
         (let [~binding r#]
           (try
             ~@then))))))

(defmacro if-alts!
  "conditional wrapper for alt take with alternative"
  [[binding chs & kwargs] then else]
  `(let [[v# :as r#] (alts! ~chs ~@kwargs)]
     (if (some? v#)
       (let [~binding r#]
         ~then)
       ~else)))

(defmacro if-alts!?
  "conditional wrapper for alt take with alternative and try/throw"
  [[binding chs & kwargs] then else & except]
  (when (not-every? except-form? except)
    (throw (ex-info "only catch and finally are allowed in the except form"
                    {})))
  `(let [[v# :as r#] (alts! ~chs ~@kwargs)]
     (if (some? v#)
       (if (instance? Exception v#)
         (let [~@(nil-binding binding)]
           (try
             (throw v#)
             ~@except))
         (let [~binding r#]
           (try
             ~then
             ~@except)))
       ~else)))

(defmacro while-alts!
  "drains a set of alts, invoking body for each message
  stops the enumeration when any one of the channels is closed"
  [[binding chs & kwargs] & body]
  `(let [chs# ~chs]
     (loop []
       (when-alts! [~binding chs# ~@kwargs]
         ~@body
         (recur)))))

(defmacro while-alts!?
  "drains a set of alts, invoking body for each message, with try/throw
  stops the enumeration when any one of the channels is closed"
  [[binding chs & kwargs] & body]
  `(let [chs# ~chs]
     (loop []
       (when-alts! [[v# :as r#] chs# ~@kwargs]
         (if (instance? Exception v#)
           (let [~@(nil-binding binding)]
             (try
               (throw v#)
               ~@(filter except-form? body)))
           (let [~binding r#]
             (try
               ~@body)))
         (recur)))))

(defmacro go-let
  "runs a go block with a set of let bindings"
  [bindings & body]
  `(go
     (let ~bindings
       ~@body)))

(defmacro go-try
  "runs a go block wrapped within a try"
  [& body]
  `(go
     (try
      ~@body)))

(defmacro go-catch
  "runs a go block that catches and returns any exceptions
  callers may retrieve these exceptions by taking from the go channel"
  [& body]
  `(go-try
     ~@body
     (catch Exception e#
       e#)))

(defmacro go-seq
  "runs a go block that enumerates over a sequence
  doseq analog of go-loop
  uses volatiles to avoid holding on to the head of
  the sequence across the go boundary"
  [[binding s] & body]
  (if (symbol? s)
    `(let [~s (volatile! ~s)]
       (go-loop []
         (let [r# (first @~s)]
           (when (some? r#)
             (let [~binding r#]
               ~@body
               (vswap! ~s next))
             (recur)))))
    `(let [s# ~s]
       (go-seq [~binding s#]
         ~@body))))

(defmacro go-each
  "starts a sequence of go blocks, one after the other,
  waiting for each to complete, and writing the
  results to the resulting channel in order
  if any exceptions are thrown/returned from a block,
  the process is halted, and the exception is written
  to the output channel"
  [& forms]
  (let [ch (gensym)]
    `(let [~ch (chan ~(count forms))]
       (go-try
         ~@(for [form forms]
             `(when<!? [r# (go-catch ~form)]
                (>! ~ch r#)))
         (catch Exception e#
           (>! ~ch e#))
         (finally
           (close! ~ch :drain? false)))
       ~ch)))

(defmacro go-all
  "starts a sequence of go blocks all at once,
  waiting for all to complete, and writing the
  results to the resulting channel, in order
  exceptions are returned when a block throws,
  but they do not halt the process"
  [& forms]
  `(let [ch# (chan ~(count forms))]
     (go-let [gs# [~@(for [form forms]
                       `(go-catch ~form))]]
       (doseq [g# gs#]
         (when<! [r# g#]
           (>! ch# r#)))
       (close! ch# :drain? false))
     ch#))

(defmacro go-as->
  "threads the asynchronous result of a sequence
  of go blocks, binding the taken result from
  each as name, similar to as->
  returns the result as the outer go block
  if an exception is thrown/returned, the process
  is halted, and the exception is returned"
  [name & forms]
  `(go-catch
     (let [~name nil
           ~@(interleave (repeat name)
                         (for [form forms]
                           `(<!? (go-catch ~form))))]
       ~name)))

(defmacro go-while<!
  "runs a go block that enumerates over a channel
  stops the enumeration when the channel is closed"
  [[binding ch] & body]
  `(let [ch# ~ch]
     (go
       (while<! [~binding ch#]
         ~@body))))

(defmacro go-while<!?
  "runs a go block that enumerates over a channel
  stops the enumeration when the channel is closed
  throws if an exception is read off the channel"
  [[binding ch] & body]
  `(let [ch# ~ch]
     (go
       (while<!? [~binding ch#]
         ~@body))))

(defmacro go-while-alts!
  "runs a go block that enumerates over a set of alts
  stops the enumeration when any one of the channels is closed"
  [[binding chs & kwargs] & body]
  `(let [chs# ~chs]
     (go
       (while-alts! [~binding chs# ~@kwargs]
         ~@body))))

(defmacro go-while-alts!?
  "runs a go block that enumerates over a set of alts
  stops the enumeration when any one of the channels is closed
  throws if an exception is read off the channel"
  [[binding chs & kwargs] & body]
  `(let [chs# ~chs]
     (go
       (while-alts!? [~binding chs# ~@kwargs]
         ~@body))))



;;; thread process utilities

(defmacro when<!!
  "conditional wrapper for channel take"
  [[binding ch] & then]
  `(let [r# (<!! ~ch)]
     (when (some? r#)
       (let [~binding r#]
         ~@then))))

(defmacro when<!!?
  "conditional wrapper for channel take with try/throw"
  [[binding ch] & then]
  `(let [r# (<!! ~ch)]
     (when (some? r#)
       (if (instance? Exception r#)
         (let [~@(nil-binding binding)]
           (try
             (throw r#)
             ~@(filter except-form? then)))
         (let [~binding r#]
           (try
             ~@then))))))

(defmacro if<!!
  "conditional wrapper for channel take with alternative"
  [[binding ch] then else]
  `(let [r# (<!! ~ch)]
     (if (some? r#)
       (let [~binding r#]
         ~then)
       ~else)))

(defmacro if<!!?
  "conditional wrapper for channel take with alternative and throw"
  [[binding ch] then else & except]
  (when (not-every? except-form? except)
    (throw (ex-info "only catch and finally are allowed in the except form"
                    {})))
  `(let [r# (<!! ~ch)]
     (if (some? r#)
       (if (instance? Exception r#)
         (let [~@(nil-binding binding)]
           (try
             (throw r#)
             ~@except))
         (let [~binding r#]
           (try
             ~then
             ~@except)))
       ~else)))

(defmacro while<!!
  "drains a channel, invoking body for each message"
  [[binding ch] & body]
  `(let [ch# ~ch]
     (loop []
       (when<!! [~binding ch#]
         ~@body
         (recur)))))

(defmacro while<!!?
  "drains a channel, invoking body for each message, with try/throw"
  [[binding ch] & body]
  `(let [ch# ~ch]
     (loop []
       (when<!! [r# ch#]
         (if (instance? Exception r#)
           (let [~@(nil-binding binding)]
             (try
               (throw r#)
               ~@(filter except-form? body)))
           (let [~binding r#]
             (try
               ~@body)))
         (recur)))))

(defmacro when-alts!!
  "conditional wrapper for alt take"
  [[binding chs & kwargs] & then]
  `(let [[v# :as r#] (alts!! ~chs ~@kwargs)]
     (when (some? v#)
       (let [~binding r#]
         ~@then))))

(defmacro when-alts!!?
  "conditional wrapper for alt take with try/throw"
  [[binding chs & kwargs] & then]
  `(let [[v# :as r#] (alts!! ~chs ~@kwargs)]
     (when (some? v#)
       (if (instance? Exception v#)
         (let [~@(nil-binding binding)]
           (try
             (throw v#)
             ~@(filter except-form? then)))
         (let [~binding r#]
           (try
             ~@then))))))

(defmacro if-alts!!
  "conditional wrapper for alt take with alternative"
  [[binding chs & kwargs] then else]
  `(let [[v# :as r#] (alts!! ~chs ~@kwargs)]
     (if (some? v#)
       (let [~binding r#]
         ~then)
       ~else)))

(defmacro if-alts!!?
  "conditional wrapper for alt take with alternative and try/throw"
  [[binding chs & kwargs] then else & except]
  (when (not-every? except-form? except)
    (throw (ex-info "only catch and finally are allowed in the except form"
                    {})))
  `(let [[v# :as r#] (alts!! ~chs ~@kwargs)]
     (if (some? v#)
       (if (instance? Exception v#)
         (let [~@(nil-binding binding)]
           (try
             (throw v#)
             ~@except))
         (let [~binding r#]
           (try
             ~then
             ~@except)))
       ~else)))

(defmacro while-alts!!
  "drains a set of alts, invoking body for each message
  stops the enumeration when any one of the channels is closed"
  [[binding chs & kwargs] & body]
  `(let [chs# ~chs]
     (loop []
       (when-alts!! [~binding chs# ~@kwargs]
         ~@body
         (recur)))))

(defmacro while-alts!!?
  "drains a set of alts, invoking body for each message, with try/throw
  stops the enumeration when any one of the channels is closed"
  [[binding chs & kwargs] & body]
  `(let [chs# ~chs]
     (loop []
       (when-alts!! [[v# :as r#] chs# ~@kwargs]
         (if (instance? Exception v#)
           (let [~@(nil-binding binding)]
             (try
               (throw v#)
               ~@(filter except-form? body)))
           (let [~binding r#]
             (try
               ~@body)))
         (recur)))))

(defmacro thread-loop
  "runs a loop within a thread block"
  [bindings & body]
  `(thread
     (loop ~bindings
       ~@body)))

(defmacro thread-let
  "runs a thread block with a set of let bindings"
  [bindings & body]
  `(thread
     (let ~bindings
       ~@body)))

(defmacro thread-try
  "runs a thread block wrapped within a try"
  [& body]
  `(thread
     (try
       ~@body)))

(defmacro thread-catch
  "runs a thread block that catches and returns any exceptions
  callers may retrieve these exceptions by taking from the thread channel"
  [& body]
  `(thread-try
     ~@body
     (catch Exception e#
       e#)))

(defmacro thread-seq
  "runs a thread block that enumerates over a sequence
  doseq analog of thread-loop"
  [[binding s] & body]
  `(thread-loop [s# ~s]
     (let [r# (first s#)]
       (when (some? r#)
         (let [~binding r#]
           ~@body)
         (recur (next s#))))))

(defmacro thread-each
  "starts a sequence of thread blocks, one after the other,
  waiting for each to complete, and writing the
  results to the resulting channel in order
  if any exceptions are thrown/returned from a block,
  the process is halted, and the exception is written
  to the output channel"
  [& forms]
  (let [ch (gensym)]
    `(let [~ch (chan ~(count forms))]
       (go-try
         ~@(for [form forms]
             `(when<!? [r# (thread-catch ~form)]
                (>! ~ch r#)))
         (catch Exception e#
           (>! ~ch e#))
         (finally
           (close! ~ch :drain? false)))
       ~ch)))

(defmacro thread-all
  "starts a sequence of thread blocks all at once,
  waiting for all to complete, and writing the
  results to the resulting channel, in order
  exceptions are returned when a block throws,
  but they do not halt the process"
  [& forms]
  `(let [ch# (chan ~(count forms))]
     (go-let [gs# [~@(for [form forms]
                       `(thread-catch ~form))]]
       (doseq [g# gs#]
         (when<! [r# g#]
           (>! ch# r#)))
       (close! ch# :drain? false))
     ch#))

(defmacro thread-as->
  "threads the asynchronous result of a sequence
  of thread blocks, binding the taken result from
  each as name, similar to as->
  returns the result as the outer go block
  if an exception is thrown/returned, the process
  is halted, and the exception is returned"
  [name & forms]
  `(go-catch
     (let [~name nil
           ~@(interleave (repeat name)
                         (for [form forms]
                           `(<!? (thread-catch ~form))))]
       ~name)))

(defmacro thread-while<!!
  "runs a thread block that enumerates over a channel
  stops the enumeration when the channel is closed"
  [[binding ch] & body]
  `(let [ch# ~ch]
     (thread
       (while<!! [~binding ch#]
         ~@body))))

(defmacro thread-while<!!?
  "runs a thread block that enumerates over a channel
  stops the enumeration when the channel is closed
  throws if an exception is read off the channel"
  [[binding ch] & body]
  `(let [ch# ~ch]
     (thread
       (while<!!? [~binding ch#]
         ~@body))))

(defmacro thread-while-alts!!
  "runs a thread block that enumerates over a set of alts
  stops the enumeration when any one of the channels is closed"
  [[binding chs & kwargs] & body]
  `(let [chs# ~chs]
     (thread
       (while-alts!! [~binding chs# ~@kwargs]
         ~@body))))

(defmacro thread-while-alts!!?
  "runs a thread block that enumerates over a set of alts
  stops the enumeration when any one of the channels is closed
  throws if an exception is read off the channel"
  [[binding chs & kwargs] & body]
  `(let [chs# ~chs]
     (thread
       (while-alts!!? [~binding chs# ~@kwargs]
         ~@body))))



;;; custom channel types

(defn distinct-buffer
  "creates a blocking buffer with a maximum size,
  which discards duplicate elements"
  [size]
  (let [s (atom #{})
        b (buffer size)]
    (reify
      async-impl/Buffer
        (full? [_]
          (.full? b))
        (remove! [_]
          (when-let [v (.remove! b)]
            (swap! s disj v)
            v))
        (close-buf! [_]
          (reset! s #{})
          (.close-buf! b))
        (add!* [_ v]
          (when-not (@s v)
            (swap! s conj v)
            (.add!* b v)))
      Counted
        (count [_]
          (.count b)))))

(defn interval
  "creates a channel that yields a value every interval milliseconds
  close the channel to stop the values"
  ([ms]
    (interval ms :tick))
  ([ms value]
    (let [ch (chan)]
      (go-loop []
        (<! (timeout ms))
        (when (>! ch value)
          (recur)))
      ch)))

(defn semaphore
  "a simple countdown channel, used to implement an
  asynchronous semaphore without storing a list of items
  . to acquire, take an item off the channel
  . to release, put an item back on the channel"
  [limit]
  (let [counter (atom limit)]
    (chan (reify
            async-impl/Buffer
              (full? [_]
                (= @counter limit))
              (remove! [_]
                (swap! counter dec))
              (close-buf! [_]
                nil)
              (add!* [_ _]
                (swap! counter inc))
            Counted
              (count [_]
                @counter)))))
