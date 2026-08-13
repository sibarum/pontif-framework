# Effects — actions, events, and the math library

*Part of the [Pontif guide](../../README.md). This page covers Pontif's effect
substrate — the write-only `emit` primitive and `action` reactions — and the two
builtin math modules. For the one-page overview, see the root
[README](../../README.md).*

## Contents

- [Actions and events](#actions-and-events)
- [The math library](#the-math-library)

## Actions and events

Side effects enter through one door: **`emit`**. `emit E(…)` fires a value of an event
type into a per-type conduit and *returns nothing* — it is write-only, and the emitter
never observes what happens downstream. You react with an **`action`**: a consumer
`action name(e:Sort) -> body` that runs — synchronously, in declaration order —
whenever an `emit` produces an event its parameter *sort* accepts. The sort **is** the
filter, so a refinement narrows which instances fire, and one event fans out to every
matching action. `StdOut` / `StdErr` are the builtin sink events.

```pontif
requires pontif.events.{Event, StdOut}

struct Tick(n:Int)
assign trait Tick:Event{}

action log(e:Tick)              -> emit StdOut("tick ")  e
action alarm(e:[Tick:@.n > 10]) -> emit StdOut("BIG")    e

main ( emit Tick(42)  0 )       # prints "tick BIG"; main's own value is 0
```

`log` fires for every `Tick`; `alarm` fires only when `@.n > 10`, so `Tick(42)`
triggers both (in declaration order) while `Tick(3)` would trigger only `log`. An
event with *no* consumer — neither a sink nor an action — is an error, not a silent
drop: effects fail closed like everything else. `main ( … )` is the program's
top-level effect block. This is the realized core of the effect model — the `emit`
primitive the trait *sort-transform shells* were scaffolding for
([docs/events.md](../events.md)).

## The math library

Two builtin modules ship with every program, split by *where the math can run*.
`pontif.math` is exactly the SPIR-V `GLSL.std.450` set — `sin` / `cos` / `sqrt` /
`pow`, `clamp` / `mix` / `smoothstep`, `floor` / `abs` / `sign`, and constants like
`pi()` — the GPU-portable surface, every function mapping 1:1 to a GPU opcode.
`pontif.math.ext` adds the CPU-only integer number theory that has no such opcode —
`gcd`, `lcm`, `factorial`, `choose`, `modpow`, `isqrt`. Both are installed by
default; you reach a function by `requires`-ing it.

```pontif
requires pontif.math.{sqrt, clamp}
requires pontif.math.ext.{gcd, choose}

sqrt(9.0) + clamp(9.0, 0.0, 5.0) + gcd(12, 8) + choose(5, 2)   # → 22.0
```

The split is enforced, not cosmetic: `requires pontif.math.{gcd}` is a compile error
(`gcd` has no GLSL opcode, so it lives only in `pontif.math.ext`) — the module
boundary states honestly what will and won't lower to a GPU.

Honesty extends to precision. The exact common ops (`abs`, `floor`, `clamp`, `mix`,
`fma`, …) are computed exactly over `Decimal`; the transcendentals are `double`-backed
and return *exactly the digits a `double` justifies* — never a long exact-looking
expansion claiming a certainty it doesn't have:

```pontif
requires pontif.math.{sqrt}
sqrt(2.0)   # → 1.4142135623730951  (the honest ~17 digits of a double, no more)
```

---

**Full design notes:** [events](../events.md) · [actions](../actions.md) ·
[orchestration](../orchestration.md) · [mvcc-state](../mvcc-state.md)
