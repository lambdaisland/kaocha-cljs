(ns kaocha.cljs.prepl
  (:require [cljs.core.server :as cljs-server]
            [clojure.tools.reader.reader-types :as ctr.types]
            [clojure.core.async :as async])
  (:import [java.util.concurrent LinkedBlockingQueue]))

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
      (or (nil? s) (>= s-pos (count s)))
      (do
        (set! s (.take queue))
        (set! s-pos 0)
        (ctr.types/read-char this))

      (= :done s)
      nil

      (> (count s) s-pos)
      (let [r (nth s s-pos)]
        (set! s-pos (inc s-pos))
        r)))

  (peek-char [this]
    (cond
      (or (nil? s) (>= s-pos (count s)))
      (do
        (set! s (.take queue))
        (set! s-pos 0)
        (ctr.types/peek-char this))

      (= :done s)
      nil

      (> (count s) s-pos)
      (nth s s-pos))))

(defn writable-reader []
  (let [queue (LinkedBlockingQueue.)
        reader (->WritableReader queue nil 0)]
    reader))

(defn prepl [repl-env]
  (let [out              *out*
        writable-reader  (writable-reader)
        push-back-reader (ctr.types/push-back-reader writable-reader)
        res-chan         (async/chan)
        eval             (fn [s] (write writable-reader (str s "\n")))
        opts             {}
        reader           (ctr.types/source-logging-push-back-reader push-back-reader)
        out-fn           #(binding [*out* out]
                            (prn %)
                            (async/put! res-chan %))]
    (future (cljs-server/prepl repl-env opts reader out-fn))
    [eval res-chan]))

(require 'cljs.repl.rhino)

(let [[eval chan] (prepl (cljs.repl.rhino/repl-env))]
  (def eval-cljs eval)
  (def res-chan chan))

#_
(async/go-loop [% (async/<! res-chan)]
  (prn %)
  (when (= (:tag % :ret))
    (prn :----> (:val %)))
  (recur (async/<! res-chan)))

#_
(eval-cljs "9999")
