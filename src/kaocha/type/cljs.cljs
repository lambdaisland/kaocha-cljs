(ns kaocha.type.cljs
  (:require [lambdaisland.glogi :as glogi]
            [lambdaisland.glogi.console :as glogi.console]
            [clojure.string :as str]))

(goog-define log-level "WARNING")
(goog-define root-log-level "WARNING")

(glogi.console/install!)
(glogi/set-level "" (keyword (str/lower-case kaocha.type.cljs/root-log-level)))
