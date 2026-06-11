

As of 2026-06-10
This document represents the gold standard of what direction the project should be going in.
This date should be updated every time this file changes.

Motivation behind this syntax choice:
We're working towards max unification of all design principles.

---
STATUS (2026-06-10): The CORE is IMPLEMENTED — struct extension, demotion/
promotion, function & value synthesis, the in-type pipeline, and `^` — all
landed and committed (per-slice detail in `docs/univocal-implementation-plan.md`).
`assign proof` is now BUILT: the `assign proof f(params):[ (match s …) -> [Sort] ]`
case-function surface, auto-peel of a finite residual (so `[_]` subsumes an
explicit `Singletons`), and proof dispatch ("Prove specific branches" — several
proofs per function, each granting its region's return). The refinement lives on
the proof; the function declares a base return. STILL DRAFT / NOT yet built:
"Reusable Sort" (`Type[…]`, `@{…}`), the `!!` runtime-hazard marker, the general
`{}`→`()` "Case Functions" migration (the proof body uses `()` already), and
call-site narrowing of a proof's granted return (a caller still sees the base).
`assign trait` is built; the general/bare `assign` is not. Do not read the
remaining draft sections as shipped.
Deltas from this draft (core):
- `self -> this`: DONE (alt-syntax receiver). `@` is unchanged — it's the
  refinement subject, distinct from the receiver.
- The `requires @` / "outside the universe" world-boundary: DROPPED. The in-type
  pipeline's `let` stages call global functions by name, so no import was needed;
  dropping it removed the parent-scope question entirely.
- `^` power operator: DONE — no longer out of scope.
- `@.z` is the canonical field-in-pin spelling (the bare `@z` forms below are loose).
---

"Univocal" - said in one sense of many things.
A univocal type system.
Now THAT sounds cool.

Breaking change required: Refactor self -> this. "this" is the most widely used keyword for this purpose.
Python uses self but it's an explicit argument. "this" is typically an automatic keyword, like in Pontif.



```
struct Point(x:Int,y:Int)

# Inheritance with demotion rule - which is always required.
# the @.x and @.y belong to Point. x and y from the constructor arguments
# Demotion provably loses information because of z, but that's fine
struct Point3D:[Point:@.x==x & @.y==y](x:Int,y:Int,z:Int)
# This variation is also valid and uses positional arguments instead.
# this.x and this.y could have been used in the return type also.
# But x and y can be used instead beacuse they are in the arguments list, and so are in scope.
# The return type may access variables from the arguments, always.
struct Point3D:[Point(x, y)](x:Int,y:Int,z:Int)

# b is a Point with the x and y from a, missing the z.
# z is still recoverable from a but not from b.
let a = Point3D(2,3,5)
let b:Point = a
# let c:Point3D = b # Compile error, can't synthesize data.

# Promotion comes separately.
# It can be a method or a dispatch function.
# Note the destructuring syntax on point referenced in the return type.
# Pontif types are context-aware.
function promote(point:[Point.{x,y}], z:Int):Point3D{x, y, z};
# The semicolon without a function body triggers function synthesis.

# Now it's Point3D(2,3,7)
let promoted:Point3D = promote(b, 7)

# "@" means the current token being described, "this" means the current statement's subject (Point)
# On methods, "this" always refers to the instance of the owning type.
# "this" is necessary here because there's no argument for the instance.
# This method is also able to synthesize because it's uniquely constrained.
method Point.promote(z:Int):[Point3D{this.x, this.y, z}];

# Using a method enables type inference
let promoted = b.promote(11)

# Or, even simpler, using value synthesis:
let x:[Point3D:@.z==0] = b;

# No changes to proofs identified yet.
```


Value Synthesis:

```
# semicolon is now the official function and value synthesis directive.
# Should compile-error if synthesis cannot be determined.
let zero:[Decimal:0];

# From the example above:
# let c:Point3D = b; # This would also compile error.
# let x:[Point3D:@.z==0] = b # This would also compile error, value synthesis required.
# This one works. It defines z and triggers synthesis.
let x:[Point3D:@.z==0] = b;
```


Computation:
```
# At some point you'll need to call functions within functions
# Might be tempted to do something like this:
# function magnitude(p:[Point.{x,y}]):[Decimal:@==sqrt(...)];
# Except, you can't use external references within the type system like that.
# You could include them as metareferences
# function magnitude(p:[Point.{x,y}], sqrt:[Function(Decimal):Decimal]):[...];
# Or just write a normal function, like the peasentry
function magnitude(p:[Point.{x,y}]):Decimal -> sqrt(x^2 + y^2)

# Or the mildly more sophisticated "monadic" method: the `let` stages compute,
# the final pin returns. Global functions (sqrt) resolve by name — no import —
# so the `requires @` stage was DROPPED (see STATUS).
function magnitude(p:[Point.{x,y}]):[
    let m:Decimal = sqrt(x^2 + y^2) ->
    Decimal:@==m
];
# Gotta really stretch the pinky out on that one.

```

Requires from outside the universe:
DROPPED (see STATUS) — the in-type pipeline needs no import; global functions
resolve by name. The `$fqn` import simplification can return if a non-global
ever needs naming.

Out Of Scope:

a^b should be the power operator, as Int^Int->Int or if either is a Decimal, Decimal^Decimal->Decimal.
DONE (no longer OOS): `^` landed — Int^Int and Decimal^Int (Decimal-promoted),
binding tighter than `*`. Decimal^non-integer (transcendental) stays out of scope.

Reusable Sort
```
let AnyNumberNotZero:Type[[Int:@!=0]|[Decimal:@!=0]];
let x:AnyNumberNotZero = 1 # 1 and 0.5 pass; 0 and 0.0 would fail.

# The following is existing working behavior.
struct Point(x:Int, y:Int)
let Sized:Type{
  magnitude:[Method():Int]
}

# `assign` is the mutation keyword; attaching a trait is one species of it,
# spelled `assign trait`. Other `assign <kind>` forms may follow.
assign trait Point:Sized {
  magnitude():Int -> this.x * this.x + this.y * this.y
}

# Trait + data condition. NB: `@.coef != 0` projects coef — whether that
# IMPOSES the field (open-trait reading) or requires it pre-declared in the
# `@{…}` part is the still-open closed-vs-open question; assumed open here.
let SizedNotZero:Type[ @{magnitude:[Method():Int]} & @.coef != 0 ];

struct Something(coef:[Int:@!=0]) # Assignment would fail without this. (Int so magnitude returns Int.)

# This satisfies all the demands of the trait.
# Should compile
assign trait Something:SizedNotZero {
  magnitude():Int -> this.coef*this.coef
}

```

Unproven
```
# Scenario: return refinement can't be proven natively.
# So the return sort is prefixed with !! as so:
function trueButUnproven(x:Int):[!!Int:@ >= -16] -> (x-3)*(x+5)

# But while it's like this, it's necessary to match out the error:
let value = (match trueButUnproven(...)
  [!!] -> errorRecovery()
  [x] -> x
)
  
# Or, define the function without the return sort:
function proven(x:Int):[Int] -> (x-3)*(x+5)

# To add a return type refinement, assign a proof to the function.
# An assigned proof must verify, else the compiler throws.
# The whole point of the proof is to hint the compiler without using opaque Branch/Leaf structure.
assign proof proven(x:Int):[
  # Hint the issuer for piecewise discharge. The CUT is the point, not the result:
  # each arm just re-invokes the body, so the arms are redundant by design — they
  # only carve the domain into regions the engine can close. First-match ordering
  # reproduces the real nested Split (ProofAuthoringTest.isSparse):
  #   Split(x>=3, Leaf(), Split(x<=-6, Leaf(), Singletons(x, -5, 2)))
  (match x # Doesn't need "lets" - the value chains 
    [@>=3]  -> this(x) # region A: both factors >=0, interval mult gives @>=-16. "this" = the target function
    [@<=-6] -> this(x) # region C: both factors <=0, interval mult gives @>=-16
    [_]     -> this(x) # region B: the remainder is exactly [-5,2], peeled to singletons (min -16 at x=-1)
  ) -> # In theory, same arrow syntax could pair any two semantics, here it's used for explicit coersion
  [Int:@ >= -16] # This refinement is now globally implicit for the function, if it verifies.
]



```


Prove specific branches:
Proofs dispatch on the parameter's refinement: each `assign proof` covers a
disjoint region of `d`, and the proof partition mirrors the body's own `match d`.
The return refinement is per-region (`[Int:d]` vs `@>=-16`) — they can't be
unified because `d<0` is unbounded below, which is what forces the dispatch.
This is the dispatch regime (disjoint, exhaustive) selecting a proof whose body
is the match regime (ordered arms over `x`).
```
function proveBranch(d:Int, x:Int):[Int] -> (
  match d
    [@>=0] -> (x-3)*(x+5)
    [_] -> d
)

assign proof proveBranch(d:[Int:@<0], x:Int):[Int:d]

assign proof proveBranch(d:[Int:@>=0], x:Int):[
  (match x
    [@>=3]  -> this(d, x)
    [@<=-6] -> this(d, x)
    [_]     -> this(d, x)
  ) ->
  [Int:@ >= -16]
]
```

Simpler example:
```
function quirk(x:Int):Int -> x * (x - 1)

attach proof quirk(x:Int):[
  (match x
    [@>=1] -> this(x)
    [_] -> this(x)
  ) ->
  [Int:@>=0]
]

let q:[Int:@>=0] = quirk(2)
```

Case Functions
DRAFT — far-reaching, its own slice. The parser uses `{}` for match today; this
is the direction, not shipped.
REFRAMED (see "The Operator Algebra" below and docs/univocal-arrows.md): this
isn't "functions with cases." A case-function is the `@:(…)` compute cell — a
decision tree written with the universal arrow `->`. "Case function" is retired
as a name; the construct is match-the-arrow.

One construct underlies match, map/iteration, and lambda: a *case-function* — an
ordered set of `[pattern] -> expr` arms, written in `()`.

```
# A case-function is a value: ordered arms, first match wins.
let classify = (
  [n:[Int:@<0]] -> "neg"
  [0]           -> "zero"
  [n]           -> "pos"
)

# How would the case function be invoked?
let result = classify(2) # "pos"

# How would the case function chain with another case function?
let result2:[Int:1|0|-1] = (match classify(n)
  "pos" -> 1 # We may need a whole feature exporing strange match keys and what they might mean.
  "neg" -> -1
  [_] -> 0
)

# `match` applies a case-function to ONE value.
let label = (match score
  [@>=90] -> "A"
  [_]     -> "B"
)

# `map` and friends apply it to EACH element.
let mapped = collection.map(
  [a:TypeA] -> handleA(a)
  [b:TypeB] -> handleB(b)
  [x]       -> x
)

# A "lambda" is just the one-arm case.
let inc = ( [n] -> n + 1 )
```

Why `()` and `->`, not `{}` and `:` — a case-function COMPUTES (it picks an arm
and runs it); it is code, not stored data. `{}` is the data aggregate (records,
dicts); `()` is value/computation. `->` is "bind and produce", not a `key:value`
map. The brackets follow from what the thing *is*.

Two invariants this must NOT erase:
- A case-function is MATCH-semantics: arms are ORDERED, first match wins, overlap
  is allowed (the trailing `[_]`/`[x]` catch-all depends on it). This is NOT
  dispatch (unordered, overlap forbidden). They share arm syntax, not resolution.
- It does not revive Lambda as a separate primitive — it SUBSUMES it. A lambda is
  the one-arm case-function; there is no standalone Lambda.

Scope: touches match parsing (`{}` today), every match in code/tests/docs, and
the map/lambda/iterator surface together. One deliberate slice, decided as "the
case-function is the unit", with the brackets falling out — not a find-and-replace.


The Operator Algebra

`@` is the subject — the thing being described. Combined with the three bracket
kinds and a prefix that selects instance-vs-type, it yields the whole surface as
one algebra. The BRACKET picks the semantic domain (the bracket/paren law: `{}`
names/aggregate, `[]` types/refine, `()` values/compute); the PREFIX picks the
level — `.` reaches into an instance's structure, `:` ascribes to an instance,
bare operates on the type.

```
                            on an instance      on the type
  name (access/construct)        @.{}               @{}
  refine / restrict              @:[]               @[]
  call / compute                 @:(…)              @()
```

Most cells are already the language, retrofitted — this is a structure being
noticed, not bolted on:

- `@.{}`  — `p.{x, y}` destructure (with inline `field -> local` rename: `p.{style -> s}`); `{x=1, y=2}` construct.
- `@{}`   — `Type{ ping:[Method():Int], weight:[Int:@>0] }` — a type's members by name (traits / structural shape; methods and typed attributes together).
- `@:[]`  — `let x:[Int:@>0]`, `p:[Point.{x,y}]` — a refinement ascribed to a value.
- `@[]`   — `Type[[Int:@!=0]|[Decimal:@!=0]]` — a reusable sort alias (refinement / union at the type level).
- `@()`   — `f(x)`, `obj.method()`, a lambda — application / dispatch.
- `@:(…)` — a decision tree that runs to a value (a `(match … )`).

The arrow `->` is ORTHOGONAL to the grid and means one thing everywhere —
"produced by / bind-and-produce." It appears INSIDE cells (a `@{}` member's impl
`weight -> 1`; a `@:(…)` match arm; a `@:[]` construction pipeline's let-stages),
never as a cell of its own. So the presence of `->` never tells you which cell
you're in — the bracket does. A refinement (`[]`) is therefore free to be a
predicate (`@>0`) OR a construction (a build recipe that doubles as provenance).

Two clarifying examples — same arrow, different bracket, different domain:

```
# @:[] — a refinement that IS a construction. The `;` synthesizes the body from
# it, so the signature alone gives the value's full lineage (provenance in the
# type — the conservation ledger lifted into the sort; the seed for inverse[f]).
function groove(bpm:[Int:@>=80], fundamental:[Decimal:@>=30]):[
  let bass   = osc(fundamental/2) ->
  let mids   = osc(fundamental)   ->
  let treble = osc(fundamental*2) ->
  [MultiOsc(1, (bass, mids, treble))]
];

# @:(…) — a decision tree that RUNS to a value.
let venue = (match instrument
  [Guitar.{style}] -> (match style
    [Acoustic] -> FancyRestaurant()
    [Electric] -> MusicFestival()
    [Midi]     -> ImpossibilityException()
  )
  [Piano.{style}] -> …
)
```