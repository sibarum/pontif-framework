# The reactive GUI — the conduit, and pontif.gui on it

> **The loop below is live; the widgets it is written against are not.** As of 2026-08-26 the
> `pontif.gui` in this document — `Label`, `Button`, `TextField`, `Column`, `ExprPlot`, `Clickable`
> — is deleted, replaced by Anybox on VexelRay ([`docs/anybox.md`](anybox.md)). What carried over
> unchanged is everything this doc is actually about: notification → conduit → command, isolated
> id-addressed updates, a tree walked once, and uncontrolled text fields. Read the widget names as
> the shape of the argument rather than the current API. The status ribbon (§`Status`) and the
> retained expression plot (`ExprPlot`/`SetPlot`) have not been rebuilt yet.

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

Lowering: the parser (`PontifParser.parseConduit`) lowers a conduit to two synthetic `FunctionDecl`s
(`#conduit#…` fold + `#conduit-init#…` seed, mirroring `#action#`); `IrCompiler` pairs them into a
`CompiledModule.CompiledConduit`; `IrInterpreter` holds a persistent per-conduit `conduitState` cell
and runs `foldThroughConduit` inside `fireEvent`, threading `S` and dispatching the result.

---

## 3. The reactive GUI loop — build once, isolated updates

**dasum is retained-mode and fragile: build the tree ONCE, then make ISOLATED updates through the
event system.** Do NOT rebuild the tree per event, and do NOT build a reconciler/diff — both are
off-script for dasum and invite its threading / render-order / identity gremlins (dasum keys
caret / selection / scroll / undo / highlight on Component IDENTITY via `IdentityHashMap` stores; a
rebuild orphans all of it, and dasum has no reconciliation to make a rebuild cheap or
identity-preserving). This is the pattern the editor and every dasum app use.

`pontif.gui` types: `trait GuiEvent` (umbrella), `struct Clicked(id)` (notification),
`struct SetText(id, text)` (an isolated update command). Widgets carry a stable `id` — the address
for updates and for a `Button`'s `Clicked`.

```
window(cfg, tree) builds the retained id'd widget tree ONCE (a build-time id → Component registry)
  → Button click → GuiTree.wireInput fires ctx.fireEvent(Clicked{id})
  → the GuiEvent conduit folds the model, and emits a TARGETED command  SetText("count", …)
  → the SetText native effect (GuiExtension.effects) → GuiTree.setText looks the widget up by id
      and mutates it via dasum TextStates.setContent (identity-keyed) + invalidate
  → the loop repaints on the next dirty frame — the SAME retained tree, one widget changed
```

- **Source:** the click callback (`wireInput`) runs on the root thread during the event loop, so
  `ctx.fireEvent(Clicked)` → conduit → the emitted `SetText` → the sink all execute on the root
  thread. GL-safe, no scheduler (single-threaded v1).
- **Update sink:** a `NativeFunctions.Effect` gets `(RecordValue, Origin)` — no `ctx`, and it never
  builds components. It addresses an already-built widget by id and mutates dasum's own state store
  (`TextStates.setContent`, which invalidates). Future commands (`SetChecked`, `SetChildren` via
  `DynamicChildren`, …) register alongside it. Reuses dasum's `Invalidator`/`EventLoop` — **no dasum
  changes** (and if a hard capability is ever missing, the rule is to **extend dasum** with a
  first-class tested widget, not to work around it in the bridge).
- To change what's shown structurally (not just text), emit the matching targeted command; there is
  no whole-tree re-render.
- Example: [`pontif-builtin-gui/examples/reactive-counter.ptf`](../pontif-builtin-gui/examples/reactive-counter.ptf).

**Click-after-update fix:** `wireInput`'s **press** handler hit-tests the *live* tree at the cursor
(as release already did) rather than the move-only `HoverState` cache — so a click registers even
when a prior click just repainted and the mouse hasn't moved (the identity-across-repaint hazard at
the input layer; correct regardless of render strategy).

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
- **Whole-tree rebuild (`view(model)` → replace the dasum tree each event).** An early GUI slice did
  this (a `Draw(tree)` sink + a loop that rebuilt from the latest tree). Rejected after checking the
  editor: dasum is retained-mode and discarding component identity every event orphans its
  identity-keyed caret/scroll/selection/undo state (breaks the moment `TextField` exists), and dasum
  has no reconciliation to make it cheap. Replaced by build-once + isolated updates (§3).
- **A custom reconciler/diff over dasum.** Would make `view(model)` rebuild efficient and
  identity-preserving, but it is off-script for a fragile toolkit we'd then be fighting; the safe,
  tested path is dasum's own retained + targeted-update model. If something is hard, extend dasum.

---

## 5. Built vs. next

**Built:** the conduit (scan, same-type-or-drop, `:S` sugar, ancestry match, re-emit for
type-change); emit-any-value/no-op; trait-aware routing; the retained reactive `pontif.gui` loop —
`Clicked` source, build-once id'd tree with an `id → Component` registry, `SetText` isolated-update
sink (`GuiTree.setText` via dasum `TextStates`), the press-hit-test fix; the headless data-flow test
+ the hand-verified counter window.

**Slice 3 (input elements), all on the isolated-update model:**
- ✅ **LANDED** — `TextField(id, text)` + a `TextChanged(id, text)` notification. Built exactly per
  §7: an editable `Component.Text` (`withEditable(true)`), registered as the final fluent instance,
  `TextStates.onContentChange` → `fireEvent(TextChanged)`, the two-field `element(...)` overload, and
  the `wireInput` press-branch focus fix (`FocusState.set` on an editable-Text hit) — without which
  keystrokes were silently swallowed. Input state (caret/selection) lives in dasum's identity-keyed
  `TextStates` on the retained component, preserved for free (no rebuild). Uncontrolled: the field
  owns its buffer; the conduit drives *other* widgets. Verified: `examples/reactive-textfield.ptf`
  (type→echo into a separate Label, hand-run) + two headless tests (type-check + the `TextChanged`
  data-flow fold) in `GuiExtensionTest`. Next sub-slice toward the multi-expression **calculator**:
  wire `TextChanged` → `ExprParser` → `SetText` a result `Label`.
- `Checkbox(id, on)` + `Toggled(id, on)`; `Row` (dasum `Ui.row()` exists).
- Dynamic lists: a `SetChildren(id, elements)` command over dasum `DynamicChildren` (mutate a
  retained container's child list in place) — the dasum-blessed way to change structure.

**Deferred:** the multi-conduit ordered pipeline (§2); a backstop for infinite re-emit loops (a
rate-limited/clocked conduit is a possible future distinct semantic); the scheduler / worker-thread
story (events.md Slice 2) for off-root-thread emits.

---

## 6. For a fresh instance

Keep app logic (`update`, routing) in Pontif; keep GL, the frame loop, AND all retained widget state
in dasum; keep the bridge thin (a click source + isolated-update sinks). The cardinal rule: **build
the dasum tree once, update it through isolated event-driven commands — never rebuild it, never diff
it.** If you find yourself wanting to apply a Pontif function from a native, hold the model in a Java
cell, rebuild the tree, or write a reconciler, stop — you've gone off-script (§4). If a GUI need
doesn't map cleanly onto dasum's blessed API, extend dasum with a first-class capability rather than
hacking the bridge. Start Slice 3 with `TextField` on the isolated-update model (§7), then the calculator.

---

## 7. Slice 3 hand-off — `TextField` (design + technical approach)

The next build. Grounded in the dasum editable-text API (verified 2026-07-29); file refs are into
`dasum-gui-shi` unless noted.

### Shapes
- Element: `struct TextField(id:String, text:String)` — `text` is the initial content. (Exported;
  built in `GuiTree.toComponent` like `Label`.)
- Notification: `struct Clicked`-sibling `struct TextChanged(id:String, text:String)` assigned
  `GuiEvent` (so the one app conduit folds it via ancestry). Past-tense = notification (§ naming).

### Decision: UNCONTROLLED input (the field owns its text)
The `TextField` owns its buffer; dasum manages caret/selection. On edit it fires
`TextChanged(id, text)`; the conduit folds that into the model and drives *other* widgets
(`SetText` on a result label), but does **not** write the field back. Rationale:
- It's dasum's natural mode — editable text lives in the `TextState` sidecar keyed by the component.
- Controlled (round-trip each keystroke → `SetText` back into the field the user is typing in) is
  *technically survivable* — `TextStates.setContent` is safe on a focused field, it **clamps**
  caret/selection to the new length rather than clobbering (`input/TextStates.java` setContent) —
  but `setContent` doesn't reposition the caret to the edit site, and `onContentChange` fires on
  programmatic writes too (feedback risk; see below). So avoid it for the field being edited.
- Programmatic `SetText` on a TextField is still fine for *non-editing* moments (a "Clear" button,
  seeding a non-focused field) — the clamp makes it safe. Just don't drive the actively-typed field.

### Technical approach (concrete)
1. **Build an editable `Component.Text`.** No `Ui.textField` builder exists; editability is boolean
   fields on the record. Cleanest: `new Component.Text(text, Em.of(2f), TEXT).withEditable(true)` —
   `withEditable(true)` forces `interactive`+`selectable`+`editable` on (`component/Component.java`
   `withEditable`), and leaves `acceptsTab=false` (the 3-arg default), which is what you want for a
   single-line-ish field (Tab then cycles focus instead of inserting `\t`). NOTE there is no
   single-line *type* — `onEnter` inserts `\n` and nothing intercepts it; a single-expression field
   just reacts live and ignores newlines. There is **no submit/commit hook** — react to
   `TextChanged` live (debounce in the conduit if needed), the way `plotInput` re-evals on the fly.
2. **Register it** in the `GuiTree` widget registry (`register(id, field)`) — the SetText/address path.
3. **Wire the change notification:** `TextStates.onContentChange(field, s -> ctx.fireEvent(
   element("pontif.gui/TextChanged", "id", id, "text", s)))`. Capture `ctx` (as the Button's onClick
   does). **Register against the FINAL Text instance** — every `withX` returns a *new* record, and
   `TextStates`/`FocusState` are identity-keyed, so register after any fluent chain. (Confirm
   `element(...)` accepts two key/value pairs; extend if it only did one.)
4. **THE FOCUS GAP — required, currently missing.** `TextInputController.onMouseDown` (called from
   `GuiTree.wireInput`'s press handler) sets caret/selection but does **NOT** call `FocusState.set`.
   The char/key handlers all early-return unless `FocusState.focused()` is an editable Text — so
   **typing won't route until focus is set.** The counter never needed this (no editable text). Add
   to `wireInput`'s PRESS branch: after computing `hit`, `if (hit instanceof Component.Text t &&
   t.editable()) FocusState.set(hit);`. Import `sibarum.dasum.gui.core.input.FocusState`. Without
   this, the field builds and shows but silently swallows keystrokes.
5. Char/key/backspace/arrows/clipboard already route to the focused editable via the existing
   `wireInput` → `TextInputController` wiring — nothing else to add once focus is set.

### Cautions (from the dasum map)
- **Feedback loop:** `onContentChange` fires on *both* user typing and programmatic `setContent`
  (single mutation path). The only guard is `setContent`'s identical-string early-return. Uncontrolled
  input sidesteps this; if you ever `SetText` a field with a live `onContentChange`, ensure you're not
  creating a set→emit→set cycle.
- **The "little colored boxes while typing" bug = the phantom hover caret** (`TextState.hoverCaretIndex`,
  drawn by `Render` as a translucent quad, shown only on hover of a selectable-but-unfocused Text).
  dasum clears it defensively on setContent/keypress/edit/cursor-move. If a new field flashes stray
  boxes, it's an uncleared `hoverCaretIndex` — not a mystery, a known locus.
- **Identity fragility:** keep the exact Text instance you put in the tree in the registry and as the
  `onContentChange` key; a reconstructed (`withX`) instance loses its sidecar unless `TextStates.migrate`.
- **Listener cleanup (open):** `onContentChange` has no unregister; only `TextStates.clear` /
  `Components.detach` drop listeners. We build once per window and clear the widget registry on
  close, but do **not** currently clear `TextStates` — fine while one window runs per process; if
  multiple windows/sessions in one process become real, clear `TextStates` for the tree on close.

### Integration target: the calculator
`TextField` for the expression → `TextChanged` → the `app` conduit folds the current expression
string into the model, parses it (reuse `ExprParser` / `pontif.algebra`), evaluates, and `SetText`s a
**result `Label`** (a different widget). Live, no submit. The *multi-expression* calculator
(add / enable / delete rows) additionally needs `SetChildren(id, elements)` over dasum
`DynamicChildren` (a dynamic-list command) — treat that as its own sub-slice after single-expression
works. Watch for debounce (per-keystroke parse is likely fine to start; if not, debounce in the
conduit as pure Pontif, not in the bridge).

### Open questions to resolve when starting
- `element(...)` arity (two kv pairs) — verify/extend.
- Whether to give `Label` an editable sibling vs. one `TextField` element (recommend a distinct
  `TextField` element — clearer than an `editable` flag on `Label`).
- Debounce policy for `TextChanged` (start without; add in the conduit if parse-per-keystroke stutters).

---

## 8. Session hand-off (2026-07-31) — the GUI + plot marathon

This session took the reactive GUI from `TextField` through a renderer rewrite to a fully **parametric**
plot. Both repos commit directly to master (no branches). What landed, where it lives, the next slice.

### Landed

- **Input (Slice 3):** `TextField(id, text, hue)` + `TextChanged(id, text)`. Focus fix in
  `GuiTree.wireInput` (press sets `FocusState` on an editable Text, else keystrokes are swallowed).
  `hue` colour-codes the field text to a `SERIES_PALETTE` slot so it matches its curve. The field is a
  **flat**-background `Ui.column` frame — a rounded/bordered one hid the caret (see uber-pipeline).
  Examples: `reactive-textfield.ptf`.
- **Builder migration (dasum + bridge):** `TextBuilder.editable()`/`clip()` added, so an editable
  field no longer needs the raw `Component.Text` constructor; the whole bridge (`GuiTree`,
  `GuiShared`, `LiveEdit`, `SceneBuilder`) migrated off raw `new Component.*` onto `Ui.*`. Rule in the
  dasum README ("Integrating from another codebase": build through `Ui.*`; raw constructors forfeit
  the layout-correctness defaults).
- **Status bar remodel** (dasum `status` pkg + `docs/status.md`): ledger-only, no default message.
  Every entry is a log; three orthogonal axes — surface (alert vs history-only), severity
  (GOOD/NEUTRAL/BAD, *faint pre-attentive theme-aware* tint), channel (USER/TECHNICAL, future filter).
  Idle = "N new" seen-counter (opening the log clears it). Two app-owned slots that are NOT logs:
  docked field (`setDockedMessage`) and contextual override (`setContextualMessage`/`clear`, for a
  caret-error etc.). Leading-zone priority: contextual → active alert → "N new". Playground migrated.
  Reactive `Status(text, kind)` command wired; the reactive window wraps its tree in `Status.wrap`.
- **Uber-pipeline (dasum render — the big one):** replaced the 3-bucket Batcher (flat/rounded/glyph
  flushed in a FIXED order — the source of the "caret/selection hidden behind a rounded frame" bug)
  with ONE uber-shader (`unified.vert`/`.frag`) + `UnifiedAccumulator` drawing every primitive in a
  single submission-ordered stream. Cross-primitive z-order is correct **by construction**. One branch
  point in the frag on `a_kind` (flat/rounded/glyph = how coverage is computed). Old
  accumulators/materials/6 shaders removed. `sampler2DArray` (literal one-draw-call) deliberately
  **skipped** — negligible ROI. No z-buffer (wrong tool for alpha-blended 2D UI).
- **Reactive plot (Slices A + B1):** `ExprPlot(id, exprs)` + `SetPlot(id, exprs)`. `exprs` = a string
  or an aggregate; the bridge composites each that parses as its own colour-coded curve, sampled over
  a SHARED window with a SHARED robust y-range (so 1/x's poles reach the frame edges and every curve
  spans the full width), framed exactly via `Axis.linear`. Multi-expression calculator:
  `calculator-multi.ptf`. Sampling reuses `ExprParser` + `ReliableSeries` (Pontif-side; needs the
  `evalInterval` native).
- **Parametric plot (screen-space chrome — the finale):** fixes skew/crop/thin labels. Root cause:
  ALL chrome was world-space geometry through the one camera, so it couldn't fill the data AND keep
  chrome fixed-size. The cut (after rejecting a separate-pass overlay — it would desync in 3D, not
  depth-blend, and split the data-anchored annotations): keep ONE camera transform for every
  **position** (sync + depth + blend + annotation coherence, 2D and 3D); make only text **size** (and
  later line **width**) screen-space. `TextLayer.withPixelSize(true)` — anchor projected by the MVP,
  glyphs offset in fixed pixels (`scene-text.vert` `u_pixelMode`). `PlotFrame.chrome` tick labels are
  pixel-sized; fill camera re-enabled on `ExprPlot`. Plus **per-axis tick density** from the viewport
  pixels (`PlotView.retickByPixels` + a constructor viewport-resize listener) — each axis's label
  count tracks its own pixel extent, recomputed on resize.

**State:** calculator works — multiple colour-coded functions live in one plot; it fills the viewport,
labels stay crisp/fixed-size/unskewed at any aspect, density auto-adapts per axis on resize. Tests:
dasum-core 192/0, dasum-vis 73/0, gui 68/0; Pontif clean-installs.

### Next slice — interactive pan/zoom-EXPLORE plot

The one clunky thing: drag currently orbits the scene (drags the box). Make it explore the FUNCTION.
The elegant enabler: **the data range is now the single source of truth** — because the chrome is
screen-space and range-driven, you don't pan a camera, you change the visible
`[xmin,xmax]×[ymin,ymax]` and re-sample; the chrome + fill follow for free.

- Drag → translate the range (pixel Δ → data Δ); scroll → scale about the cursor. **Disable the
  `SceneViewController` scene-orbit for 2D plots**; route drag/scroll to a range handler.
- On range change → re-sample the expressions over the new `[xlo,xhi]`
  (`ReliableSeries.resampleReliable` already takes an explicit window), rebuild the frame, republish.
- **Async/progressive:** sample on a debounced worker thread (the `plotInput` `Debouncer` pattern),
  show last-good while dragging, fill new territory in when the sample lands.
- **Seam:** dasum reports "range is now [xlo,xhi,ylo,yhi]" → the bridge re-samples async (Pontif
  `evalInterval`) → republishes. Same producer/consumer split as the rest. Feel-sensitive — tune drag
  sensitivity, zoom-about-cursor, debounce with eyes on it.

### Other deferred

- **Thick gridlines/curves:** screen-space pixel-width `LineLayer` (quad expansion in the vertex
  shader, same technique as pixel text). `glLineWidth` is NOT viable (unbound + core-GL-capped).
- **Boundary refactor (agreed, not done):** move the band→overlay composition (shared y-range,
  clip/pole-aim, exact framing) from Pontif `ReliableSeries`/`ChartBuilder` into dasum-vis; leave only
  `evalInterval` sampling → spans in Pontif. Pair with the pan/zoom or thick-line work.
- **Feature/asymptote marker labels** (`PlotScene2DRenderer`, the chartView/annotated path) are still
  world-sized `TextLayer`s → would skew under fill. The reactive `ExprPlot` path doesn't emit them
  (curves + ticks only), so it's latent, not visible in the calculator. Give them `.withPixelSize(true)`
  when that path goes parametric.
- **Slice B2 (dynamic rows):** add/enable/delete expression rows via `SetChildren(id, elements)` over
  dasum `DynamicChildren`. Those discrete events are the natural home for `emit Status(...)`
  (per-keystroke Status would spam the bar — why B1 didn't fire it).
- **Status future ideas** (`docs/status.md`): temporal grouping + disposition extraction,
  repetitive-logger consolidation, the log filter.
