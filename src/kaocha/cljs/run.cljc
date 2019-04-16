(ns kaocha.cljs.run
  (:require [cljs.test :as t]
            [cljs.analyzer.api :as ana-api]
            #?(:cljs [kaocha.cljs.websocket-client :as websocket])))

#?(:clj
   (do
     (defmacro run-once-fixtures [ns before-or-after done]
       (when (ana-api/ns-resolve ns 'cljs-test-once-fixtures)
         (let [fix-sym (symbol (name ns) "cljs-test-once-fixtures")]
           `(do
              (cond
                (some fn? ~fix-sym)
                (throw (js/Error. "Kaocha only supports asynchronous fixtures, instead of a function use a map with `:before` and `:after` keys."))

                (every? map? ~fix-sym)
                (t/run-block
                 (concat
                  (keep ~before-or-after ~fix-sym)
                  [~done]))

                :else
                (do
                  (println "nope...")
                  (~done)))))))

     (defmacro load-each-fixtures [ns]
       `(let [env# (t/get-current-env)]
          (fn []
            (when (nil? env#)
              (t/set-env! (t/empty-env)))
            ~(when (ana-api/ns-resolve ns 'cljs-test-each-fixtures)
               `(t/update-current-env! [:each-fixtures] assoc '~ns
                                       ~(symbol (name ns) "cljs-test-each-fixtures"))))))

     (defmacro run-test [test-sym]
       `(t/run-block
         (concat
          [(load-each-fixtures ~(symbol (namespace test-sym)))]
          (t/test-vars-block [(var ~test-sym)])
          [#(kaocha.cljs.websocket-client/send! {:type ::test-finished :test '~test-sym})])))))
