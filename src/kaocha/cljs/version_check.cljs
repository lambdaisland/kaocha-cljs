
(ns kaocha.cljs.version-check
  
  (:require  [goog.string :as gstring]
            [goog.string.format :as gformat] ))

(defn check-version-minimum 
  "Checks that Clojurescript has at least a minimum version"
  [major-minimum minor-minimum]
  (let [[_ major minor _patch  _trailing] (re-find #"(\d+).(\d+).(\d+)[-.]?(.*)?" *clojurescript-version*)]
    (when-not (or (and (= (js/Number major) major-minimum) (>= (js/Number minor) minor-minimum))
                (>= (js/Number major) (inc major-minimum) ))
    (let [msg (gstring/format "Kaocha-cljs requires Clojurescript %d.%d or later." major-minimum minor-minimum)]
     (throw (js/Error. msg))))))

(check-version-minimum 1 10)
