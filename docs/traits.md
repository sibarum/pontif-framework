# Pontif traits

Traits in Pontif are *named contracts that nominal types opt into* — a
contract of **method** members (mechanism-2, receiver-rooted) and/or
**operator** members (mechanism-1 bounds; see *Operator contract
members* below). The runtime mechanism is the existing dispatch table —
there is no parallel "trait dispatch" system. The compile-time mechanism
is the existing narrowing operator (`:`) — checking "type X satisfies
trait Y" is the same machinery that checks "value v narrows to sort S."

The design is the worked example of the load-bearing principle
**narrowing handles polymorphism**: when a feature looks like it wants
trait-bounds or typeclasses, reach for a narrowing-shaped solution
first. New dispatch machinery is the fallback, not the default.

## What a trait is

A **trait** is a sort. Specifically, a sort whose values are themselves
sorts — types that satisfy the trait's contract. Its kind is `Type`,
which is Pontif's name for "the sort whose values are sorts that
describe method contracts."

Decl form:

```
trait Duck{
  quack:[Method():Audio],
  eat:[Method(food:Food):Poop]
}
```

Reading: Duck has kind `Type` (it's a contract-shaped sort).
Type-level field assignments use `:` because they're sort narrowings,
not value assignments — the contract says "any value satisfying Duck
must have a method `quack` narrowing to `[Method():Audio]`."

The `this` parameter is *implicit* in each method's signature. A
contract method `quack:[Method():Audio]` says the implementation
should be a function from receiver-only to Audio; `this` is prepended
at impl-check time.

## Operator contract members

A trait may also name an **operator** as a `Dispatch` contract member —
the compile-time answer to "this type must support `+`". This is what
makes operator use *decidable at definition time* instead of failing at
a runtime dispatch miss.

```
trait Numeric{
  +:[Dispatch(this.type, this.type):this.type],
  *:[Dispatch(this.type, this.type):this.type]
}
```

(`Numeric` is illustrative, not a blessed stdlib name.) The distinction
from a method member is load-bearing:

- A **`Method` member** is mechanism-2 — receiver-rooted, owned by the
  type, resolved as `Type.method`. The trait *hosts* it.
- A **`Dispatch` member** is a mechanism-1 **bound**. The trait does
  **not** host the operator and does not make it resolve via the trait;
  `a + b` still resolves only by global multi-dispatch over the operand
  sorts. The member merely *requires* that a coherent mechanism-1
  overload exist for the satisfying type. (Why a trait at all, when
  operators live in mechanism 1? Because the trait is the place to turn
  "an overload exists" into a checked, propagatable obligation — see
  dispatch-unification B1.)

**`this.type` is the self-type** — it stands for whichever concrete type
is assigned the trait. So `+:[Dispatch(this.type, this.type):this.type]`
reads "the satisfying type `T` must have an overload `+(T, T):T`."

**Satisfaction is verified, not declared.** Unlike a method member, an
operator member is *not* implemented inside the `assign trait` block (an
operator is a free overload, not a receiver method). Instead, at
`assign trait T:Numeric` the compiler looks up the required overload in
the dispatch table and checks it exists and is coherent:

```
struct Vector(x:Int, y:Int)
function +(a:Vector, b:Vector):Vector -> Vector(a.x + b.x, a.y + b.y)
function *(a:Vector, b:Vector):Vector -> Vector(a.x * b.x, a.y * b.y)

assign trait Vector:Numeric { }   # empty: the operators are witnessed, not declared
```

If either overload is missing, the `assign trait` is a compile error.

**Carrying the proof into generic code.** A type parameter bounded by the
trait (`[type E:Numeric]`) inherits the guarantee, so operator use over
an abstract type is checked where the generic is *defined*:

```
function scaleSum[type E:Numeric](a:E, b:E):E -> (a + b) * (a + b)
```

`a + b` and `(…) * (…)` are statically known to resolve for any `E`
satisfying `Numeric` — no runtime "no matching overload" surprise.

**Scope (v1):** operator contract members are **homogeneous** —
`(this.type, this.type):this.type` only. Mixed-operand / promotion
contracts (e.g. `(this.type, Int):this.type`) are deferred and must
fail with a clear error until that slice lands. Heterogeneous operator
overloads themselves stay ordinary mechanism-1 overloads, just not
trait-bounded.

## Assigning a trait to a type

A struct opts into a trait via the `assign trait` block:

```
struct Donald(name:String, color:Color)

assign trait Donald:Duck {
  quack():Audio -> Audio("quack")
  eat(food:Food):Poop -> Poop(food.weight)
}
```

The block does two things simultaneously:

1. **Declares the methods.** Each entry inside the braces parses as a
   method decl with the receiving struct's name elided (since the
   header says `Donald`). The block desugars to:
   `method Donald.quack():Audio -> Audio("quack")` etc., each
   eventually a `FunctionDecl("Donald.quack", [this:Donald], Audio, ...)`.
2. **Registers the satisfaction claim.** Adds `Donald` to Duck's
   satisfier set in the `TraitRegistry`. Compile fails (in the
   `SortChecker` pass) if any of Duck's contract methods is missing or
   has a signature that doesn't match (after receiver-prepending).

Standalone methods declared via `method Donald.greet():String -> ...`
stay outside any `assign trait` block — they belong to Donald but to
no trait.

A struct can be assigned multiple traits; each goes in its own block.

## Using a trait-typed value

Once the assignment is registered, a value of the struct can be
narrowed to the trait — same `:` operator as any other narrowing:

```
let duck:Duck = Donald("Donald", DARK_GREEN)
```

The compiler accepts this because `Donald` is in Duck's satisfier set.

Function params can declare trait sorts; method calls on trait-typed
receivers route through runtime dispatch:

```
function describe(d:Duck):Audio -> d.quack()

describe(Donald("Donald", DARK_GREEN))   # routes to Donald.quack at runtime
```

Inside `describe`, `d` has static sort `Duck`. The parser doesn't know
the concrete type — but it does know `quack` is part of Duck's
contract, so it emits `Call("Duck.quack", [d])`. At runtime, the
dispatch table's trait-fallback rule resolves this against `d`'s
actual concrete type (Donald), finds `Donald.quack`, and invokes it.

## Backward-design layers

### Step 1 — runtime (Truffle / DispatchTable)

One new fallback rule in `DispatchTable.resolve`: if a direct lookup
for `Call("Trait.method", args)` fails, check whether the args' first
concrete type `T` is in the trait's satisfier set; if yes, redirect
the lookup to `T.method`. The redirect is just one extra table
read — no Truffle node changes, no closure capture, no new IR shape
at this layer.

The satisfier set lives in a `TraitRegistry`: `Map<String, Set<String>>`
keyed by trait name. Populated at compile time from `IrStmt.TraitImpl`
declarations.

### Step 2 — IR

Three new shapes:

- `IrSort.Trait(name, methods, origin)` — a sort whose kind is `Type`.
  `methods` is `Map<String, MethodContract>` where MethodContract is
  the signature *without* the receiver (param sorts + return sort).
- `IrStmt.TraitDecl(name, methods, origin)` — top-level declaration.
  `AliasResolver` registers it as a sort alias usable in narrowing
  positions; subsequent `Sort.named("Duck")` references resolve to
  the trait sort.
- `IrStmt.TraitImpl(typeName, traitName, methods, origin)` — the
  trait-assignment block. `methods` is `List<IrStmt.FunctionDecl>`
  built with the type-qualified name and receiver-prepended params.
  Lowering: when the module is compiled, each method becomes a real
  `FunctionDecl` in the dispatch table, and the (type, trait) pair
  is added to `TraitRegistry`.

`SortChecker` extensions:
- For each `TraitImpl(T, Tr, methods)`: verify every method in Tr's
  contract has a matching entry in `methods` after receiver-prepending
  param sorts. Fail compile with a clear "missing/mismatched method"
  error.
- For narrowing-check sites where the source sort is a struct and the
  target sort is a trait: accept iff (T, Tr) is in `TraitRegistry`.

### Step 3 — S-expr reference

```
(interface Duck
  (quack () Audio)
  (eat (Food) Poop))

(struct Donald ((name String) (color Color)))

(impl Donald Duck
  (function quack () Audio (call audio "quack"))
  (function eat ((food Food)) Poop (call poop (field food weight))))
```

Each method inside `(impl ...)` parses with the type-qualified
function name (`Donald.quack`) and receiver-prepended params reconstructed
from the trait's contract.

### Step 4 — alt syntax

```
trait Duck{
  quack:[Method():Audio],
  eat:[Method(food:Food):Poop]
}

struct Donald(name:String, color:Color)

assign trait Donald:Duck {
  quack():Audio -> Audio("quack")
  eat(food:Food):Poop -> Poop(food.weight)
}

method Donald.greet():String -> "Hi, I'm " + this.name

function describe(d:Duck):Audio -> d.quack()
describe(Donald("Donald", DARK_GREEN))
```

Parser additions:
- `trait X{...}` parses as a trait declaration (lowers to `IrStmt.TypeAlias`
  binding `X` to an `IrSort.Trait`). Inside the braces, each entry is `name:Sort`
  (a `[Method(...):R]` member, a data attribute, an operator contract, or an
  associated `type`).
- `assign trait X:Y { ... }` parses as a trait impl
  (`IrStmt.TraitImpl`). Inside the braces, each entry is a method
  decl in compact form (no `method` keyword, no `Type.` prefix).
- Existing `:` narrowing works on trait sorts wherever it works on
  struct sorts.

## Slicing

Bottom-up, each slice testable end-to-end via the layer below.

1. **Runtime: dispatch fallback + TraitRegistry.** Hand-built IR
   exercises the fallback rule. No surface syntax. Verifies the
   trait-method resolution path through interpreter and Truffle.
2. **IR: sort variant, decl/impl statements, SortChecker rules.**
   AliasResolver registers trait sorts; SortChecker verifies impls.
   Tested via constructed IR + IrCompiler pipeline.
3. **S-expr reference.** Surface form for the IR shapes, testable via
   existing S-expr test infrastructure.
4. **Alt syntax.** `trait X{...}` and `assign trait X:Y {...}`.

## Default method implementations (landed 2026-06-25)

A trait may give a method a body directly, written in the impl-method form
(the same `name(params):Ret -> body` shape used inside `assign trait`):

```
trait Doubler{
  base:[Method():Int],                          # abstract — every impl must supply it
  doubled():Int -> this.base() + this.base()    # DEFAULT — impls inherit unless they override
}
struct T(x:Int)
assign trait T:Doubler {
  base():Int -> 21          # `doubled` omitted → inherits the default
}
T(0).doubled()              # 42
```

A trait member is a default iff a `(` follows its name (vs `:` for an abstract
method `quack:[Method():Int]` or a data attribute). The default body has **full
self-reference**: it may read `this`, call sibling methods (abstract or
defaulted), etc. An `assign trait` block that **provides** the method overrides
the default; one that **omits** it inherits. Defaults flow through base traits
(`trait Derived : Base` inherits `Base`'s defaults; a derived default wins).

Mechanism — `TraitDefaultExpansion` (a pre-pass before method/operator
resolution): for each impl that omits a defaulted method, the trait's body is
cloned into a per-impl `Type.method(this:Type, …)` FunctionDecl (the injected
`this` re-typed from the `this.type` placeholder to the concrete subject; the
trait's associated types / `[type E]` params substituted). The clone then rides
the ordinary pipeline — alias/method resolution, the sort checker, dispatch
registration — so nothing else needed special-casing, and the impl is *complete*
by the time `SortChecker` runs. (Slice-1 limit: a default body that names an
imported type in a linked program isn't re-run through `NameResolver`; bodies
over `this`/fields/siblings/primitives — the common case — are fully supported.)

Not (yet) defaulted: **operator** contract members (mechanism-1 free overloads,
not methods) and **attribute** producers on the trait itself (impl-side
producers already exist).

## Open follow-ups

- **Multi-trait constraints** in param positions (`x:Duck & Audible`).
  Defer to the in-progress union/intersection sort work.
- **Primitives as trait implementors** (built-in type satisfies a trait, e.g.
  `Int : Showable`, or a numeric `Int : Numeric` witnessing `+(Int, Int):Int`).
  With operator contract members now in (dispatch-unification B1, **reopened →
  yes**), the `Addable`/`Numeric`-style motivation is back: a built-in needs to be
  registrable in a trait's satisfier set so a `[type E:Numeric]` bound can include
  primitives. Today traits work for user types only — extending the satisfier set
  to built-ins is the remaining gap.
- **Trait inheritance** (Trait B *extends* Trait A — B implies A).
  Pure sugar over multi-trait constraints; defer.
