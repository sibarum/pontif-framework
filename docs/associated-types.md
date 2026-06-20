Associated Types in Traits
===

Status: LANDED (2026-06-13). Slices 1–4 plus the §3.2 existential-boundary
consumption shipped; what remains is the separate free-type-parameter arc (§6.5)
and the three OPEN decisions in §7. Per-section status is called out inline
(**LANDED** / **PROPOSED** / **OPEN**); the original design markers are kept for
provenance: **RULED** = settled elsewhere and assumed here; **PROPOSED** = this
doc's recommendation (now mostly landed — see §6); **OPEN** = still undecided;
**GOTCHA** = a concrete parser/compiler obstacle with a file:line citation (G1–G9
are resolved in code unless noted).

What landed, by commit: `e1e8cda` recursive traits (slice 1, G5); `2a8287d`
the `type X` declaration (slice 2, G4); `c26254f` the impl bind + per-impl
substitution (slice 3, G2/G3/G6); `3ed2844` bound-at-bind checking (slice 4,
G9); and the existential-boundary consumption (§3.2, the second half of slice 4 —
existentialize a contract method's associated-type return to its bound in
`InferenceContext.fromModule`, so `b.get().describe()` types through `R`). Tests:
`RecursiveTraitTest`, `AssociatedType{Decl,Bind,Bound,Existential}Test`.

# 1. Motivation

A trait may declare a member that is itself a **type**, introduced with the
`type` declarator:

```pontif
trait Expr{
  type T,                       # an associated type — bound per implementor
  simplify:[Method():Expr],
  evaluate:[Method():T]         # T may appear in any position (here, the return)
}
```

`type T` is an **associated type** — each implementor binds it to a concrete type
(`type T = [Int]`), and the trait's other members refer to it. Three new
capabilities ride along: a *self-referential* trait (methods return `Expr`), an
*associated type* (`type T`), and *dependent* signatures (members mentioning `T`).

**The model has three properties** (James, 2026-06-13):

- **`T` is usable in any position** — argument, return, or both. It is just a type
  name in scope. So `evaluate:[Method():T]` (evaluate *to* a `T`) and
  `evaluate:[Method(T):Expr]` (consume a `T`) are both legal uses, not rival
  designs to choose between — the author writes whatever the operation needs.
- **`T` is remembered at runtime** — not erased. For an associated type this is
  nearly free: `T` is a *function of the concrete type*, and a value already
  carries its concrete type, so `T` is always recoverable from it (§3.4).
- **`T` is refinable / bounded** — `type T:R` constrains `T` to satisfy trait `R`
  (or descend from it). The bound is the keystone: a value whose type you don't
  *statically* know is opaque unless you know it is an `R`, in which case you may
  use it through `R`'s interface. Bounds are what make a remembered-but-unknown
  `T` *usable*.

**A type bound is a refinement lifted to the type universe.** `type T:R` is to
types what `[Int:@>0]` is to values — `R` is the predicate, `T` the subject. This
unifies bounds with the **narrowing that is already the spine of the language**:
not a bolted-on "trait bounds" feature, but the same `refine` operation, one
level up in the operator grid (`README.md`).

**Why it matters strategically.** Pontif has deliberately refused generics —
`Stream(T)` is parked as "schematic, not implemented" (`streams.md`). An
associated type carried as a **named trait member** is a more Pontif-shaped
parametricity than `<T>`. If this lands, it is plausibly how `Stream` finally
gets its element type — `Stream:Type{ type Element, … }` — and the first step of
a broader `type`/generics arc (free type *parameters* on functions and structs
come later — §3.4 marks the boundary). It composes with landed trait machinery:
DATA attributes (`Type{ weight:[Int:@>0] }`) and struct↔trait coercion
(`docs/univocal-arrows.md`). An associated type is the **type-level** sibling of
a data attribute.

## 1.1 `type` is a general declarator, not an associated-type special case

The earlier sketch wrote the member as `T:Type` — but that is byte-for-byte a
*value*-attribute declaration (`name:Sort`, like `weight:[Int:@>0]`). It only
*becomes* an associated type by the compiler special-casing the magic sort name
`Type`, and it abuses `:` — which means **narrows-to / has-sort** everywhere
else (`x:[Int:@>0]`) — to *introduce* a fresh type variable. The colon says
*constrain an existing value*; the intent is *introduce a type*. That gap is
bridged by guessing the kind from a magic word (and, at the bind site, from a
bracket). Rejected for exactly that ambiguity.

**`type X` states the kind outright** — no kind-inference, no magic `Type`-sort
recognition, no value-vs-type guess. And it is **deliberately reusable**: `type`
is Pontif's general "introduce a type-level name" declarator, and associated
types are merely its first home. The same `type X` / `type X = […]` shape is
expected to recur wherever parametricity grows — type parameters on functions
and structs (generics proper), type-valued returns, and likely as the honest
replacement for today's value-shaped `let P:Type[…]` alias form. This doc
specifies its trait use; the keyword is meant to outlive that scope.

# 2. Surface syntax

## 2.1 Declaring — `type X` (PROPOSED)

A trait introduces an associated type with the `type` declarator, sitting among
the ordinary members:

```pontif
trait Expr{
  type T,
  simplify:[Method():Expr],
  evaluate:[Method():T]
}
```

`type T` is visibly a different *kind* of member than `simplify:[…]` — which is
the truth, and removes the kind-guessing. An **unbounded** associated type is
`type T`; a **bounded** one is `type T:R` — read "`T` is some type satisfying
`R`," the type-level reading of the `refine` operator (`@:[]` on a type):

```pontif
trait Container{
  type Element:Showable,                  # Element must satisfy Showable
  first:[Method():Element],
  describe:[Method():String]              # can call Element's Showable interface
}
```

The parser change: in `parseTraitTypeLiteral` (`AltParser.java:1867`), a member
beginning with the `type` keyword is parsed as `type IDENT (: Bound)?` and
collected into a new **associated-types** map on `IrSort.Trait` (name → bound,
with the bound absent meaning "any type"; today it has only `methods` +
`attributes`; `IrSort.java:123`), rather than parsed as `name : parseSort()`. No
`Type`-as-a-sort recognition is needed (that pressure on GOTCHA G1 evaporates).

## 2.2 Referencing the associated type — any position

`T` may appear in **any** member sort position — argument, return, or both — and
in nested sorts. It is just a type name in the trait's scope:

```pontif
make:[Method(T):Expr]     # T as an argument
get :[Method():T]         # T as a return
swap:[Method(T):T]        # both
hold:[Method():[Pair(T, T)]]   # nested inside another sort
```

The method-sort parser feeds each component through the general `parseSort`
(`AltParser.java:2181`), so `T` becomes `IrSort.Named("T")` — a *reference to the
associated type member*, resolved in the trait's scope (GOTCHA G4). The
associated-types map from §2.1 is what tells the checker `T` is a (possibly
bounded) type variable, not an unknown sort; the bound `R` is what any *use* of a
`T`-typed value is checked against.

## 2.3 Binding the associated type in an impl — `type X = […]` (PROPOSED)

An implementor supplies the concrete type with the matching declarator, bound
with `=` (the binding operator — record-literal `=`, not the value-producer
`->`), to a **bracketed sort**:

```pontif
struct Lit(value:Int)

assign trait Lit:Expr {
  type T = [Int]                # bind the associated type — kind & binding explicit
  simplify():Expr -> this
  evaluate():Int  -> this.value   # Int because T = [Int] here
}
```

Every token's role is explicit: `type` = kind (a type, not a value), `=` =
binding, `[Int]` = the type (bracket-law: types live in `[]`). The compiler
makes **no** guesses — contrast the rejected `T:Type -> [Int]`, which needed two
(is `Type` the magic metatype sort? is the `->` RHS a value or a type?).

Parser shape: in `parseAssignTrait` (`AltParser.java:1553`), a member beginning
with `type` is parsed as `type IDENT = parseSort()` and recorded as a type
binding; everything else stays the existing method / attribute-producer split
(`peek(1) == COLON`, `AltParser.java:1571`). Because `type` is the discriminator,
there is no value-vs-type ambiguity on the right-hand side — GOTCHA G3
disappears entirely.

The `=` vs `->` split is meaningful and worth stating: `->` is "produced by" (a
*value* computed from `this`); `=` is "is" (a *type*, a compile-time constant
with no `this`). Using `=` keeps the type binding from reading like a runtime
producer.

# 3. Semantics

## 3.1 Per-impl substitution (PROPOSED)

Checking `assign trait Lit:Expr` substitutes the binding `T ↦ [Int]` into the
trait's member sorts, then checks Lit's impl against the substituted contract:
`evaluate:[Method():T]` becomes `[Method():Int]`, so Lit's `evaluate():Int` must
match. This is a scoped, structural substitution — analogous to `AliasResolver`
but keyed by one impl's type bindings (GOTCHA G6).

## 3.2 At the existential boundary (LANDED)

A value of *static* type `Expr` (the bare trait) has a `T` that is unknown
*statically* but **known at runtime** (§3.4) and **bounded by `R`** if the
declaration said `type T:R`. That combination — reification + bound — dissolves
the blanket "T-mentioning methods are concrete-only" restriction an earlier draft
imposed. What remains is only what's *logically* forced:

- **Methods not mentioning `T`** (`simplify():Expr`) — callable, unchanged.
- **`T` in return position** (`evaluate():T`) — **callable.** The result has
  static type `∃T:R` ("some type satisfying `R`"): opaque if `T` is unbounded,
  and usable through `R`'s interface if bounded. Dispatch is by the receiver's
  concrete type (`DispatchTable.resolveTraitFallback`, `DispatchTable.java:178`);
  `MethodResolver` already keys `e.evaluate()` to `Expr.evaluate`. So
  `function f(e:Expr)` *can* evaluate `e` and pass the result around — it just
  can't assume more about it than `R` grants.
- **`T` in argument position** (`make(T):Expr`) — callable only when you already
  **hold a value of that same `T`** (typically one obtained from the same
  receiver). This is not a Pontif limitation — you cannot fabricate an inhabitant
  of a type you can't name. A concrete receiver lifts it entirely (then `T` is
  known and you can construct one).

So the honest one-liner: **returns of `T` flow out freely as bounded
existentials; arguments of `T` need a witness in hand.** Both are fully
unrestricted once the concrete type is known.

## 3.3 Exactly-once supply (DERIVED)

The landed trait rule is "an attribute is supplied exactly once — by a field
*xor* a producer." An associated type extends it: each `type X` member must be
bound exactly once per impl, with `type X = […]` (a missing bind is an unmet
contract; binding a member that wasn't declared is an error). When the
declaration carries a bound (`type X:R`), the binding is checked against it —
`type X = [Foo]` is rejected unless `Foo` satisfies `R` (the existing
`TraitRegistry` satisfaction check, G8).

## 3.4 Reification — associated-`T` is free, free-parameter-`T` is not (PROPOSED)

"`T` remembered at runtime" lands very differently for the two kinds of type
variable, and the split is the boundary of this doc:

- **Associated types (this doc).** `T` is a *function of the concrete type*: the
  impl binds `T = [Int]` for `Lit`, `T = [Bool]` for `BoolLit`, etc. A value
  already carries its concrete `typeName` (`RecordValue`), so `T` is **always
  recoverable** by looking the concrete type up in the impl registry — exactly
  the path `tryAttributeProducer` already uses (`IrInterpreter.java:570`). So `T`
  is "remembered" with **no per-value storage and no runtime change** — it rides
  the concrete type the value already carries.
- **Free type parameters (later, NOT this doc).** A generic *parameter* —
  `function map(type E, …)` or `struct Box(type T, …)` — is not tied to a
  concrete impl, so there is nothing to recover `E` from. To remember it at
  runtime it must be **passed and carried as a type-witness** (a reified type
  value, dictionary-style). That is a genuinely larger mechanism (type-passing /
  monomorphisation) and a separate project. This doc stops at associated types;
  §6 notes free parameters as the next arc.

# 4. Technical problems / gotchas

## G1 — a new `type` keyword (RESOLVED by the declarator)
The earlier `T:Type` form needed the metatype `Type` admitted as a sort —
`assign trait Lit:Holder { T:Type -> … }` fails today with *"Unknown sort
'Type'"* from `SortChecker.validateSortNames` (`SortChecker.java:441`), which
accepts only `PRIMITIVE_SORT_NAMES` (`SortChecker.java:55`) or declared structs.
**With the `type` declarator that pressure is gone:** `type T` is a *keyword
form*, not a member whose sort is `Type`, so `Type`-as-a-sort never has to be
recognised. The remaining cost is small and ordinary:
- Add `type` to the lexer keyword set, and parse it in the two new positions
  (trait member list; `assign trait` body). Watch for source identifiers named
  `type` (a keyword promotion is a breaking lex change — grep the corpus).
- Mind the `type` (lowercase declarator) vs `Type` (metatype constructor:
  `Type{…}`, `Type[…]`) proximity. Defensible — `type X` *introduces*, `Type{…}`
  *constructs* — but flag it (it may read as inconsistent; an OPEN below).
- Unrelated latent bug to fix while here: `SortChecker.PRIMITIVE_SORT_NAMES` and
  `NameResolver.PRIMITIVES` are **out of sync** (`NameResolver.PRIMITIVES` is
  missing `Char`/`String`; `NameResolver.java:46`).

## G2 — producer bodies are values, not types
An attribute producer lowers to `IrStmt.FunctionDecl(Type.attr, [this], sort,
bodyExpr)` where `body` is an `IrExpr` (`AltParser.java:1617`,
`IrStmt.java:66`). There is **no way to put a type in body position** — the
parser calls `parseExpr` exclusively. So an associated-type binding **cannot be
a FunctionDecl**; it is recorded separately (G6). This is *why* the bind needs
its own declarator rather than riding the producer arrow.

## G3 — RHS value-vs-type guess (RESOLVED by the declarator)
With the rejected `T:Type -> [Int]` form, the `->` RHS would have to be
disambiguated value-vs-type (by a leading `[`, or by recognising the declared
sort is `Type`). The `type X = […]` form **removes the guess**: the `type`
keyword is the discriminator. In `parseAssignTrait` (`AltParser.java:1553`) a
member beginning with `type` is parsed as `type IDENT = parseSort()` and recorded
as a type binding; the existing method / attribute-producer split
(`peek(1)==COLON`, `AltParser.java:1571`) is untouched. The RHS is unconditionally
a sort — no `parseExpr`/`parseSort` ambiguity.

## G4 — associated-type names look like unknown sorts
`T` in `[Method(T):Expr]` parses to `IrSort.Named("T")`. When the trait's method
sigs are validated, `validateSortNames` (`SortChecker.java:441`) will reject `T`
(not a primitive, not a struct) unless it knows `T` is one of *this trait's*
associated-type members. **Fix:** thread the trait's associated-type names as an
in-scope set while validating that trait's member sorts, and again (pre-
substitution) while checking its impls. After per-impl substitution (G6) the `T`
references become concrete and validate normally.

## G5 — self-referential traits trip the alias-cycle guard
`trait Expr{ simplify:[Method():Expr] }` fails with *"Cyclic type alias
chain: Expr → Expr"*. `AliasResolver` enters every **non-structural** TypeAlias
into the alias table for inlining (`AliasResolver.java:66`) — traits included —
and the cycle detector (`AliasResolver.java:149`) fires when the trait's own
method sort references the trait name. **Fix:** treat trait names **nominally**,
exactly like struct names (structs are already excluded from inlining): a trait
reference is a name, not an expansion. This unblocks every recursive trait
(ASTs, linked lists, `Stream`) and is **worth doing on its own**, independent of
associated types.

## G6 — substitution into dependent signatures
Extend `IrSort.Trait` with the set of associated-type member names (today it has
`methods` + `attributes` maps; `IrSort.java:123`), and extend
`IrStmt.TraitImpl` (today `methods` + `attributeProducers`; `IrStmt.java:117`)
with a `Map<String, IrSort> typeBindings`. A small pass (or an extension of the
trait-impl check in `SortChecker`) substitutes an impl's `typeBindings` into the
trait's method/attribute sorts before checking the impl's members against them.
Substitution is structural `IrSort`→`IrSort` (reuse the `AliasResolver`
machinery, scoped to one impl).

## G7 — `T` is "remembered" for free (associated-types case)
A trait-typed value is just the concrete `RecordValue` (its `typeName` +
`members`; `RecordValue.java`), and resolution-by-concrete-type already runs at
runtime (`tryAttributeProducer`, `IrInterpreter.java:570`). Because an associated
`T` is a *function of the concrete type* (§3.4), it is **remembered with no
per-value storage** — recoverable from the `typeName` the value already carries.
So the reification requirement adds nothing to the associated-types runtime; the
work is in the checker. (Free type *parameters* are the case that would need a
runtime witness — §3.4, out of scope here.)

## G8 — dispatch / trait-fallback already carries it
Methods on `Lit` are keyed `Lit.evaluate`; the trait fallback redirects
`Expr.evaluate` → `Lit.evaluate` by the receiver's concrete type
(`DispatchTable.java:178`), and `MethodResolver` keys `e.evaluate()` to
`Expr.evaluate` for `e:Expr`. So calling a `T`-mentioning method on an
existential receiver **already works at runtime** — it dispatches to the concrete
impl, where `T` is its bound type. The only constraints are *static* and
logical (§3.2: returns flow out as bounded existentials; arguments need a
witness). Likewise, **using** a value of bounded type `T:R` calls `R`'s methods
via the very same concrete-type fallback — the runtime story for bounds is
already shipped.

## G9 — bounds are first-class (type-level refinement), phaseable
A bound — `type Element:Showable` — is **part of the model**, not a deferred
extra: it is the `refine` operator read on a type (`R` is the predicate, the
associated type the subject), and it is what makes an existential `T` usable
(§3.2). Checking a binding against its bound (`type Element = [Foo]` requires
`Foo` satisfies `Showable`) is the existing `TraitRegistry` satisfaction check
(G8). Implementation may still **phase** it — land unbounded `type X` first, add
the `:R` bound next — but the representation should carry the bound from day one
(a `Map<String,IrSort>` member→bound on `IrSort.Trait`, bound absent = "any
type"), so it isn't a later schema change. The further generalisation `type X`
takes — *free* type parameters on functions/structs — is the separate next arc
(§3.4).

# 5. IR / representation changes (summary)

- `IrSort.Trait`: add an associated-types map **`Map<String,IrSort>`**
  (member → bound; bound absent/`null` = "any type"). Carries bounds from day one
  even if checking them is phased (G9). `IrSort.java:123`.
- `IrStmt.TraitImpl`: add `Map<String,IrSort> typeBindings`. `IrStmt.java:117`.
- Lexer: add the `type` keyword.
- `AltParser`: parse `type X` / `type X:Bound` in `parseTraitTypeLiteral`
  (collect into the map) and `type X = [Sort]` in `parseAssignTrait` (record a
  type binding). No `Type`-as-a-sort handling needed.
- `AliasResolver`: nominalize traits (G5).
- `SortChecker`: scope associated-type names while validating member sorts (G4);
  substitute per-impl bindings before contract-checking (G6); check each binding
  against its bound via `TraitRegistry` (G8/G9); fix the
  `PRIMITIVE_SORT_NAMES`/`NameResolver.PRIMITIVES` drift (G1).
- Runtime: nothing for associated types (G7); free type *parameters* would need a
  type-witness (§3.4) — out of scope.

# 6. Slice plan

1. **Recursive traits** (G5) — nominalize trait references in `AliasResolver`.
   Small, self-contained, independently useful; unblocks the `Expr`
   self-reference and any recursive trait. **LANDED** (`e1e8cda`,
   `RecursiveTraitTest`).
2. **`type X` declaration** (lexer keyword + G4) — parse `type X` in a trait,
   scope the name, validate a trait *declaration* with `type T` and a `T`-in-any-
   position signature end-to-end (no impl yet). Representation carries the
   (still-unchecked) bound slot. **LANDED** (`2a8287d`, `AssociatedTypeDeclTest`).
3. **The bind** (G2 + G3 + G6) — the `type X = [Sort]` impl syntax, the
   `typeBindings` representation, per-impl substitution + contract check. Makes
   `Lit:Expr` work. **LANDED** (`c26254f`, `AssociatedTypeBindTest`).
4. **Bounds** (G9) — `type X:R` declarations, checked-at-bind via the
   trait-satisfaction relation, and `R`-interface use of `T`-typed values. This
   is what makes existentials *useful* (not just passable). **LANDED** in two
   parts: bound-at-bind checking (`3ed2844`, `AssociatedTypeBoundTest`); and the
   §3.2 existential-boundary consumption (`AssociatedTypeExistentialTest`) —
   `InferenceContext.fromModule` registers each contract method whose return
   mentions an associated type under the call key `Trait.method` (consulted only
   when the receiver is the bare trait — a concrete receiver resolves to
   `ConcreteType.method`), with the return *existentialized* to its bound, so
   `b.get().describe()` types through `R`'s interface and dispatches to the
   concrete impl at runtime. An unbounded `type X` existential stays opaque.
5. **(Later, separate arc — NOT done)** free type parameters on functions/structs
   with a runtime type-witness (§3.4); the `let P:Type[…]` → `type P = […]`
   replacement.
6. **The self-type return — `this.type`** (decision §7.3). A contract method that
   is *type-preserving* (returns the implementor's own concrete type, e.g.
   `copy:[Method():this.type]`) is spelled with `this.type` — the runtime-actual
   type of the receiver instance. **LANDED** (`AssociatedTypeSelfTypeTest`).
   Reserved sentinel sort `IrSort.SELF_TYPE` (`"this.type"` — un-spellable as a
   user name); parsed in `AltParser.parseSort`; scoped over a trait's member
   sorts in `SortChecker.validateSortNames` (like an associated-type name); per
   impl, substituted `this.type → <implType>` so the existing conformance check
   enforces that `copy` really returns its own type (the type-preservation gate,
   free — a sibling type is rejected); at the bare-trait boundary,
   `InferenceContext.fromModule` existentializes `this.type` to the owning trait,
   so `e.copy()` on `e:Expr` flows out as `Expr` (usable through the trait), while
   `Lit(5).copy()` keeps the concrete `Lit` (no downcast). Contrast `:TraitName`
   (slice 4), which promises only trait-membership — the right return for a *non*
   type-preserving method like `simplify`.

# 7. Decisions (resolved 2026-06-13)

1. **`type X = […]` RHS form — RESOLVED: not a real choice.** Brackets mark
   *refinements*, not bare base sorts (the bracket/paren law, restated). A bare
   base name takes no brackets — `type X = Int`, exactly like `let x:Int` and a
   bare `:Int` return; a *refinement* brackets — `type X = [Int:@>0]`, like
   `let x:[Int:@>0]`. The bind RHS is just another sort position and follows the
   same rule; there is no bind-specific bracket requirement.
2. **`type` vs `Type` — RESOLVED: keep separate, by design.** The casing *is* the
   disambiguator: lowercase = keyword/declarator (`type`, `method`, `function`),
   Capitalized = a sort/kind (`Type`, `Method`, `Function`). `Type` is one kind
   among potentially other type-able kinds, so `type X` (introduce) beside
   `Type{…}` (construct) is consistency, not collision. Not to be reconciled.
3. **The self-type return — RESOLVED: `:TraitName` for the semantic existential,
   `:this.type` for the type-preserving impl type. No `Self`, no `@@`.**
   - `:TraitName` (e.g. `simplify:[Method():Expr]`) returns *some* value of the
     trait — the bounded existential. Already landed (slices 1 + 4). Name
     self-reference is **not** an antipattern *here*: the antipattern is about
     hierarchies (a base method forgetting a subtype's identity), and Pontif
     traits are **flat** — a trait cannot extend a trait — so naming the trait
     forgets nothing. **Load-bearing invariant: traits stay flat.** If traits ever
     gain extension, `:Name` self-reference reintroduces the precision loss.
   - `:this.type` returns the *runtime-actual* type of the receiver instance — the
     type-preserving "Self," without the `Self` keyword. `this` = the instance,
     `.type` = its concrete type (the `typeName` tag the value already carries —
     a real runtime projection, no-lie-grounded). Reserving `type` as a keyword is
     what makes `.type` safe: it can never be a user field, so `this.type` is
     unambiguous. Slice 6.
   - `@` is untouched — it stays the *value-level* refinement-self inside a
     predicate (`[Int:@>0]`). `@`-as-a-sort was rejected (it conflated value vs
     type, immediate vs owning scope, and runtime vs semantic — `this.type`
     resolves all three by construction, since `this` is definitionally a runtime
     instance). `@@` was rejected (glyph-counting, illegible).
   - No clash with the `Type[…]` metatype: `Type[…]` takes a *type* (`[]`, the type
     side); `this` is exclusively a constructed *instance*, so `Type[this]` is a
     kind error, not a rival spelling. `this.type` is the unique value→type
     crossing (`.`, the value side); the bracket/paren law keeps the two disjoint.

*(The earlier "what does `evaluate` mean / which direction" decision is
**retired** — `T` is usable in any position, so both readings are simply valid
uses; see §2.2.)*
