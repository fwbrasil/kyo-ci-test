# Flags on the third walk's diff, `9d5c795077..b9e522721b`

Every construct of concern `flags.sh` found on the added lines of piece O, each with a verdict from the
closed set: a cast ladder category, a measurement, a `moved` provenance, `test` for a fixture or local
state in a test, or `REMOVE`. Sites are the tip's line numbers.

| id | site | added line | class | verdict |
|----|------|------------|-------|---------|
| F1 | Fiber.scala:141 | comment: "a statement still on the wire" | mutability | moved: the base's comment, re-indented |
| F2 | Fiber.scala:145 | comment: "released what it held, so the scope closes after that" | terminology | justified: release, close |
| F3 | Fiber.scala:149 | `fiber.asInstanceOf[IOPromise[Any, Any]]` | cast | erasure-forced: the cast the extension's `lower` makes; a fiber is its promise |
| F4 | Fiber.scala:149 | `fiber.asInstanceOf[IOPromise[Any, Any]]` | carrier | justified: the release reads `result.error` only, a `Maybe[Error[Any]]`, which is `close`'s parameter type |
| F5 | Fiber.scala:368 | scaladoc: "once its finalizers have run" | terminology | justified: finalizers, release |
| F6 | IOPromise.scala:202 | scaladoc: "the state is read again" | terminology | justified: state, attempt |
| F7 | IOPromise.scala:219 | `case p: Pending[E, A] @unchecked` | cast | moved: the base's typed pattern in `interruptLoop`, for the completion loop |
| F8 | IOTask.scala:56 | scaladoc: "the abort arm settled it" | mutability | comment |
| F9 | IOTask.scala:56 | scaladoc | terminology | justified: settle, ending |
| F10 | IOTask.scala:58 | scaladoc: "after the remainder is released" | terminology | justified: remainder, release |
| F11 | IOTask.scala:95 | `(restore: P => A < S2)` | carrier | justified: the boundary's callback, typed as the base's `P => Unit` was over the isolate's output; it now yields what the value becomes |
| F12 | IOTask.scala:119 | comment: "this arm settled it" | mutability | comment |
| F13 | IOTask.scala:296 | comment: "the run that interrupt scheduled" | mutability | comment |
| F14 | IOTask.scala:323 | comment: "the release an interrupt would wait for" | terminology | justified: release |
| F15 | IOTask.scala:328 | `new Result.Panic(ex)` | allocation | moved: the base's construction, which holds a fatal, on both arms of the branch |
| F16 | IOTask.scala:353 | comment: "completed while this slice unwound" | mutability | comment |
| F17 | IOTask.scala:394 | `case error: Result.Error[E] @unchecked` | cast | erasure-forced: the word's error is this task's `E`, the one the interrupt that stored it carried |
| F18 | IOTask.scala:445 | `type Status = ... \| Result.Error[?]` | new-type | justified: not a type, the word's fifth state, the error the interrupt already allocated; the ruling of 2026-09-12: a flat union, no wrapper |
| F19 | FiberTest.scala:1258 | test title | terminology | test: the pin's title |
| F20 | FiberTest.scala:1261 | `Promise.init[Unit, Any]` | carrier | test: fixture |
| F21 | FiberTest.scala:1277 | `Promise.init[Unit, Any]` | carrier | test: fixture |
| F22 | FiberTest.scala:1289 | `Promise.init[Unit, Any]` | carrier | test: fixture |
| F23 | FiberTest.scala:1303 | `Promise.init[Fiber[Int, Any], Any]` | carrier | test: fixture |
| F24 | FiberTest.scala:1316 | test title | terminology | test: the pin's title |
| F25 | FiberTest.scala:1319 | `Promise.init[Unit, Any]` | carrier | test: fixture |
| F26 | IOPromiseTest.scala:175 | `var fired = 0` | mutability | test: local state |
| F27 | IOPromiseTest.scala:176 | `var doneAtHook = false` | mutability | test: local state |
| F28 | IOPromiseTest.scala:177 | `override protected def interrupt(p: IOPromise.Pending[Nothing, Int], v: Result.Error[Nothing])` | carrier | test: the hook's signature at the pin's types |
| F29 | IOPromiseTest.scala:200 | `class TakingPromise extends IOPromise[Nothing, Int]` | carrier | test: the task's shape, reproduced |
| F30 | IOPromiseTest.scala:200 | `class TakingPromise` | new-type | test: the task's shape, reproduced |
| F31 | IOPromiseTest.scala:201 | `var taken = Maybe.empty[Result.Error[Nothing]]` | carrier | test: local state |
| F32 | IOPromiseTest.scala:201 | `var taken = ...` | mutability | test: local state |
| F33 | IOPromiseTest.scala:202 | `override protected def interrupt(...)` | carrier | test: as F28 |
| F34 | IOPromiseTest.scala:211 | `new TakingPromise` | allocation | test: fixture |
| F35 | IOPromiseTest.scala:212 | `new Exception("first")` | allocation | test: fixture |
| F36 | IOPromiseTest.scala:214 | `new Exception("second")` | allocation | test: fixture |
