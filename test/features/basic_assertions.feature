Feature: Basic assertions

  A first test to demonstrate ClojureScript support

  Scenario: Running a ClojureScript test
    Given a file named "test/my/sample_test.cljs" with:
      """ clojurescript
      (ns my.sample-test
        (:require [clojure.test :as t :refer [is testing]]))

      (t/deftest regular-pass
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

      (t/deftest no-assert
        (= 1 1))

      (t/deftest single-arg-=
        (is (= 1)))
      """
    And a file named "tests.edn" with:
      """ clojure
      #kaocha/v1
      {:tests [{:type :kaocha.type/cljs}]
       :color? false}
      """
    When I run `npm install ws isomorphic-ws`
    And I run `bin/kaocha`
    Then the output should contain:
      """
      FAIL in cljs:my.sample-test/regular-fail (cljs/test.js:433)
      Expected:
        :foo
      Actual:
        :bar
      Diff:
        - :foo
        + :bar
      """
    And the output should contain:
      """
      ERROR in cljs:my.sample-test/exception-outside-is (Error:NaN)
      Uncaught exception, not in assertion.
      Error: Whoopsie!
      """
    And the output should contain:
      """
      ERROR in cljs:my.sample-test/exception-in-is (Error:NaN)
      Error: Whoopsie!
      """
    And the output should contain:
      """
      FAIL in cljs:my.sample-test/single-arg-= (main.java:37)
      Equality assertion expects 2 or more values to compare, but only 1 arguments given.
      Expected:
        (= 1 arg2)
      Actual:
        (= 1)
      """
    And the output should contain:
      """
      FAIL in cljs:my.sample-test/no-assert (main.java:37)
      Test ran without assertions. Did you forget an (is ...)?
      """
    And the output should contain:
      """
      8 tests, 10 assertions, 2 errors, 3 failures.
      """
