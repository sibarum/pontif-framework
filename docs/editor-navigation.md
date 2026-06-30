# Editor navigation — go to definition

Ctrl+click a name in the Pontif Editor (pontif-playground) to jump to where it's
defined. An IntelliJ-style affordance: hold Ctrl and the identifier under the
mouse underlines + the cursor turns to a hand; click and the declaring module's
source opens in a read-only **Definition** tab with the name highlighted. Esc (or
switching back to Editor) returns.

Landed 2026-06-30.

## Surface

- **Ctrl-hover** — while Ctrl is held, the editor identifier under the mouse is
  underlined and the cursor is a hand. Updates live on mouse move and on Ctrl
  press/release. Primitives (`Int`, `Bool`, …) don't underline — nothing to open.
- **Ctrl+click** — resolves the name and opens the **Definition** tab (a fixed,
  read-only 5th tab) showing the declaring module's source, the name highlighted
  and scrolled into view. A status line names the source module.
- **Esc** on the Definition tab → back to the Editor.

The tab is fixed, not created per-click: dasum's `Component.Tabs` is an immutable
record, so adding a tab at runtime means rebuilding the root (breaking
identity-keyed state). The Definition tab is therefore always present and filled
on demand — the same on-demand pattern as IR/AST, Receipts, Narrowings. "Closing"
the view is Esc / switching to Editor (the tab strip has no per-tab close button).

## Resolution (`DefinitionNavigator`)

A tolerant search, not the full link-time `ModuleSymbolTable` — a Ctrl+click is an
explicit one-off, and erring toward "find it somewhere" beats refusing on a
half-finished import. Precedence, first match wins:

1. **This file** — the editor buffer's own declarations, shown verbatim.
2. **Sibling modules** — `.ptf` files in the open file's directory, real on-disk source.
3. **Builtins** — `BuiltinModules.all()` plus the GUI extension (see below).

A clicked name matches a declaration when it equals it **or is its last dotted
segment** — so clicking `zero` finds `let Point.zero`, and `inv` finds
`method Ternion.inv`. Functions, top-level lets, struct/trait/type aliases, and
trait-impl methods are all candidates. An unparseable mid-edit buffer still
resolves builtins (the local check is just skipped). Primitives report a status
("no source to open") rather than opening an empty view.

`pontif.gui` is resolved by parsing the GUI extension's source **locally, for
lookup only** — the editor runs GUI programs in a subprocess and never installs
that extension globally, so this keeps its names navigable without changing the
in-process Run path.

## Reflecting builtins back to Pontif source

Builtins split two ways:

- **Source-authored** — `pontif.core` and the extension modules (`pontif.events`,
  `pontif.gui`) carry real Pontif source. Shown verbatim, comments and all.
  Retrieved via `BuiltinModules.sourceOf(name)` (the `pontif.core` constant plus
  each extension's `pontifSource()`, captured at `Extensions.install`).
- **IR-built** — `std.common` / `std.proof` / `std.conservation` are constructed
  directly from IR (no shipped `.ptf`). These are **reflected back into Pontif
  source** by `IrSourcePrinter` (pontif-ir): e.g. clicking `Split` shows
  `struct Split(p:Bool, whenTrue:[Leaf | Split], whenFalse:[Leaf | Split])`.

`IrSourcePrinter` is a faithful declaration unparser (struct/trait/function/let/
requires/exports + a sort renderer that prints member types as references, not
expanded shapes). It is distinct from:
- `IrPrinter` — a kind-tagged structural *debug dump* (the IR/AST tab), and
- `IrSourceReflector` — re-emits source with declared sorts replaced by *inferred
  narrowings* (the Narrowings tab).

**Why reflection, not hand-written stand-in `.ptf` files:** the reflection is
derived from the real definitions, so it can't drift out of sync. Stand-in files
would need manual upkeep on every change to those builtins.

## Reference highlighting

The Definition view highlights **every whole-word occurrence** of the name
(`DefinitionNavigator.references`, identifier-boundary aware — `twice` won't match
inside `twiceArg`): the declaration in strong blue, its other references in faint
amber. The caret/selection sits on the declaration so Ctrl+C copies it.

## Syntax highlighting

The Definition view gets the editor's token coloring via a stateless
`AltHighlighter.foreground(content)` — comments/literals dimmed, keywords neutral,
user names in the hue-hashed rainbow (so a name keeps the *same* color across the
jump). Foreground only: the editor's parser-backed **body-div** background pass is
omitted — it carries cross-keystroke state for the live editor and would also
contend with the reference highlights for the background axis. Foreground (syntax)
and background (references) are independent axes and compose.

## The underline

dasum's `TextStyle` has no underline decoration (only fg/bg color, outline,
weight). The Ctrl-hover underline is drawn as a thin `DrawCommand.ColoredQuad`
under the word in the editor's render pass, positioned via public
`TextGeometry.caretBounds`, clipped to the editor's scroll viewport. No dasum
change needed.

## Key files

| Piece | File |
|-------|------|
| Resolution + references + reflection routing | `pontif-playground/.../DefinitionNavigator.java` |
| IR → Pontif-source unparser | `pontif-ir/.../IrSourcePrinter.java` |
| Real-source accessor for builtins | `pontif-runtime/.../module/BuiltinModules.java` (`sourceOf`) |
| Editor wiring (tab, Ctrl+click, underline, Esc, highlight) | `pontif-playground/.../App.java` |
| Stateless foreground colorizer | `pontif-playground/.../AltHighlighter.java` (`foreground`) |
| Tests | `pontif-playground/.../DefinitionNavigatorTest.java` |

## Deferred

- **Editor-side usage highlighting** — highlight a symbol's references in the
  editor itself (not just the opened Definition view), as IDEs do when the caret
  rests on a name.
- **Body-div parity** — the read-only view omits the editor's function-body
  background tint (see Syntax highlighting). Add if full visual parity is wanted.
