# Link provenance: resolution should respect the module boundary, not reconstruct it

**Status: WAR DECLARED — scouting done, awaiting the direction call (branch
`war/link-provenance`, 2026-06-19).** Successor to the cross-module-dispatch war
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
no-op (it has no scope and must not re-gate). Slice 3 verifies this and, if the
re-run is purely defensive, considers dropping it. The single-file path (no
`requires`) resolves with an all-visible scope, exactly as today's `null` table.

---

## 4. Slice plan (vertical, end-to-end each)

Per the rewrite rule (`feedback_vertical_slices`): each slice compiles and is green.

- **Slice 1 — make the dependency explicit (behavior-preserving).** Introduce a
  `ModuleScope` value (own module name + the set of visible decl keys + import
  facts) and have `MethodOperatorResolver` take it explicitly, replacing the
  `currentModule` mutable field and the raw `table`. For a linked compile, build the
  scope from the existing table (same answers as today); for single-file, an
  all-visible scope (== today's `null`). No behavior change; the probes stay green.
  This isolates the smell behind one seam.
- **Slice 2 — resolve per module, before concatenation.** In `combineWithTable`,
  run the resolver on each module with its own `ModuleScope` *before* concatenating,
  so the combined module is emitted already-resolved. Drop the post-link
  `resolve(module, table)` call's reliance on the table. Visibility now holds by
  construction; the migration error moves to scope construction.
- **Slice 3 — delete the downstream table plumbing.** Remove the `table` parameter
  from `compileModule`/`resolve`'s post-link path; confirm `IrCompiler`'s re-run is
  a genuine no-op (and drop it if purely defensive). `SortChecker`/gates/runtime
  unchanged. Confirm `§5.7` of the dispatch doc (no file-init-order dependence)
  still holds with resolution moved earlier.

Probe meter throughout: the `dispatch__*` / `traits__*` cross-module probes and the
Step B migration probes are the regression meter — all must stay green, none
suppressed (R1 from the predecessor war still binds).

---

## 5. WAR markers (cut sites)

Marked in-code with `WAR(link-provenance)` pointing here:

- `MethodOperatorResolver.java` — the `currentModule` mutable field (`:56-58`) and
  its FQN-reconstruction (`:122`, `:100`); `isVisibleHere`/`ownsOrImports`
  (`:301-319`) become scope membership.
- `ModuleLinker.java` — `combineWithTable` (`:89`) is where per-module resolution
  moves in; `LinkResult.table` (`:47-54`) stops being a downstream output.
- `PontifCompiler.java` — `compileModule(.., table)` (`:222`) loses the table once
  Slice 3 lands.

---

## 6. The decision (James)

The plan above assumes **Option A** at **bold-but-bounded** ambition. Two things to
confirm before Slice 1:

1. **Option A vs C.** A moves resolution before the link (dissolves the round-trip,
   slightly larger); C stamps provenance on statements and keeps resolution post-link
   (smaller, papers it). I recommend A.
2. **Is the smell mechanical or philosophical?** My read (§1) is that declaration
   provenance is *not* actually lost — it is in the FQN — so the objection is really
   to the stateful reconstruction + side-table mechanics, which A removes cleanly. If
   your objection is deeper (the flatten-then-resolve *philosophy* itself, even done
   tidily), say so — that would push toward B (never flatten), and I should not
   assume it away.
