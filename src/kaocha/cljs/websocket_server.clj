(ns kaocha.cljs.websocket-server
  (:require [cognitect.transit :as transit]
            [org.httpkit.server :as ws])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           java.util.concurrent.BlockingQueue))

(try
  (require 'matcher-combinators.model)
  (catch Exception e))

(def handlers
  (reduce
   (fn [handlers sym]
     (if-let [var (resolve (symbol (str "matcher-combinators.model/map->" sym)))]
       (assoc handlers
              (str "matcher-combinators.model." sym)
              (transit/read-handler @var))
       handlers))
   {"var" (transit/read-handler identity)}
   '[Mismatch
     Missing
     Unexpected
     InvalidMatcherType
     InvalidMatcherContext
     FailedPredicate
     TypeMismatch]))

(defn to-transit [value]
  (let [out (ByteArrayOutputStream. 4096)
        writer (transit/writer out :json)]
    (transit/write writer value)
    (.toString out)))

(defn from-transit [^String transit]
  (let [in (ByteArrayInputStream. (.getBytes transit))
        reader (transit/reader in :json {:handlers handlers})]
    (transit/read reader)))

(defn ws-handler [^BlockingQueue queue]
  (fn [req]
    (ws/with-channel req con
      (.put queue {:type ::connect :client con})
      (ws/on-receive con (fn [msg]
                           (let [msg (from-transit msg)]
                             (.add queue {:type ::message :client con :message msg}))))
      (ws/on-close con (fn [status]
                         (.add queue {:type ::disconnect :client con}))))))

(defn send! [client message]
  (ws/send! client (to-transit message) false))

(defn start! [queue]
  (ws/run-server (ws-handler queue) {:port 0 :join? false}))
