(ns kaocha.cljs.hierarchy
  (:require [kaocha.hierarchy :as hierarchy]))

(defmacro known-keys []
  (get-in hierarchy/hierarchy [:descendants :kaocha/known-key]))
