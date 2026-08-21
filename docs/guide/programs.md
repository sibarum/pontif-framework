# Programs, files, and modules

*Part of the [Pontif guide](../../README.md). This page is the one to read first:
what a `.ptf` file actually is, when you need a `main`, how a namespace spans
several files, and what turns a directory into a project. For the one-page
overview, see the root [README](../../README.md).*

## Contents

- [A file is declarations plus a result](#a-file-is-declarations-plus-a-result)
- [`main` — an explicit entrypoint](#main--an-explicit-entrypoint)
- [`module` — naming a namespace](#module--naming-a-namespace)
- [`requires` — reaching another module](#requires--reaching-another-module)
- [Projects](#projects)

## A file is declarations plus a result

A `.ptf` file is a list of **declarations** — `function`, `method`, `struct`,
`trait`, `let`, `proof` — followed by **the program's result**. That trailing
expression is what the program evaluates to and what `pontif run` prints:

```pontif
function greet(n:Int):Int -> n + 1

greet(41)
```

That is a complete, runnable program. There is no required header, no required
entrypoint, and no boilerplate — nearly every snippet in this guide is a whole
program in exactly this shape, which is why they can all be run and checked by the
build.

Declarations are not ordered: a function may call one declared further down the
file. Only the result expression has a fixed position, at the end.

## `main` — an explicit entrypoint

The trailing expression covers a program that *computes* something. When a program
also has to **do** something — emit events, own state, open a window — its top-level
statements go inside a **`main ( … )`** block instead:

```pontif
requires pontif.events.{StdOut}

struct Point(x:Int, y:Int)

main (
  let p = Point(3, 4)
  emit StdOut("built a point")
  p.x + p.y
)
```

`main` takes one statement, and a parenthesized sequence is one statement — so
everything the program does at startup sits in there, ending with the value the
program produces (here `7`). Anything a long-running program needs — an `action`
reacting to events, a `conductor` owning state, a GUI window — is driven from
`main`.

One rule matters as soon as you have more than one module: **only the entrypoint
file's `main` runs.** A `main` in a module you `requires` stays dormant. That means
a library can carry its own demo or smoke-test `main` without it firing inside your
program.

## `module` — naming a namespace

A first line of `module a.b` names the file's namespace. A single-file program can
skip it; you want it as soon as another file needs to reach this one.

```pontif
module geometry.vectors

struct Vec(x:Int, y:Int)

method Vec.normSq():Int -> this.x * this.x + this.y * this.y

Vec(3, 4).normSq()
```

Every `.ptf` file in the project that declares the **same** `module` header merges
into **one** module — Go-package style, and deliberately folder-agnostic: what
groups two files is the header they declare, not the directory they sit in. Files
in one namespace are mutually visible with **no `requires` between them**, so you
can split a growing module across files purely for your own convenience.

Only one file per namespace may carry the entry `main`; a second one is a compile
error rather than a coin-flip about which runs.

> **Known limitation (pre-1.0):** cross-file dispatch inside a merged namespace
> currently fails when a **struct type** is involved — a `function` or `method`
> declared in one file can't be called from a sibling file if a struct appears in
> its signature, though struct *construction* and field access across files work
> fine. Keep a struct and the functions over it in one file for now.

## `requires` — reaching another module

`requires` names what you're importing, by member:

```pontif
requires pontif.math.{sqrt, clamp}

sqrt(9.0) + clamp(9.0, 0.0, 5.0)
```

The `.{…}` is the same named-decomposition form used for destructuring a value
(`p.{x, y}`) — see [the notation grid](notation.md). Builtin modules
(`pontif.math`, `pontif.events`, `pontif.algebra`, and the rest) are installed
already; you still `requires` the members you use, so a file states its
dependencies honestly.

Two rules keep imports coherent across modules. **Import-by-association** means a
value carries its type's operations with it — you don't have to import an operator
to use it on a value you were handed. The **orphan rule** means an operator or
trait impl may only be defined in a module owning one of its operand types, so a
third module can't quietly redefine `+` on types it doesn't own. Both are covered
in [the type system guide](type-system.md#operator-overloading).

## Projects

A directory becomes a **project** when it holds a `module.toml`:

```toml
name = "app"
namespace = "my.app"
version = "0.1.0"
entry = "my.app"
```

`entry` names the module whose `main` the runtime drives. `pontif run <dir>` loads
every `.ptf` under the root, merges same-namespace files, and runs the entry;
`pontif pack` zips the marker plus the sources into a `<name>-<version>.ptfpkg`.

A `.ptfpkg` is a **source bundle**, not compiled output — so the full compile and
proof gates re-run when it's executed, and a packaged artifact can never carry
unproven code. `pontif new my.app` scaffolds all of this; see [the CLI
section](../../README.md#the-pontif-cli) for the full command list.

---

**Full design notes:** [link-provenance](../link-provenance.md) ·
[cross-module dispatch](../cross-module-dispatch.md) ·
[extensions](../extensions.md) · [events](../events.md)
