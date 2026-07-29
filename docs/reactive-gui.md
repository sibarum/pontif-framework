# The reactive GUI — the conduit, and pontif.gui on it

Status: **LANDED (2026-07-28).** The conduit substrate (commit `feat(events): conduits`) and the
first interactive `pontif.gui` window (commit `feat(gui): reactive pontif.gui`) are on `master`.
This doc describes the design as built; it supersedes an earlier draft that framed the loop as
"TEA-in-native" / signals (see **Rejected approaches**). Read alongside
[`docs/events.md`](events.md) (the emit/conduit/action model), [`docs/extensions.md`](extensions.md)
(how native side-effects register), and [`docs/stream-war.md`](stream-war.md) (`scan`).

---

## 0. Thesis

The interactive GUI is **not** a feature bolted onto a finished event substrate — it is the
consumer that **completes** it. The reactive loop is expressed entirely in `emit` / `conduit` /
`action`, with the application model living in Pontif as an immutable value (a conduit's threaded
state). Nothing calls back into Pontif from Java; no app state lives in a Java cell.

The load-bearing realization: **a conduit is `scan` over the temporal stream of a type's events.**
events.md already ruled that "all instances of an Event type, over time" *is* a stream and the
conduit is its iterator; pinning that iterator to `scan` is what made the whole thing fall out.

---

## 1. The event substrate as built

Three stages (events.md), plus trait-aware routing:

- **`emit VALUE`** — a write-only statement, usable even in pure code. It accepts **any value** (no
  `Event`-trait requirement). An emitted value with no matching conduit/action/sink is a **silent
  no-op** (there is deliberately no fail-closed guard; the former `EventEmitCheck` pass is retired).
  Isolation is achieved by using a distinct type hierarchy, not by a marker trait.
- **`conduit`** — the stateful `scan` between emit and the actions (see §2). Optional: with none,
  the event goes straight to the actions (the pre-conduit path, unchanged).
- **`action NAME(e:Sort) -> body`** — a for-effect reaction; its parameter sort is the match
  filter; one event fans out to all matching actions in declaration order.

**Trait-aware routing** (`CompiledModule.actionsMatching` / `conduitsMatching`, backed by
`TraitRegistry.satisfiesBareTrait`): an emitted value matches consumers keyed on its concrete type
**or any ancestor trait** (transitively). So `emit Click` fires `action onAnyGui(e:GuiEvent)` when
`Click` is-a `GuiEvent`, and — crucially — a single umbrella conduit on `GuiEvent` folds every
subtype emit into one model. Actions **fan out** (all matching fire); a conduit is **singular** per
event (see §2).

Cost is O(distinct consumer buckets a type belongs to) per emit, not O(all consumers).

---

## 2. The conduit

```
conduit NAME(e:E, s:S):{E, S} from INIT -> BODY
```

A `scan` over the type-`E` event stream. On each emitted E (or subtype, matched by ancestry) it
folds the current state `S` and returns `{dispatched-event, new-state}`:

- **slot 1 — the dispatched event.** Must be the **same type `E`** (transform the event's *data*,
  not its type) **or `Nothing`** (drop the event — no action fires — the lossy-filter face of scan).
  Enforced at fold time. To change the event *type*, **re-emit** a new event from `BODY`
  (`emit …`), which routes independently — this keeps conduits cascade-free.
- **slot 2 — the new state `S`**, threaded to the next event. `from INIT` seeds it.

**`:S` sugar** — `conduit NAME(e:E, s:S):S from INIT -> BODY` means "pass the event through
unchanged, return just the new state" (desugars to `{e, BODY}`). The common case.

**One conduit per event type**, matched by concrete type or ancestor (so the umbrella-conduit
pattern works). A second conduit for the same type is a compile error. When an event matches
*several* conduits across a hierarchy (a conduit on `A` **and** `B`), the intended semantics is an
ordered pipeline (ancestry least→most specific, each stage feeding the next) — **deferred**; today
2+ matches is a runtime error.

**Naming convention** (unenforced): **past-tense = a notification** ("it happened", e.g. `Clicked`,
`Counted`); **imperative = a command** ("cause it", e.g. `Draw`, `Save`).

Lowering: the parser (`AltParser.parseConduit`) lowers a conduit to two synthetic `FunctionDecl`s
(`#conduit#…` fold + `#conduit-init#…` seed, mirroring `#action#`); `IrCompiler` pairs them into a
`CompiledModule.CompiledConduit`; `IrInterpreter` holds a persistent per-conduit `conduitState` cell
and runs `foldThroughConduit` inside `fireEvent`, threading `S` and dispatching the result.

---

## 3. The reactive GUI loop

`pontif.gui` types: `trait GuiEvent` (umbrella), `struct Clicked(id)` (notification),
`struct Draw(tree)` (render command).

```
window opens rendering view(initialModel)
  → Button click  → GuiTree.wireInput fires  ctx.fireEvent(Clicked{id})
  → the GuiEvent conduit folds the model, and re-emits  Draw(view(model2))
  → the Draw native effect (GuiExtension.effects) → GuiTree.publish stashes the tree
      + dasum Invalidator.invalidate()
  → the window loop (openWindowReactive) rebuilds the dasum component tree from the latest
      published tree on the next dirty frame, and repaints
```

- **Source:** the click callback (`wireInput`) runs on the root thread during the event loop, so
  `ctx.fireEvent(Clicked)` → conduit → re-emitted `Draw` → the sink all execute on the root thread.
  GL-safe with no scheduler (single-threaded v1).
- **Sink:** a `NativeFunctions.Effect` gets `(RecordValue, Origin)` — **no `ctx`** — so it must not
  build components. It stashes the raw Pontif tree + invalidates; the **loop** (which holds `ctx`)
  rebuilds via `toComponent(tree, ctx)` on the root thread. Reuses dasum's `Invalidator`/`EventLoop`
  — **no dasum changes**.
- Example: [`pontif-builtin-gui/examples/reactive-counter.ptf`](../pontif-builtin-gui/examples/reactive-counter.ptf).

**Identity-across-re-render hazard (fixed):** a repaint swaps the whole Component tree, but dasum's
hover/hit-test state (`HoverState`) refreshes only on cursor *move*. The fix: `wireInput`'s **press**
handler hit-tests the *live* tree at the cursor (as release already did) instead of the stale hover
cache — so a click registers even when a prior click repainted and the mouse hasn't moved. The same
hazard governs caret/focus/scroll (dasum keys those by Component identity); Slice 3's widget-`id`
scheme is how that persists across rebuilds.

---

## 4. Rejected approaches (do not re-derive)

- **TEA-in-native** — the `window` native holds the model and calls back into Pontif
  (`view(model)`, `update(e,m)`) each event. Rejected: puts the loop in Java and needs a general
  "apply a Pontif function value from a native" seam (`NativeCalls.Context` has no such thing). The
  substrate's `conduit` runs `view`/`update` as ordinary Pontif via the interpreter's own call path
  — no such seam is needed.
- **App state in a dasum `Property`** — an action mutates a Java cell; `Property.set` repaints.
  Rejected as the end-state: it leaks the model out of Pontif's value world. The model is a conduit's
  threaded `S` (a Pontif value).
- **Conduit output changing the event type / re-entering the conduit machinery** — ruled out to
  avoid cascade edge cases; type-change is an explicit `emit` instead (§2).

---

## 5. Built vs. next

**Built:** the conduit (scan, same-type-or-drop, `:S` sugar, ancestry match, re-emit for
type-change); emit-any-value/no-op; trait-aware routing; the reactive `pontif.gui` loop
(`Clicked` source, `Draw` sink, `openWindowReactive` host) + the press-hit-test fix; the headless
data-flow test + the hand-verified counter window.

**Next — Slice 3 (input elements):**
- A stable **widget-`id` scheme** — today a plain `Button` uses its *text* as `Clicked.id`, so
  identical labels collide; needed before dynamic lists (which would mint colliding ids) and for
  focus/caret persistence (key dasum's identity-scoped `TextStates` by the Pontif id).
- `TextField(id, text)` + a `TextChanged` notification (dasum's editable-text plumbing already
  exists) — unlocks the multi-expression **calculator**.
- `Row`, `Checkbox` (dasum `Ui.row()` exists). Dynamic lists fall out of `view` mapping over the
  model — no list element needed.

**Deferred:** the multi-conduit ordered pipeline (§2); a backstop for infinite re-emit loops (a
rate-limited/clocked conduit is a possible future distinct semantic); the scheduler / worker-thread
story (events.md Slice 2) for off-root-thread emits.

---

## 6. For a fresh instance

Keep app logic (`view`, `update`, routing) in Pontif; keep GL and the frame loop in dasum; keep the
bridge thin (a click source + a render sink). If you find yourself wanting to apply a Pontif function
from a native, or to hold the model in a Java cell, stop — the loop drifted out of the substrate
(§4). Start Slice 3 with the widget-`id` scheme (a correctness prerequisite), then `TextField`, then
rebuild the calculator on the reactive substrate.
