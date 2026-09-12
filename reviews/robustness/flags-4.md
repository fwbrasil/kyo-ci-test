# Flags on the fourth walk's diff, `b0a9d4a666..a5b7d2a512`

Every construct of concern `flags.sh` found on the added lines of pieces P and Q, each with a verdict
from the closed set: a cast ladder category, a measurement, a `moved` provenance, `test` for a fixture
or local state in a test, or `REMOVE`. Sites are the tip's line numbers.

| id | site | added line | class | verdict |
|----|------|------------|-------|---------|
| F1 | IOTask.scala:429 | `r.asInstanceOf[Result[Nothing, C]]` | cast | erasure-forced: the join's output is the promise's result at the join's own error type, which the promise's static type does not carry |
| F2 | IOTask.scala:429 | `Present(r.asInstanceOf[Result[Nothing, C]])` | carrier | justified: `Result[Nothing, C]` is the join effect's output constructor applied to `C` |
| F3 | ScopeInterruptTest.scala:107 | comment | mutability | test: the pin's comment |
| F4 | ScopeInterruptTest.scala:109 | comment | terminology | test: the pin's comment |
| F5 | ScopeInterruptTest.scala:115 | `Promise.init[Int, Any]` | carrier | test: fixture |
| F6 | Eval.scala:624 | scaladoc: "Nothing else runs" | carrier | comment |
| F7 | Eval.scala:634 | `f: Any => Maybe[Any]` | carrier | moved: the base's erased reporter, which now may answer |
| F8 | Eval.scala:653 | `case step: Arrow.Ensure[Any, Any, Any] @unchecked` | cast | moved: the base's typed pattern in `ensuring`, the flag argument added to the call beside it |
| F9 | Eval.scala:653 | `collect(step(Nested.unnest[Any](v)), Arrow.id, false)` | carrier | moved: as F8 |
| F10 | Eval.scala:659 | `collect(v: Any, cont: Arrow[Any, Any, Any], delivered: Boolean)` | carrier | moved: the base's walk signature, the flag added |
| F11 | Eval.scala:673 | `case _ if delivered =>` | terminology | justified: "delivers" is this file's word for handing a settled value to what waits on it |
| F12 | Eval.scala:703 | `case Present(t) if t <:< kyo.tag.erased` | cast | moved: the base's tag test, a match rather than a `foreach` so the delivery is a tail call |
| F13 | Eval.scala:707 | `kyo.cont.asInstanceOf[Arrow[Any, Any, Any]]` | cast | erasure-forced: the operation's output type is existential from out here, the cast `leftmost` makes on a chain's head |
| F14 | Eval.scala:707 | `collect(kyo.cont...(answer, Arrow.id), cont, true)` | carrier | moved: the walk's erased currency, as F10 |
| F15 | EvalTest.scala:704 | `new RuntimeException("abandoned")` | allocation | moved: the base's fixture |
| F16 | EvalTest.scala:1626 | `def seeing[A](f: A => Unit): [C] => A => Maybe[Nothing]` | carrier | test: the reporter helper, typed at each pin |
| F17 | EvalTest.scala:1731 | `var released = Maybe.empty[Int]` | mutability | test: local state |
| F18 | EvalTest.scala:1739 | `var released = Maybe.empty[Int]` | mutability | test: local state |
| F19 | EvalTest.scala:1740 | `var mapped = false` | mutability | test: local state |
| F20 | EvalTest.scala:1752 | `var released = Maybe.empty[Int]` | mutability | test: local state |
| F21 | EvalTest.scala:1753 | `var later = false` | mutability | test: local state |
| F22 | SqlConnectionPool.scala:409 | the acquire-timeout log message | terminology | moved: the base's message, re-indented |
