(ns repl.cljs-repl
  (:require [cljs.repl :as repl]
            [cljs.env :as env]
            [cljs.closure :as closure]
            [cljs.analyzer :as ana]
            [cljs.analyzer.api :as ana-api]
            [cljs.compiler :as compiler]
            [cljs.repl.node :as node]
            [cljs.repl.rhino :as rhino]
            [cljs.core.server :as server]))

(def repl-do
  (let [repl-env    (node/repl-env)#_(rhino/repl-env)
        repl-opts   (repl/repl-options repl-env)
        opts        {}
        opts        (merge
                     {:def-emits-var true}
                     (closure/add-implicit-options
                      (merge-with (fn [a b] (if (nil? b) a b))
                                  repl-opts opts)))
        env         (ana-api/empty-env)
        special-fns repl/default-special-fns
        ]
    ;; ensures cljs.env/*compiler* is set to (env/default-compiler-env)
    (env/ensure
     (repl/maybe-install-npm-deps opts)
     ;; essentially does
     ;;(when-not (get-in @env/*compiler* [::ana/namespaces 'cljs.core :defs])
     ;; (ana/analyze-file "cljs/core.cljs" opts))
     (compiler/with-core-cljs opts
       (fn []
         (server/with-bindings
           (binding [repl/*repl-env* repl-env]
             (let [opts (merge opts (:merge-opts (repl/setup repl-env opts)))]
               (binding [repl/*repl-opts* opts]
                 (repl/evaluate-form repl-env env "<cljs repl>"
                                     (with-meta `(~'ns ~'cljs.user) {:line 1 :column 1}) identity opts)
                 (bound-fn [f]
                   (f repl-env env opts))

                 #_(bound-fn [form]
                     #_['env/*compiler* env/*compiler*]
                     (repl/eval-cljs repl-env env form opts))))))

         )
       )
     )))

(eval '(test/run-all-tests))
(eval '(cljs.core/require '[cljs.test :as test]))
(eval '(require '[kaocha.cljs-test]))
(eval '(:test (:meta (kaocha.cljs-test/foo-test))))
(eval '(prn :test))

(repl-do (fn [repl-env env opts]
           (repl/eval-cljs repl-env env '(require '[clojure.test :as test]))))

(repl-do (fn [repl-env env opts]
           (repl/eval-cljs repl-env env '(require '[kaocha.cljs-test :as kc]))))

(repl-do (fn [repl-env env opts]
           (repl/eval-cljs repl-env env '((:test (meta #'kc/fail-test))))))

(repl-do (fn [repl-env env opts]
           (repl/eval-cljs repl-env env '(cljs.test/set-env! (cljs.test/empty-env :cljs.test/pprint)))))
"{:report-counters {:test 0, :pass 0, :fail 0, :error 0}, :testing-vars (), :testing-contexts (), :formatter #object[cljs$pprint$pprint], :reporter :cljs.test/default}"

(repl-do (fn [& _]
           ana/*cljs-ns*))

(repl-do (fn [repl-env env opts]
           (ana/get-namespace 'cljs.test)))

(repl-do (fn [repl-env env opts]
           (repl/eval-cljs repl-env env '(cljs.core/macroexpand-1 '(cljs.test/run-block )))))
