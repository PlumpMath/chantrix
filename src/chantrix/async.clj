(ns chantrix.async
  "core.async wrapper and extensions for chantrix"
  (:import [clojure.lang Counted])
  (:require [clojure.core.async]
            [clojure.core.async.impl.protocols :as async-impl]))

;;; async wrappers/utilities

(defmacro ^:private wrap-lex
  [name impl]
  `(defmacro ~name [& args#] `(~'~impl ~@args#)))

(defmacro ^:private wrap-fun
  [name impl]
  `(def ~name ~impl))

(defn- except-form? [form]
  (when (seq? form)
    (#{'catch 'finally} (first form))))

(wrap-lex go clojure.core.async/go)
(wrap-lex go-loop clojure.core.async/go-loop)
(wrap-lex thread clojure.core.async/thread)
(wrap-lex <! clojure.core.async/<!)
(wrap-lex >! clojure.core.async/>!)
(wrap-lex alts! clojure.core.async/alts!)
(wrap-fun alts!! clojure.core.async/alts!!)
(wrap-fun chan clojure.core.async/chan)
(wrap-fun timeout clojure.core.async/timeout)
(wrap-fun promise-chan clojure.core.async/promise-chan)
(wrap-fun buffer clojure.core.async/buffer)
(wrap-fun dropping-buffer clojure.core.async/dropping-buffer)
(wrap-fun sliding-buffer clojure.core.async/sliding-buffer)
(wrap-fun split clojure.core.async/split)
(wrap-fun mult clojure.core.async/mult)
(wrap-fun tap clojure.core.async/tap)
(wrap-fun untap clojure.core.async/untap)
(wrap-fun untap-all clojure.core.async/untap-all)
(wrap-fun offer! clojure.core.async/offer!)
(wrap-fun poll! clojure.core.async/poll!)
(wrap-fun pipe clojure.core.async/pipe)
(wrap-fun <!! clojure.core.async/<!!)
(wrap-fun >!! clojure.core.async/>!!)

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
  `(try
     (let [r# (<!? ~ch)]
       (when (some? r#)
         (let [~binding r#]
           ~@(remove except-form? then))))
     ~@(filter except-form? then)))

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
  `(try
     (let [r# (<!? ~ch)]
       (if (some? r#)
         (let [~binding r#]
           ~then)
         ~else))
     ~@except))

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
         (try
           (when (instance? Exception r#)
             (throw r#))
           (let [~binding r#]
             ~@(remove except-form? body))
           ~@(filter except-form? body))
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
  `(try
     (let [[v# :as r#] (alts!? ~chs ~@kwargs)]
       (when (some? v#)
         (let [~binding r#]
           ~@(remove except-form? then))))
     ~@(filter except-form? then)))

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
  `(try
     (let [[v# :as r#] (alts!? ~chs ~@kwargs)]
       (if (some? v#)
         (let [~binding r#]
           ~then)
         ~else))
     ~@except))

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
         (try
           (when (instance? Exception v#)
             (throw v#))
           (let [~binding r#]
             ~@(remove except-form? body))
           ~@(filter except-form? body))
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
  `(try
     (let [r# (<!!? ~ch)]
       (when (some? r#)
         (let [~binding r#]
           ~@(remove except-form? then))))
     ~@(filter except-form? then)))

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
  `(try
     (let [r# (<!!? ~ch)]
       (if (some? r#)
         (let [~binding r#]
           ~then)
         ~else))
     ~@except))

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
         (try
           (when (instance? Exception r#)
             (throw r#))
           (let [~binding r#]
             ~@(remove except-form? body))
           ~@(filter except-form? body))
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
  `(try
     (let [[v# :as r#] (alts!!? ~chs ~@kwargs)]
       (when (some? v#)
         (let [~binding r#]
           ~@(remove except-form? then))))
     ~@(filter except-form? then)))

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
  `(try
     (let [[v# :as r#] (alts!!? ~chs ~@kwargs)]
       (if (some? v#)
         (let [~binding r#]
           ~then)
         ~else))
     ~@except))

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
         (try
           (when (instance? Exception v#)
             (throw v#))
           (let [~binding r#]
             ~@(remove except-form? body))
           ~@(filter except-form? body))
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
