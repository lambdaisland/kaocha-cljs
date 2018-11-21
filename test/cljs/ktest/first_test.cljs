(ns ^:kaocha/focus ktest.first-test
  (:require [clojure.test :as t :refer [is testing]]))

(t/deftest ^:kaocha/mmm regular-pass
  (is (= :foo :foo)))

(t/deftest regular-fail
  (is (= :foo :bar)))

(t/deftest exception-in-is
  (is (throw (js/Error. "Whoopsie!"))))

(t/deftest exception-outside-is
  (throw (js/Error. "Whoopsie!")))

(t/deftest two-assertions
  (is (= :foo :foo))
  (is (= :bar :bar)))

(t/deftest testing-block
  (is (= :foo :foo))

  (testing "that bar is, in fact, bar"
    (is (= :bar :bar))))
