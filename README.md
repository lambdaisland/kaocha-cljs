# kaocha-cljs

<!-- badges -->
[![CircleCI](https://circleci.com/gh/lambdaisland/kaocha-cljs.svg?style=svg)](https://circleci.com/gh/lambdaisland/kaocha-cljs) [![cljdoc badge](https://cljdoc.org/badge/lambdaisland/kaocha-cljs)](https://cljdoc.org/d/lambdaisland/kaocha-cljs) [![Clojars Project](https://img.shields.io/clojars/v/lambdaisland/kaocha-cljs.svg)](https://clojars.org/lambdaisland/kaocha-cljs) [![codecov](https://codecov.io/gh/lambdaisland/kaocha-cljs/branch/master/graph/badge.svg)](https://codecov.io/gh/lambdaisland/kaocha-cljs)
<!-- /badges -->

ClojureScript support for Kaocha.

## Quickstart

- Add kaocha-cljs as a dependency

``` clojure
;; deps.edn
{:deps {lambdaisland/kaocha {...}
        lambdaisland/kaocha-cljs {...}}}
```

Note that you must be using at least Clojure 1.10.

- Configure a ClojureScript test suite

``` clojure
;; tests.edn
#kaocha/v1
{:tests [{:id :unit-cljs
          :type :kaocha.type/cljs
          ;; :test-paths ["test"]
          ;; :cljs/timeout 10000                        ; 10 seconds, the default
          ;; :cljs/repl-env cljs.repl.node/repl-env     ; node is the default
          ;; :cljs/repl-env cljs.repl.browser/repl-env
          }]}
```

For nodejs, install `ws` and `isomorphic-ws`

```
npm i isomorphic-ws ws
```

Run your tests

```
clojure -m kaocha.runner unit-cljs
```

<!-- license-epl -->
## License

Copyright &copy; 2018 Arne Brasseur

Available under the terms of the Eclipse Public License 1.0, see LICENSE.txt
<!-- /license-epl -->
