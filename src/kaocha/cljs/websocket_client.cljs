(ns kaocha.cljs.websocket-client
  (:require [kaocha.cljs.cognitect.transit :as transit]
            [kaocha.cljs.websocket :as ws]
            [kaocha.type.cljs]
            [pjstadig.print :as humane-print]
            [cljs.pprint :as pp :include-macros true]
            [cljs.test :as t]
            [clojure.string :as str]
            [goog.dom :as gdom]
            [goog.log :as glog]
            [goog.object :as gobj]
            [lambdaisland.glogi :as glogi]
            [clojure.browser.repl :as browser-repl])
  (:import [goog.string StringBuffer])
  (:require-macros [kaocha.cljs.hierarchy :as hierarchy]))

(glogi/set-level (str *ns*) (keyword (str/lower-case kaocha.type.cljs/log-level)))

(def socket nil)

(defn record-handler [type]
  (transit/write-handler (constantly type)
                         (fn [val]
                           (into {} val))))


(def transit-handlers
  (merge {:default
          (transit/write-handler
           (fn [o]
             (str (type o)))
           (fn [o]
             (str o)))

          cljs.core/Var
          (transit/write-handler
           (constantly "var")
           (fn [rep] (meta rep)))}
         (when (exists? matcher-combinators.model/Mismatch)
           {^:cljs.analyzer/no-resolve matcher-combinators.model.Mismatch
            (record-handler "matcher-combinators.model.Mismatch")})
         (when (exists? matcher-combinators.model/Missing)
           {^:cljs.analyzer/no-resolve matcher-combinators.model.Missing
            (record-handler "matcher-combinators.model.Missing")})
         (when (exists? matcher-combinators.model/Unexpected)
           {^:cljs.analyzer/no-resolve matcher-combinators.model.Unexpected
            (record-handler "matcher-combinators.model.Unexpected")})
         (when (exists? matcher-combinators.model/InvalidMatcherType)
           {^:cljs.analyzer/no-resolve matcher-combinators.model.InvalidMatcherType
            (record-handler "matcher-combinators.model.InvalidMatcherType")})
         (when (exists? matcher-combinators.model/InvalidMatcherContext)
           {^:cljs.analyzer/no-resolve matcher-combinators.model.InvalidMatcherContext
            (record-handler "matcher-combinators.model.InvalidMatcherContext")})
         (when (exists? matcher-combinators.model/FailedPredicate)
           {^:cljs.analyzer/no-resolve matcher-combinators.model.FailedPredicate
            (record-handler "matcher-combinators.model.FailedPredicate")})
         (when (exists? matcher-combinators.model/TypeMismatch)
           {^:cljs.analyzer/no-resolve matcher-combinators.model.TypeMismatch
            (record-handler "matcher-combinators.model.TypeMismatch")})))

(def transit-writer (transit/writer :json {:handlers transit-handlers}))

(defn to-transit [value]
  (transit/write transit-writer value))

(defn from-transit [string]
  (transit/read (transit/reader :json) string))

(defn send! [message]
  (assert (ws/open? socket))
  (glogi/debug :websocket/send message)
  (when (ws/open? socket)
    (ws/send! socket (to-transit message))))

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

(doseq [t (hierarchy/known-keys)]
  (derive t ::propagate))

(t/update-current-env! [:reporter] (constantly :kaocha.type/cljs))

(defn connect! [port]
  (set! socket
        (ws/connect! (str "ws://localhost:" port "/")
                     {:open
                      (fn [e]
                        (glogi/info :websocket {:callback :onopen :event e})
                        (send! {:type ::connected
                                :browser? (exists? js/document)}))

                      :error
                      (fn [e]
                        (glogi/info :websocket {:callback :onerror :event e})
                        (prn :error e))

                      :message
                      (fn [e]
                        (glogi/info :websocket {:callback :onmessage :event e})
                        (prn :message (from-transit (ws/message-data e))))

                      :close
                      (fn [e]
                        (glogi/info :websocket {:callback :onclose :event e})
                        (prn :close e))})))

(defn disconnect! []
  (when socket
    (glogi/info :msg "Disconnecting websocket")
    (ws/close! socket)))
