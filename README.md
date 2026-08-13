# Pontif Framework

![Pontif Playground editing the Traction example](assets/traction-ptf.png)

**A practical language with an unusually robust, unusually honest type system.**

Pontif is built for writing real programs — it has modules and namespaces, three
kinds of polymorphism, operator overloading, a native GUI and plotting, the ability
to compile a computation to the **GPU at runtime**, and an orchestration layer for
mutable state and message-passing between processes. What sets it apart is that its
**declared types are claims the compiler proves or rejects**, never annotations it
trusts — and it stays a *language for building things*, not a proof assistant for
mathematicians.

That last distinction is deliberate. There are already plenty of languages for
developers and plenty of proof systems for mathematicians (and no mathematician will
take you seriously unless you're using Lean anyway). Pontif isn't trying to beat
either — it's trying to **merge** them sensibly: the ergonomics of a working
language, with a type system strong enough that there's no excuse to leave it. There
is intentionally **no SMT solver**. A proof is only as good as it is *auditable*, and
a compact prover you can read end-to-end — plus a legible ledger of what it
concluded — beats an opaque oracle you have to trust. The influences run from
Smalltalk as much as from Liquid Haskell.

Built on GraalVM's Truffle: a JIT for free, native-image binaries, and an IR seam
that already lowers to three backends (Truffle, GLSL, SPIR-V/Vulkan).

## Contents

- [A quick example](#a-quick-example)
- [The pillars](#the-pillars) — dispatch · polymorphism · conservation · effects · stdlib
- [You can read what the compiler concluded](#you-can-read-what-the-compiler-concluded)
- [The guide](#the-guide) — the in-depth docs
- [Status](#status)
- [The `pontif` CLI](#the-pontif-cli)
- [Build and test](#build-and-test)
- [License](#license)

## A quick example

```pontif
module ledger

struct Account(balance:[Int:@>=0])
struct Txns(amount:Int, rest:[Txns|Done])
struct Done()

method Account.deposit(n:Int):Account -> match n {
  [@>0]  -> Account(this.balance + n)
  [@<=0] -> this
}

function totalIn(ts:[Txns|Done]):Int -> match ts {
  [Txns] -> ts.amount + totalIn(ts.rest)
  [Done] -> 0
}

Account(0).deposit(totalIn(Txns(100, Txns(50, Done())))).balance   # → 150
```

A lot of the language is already here:

- **`module ledger`** names the file's namespace — and a namespace can span
  *several* files (every `.ptf` under the project with the same `module` header
  merges into one module, Go-package style). **`Txns` / `Done`** are an inductive
  list — a value is either a `Txns` (an amount plus the rest) or the empty `Done`;
  recursion over that union is how `totalIn` folds it.
- **`struct Account(balance:[Int:@>=0])`** declares a record whose `balance` field is
  a *refined* type: an `Int` provably `>= 0`. The `[...]` wrap a type; the predicate
  inside is a real proof obligation, not a comment — a construction that can't satisfy
  it is a compile error.
- **`@`** is the subject of the enclosing refinement — the value being described or
  matched. In `match n { [@>0] -> … }`, `@` *is* `n`; in `[Int:@>=0]`, `@` is the
  field's value.
- **`match`** arms *are types*: `[@>0]` and `[@<=0]` partition every `Int`, and the
  compiler checks the partition is total. `[Txns]` / `[Done]` discriminate the union —
  a sum-type fold with no tag field and no default arm.

The rest of the language is an elaboration of these moves. The [type-system
guide](docs/guide/type-system.md) is the full tour; the pillars below are the
highlights.

## The pillars

### Dispatch and proven returns

Sorts narrow by predicate, overloads dispatch on the narrowing, and declared returns
are proof obligations the compiler discharges — or rejects:

```pontif
function factorial(n:[Int:0])  :[Int:@>=1] -> 1
function factorial(n:[Int:@>0]):[Int:@>=1] -> n * factorial(n-1)

function inc(x:[Int:@>=1]):[Int:@>1] -> x + 1

function sign(n:Int):Int -> match n {
  [@<0 ] -> -1
  [@==0] ->  0
  [@>0 ] ->  1
}

factorial(5) + inc(4) + sign(-7)   # → 124
```

Overlap between overloads is rejected at registration; `inc`'s `[Int:@>1]` is proven
from `x >= 1`; `factorial`'s claim closes inductively. When the math is genuinely
beyond the built-in prover, the program is *rejected* — and you supply the missing
reasoning with an `assign proof` that lives beside the code. → [Dispatch, refinement,
and the proof gate](docs/guide/type-system.md#function-dispatching-with-refined-types)
· [Proofs and synthesis](docs/guide/proofs-and-ledgers.md#proofs-and-synthesis)

### Three polymorphism models

Polymorphism is three sharply-separated tools — **traits** (alternative interfaces
with a guaranteed return path to the concrete type), **multi-dispatch** (open
interop, kept coherent by the orphan rule), and **type extension** (promotion to a
richer type, *lose freely, fabricate never*). Traits are the workhorse:

```pontif
trait Payable{ weeklyPay:[Method():Int] }

struct Hourly(rate:Int, hours:Int)
struct Commissioned(base:Int, sales:Int, cut:Int)

assign trait Hourly:Payable {
  weeklyPay():Int -> this.rate * this.hours
}
assign trait Commissioned:Payable {
  weeklyPay():Int -> this.base + this.sales * this.cut
}

function payroll(w:Payable):Int -> w.weeklyPay()

payroll(Hourly(20, 40)) + payroll(Commissioned(300, 10, 25))   # → 1350
```

No inheritance, no vtable — the same module-coherent dispatch, keyed on the receiver.
Traits also carry data attributes and *sort-transform shells* (logic the trait owns,
wrapped around every impl); a struct can bundle its own methods, an is-a base, and the
traits it satisfies in one block; generics never erase. → [The full type-system
guide](docs/guide/type-system.md)

### Conservation — the second ledger

Beyond *what a value is*, Pontif tracks *where it went*. Every function gets a
compile-time dataflow ledger, and `proof` statements assert properties over it:

```pontif
requires std.conservation.{DataConservative}

struct Source(name:Int, age:Int, email:Int)
struct Target(fullName:Int, years:Int, contact:Int)

function translate(s:Source):Target ->
  {fullName = s.name, years = s.age + 1, contact = s.email}

proof translate = DataConservative()       # every Source attribute provably reaches Target

translate(Source(1, 2, 3)).years   # → 3
```

Drop `contact` from `Target` and the program rejects — and the error *is* the receipt,
naming the untouched field. Dropping on purpose is fine, but *declared*. →
[Conservation receipts](docs/guide/proofs-and-ledgers.md#conservation-receipts--the-second-ledger)

### Effects — one door in

Side effects enter through `emit`, a write-only primitive; you react with an `action`
whose parameter *sort is the filter*:

```pontif
requires pontif.events.{Event, StdOut}

struct Tick(n:Int)
assign trait Tick:Event{}

action log(e:Tick)              -> emit StdOut("tick ")  e
action alarm(e:[Tick:@.n > 10]) -> emit StdOut("BIG")    e

main ( emit Tick(42)  0 )       # prints "tick BIG"; main's own value is 0
```

`log` fires for every `Tick`; `alarm` only when `@.n > 10`. An event with no consumer
is an error, not a silent drop. This substrate is what the GUI and plotting build on,
and what the concurrency model extends. → [Actions and events](docs/guide/effects.md#actions-and-events)

### A builtin standard library

Two math modules ship by default, split by *where the math can run* — `pontif.math`
is the GPU-portable `GLSL.std.450` set, `pontif.math.ext` the CPU-only integer number
theory:

```pontif
requires pontif.math.{sqrt, clamp}
requires pontif.math.ext.{gcd, choose}

sqrt(9.0) + clamp(9.0, 0.0, 5.0) + gcd(12, 8) + choose(5, 2)   # → 22.0
```

The split is enforced, not cosmetic: `requires pontif.math.{gcd}` is a compile error,
because the module boundary states honestly what will and won't lower to a GPU. →
[The math library](docs/guide/effects.md#the-math-library)

## You can read what the compiler concluded

The no-SMT stance only works because the reasoning is *legible*. The receipt graph,
the conservation ledger, and the **Narrowings** view are all plain-text windows you
can read. The Narrowings view re-emits your program with declared types replaced by
what the single inference engine actually inferred, walked from any entrypoint:

```
# entrypoint: main
inc(5)

function inc(x:[Int:(@ == 5)]):[Int:(@ == 6)]    # return was: Int
  (x + 1)
```

`inc` was *declared* to return `Int`; entered via `inc(5)`, the engine pinned the
argument to `5` and inferred the return as exactly `6` — it evaluated the call at the
type level. One engine (`NarrowingInference`) answers "what is this value?" at every
stage — parse, sort-check, return gate, dispatch — so no two stages can disagree. →
[One inference engine, every stage](docs/guide/proofs-and-ledgers.md#one-inference-engine-every-stage)

## The guide

The depth lives in `docs/guide/`. Each page is a reader-facing tour that links down to
the dense design docs.

| Guide | Covers |
| --- | --- |
| [Type system](docs/guide/type-system.md) | Refined dispatch · structs & methods · traits · type extension · the three polymorphism models · struct member blocks · generics · operator overloading |
| [Proofs & ledgers](docs/guide/proofs-and-ledgers.md) | `assign proof` · synthesis `;` · algebraic reflection (`.ast`/`.eval`) · conservation receipts · the one inference engine |
| [Notation](docs/guide/notation.md) | The braces/brackets/parens grid and the univocal `->` |
| [Streams](docs/guide/streams.md) | One iteration primitive → map / filter / fold / scan / fork / zip · generators · finite ranges |
| [Effects](docs/guide/effects.md) | `emit` + `action` · the builtin math library |
| [Graphics](docs/guide/graphics.md) | Native GUI · plotting · SDF shapes · `on Gpu` compute kernels |
| [Architecture & craft](docs/guide/architecture-and-craft.md) | The compiler pipeline · why GraalVM · the IR seam · the source-tree map · the details chosen on purpose |

For the canonical language reference see [`docs/alternative-syntax.ptf`](docs/alternative-syntax.ptf),
[`docs/glossary.md`](docs/glossary.md) for terms, and
[`docs/backward-language-design.md`](docs/backward-language-design.md) for the method
that produced all of this.

Every ` ```pontif ` snippet in this README and the guide pages — except the
illustrative fragments in the [Streams](docs/guide/streams.md) and
[Graphics](docs/guide/graphics.md) guides (whose modules live outside `pontif-runtime`)
— is pinned by `ReadmeSnippetTest`: the docs compile, or the build fails.

## Status

Active experimental development. Public APIs are not yet stable — expect breaking
changes while the version reads `1.0-SNAPSHOT`. (The 3D graphics stack is being
rebuilt on Vulkan and will eventually move to its own repository.)

Capabilities that work end-to-end in the Pontif surface syntax:

- Refinement sorts (`[Int:@>0]`, `[Int:0|1|2]`), union and intersection sorts, and
  reusable sort aliases (`let Positive:Type[[Int:@>0]]`)
- Three numeric primitives — `Int`, `Bool`, `Decimal` (BigDecimal-backed) — plus
  `Char`, with implicit `Int → Decimal` coercion at value boundaries and explicit
  `(Type:value)` casts everywhere else
- Decimal narrows (sign, range, equality), proven by a dense-valid discharger kept
  separate from integer-strict reasoning
- Functions and overloads, methods, operator overloading guarded by the orphan rule
- Pattern matching where **patterns are sorts** — refinements, destructure-with-rename,
  per-field narrowing, positional literals, `_` discards, and the narrow-in-place tuple
  binder `name:Sort`
- **Three polymorphism models** — traits (data attributes, default methods, sort-transform
  shells, free bidirectional struct↔trait coercion), module-coherent multi-dispatch, and
  `struct Name:[Base:rel](fields)` type extension — plus **struct member blocks** (a struct's
  own `{ method… }` block with an intersection is-a base `:[Super & T1 & T2]`)
- Synthesis from the spec — the trailing `;` directive (value pins, construction pins,
  in-type `let`-pipelines)
- Metareferences (`$f[Sorts]`) and **algebraic reflection** (`assign proof f:Algebraic`
  → a first-class `AlgExpr` AST via `$f[Decimal].ast`, inspectable with `match`, runnable
  with `eval`)
- Compile-time **match totality**, **return verification**, and **conservation receipts**
  (`DataConservative`, `Reversible`, `NoDuplication`, `DataConservativeExcept`) — all gated,
  with reviewable text reports
- **One inference engine**, inspectable — every stage narrows through `NarrowingInference`;
  the playground's Narrowings view reflects the inferred program
- **Module system** — namespace-as-module (same-namespace files merge across the project),
  FQN-keyed dispatch, import-by-association, the orphan rule, and cross-module struct literals
- **Streams** — one iteration primitive (the synthesis fragment applied by spread `&`) from
  which map / filter / fold / scan / fork / zip fall out; generators, finite ranges, first-class
  fragments, and element-type-checked computed streams
- **A builtin math library** (`pontif.math` + `pontif.math.ext`), an **effect substrate**
  (`emit` + `action`), and a **GUI + plotting** stack, plus GPU compute via `on Gpu`

### What's next

Two threads remain (see [`docs/stream-war.md`](docs/stream-war.md) and
[`docs/TODO.md`](docs/TODO.md)):

1. **Infinite / lazy streams** — the substrate for the event/concurrency model and stateful
   sources, built by guarded infinite recursion and gated by *productivity* (the coinductive
   dual of termination).
2. **Modular arithmetic in the discharge kernel** — `%` / `/` are rejected in refinement
   predicates today (the kernel is linear), so divisibility filters like `@%2==0` can't yet be
   written; unblocking them adds constant-modulus congruences plus a piecewise-linear case-split.
3. **Concurrency** — `emit` + `action` ship; what remains is folding events into asynchronous
   conduits and back-pressured receivers, which rides on the infinite-stream work.

## The `pontif` CLI

`pontif-cli` is the command-line front end — a thin layer over `PontifCompiler` and
`PontifRunner`, so the gates a program passes in the editor it passes here too.

```
pontif new my.app                 # scaffold a project (module.toml + sample source)
pontif run app.ptf                # run a file, a project dir, or a .ptfpkg
pontif run my.app                 #   (directory with a module.toml)
pontif pack                       # validate-by-compiling, then zip to <name>-<version>.ptfpkg
pontif run app-0.1.0.ptfpkg       # execute the packaged artifact
pontif console                    # REPL: declarations persist, expressions print
pontif console --include lib/     #   resolve `requires` against a directory or .ptfpkg
pontif editor app.ptf             # open the Pontif Editor GUI on a file
```

A `.ptfpkg` artifact is a compressed **source bundle** (the `module.toml` marker plus
the `.ptf` sources) — not compiled IR, so the full compile and proof gates re-run on
execution and an artifact can never carry unproven code.

Build it:

```
mvn -pl pontif-cli -am package                  # → pontif-cli/target/pontif-cli.jar
java -jar pontif-cli/target/pontif-cli.jar run app.ptf
mvn -Pnative -pl pontif-cli -am package         # → a native `pontif` binary (needs a GraalVM JDK)
mvn -Pnative -pl pontif-playground -am package  # → a native `pontif-editor` GUI binary
```

Both the editor and the CLI ship as GraalVM native images. `pontif editor` launches
the native `pontif-editor` binary when present (no JVM), otherwise falls back to the
editor jar via `java -jar`. Overrides: `PONTIF_EDITOR_EXE` / `PONTIF_EDITOR_JAR`.

## Build and test

```
mvn clean install              # build all modules
mvn test                       # run every test in the reactor
mvn -pl pontif-demo test       # run the demo & integration tests
```

JDK 25 (sealed interfaces, records, switch pattern matching). Maven 3.9+. GraalVM
Truffle 25.0.1 pulled transitively (aligned with the GraalVM-25 native-image builder).

## License

Pontif is dual-licensed:

- **Source code** is licensed under the
  [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).
  See `LICENSE`.
- **Documentation** (everything under `docs/`, plus `README.md` and other text
  content in the repository) is licensed under the
  [Creative Commons Attribution 4.0 International License
  (CC BY 4.0)](https://creativecommons.org/licenses/by/4.0/). See `LICENSE-docs`.

See `NOTICE` for the full dual-license statement and attribution.
