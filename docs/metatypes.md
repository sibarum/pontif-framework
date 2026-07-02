# Metatypes

As of 2026-06-09-ET

STATUS: **DRAFT — design brainstorm.** Real-vs-aspirational is marked honestly per
section; do not read this as shipped.

> **STALE NOTICE (2026-06-26, verified against code on master).** The original
> "only `Type[…]`/`Type{…}` parse today, and `Type[…]` is itself unbuilt" claim is
> out of date. As of master:
> - **Built:** `Type[…]` reusable-sort aliases (`f84ebe5`, `ReusableSortTest`); the
>   `;` synthesis directive (`873830c`); value/promotion synthesis (`eee41a3`,
>   `f9506c3`); and a **prover-resident synthesis engine + bridge seam**
>   (`pontif-predicates/Synthesis` + `pontif-ir/SynthesisBridge`, `3257457`) — "all
>   synthesis lives in the prover, behind a fragment-gated bridge."
> - **Important scope caveat:** that engine synthesizes *values from refinement
>   predicates* (`SynthesisBridge.enumerateInt: IrSort → List<Long>`), **not**
>   function-body-from-function-structure.
> - **Still unbuilt:** `inverse[f]`/`differential[f]` (absent from code); a *generic*
>   `M[arg]` operator dispatch (`Type[…]` is special-cased to the keyword `"Type"` in
>   `AltParser:1699`, sort-only).
> - **So:** `inverse`/`differential` *extend* a proven prover-synthesis seam (adding a
>   function-from-function routine + an `IrExpr→IrExpr` bridge + generalizing the
>   application surface), rather than building the substrate from scratch.

---

## What a metatype is

A **metatype** is a compile-time operator `M` that takes a metareference — a
type or a function — and **synthesizes a new one**. It is applied with postfix
brackets, `M[arg]`, the same surface as `Type[…]` and the `inc[Int]`
metareferences. `Type` is the first metatype; `inverse` is the second; the
family below is the rest.

Every metatype is three parts:

- **License** `L_M` — the obligation `arg` must satisfy for `M[arg]` to exist,
  checked by the proof engine (`inverse`: f is `Reversible ∧ halts`;
  `differential`: f is built from differentiable primitives; …).
- **Type transformer** `τ_M` — how `M[arg]`'s sort derives from `arg`'s
  (`inverse`: swap Dom/Cod, restrict Cod to f's image; `differential`: same
  domain, derivative codomain; …).
- **Synthesis rule** `σ_M` — how `M[arg]`'s body is built from `arg`'s
  structure, triggered by `;` like every other body-from-contract.

### Outcomes ride the gate trichotomy

A metatype is a *compile-time* synthesizer, so its license is a compile-time
gate — there is no runtime to defer to:

- license **proved** + rule applies → clean synthesized result.
- license **unprovable** (e.g. `halts` undecided) → **compile error**. NOT `!!`.
- license **refuted** (arg provably lacks the property) → **compile error**.

`!!` is orthogonal and lives one level down: it governs the *value refinements
of the synthesized result's return type*, not the metatype synthesis itself.
`differential[f]` either synthesizes or it doesn't (compile-time); if the
function it produces has a return refinement the engine can't prove, *that
return* is `!!` — the usual value-level rule.

---

## The family (honest scope gradient)

| metatype | license | synthesis rule | scope for Pontif |
|---|---|---|---|
| `inverse[f]` | f `Reversible ∧ halts` | transpose the conservation ledger's slot←atom map | **REAL** — rides existing machinery |
| `differential[f]` | f built from differentiable prims (Decimal; no `%`, no int `/`) | expression-tree rewrite by power/sum/product/chain rules | **REAL** on the polynomial fragment; table-driven for known transcendentals |
| `integral[f]` | f has an elementary antiderivative | reverse power rule, etc. | **PARTIAL** — Risch-incomplete; many f have no closed form → synthesis fails honestly. Non-unique (`+C`, or definite via bounds) |
| `taylor[f]` | f differentiable to order N at a point | `Σ differentialⁿ[f](a)/n! · (x−a)ⁿ`, truncated | **DERIVED** (built on `differential`); an *approximation* → must type its remainder |
| `fourier[f]` | f a finite discrete sequence | the DFT (a matrix multiply) | **DFT-only.** The continuous transform is real analysis — out of scope; not forced into the algebraic mold |

`inverse` and `differential` are siblings: both are **structural synthesis from
f's own structure** — one transposes the dataflow graph, the other rewrites the
expression tree — both exact on their fragment, both algebra rather than
analysis. They are the buildable core. The rest get progressively more analytic;
capture the interface, don't over-promise the synthesis.

---

## Metatypes form an algebra

They are not a flat list — they have laws, and the laws are the point:

- `inverse[inverse[f]] = f` — involution.
- `differential ⊣ integral` — adjoint; the Fundamental Theorem of Calculus is
  `integral[differential[f]] = f (+C)`.
- `taylor[f]` is a series in `differentialⁿ[f]` — a metatype built from a metatype.

And the whole family is the **conservation principle lifted to function-space**
(`user_cott_conservation`): each metatype is a structure-preserving transform
carrying a *recovery law*. `inverse` is information conservation (a bijection
loses nothing, so it's recoverable). `differential`/`integral` are FTC — the
integral of the rate of change *recovers* the total change. So these aren't
five unrelated features; they're one conservation algebra over functions, and
the metatype interface is the lens that shows it.

---

## Honesty (the lens is not a cage)

- An **approximating** metatype types its error: `taylor[f]` cannot claim `== f`,
  it claims "approximates f to order N, remainder bounded by …". The no-lie law
  applies to approximation just as to refinement.
- Synthesis that **can't close** (an `integral` with no elementary form) is a
  compile error, never a fabricated antiderivative.
- The shared `M[arg]` interface is **descriptive**, opportunistic — not a mold
  every transform must fit. `fourier`'s continuous form does not fit Pontif's
  discrete-algebraic core and is not forced into it. The unification is a lens.

---

## Relationship to the build

`Type[…]` (the reusable-sort metatype) is the first brick. Building it lays the
`Metatype[…]` application + synthesis substrate that `inverse[f]`,
`differential[f]`, and the rest plug into — so it is not a one-off feature, it is
the foundation of the family. Build order: `Type[…]` → `inverse[f]` (structural,
on the existing ledger) → `differential[f]` (structural, on the expression tree).
`integral`/`taylor`/`fourier` wait for a real consumer and an honest scope.
