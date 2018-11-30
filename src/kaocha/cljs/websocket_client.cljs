(ns kaocha.cljs.websocket-client
  (:require [kaocha.cljs.cognitect.transit :as transit]
            [cljs.test :as t]))

(def WebSocket
  (cond
    (exists? js/WebSocket)
    js/WebSocket

    (exists? js/require)
    (js/require "isomorphic-ws")))

(def socket nil)

(def transit-handlers
  {:default
   (transit/write-handler
    (fn [o]
      (str (type o)))
    (fn [o]
      (str o)))

   cljs.core/Var
   (transit/write-handler
    (constantly "var")
    (fn [rep] (meta rep)))})

(def transit-writer (transit/writer :json {:handlers transit-handlers}))

(defn to-transit [value]
  (transit/write transit-writer value))

(defn from-transit [string]
  (transit/read (transit/reader :json) string))

(defn send! [message]
  (when socket
    (.send socket (to-transit message))))

(defmethod t/report [:kaocha.type/cljs ::propagate] [m]
  (send! {:type :cljs.test/message :cljs.test/message m}))

(doseq [t [:fail :pass :error :summary
           :begin-test-ns :end-test-ns
           :begin-test-var :end-test-var
           :begin-run-tests :end-run-tests
           :begin-test-all-vars :end-test-all-vars]]
  (derive t ::propagate))

(t/update-current-env! [:reporter] (constantly :kaocha.type/cljs))

(defn connect! []
  (let [sock (WebSocket. "ws://localhost:9753")]
    (set! socket sock)

    (set! (.-onopen socket)
          (fn [e]
            (prn :open e)
            (send! {:type ::connected})))

    (set! (.-onerror socket)
          (fn [e]
            (prn :error e)))

    (set! (.-onmessage socket)
          (fn [e]
            (prn :message (from-transit (.-data e)))))

    (set! (.-onclose socket)
          (fn [e]
            (prn :close e)))))

(defn disconnect! []
  (when socket
    (set! (.-onclose socket) (fn [_]))
    (.close socket)))
