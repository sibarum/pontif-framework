# The type system

*Part of the [Pontif guide](../../README.md). This page is the in-depth tour of
Pontif's type system — refined dispatch, structs and methods, the three
polymorphism models, generics, and operator overloading. For the one-page
overview, see the root [README](../../README.md).*

Pontif's type system exists to be **robust enough that you never have to leave
it** — there's no excuse for a practical language to ship a weak one. Declared
types are claims the compiler proves or rejects, and a single symbolic-predicate
engine drives refinement, dispatch, traits, pattern matching, and type extension.
This page walks each in turn.

One piece of vocabulary first, because everything below leans on it. A **refined
type** — or *narrow type* — is an ordinary type plus a predicate that its values
must satisfy, written `[Base:predicate]`: `[Int:@>0]` is the type of positive
integers. Wherever this guide says a type *narrows*, it means exactly that — the
predicate got tighter, and the set of values it admits got smaller. A plain `Int` is
just the widest case, the refined type with no predicate at all.

## Contents

- [Function dispatching with refined types](#function-dispatching-with-refined-types)
- [The inline conditional](#the-inline-conditional)
- [Structs and methods](#structs-and-methods)
- [Anonymous structural types](#anonymous-structural-types)
- [Traits — alternative interfaces](#traits--alternative-interfaces)
- [Type extension — a richer type](#type-extension--a-richer-type)
- [Multiple polymorphism models](#multiple-polymorphism-models)
- [Struct member blocks](#struct-member-blocks)
- [Constructor bodies](#constructor-bodies)
- [Enums — a closed set of values](#enums--a-closed-set-of-values)
- [Generics (type parameters)](#generics-type-parameters)
- [Operator overloading](#operator-overloading)

## Function dispatching with refined types

Types narrow by predicate, dispatch selects on the narrowing, and declared
returns are proof obligations:

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

- **Overloads dispatch on the narrowing.** `factorial`'s two clauses are selected
  by which type the argument satisfies (`[Int:0]` vs `[Int:@>0]`), and *provable
  overlap is rejected at registration* — multi-dispatch is unordered and
  unambiguous. (Contrast `match`, which is ordered top-to-bottom with overlap
  allowed; the two are deliberately different tools.)
- **A declared return is a claim.** `inc`'s `[Int:@>1]` says "the result exceeds
  1." The compiler discharges `x >= 1 ⟹ x + 1 > 1` at compile time: the
  **receipt-graph engine** drafts an obligation graph from the body (`Drafter`),
  an issuer closes it with sign / linear-bound / integer reasoning
  (`BuiltinIssuer`), and a notary accepts only what it cannot refute (`Notary`).
  A false claim — or a true one the engine can't prove and you supply no `proof`
  for — rejects the program. Both `factorial` clauses carry the same claim,
  `[Int:@>=1]`, and it closes *inductively*: the recursive call's own
  `[Int:@>=1]` is taken as the induction hypothesis, so `n > 0` (from the
  parameter type) and `factorial(n-1) >= 1` together discharge
  `n * factorial(n-1) >= 1`.
- **Match totality is enforced as a conservation rule.** A non-exhaustive match
  is a compile error *with the uncovered witness*; undecidable coverage demands a
  default arm. `_` desugars to the precise complement where computable.

Narrowing extends to the `Decimal` domain (sign, range, and equality-up-to-scale
narrows; integer-only reasoning like `>0 ⟹ >=1` is provably quarantined from the
dense domain):

```pontif
struct Account(balance:[Decimal:@>=0], rate:Decimal)

function grow(a:Account):Decimal -> a.balance * (1.0 + a.rate)

let acct = Account(100.0, 0.05)
grow(acct) ~= 105.0   # → true
```

`~=` is approximate equality done right — equal within one ulp at the working
precision (DECIMAL128), a tolerance *derived from the division policy*, never
configured — and it is rejected in type position: the proof layer never forgives.

## The inline conditional

`if c then a else b` is an ordinary **expression**, valid anywhere a value is — a
call argument, a `let` right-hand side, the terminal expression of a member:

```pontif
function abs(n:Int):Int -> if n < 0 then 0 - n else n

function grade(score:Int):Int -> if score >= 90 then 4 else if score >= 80 then 3 else 1

let peak = if grade(85) > 2 then 99 else 0

abs(0 - 7) + grade(85) + peak   # → 7 + 3 + 99 = 109
```

It is pure sugar for a `match`, and lowers to exactly one — no new construct in
the IR:

```
if c then a else b   ==   match (c) { [Bool:@] -> a  _ -> b }
```

`[Bool:@]` is the sub-type of `Bool` where the value itself holds (`{true}`), and
the ordered `_` complement is `{false}`. Two consequences fall out of the lowering
rather than being decided separately:

- **`else` is mandatory** — there is no partial `if`. The lowering is total by
  construction, which is the same rule match totality already enforces; a
  one-armed `if` would be a non-exhaustive match.
- **`else if` chains need no grammar of their own.** The else-branch is *any*
  expression, so a nested `if` just works — `grade` above is three arms deep with
  no special case anywhere in the parser.

Prefer `match` when you are discriminating on *types* or on more than a couple of
predicates — it is the form that carries narrowing into each arm. `if` is for the
case where a `Bool` you already have picks between two values.

## Structs and methods

Methods are **namespaced to their receiver, not globally dispatched**:

```pontif
struct Vec(x:Int, y:Int)

method Vec.norm():Int -> this.x * this.x + this.y * this.y

Vec(3, 4).norm()   # → 25
```

`norm` is keyed `Vec.norm` and resolved on the receiver's type. A free function
`norm(v)` and a method `v.norm()` are *different names that may coexist* — there
is no `p.m()` ≡ `m(p)` equivalence. This is Pontif's second dispatch mechanism
(localized and rigid, owned by the type/module), kept deliberately separate from
the first (free functions and operators, which are global, open, and
module-coherent).

Sum types fall out of unions plus bare/destructure match arms — coverage is
*determined*, so the canonical ADT match needs no default:

```pontif
struct Circle(r:Decimal)
struct Rect(w:Decimal, h:Decimal)

function area(s:[Circle|Rect]):Decimal -> match s {
  [Circle(r)]  -> 3.14 * r * r
  [Rect(w, h)] -> w * h
}

area(Rect(3.0, 4.0))   # → 12.00
```

Match patterns *are types*, so destructuring (`[Rect(w, h)]`), narrowing
(`[@>0]`), and literal-pinning (`[Rect(3.0, h)]`) all compose in one form — there
is no separate pattern DSL. A positional pattern wears the constructor's clothes
and must account for *every* slot (discard with `_`); a subset is lying by
omission and is rejected.

The same per-slot composition applies to **tuples** — a slot is pinned to a
value, bound to a name, or discarded:

```pontif
function score(p:[{Int, Int}]):Int -> match p {
  [{0, 0}] -> 0          # both slots pinned
  [{0, y}] -> y          # pin the first, bind the second
  [{x, y}] -> x * y      # bind both — the catch-all
}

score({0, 5}) + score({2, 3})   # → 11
```

Aggregates are written with braces (`{…}`) — the value `{0, 5}` and, inside a
match, the pattern `[{…}]`. The `[` makes it unambiguously a pattern (`[` is never
postfix in Pontif — arrays index by application), while the bare `{…}` is the value.

A tuple slot has a **third** form, filling in the gap the first two leave. A bare
name (`a`) binds the whole slot but tests nothing; a bare type (`Lit`) tests the
slot but binds nothing; **`name:T`** does *both* — it tests the slot against the
type `T` *and* binds the narrowed value under that name. It is the conditional-cast
slot: the arm fires only when the slot fits, and inside it the binder is already
narrowed, so its members resolve without opening the value up into fields:

```pontif
struct Lit(value:Int)
struct Add(left:Int, right:Int)
let Expr:Type[Lit | Add]

function combine(x:Expr, y:Expr):Int -> match {x, y} {
  [{a:Lit, b:Lit}] -> a.value + b.value    # fires only when both are Lit; a, b narrowed to Lit
  [_]              -> 0
}

combine(Lit(3), Lit(4))   # → 7
```

Without it you'd have to destructure — `[{Lit(av), Lit(bv)}]` — even when you want
the value whole; `name:T` keeps the value intact while still guarding on its type.

`name:T` reads this way **inside a pattern**. In plain type position the same
spelling declares a *named member* — see [anonymous structural
types](#anonymous-structural-types) below. Position decides, and the two never
meet: a pattern matches a value, a type describes one.

## Anonymous structural types

A brace aggregate has two faces, and each has a type. The `=` knob picks which:
positional members make a **tuple**, named members make a **record**. Both are
*anonymous* — they describe a shape without claiming a name.

|            | value        | type                |
| ---------- | ------------ | ------------------- |
| positional | `{1, 2}`     | `[{Int, Int}]`      |
| by-name    | `{a = 1}`    | `[{a:Int}]`         |

The value writes `=` and the type writes `:` because that is what those symbols
mean everywhere else: `=` binds a value, and the right of `:` is always a type.

```pontif
let objectWithProp:[{property:String}] = {property = "a string"}
let point:[{x:[Int:@>0], y:[Int:@>0]}] = {x = 3, y = 4}

function widthOf(box:[{w:Int, h:Int}]):Int -> box.w

objectWithProp.property + (String:point.x + widthOf({w = 6, h = 9}))   # → "a string9"
```

A shape is a **requirement, not a name**: any literal of that shape satisfies it,
with no struct to declare and no nominal claim made. That is the whole difference
from `struct Point(x:Int, y:Int)`, which additionally says *this value is a Point*
and can carry methods and traits.

What a shape is not is a decoration — its members are judged exactly like a
struct's fields. A member of the wrong type, a missing member, and an extra member
are all compile errors, refinements on members are proved, and the primitive tower
coerces across the boundary (`{d = 3}` fits `[{d:Decimal}]`, as `P(3)` fits
`struct P(d:Decimal)`).

Two forms are deliberately rejected rather than half-supported:

- **Mixing positional and named members** in one body — `[{Int, property:Decimal}]`.
  The reserved reading is constructor-order members first and named members after,
  but nothing implements it, so it fails with that explanation instead of parsing
  into a shape that quietly drops half of what you wrote.
- **A method member** — `[{doStuff:[Method(Int):String]}]`. A shape carries data.
  Behaviour is named: declare a trait with that contract and use `[T]`. This is the
  same line the [traits](#traits--alternative-interfaces) section draws — a trait is
  how a type says what it *does*, and an anonymous shape says only what it *has*.

## Traits — alternative interfaces

A trait is a type whose members are declared by *name* with `Type{ ... }`, and a
struct is fitted to it with `assign trait`. Those members are **methods** — named
contracts a type promises to satisfy — and typed **data attributes**.

The method form is the heart of it: a trait names method signatures, each concrete
type supplies its own implementation (or inherits a **default** the trait writes),
and a function written against the trait dispatches to whichever implementation the
runtime value carries:

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

Two kinds of worker are paid by genuinely different formulas, but a payroll run
shouldn't care which is which. `weeklyPay:[Method():Int]` is the contract — a method
from the receiver alone to `Int`, with the `this` parameter implicit. Each
`assign trait` block supplies that one type's `weeklyPay`, and `payroll(w:Payable)`
accepts *any* satisfier: the call `w.weeklyPay()` resolves to `Hourly.weeklyPay`
(`20 * 40 = 800`) or `Commissioned.weeklyPay` (`300 + 10 * 25 = 550`) by the concrete
type the value carries. There is no inheritance and no vtable — trait dispatch is the
same module-coherent multi-dispatch the rest of the language uses, keyed on the receiver.

A trait usually names **more than one** member. Members are *terminated*, not
comma-separated: one per line is enough — a newline ends a member, and an explicit
`;` is only needed to put two on the same line or to close a member the parser would
otherwise read as continuing:

```pontif
trait Metrics {
  area():Int
  perimeter():Int
}

struct Box(w:Int, h:Int)

assign trait Box:Metrics {
  area():Int      -> this.w * this.h
  perimeter():Int -> 2 * (this.w + this.h)
}

function report(m:Metrics):Int -> m.area() + m.perimeter()

report(Box(4, 3))   # → 12 + 14 = 26
```

Members can also be typed **data attributes** — a pure projection of the struct:

```pontif
trait Boxed{ area:[Int:@>0] }

struct Rect(w:[Int:@>0], h:[Int:@>0])

assign trait Rect:Boxed {
  area:Int -> this.w * this.h
}

let r = Rect(4, 3)
r.area   # → 12
```

`Rect` has no `area` field, so the impl *produces* one with a `->` arrow
(`area:Int -> this.w * this.h`) — the metaprogramming that writes the member. An
attribute must be supplied **exactly once**: by a matching field *xor* a producer.
The producer is itself checked against the contract `[Int:@>0]`, and here it *passes*
only because the sides are themselves positive (`w > 0` and `h > 0` ⟹ `w * h > 0`).
Give `Rect` unconstrained `Int` sides and the very same producer is **rejected**,
fail-closed: a rectangle of negative width has no positive area to promise.

Because every trait attribute is a pure projection of the underlying struct — no
independent information is added — a struct coerces to a trait it satisfies, and
back, **freely in both directions**:

```pontif
trait Boxed{ area:[Int:@>0] }

struct Rect(w:[Int:@>0], h:[Int:@>0])

assign trait Rect:Boxed {
  area:Int -> this.w * this.h
}

let r = Rect(4, 3)
let b:Boxed = r         # upcast — free: area is computed, nothing is lost
let back:Rect = b       # downcast — free: the concrete identity was never erased
back.w                  # → 4
```

This is the conservation principle lifted to polymorphism: a trait gives a type an
alternative interface *with a guaranteed return path* to the original. (A downcast
to the *wrong* concrete type — one that merely also satisfies the trait — is
rejected: the value cannot masquerade as something it isn't.)

### Logic in the types — a shell the trait owns

A method's argument and return *types* need not be mere membership predicates —
they may be **transform-chains**. `[A -> … -> B]` takes an `A`, threads it through
each `->` stage (`@` is the value in flight), and yields a `B`. Placed in a trait
method's **return**, such a chain is a *shell* the trait owns: every satisfier's
implementation is wrapped by it and **cannot opt out** — the impl writes only the
inner kernel.

```pontif
trait Billed{ charge(qty:Int):[Int -> @ * 100 -> Int] }

struct Plan(price:Int)

assign trait Plan:Billed {
  charge(qty:Int):Int -> this.price * qty
}

Plan(9).charge(2)   # kernel: this.price * qty = 18 dollars; the return shell ×100 → 1800 cents
```

A `Billed` type reports what it charges in whole dollars, but the billing ledger
insists everything be booked in cents. The kernel `Plan` writes returns the shell's
*domain* (`Int` dollars, here `18`); the trait's return shell maps it to the
*terminus* the caller sees (`1800` cents) — so the dollars→cents conversion belongs
to the contract, not to `Plan`. No satisfier of `Billed` can hand back a raw dollar
figure by mistake. This is how a trait injects behaviour through a *type* rather than
a body: the satisfier supplies the core, the trait wraps the edges.

The **argument** side is symmetric, and a trait can own **both** shells at once — so
the caller faces the outer ends of a method while the impl's kernel faces the inner
ends, and the trait owns the conversion between:

```pontif
trait Ordered{ bill(order:[Int -> @ * 12 -> Int]):[Int -> @ * 100 -> Int] }

struct Bakery(unitPrice:Int)

assign trait Bakery:Ordered {
  bill(order:Int):Int -> this.unitPrice * order
}

Bakery(2).bill(1)   # arg shell: 1 dozen → 12 units; kernel: 2 * 12 = 24 dollars; return shell ×100 → 2400 cents
```

The customer orders in *dozens* and reads a price in *cents*; the baker only ever
thinks in individual units priced in whole dollars. The impl writes only
`this.unitPrice * order` over the inner faces (`order:Int` in — already counted in
units — and `Int` dollars out); the trait owns the dozens→units expansion on the way
in and the dollars→cents conversion on the way out, and no satisfier can opt out.
(The same `[A -> B]` shells work on an ordinary function's parameters and return —
there they are the author's own, not a contract's. See
[transform chains](../sort-transforms.md).)

Two receivers to keep distinct: **`this`** is a method's injected instance
(`this.area`); **`@`** is the value in flight inside a `[...]` — the one under a
refinement predicate, or the one a transform-chain is mid-converting. They are
orthogonal. (Effects live in the event substrate — see the
[effects guide](effects.md).)

## Type extension — a richer type

The third model. Inheritance, newtypes, union supertypes, and refinement
subtyping aren't four features — they're **one construct**,
`struct Name:[Base:rel](fields)`: the brackets say what the type *is* (is-a), the
parens what it *has* (has-a):

```pontif
struct Point(x:Int, y:Int)
struct Point3D:[Point:@.x==x & @.y==y](x:Int, y:Int, z:Int)

let p = Point3D(2, 3, 5)
let flat:Point = p                   # demote — a view; z is hidden, not lost
let back:[Point3D:@.z==0] = flat;    # promote — synthesize z through the Point view

flat.x + flat.y + back.z             # → 5
```

The cast law is the no-lie law made geometric: **lose freely, fabricate never.**

- **Demotion** (`flat:Point = p`) is a **view**, not a copy: `flat` exposes only
  `Point`'s interface (`x, y`) and methods, while the value keeps its concrete
  `Point3D` identity — `z` is *hidden*, not destroyed. Concrete identity changes only
  by construction or an explicit cast, never covertly by rebinding (the mainstream
  rule; *lose freely* is losing **access**, not data).
- **Promotion** builds a strictly richer type *through the view* — `back` sees
  `Point`'s `x, y` plus the `z==0` pin, so `back.z` is `0` (the concrete's hidden `5`
  isn't consulted by a pinned promotion; an explicit downcast would recover it). The
  view can't conjure `z`, so promotion is never implicit — it is a **synthesized
  construction**, and what triggers the synthesis is the *pin*: a definition
  terminated without a value synthesizes exactly when its type pins one (see the
  [proofs guide](proofs-and-ledgers.md)). The trailing `;` above is just the
  optional terminator, not a command. Every existing behavior of the base still
  applies.

### Explicit casts — `(Type:value)`

The same law governs coercion *across* domains. `let n:String = 12` is a type
mismatch, not a silent stringification — the fix is the explicit cast `(Type:value)`,
the value-space sibling of a refinement (`[Base:pred]` is a type, `(Type:value)` is
a value; same colon, opposite side of the type/value line):

```pontif
let n:String = (String:12)        # render is named — no silent stringification
"n=" + n                          # → "n=12"
```

This is Pontif's answer to Julia-style promotion. Implicit coercion is kept *only*
for the closed primitive tower (`Int → Decimal`, a lossless embedding you can't
extend or shadow); everything open is explicit. Because the target is named, nothing
is searched (so nothing is incomplete) and nothing is ambiguous — and a coercion that
can't be performed fails closed rather than fabricating. A cast is a dispatch feature:
it resolves `(source type → target type)` on the one shared engine. Today the built-in
renders to `String` ship; user-defined `Type → Type` coercions register and resolve
the same way.

## Multiple polymorphism models

Polymorphism in Pontif is three sharply-separated tools, and between them they
cover the major use cases:

- **Traits** give a type *alternative interfaces*, with a guaranteed
  information-conserving return path back to the concrete type.
- **Multi-dispatch** provides *interoperability* between types of independent
  authorship — open, symmetric, and kept coherent by the orphan rule.
- **Type extension** provides *promotion* to a strictly richer type while
  retaining all existing behavior.

None subsumes another; the type system's job is to keep them honest, not to merge
them.

## Struct member blocks

The three models above are separate constructs, but a type routinely wants all of
them in one place: a struct with **its own methods**, an **is-a base** it extends,
and the **traits** it satisfies. A struct may carry a trailing `{ … }` block of
compact-form method declarations, and write its is-a base as an **intersection**
`:[Super & T1 & T2]` — at most one struct supertype plus any number of traits. The
methods are declared **once** in the block; each named trait is then verified against
that one method set:

```pontif
trait Named  { label:[Method():String] }
trait Scored { score:[Method():Int] }

struct Card(points:Int)

struct Ace:[Card & Named & Scored](points:Int) {
  label():String -> "Ace"
  score():Int    -> this.points
  boosted():Int  -> this.score() + 10
}

Ace(11).boosted()   # → 21  (this.score() = 11, + 10)
```

`Ace` is-a `Card` (the one struct super) **and** satisfies both `Named` and `Scored`.
Its block supplies `label` (for `Named`), `score` (for `Scored`), and `boosted` — which
no trait requires, so it is simply a method on `Ace`. The rules follow from "a method
is a method":

- A method matching **no** trait is just a method on the struct (`boosted`); a block
  with no traits at all is pure method namespacing.
- One method may satisfy **several** traits — an overlap passes, each trait checked
  independently against the same method (declare `label:[Method():String]` on two
  traits and one `label()` answers both).
- A trait method the block **omits** is a compile error (the missing-`quack` case),
  unless the trait writes a **default** — a defaulted method the block doesn't override
  is filled in, not re-synthesized into a collision.
- **More than one** struct supertype in the intersection is rejected: a value cannot
  be-a two independent structs at once.

The feature is pure sugar over the constructs above — the block lowers
to standalone `Ace.label(this:Ace, …)` method decls, and the intersection base splits
into the single struct super plus one empty `assign trait Ace:T` per trait — so the
is-a core, demotion, and construction are untouched
([docs/struct-methods.md](../struct-methods.md)).

## Constructor bodies

A struct's default constructor takes its declared fields and always runs first.
A `->` body **extends** it: a let-led preamble in which each `let this.name = expr`
line adds *one more* field, computed from the fields already bound.

```pontif
struct Rect(
  w:Decimal
  h:Decimal
) ->
  let this.area = this.w * this.h            # type inferred (Decimal)
  let this.halfArea:Decimal = this.area / 2.0
{
  describe():Decimal -> this.area + this.halfArea
}

struct Square:[Rect:@.w==side & @.h==side](side:Decimal)

let r = Rect(3.0, 4.0)
let s = Square(3.0)

r.area + s.halfArea   # → 12.00 + 4.50 = 16.50
```

`area` and `halfArea` are ordinary fields once construction is done — readable,
usable in methods, counted by equality — but they can never be *supplied*.

- **Add-only.** An extension introduces a new name. Reusing a constructor field's
  name, or an inherited one, is a compile error — a constructor body cannot
  reassign, only extend. There is no mutation here and no second constructor to
  pick between.
- **Never undefined.** Each field is materialized onto the value at construction
  and judged against its type exactly like a constructor argument, so an
  initializer that cannot satisfy its declared type is a compile error rather than
  a runtime surprise. There is no order in which the field does not yet exist.

  > **Known limitation (pre-1.0):** "exactly like a constructor argument" is
  > currently exact in both directions — a violated *refinement* is caught, but an
  > outright *base-type* mismatch (`let this.a:String = this.w` over an `Int` field)
  > is not, because construction does not yet check bare primitives anywhere. See
  > [soundness-holes.md](../soundness-holes.md).
- **The type annotation is optional.** It is inferred over the preamble's
  whitelist grammar — field reads carry their declared types, operators stay in the
  primitive tower. Annotate when the operands leave the tower.
- **Order is the only dependency rule.** An initializer may read the constructor's
  fields and any *earlier* extension (`halfArea` reads `area`); naming a field that
  is not bound yet is an error.
- **Constructor-facing positions never see them.** Positional arity, by-name
  literals, destructuring slots, and is-a base-field determination all read the
  declared fields only — `Rect(3.0, 4.0, 12.0)` is an arity error and
  `Rect{w=3.0 h=4.0 area=9.9}` is rejected outright.
- **They are inherited.** Bodies run root-first down the is-a chain, so `Square`
  gets `area` and `halfArea` without restating them — `s.halfArea` is `4.50`
  because `Square`'s morphism pins `Rect`'s `w` and `h` to `side`.

The body and the [member block](#struct-member-blocks) coexist, in that order: the
`this.` target ends the preamble, so the `{ … }` methods follow it and a subsequent
top-level `let` parses as usual. A struct wanting a body writes at least one `let`
in it — a `->` with nothing after it is an error, not a no-op.

## Enums — a closed set of values

An `enum` is a struct whose values are a **closed, named set**. The fields are shared
by every case; each case fixes them.

```pontif
enum ResourceType(driver:String) {
    DatabaseTable("postgres")
    LocalFilesystem("NTFS")
    RemoteHttp("tcp/ip")

    latencyBudget():Int -> match this {
        [ResourceType.RemoteHttp] -> 500
        [ResourceType] -> 5
    }
}
```

Cases and methods share one member block — a method always writes
`name(params):Ret -> body`, which is how the two are told apart. Both are members, so
both are newline- or `;`-terminated (`enum Colour { Red; Green; Blue }` on one line).
The field list is optional, and an enum takes trait obligations (`enum E:[T1 & T2](…)`)
exactly as a struct does.

A case name is a **type-level member**: it names the case's singleton sort *and* that
sort's one value, so it reads as a pattern in brackets and as a value in an
expression. Since the sort has exactly one inhabitant, the two can never disagree.

```pontif
let f:ResourceType.LocalFilesystem = ResourceType.LocalFilesystem   # sort, then value
let d = ResourceType.DatabaseTable.driver                           # "postgres"
```

Applying the enum to a row of literals is a **lookup**, not a construction — a sealed
type has exactly the values its cases name, so `ResourceType("tcp/ip")` *selects*
`RemoteHttp`, and `ResourceType("mysql")` is a compile error listing the three that
exist. A non-literal argument is refused: `ResourceType(someString)` is a narrowing,
and narrowing is done by match, not by application.

The seal is what buys you exhaustiveness. Because the value-set **is** the case list,
a match needs no default arm, and a missing arm names the case you forgot:

```pontif
function availability(r:ResourceType):String -> match r {
    [ResourceType("NTFS")]       -> "available"      # a literal row is a filter
    [ResourceType.DatabaseTable] -> "needs DB access"
    [ResourceType.RemoteHttp]    -> "needs network"
}
```

All of this is sugar. The declaration lowers to the pinned-subtype form
[type extension](#type-extension--a-richer-type) already provides — a base struct plus
one zero-field case struct per case, each pinning every base field — with two things
added: a compiler-forced `_ordinal` discriminant (so payload-free cases, and cases
sharing a payload, stay distinct and ordered) and the recorded cover that closes the
type. Nothing in the is-a core, demotion, or construction changes
([docs/enums.md](../enums.md)). An enum takes trait obligations and its block methods
satisfy them, so a case is usable wherever the enum's trait is expected.

## Generics (type parameters)

A `[type T]` slot after a name makes a function, struct, or trait *parametric*.
Pontif never erases types — every value carries its concrete type — so a generic
needs no runtime witness, no dictionary, no monomorphized copy: **the value is its
own evidence.** The parameter is derived from the value at construction and
inferred at the call.

```pontif
struct Box[type T](value:T)              # T is carried by the field
function open(b:Box[Int]):Int -> b.value
function id[type E](x:E):E -> x          # E inferred at each call

id(open(Box(7))) + id(3)                 # → 10
```

A trait can be parametric too, and a struct forwards its *own* parameter into the
trait it satisfies — so the element type rides each value, per value:

```pontif
trait Container[type E]{ get:[Method():E] }

struct Box[type T](value:T)

assign trait Box[type T]:Container[T] {
  get():T -> this.value
}

Box(42).get()                            # T = Int from the field → 42
```

The same parametric type is honest in an **is-a** base, where the type argument is
*invariant*: a struct that claims it is-a `Literal[Int]` must really hold an `Int`
— not a refinement of one, and not a `Bool`.

```pontif
struct Literal[type T](value:T)
struct IntLit:[Literal[Int]:@.value==value](value:Int)

IntLit(9).value                          # → 9
```

## Operator overloading

An operator is just a free function whose name is the operator, which makes custom
algebras read like the built-in ones:

```pontif
struct Money(cents:Int)

function +(a:Money, b:Money):Money -> Money(a.cents + b.cents)

(Money(150) + Money(75)).cents   # → 225
```

The safeguard against rogue definitions is the **coherence (orphan) rule**: an
operator (or any trait impl) may be defined only in the module that owns one of
its operand types — *one operand must be local*. A third module can't quietly
redefine `+` on types it doesn't own, so global multi-dispatch stays sane.

---

**Full design notes:** [refined types](../dependent-sorts.md) ·
[traits](../traits.md) · [subtypes](../subtypes.md) ·
[struct methods](../struct-methods.md) · [type parameters](../type-parameters.md) ·
[transform chains](../sort-transforms.md) · [cross-module dispatch](../cross-module-dispatch.md)
