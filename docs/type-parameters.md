Free Type Parameters
===

Status: DRAFT (2026-06-13; surface syntax + §7 decisions ratified 2026-06-14) —
design proposal, not implemented. Markers: **RULED** = settled elsewhere (or this
session) and assumed here; **PROPOSED** = this doc's recommendation awaiting a
ruling; **OPEN** = undecided; **GOTCHA** = a concrete parser/compiler obstacle
with a file:line citation.

This is the §6.5 arc that `docs/associated-types.md` deferred — *free* type
parameters on functions and structs (`function map[type E](…)`,
`struct Box[type T](…)`), as opposed to the *associated* types that landed there.
It is the next home of the `type` declarator (`associated-types.md §1.1`).

# 1. The one idea

A free type parameter needs **no new runtime mechanism.** That is the whole
design, and it is a direct consequence of Pontif's no-erase commitment
(`user_cott_conservation`).

The reflex from languages that *erase* types (Haskell dictionaries, Rust
monomorphisation, a Java type-witness) is that a generic must *carry* its type
parameter at runtime, because the type was thrown away and has to be
reconstructed. Pontif never throws it away: **every value already carries its
concrete type** (the `typeName` on its `RecordValue` — the same tag the
associated-type existential path reads, `IrInterpreter` ~`:570`). So the witness
is not missing; it is *distributed across the values themselves.*

**You only ever need two things, and you already have both:**

1. **The concrete type of an instance** — present on every value at runtime
   (`typeName`), already consulted by dispatch.
2. **The static type of the variable you are viewing it through** — the *lens* —
   a compile-time fact the checker already threads (the `typeEnv` in
   `InferenceContext`, the declared param/return sorts in `SortChecker`).

From these two, every other type fact a type parameter could need is **derived**,
not stored. (And if a derivation is hot, it can be *cached* — a memo on the
lens-vs-concrete match, a perf knob, never a semantic requirement.)

## 1.1 Why two things suffice (the derivation)

Take the hardest case, the motivating one:

```pontif
function map[type E, type R](xs:[Stream[E]], f:[Dispatch(E):R]):[Stream[R]]
```

Every place one would reach for a reified `E`:

- **Dispatching `f` on an element** — dispatch reads the element's *concrete*
  type (#1), never an abstract `E`. The instance is in hand.
- **Constructing an `E`** — you cannot fabricate a value of an abstract type, so
  the only `E`-values that exist *came from somewhere* (an argument, an element),
  each carrying its own concrete type. Nothing to materialise.
- **The result `[Stream[R]]`** — a compile-time computation over the lens (#2);
  the actual result elements are concrete instances.
- **Type equality on two `E`s** — compare their concrete `typeName` tags.

At no point is there a free-floating `E` detached from an actual instance. **The
instance is its own witness.**

## 1.2 The invariant (the dual of associated types)

The property that makes #1 + #2 airtight:

> **Every runtime occurrence of a type parameter is attached to an actual
> instance** — because you cannot conjure values of an abstract type.

This is the dual of the associated-types invariant ("`T` is a function of the
concrete type," `associated-types.md §3.4`). There, the instance is the
*receiver* and the lens is the *trait*. Here, the instances are the *arguments*
and the lens is the *declared parameter sorts*. **It is one rule:**

> *A type variable is recovered from the concrete type of an actual instance,
> viewed through a static lens.*

So free type parameters and associated types are **the same machinery** — the
`substituteTypeVars` derivation (`SortChecker` ~`:1126`), run at call sites
instead of impl bindings. The difference is direction: an impl *substitutes* a
known binding into the contract; a call *unifies* the lens against the
arguments' concrete types to *extract* the binding (§3.1). That extraction —
matching a sort-with-variables against a concrete sort — is the one genuinely new
piece of logic, and it is small.

## 1.3 This overturns `associated-types.md §3.4`

That section called free parameters "a genuinely larger mechanism
(type-passing / monomorphisation) and a separate project." **That assumption was
wrong** — it imported the erase-then-reconstruct model. Given no-erase, there is
nothing to pass and nothing to monomorphise. `associated-types.md §3.4` and §6.5
should be corrected to point here. (The *runtime-witness* framing is retired; the
*"recovered from the concrete type"* framing is exactly right and generalises.)

# 2. Surface syntax

## 2.1 Declaring — `[type T]` after the name (RULED 2026-06-14)

A function or struct introduces its type parameters in a **bracket slot directly
after the name**, ahead of the value params / fields. **No colon** — the colon is
the *has-sort / is-a* operator (`Sub:[Base]` *refines* a type), and a type
parameter is a *binder*, not a refinement of the function/struct. `func:[type T]`
would wrongly claim "func has-sort `type T`"; the binder takes no colon.

```pontif
function map[type E, type R](xs:[Stream[E]], f:[Dispatch(E):R]):[Stream[R]]
function id[type E](x:E):E
struct   Box[type T](value:T)
struct   Pair[type A, type B](first:A, second:B)
struct   Sub[type T]:[Base:rel](value:T)    # [type T] binds, :[Base] refines, (…) fields
trait Expr[type T, type R]{ process:[Method(T):R], … }   # a trait, too
```

The slot is a **comma-separated list**, so multiple parameters are just
`[type T, type R]`. The punctuation cleanly separates the three roles, identically
across functions, structs, and traits: **`[type …]` binds** a type parameter,
**`:[…]` refines / is-a**, and **`(…)`** is value params / fields. A **bounded**
parameter is `[type E:R]` (read "`E` is some type satisfying `R`") — the
type-level refinement, reusing the slice-4 bound machinery. Type parameters come
**first** so they are in scope for the is-a, the fields, and the return.

**Trait type *parameter* vs *associated* type — two mechanisms, told apart by
position.** A trait can take a type *parameter* in the slot (`Expr[type T]`) OR
declare an *associated* type as a member (`Expr:Type{ type T, … }`, landed in
`associated-types.md`). The difference is **who chooses the type**: a parameter is
chosen *from outside* (the user writes `Expr[Int, Bool]`; many instantiations
coexist — an *input*), whereas an associated type is fixed *by the implementor*
(one choice per impl — an *output*). The position disambiguates with no
kind-guessing: **slot before `:Type{}`** = parameter, **member inside `{}`** =
associated. Both are the same `type` declarator; reach for a parameter when the
caller picks, an associated type when the implementor determines it.

**Impls carry their own `[type T]` binder (RULED 2026-06-14).** When a parametric
struct satisfies a parametric trait, the impl restates the slot after the subject
name, and the bound variable is forwarded into the trait's bracket:

```pontif
struct Element[type T](head:T, rest:[Element[T]|Leaf])
trait Stream[type E]{ head:[Method():E] }

assign trait Element[type T]:Stream[T] {   # [type T] BINDS T; [T] forwards it
    head():T -> this.head                   # `this` is Element[T]; method sigs use T
}
assign trait IntList:Stream[Int] {          # no binder → Int is a concrete arg
    head():Int -> this.head
}
```

The binder is **load-bearing, not ceremony**: it is the toggle that tells a
*forwarded variable* `Stream[T]` apart from a *concrete* `Stream[SomeType]`.
Without it, `T` in `Stream[T]` is read as a concrete type name (and rejected as
unknown if none exists) — there is no lookup into the subject's parameters to
guess from. This keeps the "a name is a variable only where a `[type T]` slot put
it in scope" rule universal: an impl is its own scope, so it introduces its own
variable rather than being the one exception that inherits silently. The binder is
**positional** against the subject's declared arity (like a lambda param — the
impl picks the local name; an arity mismatch is an error), so there is no
must-match-the-struct's-spelling rule. Mechanically the impl scopes its `[type T]`
over the trait args and method sigs, then zips the trait's declared `[type E]`
against the supplied args (`E↦T` or `E↦Int`) and substitutes into the contract
before matching.

**Parametric is-a base (`struct IntLit:[Literal[Int]](…)`) — RULED 2026-06-14.**
The same parametric-application sort also lands in a struct's `:[…]` is-a slot,
where the base is a parametric *struct* (the extension/subtype regime, not trait
satisfaction). Two surfaces, both supported: bare (`:[Literal[Int]]`) and with a
demotion morphism (`:[Literal[Int]:@.value==value]`). Here the type argument is
**invariant**: substituting it into the base struct's fields (`value:T` ⟹
`value:Int`), the child's field providing each base field must be **exactly** that
sort — a refinement of it (`[Int:@>0]`) or a different base (`Bool`) is a
falsehood and is rejected. (Contrast §3.4's *covariant* subtyping between two
parametric instances — that is assignability `Stream[Lit] ⊆ Stream[Expr]`; this is
a struct *asserting its own identity*, where the carried argument must be honest,
hence exact.) This enforcement is NEW relative to non-parametric extension (which
only name-pins base fields, never checks their sorts) and runs only when the base
carries type arguments, so non-parametric extension is unchanged.
`IrSort.Refined` gains a `typeArgs` list (back-compat, like `Named`) to carry the
base's arguments through to the check.

## 2.2 Inferred at the call; explicit when you must widen (RULED 2026-06-14)

The brackets bind at the declaration and **apply at the call** — exactly as
`(params)` / `(args)` do, one level up in the type world. Usually the arguments
determine the type parameters, so the brackets are omitted:

```pontif
map(xs, f)            # E, R inferred from xs and f
Box(5)                # T inferred to Int from the field value
```

But inference cannot *widen* a concrete argument to a common supertype on its
own, so an **explicit** form is sometimes needed — the motivating case being a
concrete value passed through as a shared trait:

```pontif
function makePair[type E](a:E, b:E):[Pair[E]]
let x:Lit = Lit(1)
let y:Add = Add(2, 3)
makePair(x, y)        # inferred E=Lit from a vs E=Add from b → DISAGREE → error
makePair[Expr](x, y)  # you SAY E=Expr; both widen → Pair[Expr]
```

`name[Arg](…)` is free for this: metareferences are `$`-prefixed (`$inc[Int]`,
`bracket-paren-law`), so a bare `name[Arg]` does not collide with them. (The old
G5 worry predated the `$` re-cut.)

## 2.3 Spelling a parametric type — `Name[arg]` (RULED 2026-06-14)

A parametric type is *applied* with **brackets** — `Stream[Int]`, `Box[Lit]`,
`Element[T]` — the same `[…]` that binds the parameter, now supplying it. It
appears recursively inside a parametric struct and at any use site (wrapped in an
outer `[…]` when the whole thing is the sort):

```pontif
struct Element[type T](head:T, rest:[Element[T]|Leaf])   # recursive application
let xs:[Element[Int]] = Element(1, Element(2, Leaf))     # at a use site
```

Using **brackets** (not parens) is what keeps this clear of the existing
`[Name(…)]` *refinement* syntax: `[Ternion(z, 0, w)]` (positional field
constraints, `parseStructFields`, `PontifSexprParser.java` ~`:2260`) uses **parens** for
value-shaped constraints, while `Element[Int]` uses **brackets** for the type
argument. Different brackets, different jobs — the G7 collision the parens form
would have caused simply does not arise.

This is **not** an inconsistency with `Method(…)` / `Dispatch(…)`, which keep
**parens** — and the reason is the bracket/paren law working, not an exception to
it. Those are **callable** types: their parens hold the *value* arguments you call
them with (value-level application, `()`). `Stream[Int]` is a **data** type: its
brackets hold a *type* argument (type-level application, `[]`). Parens = the values
you call with; brackets = the types you parameterise by. The surfaces differ
because the *things* differ (callable vs data), so `Dispatch(Int):Int` and
`Stream[Int]` are each correct.

The two compose: a callable type can itself carry a `[type T]` slot —
`Method[type T](…):…` — `[type T]` binds the method's own type parameter, `(…)`
its value args, `:…` its return. (Scoping `[type T]` to a Method, and the
`IrSort.Method` representation it needs, is future scope — §7.)

## 2.4 Type arguments are optional — spelling them *destructures* (RULED 2026-06-14)

A type reference need only spell as many parameters as you actually use. Writing
`Name[A, B]` with **fresh names** is an opt-in **destructuring**: it binds those
names to the reference's type arguments so you can use them elsewhere in the
signature — the type-level dual of value destructuring (`Point(x, y)` binds `x`,
`y`), the same in-place deconstruction `requires` does (`requires-unification`).

```pontif
function something(x:Expr[T, R], t:T):R    # [T,R] destructures x's params → bind T,R; t:T, returns R
function something(x:Expr)                  # don't need them → omit; x is just an Expr
```

Same surface, two readings by whether the names are already bound — exactly like
values: `Expr[Int, Bool]` *applies* concrete args (bound); `Expr[T, R]`
*destructures* into fresh ones (free).

This is **typo-safe** and does NOT reopen the implicit-parameter hole (§2.1's
no-guessing): the fresh names bind **positionally inside a known parametric type's
brackets** — anchored to the type's declared arity — not invented free-floating. A
wrong name surfaces as an unbound reference where you use it.

So there are two ways a type name enters a signature, and they coexist:
- **Declared in the slot** — `f[type E](…)`: `E` is *f's own* parameter, chosen by
  the caller (or inferred); the input knob, needed to widen (`makePair[Expr]`).
- **Destructured inline** — `f(x:Expr[T, R], …)`: `T, R` are *projected out of an
  argument's* type, determined by what you pass — like reading fields.

Bare (`x:Expr`) omits both. This also resolves the self-reference question (§2.1):
a bare `Expr` in a trait body means "the trait, params unmentioned"; write
`Expr[T, R]` only where you need to name them. And it lets a combinator skip the
slot when the type comes from data — `first(xs:Stream[E]):E` destructures `E` from
`xs`; no `[type E]` slot needed.

# 3. Semantics

## 3.1 Call-site derivation by unification (PROPOSED — the new logic)

Checking a call `map(xs, f)` derives the type-parameter bindings by **matching
each declared value-param sort (the lens, which mentions the parameters) against
its argument's inferred concrete sort**:

```
param  xs:[Stream[E]]      arg concrete [Stream[Int]]          ⟹  E ↦ Int
param  f:[Dispatch(E):R]   arg concrete [Dispatch(Int):Bool]   ⟹  E ↦ Int (agrees), R ↦ Bool
```

Then the bindings substitute into the return sort: `[Stream[R]]` ⟹
`[Stream[Bool]]`. Disagreement across occurrences (`E ↦ Int` from `xs` but
`E ↦ Bool` from `f`) is a type error at the call — *unless* the caller widens
explicitly with `map[…](…)` (§2.2), which fixes the parameters up front and skips
the disagreement.

This is **`substituteTypeVars` inverted** — a one-directional *match* that binds
variables, rather than a substitution of known bindings. Its nearest existing
neighbour is `StaticDispatch`, which already matches argument narrowings against
parameter sorts for overload selection (`InferenceContext` / `StaticDispatch`);
the new step is *extracting the variable bindings* from that match.

## 3.2 Bounds make an unknown `E` usable (RULED, reused)

`[type E:R]` constrains `E` and lets the body use an `E`-typed value through
`R`'s interface — identical to the associated-type bound
(`associated-types.md §3.2`, §4, landed). A bounded `E` is usable; an unbounded
`E` is opaque (passable, comparable by concrete type, but not called into). The
existential-boundary machinery already shipped (`AssociatedTypeBoundTest`,
`AssociatedTypeExistentialTest`) is the same here.

## 3.3 Structs: the field recovers the parameter (PROPOSED)

`struct Box[type T](value:T)` — `T` is recovered at runtime from `value`'s
concrete type, exactly as an associated type rides the receiver's `typeName`. At
construction `Box(5)`, `T ↦ Int` derives from the field-value argument (§3.1).
**No separate storage:** the value already carries `value=5:Int`, so `T` is a
projection of the field, recovered the same way associated `T` is recovered "for
free" (`associated-types.md §3.4 / G7`).

The plain way to say it: **the type is tracked because it's on the struct, not by
magic.** The field *is* the witness. There is no dictionary, no monomorphised
copy, no reflective type token — `Box(5)` is a `RecordValue` whose `value` field
holds an `Int`, and that is all the runtime ever needs to know `T = Int`.

**This is also why a struct parameter — not an associated type — is the carrier**
(the point that decides the Streams minimum, §6.1). An associated type binds
**per impl**, and coherence permits one `assign trait Queue:Stream`, so it would
fix the element type across *all* `Queue`s. But an element type is **per value**:
a `Queue` of `Int` and a `Queue` of `Bool` are the same implementor with
different contents. Only a per-value carrier — a `[type T]` field parameter on the
struct — can vary with the value, which is exactly what "the type is on the
struct" buys.

## 3.4 Subtyping is covariant, by immutability (RULED 2026-06-14)

A `Stream[Lit]` is usable where a `Stream[Expr]` is expected (given `Lit` is-a
`Expr`). This is **sound by default** because Pontif values are immutable: the
only thing that makes covariance unsafe is a *write* (aliasing a `Stream[Lit]` as
`Stream[Expr]` and inserting an `Add` through the wider view), and there is no
write — "appending" produces a *new* stream, never mutating the original. With no
write position anywhere in the immutable substrate, covariance holds for every
struct, not just `Stream`.

So: **covariant by default, no per-type variance annotations.** Mechanically, the
sort-conformance check (the subsumption `StaticDispatch` / `SortChecker` already
use to decide `Lit ⊆ Expr` via trait satisfaction) **recurses into the
type-argument position** for two sorts sharing a head — `Stream[Lit] ⊆
Stream[Expr]` because `Lit ⊆ Expr`, same direction, recursively (nested
`Stream[Stream[Lit]]` falls out too). The lone future exception is the **mutable
native Array** (`streams.md` slice 4) — real in-place storage — which is already
fenced behind linearity / actions and is the one place that must opt out.

## 3.5 The boundary case, and why it is not a gap (PROPOSED)

A parameter that appears *only* in an uninhabited position has no instance to
recover from:

- `function default[type E]():E` — `E` only in the return, no `E`-argument. But
  you cannot write the body (you cannot construct an `E` from nothing), so the
  function is uninhabited — there is no runtime behaviour to resolve.
- `struct Empty[type T]()` — `T` in no field. A phantom; `T` has no runtime
  occurrence, so the lens (compile-time) carries it and nothing needs runtime
  recovery.

The cases the two things cannot resolve are exactly the cases with no runtime
behaviour to resolve. So they are a non-gap — but the checker should *recognise* a
parameter as phantom/uninhabited rather than silently misbehave (G4).

# 4. Technical problems / gotchas

## G1 — parsing the `[type E]` bracket slot
A function name is followed directly by `(` today, parsed by `parseParamList`
(`PontifSexprParser.java:818`, from `parseFunction` `:616`); a struct similarly. **Fix:**
after the name, if a `[` follows, parse a `[type IDENT (: Bound)?, …]` slot into a
*separate* type-parameter map before the value `(…)`. This is a new slot, parallel
to (and composable with) the struct's `:[Base]` is-a slot; it does **not** live
inside `parseParamList`. The `type` keyword inside the slot mirrors how
`parseAssignTrait` (`:1554`) / `parseTraitTypeLiteral` already split `type X`
members from value members.

## G2 — representation: functions and structs must carry their type-param names
`IrStmt.FunctionDecl` (`IrStmt.java:66`) and `IrSort.Structural` need a
`Map<String,IrSort> typeParams` (name → bound; bound absent/`null` = "any type"),
exactly the shape `IrSort.Trait.associatedTypes` already carries
(`IrSort.java`). Carries bounds from day one even if checking them is phased.

## G3 — type-param names look like unknown sorts
`E` in `xs:[Stream[E]]` parses to `IrSort.Named("E")`; `validateSortNames`
(`SortChecker.java` ~`:552`) will reject it unless it knows `E` is this
function's type parameter. **Fix:** thread the function's (or struct's)
type-param names as the in-scope `typeVars` set while validating its param /
return / field sorts — the *same* mechanism that already scopes a trait's
associated types and `this.type` (`SortChecker` Trait case, ~`:613`).

## G4 — extraction (unify) does not exist yet; only substitution does
`substituteTypeVars` (`SortChecker.java` ~`:1126`) substitutes *known* bindings
into a sort. Call-site derivation (§3.1) needs the *inverse*: match a
sort-with-variables (lens) against a concrete sort and *return the bindings*
(`{E↦Int, R↦Bool}`), failing on disagreement. This is the one new function —
structural, small, and a sibling of `substituteTypeVars`. It must also flag a
parameter that never gets bound (phantom/uninhabited, §3.5).

## G5 — explicit type args at the call (RESOLVED)
Explicit type args ARE supported — `name[Arg](…)` (§2.2), needed to widen a
concrete argument to a common trait. No collision with metareferences: those are
`$`-prefixed (`$inc[Int]`), so bare `name[Arg]` is free. This G5 originally feared
a clash that the `$` re-cut had already removed.

## G6 — interaction with multi-dispatch coherence
A type-parametric free function is still subject to the module-coherence /
orphan rule (`namespace_hygiene_nonnegotiable`). Deriving `E` from arguments does
not change *which* overload is chosen — `StaticDispatch` still resolves on the
arguments' concrete sorts; the type parameter is read off the *same* match. Worth
a test that a parametric function and an overload set coexist coherently.

## G7 — parametric application vs refinement (RESOLVED by using brackets)
Type application uses **brackets** — `Element[Int]` — so it never collides with
the **paren**-based field-constraint refinement `[Ternion(z, 0, w)]` (§2.3). The
earlier parens form `Element(Int)` would have collided; switching application to
`[…]` dissolves it, and is more bracket-law-honest (types live in `[]`).

# 5. IR / representation changes (summary)

- `IrStmt.FunctionDecl`: add `Map<String,IrSort> typeParams` (G2).
- `IrSort.Structural`: add `Map<String,IrSort> typeParams` (G2).
- `PontifParser`: parse the `[type E]` / `[type E:R]` bracket slot after a
  function/struct name (G1); parse `Name[Arg]` type application in `parseSort`
  (brackets — no collision with the paren refinement form, G7); accept
  `name[Arg](…)` explicit type application at calls (free of `$`-metareferences,
  G5).
- `SortChecker`: scope type-param names while validating sorts (G3); a new
  *unify/extract* pass deriving bindings at call sites (G4); substitute the
  derived bindings into the result sort; recurse covariantly into type-arg
  position for subsumption (§3.4); check bounds (reuse slice-4).
- Runtime: **nothing** (§3.4) — the value carries its concrete type; dispatch
  already reads it.

# 6. Slice plan (PROPOSED)

Scope: **the whole arc** (James, 2026-06-14 — "tackle the whole thing while it's
fresh"), executed as ordered vertical slices, each committed, but committed to
*finishing* the sequence. Re-sequenced to be **Streams-driven**: the first real
consumer needs the *struct* parameter (the per-value carrier, §3.3), so structs
lead — the reverse of the "functions are simpler" instinct. Functions (the
combinators) follow.

1. **`[type T]` on structs — the minimum typed Stream** (G1 + G2 + G3 + G7 + §3.3).
   The whole of "Streams working" that's been parked behind `head:_`:
   ```pontif
   struct Element[type T](head:T, rest:[Element[T]|Leaf])
   let xs:[Element[Int]] = Element(1, Element(2, Leaf))   # T=Int from head
   ```
   Parse the `[type T]` slot on a struct (G1); represent it (G2,
   `Structural.typeParams`); scope `T` in the field sorts (G3); parse the
   `Name[Arg]` sort-application form (G7); **derive `T` from the `head` field at
   construction** (§3.3 — no storage, the field is the witness); destructure with
   the element typed. Use the *concrete carrier* sort `[Element[Int]]` /
   `[Queue[Int]]` — no parametric trait sort yet. Pin: construct a typed Queue,
   pull `head` as `Int`, reject a mismatched element. **This is the minimum; it
   unparks `head:_`.** No function type parameters, no combinators.
2. **`[type E]` on functions + inline destructuring** (G2 + G3 + G4 + §2.4).
   Parse the function type-parameter slot AND inline destructuring of a known
   parametric type (`f(xs:Stream[E])` binds `E` from `xs`); at the call derive
   `E ↦ concrete` by *unifying* the declared/destructured sorts against the
   argument concrete sorts (§3.1, the one new piece), substituting into the
   return; support explicit `name[Arg](…)` widening (§2.2). Smallest win
   `id[type E](x:E):E`; the real target is `map`/`fold` (`streams.md` slice 2).
   Bounds phase in next (`[type E:R]`, reusing slice-4).
3. **Parametric traits — `Expr[type T]` + `[Stream[T]]` sort + literal desugar**
   (`streams.md` slice 2). Type parameters on a trait (CONFIRMED needed, not
   hypothetical: it is how the trait-applied sort `[Stream[Int]]` is spelled, and
   the cleaner Stream story — `struct Element[type T]` implements `Stream[T]`, the
   struct parameter flowing into the trait's). Coexists with associated types,
   distinguished by position (§2.1).
   - **3a (LANDED)** — the `[type T]` trait declaration slot + member scoping.
   - **3b (LANDED)** — the trait-application sort `[Stream[Int]]` (AliasResolver
     inlines a parametric trait reference, substituting `E↦Int` with an arity
     check; structs stay nominal) + parametric impls (`assign trait
     Element[type T]:Stream[T]`, the impl's `[type T]` binder forwarded into the
     trait; §2.1). The struct-is-a-parametric-base form (`IntLit:[Literal[Int]]`)
     reuses the same sort but in the `:[…]` slot — a later consumer.
   - **3c (TODO)** — the literal desugar (`(1,2,3) : [Stream[Int]]`).
4. **`let P:Type[…]` → `type P = […]`** — retire the value-shaped sort-alias form
   (`PontifSexprParser.java:1066`) in favour of the declarator. Pure consolidation, lowest
   priority; do last so the migration is mechanical.

# 7. Decisions (resolved 2026-06-14) + future scope

1. **Explicit type arguments — RESOLVED: yes, `name[Arg](…)`.** Inference covers
   the common case (brackets omitted), but a concrete value passed through as a
   shared trait can't be widened by inference, so the explicit form is real (§2.2).
   Free of the `$`-prefixed metareferences (G5).
2. **Declaration surface — RESOLVED: `[type T]` bracket slot after the name**, for
   both functions and structs, no colon (the colon is is-a; a binder isn't),
   type-params-first so they scope the rest (§2.1). Application is `Name[Arg]`
   brackets (§2.3).
3. **Variance — RESOLVED: covariant by default, no annotations**, sound because
   the value substrate is immutable; the mutable native Array is the one fenced
   exception (§3.4).
4. **Higher-kinded parameters** (`[type F[_]]`) — explicitly **OUT of scope**; this
   doc is first-order type parameters only.
5. **Naming** — "free type parameters" / "type parameters" (file
   `type-parameters.md`); descriptive over "generics" (`pontif_naming`). Not a
   blocking decision.

**Future scope (James, 2026-06-14 — NOT this arc).** Inline *use* of a parametric
type's parameters now IS in this arc — that is the destructuring of §2.4
(`f(x:Expr[T,R])` binds `T, R`). What remains deferred: (a) **refinements on type
parameters** — constraining a `type T` beyond a trait bound; and (b) **`[type T]`
scoped to a callable type** — `copy:[Method[type T]():T]`,
`parse:[Method[type T](String):T]` — the method carrying its own type parameter (a
`typeParams` slot on `IrSort.Method`), governed by the same inhabitation rule
(§1.2 / §3.5): the method-level `T` must be pinned by an instance — a value arg,
the receiver, or the expected-return lens — so a return-only `Method[type T]():T`
overlaps `this.type` when the receiver supplies `T`, and earns its keep when `T`
comes from a value argument or the caller's choice. Plus higher-kinded (§7.4).
Both extend the committed surface without contradicting it.
