# Link provenance: resolution should respect the module boundary, not reconstruct it

**Status: WAR COMPLETE — Option A delivered (James, 2026-06-19); Slices 1–3 all
landed (branch `war/link-provenance`).** Successor to the cross-module-dispatch war
(`docs/cross-module-dispatch.md`), whose Step B got cross-module visibility working
but did it by *re-threading* module context into a post-link pass. This war removes
the workaround by making module scope structural.

This doc is the breadcrumb: the smell (ground truth from the code), an **honest
scope assessment** (the footprint is more contained than "war" suggests — read §1
before swinging), the options weighed, a recommendation, and a sliced plan. The
one open decision is in §6 — James's call.

---

## 0. The smell (ground truth, 2026-06-19)

`ModuleLinker.combine` flattens every module into ONE FQN-keyed `IrModule` — the
statements of all modules concatenated, the entry module's `main`
(`ModuleLinker.java:110-131`). The javadoc is explicit that this discards
structure: the `ModuleSymbolTable` "carries the per-module ownership + import facts
the combined (flat, FQN-keyed) module no longer exposes" (`ModuleLinker.java:47-54`).

Resolution then runs **post-link, over the flat module** (`MethodOperatorResolver`,
invoked from `PontifCompiler.compileModule:235` and `IrCompiler.compile:49`). To
make the import-by-association visibility gate work there, Step B had to reconstruct
the two things the flatten threw away:

- **"Which module am I in?"** — re-derived per-decl by parsing the FQN back out into
  a **mutable field**: `currentModule = QualifiedName.parse(fd.name()).module()`
  (`MethodOperatorResolver.java:122`), with `main` special-cased
  (`:100`). Lambdas/nested exprs inherit the field implicitly.
- **"What did that module import?"** — read from the `ModuleSymbolTable`, **threaded
  as a side channel** in parallel to the flat module: `combineWithTable` →
  `LinkResult.table` → `compileModule(.., table)` → `resolve(module, table)` →
  `isVisibleHere`/`ownsOrImports` (`:301-319`).

So the front-end flattens away module structure, then a downstream pass rebuilds it
from name-parsing plus a side table. That round-trip is the smell.

---

## 1. Honest scope (read before swinging)

The "per-module provenance is gone" framing oversells it. Measured against the code:

1. **Declaration provenance is NOT lost.** Each decl's owning module is recoverable
   losslessly from its FQN (`module/localKey`). The `currentModule` mutable field is
   a *code* smell (stateful reconstruction), not information loss.
2. **Only import-set scope is genuinely off the flat module.** That is what the side
   table carries, and it is the only thing the visibility gate truly needs.
3. **The blast radius is contained.** The cross-module *checks* already run
   **pre-link, module-scoped** on the `Map<String,IrModule>` with the table —
   `CoherenceCheck`, `ModuleImportCheck`, `CoercionCheck.validateOrphans`
   (`ModuleLinker.java:103-108`). Downstream of the link, `SortChecker`,
   overload-overlap, and the return gate operate on **globally-unique FQN keys** and
   need no import scope. The *only* post-link consumer that needs module scope is the
   visibility gate inside `MethodOperatorResolver`.

Conclusion: the front-end is already half module-scoped (the checks) and half flat
(resolution). The fix is to make *resolution* module-scoped too — bounded, not
sprawling. This is a focused architectural correction with one real design choice,
not a rewrite of the linker or the IR. Scope honestly; don't over-build.

### Footprint inventory (every site that touches the workaround)

| File | Role | Touched? |
|------|------|----------|
| `runtime/module/ModuleLinker.java` | flattens; builds + returns the table | **yes** — where resolution would move |
| `ir/MethodOperatorResolver.java` | `currentModule` field, `isVisibleHere`, `ownsOrImports`, `notImportedError` | **yes** — the core change |
| `runtime/PontifCompiler.java` | threads `table` into `compileModule`/`resolve` | **yes** — plumbing removed/changed |
| `runtime/module/ModuleResolver.java` | single-file demand link → `combineSingleWithTable` | maybe — same entry points |
| `ir/IrCompiler.java` | re-runs `resolve` (no table, no-op on resolved IR) | maybe — see §3 wrinkle |
| `ir/ModuleSymbolTable.java` | the index (rich, well-shaped) | **no change** — becomes resolution input |
| `ir/NameResolver`, `CoherenceCheck`, `ModuleImportCheck`, `CoercionCheck` | already pre-link/module-scoped | **no change** |
| `SortChecker`, overload-overlap, return gate, runtime | FQN-keyed, scope-blind | **no change** |

---

## 2. Options weighed

| Option | Model | Blast radius | Verdict |
|--------|-------|--------------|---------|
| **A — Resolve before link (module-scoped resolution)** | Run `MethodOperatorResolver` per module, each in its own scope (own decls ∪ imported-by-association decls), *then* concatenate already-resolved statements. The flat module is a pure post-resolution artifact. | `ModuleLinker` (resolve step moves in), `MethodOperatorResolver` (takes a scope, drops the mutable field + table), `PontifCompiler`/`IrCompiler` (no downstream table). | **Recommended.** Dissolves the round-trip: visibility is correct *by construction* (you cannot route to what you did not import). Makes the whole front-end uniformly module-scoped, matching the checks (§1.3). |
| **B — Linked-program type carrying module groups** | Replace the flat `IrModule` with `LinkedProgram = [(module, importSet, statements)]`; every downstream pass iterates scope-aware. | Every consumer of `module.statements()` — `SortChecker`, gates, runtime, IrCompiler. Large. | **Set aside.** Over-built: only resolution needs scope; forcing the scope-blind back-end to carry it buys nothing and breaks the "back-end doesn't care about modules" simplicity. |
| **C — Provenance stamped on the statement** | Add owning-module + import-scope ref to `IrStmt`/`FunctionDecl`; resolver reads it instead of FQN-parsing + mutable field. Keeps post-link resolution. | `IrStmt`/`FunctionDecl` records, `MethodOperatorResolver`, the builders that emit them. | **Fallback.** Removes the mutable-field smell but keeps resolution post-link and the table alongside — papers the round-trip rather than removing it. Smaller, less principled. |

---

## 3. Recommendation — Option A, and why

Resolution is the one pass that asks "what can I see from here?" — a question that
only has a clean answer *inside* a module's scope. Running it post-link forces the
question into a namespace where the answer was deliberately erased, so it gets
reconstructed. Move resolution to where the answer is still present and the question
answers itself: a module can only be handed its own + imported-by-association decls,
so an un-imported overload is not *rejected* — it is simply **not in scope**. The
`notImportedError` migration message becomes a property of scope construction, not a
post-hoc visibility test.

This also leaves the back-end honestly module-blind: `SortChecker`, the gates, and
the runtime keep operating on one flat FQN-keyed module, which is correct — they
resolve by globally-unique key and have no business knowing about modules. We are
not making the back-end scope-aware (that is option B's mistake); we are making the
*front-end* uniformly scope-aware.

**Known wrinkle — `IrCompiler` re-runs `resolve`.** Today `IrCompiler.compile:49`
re-runs `MethodOperatorResolver` (a no-op on already-resolved IR). Under A, the flat
module reaching `IrCompiler` is already resolved, so the re-run must stay a true
no-op (it has no scope and must not re-gate). **Slice 3 resolution: kept, not
dropped** — the re-run is *not* purely defensive: it is the real resolution pass for
the bare single-file path (no `requires`, never linked), and a genuine no-op
(unrestricted scope) on already-linked input. The single-file path resolves with an
all-visible scope, exactly as the old `null` table.

---

## 4. Slice plan (vertical, end-to-end each)

Per the rewrite rule (`feedback_vertical_slices`): each slice compiles and is green.

- **Slice 1 — make the dependency explicit (behavior-preserving). DONE (2026-06-19).**
  Introduced `ModuleScope` (`ir/ModuleScope.java`): own module + the symbol table,
  exposing `restricts()` and `ownsOrImports(typeFqn)` — the old `ownsOrImports`
  logic relocated behind one type. `MethodOperatorResolver` now holds a
  `currentScope` (set per-decl via `scopeFor`) instead of the `currentModule`
  mutable field; `isVisibleHere`/`notImportedError` route through it. The unrestricted
  scope reproduces the old `table == null || currentModule.isEmpty()` short-circuit
  exactly. Green: 765 pontif-runtime tests + the full pontif-ir suite, all
  cross-module dispatch/traits + migration probes unchanged. The smell is now behind
  one seam, ready for Slice 2 to move resolution per-module.
- **Slice 2 — resolution moves into the link, gated per-module. DONE (2026-06-19).**
  `MethodOperatorResolver.resolvePerModule` resolves the combined module with a
  *fixed* `ModuleScope` per owning module (grouped by FQN, set once — no per-decl
  reconstruction), against the *full combined registry* (the migration error must
  see overloads that exist but aren't imported — proven by the `(mk()+mk()).x` on an
  unimported `Vec` case). `ModuleLinker.combineWithTable` calls it last (after struct
  literals are Records and destructures resolved), making the linker the **sole
  visibility gate**; `compileModule` dropped the `table` parameter and now resolves
  unrestricted (a no-op re-run on linked input; the real pass for bare single-file).
  Result is identical to the old post-link gated pass — the change is structural:
  the table is consumed *during* the link, not threaded downstream. Green: 255
  pontif-ir + 765 pontif-runtime tests, `CrossModuleVisibilityTest` both cases,
  all dispatch/traits probes unchanged.

  *Deliberate scope note (honest-scope):* `StructLiteralRewriter` and
  `DestructureResolver` keep running on the combined module — they are FQN-keyed and
  have no visibility concern, so forcing them per-module would be over-reach for zero
  debt reduction. "Before concatenation" was relaxed to "during the link, grouped by
  module" because the only thing needing module scope is the resolver's gate.
- **Slice 3 — delete the downstream table plumbing. DONE (2026-06-19).**
  Removed every `*WithTable` variant and the `LinkResult` record: `ModuleLinker`
  exposes only `combine`/`combineSingle` (the table is built and fully consumed
  *inside* the link — coherence/import/coercion checks + `resolvePerModule`, the
  gate); `ModuleResolver.resolveAndCombine` and `PontifCompiler.compileFromSource`/
  `compileProject` thread a plain `IrModule`, never a side table.
  `MethodOperatorResolver` dropped its `table` field, the `fixedScope` flag, the
  per-decl `scopeFor` reconstruction, and the table-carrying `resolve(..)`
  overloads — `currentScope` is now caller-owned (unrestricted for the whole-module
  paths, per-module inside `resolvePerModule`). `IrCompiler`'s `MethodOperatorResolver.resolve`
  re-run stays: it is *not* purely defensive — it is the real resolution pass for
  the bare single-file path (no `requires`, never linked), and a genuine no-op
  (unrestricted scope, no re-gating) on already-linked input. `SortChecker`/gates/
  runtime unchanged. Green: 765 pontif-runtime tests + the full pontif-ir suite,
  all dispatch/traits cross-module + migration probes unchanged.

Probe meter throughout: the `dispatch__*` / `traits__*` cross-module probes and the
Step B migration probes are the regression meter — all must stay green, none
suppressed (R1 from the predecessor war still binds).

---

## 5. WAR markers (cut sites — all resolved)

Every cut site is closed. The remaining in-code `WAR(link-provenance)` comments are
now *descriptive* (they document where the sole visibility gate lives and why the
table is not threaded), not open TODOs:

- `MethodOperatorResolver.java` — the `currentModule` mutable field, its
  FQN-reconstruction, the `fixedScope` flag, `scopeFor`, and the table-carrying
  `resolve(..)` overloads are **gone**. Visibility is `currentScope` (a
  `ModuleScope`), caller-owned: unrestricted on the whole-module paths, set
  per-module inside `resolvePerModule`.
- `ModuleLinker.java` — `combine` builds the table and consumes it within the link
  (checks + `resolvePerModule`); `LinkResult`/`*WithTable` are **deleted** — nothing
  downstream re-threads it.
- `PontifCompiler.java` / `ModuleResolver.java` — the post-link `table` plumbing is
  **removed**; both thread a plain `IrModule`.

---

## 6. The decision (James) — RESOLVED 2026-06-19

1. **Option A vs C → A.** James chose A: resolution moves into the link, gated
   per-module, dissolving the round-trip rather than papering it.
2. **Mechanical, not philosophical.** The objection was to the stateful
   reconstruction + side-table mechanics (§1), which A removes cleanly. The
   flatten-then-resolve shape stays (the back-end is honestly module-blind); only
   the *front-end* became uniformly module-scoped. B (never flatten) was not needed.

All three slices landed on `war/link-provenance`. The war is closed.
