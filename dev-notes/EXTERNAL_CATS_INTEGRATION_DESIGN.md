# kyo cats-effect integrations maintained outside the kyo repo

**Status:** Draft
**Last updated:** 2026-08-03
**Related:** [getkyo/kyo#1778](https://github.com/getkyo/kyo/issues/1778) (removal), [getkyo/kyo#1779](https://github.com/getkyo/kyo/pull/1779) (the removal PR)

## Context and scope

PR #1779 removed every cats-effect integration from the kyo repository. Three
modules were deleted: the direct `kyo-cats` bridge, the `kyo-compat` cats-effect
backend, and the `kyo-scheduler-cats` runtime. Only the comparison benchmarks
still depend on cats-effect, and they are not an integration anyone consumes.

The integrations themselves are useful and some projects already depend on them.
The clearest case is a library that is written once against the `kyo-compat`
surface and cross-published per backend, including a cats-effect artifact; when it
upgrades to a kyo version past the removal, its cats-effect build stops resolving.
That project has to obtain the integration from somewhere other than the kyo repo.

This document describes how to keep any of the three integrations alive in a
repository that is not the kyo repo. It covers what each integration is, what the
kyo repo provides to make external maintenance work, and the concrete build recipes
for each combination of integration and distribution mode. It is a plan for the
maintainer who takes one of these on, not a proposal to change kyo.

Out of the scope of this document: reintroducing cats-effect code into kyo,
publishing these artifacts under the `io.getkyo` organization, and prescribing who
owns the external repository or how it is governed.

## Goals

- Let each of the three integrations be built, published, consumed, and validated
  from a repository other than kyo, with no cats-effect code in kyo.
- Support two distribution modes so the choice fits the maintainer: vendoring the
  source into one consumer, and publishing a standalone artifact for many.
- Require no ongoing work in the kyo repo per integration. The integrations build
  on kyo's published artifacts and the plugin extension points that ship in the
  first kyo release after the removal.
- Give a consumer that used the built-in cats-effect backend a mechanical
  migration path with no change to its own source or its published artifact names.

## Non-goals

- Shipping a cats-effect binding artifact from kyo. kyo provides the tooling to
  build and validate one; it does not provide the artifact.
- Choosing the maintainer, the repository layout, or the artifact coordinates.
  Those are decisions for whoever adopts this, listed at the end.
- Changing the `kyo.compat.*` surface or the `Cats.get`/`Cats.run` API. The
  external code is the removed code, carried forward as-is.

## Background: the three integrations

The integrations are independent. They share no code, target different audiences,
and can be maintained separately or together.

**A. Direct bridge (`kyo-cats`).** Two functions that move a computation across the
kyo/cats-effect boundary:

```scala
def get[A](io: => cats.effect.IO[A])(using Frame): A < (Abort[Nothing] & Async)
def run[A](v: => A < (Abort[Throwable] & Async))(using Frame): cats.effect.IO[A]
```

`get` runs a `cats.effect.IO` as a kyo computation; `run` interprets a kyo
computation as a `cats.effect.IO`, honoring cancellation in both directions. This
is for code written directly in kyo that has to call into cats-effect libraries.
It is a small module in `package kyo`, depends on `io.getkyo:kyo-core` and
cats-effect, and cross-builds JVM and JS on Scala 3.

**B. kyo-compat cats-effect backend (`ce`).** An implementation of the entire
`kyo.compat.*` surface (`CIO`, `CFiber`, `CPromise`, `CChannel`, `CStream`, the
atomics, `CLatch`, `CMeter`, `CLocal`) backed by `cats.effect.IO`. `kyo-compat`
lets a library be written once against that surface and shipped to several
runtimes; this backend is the cats-effect target. It depends only on cats-effect
and fs2, has no dependency on kyo (it provides the `kyo.compat.*` types itself),
and cross-builds JVM and JS on Scala 3. This is the integration that a
backend-agnostic library depends on.

**C. Scheduler runtime (`kyo-scheduler-cats`).** A cats-effect `IORuntime` whose
compute and blocking pools are kyo's scheduler, plus an `IOApp` that uses it:

```scala
object KyoSchedulerIORuntime { implicit lazy val global: cats.effect.unsafe.IORuntime = ... }
trait KyoSchedulerIOApp extends cats.effect.IOApp { override def runtime = KyoSchedulerIORuntime.global }
```

The implementation is one call, `kyo.scheduler.Scheduler.get.asExecutionContext`,
handed to `IORuntime`. This is for a cats-effect application that wants kyo's
work-stealing scheduler; no kyo effects are involved. It depends on
`io.getkyo:kyo-scheduler` and cats-effect, is JVM only, and builds on Scala 2.13
and 3.

## Overview

Maintaining an integration externally is two independent decisions.

First, **which integration**: A, B, C, or any combination. The choice follows the
need. A backend-agnostic library targeting `kyo.compat.*` needs B. Code that mixes
kyo and cats-effect directly needs A. A cats-effect application that wants kyo's
scheduler needs C.

Second, **how to distribute it**:

- **Vendored.** Copy the source into your own repository and build it as one of
  your modules. There is no separate published artifact and no coordination cost.
  This fits a single consumer that just needs the integration to exist.
- **Standalone.** A repository publishes the integration under its own Maven
  organization, and any number of projects depend on it. This fits shared,
  community maintenance.

The two modes are not exclusive. Because the source and the exposed surface are
identical either way, a consumer can vendor now and switch to a published artifact
later without changing its own code.

The rest of this document covers what kyo provides to support these choices, then the
per-integration recipes, then a worked migration for the common case.

## How it works

### Building blocks the kyo repo provides

The direct bridge and the scheduler runtime build on artifacts kyo already
publishes (`kyo-core`, `kyo-scheduler`). The compat backend additionally uses two
`kyo-compat-plugin` extension points added by the same change that removed the code,
`CompatBackendAxis.external` and `.compatConformance`. These ship in the first kyo
release after the removal, so a consumer of that path needs that plugin version or
later. No integration needs `private[kyo]` access or code inside `package kyo` that
it cannot legally compile from outside.

- **`io.getkyo:kyo-core`** backs the direct bridge (A). The bridge uses only public
  API: `Fiber.initUnscoped`, `Promise.Unsafe.init`, the `Fiber.Unsafe` `onComplete`
  and `interrupt` operations, and `Sync.Unsafe.defer`/`evalOrThrow`. None of these
  is package-private, so the bridge compiles from an external module.

- **`io.getkyo:kyo-scheduler`** backs the scheduler runtime (C). The whole
  integration is the public call `kyo.scheduler.Scheduler.get.asExecutionContext`.
  `kyo-scheduler` is a standalone module with no kyo-core dependency and is usable
  from Scala 2.13.

- **`io.getkyo:kyo-compat-plugin`** backs the compat backend (B). The plugin
  generates one sbt project per (backend, platform, Scala version) from a single
  `compatLibrary(...)` declaration. Two extension points, added specifically to
  support out-of-repo backends, make an external backend a first-class citizen:

  - `CompatBackendAxis.external(name, idSuffix, directorySuffix, supportedPlatforms, organization, artifactName, version)`
    declares a backend that resolves to your Maven coordinates. The built-in
    backends resolve to `io.getkyo:kyo-compat-<name>`; an external axis resolves to
    `<organization>:<artifactName>:<version>`. External and built-in coordinates go
    through the same code path, so an external backend behaves exactly like a
    built-in one everywhere else (row generation, cross-platform suffixes,
    `dependsOn` wiring).

  - `CompatBackendAxis.local(name, idSuffix, directorySuffix, supportedPlatforms)`
    declares a backend with no coordinates, for vendoring: the binding is a project
    in the consumer's own build, wired in with `bindLocally(axis, project)` rather
    than resolved from Maven. An unbound `local` backend fails at build load with a
    clear error.

  - `matrix.compatConformance()` wires the cross-binding conformance suite into
    every generated row's test scope. kyo bundles that suite inside the plugin jar;
    the call extracts it into `Test/sourceManaged`, adds scalatest, and sets the
    compiler options the suite needs, so the suite compiles against your binding and
    runs. This is how an external binding proves it satisfies the same contract the
    built-in backends satisfy.

### A. Direct bridge (`kyo-cats`)

The whole integration is one file, `Cats.scala`, in `package kyo`.

Vendored: copy `Cats.scala` into a module of your build and add its dependencies.

```scala
libraryDependencies += "io.getkyo"    %%% "kyo-core"    % kyoVersion
libraryDependencies += "org.typelevel" %%% "cats-effect" % "3.7.0"
```

`Cats.get` and `Cats.run` are then available. Vendor `CatsTest.scala` alongside it
as a regression guard; it covers abort ordering, fiber interop, and cancellation
in both directions.

Standalone: a `crossProject(JVMPlatform, JSPlatform)` with those two dependencies,
published as `<your-org>:kyo-cats`. Consumers depend on it directly.

### B. kyo-compat cats-effect backend (`ce`)

The binding provides the full `kyo.compat.*` surface for `cats.effect.IO`. It
depends only on cats-effect and fs2 and cross-builds JVM and JS.

A load-bearing detail for binding authors: on this backend `CIO[+A]` is an opaque
alias of `cats.effect.IO[A]`, so `CIO.lift`/`lower` are the identity and
`CStream` lowers to an `fs2.Stream`. A consumer that reaches through the surface
into the native carrier (`.lower` to a `cats.effect.IO`, `.lift` from one) relies
on that specific carrier, not merely on the portable operations. Keep the carrier
as published in the removed source; the conformance suite checks the portable
surface, not the carrier identity.

Standalone: publish the binding under your own coordinates, per platform. A
consumer declares the backend with `CompatBackendAxis.external` and lists it with
the built-in backends. Preserving the removed backend's `idSuffix` (`Ce`) and
`directorySuffix` (`-ce`) keeps a consumer's generated module ids and published
artifact names unchanged.

```scala
// project/plugins.sbt: addSbtPlugin("io.getkyo" % "kyo-compat-plugin" % kyoVersion)
// plus sbt-projectmatrix and the scala-js / scala-native crossproject plugins
// (see the kyo-compat README, "Cross-publishing with the sbt plugin").

// build.sbt
val CeLib = CompatBackendAxis.external(
  name = "ce", idSuffix = "Ce", directorySuffix = "-ce",
  supportedPlatforms = Set("jvm", "js"),
  organization = "<your-org>", artifactName = "kyo-compat-ce", version = "<ce-version>"
)

lazy val myLib = (projectMatrix in file("my-lib"))
  .compatLibrary(KyoLib, ZioLib, CeLib)(VirtualAxis.jvm, VirtualAxis.js)(Seq("3.3.4"))
```

Each `ce` row resolves `<your-org>:kyo-compat-ce:<ce-version>`; the built-in rows
are unaffected. External backends have no named accessor, so reach their rows with
`myLib.get(CeLib)`, which returns `Option[CompatBackendProjects]`
(`myLib.get(CeLib).get.jvm` for one cell).

Vendored: declare the backend with `CompatBackendAxis.local` (no coordinates), keep
the binding as a local project, and bind the backend to it with `bindLocally`
instead of resolving a published artifact.

```scala
val CeLib = CompatBackendAxis.local("ce", "Ce", "-ce", Set("jvm", "js"))

lazy val kyoCompatCe = (crossProject(JVMPlatform, JSPlatform) in file("kyo-compat-ce"))
  .settings(
    libraryDependencies += "org.typelevel" %%% "cats-effect" % "3.7.0",
    libraryDependencies += "co.fs2"         %%% "fs2-core"    % "3.13.0"
  )

lazy val myLib = (projectMatrix in file("my-lib"))
  .compatLibrary(CeLib)(VirtualAxis.jvm, VirtualAxis.js)(Seq("3.3.4"))
  .bindLocally(CeLib, kyoCompatCe.jvm)
```

A `local` backend must be bound; an unbound one fails at build load with a clear
error. `bindLocally` also overrides an `external` backend's coordinates, so a
consumer can move between vendoring and a published artifact without touching its
own source.

Validate the binding with the conformance suite:

```scala
lazy val ceConformance = (projectMatrix in file(".conformance"))
  .compatLibrary(CeLib)(VirtualAxis.jvm)(Seq("3.3.4"))
  .bindLocally(CeLib, kyoCompatCe.jvm)
  .compatConformance()
  .settings(publish / skip := true)
// sbt ceConformanceCe/test  compiles and runs the shared suite against your binding
```

`compatConformance()` extracts the suite that exercises fibers, promises, channels,
atomics, latches, meters, locals, streams, and error semantics, and runs it against
your binding. It is the check that a cats-effect or fs2 upgrade, or any change to
the binding, still honors the surface.

### C. Scheduler runtime (`kyo-scheduler-cats`)

Two files on top of `io.getkyo:kyo-scheduler`, JVM only, Scala 2.13 and 3.
`KyoSchedulerIORuntime.global` is an `IORuntime` on kyo's scheduler;
`KyoSchedulerIOApp` is an `IOApp` that uses it. Opting in is one line:

```scala
object Main extends kyo.KyoSchedulerIOApp:
  def run(args: List[String]): cats.effect.IO[cats.effect.ExitCode] = ???
// or, for a plain program:  import kyo.KyoSchedulerIORuntime.global
```

Vendored: copy the two files and depend on `io.getkyo:kyo-scheduler` plus
cats-effect. Standalone: a `crossProject(JVMPlatform)` on `kyo-scheduler`,
published as `<your-org>:kyo-scheduler-cats`, cross-built for Scala 2.13 and 3,
with the preserved tests.

### Sourcing the removed code

All three integrations exist verbatim at `90f79b86a4^`, the commit before the
removal merge. Lift a module or a file:

```sh
git archive 90f79b86a4^ kyo-cats                | tar -x -C <dest>
git archive 90f79b86a4^ kyo-compat/bindings/ce  | tar -x -C <dest>
git archive 90f79b86a4^ kyo-scheduler-cats      | tar -x -C <dest>
git show    90f79b86a4^:kyo-cats/shared/src/main/scala/kyo/Cats.scala
```

The `kyo-cats` and `kyo-scheduler-cats` modules had their own README at that
commit; the `ce` backend was documented inside `kyo-compat/README.md`. The code
last built against cats-effect `3.7.0` and fs2-core `3.13.0`.

## Worked example: migrating a kyo-compat consumer

A concrete instance of integration B is a library that is written once against
`kyo.compat.*` and cross-published per backend, shipping a first-class cats-effect
artifact (for example a `-ce` module exposing cats-effect and fs2 types). While the
`ce` backend was built into kyo, such a project got its `CeLib` axis from the
plugin's `autoImport` and its `ce` cell resolved `io.getkyo:kyo-compat-ce`. After
the removal, both are gone: the axis is no longer in `autoImport`, and the artifact
is no longer published. The `sage` Redis/Valkey client is a live example: it depends
on the current kyo release, where `ce` is still built in. The removal is not yet in
any release, so this is the migration such a consumer makes when it upgrades to the
first release that carries it.

The migration for such a consumer is mechanical and touches only its build:

1. Vendor the `ce` binding source (integration B, above) as a local module, or
   depend on a standalone `kyo-compat-ce` if one is published.
2. Define `CeLib` locally, using the same `idSuffix = "Ce"` and
   `directorySuffix = "-ce"` the built-in axis used, so module ids and the `-ce`
   artifact name do not change: `CompatBackendAxis.local(...)` when vendoring,
   `CompatBackendAxis.external(...)` when depending on a published artifact.
3. If vendoring, `bindLocally(CeLib, <local ce project>)`; if consuming a published
   binding, the external coordinates on the axis resolve it.
4. Keep the conformance harness so the vendored binding is validated on every build.

No application or library source changes. The consumer's public artifacts keep
their names. The only new responsibility is owning the binding source, which is the
transfer of maintenance that #1778 set out to make.

## Alternatives considered

**Keep cats-effect in kyo behind an opt-in flag.** Rejected by #1778. The point of
the removal is that kyo does not carry this code; a flag still carries it.

**Republish the integrations under `io.getkyo`.** Not available to an external
maintainer, who has no rights to that organization, and it would recreate the
coupling the removal broke. External maintainers publish under their own
organization; `CompatBackendAxis.external` exists precisely so the coordinates are
not fixed to `io.getkyo`.

**One umbrella repo versus one per integration.** Left open. The three modules are
independent, so either works. A single repository with three modules reduces
release overhead; separate repositories let different people own different pieces.

**Vendoring only, no plugin support.** A consumer could vendor the `ce` source and
hand-wire a project matrix without the plugin's external-backend support. That
duplicates the row generation, cross-platform suffixing, and conformance wiring the
plugin already does, and drifts from how the built-in backends are built. The
external-backend extension points exist so vendoring and publishing use the same
supported path.

## Cross-cutting concerns

**Versioning.** Depend on a single published kyo version; `kyo-core`,
`kyo-scheduler`, and `kyo-compat-plugin` release together. Pin cats-effect and fs2
independently. The last-known-good versions are cats-effect `3.7.0` and fs2
`3.13.0`; newer ones are fine, and for B the conformance suite is the safety check
on a bump.

**Testing.** A and C carry their own preserved test suites. B is validated by the
conformance suite through `compatConformance()`, which is the same contract the
built-in backends pass. kyo's own CI compiles that bundled suite against a real
backend, so the extract-and-compile path an external binding relies on is
regression-tested upstream, not just at the point of adoption. Note that the
conformance suite proves the portable surface behaves correctly; it does not assert
the backend's carrier identity (that `CIO` is `cats.effect.IO` and `CStream` lowers
to `fs2.Stream`). A consumer that uses `.lower`/`.lift` depends on that identity, so
keep the carrier as published rather than substituting a differently-carried
implementation.

**Maintenance boundary.** kyo provides the building blocks and keeps them stable;
it does not provide the artifacts. The gap between "the removal merged" and "a
consumer's cats-effect build is green again" is exactly the work of vendoring or
publishing the binding, which is the maintenance transfer #1778 intends. If
maintaining any integration externally surfaces a kyo API that should be public but
is not, that is an issue kyo will address; exposing the surface an external
integration needs is in scope, hosting the cats-effect code is not.

## Open questions

These are for whoever adopts the work.

1. **Which integrations get an external home,** and who maintains each. A, B, and C
   are independent; a consumer may only need one.
2. **Vendored, standalone, or both.** Vendoring is zero-coordination for a single
   consumer; a standalone artifact serves many. Switching later needs no code
   change.
3. **Repository layout and coordinates.** One repository or several; artifact names
   (`kyo-cats`, `kyo-compat-ce`, `kyo-scheduler-cats`, or new ones) and the
   publishing organization.
