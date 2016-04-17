# chantrix

<img src="logo.png" align="left" height="240px" />

Chantrix builds a bidirectional asynchronous communication idiom, the
junction, on top of the [core.async](https://github.com/clojure/core.async)
channel abstraction. Stackable channel junctions support composable
client-side middleware for request/response protocols such as HTTP. Junctions
further enable feedback control mechanisms for asynchronous programming,
such as [Hystrix](https://github.com/Netflix/Hystrix)-style backpressure.

![](https://clojars.org/chantrix/latest-version.svg)

[API Reference](http://bspell1.github.io/docs/chantrix/codox/index.html)
|
[Marginalia](http://bspell1.github.io/docs/chantrix/marginalia/uberdoc.html)

### junctions
A junction is simply a pair of core.async channels. Requestor and dispatcher
layers sit on either side of a junction. Requestors write requests onto the
first channel in the junction and read responses from the second. Dispatchers
read from the first channel, dispatch requests, and write responses to the
second.

```clojure
(require '[chantrix
           [async :refer :all]
           [junction :as junc :refer [junc]]])

(defn squarer
  []
  (let [[nums sqrs :as j] (junc)]
    (go-while<! [n nums]
      (>! sqrs (* n n)))
    j))

(let [[nums sqrs :as j] (squarer)]
  (doseq [i (range 10)]
    (>!! nums i)
    (println (<!! sqrs)))
  (junc/close! j))
```

This example starts up an asynchronous `squarer` process that reads a number
from its input channel, squares it, and writes the results to its output
channel. The squarer process allocates and returns the junction used to
communicate with it. The client synchronously sends a sequence of numbers to
the squarer and prints the responses.

This is obviously a silly and contrived example, and it would be trivial
to implement the squarer process using an indepent pair of channels. However,
the junction abstraction provides explicit control over both sides of
communication with an asynchronous process. This enables composable
middleware that can correlate requests with responses. For example, we can
independently inject faults into our squarer process through junction
composition.

```clojure
(defn faulter
  [base]
  (let [this          (junc)
        [>base <base] base
        [<this >this] this]
    (go-while<! [request <this]
      (if (< (rand) 0.1)
        (>! >this (ex-info "faulted" {:request request}))
        (>! >base request)))
    (pipe <base >this)
    this))
```

The `faulter` process above randomly chooses either to send incoming requests
to the base squarer process, or to deliver an exception to its output
channel, bypassing the base process. Successful responses are piped directly
to the output channel. The client composes the faulter middleware atop the
base squarer process.

We can then introduce retry middleware to compensate for transient faults
injected by our faulter process. The retryer takes advantage of the junction
abstraction by resending failed requests to the base process.

```clojure
(defn retryer
  [base]
  (let [this          (junc)
        [>base <base] base
        [<this >this] this]
    (pipe <this >base)
    (go-while<! [response <base]
      (if (instance? Exception response)
        (>! >base (:request (ex-data response)))
        (>! >this response)))
    this))
```

Junctions support many of the same operations as ordinary channels, such as
transducers, buffers, piping, and multiplexing. The following example
creates a buffered, transduced junction, and pipes its input to its output.

```clojure
(require '[chantrix
           [async :refer :all]
           [junction :as junc :refer [junc]]])

(let [[>j <j] (junc :>buf   3
                    :<buf   5
                    :>xform (map inc)
                    :<xform (map -))]
  (pipe >j <j)
  (onto >j [1 2 3])
  (to-seq <j))
```

In many cases, it is desirable to route all inbound traffic to a process
through a single junction. For example, rate limiting requests to a remote
service requires that all requests are metered through a single channel.
However, doing so makes it awkward to correlate responses to the original
request state/client connection.

The junction abstraction provides a `context` mechanism to address this
problem. A context junction tags incoming requests and uses the tagged
identifier to exclude responses that do not correspond to those requests.
This allows a junction-based client to create a "connection" to an
asynchronous process.

For example, consider an async http process that sends requests to a remote
web service and delivers responses using junctions.

```clojure
(require '[org.httpkit.client :as http]
         '[chantrix
           [async :refer :all]
           [junction :as junc :refer [junc]]])

(def http
  (let [[<http >http :as http] (junc)]
    (go-while<! [request <http]
      (http/request request
                    (fn [response]
                      (>!! >http response))))
    (junc/mult http)))

(let [[>ctx <ctx] (junc/context http [:context] [:opts :context])]
  (>!! >ctx {:url "https://api.github.com/users/richhickey/keys"})
  (<!! <ctx))
```

Here, the http process returns a multiplexed junction, whose output side
can be tapped by multiple readers. The context function creates a junction
that attaches a context identifier to all requests and filters responses
containing that identifier. For httpkit, the request for a response is
returned in the `:opts` key.

This sequential request/response pattern is so common that the junction api
provides a `transact!` function (or `>!<` sugar) to write to one side and
read from the other. The blocking client above could be rewritten as follows
to use this idiom.

```clojure
(let [ctx (junc/context http [:context] [:opts :context])]
  (>!!< ctx {:url "https://api.github.com/users/richhickey/keys"}))
```

### feedback control
The composability and response correlation of channel junctions lay the
groundwork for feedback control middleware. These components can actively
monitor the channels and apply heuristics to control the distribution of
traffic.

#### request governor
The governor component limits the number of outstanding requests allowed
in a junction at a given time. A simple countdown semaphore is used to
enforce the request limit.

In the example below, the dispatcher pulls requests off the queue as fast as
possible, and starts a process to output each after a delay. The governor
limits the number of requests that can be dequeued until the semaphore
has been replenished (by putting responses on the output channel). The
effect is that responses are printed in batches of 10.

```clojure
(require '[chantrix
           [async :refer :all]
           [junction :as junc :refer [junc]]
           [control :refer [governor]]])

(defn dispatcher
  []
  (let [[<j >j :as j] (junc)]
    (go-each
      (while<! [request <j]
        (go
          (<! (timeout 1000))
          (>! >j request)))
      (close! >j))
    j))

(let [[>j <j] (-> (dispatcher) (governor :limit 10))]
  (onto >j (range 100))
  (while<!! [response <j]
    (println response)))
```

#### todo
* hystrix-style backpressure
* fault injection

### integration
#### httpkit/cheshire
Chantrix includes a junction-based HTTP client built using the
[httpkit](http://www.http-kit.org/client.html) library, with transducers for
JSON parsing/formatting using [cheshire](https://github.com/dakrone/cheshire).
Below is an example of using the httpkit stack with the transducers for JSON
body encoding and mapping failed requests to exceptions. It also uses the
built-in context wrapper for correlating responses with requests.

```clojure
(require '[chantrix
           [async :refer :all]
           [junction :as junc :refer [>!!<]]]
         '[chantrix.http.kit :as http])

(def http
  (-> (http/start :opts   {:timeout 10000}
                  :>xform (map (http/json-request :keywords? true))
                  :<xform (comp (map (http/json-response :keywords? true))
                                (map (http/exception-response))))
      (junc/mult)))

(let [connect (http/context http)]
  (>!!< connect {:url "https://api.github.com/users/richhickey/keys"}))
```

### async extensions
Chantrix wraps the core.async API, removing the deprecated functions/macros
and those that conflict with clojure.core. It also provides a few extensions
for common patterns.

#### draining `close!`
This implementation of close! provides an option to unblock the channel
writer by polling the channel. The default behavior is the same as
core.async/close!.

```clojure
(require '[chantrix.async :refer :all])

(let [ch (chan)]
  (go
    (>! ch 1)
    (>! ch 2)
    (>! ch 3)
    (println "done"))
  (println (<!! ch))
  (close! ch :drain? true))
```

#### throwing takes
`<!? <!!? alts!? alts!!?`

These macros were inspired by David Nolan's <?
[macro](http://swannodette.github.io/2013/08/31/asynchronous-error-handling/).
They all throw if an exception is returned from a take/alts.

```clojure
(require '[chantrix.async :refer :all])

(let [ch (chan)]
  (try
    (<!!? (go
            (ex-info "failed" {})))
    (println "succeeded")
    (catch Exception e
      e)))
```

#### taking conditionals
`when<! when<!! when<!? when<!!? when-alts! when-alts!! when-alts!?
when-alts!!?`

`if<! if<!! if<!? if<!!? if-alts! if-alts!! if-alts!? if-alts!!?`

`while<! while<!! while<!? while<!!? while-alts! while-alts!! while-alts!?
while-alts!!?`

Conditional variants of take macros, with the same semantics as the
corresponding if/when/while macros in Clojure. All versions accept a
`[binding channel]` vector as a first argument, which binds the result
of the take from `channel` to `binding`.

```clojure
(require '[chantrix.async :refer :all])

(let [ch (chan)]
  (go
    (while<! [x ch]
      (println x)))
  (onto ch [1 2 3 4 5]))
```

The `?` versions will throw if an exception is taken off the channel,
or if an exception is thrown by the body of the form, preventing an
asynchronous block from throwing out to the go/thread boundary.
`catch`/`finally` forms may be embedded within the taking form to handle
these exceptions.

```clojure
(require '[chantrix.async :refer :all])

(let [ch (chan)]
  (go
    (while<!? [x ch]
      (when (= x 3)
        (throw (ex-info "failed-3" {})))
      (println x)
      (catch Exception e
        (println e))))
  (onto ch [1 2 3 (ex-info "failed-4" {}) 5]))
```

#### convenience go/thread forms
`go-let` `go-try` `go-seq` `go-catch`

`thread-let` `thread-try` `thread-seq` `thread-catch`

The first three are analogs of the corresponding `let`, `try`, and `doseq`
forms in core. `go-catch` provides the ability to catch any exception that
is thrown in the go block, and return it as the output of the channel.

#### composition forms
`go-each` `go-all` `go-as->`

`thread-each` `thread-all` `thread-as->`

`go-each` executes a sequence of `go-catch` blocks one after the other,
waiting for each to return before starting the next. The results of each form
are written to the returned output channel, in order. `go-each` is commonly
used to perform a cleanup operation after taking all elements off a channel.

```clojure
(require '[chantrix.async :refer :all])

(let [ch1 (chan)
      ch2 (chan)]
  (go-each
    (while<! [x ch1]
      (>! ch2 x))
    (close! ch2))
  (onto ch1 [1 2 3])
  (to-seq ch2))
```

`go-all` starts a sequence of `go-catch` blocks all at once. It then waits
for each to complete and writes the results to the returned output channel,
in the order in which the blocks appear in the form.

```clojure
(require '[chantrix.async :refer :all])

(let [ch (go-all
           1
           (<! (thread 2))
           (throw (ex-info "failed-2" {}))
           (last (take 10000000 (iterate inc 0))))]
  (while<!! [x ch]
    (println x)))
```
`go-as->` is an analog of the `as->` macro in core. It repeatedly binds a name
to the result of each form wrapped in a `go-catch` block. The forms are
started sequentially, as with `go-each`. The result of `go-as->` is the
result of the final form. If an exception is thrown by any form, it is
returned as the result of the block, and no further processing is attempted.

```clojure
(require '[chantrix.async :refer :all])

(<!! (go-as-> x
       1
       (<! (thread (inc x)))
       (last (take (* 10000000 x) (iterate inc 0)))))
```

#### taking enumerators
`go-while<!` `go-while<!?` `go-while-alts!` `go-while-alts!?`

`thread-while<!!` `thread-while<!!?` `thread-while-alts!!` `thread-while-alts!!?`

These macros simply wrap the corresponding `while` macro with a `go` block,
allowing the user to write fewer forms for an asynchronous looping process.

#### custom channel `interval`
This channel is repeated analog of the `timeout` channel type, producing
a value every `ms` milliseconds. It stops producing values once it is closed.

```clojure
(require '[chantrix.async :refer :all])

(let [ch (interval 1000)]
  (go-while<! [_ ch]
    (println "tick"))
  (<!! (timeout 5000))
  (close! ch))
```

#### custom channel `semaphore`
The semaphore pseudo-channel implements a mechanism for blocking once the
configured limit of items has been taken and not yet released. Nothing is
actually written to the buffer, and its state is a single counter.

```clojure
(require '[chantrix.async :refer :all])

(let [ch  (chan)
      sem (semaphore 2)]
  (dotimes [i 10]
    (go-while<! [x ch]
      (<! sem)
      (println x)
      (<! (timeout 1000))
      (>! sem :release)))
  (onto ch (range 10)))
```

## license

Special thanks to OpenTable for sponsoring
[this and other](https://github.com/OpenTable) open source projects

Distributed under the Eclipse Public License, the same as Clojure.
