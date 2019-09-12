# 0.0-59 (2019-09-12 / 9159233)

## Added

- Added support for matcher-combinators

## Changed

- When using the nodejs repl type, automatically set the CLJS compile target to :nodejs

# 0.0-51 (2019-09-11 / 4b6a751)

## Added

- Added compatibility with Figwheel REPL
- Make the CLJS-side logging configurable with `:closure-defines`

## Fixed

- Use the same compiler env for the load and run stages

## Changed

- isomorphic-ws is no longer needed on Node (ws still is)
- rework the websocket connection to be more reliable

# 0.0-40 (2019-07-02 / d0324dd)

## Changed

- Instead of using the ClojureScript PREPL we now use a queue based solution
  that bypasses the need for a Reader. This should hopefully lead to better
  reliability.

## Fixed

- Correctly pass custom compiler-options to the prepl

# 0.0-32 (2019-04-23 / 3d46a25)

## Fixed

- Honor `:kaocha.testable/wrap`, and thus `:kaocha.hooks/wrap-run`, which fixes
  support for output capturing. This work is funded by
  [Nextjournal](https://nextjournal.com/).

# 0.0-29 (2019-04-16 / 56f47ff)

## Added

- The `kaocha.type.cljs/*debug*` var can be set to see what kaocha-cljs is doing
  (use `:binding {kaocha.type.cljs/*debug* true}` in `tests.edn`)
- Proper support `cljs.test/use-fixtures`. It still only supports the map
  version, i.e. `(use-fixtures :once {:before ... :after ...})`, but should run
  both `:once`, and `:each` fixtures, honoring uses of `async` in the fixture
  functions. This work is funded by [Nextjournal](https://nextjournal.com/).

## Fixed

- Improved error handling and cleanup, to prevent cases where Kaocha would fail
  to exit after an exception.

# 0.0-24 (2019-04-09 / 248e33c)

## Added

- Added support for `cljs.test/async`.
- Added initial support for `cljs.test/use-fixtures`. Currently both `:each` and `:once` fixtures are treated as `:each` fixtures, so they run before/after each test var.

## Fixed

## Changed

# 0.0-21 (2019-02-28 / 1be9c73)

## Fixed

- Add ClojureScript implicit options during analysis, fixes issues with CLJSJS,
  among others.

# 0.0-16 (2018-12-31 / 214b14e)

## Fixed

- Capture exception type and message so it can be reported

# 0.0-11 (2018-12-12 / 53fe73a)

## Added

- Initial implementation
