Strings
===

Status: DRAFT (2026-06-12). Companion to `streams.md`; supersedes the
earlier rough sketch in this file. Markers follow the house convention:
**RULED** = settled; **DERIVED** = follows from ruled material plus
standing laws; **PROPOSED** = a default awaiting your ruling; **OPEN** =
explicitly undecided.

# Governing stance: strings are privileged (RULED)

A String is the first Char **collection**, but it is *not* just "a Stream
of Char with the Stream's austerity." Streams, arrays, and sets interact
only through combinators; **String earns bespoke sugar they don't** —
an infix `+`, concatenation by adjacency, and (Slice 4) pattern matching
as parsing. The sequence machinery is still underneath (the `Queue(Char)`
view, Slice 3), but everyday string code never has to reach for it.

This is a deliberate reversal of the combinator-only framing that shaped
Slice 1's *description* (see Reconciliation). Slice 1's *code* — `"..."`
literals and lexicographic-by-code-point ordering — stands unchanged.

# Reconciliation: Slice-1 framing that is being reversed

The following claims appeared around Slice 1 (commit `192054b`,
`streams.md`, `glossary.md`, `TODO.md`) and were misunderstandings, not
rulings. They are **withdrawn**:

- ~~"strings order and compare; they don't compute — no arithmetic"~~ →
  strings compute: `+` concatenates (below).
- ~~"concatenation is the `concat` combinator, not an operator"~~ → for
  strings, `+` is an ordinary operator overload, and adjacency is sugar
  for it. `concat()` is not part of the String surface.
- ~~"no String/Char tower; mixed fails closed; no coercion"~~ → Char and
  String coerce (below).
- ~~"String/Int mixed fails closed"~~ → `String + Int` renders the int
  into the string (below).

What still holds from Slice 1: no **indexing** (`s(i)` does not exist —
random access is an Array/action concern, not a String one); ordering is
**by code point** (not `String.compareTo`), so astral planes rank
correctly; engines abstain on the raw `Str` domain (no discrete route).

# Literals (RULED unless marked)

```pontif
let x:String = "ty"
let z = 'the "top row" of a keyboard.'   # single quotes embed double quotes, no escapes
let y = "qwer"x" comes from"z            # adjacency to a literal = concatenation
let u = x + y + z                        # + is an ordinary operator overload
```

- **`"` and `'` are interchangeable** (RULED). The only reason to have
  both is escape-free embedding of the other quote; they produce identical
  `String` values. Escapes: `\n \t \" \' \\`, full Unicode incl. astral.
- **Backtick `` ` ``** (RULED out for now). Not a string delimiter — `"`/`'`
  already cover escape-free embedding. **Reserved** for some yet-undetermined
  future feature; held back precisely so it stays available for one.
- The `''`-as-glue form (`x''y''z`) from the sketch is **dropped**:
  adjacency concatenates only when a **literal** abuts an expression;
  variable–variable concatenation uses `+`.

# Computation: `+` (RULED unless marked)

`+` on strings is a plain operator overload — the same mechanism as any
other operator (`function +(a:String, b:String):String`), governed by the
ordinary orphan rule, no special machinery.

| Expression | Result | Note |
| --- | --- | --- |
| `String + String` | concatenation | RULED |
| `String + Int` | the int rendered into the string | RULED — clean decimal rendering |
| `String + Decimal` | the decimal **formatted** then concatenated | default render below; explicit via format-coercion |

**`String + Decimal` rendering** (RULED): the *default* is the canonical
`Decimal` display already used in the ledgers (`Decimals`), which prints at
the value's own scale (`105.00`, not `105`). For explicit control you format
the value first with the **format-coercion** `d:["…"]` (next section) and
concatenate the result.

**Adjacency** (DERIVED) desugars directly to `+`: `"a"x"b"` ≡
`"a" + x + "b"`. So the rendering rules above apply identically whether you
wrote `+` or juxtaposed.

# Char ↔ String coercion (RULED unless marked)

- **Char → String** is free (widening; a code point is always a valid
  one-character string). This is what makes the sugar safe: a `Char`
  flowing into a `+` chain or an adjacency just renders.
- **String → Char**: free when the string is a **statically-known
  one-character literal** (`'a'` is a one-char string coerced to `Char`);
  otherwise a **guarded** length-1 coercion (fails closed if the length
  isn't 1 — this is a narrowing, not a free cast, per the cast law:
  *lose freely, fabricate never*).
- A **character literal** is therefore not a separate lexical class — it
  is a one-character string coerced to `Char` (Slice 2 makes the coercion
  real; the lexer need not special-case it).

# Decimal display formatting — `value:["fmt"]`

A **string literal inside the coercion brackets** is a *formatting coercion*,
distinct from a sort: `v:[Sort]` narrows a value's type, `v:["fmt"]` governs
its **presentation**. A format is a presentational refinement — which is why
it rides the same `:[…]` bracket.

## The grammar — per-position placeholders (RULED)

Each digit position is written explicitly, with two symbols (the Excel /
`DecimalFormat` model, chosen because one rule covers min/max/exact/trim/pad
with nothing to disambiguate):

- **`0`** — a digit *always shown* (pad with zero if the value lacks it).
- **`#`** — a digit shown *only if significant* (trailing `#` positions are
  trimmed).

```pontif
# d is implicitly Decimal.
let d = 12.4
let dFormatted = d:["0.########"]   # min 1 integer digit; up to 8 fraction digits, trimmed

struct BankAccount(b:Decimal)
let bankAccountFormatted = BankAccount(12000.45):[",0.00"]
# comma-grouped, min 1 integer digit; exactly 2 fraction digits (padded).
let european = BankAccount(12000.45):[".0,00"]
# the same, with the . and , roles swapped (the format is locale-self-describing).
```

Rules:

- **Integer side** — a run of `0`s sets the *minimum* integer digits
  (leading-zero pad). It never sets a maximum: dropping an integer digit would
  change the value, so the integer part is **never truncated** (no-lie). A
  **grouping glyph** in the run turns on thousands grouping.
- **Fraction side** — per-position `0`/`#`: trailing `0`s set the minimum
  shown, the total positions set the maximum. `.00` = exactly 2; `.########` =
  up to 8 trimmed; `.00###` = at least 2, up to 5.
- **Radix / locale (self-describing)** — the glyph *between* the two runs is
  the decimal point; the other (`,`/`.`) is the group separator. So `.0,00` is
  the European mirror of `,0.00` with no separate locale flag.
- **Rounding** — when the value carries more fraction digits than the pattern's
  maximum, round **half-even** (banker's), matching `Decimal`'s policy. Display
  rounding only — never silent truncation.

| Value | Pattern | Output |
| --- | --- | --- |
| `12.4` | `0.##` | `12.4` |
| `12.4` | `0.00` | `12.40` |
| `12000.456` | `,0.00` | `12,000.46` |
| `5` | `000.0` | `005.0` |

**Future (out of scope now):** a `;` negative subpattern
(`,0.00;(,0.00)` for accounting parens), currency / percent affixes, and
scientific notation. Negatives default to a leading `-`.

**Result type (RULED): `String`.** `d:["fmt"]` *renders* the value to a
`String` — it is not a display-annotated `Decimal`. So
`(12.4):["0.00"]` is the string `"12.40"`, and it composes with `+` /
adjacency like any other string. Applied to a struct that wraps a `Decimal`
(`BankAccount(12000.45):[",0.00"]`), it formats that `Decimal` content; the
rule for a struct with no, or more than one, `Decimal` field is a minor
follow-up (likely: defined only where there is exactly one).

**Forward note (spelling).** This rides the `:["fmt"]` coercion bracket today.
If instance methods on primitives land (a live want — see `TODO`), the more
discoverable home may be a method, `d.format("0.00")` — same semantics, better
namespacing. The grammar above is independent of which spelling wins.

This is adjacent to Slice 2 (it gives `String + Decimal` its explicit-control
path); the format grammar above gets pinned before it ships.

# Pattern matching strings — parsing (Slice 4)

Match patterns are sorts everywhere else; over a String they are **parser
productions** — the pattern consumes the string left to right, binding
typed pieces.

```pontif
module messages

struct Num(value:Int)
struct Pair(left:[Int|Pair], right:[Int|Pair|Num], relationship:String)
struct Error(message:String)

let message = "1+2=3"

function parseMessage(m:String):[Pair|Num|Error] ->
  match m {
    # Decimal requires a 0.0-shaped value: digits, a dot, digits — so a
    # bare "1" does NOT match here, and this arm doesn't shadow the Int arms.
    [x:Decimal r] -> Error("Parser doesn't support decimal "x)
    [x:Int "+" r] -> Pair(x, parseMessage(r), "+")
    [x:Int "=" r] -> Pair(x, parseMessage(r), "=")
    # A bare remainder var (r) binds "the rest". "" anchors end-of-string.
    [x:Int ""]    -> Num(x)
    [""]          -> Error("Premature end of string")
    [_]           -> Error("Unrecognized format")
  }

parseMessage(message)   # → Pair(1, Pair(2, Num(3), "="), "+")
```

Rules:

- **Typed extraction** (RULED): `x:Int` consumes a maximal integer literal
  and binds the parsed value; `x:Decimal` consumes a `digits.digits` form
  (numbers on both sides of the dot). String literals (`"+"`) must match
  verbatim.
- **Remainder var + `""` anchor** (RULED): a trailing bare variable binds
  the unconsumed rest; a trailing `""` asserts the string is exhausted. A
  pattern with neither would only match if the productions happen to
  consume everything — require one explicitly.
- **A default arm is mandatory** (RULED, your call). String-pattern
  coverage is a grammar-coverage question, which is not decidable the way
  integer-refinement complement is — so the determined-coverage property
  that ordinary `match` enjoys is *surrendered* here. Every `match` with a
  string-parser arm must carry `[_]`. Accepted tradeoff.
- **Ordering** (DERIVED): arms are tried top-to-bottom (ordinary `match`
  semantics), so put more specific productions first.

# Custom parsers for types — DEFERRED (not yet designed)

The built-in `Int`/`Decimal` extractors are pre-registered productions. A way
to register a *user* type's parser — so `[x:Money r]` could appear in a string
match — is anticipated (likely an `assign`-family directive, paralleling
`assign trait` / `assign proof`), but it is **not yet designed and out of
scope** for the current plan. Slice 4 ships with the built-in extractors only.

# The `Queue(Char)` view (Slice 3)

The pure `String → Queue(Char)` coercion decodes code points into the
inductive `Element/Leaf` queue (no action-gate — a string's content is
statically known, unlike an Array's runtime content). Its role, now that
concatenation is `+`:

- It is the **operational substrate for parsing** (Slice 4 folds over it).
- It carries the transforming combinators that don't have sugar (`map`
  over chars, etc.).

It is *not* needed for `+`/adjacency, which is why the sugar (Slice 2)
lands first.

# Slice plan

1. **Slice 1 — value + literal + ordering.** LANDED (`192054b`). Code
   stands; framing reconciled by this document.
2. **Slice 2 — strings compute.** `+` overloads (String+String,
   String+Int, String+Decimal), adjacency sugar, Char↔String coercion.
   Needs nothing else first; highest ergonomic payoff.
3. **Slice 3 — `String → Queue(Char)` view.** Pure coercion; substrate for
   transform combinators and for parsing.
4. **Slice 4 — string pattern matching.** Parser productions over the
   char view; built-in Int/Decimal extractors; mandatory default arm.
   (Custom per-type parsers are deferred — see above.)

Decimal display formatting (`value:["fmt"]`) rides alongside Slice 2 once
its format grammar is pinned.

**Alongside (proof revamp):** the proof-surface `Leaf`/`Split`/`Singletons`
constructors are removed (the already-ratified proof revamp). The
**stream terminal** `Leaf()` in `std.common`/`std.stream` is a different
thing and is untouched.

# Open decisions

The literal/quote, backtick, coercion, slice-order, Decimal **format grammar**,
and **result type** (`String`) questions are now RULED (above). Nothing in the
String plan is blocked. Two soft follow-ups, neither gating:

1. **Format spelling** — keep the `:["fmt"]` coercion, or move it to a
   `d.format("…")` method if instance-methods-on-primitives lands.
2. **Format on a struct** — the rule for structs with zero or many `Decimal`
   fields (the single-field case is defined).