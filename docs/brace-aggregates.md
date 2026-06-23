# The brace-aggregates war

**Declared 2026-06-22 (James).** *"Parenthesis for grouping expressions and for tuples
is a bad idea. I won't copy it just because it's popular."*

Move **aggregate literals from parentheses to braces**. Parentheses are freed to mean
exactly one thing each context already wants: **grouping** `(expr)`, **call arguments**
`f(a, b)`, and **parameter lists** `(x:Int)`. Braces `{…}` become **the** aggregate
delimiter — positional and named alike.

## Why (the reasoning that forced it)

A 1-element aggregate has no honest spelling when parens do double duty:

- `(x)` is grouping, so a 1-tuple needs a disambiguator — `(x,)`, which James rejects.
- Lifting a scalar in `+` (`x + ()`) makes the operator **type-unstable**: `a + b` would
  change its output shape when an operand happens to be empty or scalar. Rejected — `+`
  (concat) must be uniform: `() + b == b`, same type, always.
- `((x))` can't be a 1-tuple: grouping is idempotent (`(e) → e`), so `((4,5)) → (4,5)`
  (verified), and `((x))` is genuinely two parses. Reintroduces the collision.
- Type-directed lift (append-when-`x:E`, concat-when-`x:Stream[E]`) is *also* type-unstable
  and ambiguous once `E` is itself a stream. Rejected for the same reason as the lift.

Every dead end has the same root: **parens overload grouping and aggregation.** Give
aggregation its own delimiter and the collision evaporates — `{}`, `{x}`, `{1,2}` are all
unambiguous because `{…}` never means grouping.

```
{}            empty aggregate
{5}           one element            ← no comma, no ambiguity
{1, 2, 3}     many
{4, 5}        two
{{4, 5}}      ONE element that is the aggregate {4,5}   ← nesting falls out
(expr)        grouping ONLY
f(a, b)       call
(x:Int)       parameter list
```

Type side mirrors it: a tuple **sort** moves `[(Int, Int)]` → `[{Int, Int}]`.

This is the syntactic form of the [aggregate-unification](aggregate-unification.md) ruling
(structs / tuples / dicts / arrays are one ordered name→value substrate): positional
`{1, 2}` and named `{a=1, b=2}` are the *same* brace delimiter, name-absent vs name-present.
The IR is untouched — `_tuple` Records/Structurals stay — so this is a **parser-frontend
remap** plus source/test churn.

## The collisions this resolves

| delimiter | old roles | new role |
|---|---|---|
| `( … )` | grouping **+** tuple value **+** tuple sort | grouping / call / param list only |
| `{ … }` | dict literal **+** block expression `{EXPR}` | aggregate literal (positional **+** named) only |

Braces have their *own* overload today: `{a=1}` (dict) and `{EXPR}` (block expression).
The block form collides with the 1-element aggregate (`{5}` is a block → `5` today,
verified), so **the `{EXPR}` block expression is retired** (slice 2). Its only role was an
explicit closing boundary for greedy `let`-chains; if that's still needed, revisit with a
non-brace mechanism.

## Slice plan (additive first, retire last — green throughout)

1. **Value positional braces, arity ≥ 2 — LANDED.** `{e0, e1, …}` parses to the same
   `_tuple` Record as the paren tuple (mirrors the `LPAREN` branch). `{1,2,3}` builds,
   projects (`._0`), autoboxes to `Stream[T]`, concatenates. Parens/dicts/blocks all still
   work (additive). `BraceAggregateTest`. (`{x}`/`{}` deferred to slice 2 — block collision.)
2. **Singleton + empty value braces — LANDED.** `{}` empty, `{x}` singleton, `{{4,5}}` a
   one-element aggregate holding the pair (the case bare parens couldn't spell). The
   `{EXPR}` block form is **gone from braces entirely** — the grouping / let-chain-boundary
   role is parens (`( let y = 5  y + 1 )`, which already bound to its closing `)`). Fallout
   migrated: every braced function body / let-value / call-arg block `-> { … }` moved to
   `-> ( … )` (`AltParserLetExprTest`, `AltParserIntegrationTest`, `DictTest`,
   `LetClaimGateTest`, the ternion examples in `ApproxEqualityTest`/`DecimalPromotionTest`).
   `match this { … }` is the match parser's own braces — untouched. Empty/1-ary `_tuple`
   Records work downstream unchanged (no arity≥2 assumption). Full reactor green.
3. **Tuple sort + pattern braces.** `[{Int, Int}]` sorts and `{a, b}` destructure/match
   patterns. Parameterize `parseTupleSortBody` over the delimiter (LPAREN/RPAREN →
   accept LBRACE/RBRACE); touches the nested-tuple recursive call sites. Additive.
4. **Mass migration.** Convert `.ptf` examples, alt-syntax test strings, probe resources,
   and `docs/alternative-syntax.ptf` from parens to braces. The probe harness + suite is the
   regression meter. (Heaviest: `TupleTest`, `NestedDestructureTest`, `ConservationGateTest`,
   `streams/ternion/traction.ptf`.)
5. **Retire paren-aggregates.** `(a, b)` value and `[(Int, Int)]` sort become parse errors
   pointing at the brace form; `( … )` is grouping/call/param only. Update stragglers. Update
   the printer/`ReceiptGraphPrinter` to render tuples as `{…}`. Amend the bracket/paren law
   (`docs/glossary.md`, `project_bracket_paren_law`): `[]` types, `{}` aggregates, `()`
   grouping/application, `$` names.

## Blast radius (inventory 2026-06-22)

- `.ptf` files with paren-aggregates: ~6 examples + `docs/alternative-syntax.ptf`.
- Java tests embedding alt-syntax with paren-tuples: ~12 tuple-heavy (`TupleTest`,
  `NestedDestructureTest`, `ConservationGateTest`, `PositionalParamDestructureTest`,
  `CastAltTest`, `ReadmeSnippetTest`, …) of 71 `compileAlt` users.
- Probe resources: ~10–15 of ~150 (`destructure__*`, `generics__*`, …).
- Hard cases: nested tuples `((a,b),c)`, refined components `[([Int:@>0], Bool)]`,
  tuple param/codomain shapes (`previous:(Int,Int)`, `(el):(Stream[Int],Int) -> …`).

## Rulings (James)

- **Block-expr → parens** (2026-06-22): braces are *exclusively* aggregates; the grouping /
  block role is parens. `( let … )` is the block (it already bound a let-chain to its `)`).
  Resolved in S2 — no `{EXPR}` survives.

## WAR markers (cut sites)

- `AltParser` `LBRACE` case in `parsePrimary` — value aggregate only (S1+S2 done; block
  fallthrough removed).
- `AltParser.parseTupleSortBody` — parameterize over the delimiter (slice 3).
- `AltParser` `LPAREN` tuple branch + the `(` triggers into `parseTupleSortBody` — remove at
  slice 5 (paren-aggregate retirement).
- Tuple printer (`ReceiptGraphPrinter` / value `toString`) — render `{…}` (slice 5).
