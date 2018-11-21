(ns kaocha.type.cljs
  (:require [kaocha.testable :as testable]
            [clojure.spec.alpha :as s]
            [kaocha.load :as load]
            [cljs.util :as util]
            [cljs.analyzer :as ana]
            [cljs.env :as env]
            [clojure.java.io :as io]
            [clojure.tools.namespace.find :as ctn.find]))

(load/find-test-nss ["/home/arne/github/kaocha-cljs/test/cljs"] [#".*-test$"] load/cljs)
;; => (ktest.first-test)

(ana/parse-ns (io/reader (io/file "/home/arne/github/kaocha-cljs/test/cljs/ktest/first_test.cljs")))
(ana/analyze-file (io/file "/home/arne/github/kaocha-cljs/test/cljs/ktest/first_test.cljs"))


(ctn.find/find-ns-decls-in-dir (io/file "/home/arne/github/kaocha-cljs/test/cljs/") load/cljs)
;; => ((ns ktest.first-test (:require [clojure.test :as t :refer [is testing]])))

(ctn.find/find-namespaces [(io/file "/home/arne/github/kaocha-cljs/test/cljs/")] load/cljs)
;; => (ktest.first-test)

(ctn.find/find-sources-in-dir (io/file "/home/arne/github/kaocha-cljs/test/cljs/") load/cljs)
;; => (#object[java.io.File 0x1f53284e "/home/arne/github/kaocha-cljs/test/cljs/ktest/first_test.cljs"])

(def env
  (binding [env/*compiler* (env/default-compiler-env)
            ana/*cljs-ns* 'cljs.user]
    (let [opts {}
          env (assoc (ana/empty-env) :build-options opts)
          res (io/file "/home/arne/github/kaocha-cljs/test/cljs/ktest/first_test.cljs")]
      (with-open [rdr (io/reader res)]
        (loop [ns nil
               forms (seq (ana/forms-seq* rdr (util/path res)))]
          (if forms
            (let [form (first forms)
                  env (assoc env :ns (ana/get-namespace ana/*cljs-ns*))
                  ast (ana/analyze env form nil opts)]
              (prn form)
              (cond
                (= (:op ast) :ns)
                (recur (:name ast) (next forms))

                (and (nil? ns) (= (:op ast) :ns*))
                (recur (ana/gen-user-ns res) (next forms))

                :else
                (recur ns (next forms))))
            ns))))

    env/*compiler*
    ))

(-> @env
    :cljs.analyzer/namespaces
    (get 'ktest.first-test)
    :defs
    vals
    (->> (filter :test)
         (map (juxt :name :meta))))

(defmethod testable/-load :kaocha.type/cljs [testable]
  testable
  )

(defmethod testable/-run :kaocha.type/cljs [testable]
  testable
  )

(s/def :kaocha.type/cljs any?)
