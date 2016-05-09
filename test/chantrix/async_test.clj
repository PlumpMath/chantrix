(ns ^:unit chantrix.async-test
  (:import [clojure.lang ExceptionInfo])
  (:require [clojure.test :refer :all]
            [chantrix.async :refer :all]))

(deftest close-chan
  (testing "invalid close"
    (close! nil)
    (close! (buffer 10)))

  (testing "non-draining close"
    (let [ch (chan 3)]
      (>!! ch 1)
      (>!! ch 2)
      (>!! ch 3)
      (close! ch)
      (is (= (<!! ch) 1))
      (is (= (<!! ch) 2))
      (is (= (<!! ch) 3))))

  (testing "input graceful shutdown"
    (let [ch (chan)]
      (go
        (>! ch 1)
        (>! ch 2))
      (is (= (<!! ch) 1))
      (close! ch :drain? true)
      ;; reader should never block after close
      (is (nil? (<!! ch)))
      (is (nil? (<!! (go (<! ch)))))))

  (testing "output graceful shutdown"
    (let [ch1 (chan)
          ch2 (chan)]
      (go
        (>! ch1 1)
        (while (>! ch1 2))
        (>! ch2 :done))
      (is (= (<!! ch1) 1))
      (close! ch1 :drain? true)
      ;; writer should unblock after close
      (is (= (<!! ch2) :done)))))

(deftest take-throw
  (testing "<!?"
    (is (= (<!! (go (<!? (go 1)))) 1))
    (<!! (go
           (is (thrown-with-msg? ExceptionInfo #"testing"
                  (<!? (go (ex-info "testing" {}))))))))

  (testing "<!!?"
    (is (= (<!!? (go 1)) 1))
    (is (thrown-with-msg? ExceptionInfo #"testing"
          (<!!? (go (ex-info "testing" {}))))))

  (testing "alts!?"
    (is (= (<!! (go (first (alts!? [(go 1)
                                    (go
                                      (<! (timeout 1000))
                                      (ex-info "testing" {}))]
                                   :priority true))))
           1))
    (<!! (go
           (is (thrown-with-msg? ExceptionInfo #"testing"
                  (alts!? [(go (ex-info "testing" {}))
                           (go
                             (<! (timeout 1000))
                             1)]
                          :priority true))))))

  (testing "alts!!?"
    (is (= (first (alts!!? [(go 1)
                            (go
                              (<! (timeout 1000))
                              (ex-info "testing" {}))]
                           :priority true))
           1))
    (is (thrown-with-msg? ExceptionInfo #"testing"
          (alts!!? [(go (ex-info "testing" {}))
                    (go
                      (<! (timeout 1000))
                      1)]
                   :priority true)))))

(deftest chan<->seq
  (testing "onto empty"
    (let [ch (chan)]
      (onto ch nil false)
      (go
        (>! ch 1))
      (is (= (<!! ch) 1))
      (onto ch nil true)
      (is (nil? (<!! ch))))
    (let [ch (chan)]
      (onto ch [] false)
      (go
        (>! ch 1))
      (is (= (<!! ch) 1))
      (onto ch [])
      (is (nil? (<!! ch)))))

  (testing "onto false"
    (let [ch (chan)]
      (onto ch [1 false 3] false)
      (is (= (<!! ch) 1))
      (is (= (<!! ch) false))
      (is (= (<!! ch) 3))))

  (testing "onto non-empty"
    (let [ch (chan)]
      (onto ch [1 2 3] false)
      (is (= (<!! ch) 1))
      (is (= (<!! ch) 2))
      (is (= (<!! ch) 3))
      (onto ch [4 5 6])
      (is (= (<!! ch) 4))
      (is (= (<!! ch) 5))
      (is (= (<!! ch) 6))
      (is (nil? (<!! ch)))))

  (testing "onto-chan empty"
    (let [ch (onto-chan nil 10)]
      (is (nil? (<!! ch))))
    (let [ch (onto-chan [] 10)]
      (is (nil? (<!! ch)))))

  (testing "onto-chan non-empty"
    (let [ch (onto-chan [1 2 3] 10)]
      (is (= (<!! ch) 1))
      (is (= (<!! ch) 2))
      (is (= (<!! ch) 3))
      (is (nil? (<!! ch)))))

  (testing "to-seq"
    (is (empty? (to-seq nil)))
    (is (empty? (to-seq (onto-chan []))))
    (let [ch (chan)]
      (close! ch)
      (is (empty? (to-seq ch))))
    (is (= (to-seq (onto-chan [1 2 false 3])) [1 2 false 3]))))

(deftest mult-tap
  (testing "tap-chan"
    (let [ch   (chan)
          mux  (mult ch)
          tap1 (tap-chan mux)
          tap2 (tap-chan mux 10)]
      (go
        (>! ch 1))
      (is (= (<!! tap1) 1))
      (is (= (<!! tap2) 1))
      (close! ch)
      (is (nil? (<!! tap1)))
      (is (nil? (<!! tap2))))))

(deftest chan-pipe
  (testing "identity pipe"
    (let [chin  (onto-chan [1 2 false 3])
          chout (pipe-chan chin)]
      (is (= (to-seq chout)
             [1 2 false 3]))))

  (testing "transduced pipe"
    (let [chin  (onto-chan [1 2 3])
          chout (pipe-chan chin 1 (map inc))]
      (is (= (to-seq chout)
             [2 3 4])))))

(deftest go-utilities
  (testing "when<!"
    (let [ch (onto-chan [1 false (ex-info "testing" {}) 2])]
      (<!!? (go-catch
              (is (= (when<! [x ch] x)
                     1))
              (is (= (when<! [x ch] x)
                     false))
              (is (instance? ExceptionInfo (when<! [x ch] x)))
              (is (= (when<! [x ch] x)
                     2))
              (is (nil? (when<! [x ch] (is false))))))))

  (testing "when<!?"
    (let [ch (onto-chan [1 false (ex-info "testing" {}) 2 3])]
      (<!!? (go-catch
              (is (= (when<!? [x ch] x)
                     1))
              (is (= (when<!? [x ch] x)
                     false))
              (is (thrown-with-msg? ExceptionInfo #"testing"
                    (when<!? [x ch]
                      (is false)
                      (catch ExceptionInfo e
                        (throw (ex-info "testing2" {}))))))
              (is (thrown-with-msg? ExceptionInfo #"testing2"
                    (when<!? [x ch]
                      (is (= x 2))
                      (throw (ex-info "testing2" {})))))
              (is (thrown-with-msg? ExceptionInfo #"testing2"
                    (when<!? [x ch]
                      (is (= x 3))
                      (finally
                        (throw (ex-info "testing2" {}))))))
              (is (nil? (when<! [x ch] (is false))))))))

  (testing "if<!"
    (let [ch (onto-chan [1 false (ex-info "testing" {}) 2])]
      (<!!? (go-catch
              (is (= (if<! [x ch] x :fail)
                     1))
              (is (= (if<! [x ch] x :fail)
                     false))
              (is (instance? ExceptionInfo (if<! [x ch] x :fail)))
              (is (= (if<! [x ch] x :fail)
                     2))
              (is (= (if<! [x ch] (is false) 3)
                     3))))))

  (testing "if<!?"
    (let [ch (onto-chan [1 false (ex-info "testing" {}) 2 3])]
      (<!!? (go-catch
              (is (= (if<!? [x ch] x :fail)
                     1))
              (is (= (if<!? [x ch] x :fail)
                     false))
              (is (thrown-with-msg? ExceptionInfo #"testing2"
                    (if<!? [x ch]
                      (is false)
                      :fail
                      (catch ExceptionInfo e
                        (throw (ex-info "testing2" {}))))))
              (is (thrown-with-msg? ExceptionInfo #"testing2"
                    (if<!? [x ch]
                      (throw (ex-info "testing2" {}))
                      :fail)))
              (is (thrown-with-msg? ExceptionInfo #"testing2"
                    (if<!? [x ch]
                      (is (= x 3))
                      :fail
                      (finally
                        (throw (ex-info "testing2" {}))))))
              (is (= (if<! [x ch] (is false) 4)
                     4))))))

  (testing "while<!"
    (let [ch (chan)]
      (go
        (while<! [x (onto-chan [1 false (ex-info "testing" {}) 2])]
          (>! ch x))
        (close! ch))
      (is (= (<!! ch) 1))
      (is (= (<!! ch) false))
      (is (thrown-with-msg? ExceptionInfo #"testing"
            (<!!? ch)))
      (is (= (<!! ch) 2))
      (is (nil? (<!! ch)))))

  (testing "while<!?"
    (let [ch (chan)]
      (go
        (while<!? [x (onto-chan [1 false (ex-info "testing" {}) 2 3])]
          (when (= x 3)
            (throw (ex-info "testing" {})))
          (>! ch x)
          (catch Exception e
            (>! ch (ex-info "testing2" {}))))
        (close! ch))
      (is (= (<!! ch) 1))
      (is (= (<!! ch) false))
      (is (thrown-with-msg? ExceptionInfo #"testing2"
            (<!!? ch)))
      (is (= (<!! ch) 2))
      (is (thrown-with-msg? ExceptionInfo #"testing2"
            (<!!? ch)))
      (is (nil? (<!! ch)))))

  (testing "when-alts!"
    (let [ch0 (onto-chan [1 false (ex-info "testing" {}) 2])
          ch1 (timeout 1000)]
      (<!!? (go-catch
              (is (= (when-alts! [[x] [ch0 ch1] :priority true] x)
                     1))
              (is (= (when-alts! [[x] [ch0 ch1]] x)
                     false))
              (is (instance? ExceptionInfo (when-alts! [[x] [ch0 ch1]] x)))
              (is (= (when-alts! [[x] [ch0 ch1]] x)
                     2))
              (is (nil? (when-alts! [[x] [ch0 ch1]] (is false))))))))

  (testing "when-alts!?"
    (let [ch0 (onto-chan [1 false (ex-info "testing" {}) 2 3])
          ch1 (timeout 1000)]
      (<!!? (go-catch
              (is (= (when-alts!? [[x] [ch0 ch1] :priority true] x)
                     1))
              (is (= (when-alts!? [[x] [ch0 ch1]] x)
                     false))
              (is (thrown-with-msg? ExceptionInfo #"testing"
                    (when-alts!? [[x] [ch0 ch1]]
                      (is false)
                      (catch ExceptionInfo e
                        (throw (ex-info "testing2" {}))))))
              (is (thrown-with-msg? ExceptionInfo #"testing2"
                    (when-alts!? [[x] [ch0 ch1]]
                      (is (= x 2))
                      (throw (ex-info "testing2" {})))))
              (is (thrown-with-msg? ExceptionInfo #"testing2"
                    (when-alts!? [[x] [ch0 ch1]]
                      (is (= x 3))
                      (finally
                        (throw (ex-info "testing2" {}))))))
              (is (nil? (when-alts! [[x] [ch0 ch1]] (is false))))))))

  (testing "if-alts!"
    (let [ch0 (onto-chan [1 false (ex-info "testing" {}) 2])
          ch1 (timeout 1000)]
      (<!!? (go-catch
              (is (= (if-alts! [[x] [ch0 ch1] :priority true] x :fail)
                     1))
              (is (= (if-alts! [[x] [ch0 ch1]] x :fail)
                     false))
              (is (instance? ExceptionInfo (if-alts! [[x] [ch0 ch1]] x :fail)))
              (is (= (if-alts! [[x] [ch0 ch1]] x :fail)
                     2))
              (is (= (if-alts! [[x] [ch0 ch1]] (is false) 3)
                     3))))))

  (testing "if-alts!?"
    (let [ch0 (onto-chan [1 false (ex-info "testing" {}) 2 3])
          ch1 (timeout 1000)]
      (<!!? (go-catch
              (is (= (if-alts!? [[x] [ch0 ch1] :priority true] x :fail)
                     1))
              (is (= (if-alts!? [[x] [ch0 ch1]] x :fail)
                     false))
              (is (thrown-with-msg? ExceptionInfo #"testing2"
                    (if-alts!? [[x] [ch0 ch1]]
                      (is false)
                      :fail
                      (catch ExceptionInfo e
                        (throw (ex-info "testing2" {}))))))
              (is (thrown-with-msg? ExceptionInfo #"testing2"
                    (if-alts!? [[x] [ch0 ch1]]
                      (throw (ex-info "testing2" {}))
                      :fail)))
              (is (thrown-with-msg? ExceptionInfo #"testing2"
                    (if-alts!? [[x] [ch0 ch1]]
                      (is (= x 3))
                      :fail
                      (finally
                        (throw (ex-info "testing2" {}))))))
              (is (= (if-alts! [[x] [ch0 ch1]] (is false) 4)
                     4))))))

  (testing "while-alts!"
    (let [ch (chan)]
      (go
        (while-alts! [[x]
                      [(onto-chan [1 false (ex-info "testing" {}) 2])
                       (timeout 1000)]
                      :priority true]
          (>! ch x))
        (close! ch))
      (is (= (<!! ch) 1))
      (is (= (<!! ch) false))
      (is (thrown-with-msg? ExceptionInfo #"testing"
            (<!!? ch)))
      (is (= (<!! ch) 2))
      (is (nil? (<!! ch)))))

  (testing "while-alts!?"
    (let [ch (chan)]
      (go
        (while-alts!? [[x]
                       [(onto-chan [1 false (ex-info "testing" {}) 2 3])
                        (timeout 1000)]
                       :priority true]
          (when (= x 3)
            (throw (ex-info "testing" {})))
          (>! ch x)
          (catch Exception e
            (>! ch (ex-info "testing2" {}))))
        (close! ch))
      (is (= (<!! ch) 1))
      (is (= (<!! ch) false))
      (is (thrown-with-msg? ExceptionInfo #"testing2"
            (<!!? ch)))
      (is (= (<!! ch) 2))
      (is (thrown-with-msg? ExceptionInfo #"testing2"
            (<!!? ch)))
      (is (nil? (<!! ch)))))

  (testing "go-let"
    (is (= (<!! (go-let []
                  1))
           1))
    (is (= (<!! (go-let [x 2]
                  x))
           2))
    (is (= (<!! (go-let [x 3
                         y (inc x)]
                  [x y]))
           [3 4])))

  (testing "go-try"
    (is (= (<!! (go-try
                  1))
           1))
    (is (= (<!! (go-try
                  (throw (ex-info "testing" {}))
                  2
                  (catch ExceptionInfo e
                    3)))
           3)))

  (testing "go-catch"
    (is (= (<!! (go-catch
                  1))
           1))
    (is (thrown-with-msg? ExceptionInfo #"testing"
          (<!!? (go-catch
                  (throw (ex-info "testing" {}))
                  1)))))

  (testing "go-seq"
    (let [results (atom [])]
      (is (nil? (<!! (go-seq [i nil]
                      (swap! results conj i)))))
      (is (empty? @results)))
    (let [results (atom [])]
      (is (nil? (<!! (go-seq [i []]
                      (swap! results conj i)))))
      (is (empty? @results)))
    (let [results (atom [])
          s      [1 2 false 3]]
      (is (nil? (<!! (go-seq [i s]
                      (swap! results conj i)))))
      (is (= @results s))))

  (testing "go-each"
    (let [ch (go-each)]
      (is (= (to-seq ch) nil)))
    (let [ch (go-each
               (<! (go 1))
               2
               (<! (go nil))
               (<! (go false))
               (<! (thread 3)))]
      (is (= (to-seq ch)
             [1 2 false 3])))
    (let [ch (go-each
               (<! (go 1))
               (ex-info "testing" {})
               3)]
      (is (= (<!!? ch) 1))
      (is (thrown-with-msg? ExceptionInfo #"testing" (<!!? ch) 1))
      (is (nil? (<!!? ch)))))

  (testing "go-all"
    (let [ch (go-all)]
      (is (= (to-seq ch) nil)))
    (let [ch (go-all
               (do (<! (timeout 20)) 1)
               2
               (<! (go nil))
               (<! (go false))
               (<! (thread 3)))]
      (is (= (to-seq ch)
             [1 2 false 3])))
    (let [ch (go-all
               (<! (go 1))
               (ex-info "testing" {})
               3)]
      (is (= (<!!? ch) 1))
      (is (thrown-with-msg? ExceptionInfo #"testing" (<!!? ch) 1))
      (is (= (<!!? ch) 3))
      (is (nil? (<!!? ch)))))

  (testing "go-as->"
    (let [ch (go-as-> x)]
      (is (nil? (<!! ch))))
    (let [ch (go-as-> x
                      (<! (go 1))
                      x
                      (<! (go (inc x)))
                      (<! (go 3))
                      (<! (thread (inc x)))
                      (inc x))]
      (is (= (<!! ch) 5)))
    (is (thrown-with-msg? ExceptionInfo #"testing"
          (<!!? (go-as-> x
                         (<! (go 1))
                         (ex-info "testing" {:x x})
                         1)))))

  (testing "go-while<!"
    (let [ch     (chan)
          results (atom [])]
      (close! ch)
      (is (nil? (<!! (go-while<! [i ch]
                       (swap! results conj i)))))
      (is (empty? @results)))
    (let [ch     (chan)
          results (atom [])]
      (onto ch [1 2 false 3])
      (is (nil? (<!! (go-while<! [i ch]
                       (swap! results conj i)))))
      (is (= @results [1 2 false 3]))))

  (testing "go-while<!?"
    (let [ch      (chan)
          results (atom [])]
      (close! ch)
      (is (nil? (<!! (go-while<!? [i ch]
                       (swap! results conj i)
                       (catch ExceptionInfo e
                         (is false))))))
      (is (empty? @results)))
    (let [ch      (chan)
          results (atom [])
          errors  (atom [])]
      (onto ch [1 (ex-info "testing" {}) 3 4 [5 6]])
      (is (nil? (<!! (go-while<!? [i ch]
                       (if (= i 3)
                         (throw (ex-info "testing" {}))
                         (swap! results conj i))
                       (catch Exception e
                         (swap! errors conj e))))))
      (is (= @results [1 4 [5 6]]))
      (is (= (count @errors) 2))))

  (testing "go-while-alts!"
    (let [ch      (chan)
          results (atom [])]
      (close! ch)
      (is (nil? (<!! (go-while-alts! [[i] [ch (timeout 1000)] :priority true]
                       (swap! results conj i)))))
      (is (empty? @results)))
    (let [ch      (chan)
          results (atom [])]
      (onto ch [1 2 false 3])
      (is (nil? (<!! (go-while-alts! [[i] [ch (timeout 1000)] :priority true]
                       (swap! results conj i)))))
      (is (= @results [1 2 false 3]))))

  (testing "go-while-alts!?"
    (let [ch      (chan)
          results (atom [])]
      (close! ch)
      (is (nil? (<!! (go-while-alts!? [[i] [ch (timeout 1000)] :priority true]
                       (swap! results conj i)
                       (catch ExceptionInfo e
                         (is false))))))
      (is (empty? @results)))
    (let [ch      (chan)
          results (atom [])
          errors  (atom [])]
      (onto ch [1 (ex-info "testing" {}) 3 4 [5 6]])
      (is (nil? (<!! (go-while-alts!? [[i] [ch (timeout 1000)] :priority true]
                       (if (= i 3)
                         (throw (ex-info "testing" {}))
                         (swap! results conj i))
                       (catch Exception e
                         (swap! errors conj e))))))
      (is (= @results [1 4 [5 6]]))
      (is (= (count @errors) 2)))))

(deftest thread-utilities
  (testing "when<!!"
    (let [ch (onto-chan [1 false (ex-info "testing" {}) 2])]
      (<!!? (thread-catch
              (is (= (when<!! [x ch] x)
                     1))
              (is (= (when<!! [x ch] x)
                     false))
              (is (instance? ExceptionInfo (when<!! [x ch] x)))
              (is (= (when<!! [x ch] x)
                     2))
              (is (nil? (when<!! [x ch] (is false))))))))

  (testing "when<!!?"
    (let [ch (onto-chan [1 false (ex-info "testing" {}) 2 3])]
      (<!!? (thread-catch
              (is (= (when<!!? [x ch] x)
                     1))
              (is (= (when<!!? [x ch] x)
                     false))
              (is (thrown-with-msg? ExceptionInfo #"testing2"
                    (when<!!? [x ch]
                      (is false)
                      (catch ExceptionInfo e
                        (throw (ex-info "testing2" {}))))))
              (is (thrown-with-msg? ExceptionInfo #"testing2"
                    (when<!!? [x ch]
                      (is (= x 2))
                      (throw (ex-info "testing2" {})))))
              (is (thrown-with-msg? ExceptionInfo #"testing2"
                    (when<!!? [x ch]
                      (is (= x 3))
                      (finally
                        (throw (ex-info "testing2" {}))))))
              (is (nil? (when<!! [x ch] (is false))))))))

  (testing "if<!!"
    (let [ch (onto-chan [1 false (ex-info "testing" {}) 2])]
      (<!!? (thread-catch
              (is (= (if<!! [x ch] x :fail)
                     1))
              (is (= (if<!! [x ch] x :fail)
                     false))
              (is (instance? ExceptionInfo (if<!! [x ch] x :fail)))
              (is (= (if<!! [x ch] x :fail)
                     2))
              (is (= (if<!! [x ch] (is false) 3)
                     3))))))

  (testing "if<!!?"
    (let [ch (onto-chan [1 false (ex-info "testing" {}) 2 3])]
      (<!!? (thread-catch
              (is (= (if<!!? [x ch] x :fail)
                     1))
              (is (= (if<!!? [x ch] x :fail)
                     false))
              (is (thrown-with-msg? ExceptionInfo #"testing"
                    (if<!!? [x ch]
                      (is false)
                      :fail
                      (catch ExceptionInfo e
                        (throw (ex-info "testing2" {}))))))
              (is (thrown-with-msg? ExceptionInfo #"testing2"
                    (if<!!? [x ch]
                      (throw (ex-info "testing2" {}))
                      :fail)))
              (is (thrown-with-msg? ExceptionInfo #"testing2"
                    (if<!!? [x ch]
                      (is (= x 3))
                      :fail
                      (finally
                        (throw (ex-info "testing2" {}))))))
              (is (= (if<!! [x ch] (is false) 4)
                     4))))))

  (testing "while<!!"
    (let [ch (chan)]
      (thread
        (while<!! [x (onto-chan [1 false (ex-info "testing" {}) 2])]
          (>!! ch x))
        (close! ch))
      (is (= (<!! ch) 1))
      (is (= (<!! ch) false))
      (is (thrown-with-msg? ExceptionInfo #"testing"
            (<!!? ch)))
      (is (= (<!! ch) 2))
      (is (nil? (<!! ch)))))

  (testing "while<!!?"
    (let [ch (chan)]
      (thread
        (while<!!? [x (onto-chan [1 false (ex-info "testing" {}) 2 3])]
          (when (= x 3)
            (throw (ex-info "testing" {})))
          (>!! ch x)
          (catch Exception e
            (>!! ch (ex-info "testing2" {}))))
        (close! ch))
      (is (= (<!! ch) 1))
      (is (= (<!! ch) false))
      (is (thrown-with-msg? ExceptionInfo #"testing2"
            (<!!? ch)))
      (is (= (<!! ch) 2))
      (is (thrown-with-msg? ExceptionInfo #"testing2"
            (<!!? ch)))
      (is (nil? (<!! ch)))))

  (testing "when-alts!!"
    (let [ch0 (onto-chan [1 false (ex-info "testing" {}) 2])
          ch1 (timeout 1000)]
      (is (= (when-alts!! [[x] [ch0 ch1] :priority true] x)
             1))
      (is (= (when-alts!! [[x] [ch0 ch1]] x)
             false))
      (is (instance? ExceptionInfo (when-alts!! [[x] [ch0 ch1]] x)))
      (is (= (when-alts!! [[x] [ch0 ch1]] x)
             2))
      (is (nil? (when-alts!! [[x] [ch0 ch1]] (is false))))))

  (testing "when-alts!!?"
    (let [ch0 (onto-chan [1 false (ex-info "testing" {}) 2 3])
          ch1 (timeout 1000)]
      (is (= (when-alts!!? [[x] [ch0 ch1] :priority true] x)
             1))
      (is (= (when-alts!!? [[x] [ch0 ch1]] x)
             false))
      (is (thrown-with-msg? ExceptionInfo #"testing"
            (when-alts!!? [[x] [ch0 ch1]]
              (is false)
              (catch ExceptionInfo e
                (throw (ex-info "testing2" {}))))))
      (is (thrown-with-msg? ExceptionInfo #"testing2"
            (when-alts!!? [[x] [ch0 ch1]]
              (is (= x 2))
              (throw (ex-info "testing2" {})))))
      (is (thrown-with-msg? ExceptionInfo #"testing2"
            (when-alts!!? [[x] [ch0 ch1]]
              (is (= x 3))
              (finally
                (throw (ex-info "testing2" {}))))))
      (is (nil? (when-alts!! [[x] [ch0 ch1]] (is false))))))

  (testing "if-alts!!"
    (let [ch0 (onto-chan [1 false (ex-info "testing" {}) 2])
          ch1 (timeout 1000)]
      (is (= (if-alts!! [[x] [ch0 ch1] :priority true] x :fail)
             1))
      (is (= (if-alts!! [[x] [ch0 ch1]] x :fail)
             false))
      (is (instance? ExceptionInfo (if-alts!! [[x] [ch0 ch1]] x :fail)))
      (is (= (if-alts!! [[x] [ch0 ch1]] x :fail)
             2))
      (is (= (if-alts!! [[x] [ch0 ch1]] (is false) 3)
             3))))

  (testing "if-alts!!?"
    (let [ch0 (onto-chan [1 false (ex-info "testing" {}) 2 3])
          ch1 (timeout 1000)]
      (is (= (if-alts!!? [[x] [ch0 ch1] :priority true] x :fail)
             1))
      (is (= (if-alts!!? [[x] [ch0 ch1]] x :fail)
             false))
      (is (thrown-with-msg? ExceptionInfo #"testing2"
            (if-alts!!? [[x] [ch0 ch1]]
              (is false)
              :fail
              (catch ExceptionInfo e
                (throw (ex-info "testing2" {}))))))
      (is (thrown-with-msg? ExceptionInfo #"testing2"
            (if-alts!!? [[x] [ch0 ch1]]
              (throw (ex-info "testing2" {}))
              :fail)))
      (is (thrown-with-msg? ExceptionInfo #"testing2"
            (if-alts!!? [[x] [ch0 ch1]]
              (is (= x 3))
              :fail
              (finally
                (throw (ex-info "testing2" {}))))))
      (is (= (if-alts!! [[x] [ch0 ch1]] (is false) 4)
             4))))

  (testing "while-alts!!"
    (let [ch (chan)]
      (thread
        (while-alts!! [[x]
                      [(onto-chan [1 false (ex-info "testing" {}) 2])
                       (timeout 1000)]
                      :priority true]
          (>!! ch x))
        (close! ch))
      (is (= (<!! ch) 1))
      (is (= (<!! ch) false))
      (is (thrown-with-msg? ExceptionInfo #"testing"
            (<!!? ch)))
      (is (= (<!! ch) 2))
      (is (nil? (<!! ch)))))

  (testing "while-alts!!?"
    (let [ch (chan)]
      (thread
        (while-alts!!? [[x]
                       [(onto-chan [1 false (ex-info "testing" {}) 2 3])
                        (timeout 1000)]
                       :priority true]
          (when (= x 3)
            (throw (ex-info "testing" {})))
          (>!! ch x)
          (catch Exception e
            (>!! ch (ex-info "testing2" {}))))
        (close! ch))
      (is (= (<!! ch) 1))
      (is (= (<!! ch) false))
      (is (thrown-with-msg? ExceptionInfo #"testing2"
            (<!!? ch)))
      (is (= (<!! ch) 2))
      (is (thrown-with-msg? ExceptionInfo #"testing2"
            (<!!? ch)))
      (is (nil? (<!! ch)))))

  (testing "thread-let"
    (is (= (<!! (thread-let []
                  1))
           1))
    (is (= (<!! (thread-let [x 2]
                  x))
           2))
    (is (= (<!! (thread-let [x 3
                             y (inc x)]
                  [x y]))
           [3 4])))

  (testing "thread-try"
    (is (= (<!! (thread-try
                  1))
           1))
    (is (= (<!! (thread-try
                  (throw (ex-info "testing" {}))
                  2
                  (catch ExceptionInfo e
                    3)))
           3)))

  (testing "thread-catch"
    (is (= (<!! (thread-catch
                  1))
           1))
    (is (thrown-with-msg? ExceptionInfo #"testing"
          (<!!? (thread-catch
                  (throw (ex-info "testing" {}))
                  1)))))

  (testing "thread-seq"
    (let [results (atom [])]
      (is (nil? (<!! (thread-seq [i nil]
                      (swap! results conj i)))))
      (is (empty? @results)))
    (let [results (atom [])]
      (is (nil? (<!! (thread-seq [i []]
                       (swap! results conj i)))))
      (is (empty? @results)))
    (let [results (atom [])
          s      [1 2 false 3]]
      (is (nil? (<!! (thread-seq [i s]
                       (swap! results conj i)))))
      (is (= @results s))))

  (testing "thread-each"
    (let [ch (thread-each)]
      (is (= (to-seq ch) nil)))
    (let [ch (thread-each
               (<!! (go 1))
               2
               (<!! (go nil))
               (<!! (go false))
               (<!! (thread 3)))]
      (is (= (to-seq ch)
             [1 2 false 3])))
    (let [ch (thread-each
               (<!! (go 1))
               (ex-info "testing" {})
               3)]
      (is (= (<!!? ch) 1))
      (is (thrown-with-msg? ExceptionInfo #"testing" (<!!? ch) 1))
      (is (nil? (<!!? ch)))))

  (testing "thread-all"
    (let [ch (thread-all)]
      (is (= (to-seq ch) nil)))
    (let [ch (thread-all
               (do (<!! (timeout 20)) 1)
               2
               (<!! (go nil))
               (<!! (go false))
               (<!! (thread 3)))]
      (is (= (to-seq ch)
             [1 2 false 3])))
    (let [ch (thread-all
               (<!! (go 1))
               (ex-info "testing" {})
               3)]
      (is (= (<!!? ch) 1))
      (is (thrown-with-msg? ExceptionInfo #"testing" (<!!? ch) 1))
      (is (= (<!!? ch) 3))
      (is (nil? (<!!? ch)))))

  (testing "thread-as->"
    (let [ch (thread-as-> x)]
      (is (nil? (<!! ch))))
    (let [ch (thread-as-> x
                          (<!! (go 1))
                          x
                          (<!! (go (inc x)))
                          (<!! (go 3))
                          (<!! (thread (inc x)))
                          (inc x))]
      (is (= (<!! ch) 5)))
    (is (thrown-with-msg? ExceptionInfo #"testing"
          (<!!? (thread-as-> x
                             (<!! (go 1))
                             (ex-info "testing" {:x x})
                             1)))))

  (testing "thread-while<!!"
    (let [ch      (chan)
          results (atom [])]
      (close! ch)
      (is (nil? (<!! (thread-while<!! [i ch]
                       (swap! results conj i)))))
      (is (empty? @results)))
    (let [ch     (chan)
          results (atom [])]
      (onto ch [1 2 false 3])
      (is (nil? (<!! (thread-while<!! [i ch]
                      (swap! results conj i)))))
      (is (= @results [1 2 false 3]))))

  (testing "thread-while<!!?"
    (let [ch      (chan)
          results (atom [])]
      (close! ch)
      (is (nil? (<!! (thread-while<!!? [i ch]
                       (swap! results conj i)
                       (catch ExceptionInfo e
                         (is false))))))
      (is (empty? @results)))
    (let [ch      (chan)
          results (atom [])
          errors  (atom [])]
      (onto ch [1 (ex-info "testing" {}) 3 4 [5 6]])
      (is (nil? (<!! (thread-while<!!? [i ch]
                       (if (= i 3)
                         (throw (ex-info "testing" {}))
                         (swap! results conj i))
                       (catch Exception e
                         (swap! errors conj e))))))
      (is (= @results [1 4 [5 6]]))
      (is (= (count @errors) 2))))

  (testing "thread-while-alts!!"
    (let [ch      (chan)
          results (atom [])]
      (close! ch)
      (is (nil? (<!! (thread-while-alts!! [[i]
                                          [ch (timeout 1000)]
                                          :priority true]
                       (swap! results conj i)))))
      (is (empty? @results)))
    (let [ch      (chan)
          results (atom [])]
      (onto ch [1 2 false 3])
      (is (nil? (<!! (thread-while-alts!! [[i]
                                          [ch (timeout 1000)]
                                          :priority true]
                       (swap! results conj i)))))
      (is (= @results [1 2 false 3]))))

  (testing "thread-while-alts!!?"
    (let [ch      (chan)
          results (atom [])]
      (close! ch)
      (is (nil? (<!! (thread-while-alts!!? [[i]
                                           [ch (timeout 1000)]
                                           :priority true]
                       (swap! results conj i)
                       (catch ExceptionInfo e
                         (is false))))))
      (is (empty? @results)))
    (let [ch      (chan)
          results (atom [])
          errors  (atom [])]
      (onto ch [1 (ex-info "testing" {}) 3 4 [5 6]])
      (is (nil? (<!! (thread-while-alts!!? [[i]
                                           [ch (timeout 1000)]
                                           :priority true]
                       (if (= i 3)
                         (throw (ex-info "testing" {}))
                         (swap! results conj i))
                       (catch Exception e
                         (swap! errors conj e))))))
      (is (= @results [1 4 [5 6]]))
      (is (= (count @errors) 2)))))

(deftest distinct-buffer-chan
  (testing "distinct elements"
    (let [ch (chan (distinct-buffer 3))]
      (>!! ch 1)
      (>!! ch 2)
      (>!! ch 3)
      (is (not (offer! ch 4)))
      (is (= (<!! ch) 1))
      (>!! ch 1)
      (is (= (<!! ch) 2))
      (is (= (<!! ch) 3))
      (is (= (<!! ch) 1))
      (is (nil? (poll! ch)))))

  (testing "duplicate elements"
    (let [ch (chan (distinct-buffer 3))]
      (>!! ch 1)
      (>!! ch 2)
      (>!! ch 2)
      (>!! ch 1)
      (>!! ch 3)
      (is (= (<!! ch) 1))
      (is (= (<!! ch) 2))
      (is (= (<!! ch) 3))
      (is (nil? (poll! ch))))))

(deftest interval-chan
  (testing "default value"
    (let [ch (interval 20)]
      (loop [t0 (System/currentTimeMillis)
             i  0]
        (when (< i 5)
          (is (<!! ch))
          (let [t1 (System/currentTimeMillis)]
            (is (>= (- t1 t0 20)))
            (recur t1 (inc i)))))
      (close! ch)
      (is (nil? (<!! ch)))))

  (testing "interval value"
    (let [ch (interval 20 :test)]
      (loop [t0 (System/currentTimeMillis)
             i  0]
        (when (< i 5)
          (is (= (<!! ch) :test))
          (let [t1 (System/currentTimeMillis)]
            (is (>= (- t1 t0 20)))
            (recur t1 (inc i)))))
      (close! ch)
      (is (nil? (<!! ch))))))

(deftest semaphore-chan
  (testing "semaphore"
    (let [ch (semaphore 2)]
      (is (not (offer! ch :release)))
      (is (= (<!! ch) 1))
      (is (= (<!! ch) 0))
      (is (nil? (poll! ch)))
      (is (>!! ch :release))
      (is (= (<!! ch) 0))
      (is (nil? (poll! ch)))
      (is (>!! ch :release))
      (is (>!! ch :release))
      (is (not (offer! ch :release)))
      (is (= (<!! ch) 1))
      (is (= (<!! ch) 0))
      (is (nil? (poll! ch)))
      (is (>!! ch :release))
      (is (>!! ch :release)))))
