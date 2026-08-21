# The `docs/` directory

Three kinds of document live here, and they are written for different readers.
Start with the guide.

## 1. The guide — start here

Reader-facing tours, in reading order. Each one links down to the design notes
below it.

| Page | Covers |
| --- | --- |
| [guide/programs.md](guide/programs.md) | The shape of a `.ptf` file · `main` · namespaces across files · `requires` · projects |
| [guide/type-system.md](guide/type-system.md) | Refined dispatch · structs & methods · traits · type extension · the three polymorphism models · generics · operator overloading |
| [guide/proofs-and-ledgers.md](guide/proofs-and-ledgers.md) | `assign proof` · synthesis `;` · algebraic reflection · conservation receipts · the one inference engine |
| [guide/notation.md](guide/notation.md) | The braces/brackets/parens grid and the univocal `->` |
| [guide/streams.md](guide/streams.md) | One iteration primitive → map / filter / fold / scan / fork / zip |
| [guide/effects.md](guide/effects.md) | `emit` + `action` · the builtin math library |
| [guide/graphics.md](guide/graphics.md) | Native GUI · plotting · SDF shapes · `on Gpu` compute kernels |
| [guide/architecture-and-craft.md](guide/architecture-and-craft.md) | The compiler pipeline · why GraalVM · the IR seam · the source-tree map |

## 2. Reference

Look things up here.

- [language-reference.ptf](language-reference.ptf) — the annotated canonical
  syntax, organized around the load-bearing principles. The closest thing to a
  spec.
- [glossary.md](glossary.md) — every invented or reframed term, with a note on why
  each name differs from the conventional one.
- [backward-language-design.md](backward-language-design.md) — the construction
  method the whole project follows: implement from the execution layer upward, and
  let each design question descend to the deepest layer with jurisdiction.
- [feature-matrix.md](feature-matrix.md) — feature-by-feature implementation state.
- [TODO.md](TODO.md) — the running work list.

## 3. Design notes

**These are working documents, not documentation.** They are where a feature was
argued out — often at length, often before it was built, sometimes recording a
direction later abandoned. They are the right place to understand *why* a design
came out the way it did, and the wrong place to learn how to use it. Where a design
note and the guide disagree, the guide is current.

- **Type system** — [dependent-sorts](dependent-sorts.md),
  [subtypes](subtypes.md), [traits](traits.md),
  [type-parameters](type-parameters.md), [associated-types](associated-types.md),
  [sort-transforms](sort-transforms.md), [metatypes](metatypes.md),
  [type-records](type-records.md), [inference-unification](inference-unification.md),
  [dispatch-unification](dispatch-unification.md),
  [cross-module-dispatch](cross-module-dispatch.md),
  [type-system-roadmap](type-system-roadmap.md)
- **Proof and conservation** — [receipt-graph](receipt-graph.md),
  [receipt-graph-refinement](receipt-graph-refinement.md),
  [receipt-graph-overhaul](receipt-graph-overhaul.md),
  [conservation-receipts](conservation-receipts.md),
  [conservation-algebra](conservation-algebra.md),
  [numeric-discharge](numeric-discharge.md)
- **Iteration and data** — [streams](streams.md), [stream-war](stream-war.md),
  [stream-queries](stream-queries.md), [indexed-streams](indexed-streams.md),
  [iteration](iteration.md), [keyed](keyed.md), [strings](strings.md)
- **Effects, state, and concurrency** — [events](events.md), [actions](actions.md),
  [orchestration](orchestration.md), [mvcc-state](mvcc-state.md),
  [reactive-gui](reactive-gui.md)
- **Graphics and GPU** — [shapes](shapes.md), [sdf-glsl](sdf-glsl.md),
  [gradient-normals](gradient-normals.md), [gpu-kernels](gpu-kernels.md),
  [plotting](plotting.md), [reliable-plotting](reliable-plotting.md)
- **Notation** — [brace-aggregates](brace-aggregates.md), [arrows](arrows.md),
  [univocal-arrows](univocal-arrows.md),
  [univocal-language-design](univocal-language-design.md),
  [univocal-implementation-plan](univocal-implementation-plan.md)
- **Mathematics** — [projective-rational-algebra](projective-rational-algebra.md),
  [differential-endofunctors](differential-endofunctors.md)
- **Tooling and internals** — [extensions](extensions.md),
  [editor-navigation](editor-navigation.md), [link-provenance](link-provenance.md),
  [language-inventory](language-inventory.md),
  [parser-linker-refactor](parser-linker-refactor.md),
  [recursive-union-typecheck-blowup](recursive-union-typecheck-blowup.md)

[archive/](archive/) holds superseded notes, kept for the record.

---

Documentation in this directory is licensed [CC BY
4.0](https://creativecommons.org/licenses/by/4.0/); see `LICENSE-docs` at the repo
root.
