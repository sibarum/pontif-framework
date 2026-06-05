# Pontif traits

Traits in Pontif are *named method contracts that nominal types opt into*.
The runtime mechanism is the existing dispatch table — there is no
parallel "trait dispatch" system. The compile-time mechanism is the
existing narrowing operator (`:`) — checking "type X satisfies trait Y"
is the same machinery that checks "value v narrows to sort S."

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
let Duck:Type{
  quack:[Method():Audio],
  eat:[Method(food:Food):Poop]
}
```

Reading: Duck has kind `Type` (it's a contract-shaped sort).
Type-level field assignments use `:` because they're sort narrowings,
not value assignments — the contract says "any value satisfying Duck
must have a method `quack` narrowing to `[Method():Audio]`."

The `self` parameter is *implicit* in each method's signature. A
contract method `quack:[Method():Audio]` says the implementation
should be a function from receiver-only to Audio; `self` is prepended
at impl-check time.

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
   eventually a `FunctionDecl("Donald.quack", [self:Donald], Audio, ...)`.
2. **Registers the satisfaction claim.** Adds `Donald` to Duck's
   satisfier set in the `TraitRegistry`. Compile fails (in the
   `SortChecker` pass) if any of Duck's contract methods is missing or
   has a signature that doesn't match (after self-prepending).

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
  the signature *without* self (param sorts + return sort).
- `IrStmt.TraitDecl(name, methods, origin)` — top-level declaration.
  `AliasResolver` registers it as a sort alias usable in narrowing
  positions; subsequent `Sort.named("Duck")` references resolve to
  the trait sort.
- `IrStmt.TraitImpl(typeName, traitName, methods, origin)` — the
  trait-assignment block. `methods` is `List<IrStmt.FunctionDecl>`
  built with the type-qualified name and self-prepended params.
  Lowering: when the module is compiled, each method becomes a real
  `FunctionDecl` in the dispatch table, and the (type, trait) pair
  is added to `TraitRegistry`.

`SortChecker` extensions:
- For each `TraitImpl(T, Tr, methods)`: verify every method in Tr's
  contract has a matching entry in `methods` after self-prepending
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
function name (`Donald.quack`) and self-prepended params reconstructed
from the trait's contract.

### Step 4 — alt syntax

```
let Duck:Type{
  quack:[Method():Audio],
  eat:[Method(food:Food):Poop]
}

struct Donald(name:String, color:Color)

assign trait Donald:Duck {
  quack():Audio -> Audio("quack")
  eat(food:Food):Poop -> Poop(food.weight)
}

method Donald.greet():String -> "Hi, I'm " + self.name

function describe(d:Duck):Audio -> d.quack()
describe(Donald("Donald", DARK_GREEN))
```

Parser additions:
- `let X:Type{...}` parses as a trait declaration (`IrStmt.TraitDecl`).
  Inside the braces, each entry is `name:FunctionSort`.
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
4. **Alt syntax.** `let X:Type{...}` and `assign trait X:Y {...}`.

## Open follow-ups

- **Default method impls** in the trait body. Trait body provides a
  default; impl blocks override or inherit. Useful but adds
  self-reference resolution. Defer to a later slice.
- **Multi-trait constraints** in param positions (`x:Duck & Audible`).
  Defer to the in-progress union/intersection sort work.
- **Primitives as trait implementors** (built-in type satisfies a
  *named-method* trait, e.g. `Int : Showable`). Operators are never trait
  contracts (dispatch-unification B1, resolved) — `Int : Addable`/`+` is not a
  thing, so the numeric-trait motivation is gone. What's left is purely whether a
  built-in may be registered in a trait's satisfier set (mechanism 2),
  independent of the operator work. Low demand now. Today traits work for user
  types only.
- **Trait inheritance** (Trait B *extends* Trait A — B implies A).
  Pure sugar over multi-trait constraints; defer.
