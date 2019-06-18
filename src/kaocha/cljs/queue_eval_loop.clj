(ns kaocha.cljs.queue-eval-loop
  (:refer-clojure :exclude [with-bindings resolve-fn prepl io-prepl])
  (:require [cljs.env :as env]
            [cljs.closure :as closure]
            [cljs.analyzer :as ana]
            [cljs.analyzer.api :as ana-api]
            [cljs.repl :as repl]
            [cljs.compiler :as comp]
            [cljs.tagged-literals :as tags]))

(defprotocol Input
  (take! [this]))

(extend-protocol Input
  java.util.Queue
  (take! [q] (.take q))

  ;; clojure.core.async.impl.channels.ManyToManyChanne
  ;; (take! [q] (<! q))
  )

(defmacro with-bindings [& body]
  `(binding [ana/*cljs-ns* ana/*cljs-ns*
             ana/*unchecked-if* ana/*unchecked-if*
             ana/*unchecked-arrays* ana/*unchecked-arrays*]
     ~@body))

(defn- resolve-fn [valf]
  (if (symbol? valf)
    (or (resolve valf)
        (when-let [nsname (namespace valf)]
          (require (symbol nsname))
          (resolve valf))
        (throw (Exception. (str "can't resolve: " valf))))
    valf))

(defn repl-quit? [v]
  (#{":repl/quit" ":cljs/quit"} v))

(defn start!
  "Like a prepl, but takes a queue which delivers forms, rather than a reader."
  [repl-env {:keys [special-fns] :as opts} in out-fn & {:keys [stdin]}]
  (let [repl-opts      (repl/repl-options repl-env)
        opts           (merge
                        {:def-emits-var true}
                        (closure/add-implicit-options
                         (merge-with (fn [a b] (if (nil? b) a b))
                           repl-opts opts)))
        tapfn          #(out-fn {:tag :tap :val %1})
        env            (ana-api/empty-env)
        special-fns    (merge repl/default-special-fns special-fns)
        is-special-fn? (set (keys special-fns))]
    (env/ensure
     (repl/maybe-install-npm-deps opts)
     (comp/with-core-cljs opts
       (fn []
         (with-bindings
           (binding [*in*            (or stdin *in*)
                     *out*           (PrintWriter-on #(out-fn {:tag :out :val %1}) nil)
                     *err*           (PrintWriter-on #(out-fn {:tag :err :val %1}) nil)
                     repl/*repl-env* repl-env]
             (let [opts (merge opts (:merge-opts (repl/setup repl-env opts)))]
               (binding [repl/*repl-opts* opts]
                 (repl/evaluate-form repl-env env "<cljs repl>"
                                     (with-meta `(~'ns ~'cljs.user) {:line 1 :column 1}) identity opts)
                 (try
                   (add-tap tapfn)
                   (loop []
                     (when (try
                             (let [form (take! in)
                                   s    (pr-str form)]
                               (try
                                 (let [start (System/nanoTime)
                                       ret   (if (and (seq? form) (is-special-fn? (first form)))
                                               (do
                                                 ((get special-fns (first form)) repl-env env form opts)
                                                 "nil")
                                               (repl/eval-cljs repl-env env form opts))
                                       ms    (quot (- (System/nanoTime) start) 1000000)]
                                   (when-not (repl-quit? ret)
                                     (out-fn {:tag  :ret
                                              :val  (if (instance? Throwable ret)
                                                      (Throwable->map ret)
                                                      ret)
                                              :ns   (name ana/*cljs-ns*)
                                              :ms   ms
                                              :form s})
                                     true))
                                 (catch Throwable ex
                                   (out-fn {:tag :ret                 :val  (Throwable->map ex)
                                            :ns  (name ana/*cljs-ns*) :form s})
                                   true)))
                             (catch Throwable ex
                               (out-fn {:tag :ret :val (Throwable->map ex)
                                        :ns  (name ana/*cljs-ns*)})
                               true))
                       (recur)))
                   (finally
                     (remove-tap tapfn)
                     (repl/tear-down repl-env))))))))))))
