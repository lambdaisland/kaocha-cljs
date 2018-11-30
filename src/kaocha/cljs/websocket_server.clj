(ns kaocha.cljs.websocket-server
  (:require [cognitect.transit :as transit]
            [org.httpkit.server :as ws])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           java.util.concurrent.BlockingQueue))

(defn to-transit [value]
  (let [out (ByteArrayOutputStream. 4096)
        writer (transit/writer out :json)]
    (transit/write writer value)
    (.toString out)))

(defn from-transit [^String transit]
  (let [in (ByteArrayInputStream. (.getBytes transit))
        reader (transit/reader in :json {:handlers {"var" (transit/read-handler identity)}})]
    (transit/read reader)))

(defn ws-handler [^BlockingQueue queue]
  (fn [req]
    (ws/with-channel req con
      (.put queue {:type ::connect :client con})
      (ws/on-receive con (fn [msg]
                           (let [msg (from-transit msg)]
                             (.offer queue {:type ::message :client con :message msg}))))
      (ws/on-close con (fn [status]
                         (.offer queue {:type ::disconnect :client con}))))))

(defn send! [client message]
  (ws/send! client (to-transit message) false))

(defn start! [queue]
  (ws/run-server (ws-handler queue) {:port 9753 :join? false}))
