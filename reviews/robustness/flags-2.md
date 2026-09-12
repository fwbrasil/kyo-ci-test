# Flags on the second round's diff, `7fedf93c73..98bdc584b7`

Every construct of concern `flags.sh` found on the added lines, each with a verdict from the closed set:
a cast ladder category, a measurement, a `moved` provenance, `test` for a fixture or local state in a
test, or `REMOVE`. Sites are the tip's line numbers.

| id | site | added line | class | verdict |
|----|------|------------|-------|---------|
| F1 | Scope.scala:349 | `.handle(Fiber.initUnscoped[Nothing, Unit, Any, Any])` | carrier | moved: the base's line, re-indented under the synchronous registration |
| F2 | Sync.scala:157 | `Bracket.ensuringWith(AtomicRef.Unsafe.init[Maybe[Result.Error[Any]]](Absent)...` | carrier | moved: the base's slot type, now the `init` of `ensuringWith` |
| F3 | ScopeInterruptTest.scala:8 | doc: "the release is registered in that same step" | terminology | justified: step, release, acquire are the kernel's words |
| F4 | ScopeInterruptTest.scala:102 | `assert(acq == 0 && rel == 0, ...)` | terminology | test: the assertion's message |
| F5 | FfiGenErrors.scala:27 | `if t != null then t.printStackTrace()` | carrier | justified: the reporter is total and runs on the diagnostic path of a native trampoline, where the throwable it is handed may be absent; kyo-ffi, not kernel code |
| F6 | FfiGenErrors.scala:31 | `val cls = if t == null then "<null>" else t.getClass.getName` | carrier | justified: as F5 |
| F7 | Safepoint.scala:238 | `(pending.slice eq null) \|\| (pending.slice eq slice) \|\| (thread ne Thread.currentThread()) \|\|` | carrier | moved: the base's wildcard encoding, `stop(thread)` is `stop(thread, null)` |
| F8 | Safepoint.scala:239 | `slots.compareAndSet(idx, pending, new Stop(thread, slice))` | allocation | justified: one `Stop` per stale stop superseded, on the owner's request path; the base allocates the same `Stop` on every request that finds the thread, and the poll allocates nothing |
| F9 | SafepointTest.scala:73 | comment | terminology | test: the pin's comment |
| F10 | SafepointTest.scala:79 | `val departed = new AnyRef` | allocation | test: fixture |
| F11 | SafepointTest.scala:80 | `val slice    = new AnyRef` | allocation | test: fixture |
| F12 | SafepointTest.scala:93 | `val departed = new AnyRef` | allocation | test: fixture |
| F13 | SafepointTest.scala:94 | `Safepoint.beginSlice(slot, new AnyRef)` | allocation | test: fixture |
| F14 | SafepointTest.scala:108 | `val departed = new AnyRef` | allocation | test: fixture |
| F15 | SafepointTest.scala:109 | `val slice    = new AnyRef` | allocation | test: fixture |
| F16 | SafepointTest.scala:113 | `val late = new Thread(...)` | allocation | test: the other thread the pin needs |
| F17 | Bracket.scala:51 | `final class Live[R](val state: R, fin: (R, Maybe[Throwable]) => Unit)` | new-type | justified: not a new type, the existing `Live` parameterised by the state its release is owed, which `apply` carried in a closure per run |
| F18 | Bracket.scala:113 | `val cell = new Cell.Live(a, release)` | allocation | measured structurally: one allocation where the base made two, the closure `outcome => release(a, outcome)` is gone |
| F19 | Bracket.scala:140 | `region(new Cell.Live((), fin), b)` | allocation | moved: the base's per-run cell; `fin` is built once per `ensuring` call, outside the by-name |
| F20 | Bracket.scala:161 | `new Cell.Live(init, release)` | allocation | justified: the per-run cell every region has, made in `derive` |
| F21 | Bracket.scala:165 | `cell.asInstanceOf[Cell.Live[R]]` | cast | erasure-forced: the read is the first step under the region that bound the cell it made with `init`, so it is that cell and `R` is what it holds |
| F22 | Eval.scala:609 | doc: "acquiring what nothing will release" | terminology | justified: acquire, release, step |
| F23 | Eval.scala:627 | `input.asInstanceOf[I[Any]]` | cast | moved: the base's erasure-forced cast, the budget argument dropped |
| F24 | Eval.scala:627 | `release(v, ex, Present(effectTag.erased), input => f[Any](...))` | carrier | moved: the base's call, the budget argument dropped |
| F25 | Eval.scala:629 | `private def release[A, S](v: A < S, ex: Throwable, effectTag: Maybe[Tag[Any]], f: Any => Unit)` | carrier | moved: the base's walk signature, the budget parameter dropped |
| F26 | Eval.scala:648 | `case step: Arrow.Ensure[Any, Any, Any] @unchecked` | cast | moved: the base's typed pattern, the budget argument dropped from the call beside it |
| F27 | Eval.scala:648 | `collect(step(Nested.unnest[Any](v)), Arrow.id)` | carrier | moved: as F26 |
| F28 | Eval.scala:651 | `@tailrec def collect(v: Any, cont: Arrow[Any, Any, Any]): Unit` | carrier | moved: the base's walk signature, the budget parameter dropped |
| F29 | BracketTest.scala:98 | `var seen = Maybe.empty[Maybe[Throwable]]` | mutability | test: local state |
| F30 | BracketTest.scala:100 | `val handled: Int < Any =` | carrier | test: the pin's shape |
| F31 | BracketTest.scala:105 | `ArrowEffect.suspendWith[Any](Tag[Ask], ())(r => cont(r))` | carrier | test: the boundary's re-raise, reproduced |
| F32 | BracketTest.scala:1758 | `var seen = Maybe.empty[(AnyRef, Maybe[Throwable])]` | mutability | test: local state |
| F33 | BracketTest.scala:1759 | `var got  = Maybe.empty[AnyRef]` | mutability | test: local state |
| F34 | BracketTest.scala:1760 | `Bracket.ensuringWith(new AnyRef)(...)` | allocation | test: fixture |
| F35 | BracketTest.scala:1774 | `Bracket.ensuringWith(new AnyRef)(...)` | allocation | test: fixture |
| F36 | BracketTest.scala:1782 | `var seen = Maybe.empty[(Int, Maybe[Throwable])]` | mutability | test: local state |
| F37 | BracketTest.scala:1794 | `var made = 0` | mutability | test: local state |
| F38 | BracketTest.scala:1795 | `var seen = Maybe.empty[(Int, Maybe[Throwable])]` | mutability | test: local state |
| F39 | BracketTest.scala:1796 | `var ran  = false` | mutability | test: local state |
| F40 | EvalTest.scala:637 | `Effect.defer[String, Int, Any](` | carrier | test: explicit arguments for the settled shape |
| F41 | EvalTest.scala:644 | `Eval.release(v, new RuntimeException("abandoned"))` | allocation | test: fixture |
| F42 | EvalTest.scala:654 | `Effect.defer[Int, Int, Any](` | carrier | test: as F40 |
| F43 | EvalTest.scala:660 | `Eval.release(v, new RuntimeException("abandoned"))` | allocation | test: fixture |
| F44 | EvalTest.scala:670 | `val v: Int < Any =` | carrier | test: the pin's shape |
| F45 | EvalTest.scala:671 | `Effect.defer[Int < Ask, Int, Any](` | carrier | test: as F40 |
| F46 | EvalTest.scala:678 | `Eval.release(v, new RuntimeException("abandoned"))` | allocation | test: fixture |
| F47 | EvalTest.scala:681 | `released.get.asInstanceOf[AnyRef] eq resource.asInstanceOf[AnyRef]` | cast | moved: the base's identity check |
| F48 | EvalTest.scala:687 | comment | terminology | test: the pin's comment |
| F49 | EvalTest.scala:690 | `var acquired = false` | mutability | test: local state |
| F50 | EvalTest.scala:691 | `var released = Maybe.empty[Int]` | mutability | test: local state |
| F51 | EvalTest.scala:1706 | comment | terminology | test: the pin's comment |
| F52 | EvalTest.scala:1707 | comment | carrier | test: the pin's comment |
| F53 | ReactiveUITeardownTest.scala:66 | comment | terminology | test: the pin's comment |
| F54 | CallbackShapesGen.scala:264 | `private type TransientStacks = ConcurrentHashMap[Thread, ArrayDeque[AnyRef]]` | new-type | justified: an alias for the registry's concrete type in generated code, replacing the `ThreadLocal` that lost an entry mid-call |
| F55 | CallbackShapesGen.scala:265 | `new TransientStacks()` | allocation | justified: one map per shape, once, at object initialisation |
| F56 | CallbackShapesGen.scala:349 | `f.asInstanceOf[AnyRef]` | cast | moved: the base's cast into the erased stack |
| F57 | CallbackShapesGen.scala:349 | `new TaggedCallback(...)` | allocation | moved: the base's per-push record |
| F58 | CallbackShapesGen.scala:361 | `var tagged: TaggedCallback = null` | carrier | justified: a local of the generated trampoline, unset until the peek inside the `try` lands and read only in the `catch` to name the callback; the reporter names `<unknown>` when it never landed; kyo-ffi generated code, not kernel code |
| F59 | CallbackShapesGen.scala:361 | `var tagged: TaggedCallback = null` | mutability | justified: as F58, a local written once by the peek |
| F60 | CallbackShapesGen.scala:368 | `if tagged eq null then FfiGenErrors.reportCallbackFailed("<unknown>", ...)` | carrier | justified: as F58, the branch that names a callback the peek never reached |
