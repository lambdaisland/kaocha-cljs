(ns kaocha.type.version-check
  (:require [kaocha.output :as output]
            [cljs.util :as util]
            [slingshot.slingshot :refer [throw+]]))

(defn meets-minimum-cljs-version
  "Checks whether Clojurescript has at least a minimum version"
  [major minor]
  (or (and (= (:major util/*clojurescript-version*) major) (>= (:minor util/*clojurescript-version*) minor))
      (>= (:major util/*clojurescript-version*) (inc major) )))

(when (not (meets-minimum-cljs-version 1 10))
  (let [msg (format "Kaocha-cljs requires Clojurescript %d.%d or later." 1 10 )]
      (output/error msg)
      (throw+ {:kaocha/early-exit 251} msg)))
