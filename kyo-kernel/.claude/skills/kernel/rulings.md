# Rulings

What the reviewer has already objected to, in his own words. This is `kernel-rehearsal`'s rubric,
and it is what makes the rehearsal predictive of this reviewer rather than generically competent.

Rulings are recorded **verbatim and dated**. A paraphrase loses the thing that makes a ruling
survive a compaction: "user decided against it" invites a later session to relitigate from a
summary of a summary, while his own sentence does not.

An entry is added whenever he objects during a live review, whether or not the objection was
already covered. A repeat is the strongest signal there is: it means the preparation ignored a
ruling he had already given, and `kernel-rehearsal` reports those first.

## Types and safety

**2026-08-29** on `Any < Nothing` as the evaluator's return type:
> fuck man.... Any < Nothing everywhere!?!?!? STOP AND TAKE LONG STEP BACK

**2026-08-29** on `var res: Any = null` in the eval's guard:
> var res: any = null / why the fuck are you doing this!? you should only update the regions handling?

**2026-08-29**, on the general standard for the change:
> FUCKING SAFE CODE!!! PROPERLY TYPED!!!

Standing consequences: no `Any` or `Null` carrier in the evaluator. A signature that stops
typechecking after a design change is information about the design, not an obstacle to route
around: fix the signature to say what the method now does. Erasing a return type to make a call
compile is the failure this ruling names.

## New types

**2026-08-29** on introducing a `Region` class to carry an entry's types:
> oh fuck why have a Region class? man.....................

> SO WHY ADD REGION!?!?!?!?

Standing consequence: a new type needs an argument that an existing one cannot serve. Re-typing an
array element at the storage boundary is a sanctioned erasure-forced cast (the skill's cast ladder
names `Stack` as the example) and is not a reason to invent a carrier.

**2026-09-13**, on the release list a stack entry holds:
> for releases make it Maybe[Throwable] => Unit | Chunk[Maybe[Throwable] => Unit] so we don't need
> to allocate for a single element.

Standing consequence: a list that is almost always one element is the element or a chunk, never a
wrapper around one.

## Regions and releases

**2026-09-12**, on the `Ensure` arrow the first walks built the bracket on, after two issues traced
to it:
> I think these issues all stem from a fundamental flawed decision at the start of tihs work. I
> think we need Pending.Bracket and should not have Ensure

**2026-09-12**, on the claim that no stop is honored inside an acquire:
> this is incorrect: **Inside an acquire, no stop is honored.**
>
> acquires should allow stop but not after they're done right before the finalizer being registered

Standing consequence: an acquire is interruptible; the one window that does not exist is between
the acquire's value and the registration of its release, which is the region's own hook.

**2026-09-13**, on the mechanisms (`discharge`, `owed`, `bound`, `unbound`, `borrow`, `defers`)
the previous design had accumulated around releases:
> all these mechanisms dischard, owed, etc seem so overengineered. In the end is in't just a
> question of moving the release to the outer handler region?

> let's say each reagion in the stack holds a list of release functions. Isn't it a quesitn of
> "bumping" the release to the outer region?

> I can't see why we need bound/unbound. WTF why wouldn't we have release?

> if we resolve from handlers/stack, then we don't even need Context?

Standing consequence: a region's release lives in its own stack entry; a handler that takes the
continuation moves the dumped entries' lists to its own entry; an escaping handler moves its list
to the entry below; a binding is found on the stack the way a handler is, so there is no separate
context. Every mechanism beyond that is a finding.

**2026-09-13**, on whether the normal and the repeated handler differ:
> nope, I do not understand and I think you're just trying to defend a design. Please do not take
> effort into consideration here. I require complete and correct solutions only. I can't see why
> we'd dump the release into the continuation at all. Once the handling is done then the release
> must happen

> can normal be == repeated or not? I can't see a single good argument why not

> sorry I meant only the escaping flag. no repeated

Standing consequence: a held continuation runs every shot against the live resource and the
release runs once, where the holder ends; `escaping` is the only flag on a handler; `repeated`
does not exist. An argument from effort is not an argument.

**2026-09-13**, on the bracket's shape:
> oh fuck you really don't understand the task it seems. Are you saying bracket won't use a context
> effect handler with the proper hooks? Bracket(acquire)(use)(release): the cell region, holding
> the release from derive on, around an acquire region whose done fills the cell and returns
> use(a).

Standing consequence: the bracket is a region built on the handler protocol's hooks, its `done`
taking the acquire's value and continuing with the use. It is not a node kind and not an arrow.

**2026-09-13**, on the overnight report's proposal to fold `handleCont` away:
> wtf are you talking about before/after!? you're proposing removing handleCont!? WTFFF

Standing consequence: `handleCont` stays. The public handler surface is not a variable of a
release redesign.

## Scope

**2026-08-29** on rewriting `run` while changing region handling:
> you should only update the regions handling?

**2026-08-29**, when the stack type was already what had been asked for:
> don't we just need arrays in Stack? WHATS GOING ON!?

Standing consequence: changes stay inside the derivation's declared surface. An improvement
outside it is still a finding, because nobody agreed to it.

**2026-09-12**, during the live review of the robustness change, on two benchmark classes added
to kyo-bench beside a consumer change and a bounds check:

> why are you benchmarking span and choice?

> remove those benchmarks for now

Standing consequence: a benchmark class outside the module under review is not evidence for the
review and is not part of the walk. A consumer-level number that supports a ruling is presented
as a number in the package, from a session, and the class that produced it stays in the isolated
worktree unless the reviewer asks for it.

## Naming

**2026-08-29**, setting the vocabulary for the change:
> Avoid new terminology: "drive" is explicitly banned, the correct is "eval", don't use "after" use
> "cont", make sure naming is fully consistent.

## Working method

**2026-08-29**, on the shape of the work:
> make sure the code is as simple and as safe as possible. Avoid new types if possible. Prefer code
> that is correct by construction. If you find yourself handling multiple edge cases you need to
> take a step back and rethink the approach.

**2026-08-29**, opening the live-review model:
> You do NOT touch my code like you did recently after compaction. You can only change it via a
> "live review". It's a different model of execution where your goal is to present a live review
> that will pass my review and you know how picky I am. Your ultmost goal must be satisfying my
> requirements and ensuring the live review will go smoothly.

**2026-08-29**, on how the previous attempt went:
> reflect on how you got a lot of instructions and just went ahead producing garbage code

**2026-09-11**, on where work happens and how it is preserved:
> you must always commit as you go! work on the scaladocs in my worktree directly

**2026-09-13**, sending the redesign to be built overnight:
> ok, work on it to completion in an isolated worktree. Be diligent to simplify things and ensure
> correctness by construction. Read the kernel skill again to remember. I'll go to bed and expect
> you to work fully autonomously without any excuses. Don't stop, I expect the design fully
> validated by morning and all that can be cleaned properly cleaned

Standing consequence: an isolated worktree, one verified commit per item, the design validated on
every platform and every downstream suite before it is reported, and the removals the design
implies made in the same walk rather than left for later.

## Inference and workarounds

**2026-08-28**, on a compile error worked around rather than root-caused:
> You cant workaround real issues, even if they're inference issues

> remember: avoid working around inference issues

Standing consequence: an inference failure is diagnosed to its root in the kernel and fixed there.
An ascription, a helper, or a widened type that makes the site compile is a workaround.

## Applying a live review

**2026-08-30**, on reaching for a bulk replace mid-walk:
> and you were about to do a batch edit!? where's the live review preparation?

Standing consequence: the walk is applied one edit at a time with the Edit tool, and the sequence
must exist as data before the walk starts. A described sequence is not a sequence: `sequence.json`
holds the exact text pairs and `sequence.py --verify` proves they reproduce the tip. If applying
ever needs improvisation, PACKAGE did not finish.

**2026-08-30**, on the tree the walk starts from. A half-applied walk was committed and left
`kyo-kernel` red, which is not a state to restart from and not a tree to build in. Standing
consequence: the walk is atomic in the sense that matters. If it stops part way, the sources go back
to the baseline byte for byte before anything else happens, and the target state stays in the
isolated worktree where it already lives.

**2026-09-12**, on the status report of the robustness change, which reported a multi-shot re-entry
defect at the base and a fix for it:

> I don't get it, how come kernel and prelude tests pass if this is wrong?

Standing consequence: a claim that the base is wrong carries, in the same breath, the reason the
suites did not catch it: which shape no case had, and which consumer masked it by hand. A defect
report without that is a claim the reviewer cannot place, and the first question it gets is this
one. The package's ruling on the fix records the answer beside the claim.

**2026-09-12**, when the package was ready and the agent had spent the day on lens rounds, benchmark
reruns and an experiment while two regressions it had found stayed unfixed, then proposed fixing
them before the review:

> fuck man......... let's just do the live review of what we have so far. You waste so much time
> with silly reward hackigns

Standing consequence: once the reviewer has asked for the review, the review starts. Polishing the
package, re-running lenses, and reordering work ahead of him are the agent spending his time; the
package is judged live, by him, and what it lacks is a finding there, not a reason to delay.

## Evidence

**2026-08-30**, discovered rather than ruled, and the worst escape this pipeline has had. Four rounds
of review argued over benchmark tables produced by `ProtoKernelBench`, a class that measures
`kyo.kernel` and contains no reference to `proto`; its name is left over from the rename that made
kernel2 the kernel. No benchmark under `src/jmh` referenced `kyo.proto` at all, so nothing had ever
measured the file under review.

Standing consequence: before a number is evidence, verify that the thing measured is the thing
changed, mechanically. `package-check.sh` resolves every benchmark class a package names and reports
one that does not reference the package under review. A plausible name answers the question by
looking right, which is why judgment kept passing it.

**2026-09-12**, discovered during the live review of the robustness change: a `Jmh / classDirectory`
setting that is right for a module whose benchmarks live under `src/jmh` (kyo-kernel, where it keeps
the benchmark classes out of the directory scaladoc reads) is wrong for a module whose benchmarks
live under `src/main` (kyo-bench, kyo-ffi-bench): the generated benchmark list moves and the runner
finds nothing. The walk had applied it to all three "for consistency". Standing consequence: a build
setting copied to a sibling module is verified on that module, by running the thing it configures,
before it enters a walk.
