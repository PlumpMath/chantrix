(ns ^:unit chantrix.junction-test
  (:import [clojure.lang ExceptionInfo])
  (:require [clojure.test :refer :all]
            [chantrix.async :as async :refer [>! <! <!? >!! <!! <!!?]]
            [chantrix.junction :refer :all]))

(deftest junc-create
  (testing "default junction"
    (let [[>ch <ch] (junc)]
      (async/pipe >ch <ch)
      (async/onto >ch [1 2 3])
      (is (= (async/to-seq <ch)
             [1 2 3]))))

  (testing "buffered junction"
    (let [[>ch <ch] (junc :>buf 3 :<buf 2)]
      (>!! >ch 1)
      (>!! >ch 2)
      (>!! >ch 3)
      (is (not (async/offer! >ch 4)))
      (>!! <ch (<!! >ch))
      (>!! <ch (<!! >ch))
      (is (not (async/offer! <ch 3))))
    (let [[>ch <ch] (junc :buf 3)]
      (>!! >ch 1)
      (>!! >ch 2)
      (>!! >ch 3)
      (is (not (async/offer! >ch 4)))
      (>!! <ch (<!! >ch))
      (>!! <ch (<!! >ch))
      (>!! <ch (<!! >ch))
      (is (not (async/offer! <ch 4)))))

  (testing "transduced junction"
    (let [[>ch <ch] (junc :>xform (map inc) :<xform (map -))]
      (async/pipe >ch <ch)
      (async/onto >ch [1 2 3])
      (is (= (async/to-seq <ch)
             [-2 -3 -4]))))

  (testing "buffered transduced junction"
    (let [[>ch <ch] (junc :buf 3 :>xform (map inc) :<xform (map -))]
      (>!! >ch 1)
      (>!! >ch 2)
      (>!! >ch 3)
      (is (not (async/offer! >ch 4)))
      (>!! <ch (<!! >ch))
      (>!! <ch (<!! >ch))
      (>!! <ch (<!! >ch))
      (is (not (async/offer! <ch 4)))
      (is (= (<!! <ch) -2))
      (is (= (<!! <ch) -3))
      (is (= (<!! <ch) -4)))))

(deftest junc-close!
  (testing "invalid close"
    (close! nil)
    (close! [])
    (close! [nil])
    (close! [nil nil])
    (close! [nil (async/chan)]))

  (testing "valid close"
    (close! (junc))
    (close! [nil (async/chan)])
    (close! [(async/chan) nil])))

(deftest junc-pipe
  (testing "default pipe"
    (let [[>ch <ch :as j] (junc)]
      (pipe j)
      (async/onto >ch [1 2 3])
      (is (= (async/to-seq <ch)
             [1 2 3])))
    (let [[>ch <ch] (pipe-junc)]
      (async/onto >ch [1 2 3])
      (is (= (async/to-seq <ch)
             [1 2 3]))))

  (testing "buffered pipe"
    (let [[>ch <ch :as j] (junc)]
      (pipe j :buf 3)
      (>!! >ch 1)
      (>!! >ch 2)
      (>!! >ch 3)
      (while (not (async/offer! >ch 0)))
      (is (= (<!! <ch) 1))
      (is (= (<!! <ch) 2))
      (is (= (<!! <ch) 3)))
    (let [[>ch <ch] (pipe-junc :buf 3)]
      (>!! >ch 1)
      (>!! >ch 2)
      (>!! >ch 3)
      (is (not (async/offer! >ch 4)))
      (is (= (<!! <ch) 1))
      (is (= (<!! <ch) 2))
      (is (= (<!! <ch) 3))))

  (testing "transduced pipe"
    (let [[>ch <ch :as j] (junc)]
      (pipe j :xform (map inc))
      (async/onto >ch [1 2 3])
      (is (= (async/to-seq <ch)
             [2 3 4])))
    (let [[>ch <ch] (pipe-junc :xform (map inc))]
      (async/onto >ch [1 2 3])
      (is (= (async/to-seq <ch)
             [2 3 4]))))

  (testing "buffered transduced pipe"
    (let [[>ch <ch :as j] (junc)]
      (pipe j :buf 3 :xform (map inc))
      (>!! >ch 1)
      (>!! >ch 2)
      (>!! >ch 3)
      (while (not (async/offer! >ch 0)))
      (is (= (<!! <ch) 2))
      (is (= (<!! <ch) 3))
      (is (= (<!! <ch) 4)))
    (let [[>ch <ch] (pipe-junc :buf 3 :xform (map inc))]
      (>!! >ch 1)
      (>!! >ch 2)
      (>!! >ch 3)
      (is (not (async/offer! >ch 4)))
      (is (= (<!! <ch) 2))
      (is (= (<!! <ch) 3))
      (is (= (<!! <ch) 4)))))

(deftest junc-xform
  (testing "identity xform"
    (let [j         (pipe-junc)
          [>ch <ch] (compose j)]
      (async/onto >ch [1 2 3])
      (is (= (async/to-seq <ch)
             [1 2 3]))))

  (testing "buffered xform"
    (let [j         (pipe-junc)
          [>ch <ch] (compose j :buf 3)]
      (>!! >ch 1)
      (>!! >ch 2)
      (>!! >ch 3)
      (while (not (async/offer! >ch 0)))
      (is (= (<!! <ch) 1))
      (is (= (<!! <ch) 2))
      (is (= (<!! <ch) 3))))

  (testing "transduced xform"
    (let [j         (pipe-junc)
          [>ch <ch] (compose j :>xform (map inc) :<xform (map -))]
      (async/onto >ch [1 2 3])
      (is (= (async/to-seq <ch)
             [-2 -3 -4]))))

  (testing "buffered transduced xform"
    (let [j         (pipe-junc)
          [>ch <ch] (compose j :buf 3 :>xform (map inc))]
      (>!! >ch 1)
      (>!! >ch 2)
      (>!! >ch 3)
      (while (not (async/offer! >ch 0)))
      (is (= (<!! <ch) 2))
      (is (= (<!! <ch) 3))
      (is (= (<!! <ch) 4)))
    (let [j         (pipe-junc)
          [>ch <ch] (compose j :buf 3 :>xform (map inc) :<xform (map -))]
      (>!! >ch 1)
      (>!! >ch 2)
      (>!! >ch 3)
      (while (not (async/offer! >ch 0)))
      (is (= (<!! <ch) -2))
      (is (= (<!! <ch) -3))
      (is (= (<!! <ch) -4)))))

(deftest junc-mult-tap
  (testing "default mult/tap"
    (let [[>ch _ :as j] (mult (pipe-junc))
          [_   <|1|]    (pipe-tap j)
          [_   <|2|]    (pipe-tap j)]
      (async/onto >ch [1 2 3])
      (is (= (<!! <|1|) 1))
      (is (nil? (async/poll! <|1|)))
      (is (= (<!! <|2|) 1))
      (is (= (<!! <|2|) 2))
      (is (nil? (async/poll! <|2|)))
      (is (= (<!! <|1|) 2))
      (is (= (<!! <|1|) 3))
      (is (nil? (async/poll! <|1|)))
      (is (= (<!! <|2|) 3)))
    (let [[>ch _ :as j] (mult-pipe)
          [_   <|1|]    (pipe-tap j)
          [_   <|2|]    (pipe-tap j)]
      (async/onto >ch [1 2 3])
      (is (= (<!! <|1|) 1))
      (is (nil? (async/poll! <|1|)))
      (is (= (<!! <|2|) 1))
      (is (= (<!! <|2|) 2))
      (is (nil? (async/poll! <|2|)))
      (is (= (<!! <|1|) 2))
      (is (= (<!! <|1|) 3))
      (is (nil? (async/poll! <|1|)))
      (is (= (<!! <|2|) 3))))

  (testing "buffered mult/tap"
    (let [[>ch _ :as j] (mult (pipe-junc))
          [_   <|1|]    (pipe-tap j)
          [_   <|2|]    (pipe-tap j :buf 3)]
      (async/onto >ch [1 2 3])
      (is (= (<!! <|1|) 1))
      (is (= (<!! <|1|) 2))
      (is (= (<!! <|1|) 3))
      (is (= (<!! <|2|) 1))
      (is (= (<!! <|2|) 2))
      (is (= (<!! <|2|) 3)))
    (let [[>ch _ :as j] (mult-pipe :buf 3)
          [_   <|1|]    (pipe-tap j)
          [_   <|2|]    (pipe-tap j)]
      (async/>!! >ch 1)
      (async/>!! >ch 2)
      (async/>!! >ch 3)
      (while (async/offer! >ch 0))
      (is (= (<!! <|1|) 1))
      (is (= (<!! <|2|) 1))
      (is (= (<!! <|1|) 2))
      (is (= (<!! <|2|) 2))
      (is (= (<!! <|1|) 3))
      (is (= (<!! <|2|) 3))))

  (testing "transduced mult/tap"
    (let [[>ch _ :as j] (mult (pipe-junc))
          [_   <|1|]    (pipe-tap j :<xform (map inc))
          [_   <|2|]    (pipe-tap j :<xform (map dec))]
      (async/onto >ch [1 2 3])
      (is (= (<!! <|1|) 2))
      (is (= (<!! <|2|) 0))
      (is (= (<!! <|1|) 3))
      (is (= (<!! <|2|) 1))
      (is (= (<!! <|1|) 4))
      (is (= (<!! <|2|) 2)))
    (let [[>ch _ :as j] (mult-pipe :xform (map inc))
          [_   <|1|]    (pipe-tap j)
          [_   <|2|]    (pipe-tap j :<xform (map dec))]
      (async/onto >ch [1 2 3])
      (is (= (<!! <|1|) 2))
      (is (= (<!! <|2|) 1))
      (is (= (<!! <|1|) 3))
      (is (= (<!! <|2|) 2))
      (is (= (<!! <|1|) 4))
      (is (= (<!! <|2|) 3))))

  (testing "buffered transduced mult/tap"
    (let [[>ch _ :as j] (mult (pipe-junc))
          [_   <|1|]    (pipe-tap j :<xform (map inc))
          [_   <|2|]    (pipe-tap j :buf 3 :<xform (map dec))]
      (async/onto >ch [1 2 3])
      (is (= (<!! <|1|) 2))
      (is (= (<!! <|1|) 3))
      (is (= (<!! <|1|) 4))
      (is (= (<!! <|2|) 0))
      (is (= (<!! <|2|) 1))
      (is (= (<!! <|2|) 2)))
    (let [[>ch _ :as j] (mult-pipe :buf 3 :xform (map inc))
          [_   <|1|]    (pipe-tap j :<xform (map inc))
          [_   <|2|]    (pipe-tap j :buf 3 :<xform (map -))]
      (async/>!! >ch 1)
      (async/>!! >ch 2)
      (async/>!! >ch 3)
      (while (async/offer! >ch 0))
      (is (= (<!! <|1|) 3))
      (is (= (<!! <|1|) 4))
      (is (= (<!! <|1|) 5))
      (is (= (<!! <|2|) -2))
      (is (= (<!! <|2|) -3))
      (is (= (<!! <|2|) -4)))))

(deftest junc-transact
  (testing "go transact"
    (let [[>ch <ch :as j] (junc)]
      (async/go-while<! [x >ch]
        (>! <ch (if (instance? Exception x)
                  x
                  (inc x))))
      (is (= (<!! (async/go (transact! j 1))) 2))
      (is (= (<!! (async/go (transact! j 2))) 3))
      (is (= (<!! (async/go (transact! j 3))) 4))
      (is (thrown-with-msg? ExceptionInfo #"testing"
            (<!!? (async/go-catch
                    (transact! j (ex-info "testing" {}))
                    nil))))
      (close! j)
      (is (nil? (<!! (async/go (transact! j 1))))))
    (let [[>ch <ch :as j] (junc)]
      (async/go-while<! [x >ch]
        (>! <ch (if (instance? Exception x)
                  x
                  (inc x))))
      (is (= (<!! (async/go (>!< j 1))) 2))
      (is (= (<!! (async/go (>!< j 2))) 3))
      (is (= (<!! (async/go (>!< j 3))) 4))
      (is (thrown-with-msg? ExceptionInfo #"testing"
            (<!!? (async/go-catch
                    (>!< j (ex-info "testing" {}))
                    nil))))
      (close! j)
      (is (nil? (<!! (async/go (>!< j 1)))))))

  (testing "thread transact"
    (let [[>ch <ch :as j] (junc)]
      (async/go-while<! [x >ch]
        (>! <ch (if (instance? Exception x)
                  x
                  (inc x))))
      (is (= (transact!! j 1) 2))
      (is (= (transact!! j 2) 3))
      (is (= (transact!! j 3) 4))
      (is (thrown-with-msg? ExceptionInfo #"testing"
            (transact!! j (ex-info "testing" {}))))
      (close! j)
      (is (nil? (transact!! j 1))))
    (let [[>ch <ch :as j] (junc)]
      (async/go-while<! [x >ch]
        (>! <ch (if (instance? Exception x)
                  x
                  (inc x))))
      (is (= (>!!< j 1) 2))
      (is (= (>!!< j 2) 3))
      (is (= (>!!< j 3) 4))
      (is (thrown-with-msg? ExceptionInfo #"testing"
            (>!!< j (ex-info "testing" {}))))
      (close! j)
      (is (nil? (>!!< j 1))))))

(deftest junction-context
  (testing "success response context"
    (let [[>j <j :as j] (mult-pipe)]
      (dotimes [i 2]
        (let [[>c <c :as c] (context j)]
          (>!! >j {:test 0})
          (is (not (async/poll! <c)))
          (>!! >c {:test i})
          (is (= (dissoc (<!! <c) :context) {:test i}))
          (close! c)))
      (close! j))
    (let [[>j <j :as j] (junc)
          [>c <c :as c] (context (mult j) [:c] [:r :c])]
      (async/go-while<! [msg >j]
        (>! <j {:r msg}))
      (>!! >j {:test 0})
      (is (not (async/poll! <c)))
      (>!! >c {:test 1})
      (is (= (dissoc (:r (<!! <c)) :c) {:test 1}))
      (close! c)
      (close! j)))

  (testing "exception response context"
    (let [[>j <j :as j] (junc)
          [>c <c :as c] (context (mult j))]
      (async/go-while<! [msg >j]
        (>! <j (ex-info "testing" msg)))
      (>!! >j {:test 0})
      (is (not (async/poll! <c)))
      (>!! >c {:test 1})
      (is (thrown-with-msg? ExceptionInfo #"testing"
            (<!!? <c)))
      (close! c)
      (close! j))))
