(ns ^:unit chantrix.control-test
  (:require [clojure.test :refer :all]
            [chantrix.async :as async :refer [>! <! <!? >!! <!! <!!?]]
            [chantrix.junction :as junc :refer [junc]]
            [chantrix.control :as control]))

(deftest governor
  (testing "invalid limit"
    (is (thrown? AssertionError
          (control/governor (junc/pipe-junc :buf 3) :limit 0))))

  (testing "default limit"
    (let [[>gov <gov] (control/governor (junc/pipe-junc :buf 3))]
      (>!! >gov 1)
      (is (not (async/offer! >gov 2)))))

  (testing "valid limit"
    (let [[>gov <gov] (control/governor (junc/pipe-junc :buf 3) :limit 2)]
      (>!! >gov 1)
      (>!! >gov 2)
      (is (not (async/offer! >gov 3)))
      (is (= (<!! <gov) 1))
      (>!! >gov 3)
      (is (not (async/offer! >gov 4)))
      (is (= (<!! <gov) 2))
      (is (= (<!! <gov) 3))))

  (testing "governor close"
    (let [[>gov <gov] (control/governor (junc/pipe-junc))]
      (>!! >gov 1)
      (async/close! >gov :drain? false)
      (is (= (<!! <gov) 1))
      (is (nil? (<!! <gov))))))
