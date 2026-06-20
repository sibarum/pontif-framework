

As of 2026-06-11
DRAFT — design in progress. Captures the conversation that reframed the old
"Case Functions" idea. This supersedes the "Case Functions" section in
docs/univocal-language-design.md: that surface is not "functions with cases" —
it's the arrow operators, and traits are their first concrete home.

STATUS (2026-06-11): The trait home below is **BUILT** — `Type{…}` with typed
data attributes (existence-only `width:Int` and refined `weight:[Int:@>0]`)
alongside methods; `assign trait` providing exactly the unmet members via
`name:Sort -> producer` arrows (the impl states the base, the contract supplies
the refinement); the exactly-unmet rule (field XOR producer; over/under-
assignment rejected); fail-closed (a field must already carry the refinement —
conservative; a producer rides the return-refinement gate so `weight:Int -> 0`
is rejected); and **bidirectional implicit coercion** (struct↔trait, sound — a
wrong-type downcast is rejected). See `TraitAttributeTest`. Built on the
interpreter; NOT yet done: Truffle attribute-access parity, the relational
constraint `[Type{…}:@.pred]` (refining a structural base — "the one new piece"
flagged below), deep field-refinement-through-`this` proving in producers, and
the `->`/`<-` parser-surface unification (still per-construct). The rest of this
doc (the generalization, the open questions) remains design.

---

# Univocal Arrows

One operator, `->` (with a routing dual `<-`), defines **processes and decision
trees**. The arrow is the primitive; lambdas, match trees, proof literals, and
trait impls are not separate features — they are the same construct: a member or
result **produced by** an expression.

- `->` reads "**produced by** / bind-and-produce" — forward, computational.
- `<-` reads "**asserted placement** / routing" — the conservation ledger's
  construction notation (see docs/TODO.md arrow-notation rulings); its surviving
  home is the future property-definition language. Not detailed here.

The arrow is for metaprogramming: the things it builds (proofs, lambdas, trait
projections) are first-class, inspectable values, not bespoke syntax.

---

## First concrete home: traits that compute their attributes

A trait is a reusable sort (`Type[…]`, see docs/univocal-language-design.md's
"Reusable Sort") that demands members — methods AND data attributes — and is
satisfied by any type that supplies them. The novelty: a trait's attributes are
**computed projections** of the underlying value, which is what makes trait
polymorphism free in both directions.

### Why traits up- and down-cast for free (the conservation argument)

Compare the two is-a regimes:

- **Struct extension** — `struct Point3D:[Point(x,y)](x:Int,y:Int,z:Int)`. The
  added `z` is **independent** data, not a function of `x,y`. So `Point3D -> Point`
  is free (forget `z`), but `Point -> Point3D` must **synthesize** `z` —
  information a Point doesn't carry. Upcast *creates* information ⟹ irreversible
  ⟹ explicit (the cast law's "promotion is explicit").

- **Trait** — every added attribute is **computed** from the base
  (`weight -> 1`, or `-> this.name.size`, …). Upcast *creates no information*
  (recomputable); downcast *destroys none* (forget a projection, recompute it
  any time). Base and trait-view are inter-derivable.

So a trait coerces **freely and implicitly in both directions** because
**promotion-without-fabrication** holds automatically — there is nothing to
synthesize. This is exactly the case the cast law lets through silently; it only
demands explicitness when a promotion would fabricate. The "added attributes"
are not added information — they are a **lens** of computed projections over the
base. (Consequence: the producers must be pure — already Pontif's default — or
the upcast wouldn't be deterministic.)

That is the trait's whole value over struct promotion, stated precisely: more
boilerplate (you write the projections) buys information-conserving coercion in
both directions, so the polymorphism is free.

### The sort side: members live in `Type{…}` (the `@{}` cell), typed

A trait is *declared* `trait NAME{ … }`; the brace block `{ … }` is the `@{}` cell
of the operator algebra (a type's members by name; see "The Operator Algebra" in
docs/univocal-language-design.md), and `Type{ … }` is the **anonymous** form of that
same cell — a sort usable in any sort position. Methods and data attributes are
members together, each `name:Type`. Strict typing is the lever: a bare `@.coef != 0`
clause **cannot** impose `coef` on its own, because then `coef` would be untyped
— which is forbidden. So every required member is declared, with a type, inside
`Type{…}`. Strict typing forces the "closed" reading: the "open" reading would
produce a typeless attribute the rules outlaw.

```
# Members are name:Type. Methods use the Method sort; attributes a value sort.
trait Pingable{ ping:[Method():Int] };                    # method only

trait Heavyish{ ping:[Method():Int], weight:[Int:@>0] };  # method + refined attr

trait Boxed{ width:Int, height:Int };                     # existence + type only
```

Two forms of attribute requirement, both inside `Type{…}`:
- `width:Int` — existence + type, no refinement (the minimum).
- `weight:[Int:@>0]` — existence + type + per-member refinement, inline.

**Cross-field/relational constraints** (`width > height`, which no single
member's type can hold) are a **refinement over the member shape** — the cells
nest freely (a refine-cell wrapping a member-cell). Two equivalent spellings, by
the five-way base-is-a-conjunct equivalence (`[Base:pred]` ≡ `[Base & pred]`):

- `[Type{ width:Int, height:Int }:@.width > @.height]` — CANONICAL: a plain
  refinement, base = the member shape, reads like `[Int:@>0]`. (`@:[]` cell.)
- `Type[ @{ width:Int, height:Int } & @.width > @.height ]` — the same sort at
  the type cell (`@[]`). Valid, kept — NOT an artifact. Forbidding it would be an
  artificial limitation (univocal = cells nest freely).

Here the predicate is a CONSTRAINT (a membership requirement on instances of the
trait); the very same `[X:@pred]` is a CLAIM at a binding site. Role is positional,
syntax is fixed. `@` is the instance; `@.width` projects its field — the
refinement belongs to instances of the trait, not the type.

Rejected spelling: `[Type{…}[@.pred]]` (postfix `[]`) — postfix `[…]` is already
metareference/metatype application (`inc[Int]`, `Type[…]`); overloading it to
mean "refine" is a third meaning. Type-level refinement uses the `Type[…]`
*prefix* precisely to keep postfix `[]` free.

Implementation note: a refinement whose base is a member-shape
(`[Type{…}:pred]`) needs `IrSort.Refined` to refine a STRUCTURAL base, not just a
named one (`[Int:…]` carries a base name today) — the one new piece vs. refining
a primitive.

### The impl side: the construction morphism, as arrows

`assign trait T:Trait { … }` is the construction morphism `T -> Trait`. Its body
is a bundle of **`member <- producer`** arrows; attribute vs. method is just the
presence of `()`. It must define **exactly** the required members `T` doesn't
already supply — no fewer (incomplete), no more (a member the trait doesn't
expose is unreachable through the view, so defining it asserts dead structure: a
lie).

```
struct Ipsum(name:Char)                 # no weight

assign trait Ipsum:Heavyish {
  weight:Int -> 1                        # attribute: NAME : Sort -> producer (computed; checked 1 > 0)
  ping():Int  -> 0                       # method:    NAME(params) : Ret -> body
}
# An Ipsum viewed as Heavyish reads { name, weight = 1 }, ping() = 0.

struct Lorem(weight:[Int:@>0], name:Char)   # already brings weight > 0
assign trait Lorem:Heavyish {
  ping():Int -> 7                        # only the method — weight already present; re-providing it is over-assignment
}
```

`->` is one operator with one meaning: the right side is a producer over the
instance (`this`) and any method args. `weight:Int -> 1` is a 0-arg producer;
`ping():Int -> 0` is a producer with the method's arg shape; a producer can read
the instance (`weight:Int -> this.name.size`). Field-init and method-def stop
being two things.

### Failure modes (lorem ipsum)

```
struct Dolor(weight:Int)                 # weight present but not provably > 0
assign trait Dolor:Heavyish { ping():Int -> 1 }
# ✗ fail-closed: @.weight > 0 unprovable for an unconstrained Int

struct Sit(name:Char)
assign trait Sit:Heavyish { ping():Int -> 0 }
# ✗ incomplete: Heavyish requires `weight`; Sit neither pre-declares nor provides it

assign trait Lorem:Heavyish { weight:Int -> 9  ping():Int -> 7 }
# ✗ over-assignment: Lorem already has `weight`; re-providing it is dead/conflicting structure
```

---

## The generalization (direction, not yet detailed)

Everything below is the same arrow; the unification is the point, but only the
trait home above is firm. The rest is the roadmap.

- **lambda** = a one-arm arrow: `( [n] -> n + 1 )`.
- **match** = ordered arrow arms, a decision tree (first-match): the existing
  `match` is a sequence of `[pattern] -> result`.
- **proof literal** = arrow arms used as **domain cuts** rather than for their
  results — exactly the `assign proof` case-function already shipped (the arms
  re-invoke the body; the cut is the content).
- **trait impl** = a bundle of `member <- producer` arrows (above).

So `->` spans value production (lambda), decision (match), proof (cut tree), and
member projection (trait). `<-` is the routing/placement dual for the property
language. A future slice unifies the parser surface; today each lives in its own
construct.

---

## Rulings (this conversation, 2026-06-11)

1. Traits coerce **implicitly, both directions** — because added attributes are
   computed projections (information-conserving). Struct extension stays
   explicit-on-upcast (independent data).
2. The impl defines **exactly** the unmet required members — over-assignment is a
   compile error (unreachable member = a lie); under-assignment is incomplete.
3. Required attributes are declared **typed, in `@{…}`**; `@.` clauses refine or
   relate them. Strict typing forces this (no typeless imposed attribute).
4. Existence-only attribute = `@{ name:Type }`. A name with no type is rejected.
5. The sort carries **requirements**; the impl carries **definitions**. The sort
   never holds a value; `@.weight > 0` is an obligation, `weight -> 1` discharges it.
6. Relational constraints are a **refinement over the member shape**, canonical
   `[Type{…}:@.pred]` (`&` form equivalent, postfix `[]` rejected). Constraint in a
   sort, claim at a binding — role is positional, syntax fixed. Cells nest freely:
   the old `Type[ @{…} & @.pred ]` is valid, NOT an artifact.

## Open questions

- The full `->`/`<-` surface unification (lambda, match, proof literal sharing
  one parser construct) — far-reaching; its own slice.
- Whether trait-view attributes are recomputed per access or memoized at the
  coercion (observationally equivalent under purity; an implementation choice).
- `<-` routing semantics for the property-definition language (parked).

RESOLVED (was open): `Type{…}` IS the `@{}` member cell — it holds methods AND
typed attributes together; the old methods-only trait form is just `Type{…}` with
no data members. No separate surface.
