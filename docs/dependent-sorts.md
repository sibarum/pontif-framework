# Dependent sorts: sorts that reference in-scope value binders

**Status: WAR — PROPOSED (2026-06-20).** Substrate-first, consumer-driven by
`Indexed` (`docs/indexed-streams.md`). The one open cluster is §6 — James's call.
Markers: **RULED** = settled (here or in the 2026-06-19/20 design dialogue) ·
**DERIVED** = follows from ruled material + standing laws · **PROPOSED** = this doc's
recommendation · **OPEN** = undecided · **SYNTAX-SLOT** = a hole for surface design.

This is one facet of the type-system convergence (`project_type_spec_layering`,
`docs/feature-matrix.md`): structural traits, named Type fragments, dependent sorts,
and generics are projections of one **scoped type-level binding** substrate. Dependent
sorts is the foundational facet — the others are built *on* it (see §1).

---

## 0. The smell (ground truth, 2026-06-20)

A sort cannot reference a value binder in scope — not a sibling parameter, not the
receiver, not a sibling field. The capability is **partial, not absent**, and the
partial is *specialized*, which is the smell:

- **It already works, narrowly.** A return-position value-pin references a parameter
  today: `ackermann(x, y) : [Int: @ == y_0 + 1]` synthesizes its body and discharges
  (`SpecOnlySynthesisTest`; visible in the receipt-graph report). So the language can
  already relate a return sort to a parameter — but only through the **synthesis /
  construction-gate path** that *evaluates* the pinned expression, not through a
  general "a sort names a binder" mechanism.

- **The general mechanism is missing, and there's a concrete fulcrum.**
  `IrSort.Method` carries `List<IrSort> paramSorts` — **parameter sorts with no
  names**. A method sort therefore cannot even *mention* its own parameter, so the
  parser rejects it outright:

  > `AltParser` (the named-parameter-method-sort guard, ~`:2686`): *"Named-parameter
  > method sorts (e.g., `[Method(x:Int):Ret]`) are not yet supported — `IrSort.Method`
  > needs param-name support. Use positional form for now."*

- **Zero tests** exercise a refinement referencing anything other than `@` and its own
  fields (verified across the whole suite). The `Depend` column of
  `docs/feature-matrix.md` is the most `!!`-heavy column, with the `/` (partial) cells
  exactly on the synthesis-pin path above.

So the front-end can relate a return to a param in one specialized corner, and the
data model (`IrSort.Method`) can't carry a parameter name at all. The round-trip —
specialized partials piling up around a representational gap — is the smell.

---

## 1. Why this is the foundation (read before sequencing)

The sequencing question that chose this war (James, 2026-06-20): *not* "which delivers
most value fastest," but **"which task gets harder if pushed back?"** — which one, if
deferred, forces the others to be built around its absence and reworked.

Dependent sorts is **upstream** of the other three facets:

- **`IrSort.Method` carrying parameter names** is needed by dependent returns,
  **trait method contracts in general** (e.g. `Indexed.at`), **structural-trait
  contract members** (an anonymous `Type{ m:[Method(i:Int):…i…] }`), and
  receiver-relative bounds. Build structural traits or any trait-contract work *first*
  and it sits on the nameless `IrSort.Method`; adding names later ripples through every
  consumer (parser, sort-checker, dispatch, contract validation). Do it first and they
  are built on the final shape.
- **"A sort references an in-scope binder" + scope + substitution** is the shared
  engine behind `this.count`, `OutOfRange(i)`, dependent returns, *and* parametric
  fragments (`gte(n)=[@>=n]`). Build it once; the other facets ride it. Defer it and
  each grows an ad-hoc partial — which is already happening (the synthesis-pin path,
  §0).

The other facets are additive / cheap to defer: **named fragments** (inline predicates
work meanwhile; aliases add monotonically), **structural traits** (nominal traits
cover; the anonymous form errors cleanly; its fix is *downstream* of `IrSort.Method`),
**sub-traits** (pure additive `:Super`, already deprioritized).

### Footprint inventory (every site the substrate touches)

| File | Role | Touched? |
|------|------|----------|
| `ir/IrSort.java` — `IrSort.Method` | carries `paramSorts`, no names | **yes** — the fulcrum (add names) |
| `parser/AltParser.java` | the `[Method(i:Int):…]` guard (~`:2686`); sort parsing | **yes** — accept named params; parse binder refs |
| `ir/SortChecker.java` | checks signatures / trait contracts | **yes** — names in scope for signature sorts |
| `ir/NarrowingInference.java` | the unified engine; substitutes/instantiates sorts | **yes** — substitute binder refs at application |
| construction-gate / synthesis path | the existing specialized partial (`@==EXPR` refs param) | **yes** — generalize, don't special-case |
| dispatch / contract validation | consume `IrSort.Method` | maybe — ride the new shape (names ignorable when unused) |
| `BoundAnalysis` / `IntegerDischarge` | prove `i < this.count` | later (rung 2, §4) |

*Exact line numbers beyond the verified `AltParser` guard and `IrSort.Method` shape
are to be confirmed in slice 1 — not asserted here (the no-lie rule applies to this
doc too).*

---

## 2. What "dependent" means here (scope it — RULED)

**A sort may reference a value binder in scope:** a parameter (by name), the receiver
(`this` / `this.field`), or a sibling field. References are *closed* over in-scope
binders and **instantiated/substituted at application** (call site, or when the
receiver is bound). This is the refinement-types-reference-a-prior-binder shape
(F\*/Liquid Haskell/Dafny), **not** arbitrary type-level computation (§7 lineage).

Three concrete forms, all forced by `Indexed`'s honest `at` (`docs/indexed-streams.md`):

1. **Dependent return** — the return sort references a parameter:
   `at(i:Int) : [T | OutOfRange(i)]`.
2. **Receiver-relative refinement** — a parameter sort references the receiver:
   `at(i:[Int: @ >= 0 & @ < this.count]) : T`. Here `@` is the refinement-self (the
   index `i`), `this.count` is a field reference (a **data attribute**, not a method
   call — immutability makes the field reading sound; `project_indexed_streams`).
3. **Value-indexed struct sort** — a struct pinned to a binder's value:
   `OutOfRange(i)` ≡ `[OutOfRange: @.at == i]` (the failure carries which index).
   **Not new syntax** (RULED, spike-confirmed 2026-06-20): *both* spellings already
   work for **literals** — `[OutOfRange(2)]` (positional ctor arg) and
   `[OutOfRange:@.at==2]` (named field) parse, type-check, and run today. The
   dependent delta is purely **the operand: literal → in-scope binder** (`i` a
   parameter). One generalization of existing machinery, not a from-scratch form.

**Self-reference law holds** (`project_self_reference_law`): `@` = refinement-self
only; `this` = receiver; `this.type` = runtime-actual self (already works,
`AssociatedTypeSelfTypeTest`). Dependent sorts adds *value* references alongside these.

---

## 3. The substrate (the foundational core)

Three pieces, in dependency order:

1. **`IrSort.Method` carries parameter names.** The fulcrum. Unblocks
   `[Method(i:Int):R]` at the parser; gives later pieces a binder to reference. A
   purely representational change — names are ignorable by consumers that don't use
   them, so the existing suite must stay green with names threaded but unused.
2. **A signature scope.** When checking a function/method/trait-contract signature,
   its parameter names (and `this` / `this.field` for a receiver) are in scope for the
   *sorts within that signature* (param sorts and the return sort).
3. **Substitution at application.** When a dependent sort is instantiated — a call
   site supplies arguments, or a receiver is bound — binder references substitute to
   the actual values/sorts. This **generalizes the existing synthesis-pin path** (§0):
   `@==EXPR`-referencing-a-param becomes one case of binder-reference substitution,
   not a special path.

This substrate is **representational** — it lets dependent sorts be *written, scoped,
and substituted*. It deliberately does **not** include the hard discharge (§4).

---

## 4. The three rungs (discharge deferred behind `[!!]`)

Tracks the dependent-types literature (§7); only rung 1 is this war's core.

- **Rung 1 — represent + total access (the substrate, RULED first).** Dependent sorts
  are written/scoped/substituted (§3). The **total** forms need *no* discharge:
  `at(i) : [T | OutOfRange(i)]` makes out-of-bounds a **match arm** (the head-of-empty
  precedent), so it cannot lie with zero proof machinery. This is the rung-1 deliverable
  alongside the substrate. (≈ Rust `get(i):Option`.)
- **Rung 2 — discharge the refined form (incremental).** `at(i:[Int:@<this.count]):T`
  returning the bare element needs `i < this.count` *proven*. Routes through
  `BoundAnalysis`/`IntegerDischarge`; provable for literals (`(1,2,3).count==3`) and
  `Iterate`-bounded indices, **deferred elsewhere via `[!!T]`** (the runtime hazard:
  throws on access, not before; `project_runtime_hazard`). Provable miss (a negative
  literal) = compile error. The construction-gate three-way, applied to an index.
  (≈ F\* `i:nat{i<length l}`.)
- **Rung 3 — `Fin`-style index sort (endgame, OPEN).** `Fin(this.count)` via the
  free-type-parameter machinery (`the field IS the witness`, `project_type_parameters`);
  out-of-bounds **unrepresentable**, no `OutOfRange` arm. Deferred. (≈ Agda/Idris
  `Vec n`+`Fin n`.)

Honesty is preserved at every rung: rung 1 is total (can't lie), rung 2 degrades safe
(`[!!]`), rung 3 makes the bad case unrepresentable. No rung asserts a false bound.

---

## 5. Slice plan (vertical, each compiles + green)

Per the rewrite rule (`feedback_vertical_slices`): each slice is end-to-end.

0. **This doc ratified** (§6 decisions).
1. **`IrSort.Method` carries parameter names. LANDED (2026-06-20).**
   `IrSort.Method` gained `List<String> paramNames` (canonical 4-arg + a 3-arg
   back-compat constructor, so all ~20 construction sites compiled unchanged); the
   parser accepts `[Method(i:Int):R]` (the `:2686` guard deleted) and rejects mixing
   named/positional. Names are carried but **not yet referenced** by any sort (slice 2)
   and there is **no discharge**. Green: existing pontif-parser/ir/runtime suites
   unchanged + new `AltParserSortTest` cases (named carry names, positional stay
   nameless, mixed rejected). The fulcrum is in place.
2. **Binder references in sorts + scope + substitution (§3).** A param/return sort may
   name a parameter, `this`, or `this.field`; resolved in the signature scope;
   substituted at application; the synthesis-pin path folded into the general
   mechanism. Unprovable bounds degrade to `[!!]` (no discharge engine yet — rung 1).
3. **Consumer: `Indexed` total `at`.** `count` as a data attribute, total
   `at(i):[T|OutOfRange(i)]` (out-of-bounds = match arm), receiver-relative refinement
   *written* (`[Int:@<this.count]`) but its bound rides `[!!]`. This is the forcing
   function that proves the substrate (`docs/indexed-streams.md` slices 1–2).
4. **Rung 2 — discharge.** Integer engines prove `i < this.count` for the provable
   cases; the refined `xs(i):T` graduates from `[!!]` to clean where provable.
5. **(Deferred) Rung 3 — `Fin`-style index sort.**

Probe meter: new `dependent__*` probes (binder-ref parses; total-access arm;
provable-bound clean; unprovable→hazard; provable-miss→reject) plus the existing
`inference__*`/`traits__*` suites staying green. Update `docs/feature-matrix.md`'s
`Depend` column as cells graduate (`!!`→`/`→`^^`), each with its witness.

---

## 6. The decision (James)

The plan assumes the §2 scope at **substrate-first** ambition. To confirm:

1. **Scope boundary.** Binder references reach **parameters (by name), `this`,
   `this.field`** — closed references to in-scope binders, substituted at application.
   *Not* arbitrary expressions / type-level computation. Is that the right fence, or
   do you want it wider (e.g. references to earlier *let*-bindings in a body) or
   narrower (receiver-relative only, defer sibling-param refs)?
2. **Slice 1 standalone.** Ship `IrSort.Method` param-names as its own green commit
   first (the fulcrum, no behavior change), before any binder-reference semantics?
   (Recommended — it's the change everything waits on and it's low-risk.)
3. ~~`OutOfRange(i)` value-indexed struct sorts ride existing machinery?~~ **RESOLVED
   (spike, 2026-06-20):** yes — both `[OutOfRange(2)]` (positional) and
   `[OutOfRange:@.at==2]` (named) already work for literals (`project_named_refinements`);
   the dependent delta is the operand `literal → binder`. No new sort form needed.

Already RULED in dialogue: dependent sorts is the foundational facet (build first);
substrate ≠ discharge (discharge is rung 2, deferred behind `[!!]`); total
`[T|OutOfRange(i)]` is the rung-1 primitive; `count` is a field/attribute and
`this.count` a field reference; `Indexed` is the forcing consumer; **do not** build a
speculative type-level calculus (lens-not-cage — let the substrate emerge from
`Indexed`).

---

## 7. Lineage

Array indexing is *the* motivating example for refinement/dependent types; Pontif's
three rungs track three literature strengths:

| Rung | Literature | Shape |
|------|------------|-------|
| 1 — total union | Rust `slice::get -> Option`, Scala `Seq.lift` | runtime-checked totality, no dependency |
| 2 — receiver/param refinement | **F\*** `i:nat{i<length l}`, **Liquid Haskell** `{i:Nat\|i<len xs}`, **Dafny** `requires 0<=i<a.Length` | predicate references a prior/receiver binder |
| 3 — `Fin`-indexed family | **Agda/Idris/Coq** `lookup : Vec n a → Fin n → a` | index type excludes OOB by construction |

Seminal: **Xi & Pfenning, "Eliminating Array Bound Checking Through Dependent Types,"
PLDI 1998** (Dependent ML) — exactly this problem. Pontif's grain is the rung-2
refinement style (`[Base: predicate]` with the predicate allowed to name `this`/a
param); rung 3 is the heavier type-level-natural encoding.

---

## 8. WAR markers (cut sites)

To be marked in-code with `WAR(dependent-sorts)` pointing here:

- `AltParser.java` (~`:2686`) — the named-parameter-method-sort guard (slice 1 deletes
  it).
- `IrSort.java` — `IrSort.Method` gains parameter names (slice 1, the fulcrum).
- The construction-gate / synthesis path — the existing specialized `@==EXPR`-refs-param
  partial that slice 2 generalizes into binder-reference substitution.
- `NarrowingInference.java` — where dependent sorts substitute at application.
