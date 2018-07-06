(ns kaocha.type.cljs
  (:require [kaocha.testable :as testable]
            [clojure.spec.alpha :as s]))

(defmethod testable/-load :kaocha.type/cljs [testable]
  testable
  )

(defmethod testable/-run :kaocha.type/cljs [testable]
  testable
  )

(s/def :kaocha.type/cljs any?)
