(ns kaocha.type.cljs
  (:require [cljs.analyzer :as ana]
            [cljs.compiler :as comp]
            [cljs.env :as env]
            [cljs.repl.node :as node]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.test :as t]
            [kaocha.cljs.prepl :as prepl]
            [kaocha.cljs.websocket-server :as ws]
            [kaocha.core-ext :refer :all]
            [kaocha.hierarchy :as hierarchy]
            [kaocha.load :as load]
            [kaocha.output :as output]
            [kaocha.testable :as testable]
            [lambdaisland.tools.namespace.file :as ctn.file]
            [lambdaisland.tools.namespace.find :as ctn.find]
            [lambdaisland.tools.namespace.parse :as ctn.parse]
            [kaocha.type :as type]
            [kaocha.result :as result])
  (:import [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(defn ns-testable [ns-name ns-file]
  {::testable/type ::ns
   ::testable/id (keyword ns-name)
   ::testable/meta (meta ns-name)
   ::testable/desc (str ns-name)
   ::ns ns-name
   ::file ns-file})

(defn test-testable [name meta]
  {::testable/type ::test
   ::testable/id (keyword name)
   ::testable/desc (str name)
   ::testable/meta meta
   ::test name})

(defn find-cljs-sources-in-dirs [paths]
  (mapcat #(ctn.find/find-sources-in-dir % ctn.find/cljs) paths))

(defn file->ns-name [f]
  (ctn.parse/name-from-ns-decl (ctn.file/read-file-ns-decl f)))

(defmethod testable/-load :kaocha.type/cljs [testable]
  (let [options     (:cljs/compiler-options testable {})
        repl-env    (:cljs/repl-env testable 'cljs.repl.node/repl-env)
        timeout     (:cljs/timeout testable 5000)
        test-paths  (map io/file (:kaocha/test-paths testable))
        ns-patterns (map regex (:kaocha/ns-patterns testable))
        test-files  (find-cljs-sources-in-dirs test-paths)
        cenv        (env/default-compiler-env options)
        testables   (keep (fn [ns-file]
                            (let [ns-sym (file->ns-name ns-file)]
                              (when (load/ns-match? ns-patterns ns-sym)
                                (ns-testable ns-sym ns-file))))
                          test-files)]
    (assoc testable
           :cljs/compiler-options options
           :cljs/repl-env repl-env
           :cljs/timeout timeout
           :kaocha.test-plan/tests
           (env/with-compiler-env cenv
             (comp/with-core-cljs {}
               (fn []
                 (testable/load-testables testables)))))))

(defmethod testable/-load ::ns [testable]
  (let [ns-name (::ns testable)
        ns-file (::file testable)]
    (ana/analyze-file ns-file)
    (let [tests (filter :test (-> @env/*compiler*
                                  :cljs.analyzer/namespaces
                                  (get ns-name)
                                  :defs
                                  vals))
          testables (map #(test-testable (:name %) (:meta %)) tests)]
      (assoc testable
             :kaocha.test-plan/tests
             testables))))

(defmethod testable/-load ::test [testable]
  testable)

(def default-queue-handlers
  {::ws/connect
   (fn [_ state]
     (assoc state :ws/connected? true))

   ::ws/disconnect
   (fn [_ state]
     (assoc state
            :ws/disconnected? true
            :ws/connected? false))

   :cljs.test/message
   (fn [msg state]
     (t/do-report (:cljs.test/message msg))
     state)

   :cljs/out
   (fn [{:keys [val]} state]
     (println val)
     state)

   :cljs/err
   (fn [{:keys [val]} state]
     (binding [*out* *err*]
       (println val))
     state)

   :cljs/ret
   (fn [{:keys [val form]} state]
     (when (map? val)
       (throw (ex-info "ClojureScript Exception" val)))
     (cond-> state
       (= ":cljs/quit" form)
       (assoc :cljs/quit? true)

       :always
       (assoc :cljs/last-val val)))

   :kaocha.cljs.websocket-client/connected
   (fn [_ state]
     (assoc state :ws-client/ack? true))

   ::prepl/exit
   (fn [_ state]
     (assoc state ::prepl/exit? true))})

(defn queue-consumer [{:keys [queue timeout handlers result]}]
  (let [poll #(.poll queue timeout TimeUnit/MILLISECONDS)
        handlers (merge default-queue-handlers handlers)]
    (loop [message (poll)
           state {}]

      (if (nil? message)
        (if-let [timeout-handler (get handlers :timeout)]
          (timeout-handler state)
          :timeout)

        (let [message (cond-> message (contains? message :message) :message)]
          #_(prn (:type message) message)
          (if-let [handler (get handlers (:type message))]
            (let [state (handler message state)]
              #_(prn "    STATE" state)
              (if-let [result (result state)]
                result
                (recur (poll) state)))
            (recur (poll) state)))))))

(defmethod testable/-run :kaocha.type/cljs [{:cljs/keys [compiler-options repl-env timeout] :as testable} test-plan]
  (t/do-report {:type :begin-test-suite})
  (let [queue    (LinkedBlockingQueue.)
        renv     (do (require (symbol (namespace repl-env)))
                     (mapply (resolve repl-env) compiler-options))
        stop-ws! (ws/start! queue)]
    (try
      (let [eval (prepl/prepl renv queue)]
        (eval "(require 'kaocha.cljs.websocket-client) (kaocha.cljs.websocket-client/connect!) :done")

        (queue-consumer {:queue queue
                         :timeout timeout
                         :handlers {:cljs/ret
                                    (fn [{:keys [val] :as event} state]
                                      (when (map? val)
                                        (throw (ex-info "ClojureScript Error while loading Kaocha websocket client" event)))
                                      (cond-> state
                                        (= ":done" val)
                                        (assoc :eval-done? true)))

                                    :timeout
                                    (fn [{:keys [eval-done?]}]
                                      (if eval-done?
                                        (throw (ex-info "Failed initializing ClojureScript runtime" testable))
                                        (throw (ex-info "Kaocha ClojureScript client failed connecting back." testable))))}

                         :result (fn [state]
                                   (and (:eval-done? state)
                                        (:ws-client/ack? state)))})

        (let [tests (testable/run-testables (map #(assoc %
                                                         ::eval eval
                                                         ::queue queue
                                                         ::timeout timeout)
                                                 (:kaocha.test-plan/tests testable))
                                            test-plan)]

          (eval "(kaocha.cljs.websocket-client/disconnect!) :cljs/quit")
          (queue-consumer {:queue queue
                           :timeout timeout
                           :handlers {:timeout
                                      (fn [state]
                                        (throw (ex-info "Failed cleaning up ClojureScript runtime" state)))}

                           :result (fn [{ws-disconnected? :ws/disconnected?
                                         prepl-exit? ::prepl/exit?}]
                                     (and ws-disconnected? prepl-exit?))})

          (t/do-report {:type :end-test-suite})
          (assoc (dissoc testable :kaocha.test-plan/tests) :kaocha.result/tests tests)))
      (finally
        (stop-ws!)))))

(defmethod testable/-run ::ns [{::keys [ns eval queue timeout] :as testable} test-plan]
  (t/do-report {:type :begin-test-ns})
  (eval (str "(require '" ns ") :done"))
  (queue-consumer {:queue queue
                   :timeout timeout
                   :handlers {:timeout
                              (fn [state]
                                (throw (ex-info (str "Timeout loading ClojureScript namespace " ns) testable)))}

                   :result (fn [{last-val :cljs/last-val}]
                             (= ":done" last-val))})
  (let [tests (testable/run-testables (map #(assoc %
                                                   ::eval eval
                                                   ::timeout timeout
                                                   ::queue queue)
                                           (:kaocha.test-plan/tests testable))
                                      test-plan)]
    (t/do-report {:type :end-test-ns})
    (assoc (dissoc testable :kaocha.test-plan/tests) :kaocha.result/tests tests)))

(defmethod testable/-run ::test [{::keys [test eval queue timeout] :as testable} test-plan]
  (type/with-report-counters
    (eval (str "(" test ") :done"))
    (queue-consumer {:queue queue
                     :timeout timeout
                     :handlers {:timeout
                                (fn [state]
                                  (throw (ex-info (str "Timeout running ClojureScript test " test) testable)))}

                     :result (fn [{last-val :cljs/last-val}]
                               (= ":done" last-val))})

    (let [{::result/keys [pass error fail pending] :as result} (type/report-count)]
      (when (= pass error fail pending 0)
        (binding [testable/*fail-fast?* false]
          (t/do-report {:type ::zero-assertions})))
      (merge testable {:kaocha.result/count 1} (type/report-count)))))

(hierarchy/derive! ::test :kaocha.testable.type/leaf)

(s/def :kaocha.type/cljs any? #_(s/keys :req [:kaocha/source-paths
                                              :kaocha/test-paths
                                              :kaocha/ns-patterns]
                                        :opt [:cljs/compiler-options]))

(s/def :cljs/compiler-options any?)
(s/def ::ns any?)
(s/def ::test any?)


(comment
  (require 'kaocha.repl)

  (kaocha.repl/run :cljs {:kaocha/tests [{:kaocha.testable/type :kaocha.type/cljs
                                          :kaocha.testable/desc ""
                                          :kaocha.testable/id   :cljs
                                          :kaocha/source-paths  ["src"]
                                          :kaocha/test-paths    ["test/cljs"]
                                          :kaocha/ns-patterns   [".*-test$"]
                                          :cljs/timeout 5000}]
                          :kaocha.plugin.capture-output/capture-output? false
                          :kaocha/reporter [kaocha.report/documentation]})

  )
