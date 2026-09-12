FAIL

Judged against the package as it stands on disk and the range cdefdc9e60..00409bf913, the current tip; `sequence.py --verify` reproduces that tip from `sequence.json`. Eleven findings. R1 and R2 repeat rulings and lead out of walk order; R3 to R11 follow the walk. The rulings that held, and what I ran to check them, are at the end.

## R1. Edits 4 and 7. The evaluator decides re-entry behind a flag, and `resumed` is partial on the base class

Lines:

    def resumed: ContHandler[I, O, E, A, A, S] = bug(s"resumed on a handler that does not repeat: $this")

    val continuation = if handler.repeated && !handler.escaping then handler.reentering(raw) else raw

Rulings, 2026-08-29:

> make sure the code is as simple and as safe as possible. Avoid new types if possible. Prefer code that is correct by construction. If you find yourself handling multiple edge cases you need to take a step back and rethink the approach.

> FUCKING SAFE CODE!!! PROPERLY TYPED!!!

> you should only update the regions handling?

What he sees. The property the fix rests on, that a handler asked for `resumed` has one, is in no type. It is a throwing default on `ContHandler` plus an enumeration of five sites in the adjudication table, H2, whose class the package gives as "claim" and whose verdict is that enumeration. A sixth handler that overrides `repeated` and not `resumed` compiles and fails at run time with `bug`. The evaluator's cont arm, the arm every `handleCont` answer takes, gains a virtual read and an unless-clause to serve two handlers, and H1 exists to measure that read.

The shape that makes it true by construction is in the package's own description: the handler that repeats knows it repeats. Wrap in the handler's `run`, in the two `handleContRepeated` overloads, `def run[X](input, next) = Region.discharge(handle[X](input, reentering(next)))`, with the twin a local `val` beside it. The twin's `run` delegates to `outer.run`, so the twin wraps too and the nesting is exactly what the evaluator produces today. `handleFirstRepeated` is untouched, which is what `!escaping` encodes today. Then `ContHandler` gains no partial member, edit 3 disappears, Eval does not change, `raw` does not exist, H1 and H2 leave the table, and `repeated` on `handleCont` rows costs what it cost at the base, which is nothing.

The one defense the kernel offers for the placement, that `repeated` and `escaping` are declared on the handler and acted on by the evaluator, covers total declarations with total defaults: `bound`, `unbound`, `recover`. `resumed` is the first partial one.

He would say: "Why is Eval deciding this? The handler that repeats wraps its own cont in run, and then there's no bug, no flag, and no read on every handleCont."

## R2. Adjudication and evidence. The tip leg of every KernelBench table is not the tip, and two rows say "measurement pending"

Lines:

> F2 ... measurement pending: the recovering overload's twin, carried by `repeatedRegionsPayEntryRecovering`, base against tip, `-f 3 -prof gc`, in the tip's final benchmark session

> H1 ... measurement pending for the line as written, rows named: every `KernelBench` row, base against tip, `-f 1`, then `-f 3` outside the band, the tip's final session

> the fix alone (A, edits 3 to 8 with edit 7's arm reading `repeated` only), and that fix with C (edits 9 to 14 added, the arm the same). The `escaping` read in edit 7 came after this session, from the Choice measurement below, and the whole class runs again on the tip as it stands; those numbers close H1.

Ruling, 2026-08-30, standing consequence:

> before a number is evidence, verify that the thing measured is the thing changed, mechanically.

And the brief's evidence rule: base and tip back to back, `-f 3` on anything outside drift, a missing element is a stop.

What he sees. Every `KernelBench` comparison the package quotes, `compare-base-vs-AC`, `compare-A-vs-AC`, their `-f 3` confirmations and the multi-shot rows, measured a tree whose cont arm reads `repeated` alone. The line the tip carries reads `repeated && !escaping`, and the package says so itself and says the numbers that close H1 are not in it. The package also says "Two rows added by edit 16" in the evidence section while edit 16 now adds three, and the third has no number. A package that names its own pending measurements is honest and is not ready; the ruling above was written after four rounds argued over numbers that measured the wrong thing, and this one asks him to accept numbers that measure a line the diff does not contain.

He would say: "Your tip numbers aren't the tip. Run the class on what I'm reviewing and come back."

## R3. Edit 1. A rule where a build step would serve

Line: "A change to a public signature also runs `kyo-kernelJVM/Jmh/compile`: the benchmark sources under `jvm/src/jmh` are compiled by neither `test` nor CI's test action".

Rule: the same standing consequence as R2, and its reason, "A plausible name answers the question by looking right, which is why judgment kept passing it." Edit 15 shows the bench sources did not compile from the base commit onward because nothing compiled them. Item 13 answers that with a sentence to remember, which is judgment; a CI step that compiles `jmh` is the mechanical answer.

He would say: "You just showed nobody remembers to compile it, and the fix is a sentence asking them to remember?"

## R4. Edit 4. Two names for one value, and a type parameter renamed against the file's own convention

Lines:

    val twin = resumed

    // `V` rather than `X` for the operation's type: `Arrow` has a type member `X`, and inside the Step the two would collide.
    private[kyo] def reentering[V](k: Arrow[O[V], A, E & S]): Arrow[O[V], A, E & S] =

Ruling, 2026-08-29:

> Avoid new terminology: "drive" is explicitly banned, the correct is "eval", don't use "after" use "cont", make sure naming is fully consistent.

"twin" occurs nowhere in the kernel at the base. It is the package's word, used in every sentence about the member, and the member is called `resumed`. `resumed` sits beside `repeated` and `escaping`, both Booleans, and reads as a third; at the base `resumed` names a resumed computation in Eval. The file's own name for a type parameter moved out of the way of `Arrow`'s `X` is `X0`, in `attachReentry`, `attachReentry2` and the new `attachReentryUnlessSettled`; `reentering` picks `V` and spends a comment on it.

He would say: "twin or resumed? And this file calls that X0."

## R5. Edits 5 and 6. The twin's `repeated` is unexplained and load-bearing, the twin is eager, and it is written twice

Lines, in both overloads:

    override val resumed: Handler.ContHandler[I, O, E, A, A, S & S2] =
        new Handler.ContHandler[I, O, E, A, A, S & S2]:
            ...
            override def repeated = true
            override def resumed  = this

Rule: the one sentence. The sentence for edit 5 names `run`, `done` and `outer` and says nothing about `repeated = true` on the twin. That line is what makes a bracket inside a re-entered continuation correct: the twin becomes the holder of the regions dumped into continuations captured inside it, and it has to, because its clause resumes those continuations more than once and the release cannot fire under the second shot. It also moves where that debt discharges. At the base a bracket dumped into a continuation captured under `handleContRepeated` was held to the outer region's end; at the tip it is held to the innermost twin's end, which is after the clause that owns the continuation finishes all its resumptions. The `BracketTest` cases under `handleContRepeated` pass; whether one of them pins when the release fires is not in the package, and the package does not say the move happened. Second, the twin is an eager `val`, built at every region entry, paid by regions whose clause resumes at most once; F1 measures it at 16 bytes and 4.7 ns per region and does not say why it is built before anything resumes, and F2, the recovering overload's copy, is the pending measurement in R2. Third, the twin is the same nine lines in both overloads, which is what CONTRIBUTING's "overloads delegate to canonical" is about; the twin is a function of `outer` alone.

He would say: "Why is the twin repeated, and where does a bracket inside a re-entered cont release now? Say it on the line. And why build it before anyone resumes?"

## R6. Edit 8. No depth case for the one handler whose depth behaviour changed

Line: the four cases stop at three occurrences.

Rule: rubric 4, the suites, and item 14 of the module guide. The mechanism the package gives for F3 is that a sequential program under `handleContRepeated` now nests one region per resumption where the base ran one region; the region stack doubles to hold them. Every sibling handler carries "deep sequential operations are stack safe" at 100000 in `ArrowEffectTest`: `handleCont`, `handleContWith`, `handleLoop`. `handleContRepeated` carries none, before or after this change, and it is the only handler for which the question is new. `repeatedClausesPayReentry` at 10000 is a measurement, not a pin.

He would say: "Every other handler has the 100000 case. This one now nests a region per op and doesn't."

## R7. Edits 9 to 14. A consolidation the fix does not need, with a name that says "unless"

Line: `private[kyo] inline def attachReentryUnlessSettled[...]` and the four call sites.

Ruling, 2026-08-29: "you should only update the regions handling?", and the standing consequence, "An improvement outside it is still a finding, because nobody agreed to it." Piece C is on the package's list, and each of its six sentences says what the tail is and that it is inline; none says what C does for the hang or why it travels with the fix. The branch it names exists to save one `Arrow.Step` on the once-per-region ending path, which `attachReentry`'s third arm already handles; the split predates the change, and C is where he will ask about it.

He would say: "What does this have to do with the hang?"

## R8. Edit 19. A kyo-data change verified on three of four platforms

Line:

    if idx < 0 || idx >= size then
        throw new IndexOutOfBoundsException(s"$idx is out of bounds (min 0, max ${size - 1})")

Rule: rubric 4 and the scope ruling. The package verifies `SpanTest` on JVM, JS and Wasm; the matrix it cites has a fourth platform, Native, and the change is to a shared `inline` method in `kyo-data`. On the JVM the array store already checked the index, so that path now checks twice, on an inline method, and the package does not say what that costs or that it is inside drift anywhere.

He would say: "Native? And what does the JVM pay for checking twice?"

## R9. Edit 20. A linux-arm64 race verified on a mac

Line: `driver.awaitWritable(handle, writePromise)` moved above `driver.awaitRead(handle, readPromise)`, with the comment "The registrations are applied in order on the poll fiber and the read event cannot be dispatched before the read registration is applied".

Rule: rubric 4 and rubric 2. The failure was a linux-arm64 JVM job. The evidence is "2 passed after edit 20" on `kyo-netJVM`, on the machine where the leaf did not fail before the edit either, so the run distinguishes nothing. `scripts/build.sh --env podman-ci --arch arm` exists for exactly this and is not mentioned. The ordering property the fix rests on is asserted in a test comment; nothing pins it, so a driver that reorders its command log makes the leaf racy again without a test going red.

He would say: "You fixed an arm64 race and ran it on your mac."

## R10. Evidence. Two test counts that cannot both hold

Lines: "invisible to 1513 kernel tests" and "`kyo-kernelJVM/test` 1737 passed, 0 failed, 5 canceled".

Rule: rubric 4. The range adds 324 leaves, 320 cells and 4 cases, and removes none; `sequence.py --verify` confirms no other file changed in the range. 1513 plus 324 is 1837. One count is off by 100 or counts a different tree, and the package does not say which.

He would say: "1513 plus 324 is not 1737."

## R11. Evidence. A pointer to "below" that leaves the package

Lines: "The matrix's final state is reported with the sweep below." and, next paragraph, "is reported in the summary that proposes this review."

Rule: rubric 4. "Below" resolves to a document outside the package. What the package carries for a change to shared kernel sources is JVM runs; every non-JVM kernel run lives in the matrix the package points elsewhere for.

He would say: "Below where?"

## What held

- Rulings on types: no `Any` or `Null` carrier enters the evaluator. `reentering`, `attachReentryUnlessSettled` and the twin are fully typed. The casts on added lines are the moved pass-through at the four tails, the erasure-forced `k.asInstanceOf` the sites already carried, the `Pending` type test in the same arm shape as `Arrow.apply`, and `Nested.unnest` at the settled arm.
- New types: none named. The twin is an instance of `ContHandler`, and it has the argument the ruling asks for: the re-entered region yields `A` where the outer yields `B`, so `done` as identity is a different handler type, `[.., A, A, S]`, and no flag on the outer handler could carry it.
- Naming: no `drive`, no `after` as an identifier. "holding", "holder" and "settled" are the kernel's own words at the base.
- Inference: `V` against `X` is a rename, not a workaround; the `PollTest` ascriptions are declared as open ruling 2 with their root cause named.
- The walk: `sequence.json` holds 20 text pairs in the package's order, regenerated for the current tip. I ran `sequence.py --verify cdefdc9e60 HEAD` against it; every edit applies exactly once at the moment it is applied, every file reproduces the tip, and no file changed in the range is missing from the walk, VERIFIED. The script reads with `git show` only and touches no tree.
- Evidence that is there: the clean batch build; the suites named; `KernelBench` confirmed to reference `kyo.kernel`; the rows the fix and C reach named before any number; every measured row outside the 5% band with its `-f 3`; every per-unit number in the multi-shot table reproduces from the raw rows: 16 B, 64 B, 4.7 ns, 61 ns, 7.2 times, 3.2 times. What is not there is R2.
- The pending arm of `reentering`: a computation handed to the wrapped continuation runs at the clause's level and only its settled value re-enters. That is correct, because an effect performed by the argument is the clause's, answered by the clause's own handler with the clause's rest as its continuation; only the body belongs inside the region.
- F3 and open ruling 5 are presented as what they are, a regression nobody has accepted, with the mechanism, the floor it sits on, and the consumer's numbers.

What I looked hardest at is R1, because the package's own adjudication table names the two rows, H1 and H2, that exist only because the wrapping lives in Eval rather than in the handler that declares `repeated`, and one of those two rows is now the pending measurement in R2.
