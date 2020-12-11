(require 'kaocha.type.version-check)
(ns kaocha.type.cljs
  (:refer-clojure :exclude [symbol])
  (:require [cljs.analyzer :as ana]
            [cljs.compiler :as comp]
            [cljs.build.api]
            [cljs.env :as env]
            [cljs.repl :as repl]
            cljs.repl.server
            [cljs.test :as ct]
            [cljs.util :as util]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :as t]
            [kaocha.cljs.prepl :as prepl]
            [kaocha.cljs.websocket-server :as ws]
            [kaocha.core-ext :refer :all]
            [kaocha.hierarchy :as hierarchy]
            [kaocha.load :as load]
            [kaocha.result :as result]
            [kaocha.testable :as testable]
            [kaocha.type :as type]
            [kaocha.type.version-check :as version-check]
            [lambdaisland.tools.namespace.file :as ctn.file]
            [lambdaisland.tools.namespace.find :as ctn.find]
            [lambdaisland.tools.namespace.parse :as ctn.parse]
            [slingshot.slingshot :refer [try+]]
            [kaocha.report :as report]
            [kaocha.output :as output]
            [cljs.closure :as closure])
  (:import java.lang.Process
           [java.util.concurrent LinkedBlockingQueue TimeUnit]))

;; Set in tests.edn with {:bindings {kaocha.type.cljs/*debug* true}}
(def ^:dynamic *debug* false)

(require 'kaocha.cljs.print-handlers
         'kaocha.type.var) ;; (defmethod report/fail-summary ::zero-assertions)

(defn ns-testable [ns-sym ns-file]
  {::testable/type ::ns
   ::testable/id (keyword (str "cljs:" ns-sym))
   ::testable/meta (meta ns-sym)
   ::testable/desc (str ns-sym)
   ::ns ns-sym
   ::file ns-file})

(defn test-testable [test-name meta]
  {::testable/type ::test
   ::testable/id (keyword (str "cljs:" test-name))
   ::testable/desc (name test-name)
   ::testable/meta meta
   ::test test-name})

(defn find-cljs-sources-in-dirs [paths]
  (mapcat #(ctn.find/find-sources-in-dir % ctn.find/cljs) paths))

(defn file->ns-name [f]
  (ctn.parse/name-from-ns-decl (ctn.file/read-file-ns-decl f)))

(defmethod testable/-load :kaocha.type/cljs [testable]
  (let [repl-env     (:cljs/repl-env testable 'cljs.repl.node/repl-env)
        copts        (cond-> (:cljs/compiler-options testable {})
                       *debug*
                       (update :closure-defines assoc
                               `log-level "DEBUG"
                               `root-log-level "DEBUG")
                       (= 'cljs.repl.node/repl-env repl-env)
                       (assoc :target :nodejs))
        timeout      (:cljs/timeout testable 15000)
        source-paths (map io/file (:kaocha/source-paths testable))
        test-paths   (map io/file (:kaocha/test-paths testable))
        ns-patterns  (map regex (:kaocha/ns-patterns testable))
        test-files   (find-cljs-sources-in-dirs test-paths)
        cenv         (env/default-compiler-env (closure/add-implicit-options copts))
        precompile?  (:cljs/precompile? testable
                                        (get '#{figwheel.repl/repl-env} repl-env false))
        testables    (keep (fn [ns-file]
                             (let [ns-sym (file->ns-name ns-file)]
                               (when (load/ns-match? ns-patterns ns-sym)
                                 (ns-testable ns-sym ns-file))))
                           test-files)
        ]
    (if (version-check/meets-minimum-cljs-version 1 10)
      
      (assoc testable
             :cljs/compiler-options copts
             :cljs/repl-env repl-env
             :cljs/timeout timeout
             :cljs/compiler-env cenv
             :kaocha.test-plan/tests
             (env/with-compiler-env cenv
               (when precompile?
                 (cljs.build.api/build (into #{} cat [source-paths test-paths]) copts))
               (comp/with-core-cljs {}
                 (fn []
                   (testable/load-testables testables)))))
      (assoc testable :kaocha.testable/load-error 
             (ex-info "ClojureScript version too low" {:expected ">=1.10"  :got (cljs.util/clojurescript-version) }))
      )))

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
     (assoc state
            :ws/connected? true))

   ::ws/disconnect
   (fn [_ state]
     (assoc state
            :ws/disconnected? true
            :ws/connected? false))

   :cljs.test/message
   (fn [msg state]
     (binding [t/*testing-contexts* (:cljs.test/testing-contexts msg)]
       (t/do-report (:cljs.test/message msg)))
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

   :cljs/exists
   (fn [m state]
     (assoc state :cljs/exists (:symbol m)))

   ::fixture-loaded
   (fn [{[var-or-ns before-or-after] :fixture} state]
     (update-in state [:loaded-fixtures var-or-ns] (fnil conj #{}) before-or-after))

   :kaocha.cljs.websocket-client/connected
   (fn [msg state]
     (assoc state
            :ws-client/ack? true
            :repl-env/browser? (:browser? msg)))

   :kaocha.cljs.run/test-finished
   (fn [{:keys [test]} state]
     (assoc state :cljs.test/last-finished-test test))

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
          (when *debug*
            (prn (:type message) '-> message))
          (if-let [handler (get handlers (:type message))]
            (let [state (handler message state)]
              #_(prn "    STATE" state)
              (if-let [result (result state)]
                result
                (recur (poll) state)))
            (recur (poll) state)))))))

(defn- alive? [^Process proc]
  (when proc
    (try (.exitValue proc) false (catch IllegalThreadStateException _ true))))

(defn- kill! [^Process proc]
  (when (alive? proc)
    (.destroyForcibly proc)))

(defn file-relative [file]
  (str/replace file (str (.getCanonicalPath (io/file ".")) "/") ""))

(defmacro try-all
  "Run all forms, even if a previous form throws an error. Good for cleanup. If
  any of the forms throws then the last exception will be re-thrown."
  [& forms]
  (when-first [form forms]
    `(try
       ~form
       (catch Exception e#
         (try-all ~@(next forms))
         (throw e#))
       (finally
         (try-all ~@(next forms))))))

(defmethod testable/-run :kaocha.type/cljs [{:cljs/keys [compiler-options compiler-env repl-env timeout] :as testable} test-plan]
  (t/do-report {:type :begin-test-suite})

  ;; Somehow these don't get cleaned up properly, leading to issues when
  ;; starting a new browser-based environment a second time in the same process.
  ;; Drain the queues so we're at a clean slate once more.
  (while (.poll cljs.repl.server/connq))
  (while (.poll cljs.repl.server/promiseq))

  (when *debug*
    (println {:cljs/compiler-options (closure/add-implicit-options compiler-options)}))

  (let [queue    (LinkedBlockingQueue.)
        renv     (do (require (symbol (namespace repl-env)))
                     (mapply (resolve repl-env) compiler-options))
        stop-ws! (ws/start! queue)
        port     (:local-port (meta stop-ws!))]
    (try
      (let [eval (prepl/prepl renv compiler-env compiler-options queue)
            done (keyword (gensym "require-websocket-client-done"))
            eval (if *debug*
                   (fn [form]
                     (println "EVAL: " form)
                     (eval form))
                   eval)
            limited-testable (select-keys testable [:kaocha.testable/id :cljs/repl-env :cljs/compiler-options])]
        (try
          (when (io/resource "matcher_combinators/model.cljc")
            (eval '(require 'matcher-combinators.model)))

          (eval '(require 'kaocha.cljs.websocket-client
                          'kaocha.cljs.run))

          (eval `((~'fn ~'wait-for-websocket-client []
                    (if (~'exists? kaocha.cljs.websocket-client)
                      (kaocha.cljs.websocket-client/connect! ~port)
                      (js/setTimeout ~'wait-for-websocket-client 50)))))
          (eval done)

          (queue-consumer {:queue queue
                           :timeout timeout
                           :handlers {:cljs/ret
                                      (fn [{:keys [val] :as event} state]
                                        (when (map? val)
                                          (throw (ex-info "ClojureScript Error while loading Kaocha websocket client" event)))
                                        (cond-> state
                                          (= (str done) val)
                                          (assoc :eval-done? true)))

                                      :cljs/out
                                      (fn [{:keys [val]} state]
                                        (if (= "NODE_WS_NOT_FOUND\n" val)
                                          (throw (ex-info "Nodejs: require('ws') failed, make sure to 'npm install ws'."
                                                          (merge limited-testable state)))
                                          (println val))
                                        state)

                                      :timeout
                                      (fn [{:keys [eval-done?] :as state}]
                                        (if eval-done?
                                          (throw (ex-info "Failed initializing ClojureScript runtime" (merge limited-testable state)))
                                          (throw (ex-info "Kaocha ClojureScript client failed connecting back." (merge limited-testable state)))))}

                           :result (fn [state]
                                     (and (:eval-done? state)
                                          (:ws-client/ack? state)))})

          (let [tests (testable/run-testables (map #(assoc %
                                                           ::eval eval
                                                           ::queue queue
                                                           ::timeout timeout)
                                                   (:kaocha.test-plan/tests testable))
                                              test-plan)
                timeout? (some ::timeout? tests)]

            (if-let [proc (and timeout? (:proc renv))]
              (kill! @proc)
              (do
                (eval '(do (kaocha.cljs.websocket-client/disconnect!) :cljs/quit))
                (queue-consumer {:queue queue
                                 :timeout timeout
                                 :handlers {:timeout
                                            (fn [state]
                                              (throw (ex-info "Failed cleaning up ClojureScript runtime" state)))}

                                 :result (fn [{ws-disconnected? :ws/disconnected?
                                               prepl-exit? ::prepl/exit?}]
                                           (and ws-disconnected? prepl-exit?))})))

            (t/do-report {:type :end-test-suite})
            (assoc (dissoc testable :kaocha.test-plan/tests) :kaocha.result/tests tests))
          (finally
            (eval :cljs/quit))))
      (catch Exception e
        (t/do-report {:type                    :error
                      :message                 "Unexpected error executing kaocha-cljs test suite."
                      :expected                nil
                      :actual                  e
                      :kaocha.result/exception e})
        (t/do-report {:type :end-test-suite})
        (assoc testable :kaocha.result/error 1))
      (finally
        (try-all
         (when-let [proc (:proc renv)] (kill! @proc))
         (try (repl/tear-down renv) (catch Exception e))
         (stop-ws!))))))

(defn run-once-fixtures [{::keys [queue timeout eval]} ns before-or-after]
  (eval `(kaocha.cljs.run/run-once-fixtures
          ~ns ~before-or-after
          (~'fn []
           (kaocha.cljs.websocket-client/send!
            {:type ::fixture-loaded
             :fixture ['~ns ~before-or-after]}))))

  (queue-consumer {:queue queue
                   :timeout timeout
                   :handlers {:timeout
                              (fn [state]
                                (throw (ex-info (str "Timeout running :before :once fixtures on ns " ns) {})))}
                   :result (fn [state]
                             (get-in state [:loaded-fixtures ns before-or-after]))}))

(defmethod testable/-run ::ns [{::keys [ns eval queue timeout] :as testable} test-plan]
  (t/do-report {:type :begin-test-ns})
  (let [js-file (-> ns str (str/replace "-" "_") (str/replace "." "/") (str ".js"))
        done (keyword (gensym "require-ns-done"))]
    (eval `(~'require '~ns))
    (eval `((~'fn ~'wait-for-symbol []
             (if (~'exists? ~ns)
               (kaocha.cljs.websocket-client/send! {:type :cljs/exists :symbol '~ns})
               (js/setTimeout ~'wait-for-symbol 50)))))
    (eval done)

    (queue-consumer {:queue queue
                     :timeout timeout
                     :handlers {:timeout
                                (fn [state]
                                  (throw (ex-info (str "Timeout loading ClojureScript namespace " ns) testable)))}

                     :result (fn [{last-val :cljs/last-val
                                   exists :cljs/exists
                                   browser? :repl-env/browser?}]
                               (and last-val exists
                                    (= (str done) last-val)
                                    (= ns exists)))}))

  (run-once-fixtures testable ns :before)

  (let [tests (map #(assoc %
                      ::eval eval
                      ::timeout timeout
                      ::queue queue)
                   (:kaocha.test-plan/tests testable))
        result (testable/run-testables tests test-plan)
        timeout? (some ::timeout? result)]

    (when-not timeout?
      (run-once-fixtures testable ns :after))

    (t/do-report {:type :end-test-ns})
    (merge (dissoc testable :kaocha.test-plan/tests)
           {:kaocha.result/tests result}
           (when timeout?
             {::testable/skip-remaining? true
              ::timeout? true}))))

(defn run-test [{::keys [test eval queue timeout]}]
  (let [done (keyword (gensym (str test "-done")))]
    (eval `(do
             (kaocha.cljs.run/run-test  ~test)
             ~done))

    (queue-consumer
     {:queue queue
      :timeout timeout
      :handlers {:timeout
                 (fn [state]
                   (t/do-report {:type ::timeout
                                 :message "Test timed out, skipping other tests. Consider increasing :cljs/timeout."})
                   :timeout)}

      :result (fn [{last-val :cljs/last-val
                    last-test :cljs.test/last-finished-test}]
                (and (= (str done) last-val)
                     (= test last-test)))})))

(defmethod testable/-run ::test [{::keys          [test eval queue timeout]
                                  ::testable/keys [wrap]
                                  :as             testable}
                                 test-plan]
  (type/with-report-counters
    (let [run    (reduce #(%2 %1) #(run-test testable) wrap)
          result (run)]
      (let [{::result/keys [pass error fail pending]} (type/report-count)]
        (when (= pass error fail pending 0)
          (binding [testable/*fail-fast?* false]
            (t/do-report {:type :kaocha.type.var/zero-assertions
                          :file (file-relative (:file (::testable/meta testable)))
                          :line (:line (::testable/meta testable))})))
        (merge testable
               {:kaocha.result/count 1}
               (type/report-count)
               (when (= :timeout result)
                 {::timeout?                 true
                  ::testable/skip-remaining? true}))))))

(hierarchy/derive! :kaocha.type/cljs :kaocha.testable.type/suite)
(hierarchy/derive! ::ns :kaocha.testable.type/group)
(hierarchy/derive! ::test :kaocha.testable.type/leaf)
(hierarchy/derive! ::timeout :kaocha/fail-type)

(s/def :kaocha.type/cljs any? #_(s/keys :req [:kaocha/source-paths
                                              :kaocha/test-paths
                                              :kaocha/ns-patterns]
                                        :opt [:cljs/compiler-options]))

(s/def :cljs/compiler-options any?)
(s/def ::ns any?)
(s/def ::test any?)

(defmethod ct/assert-expr '= [menv msg form]
  (if (= 2 (count form))
    `(ct/do-report {:type :kaocha.report/one-arg-eql
                    :message "Equality assertion expects 2 or more values to compare, but only 1 arguments given."
                    :expected '~(concat form '(arg2))
                    :actual '~form
                    :file ~(file-relative (:file (meta form)))
                    :line ~(:line (meta form))})
    (ct/assert-predicate msg form)))

(defmethod report/dots* ::timeout [m]
  (t/with-test-out
    (print (output/colored :red "T"))
    (flush)) )

(comment
  (require 'kaocha.repl)

  (kaocha.repl/run :cljs {:kaocha/tests [{:kaocha.testable/type :kaocha.type/cljs
                                          :kaocha.testable/id   :cljs
                                          :kaocha/source-paths  ["src"]
                                          :kaocha/test-paths    ["test/cljs"]
                                          :kaocha/ns-patterns   [".*-test$"]
                                          :cljs/timeout 50000
                                          :cljs/repl-env 'cljs.repl.browser/repl-env
                                          }]
                          :kaocha.plugin.capture-output/capture-output? false
                          :kaocha/reporter ['kaocha.report/documentation]})

  (require 'kaocha.type.var)

  )
