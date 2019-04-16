(ns kaocha.cljs.websocket-client
  (:require [kaocha.cljs.cognitect.transit :as transit]
            [pjstadig.print :as humane-print]
            [cljs.pprint :as pp :include-macros true]
            [cljs.test :as t]
            [clojure.string :as str]
            [goog.dom :as gdom]
            [goog.log :as glog]
            [goog.object :as gobj]
            [clojure.browser.repl :as browser-repl])
  (:import [goog.string StringBuffer]))

(defonce logger (glog/getLogger "Kaocha CLJS Client"))

(def WebSocket
  (cond
    (exists? js/WebSocket)
    js/WebSocket

    (exists? js/require)
    (js/require "isomorphic-ws")

    :else
    (throw (ex-info "No WebSocket implementation found." {}))))

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
  (when (and socket (= (.-readyState socket) (.-OPEN socket)))
    (.send socket (to-transit message))))

(defn pretty-print-failure [m]
  (let [buffer (StringBuffer.)]
    (binding [humane-print/*sb* buffer
              *out*             (pp/get-pretty-writer (StringBufferWriter. buffer))]
      (let [{:keys [type expected actual diffs message] :as event}
            (humane-print/convert-event m)
            print-expected (fn [actual]
                             (humane-print/rprint "Expected:\n  ")
                             (pp/pprint expected *out*)
                             (humane-print/rprint "Actual:\n  ")
                             (pp/pprint actual *out*))]
        (if (seq diffs)
          (doseq [[actual [a b]] diffs]
            (print-expected actual)
            (humane-print/rprint "Diff:\n  ")
            (if a
              (do (humane-print/rprint "- ")
                  (pp/pprint a *out*)
                  (humane-print/rprint "  + "))
              (humane-print/rprint "+ "))
            (when b
              (pp/pprint b *out*)))
          (print-expected actual)))
      (str humane-print/*sb*))))

(defn cljs-test-msg [m]
  {:type :cljs.test/message
   :cljs.test/message m
   :cljs.test/testing-contexts (:testing-contexts (t/get-current-env))})

(defmethod t/report [:kaocha.type/cljs ::propagate] [m]
  (send! (cljs-test-msg m)))

(defmethod t/report [:kaocha.type/cljs :fail] [m]
  (send! (-> m
             (assoc :kaocha.report/printed-expression
                    (pretty-print-failure m))
             cljs-test-msg)))

(defmethod t/report [:kaocha.type/cljs :error] [m]
  (let [error      (:actual m)
        stacktrace (.-stack (:actual m))]
    (send! (-> m
               (assoc :kaocha.report/printed-expression
                      (str (str/trim stacktrace) "\n")
                      :kaocha.report/error-type
                      (str "js/" (.-name error))
                      :message
                      (or (:message m) (.-message error)))
               cljs-test-msg))))

(doseq [t [:pass :summary
           :begin-test-ns :end-test-ns
           :begin-test-var :end-test-var
           :begin-run-tests :end-run-tests
           :begin-test-all-vars :end-test-all-vars
           :kaocha.report/one-arg-eql]]
  (derive t ::propagate))

(t/update-current-env! [:reporter] (constantly :kaocha.type/cljs))

(defn connect! []
  (let [sock (WebSocket. "ws://localhost:9753")]
    (set! socket sock)

    (set! (.-onopen socket)
          (fn [e]
            (send! {:type ::connected
                    :browser? (exists? js/document)})))

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

(kaocha.cljs.websocket-client/connect!)
