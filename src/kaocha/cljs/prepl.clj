(ns kaocha.cljs.prepl
  (:require [kaocha.cljs.queue-eval-loop :as qel]
            [clojure.java.io :as io]
            [clojure.tools.reader.reader-types :as ctr.types])
  (:import [java.util.concurrent BlockingQueue LinkedBlockingQueue]))

(defn prepl [repl-env compiler-opts ^BlockingQueue queue]
  (let [eval-queue (LinkedBlockingQueue.)
        eval       (fn [form] (.add eval-queue form))
        out-fn     #(.add queue (let [tag (:tag %)]
                                  (assoc (dissoc % :tag) :type (keyword "cljs" (name tag)))))]
    (future
      (try
        (qel/start! repl-env compiler-opts eval-queue out-fn)
        (.add queue {:type ::exit})
        (catch Exception e
          (.add queue {:type ::exit})
          (println "Exception in queue-eval-loop" e))))
    eval))

(comment
  (require 'cljs.repl.node)

  (let [chan (LinkedBlockingQueue.)
        eval (prepl (cljs.repl.node/repl-env) chan)]
    (def eval-cljs eval)
    (def res-chan chan))

  (eval-cljs '(require 'kaocha.cljs.websocket-client :reload))
  (eval-cljs 'kaocha.cljs.websocket-client/socket)
  (eval-cljs '(kaocha.cljs.websocket-client/connect!))
  (eval-cljs '(require 'ktest.first-test))
  (eval-cljs '(ktest.first-test/regular-fail))

  (eval-cljs ':cljs/quit)

  (eval-cljs '(xxx))
  (eval-cljs '(+ 1 1))

  (take-while identity (repeatedly #(.poll res-chan)))

  (cljs.util/ns->source 'ktest.first-test)

  )
