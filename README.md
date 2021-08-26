# kaocha-cljs

<!-- badges -->
[![CircleCI](https://circleci.com/gh/com.lambdaisland/kaocha-cljs.svg?style=svg)](https://circleci.com/gh/com.lambdaisland/kaocha-cljs) [![cljdoc badge](https://cljdoc.org/badge/com.lambdaisland/kaocha-cljs)](https://cljdoc.org/d/com.lambdaisland/kaocha-cljs) [![Clojars Project](https://img.shields.io/clojars/v/com.lambdaisland/kaocha-cljs.svg)](https://clojars.org/com.lambdaisland/kaocha-cljs)
<!-- /badges -->

ClojureScript support for Kaocha

## Features

Kaocha-cljs provides basic ClojureScript support for the Kaocha test runner. It
can run tests in the browser or in Node.js. It does this under the hood using
the built-in ClojureScripr REPL implementations for the browser or for node.

This approach makes it fairly easy to set up, but also fairly limited and
inflexible.

- Your code has to be compatible with vanilla ClojureScript
- Shadow-cljs is not supported
- We can only run tests with `:optimizations :none`
- You have little to no control over the compiler settings
- We don't support other runtimes besides Node and browser
- Each run opens a new tab/process, we can't reconnect to existing JS runtimes
- The `repl-env` abstraction is a black box which provide very little diagnostics

To get around these limitations we created
[kaocha-cljs2](https://github.com/lambdaisland/kaocha-cljs2), which is
infinitely flexible, but significantly harder to set up. For simple projects and
libraries kaocha-cljs v1 can still be a valid choice. If it no longer serves
your needs, you can try your hand at kaocha-cljs2.

Kaocha-cljs require Clojure and ClojureScript 1.10 or later.

<!-- installation -->
## Installation

To use the latest release, add the following to your `deps.edn` ([Clojure CLI](https://clojure.org/guides/deps_and_cli))

```
com.lambdaisland/kaocha-cljs {:mvn/version "1.0.107"}
```

or add the following to your `project.clj` ([Leiningen](https://leiningen.org/))

```
[com.lambdaisland/kaocha-cljs "1.0.103"]
```
<!-- /installation -->

For Node.js support also install the `ws` npm package, you can add something
like this to `bin/kaocha` to this for you.

```sh
#!/usr/bin/env sh

[ -d "node_modules/ws" ] || npm install ws
clojure -A:dev:test -M -m kaocha.runner "$@"
```

To configure your kaocha-cljs test suite:

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

And run your tests

```
bin/kaocha unit-cljs
```

## Configuration

- `:kaocha/source-paths` (or `:source-paths` when using `#kaocha/v1`) <br>
  The location of your ClojureScript source paths (vector)
- `:kaocha/test-paths` (or `:test-paths` when using `#kaocha/v1`) <br>
  The location of your ClojureScript test paths (vector)
- `:cljs/timeout` <br> Time in milliseconds before timing out. This timeout gets
  reset whenever we receive an event from the ClojureScript environment, like a
  cljs.test event, or something being written to stdout. Once there is no
  activity for `:cljs/timeout` seconds, the test fails. This also causes
  subsequent tests to be skipped, because we assume the ClojureScript runtime is
  no longer responsive.
- `:cljs/repl-env` <br> A function (var) name which takes ClojureScript Compiler
  options, and returns a REPL environment. Values you can use include
  - `cljs.repl.node/repl-env`
  - `cljs.repl.browser/repl-env`
  - `figwheel.repl/repl-env`
- `:cljs/compiler-options` <br> Additional compiler options, defaults to `{}`.
- `:cljs/precompile?` <br> Invoke `cljs.build.api/build` before launching the
  REPL. Certain REPL types like Figwheel REPL require an initial build before
  the REPL is able to connect. If this is the case you can set this to `true`.
  Defaults to `false`.

## Known issues

- The `:test-paths` do not get automatically added to the classpath (at least not
in a way that makes the sources visible to ClojureScript), so you need to also
have any `:test-paths` in your `project.clj`/`deps.edn`/`build.boot`.

  This is a discrepancy with regular Kaocha, where you only need to specify the
test paths once.

- On Linux the `cljs.repl.browser/repl-env` requires the browser process to already be started
  before running Kaocha (see: <https://clojure.atlassian.net/browse/CLJ-2493>).

  To support running browser tests on CircleCI add an early config step like:

  ```
  - run:
      command: /usr/bin/google-chrome-stable --no-first-run
      background: true
  ```

- You can not pass the `:advanced` optimization setting to the to the clojurescript compiler options, which is very important to run tests against a real build.
  If this feature is important you should consider using [kaocha-cljs2 instead](https://github.com/lambdaisland/kaocha-cljs2).

### Common Errors

- `"Kaocha ClojureScript client failed connecting back."`

This is the most common problem you'll encounter. Unfortunately it's a symptom
that can have many underlying causes. What it means is this: Kaocha-cljs has
created a ClojureScript repl-env, and asked it to evaluate the code which loads
our websocket client. At this point Kaocha has to wait until that client is
loaded, and has connected back to Kaocha, so we know we're in business.

For some reason this didn't happen in time, and so we time out and provide this
error. What this really means is that the `repl-env` misbehaved. Maybe the JS
runtime didn't start up properly (check your node process for instance), maybe
the compiles CLJS caused an error (anything in the browser console)? Maybe it's
a networking issue... We handed over control, and never got it back.

## Architecture

### Kaocha's execution model

Most ClojureScript testing tools work by building a big blob of JavaScript that
contains both the compiled tests and a test runner, and then handing that over
to a JavaScript runtime.

Kaocha, however, enforces a specific execution model on all its test types.

```
[config] --(load)--> [test-plan] --(run)--> [result]
```

Starting from a test configuration (e.g., `tests.edn`) Kaocha will recursively
`load` the tests, building up a hierarchical test plan. For instance
`clojure.test` will have a test suite containing test namespaces containing test
vars.

Based on the test plan Kaocha recursively invokes run on these "testables",
producing a final result.

During these process various "hooks" are invoked (pre-test, post-test, pre-load,
post-load), which can be implemented by plugins, and test events
(begin-test-var, pass, fail, summary) are generated, which are handled by a
reporter to provide real-time progress.

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

This will also set the `goog.log` root logger, and the
`kaocha.cljs.websocket-client` logger both to the `DEBUG` level. Have a look at
[glogi](https://github.com/lambdaisland/glogi) for more information about Google
Closure's logging facilities.

When not using `*debug*` you can still set these log levels separately through
`:closure-defines`.

``` clojure
#kaocha/v1
{:tests [{:type :kaocha.type/cljs
          :cljs/compiler-options {:closure-defines {kaocha.type.cljs/log-level "ALL"
                                                    kaocha.type.cljs/root-log-level "INFO"}}}]}
;; Log levels:
;; OFF SHOUT SEVERE WARNING INFO CONFIG FINE FINER FINEST ALL
```

<!-- opencollective -->
## Lambda Island Open Source

<img align="left" src="https://github.com/lambdaisland/open-source/raw/master/artwork/lighthouse_readme.png">

&nbsp;

kaocha-cljs is part of a growing collection of quality Clojure libraries created and maintained
by the fine folks at [Gaiwan](https://gaiwan.co).

Pay it forward by [becoming a backer on our Open Collective](http://opencollective.com/lambda-island),
so that we may continue to enjoy a thriving Clojure ecosystem.

You can find an overview of our projects at [lambdaisland/open-source](https://github.com/lambdaisland/open-source).

&nbsp;

&nbsp;
<!-- /opencollective -->

<!-- contributing -->
## Contributing

Everyone has a right to submit patches to kaocha-cljs, and thus become a contributor.

Contributors MUST

- adhere to the [LambdaIsland Clojure Style Guide](https://nextjournal.com/lambdaisland/clojure-style-guide)
- write patches that solve a problem. Start by stating the problem, then supply a minimal solution. `*`
- agree to license their contributions as MPL 2.0.
- not break the contract with downstream consumers. `**`
- not break the tests.

Contributors SHOULD

- update the CHANGELOG and README.
- add tests for new functionality.

If you submit a pull request that adheres to these rules, then it will almost
certainly be merged immediately. However some things may require more
consideration. If you add new dependencies, or significantly increase the API
surface, then we need to decide if these changes are in line with the project's
goals. In this case you can start by [writing a pitch](https://nextjournal.com/lambdaisland/pitch-template),
and collecting feedback on it.

`*` This goes for features too, a feature needs to solve a problem. State the problem it solves, then supply a minimal solution.

`**` As long as this project has not seen a public release (i.e. is not on Clojars)
we may still consider making breaking changes, if there is consensus that the
changes are justified.
<!-- /contributing -->

<!-- license -->
## License

Copyright &copy; 2018-2021 Arne Brasseur and Contributors

Licensed under the term of the Mozilla Public License 2.0, see LICENSE.
<!-- /license -->
