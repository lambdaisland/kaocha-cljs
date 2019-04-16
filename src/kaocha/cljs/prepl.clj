(ns kaocha.cljs.prepl
  (:require [cljs.core.server :as cljs-server]
            [clojure.java.io :as io]
            [clojure.tools.reader.reader-types :as ctr.types])
  (:import [java.util.concurrent BlockingQueue LinkedBlockingQueue]))

(defprotocol Writable
  (write [writer string]))

(deftype WritableReader [^java.util.Queue queue
                         ^:unsynchronized-mutable ^String s
                         ^:unsynchronized-mutable ^long s-pos]
  Writable
  (write [this string]
    (.put queue string))

  ctr.types/Reader
  (read-char [this]
    (cond
      (= :done s)
      nil

      (or (nil? s) (>= s-pos (count s)))
      (do
        (set! s (.take queue))
        (set! s-pos 0)
        (when-not (= :done s)
          (ctr.types/read-char this)))

      (> (count s) s-pos)
      (let [r (nth s s-pos)]
        (set! s-pos (inc s-pos))
        r)))

  (peek-char [this]
    (cond
      (= :done s)
      nil

      (or (nil? s) (>= s-pos (count s)))
      (do
        (set! s (.take queue))
        (set! s-pos 0)
        (when-not (= :done s)
          (ctr.types/peek-char this)))

      (> (count s) s-pos)
      (nth s s-pos))))

(defn writable-reader []
  (let [queue (LinkedBlockingQueue.)
        reader (->WritableReader queue nil 0)]
    reader))

(defn prepl [repl-env ^BlockingQueue queue]
  (let [writable-reader  (writable-reader)
        push-back-reader (ctr.types/push-back-reader writable-reader)
        eval             (fn [s]
                           #_(println s)
                           (write writable-reader (str s "\n")))
        opts             {}
        reader           (ctr.types/source-logging-push-back-reader push-back-reader)
        out-fn           #(.offer queue (let [tag (:tag %)]
                                          (assoc (dissoc % :tag) :type (keyword "cljs" (name tag)))))]
    (future
      (try
        (cljs-server/prepl repl-env opts reader out-fn)
        (.offer (.-queue writable-reader) :done)
        (.offer queue {:type ::exit})
        (catch Exception e
          (.offer (.-queue writable-reader) :done)
          (.offer queue {:type ::exit})
          (println "Exception in prepl" e))))
    eval))

(comment
  (require 'cljs.repl.node)

  (let [chan (LinkedBlockingQueue.)
        eval (prepl (cljs.repl.node/repl-env) chan)]
    (def eval-cljs eval)
    (def res-chan chan))

  (eval-cljs "(require 'kaocha.cljs.websocket-client :reload)")
  (eval-cljs "kaocha.cljs.websocket-client/socket")
  (eval-cljs "(kaocha.cljs.websocket-client/connect!)")
  (eval-cljs "(require 'ktest.first-test)")
  (eval-cljs "(ktest.first-test/regular-fail)")

  (eval-cljs ":cljs/quit")

  (eval-cljs "(xxx)")

  (io/resource "ktest/first_test.cljs")
  (.getContextClassLoader (Thread/currentThread))
  (io/resource "ktest/first_test.cljs"), :cljc ktest/first_test.cljc

  (eval-cljs "(require 'kaocha.cljs.websocket-client) (kaocha.cljs.websocket-client/connect!) :done")

  (take-while identity (repeatedly #(.poll res-chan)))

  (cljs.util/ns->source 'ktest.first-test)

  )
