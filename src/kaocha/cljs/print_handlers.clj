(ns kaocha.cljs.print-handlers
  (:require [lambdaisland.deep-diff2.printer-impl :as printer]
            [lambdaisland.deep-diff2.puget.printer :as puget]))

(printer/register-print-handler!
 'com.cognitect.transit.impl.TaggedValueImpl
 (fn [printer ^com.cognitect.transit.impl.TaggedValueImpl value]
   (puget/format-doc printer (tagged-literal (symbol (.getTag value)) (.getRep value)))))
