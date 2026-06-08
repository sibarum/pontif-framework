# Univocal — implementation plan

Implementation slices for the gold-standard design in
`docs/univocal-language-design.md` (2026-06-08). Each slice is **vertical**
(end-to-end, independently reviewable), not a layer. Status markers: ☐ not
started · ◐ in progress · ☑ landed. File paths/line numbers from the 2026-06-08
recon pass — verify against current code before editing.

Two findings from recon reshape the work and are baked into the slices below:

1. **`self`, `@`, and the IR conflate.** `@` *and* the method receiver both flow
   through `IrExpr.SelfRef` / `SymExpr.Self`, with the receiver also injected as a
   param literally named `"self"`. The design needs `@` (bracket subject) and
   `this` (statement subject) **distinct**. So S0 is not a pure rename — step one
   is disentangling the IR.
2. **The demotion morphism is a coercion, not a construction check.** Nothing is
   checked when a `Point3D` is *built*; the morphism runs at **demotion**
   (`let b:Point = a` projects Point3D→Point, drops z, clean forget). The seam is
   the assignment/claim site, not `ConstructionGate` at the constructor.

---

## Lose-freely half

### ☑ S0 — `self → this` (receiver keyword only)  *(landed 2026-06-08, full suite green)*
**Landed:** alt-syntax receiver param `self`→`this` (AltParser); `@`-node
`SelfRef`/`SymExpr.Self` untouched (it's `@`); S-expr kept `self` as the
test-stable legacy (`Parser.java`, `LanguageDef.selfReference` unchanged). Bare
receiver refs (`match self`, `-> self`) needed a second pass beyond the dotted
`self.`. Remaining: doc prose in `traits.md` / `alternative-syntax.ptf` still says
`self` (cosmetic, non-blocking).

**Disambiguation done (recon was wrong):** `IrExpr.SelfRef` / `SymExpr.Self` are
`@` (the refinement subject, line 2834 maps the `@` token to `SelfRef`), NOT the
receiver — renaming them to `ThisRef` would conflate `@` with `this`. The
receiver is a plain injected param named `"self"` (AltParser 711/1199), referenced
as `self.x` → `Var("self")`. **Only that becomes `this`.** The 68-site
`NarrowingInference` / `SymExpr.Self` cluster is `@` machinery and stays untouched.
- Seams: rename injected param `"self"`→`"this"` (AltParser 711, 1199; reject
  message 701; Parser.java 238); `LanguageDef.selfReference` default + reserved
  list; glossary `self` entry; user-facing `self.` → `this.` across tests/docs/.ptf.
- **`this` in struct-decl type position** (the "statement subject" generalization,
  `this.x` where there's no injected param) is NOT here — it arrives with S2.
- Reviewable: method tests pass with `this`; `@` semantics unchanged; `self` gone.

### ☑ S1 — `;` universal synthesis directive  *(landed 2026-06-08, full suite green)*
**Landed:** `SEMICOLON` token + lexer; `;`-gate at all three bodyless-synthesis
sites (function, method, let). Bodyless-without-`;` is now an error; `;` on a
non-pinning sort is an honest "does not pin" error (the old silent NoOp for
bodyless lets is gone). Corpus migrated (~23 spec-only decls across
SpecOnlyLetTest, SpecOnlySynthesisTest, AltParserIntegrationTest,
PlaygroundProbeTest, ReturnVerificationMeasurementTest). Partial-value+pin
(`= partial;`) deferred to S6 — `;` is a harmless terminator when a value is
present today.

Make a trailing `;` the explicit, sole trigger for function AND value synthesis.
- Seams: add `SEMICOLON` to `AltToken.Kind` + `AltLexer`; gate `parseFunction`
  (~616) and `parseLet` (~876) on `;`; bodyless-without-`;` → error;
  `;`-without-determinable-pin → error. Migrate existing spec-only decls.
- **Risk:** breaks every current bodyless synthesis until the corpus is migrated.
- Reviewable: `let zero:[Decimal:0];` synthesizes; without `;` it errors;
  `function timesTwo(n:Int):[Int:n*2];` synthesizes.

### ☑ S2 — struct-extension declaration  *(landed 2026-06-08, full suite green)*
**Landed:** `IrSort.Structural` gains a nullable `baseSort` (holds the parsed
`[Base:rel]` whole — base + morphism predicate); `parseStruct` parses the
optional `:[Base:rel]` before the param list; `SortChecker.validateStructBase`
checks the base resolves and (for a refined-struct base) the morphism
functionally pins every base field — non-total → compile error. Purely additive
(no existing struct uses `:[…]`). **Gotcha fixed:** three sites rebuilt
`Structural` via the 3-arg ctor and would have dropped `baseSort` before
SortChecker saw it — AliasResolver (×2) + NameResolver (now thread it through).
StructExtensionTest pins parse/total/positional/non-total. **Deferred:** the
no-parens wrapper form (`struct Zero:[Decimal:0]`, parens still required);
morphism-RHS param scoping; demotion coercion is S3.

`struct Name:[Base:rel](fields)` — base sort + demotion morphism alongside fields.
- Seams: extend `IrSort.Structural` with `baseSort` + `baseMorphism`; parse
  `:[Base:rel]` and positional `[Base(x,y)]` in `parseStruct` (~1234); add a
  `SortChecker` totality pass (base resolves to a declared struct; the morphism
  functionally pins every base field; field sorts compatible).
- Reviewable: the decl parses + registers; a non-total morphism is rejected with
  a pointed diagnostic. No coercion yet.

### ☑ S3 — demotion coercion  *(landed 2026-06-08, full suite green)*
**Landed:** `let b:Point = a` (a:Point3D) runs the morphism → `Point(a.x, a.y)`,
`z` dropped, no surviving tag. Two seams: the parser's base-mismatch check now
allows a declared demotion (`demotesTo`) and records the binding at the demoted
(base) sort; `ConstructionGate.maybeDemote`/`projectDemotion` rewrite the value
to the projection record (morphism RHS with deriving-struct param names rewritten
to field reads on the value). Reviewable verified: demotion projects, `b.z`
errors (clean forget), `let c:Point3D = b` rejected (no auto-promotion). Both
engines, no runtime change (the projection rides existing Record construction).
**Follow-up:** the value expr is duplicated per base field (fine for pure
top-level-let constants; bind-once for non-constant values later).

At a declared-sort assignment where the value's sort ⊑ target via the morphism,
run the projection → drop unmentioned fields, **no surviving tag**.
- Seams: the LetIn claim / coercion site (NOT `ConstructionGate` at the
  constructor); both engines (`IrInterpreter`, `TruffleLowering`).
- Reviewable: `let a = Point3D(2,3,5); let b:Point = a` → b is Point(2,3); `b.z`
  errors; `let c:Point3D = b` errors ("can't synthesize data").

---

## Fabricate-never half

### ☑ S4 — param-sort `.{}` destructuring  *(landed 2026-06-08, full suite green)*
**Landed:** `point:[Point.{x, y}]` — the param keeps base sort `[Point]`; x, y
bind to `point.x`, `point.y` in the body (via `let`-wrapping). `parseParamList`
detects `[Base.{entries}]` (reusing `parseDotBraceEntryList`, so `x -> px` rename
works) and accumulates `ParamDestructure`s; all three function-bodied callers
(function/method/trait-method) drain + bind-into-scope + wrap the body. The 4th
`.{}` consumer after requires/exports/let. Verified: `function sumXY(point:[Point.{x,y}]):Int -> x + y` and the rename form.

`point:[Point.{x,y}]` binds x,y into scope.
- Seams: `parseBracketBranch` (~1395) / `parseParamList` (~741); scope injection
  mirrors the `let`-destructure path. Compose with `->` rename for collisions.
- Reviewable: bare x,y usable in the function body/return.

### ☐ S5 — promotion via synthesis (function + method)  *(needs S1+S2+S4)*
Extend `tryDeriveBodyFromReturnSort` (~769) to a **construction pin** `Type{names}`
(not just `@==EXPR`).
- `function promote(point:[Point.{x,y}], z:Int):Point3D{x,y,z};`
- `method Point.promote(z:Int):[Point3D{this.x,this.y,z}];` (uses `this` from S0)
- Reviewable: both synthesize + run; `promote(b,7)` → Point3D(2,3,7);
  `b.promote(11)` infers Point3D.

### ☐ S6 — promotion via value synthesis (partial value ⊕ pin)  *(needs S1+S2+S3)*
`let x:[Point3D:@.z==0] = b;` — merge partial value (b → x,y) with pin (→ z).
- Seams: `mergePartialWithPin` + `extractFieldPins` in `pinnedWitness` (~967);
  existing LetIn-claim + ConstructionGate verdict unchanged.
- Reviewable: → Point3D(2,3,0); missing field (no pin, no value) errors.

---

## Computation / world-boundary

### ☐ S7 — `requires @.{…}` (world-boundary `@`)  *(independent)*
`@` = the parent/outside scope inside `requires`; makes `$fqn` import sources
unnecessary (`$` kept, see [[project_requires_unification]]).
- Seams: pragmatic — lower `requires @.{name}` to local let-destructure from the
  enclosing scope (`parseRequires` ~360), avoiding module-linker rework.
- Reviewable: `requires @.{sqrt}` brings sqrt into scope from the enclosing namespace.

### ☐ S8 — monadic in-type pipeline  *(deepest; needs S1+S7)*
`[requires @.{sqrt} -> let m:Decimal = sqrt(...) -> Decimal:@==m]` in type position.
- **Approach: DESUGAR** into body statements + a final pin sort — NOT a new
  executable sort kind (keeps sorts non-executable; reuse `IrExpr.LetIn` chaining).
- Reviewable: the `magnitude` monadic form compiles + runs, equivalent to the
  plain-body version.

### ☐ S9 — `^` power operator  *(small, independent, OOS-flagged)*
`Op.POW`; lexer `^`; `Int^Int→Int`, Decimal promotion if either operand Decimal;
opaque in the receipts drafter (like `/`). Decimal^non-integer is out of scope
(transcendental).

---

## Sequencing

Critical path: **S0 → S1 → S2 → S3 → S6**, with **S2 → S4 → S5** branching off.
S0, S1, S2, S4, S7, S9 are mutually independent (solo-dev → sequential, but
reorderable). Recommended start: **S0** (everything downstream wants `this`; the
IR disambiguation shouldn't be retrofitted).
