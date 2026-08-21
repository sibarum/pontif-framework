# Streams

*Part of the [Pontif guide](../../README.md). This page is the tour of Pontif's
stream model — one iteration primitive from which map / filter / fold / scan / fork
/ zip all fall out. For the one-page overview, see the root
[README](../../README.md).*

A `Stream[T]` is the pure, conservation-checked membrane over a sequence (and,
ultimately, over messy stateful sources). It is a **trait** in `pontif.core` — a tuple
literal autoboxes into one, element-checked. Per-element control during iteration is
signalled by **returning a control value** from the fragment body — the `Nothing` family
(there is **no built-in `null` keyword**; `null` is just a conventional name for
`Nothing`'s sole value):

- **`Nothing`** → **drop** this element, keep going (the lossy filter's omission).
- **`Break`** → **terminate** the stream; the triggering element is not emitted
  (`takeWhile`, and the cutoff for an infinite stream).

Both are consumed by the machinery and never appear in the output — a body returning
`[T|Nothing|Break]` produces a `Stream[T]`. The setup the rest of this section builds on:

```pontif
requires pontif.core.{Stream, Nothing, Break}
let s:Stream[Int] = {1, 2, 3, 4}
let null:Nothing = Nothing()                  # `null` is a name for Nothing's only value
let stop:Break = Break()
```

There is **no `map`/`filter`/`fold` primitive**. There is one construct — the **synthesis
fragment**, a per-element transform applied to a stream by **spread** (`&`). filter has
two faces: a **body that returns `null`** drops an element, or — equivalently, with no
branch in the body (GPU-friendly) — a **domain-refined binder** `(el:[T:pred])` admits
only in-domain elements (the *subscribe* semantic). Either way it **drops and continues**;
terminating is the separate `Break` return:

```pontif
&s:[ (el:Int) -> el * 2 ]                     # map  → {2, 4, 6, 8}

&s:[ (el:[Int:@>2]) -> el ]                   # filter (guard) — drops ≤2, continues → {3, 4}

&s:[ (el:Int) ->                              # filter (body) — `null` drops the element
       match el
         [@>2] -> el
         [_]   -> null ]                      # → {3, 4}

&s:[ (el:Int) ->                              # takeWhile — `Break` halts the stream
       match el
         [@<3] -> el
         [_]   -> stop ]                      # → {1, 2}  (not {1, 2, ...}: it stops, not skips)
```

Note the difference: a domain-refined binder `(el:[Int:@<3])` would yield `{1, 2, ...}`
(**dropping** off-domain elements and continuing), whereas returning `Break` **stops** at
the first — a filter and a takeWhile, the two dispositions kept distinct.

Every classic combinator is a configuration of this one idea — the *positional channel*
model, where each tuple position is a channel and `&` distributes a transform over it:

| operation | shape |
|-----------|-------|
| **map** | one stream channel, single return |
| **filter** | one stream channel; a `null` return **or** a domain-refined binder drops (lossy), continues |
| **takeWhile** | body returns **`Break`** → halts the stream (element not emitted) |
| **fold / scan** | a stream channel + an accumulator seed (`fold(&s, 0)`); one fragment can map *and* fold at once |
| **fork** | one stream in, a *tuple of stream channels* out (conservative split) |
| **zip** | several `&` streams walked in lockstep (`(&a, &b):[ (x:Int, y:Int) -> x+y ]`) |

A **generator** is the dual of fold — a stream *source* with no `&` input, where **the
domain refinement is the base case** (it halts exactly when the next state would be
ill-typed):

```pontif
let count:[ (from:[Int:@>=0], to:[Int:@>=from]):{Stream[Int], Int, Int} ->
            {from, from+1, to} ]
count(0, 5)._0                                # → {0, 1, 2, 3, 4, 5}
```

A finite **range** needs no hand-written step at all: a membership refinement on the
element type *is* the definition, materialized on request with the `;` directive. The
traversal **direction is read from the comparison chain**, and any non-bound condition
filters per element:

```pontif
let ascending :Stream[Int:0 <= @ < 10];            # → {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}
let descending:Stream[Int:10 > @ >= 0];            # → {9, 8, 7, 6, 5, 4, 3, 2, 1, 0}
let trimmed   :Stream[Int:0 <= @ < 10 & @ != 5];   # → {0, 1, 2, 3, 4, 6, 7, 8, 9}
```

(Integer bounds, materialized statically; arithmetic-divisibility filters like `@%2==0`
await modular arithmetic in the discharge kernel — see [docs/TODO.md](../TODO.md).)

Streams **concatenate** with `+` (the same rule that gives `String + String`, since a
`String` is a `Char` stream), and a *computed* stream's element type is checked against
its declaration — `let z:Stream[String] = double(&s)` over an `Int` stream is a compile
error, not a silent lie.

A fragment is a **first-class value** (the lambda replacement): it can be bound by `let`
in any scope, passed to a `[Method(A):R]` parameter, and returned. So combinators
generalize — a generic `map` runs both explicitly and by inference:

```pontif
function map[type A, type R]( s:Stream[A], mapper:[Dispatch(A):R] ):[Stream[R]] ->
  &s:[ (el:A) -> mapper(el) ]
function toString(i:Int):String -> ""+i

map[Int,String](s, $toString[Int])            # explicit  → {"1", "2", "3", "4"}
map(s, $toString[Int])                         # inferred  (A, R recovered from the args)
```

Everything above is the **finite** half — a stream whose elements are all known up
front. The other half is a **live source**: something the runtime pulls one element at
a time, sealing when it runs dry, rather than materializing. `stdin` is the first, and
the iteration engine drives it with a pull-loop, so laziness lives in the *iterator*
and never in the data. The general form — an infinite stream built by guarded
corecursion and gated by *productivity* (the coinductive dual of termination) — is
still in design; see [docs/stream-war.md](../stream-war.md).

---

**Full design notes:** [stream-war](../stream-war.md) · [streams](../streams.md) ·
[stream-queries](../stream-queries.md) · [indexed-streams](../indexed-streams.md) ·
[iteration](../iteration.md)
