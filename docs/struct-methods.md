# Struct member blocks

A struct may carry a **member block** — a `{ … }` of method declarations — and
declare its is-a base as an **intersection** naming at most one struct supertype
plus any number of traits the block must satisfy:

```
struct Ace:[Card & Named & Scored](points:Int) {
  label():String  -> "Ace"
  score():Int     -> this.points
  boosted():Int   -> this.score() + 10
}
```

Reading: `Ace` is-a `Card` (the one struct supertype), and satisfies the traits
`Named` and `Scored`. Its block declares three methods. `label` discharges
`Named`, `score` discharges `Scored`, and `boosted` is **untethered** — no trait
requires it, so it is simply a method on `Ace`.

## The rules

- **The block holds compact-form method decls only** — `name(params):Ret -> body`,
  the same form used inside an `assign trait` block. The `this` receiver is
  implicit. The `method` keyword is **not** used inside the block; it stays valid
  only for standalone `method Type.m` decls written outside any block.
- **The is-a base may be an intersection** `:[A & B & C]`. At most one branch may
  be a **struct** (the supertype); the rest must be **traits**. Two struct
  branches is a compile error ("at most one struct supertype"). A branch that is
  neither a declared struct nor a trait is an error.
- **A struct supertype is optional.** `struct S:[T1 & T2](…){ … }` (all traits) and
  `struct S:[T1](…){ … }` (one trait) are fine, as is a block with **no** base at
  all — then it is pure method namespacing on the struct.
- **Each declared trait is verified individually** against the struct's methods:
  every contract method must have an exact match (correct arity and a compatible
  return), checked the same way a hand-written `assign trait` block is checked. A
  signature that does not match the contract is rejected.
- **Overlaps pass.** One method may satisfy several traits at once (e.g. two traits
  that both declare `size:[Method():Int]`), as long as it is genuinely compatible
  with each — it is checked against every trait independently.
- **Trait defaults still apply.** If a trait gives a method a default body and the
  block omits that method, the default is inherited (as with `assign trait`); if
  the block provides it, the block wins.

- **A block method may construct its own struct**, which is what makes the
  immutable-copy method — the most ordinary method on an immutable value — writable
  where it belongs:

  ```
  struct Box(kind:String, size:Int) {
    withKind(k:String):Box -> Box(k, this.size)
  }
  ```

  This needs no special rule: no type's visibility depends on where in the file it
  is declared (`PontifParser.prescanTypeDeclarations` registers every declaration
  before any body is parsed), and a struct is simply the first type its own block
  can name. Before that, the form was impossible — a struct is never declared before
  itself — and the method had to be exiled to a standalone `method Box.withKind`
  below the declaration.

Methods are declared **once** in the block. There is no separate `assign trait`
block to write and no method is repeated per trait — the single method set is the
one thing every declared trait is checked against.

## Relationship to `method` and `assign trait`

The member block is sugar, not a new mechanism. A method in the block lowers to an
ordinary `Struct.method(this:Struct, …)` declaration — exactly what a standalone
`method Struct.method(…)` or an `assign trait` method produces — so it registers in
dispatch and is called the same way. The traits named in the intersection base
lower to ordinary `assign trait Struct:Trait` obligations, verified against the
struct's methods. Because a method is a method wherever it is declared, any method
the struct declares (block or standalone) can discharge that struct's trait
obligations.

## How it is compiled

`StructTraitLowering` (an IR pass that runs right after `AliasResolver`, where each
base branch is a resolved sort) splits a struct's intersection base:

- the single struct supertype branch, if any, stays the struct's `baseSort` — so
  every is-a consumer (the ancestry walk, the demotion/totality check, the
  construction gate) sees an ordinary single-base struct;
- each trait branch becomes an empty `assign trait Struct:Trait` impl.

`TraitDefaultExpansion` then fills any defaulted method the block omits, and
`SortChecker` verifies each obligation against the struct's own method set (the
"some-method" pool: a contract method is discharged by the struct's like-named
method, run through the same signature check the contract demands). The is-a core
is untouched — the intersection never reaches it, because the split happens first.

## Example

See [`pontif-playground/examples/struct-member-block.ptf`](../pontif-playground/examples/struct-member-block.ptf),
and [`declaration-order.ptf`](../pontif-playground/examples/declaration-order.ptf) for
the immutable-copy method and the other forms that stop depending on where a type is
declared (docs/parser-linker-refactor.md item 6).
