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

## Architecture

### Kaocha's execution model

Most ClojureScript testing tools work by building a big blob of JavaScript which
contains both the compiled tests and a test runner, and then handing that over
to a JavaScript runtime.

Kaocha however enforces a specific execution model on all its test types.

```
[config] --(load)--> [test-plan] --(run)--> [result]
```

Starting from a test configuration (e.g. `tests.edn`) Kaocha will recursively
`load` the tests, building up a hierarchical test plan. For instance
`clojure.test` will have a test suite containing test namespaces containing test
vars.

Based on the test plan Kaocha recursively invokes run on these "testables",
producing a final result.

During these process various "hooks" are invoked (pre-test, post-test, pre-load,
post-load), which can be implemented by plugins, and test events
(begin-test-var, pass, fail, summary) are generated, which are handled by a
reporter to provide realtime progress.

Kaocha's built-in features, plugins and reporters are rely on this model of
execution, so any test type must adhere to it. Note that all of this is on the
Clojure side. Kaocha's own core, as well as plugins and reporters are all
implemented in (JVM-based) Clojure, not in ClojureScript, so even in the case of
ClojureScript tests the main coordination still happens from Clojure.

### PREPL + Websocket

To make this work kaocha-cljs makes use of a ClojureScript PREPL (a programmable
REPL). Given a certain repl environment function (e.g. `browser/repl-env` or
`node/repl-env`) Kaocha will boot up a ClojureScript environment ready to
evaluate code, and load a websocket client that connects back to Kaocha-cljs, so
we have a channel to send data back from ClojureScript to Kaocha. It will then
send code to the PREPL to load the test namespaces, and to invoke the tests.

Anything written on stderr or stdout will be forwarded to Clojure's out/err
streams, and possibly captured by the output capturing plugin.

The test events produced by `cljs.test` (pass, fail, error) are sent back over
the websocket, and ultimately handled by whichever Kaocha reporter you are using.

Events received from the PREPL and the websocket are all placed on a queue,
which ultimately drives a state machine, which coordinates what needs to happen
next, and gathers up the test results.

## Debugging

If you're having issues, first try running with `--no-capture-output`. There may
be relevant information that's being hidden.

To see all messages coming in over the PREPL and Websocket you can set
`kaocha.type.cljs/*debug*` to `true`. You can do this directly from `tests.edn`.

``` clojure
#kaocha/v1
{:tests [,,,]
 :bindings {kaocha.type.cljs/*debug* true}}
```

<!-- license-epl -->
## License

Copyright &copy; 2019 Arne Brasseur

Available under the terms of the Eclipse Public License 1.0, see LICENSE.txt
<!-- /license-epl -->
