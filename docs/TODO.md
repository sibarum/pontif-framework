# Pontif TODO

Running list of open work and parked design directions. Each entry: one-line
description + enough context to pick it up later. **Resolved items are removed —
history is in git; this file is forward-looking.** (Recently landed work also
lives in the design docs under `docs/` and the auto-memory index.)

Regression meter: `mvn install -Dmaven.test.skip=true -q` then
`mvn -o test -pl pontif-runtime -am -Dtest=ProbeHarnessTest -Dsurefire.failIfNoSpecifiedTests=false`
(the 150-probe matrix records, never asserts — probes flip BUG→PASS as work lands).

---

## Bracket/paren law: migrate `match` from `{ }` to `( )` — pre-launch requirement (low priority)

RULED 2026-06-24 (with the main-block design, docs/events.md): under the bracket/paren law
the *block* role is parens (`( let …; expr )`) and `{ }` is aggregates only. `main` landed
as `main ( … )`. `match SCRUTINEE { arms }` is the same category — grouped branches whose
arms bind with `->`, not the aggregate `=` — so its `{ }` is a grandfathered exception.
Migrate to `match SCRUTINEE ( arms )` for consistency (eased by match arms already being
optionally brace-less). Corpus-wide: parser + examples + test corpus. **Low priority, but
required before public launch.**

---

## Audit type-inference / sort-checking / proof subsystems for completeness post-`Emit` (HIGH PRIORITY)

The event substrate's `IrExpr.Emit` node (slice 1b, branch war/event-substrate) added a new
sealed-`IrExpr` case, forcing a mechanical arm into ~17 switches across the inference,
sort-checking, construction-gate, conservation, receipt-drafter, and Truffle passes. Those
arms were added compiler-driven (exhaustiveness errors) + light reasoning, NOT a deep
correctness review. **Sweep every IrExpr switch and the proof/receipt + conservation drafters
for whether the new `Emit` handling — and, more broadly, recently-added nodes (`Iterate`,
`Cast`) — is COMPLETE and SOUND, not merely compiling.** Watch for: passes with a `default ->`
that silently skip a node's subtree (no exhaustiveness error to catch them); inference/
narrowing arms returning a coarse sort; conservation/receipt drafters treating new nodes as
opaque residual when they shouldn't. Prompted by James after the 1b code review surfaced that
`SortChecker` has no trait access and `ConstructionGate.gated()` deliberately skips marker
traits — the refactoring's blast radius across the checking subsystems is wider than the green
build implies. Pair with [[project_inference_unification]] (one-engine invariant) + the proof revamp.

---

## ⭐ Type-system convergence — one scoped type-level binding substrate (4 facets)

*Discovered 2026-06-20 (design dialogue): structural traits, named Type fragments,
dependent sorts, and generics are NOT independent — they're projections of ONE
substrate, **scoped type-level bindings that can reference each other** (`[type T]` =
binder, `let X:Type=frag` = definition, `this.count`/`i`-in-a-return = value-reference;
a parametric fragment `gte(n)=[@>=n]` is a generic if `n` is a type, a dependent type
if a value). Build the shared substrate by pulling ONE consumer-driven slice — do NOT
design a grand calculus up front (lens-not-cage). Layering: `trait`/`struct` are
root-level declarators (registered/dispatched); `let X:Type=…` is a locally-scopable
fragment binding (scoping/locality is the essence of `let`). Status per facet tracked
in `docs/feature-matrix.md` (the `Depend`/`TypeFrag`/`Traits`/`Struct` columns);
memory `project_type_spec_layering`.*

- **Dependent sorts (the war — was "step 2").** PARTIAL today, not zero: return
  value-pins already reference params (`ackermann:[Int:@==y_0+1]`, `SpecOnlySynthesisTest`,
  receipt report). The war EXTENDS this to: a refinement referencing a sibling
  param / another field's value (`struct Window(n, data:[Indexed:@.count==n])`);
  **receiver-relative** bounds (`at(i:[Int:@<this.count])`); **value-indexed struct
  sorts** (`OutOfRange(i)`); and **named-parameter method sorts** (`[Method(i:Int):…i…]`,
  rejected at `AltParser:2686` today). First consumer = `Indexed` (`docs/indexed-streams.md`).
  **War doc: `docs/dependent-sorts.md` (PROPOSED)** — substrate-first; slice 1 =
  `IrSort.Method` carries param names (the fulcrum, deletes the `:2686` guard); binder
  references + scope + substitution next; discharge deferred behind `[!!]`. **Chosen as
  the FIRST facet** (it's foundational — the others are built on it; §1 of the doc).
  Deepest, but no longer cold.
- **Structural (anonymous) traits.** `function f(x:Type{m:[Method():Int]})` parses but
  dispatch is unwired — spike: `No method 'm' on type '_pending'`. Fix: resolve methods
  against the receiver's trait CONTRACT members (named or anonymous), not its name; add
  a call-site structural-satisfaction check. Nominal satisfaction (`assign trait`)
  already works. **Highest payoff-per-effort** (no new runtime axis; reuses
  bound-checking). The existential form of a generic bound (`x:Type{…}` ≈
  `[type T:«contract»](x:T)` with T hidden).
- **Named Type fragments.** `let gtz:Type = [@>0]`, applied `[Int:gtz]` →
  `[Int:@>=0]` (base-polymorphic, composable with `&`/`|`). Complete-sort aliases
  already work (`ReusableSortTest`, `TypeAliasIntegrationTest`); the new bit is
  **baseless predicate fragments + apply-to-base** (the alias defers the base to the
  use site, dodging the contextual-base gap). **Smallest / gentlest** way to first
  exercise local type-level binding. Scope v1 to nullary fragments (no params); a
  *parametric* fragment is a generic and is the next rung.
- **Sub-traits.** `trait Indexed[type T]:Stream[T]{…}` — the `:Super` slot the rename
  freed. To satisfy `B`, also satisfy `A`; a `B`-satisfier is accepted where `A` is
  expected. Needs the `Stream` trait first (streams slice 2b). **QoL, deprioritized
  by James — not critical path.**

## ⭐ Next up — dispatch unification (the valuable remaining rung)

*The inference-engine unification (Cluster 5) is done — one engine, `NarrowingInference`,
at every stage; see `docs/inference-unification.md`. Dispatch is the sibling effort the
language-inventory root causes 2–4 still point at. Full plan + decisions in
`docs/dispatch-unification.md`; cross-module visibility model in
`docs/cross-module-dispatch.md` (DRAFT, awaits James's review).*

- **Cluster 4 — operators route once + drop method-form operators (RULED, in progress).**
  Operators are symmetric mechanism-1 multi-dispatch (matched on all operands). (1) Delete
  the parse-time left-operand routing guess so `a op b` always parses to `BinOp` and the
  post-link `MethodOperatorResolver` routes by both operands; (2) reject the receiver-rooted
  `method T.<op>` form. Watch `generics__11/12/13` + `SortChecker.checkOperatorBounds` (must
  handle the `BinOp` form once generic `a+b` over `E` stays a `BinOp`). `dispatch__26` becomes
  an expected rejection.
- **Phase 2 — methods resolve on the receiver sort post-typecheck.** Move method resolution
  off the parser (today `Type.`-mangling needs the receiver's sort at parse time). **Delivers
  cross-module `recv.method()` for free** — the parser stops needing the receiver's sort.
  Migrate `Type.method`-keyed tests. Also unblocks method *references* (`Point.scale[Decimal]`).
- **Phase 3 — trait dispatch becomes mechanism-2 receiver-sort resolution.** The
  `Trait.method → Type.method` redirect in `DispatchTable.resolveTraitFallback` disappears;
  `TraitRegistry` keeps only its narrowing-satisfaction role.
- **Phase 4 — parser de-blinding cleanup.** Delete the residual parse-time routing hacks
  (`methodNameForReceiver`, `tryOperatorOverloadRoute`, the method branch of
  `extractDottedName`). (`inferMaximalSort` already routes through the core engine.)
- **Cross-module visibility — import-by-association + orphan rule.** Generalize the trait-impl
  orphan rule (`CoherenceCheck`) to *all* mechanism-1 overloads (declarable only in a module
  owning ≥1 parameter type); surface them by import-by-association (importing a type brings the
  overloads that mention it). One rule covers fns/operators/methods + the orphan-method fork
  (so `inference__20`'s orphan method becomes forbidden). §5 open questions in the doc.

**Known-failing probes tied to this effort** (all on `master`'s baseline; not regressions):
`traits__20` (cross-module operator *execution* `RecordValue`→`Long` ClassCast — the
top-level-let-sort gap: `let v = Vec(1,2)` over an imported constructor infers `_` at parse,
so `v + v` can't route post-link), `generics__22` (`checkOperatorBounds` can't recognize an
*imported* trait bound), `inference__20` (orphan method), `dispatch__26` (method-form operator,
→ expected rejection once Cluster 4 lands).

**Puntable follow-ups (not off the table):**
- **Static dispatch on union-typed operands (B4).** Runtime already handles a union operand;
  the missing piece is a compile-time exhaustiveness verifier (every union member has a matching
  overload).
- **`compareTo`/`Ord`-style derivation** — derive all six of `< <= > >= == !=` from one ordering
  (today each is overloaded individually). Ergonomic sugar over existing overloading.
- **Static methods within mechanism 2 (B2)** — no receiver value; namespaced under the type,
  resolved rigidly. Confirm the keying.
- **Open trait questions:** does a *narrowed* overload (`+([Int:@>0], …)`) satisfy a bare
  `this.type` contract (current check is exact base-name)? Primitives as trait implementors
  (built-in `+` rides the BinOp fast-path, so `Int:Numeric` can't witness until primitives can
  join satisfier sets).

---

## Merge / housekeeping

- **Merge `war/scope-aware-narrowing` → master.** The whole inference-unification campaign +
  the playground Narrowings view live on the branch (green at every commit, pushed to remote);
  `master` is still at the slice-3 commit. Merge when ready.
- **Narrowings view polish (optional, cosmetic):** absorption in narrowing predicates
  (`(A & B) | A → A`); whether to prettify a resolved `Type.method(v)` back to `v.method()`
  (the resolved form is arguably more truthful — leave unless it bugs).

---

## Return verification / proofs (gate landed; open follow-ups)

- **Multi-branch (per-`match`-arm) proofs** — needs branch-addressing syntax (v1 asserts
  single-branch); **proofs on overloaded functions** (v1 asserts sole-node-of-name).
- Separate distributable proof files (a proof is a *required lemma* keyed to an obligation —
  absence/staleness must hard-fail); explicit `proves Name : pred` claims + sharper stale
  messages; And/Or (De Morgan) split predicates; position-robust (rename-proof) variable
  binding; Notary re-validation of `REFINEMENT_ISSUER_ID` receipts (re-run the validator, not
  just refutation).
- **Real synthesis from a non-pinning spec** (a range / struct return) — genuine program
  search, not desugar. Deferred; the value-pinning case (`[Int:@==EXPR]`) already synthesizes,
  and a non-pinning spec-only `let`/`function` is a hard error in the interim.

## Receipt-graph subsystem

- **Back-reference overload disambiguation.** A `CallRef` targets by bare `targetFunctionName`,
  so a recursive/cross call to an overloaded name doesn't pin *which* overload — resolve via
  `StaticDispatch` and record the node index so the issuer carries overload-specific IHs.
  Deferred until an obligation needs it.
- **Strengthen `Refinements.imply` via bounds** — generalize the ad-hoc single-atom threshold
  compare (`checkImpliesOnLongs`) to linear shapes (`2x+3 ≥ 5`). Lives in `pontif-core`, *below*
  predicates — needs the engine reachable from core or a thin core-level port.
- **Unify `BoundAnalysis`'s `Interval` with `PredicateArithmetic`'s private `Interval`/
  `IntervalSet`** — two same-named types in one package (single-range-with-arithmetic vs
  integer-sets-for-satisfiability). Merge carefully.
- **Issuer plugin interface** — gated on Pontif's not-yet-designed package-management/build
  tool. Largely dissolves into "custom issuers as Pontif functions" below.

### Custom issuers as type-checked Pontif functions (recorded direction)

*Spec: `docs/receipt-graph-refinement.md` (note: superseded in places by the working-session
refinements below — update both when taken on). Slices 0–2 landed (interval×interval multiply;
the conservative `splitOn`/`Refinement` combinator + `RefinementValidator`; in-source
`proof`/`Singletons` surface). Slice 3 is the language lift.*

The clean move: receipt-graph nodes become a runtime-accessible value Pontif can pattern-match
on, and a custom issuer is a Pontif function whose output is gated by refinement at construction
time — so it's no more trusted than any user function (the Drafter + Notary stay Java-trusted).
The only splitting primitive is the conservative binary cut `splitOn(branch, p) → {branch∧p,
branch∧¬p}`: coverage = excluded middle, disjointness = non-contradiction, both by construction
(invalid partitions unrepresentable; `PredicateArithmetic` drops out of split-validation). This
unlocks piecewise-linear nonlinear discharge (the `(x-3)*(x+5) >= -16` shape) and, recursively,
decision-procedure-shaped proofs — kernel-verified at each split.

- **Slice 3 — `refine` as a Pontif function:** Pontif-side `SymExpr`/`Branch`, calling user
  code during checking, validator on its output. The language lift; open-Qs #1/#2/#4 live here.
- **Design call when taken on:** what goes in `ClosingReceipt`'s refinement clause —
  strict (re-verify obligation from path facts), structural (substituted goal implies
  conclusion), or loose (well-formedness only). Middle ground is the likely sweet spot.

## Conservation receipts (ledger landed; sequenced follow-ups)

Spec: `docs/conservation-receipts.md` / `docs/conservation-algebra.md`. In priority order:
1. **No-Halt consumption ruling** — vacuity-annotated certificates / gate behavior /
   receipt-graph IH refusal (the first cross-ledger proposition; after James reads printed
   ledgers). The No-Halt *fact* ships; how a verdict consumes it is the open ruling.
2. **Collection atom model** — element-quantified atoms over arrays (the sorting case needs it;
   String is the forcing function).
3. **Multi-branch `Reversible`** via the exit-assertion theorem.
4. **Property-definition language** — when real definitions demand it (the `<-` asserted-
   placement surface).
5. **Cross-ledger `Lossless`**.
6. **`~=` member-wise lift for aggregates** — claim-aware, `Decimals.approxEqual` at Decimal
   leaves, `==` elsewhere; `~=` stays NON-overloadable (delete the Ternion `==`-as-approx
   overload when it lands).

---

## Strings (Slice 1 landed; forward slices — `docs/strings.md`)

- **Slice 2 — strings compute:** `+` as a plain operator overload (String+String;
  String+Int/Decimal render), concat by adjacency-to-a-literal (sugar for `+`), Char↔String
  coercion (Char→String free; String→Char guarded length-1). Decimal display formatting via the
  `value:["fmt"]` coercion (Excel-style placeholders, half-even, locale-self-describing radix;
  result type = String) rides alongside.
- **Slice 3 — `String -> Queue(Char)` view:** the pure coercion (no action-gate). Substrate for
  transform combinators and parsing — NOT for concat.
- **Slice 4 — string pattern matching as parsing:** `match s { [x:Int "+" r] -> … }` over the
  char view; built-in Int/Decimal extractors; mandatory default arm; remainder var + `""`
  end-anchor.
- **Slice 5 — `assign parser`:** custom per-type productions, paralleling `assign trait`/
  `assign proof`.
- **Alongside (proof revamp):** remove the proof-surface `Leaf`/`Split`/`Singletons` (the
  `std.stream` terminal `Leaf()` is untouched).
- **`ord`/`chr` conversion pair** (shared with Char — a bijection, a future Reversible witness).
- **Char narrows:** Char IS discrete, so `[Char:@=='a']` singletons + ranges
  (`[Char:@>='a' & @<='z']`) may route through integer discharge — revisit
  `BoundAnalysisRules.containsFrac` and the `SymExpr.Chr` abstention notes.

## Indexed streams — random access as a named capability (PROPOSED — `docs/indexed-streams.md`)

*James reversed `streams.md`'s "no random-access indexing" (2026-06-19): base `Stream`
still can't index, but `Indexed` (a sub-trait, is-a Stream, tag survives) names the
capability for storage-backed sequences. `Array` is the first implementor. Spelling
`xs(i)` already ruled by the bracket/paren law. Scope ruled additive.*

- **FOUNDATIONS GAP (found 2026-06-19, blocks Slice 1 as originally scoped).** Three
  Slice-1 assumptions do not exist in code: (1) there is **no `Stream` trait** —
  `std.stream` is flat free-functions over `[Element|Leaf]` (`BuiltinModules.java:116`),
  the trait is doc-only (streams slice 2b); (2) there is **no sub-trait machinery** —
  `IrSort.Trait` has no supertrait field, no parser syntax, trait inheritance deferred
  (`traits.md:282`), so `Indexed : Stream` is inexpressible; (3) there is **no `Array`**
  (ruled out at the semantic level, depends on unbuilt actions). What DOES exist and is
  usable: trait DATA attributes (`count` as attribute), unions + bare-arm match
  (`[Present|OutOfRange]`), and — the reframe — **tuples are the natural first
  implementor** (ordered, immutable, static `count` via `RecordValue` `_tuple`), not
  Array. Open altitude decision (James): realize the Stream/Indexed is-a via the unified
  **narrowing/sort substrate** (no new machinery — preferred) vs build trait-inheritance.
- **Slice 1 (re-scoped, pending altitude) — `Indexed` + total `at` on tuples.**
  `count` as a data attribute + total `at(i):[Present(T)|OutOfRange]` (out-of-bounds is a
  match arm — can't lie, no proof machinery). First implementor = the tuple/aggregate
  substrate, NOT Array. Interaction to rule: does dynamic `xs(i)` reopen the deliberately-
  forbidden value-level positional access (`p._0`, `AltParser.java:3214`)? (They differ —
  static field-style vs dynamic option-returning — but confirm.)
- **Slice 2 — refined `xs(i):T` via receiver-relative refinement.** `i:[Int: @>=0 & @ <
  this.count]` (`@`=index, `this`=receiver, `this.count`=FIELD ref — NOT `@.length`,
  which collides). `count` is a trait DATA attribute (a stored field, honest because
  values are immutable), so this needs only (a) a refinement may name `this`, (b) the
  same `@.field` field-access machinery pointed at `this` — it does NOT need
  `@.method(...)`-predicate sort-checking. What remains is (c) value-dependent bounds
  discharge (BoundAnalysis/IntegerDischarge; provable for literals + Iterate-bounded
  indices, else degrades to `[!!T]` hazard). Sibling of the parked "named-parameter
  method sorts" item (dependent *return* refinement). Soundness rides value
  immutability (no TOCTOU on `this.count`).
- **Slice 3 — literals as `Indexed`** (`(1,2,3)` carries static `count` → fixed accesses
  prove clean) → **Slice 4 — `Iterate` + index → GPU** (unblocks supirvast vector-add
  WITHOUT tuple columns; likely retires supirvast path-3).
- **Existing in-code debt noticed (2026-06-19):** `std.stream` ships **interim
  leniencies** (`BuiltinModules.java:67-73`) — `Element.head` and combinator params typed
  loose (`_`), and combinator bodies left "residual" in the conservation ledger. These are
  the placeholders the unbuilt **Stream trait + `[Stream(T)]` sort form** (streams slices
  2a/2b, `docs/streams.md`) will tighten. Already tracked in the streams slice plan;
  surfaced here per the no-kludge sweep. Building `Indexed` on the narrowing substrate
  should tighten these rather than add a parallel loose path.
- **Endgame (deferred) — `Fin`-style index sort** (rung 3): `Fin(this.count())` via the
  free-type-parameter machinery; out-of-bounds unrepresentable, no `OutOfRange` arm.
- **Amend on ratification** (not yet done): the "no indexing" ruling in `streams.md` /
  `glossary.md` / `strings.md` → "no indexing on *base* Stream." **Open:** does pure
  random access make `Array` pure-side (the action-side framing was only ever about
  un-indexed memory-order walking)? `count`/`length`/`size`; `Present`/`OutOfRange` names.

## Instance methods on primitives (WANT — not yet designed)

`Int`/`Decimal`/`Char`/`String`/`Bool` should host instance methods (`d.format("0.00")`,
`s.length()`) rather than a separate library of free functions. Resolution is already
receiver-sort → `Type.method`, so the call path isn't the blocker; what's missing is (a) a
place to attach methods to a type with no struct/trait def and (b) a **coherence answer** —
primitives are language-owned, so the orphan rule forbids a user `method Int.foo` unless we
bless std-owned primitive methods (auto-in-scope) or carve out an extension-method rule.
Upstreams the Decimal-format spelling (`d:["fmt"]` vs `d.format(…)`).

## Metareferences — Slice 2

- **Type references** (`[Type(...)]`/`[Type{...}]`, the one-way struct→trait lattice,
  `TypeValue` application = construction).
- Deferred from slice 1: the bare-function-name-in-value-position fence (needs pattern-binding
  name extraction); key-sort compatibility at the *reference* site (today zero-candidates only);
  Dispatch-sort subsumption (v1 exact key match); method references (wait for dispatch Phase 2).

---

## Refinement syntax — type/name/field-aware elaboration

*Unifying principle: a refinement can refer to whatever's in scope at its position; the sugar is
just dropping what's inferable. `:` is one uniform "refined-by/has-sort" connective; `@.field`
shifts the subject inward.*

- `[Int>=0]` ≡ `[Int:@>=0]` (base + implicit `@`) — needed in return/param positions.
- `[@>0]` **contextual base on any statically-typed scrutinee**, not just a bare `Var` (**F1**:
  `match n+1 { [@<=0] -> … }` still parse-errors "no contextual base"; explicit `[Int:@<=0]` is
  the workaround). The enabling slice is the type/name/field-environment-aware elaboration —
  the parser is still nearly type-blind here.
- Constructor deconstruction `[Point(x>0, y==0)]` (binds-and-constrains per field).
- Path refinement `[Vector:@.x:Rational:@.denominator>0]` (drill one field deep), composing with
  deconstruction.
- **Open decision:** are types and values separate namespaces (Capital vs lowercase)? If yes, a
  leading identifier is classified lexically before any scope lookup.
- **Approximate comparison family as SORT operators (parked sketch).** `<~ ~= >~` in sort
  position, lowering to EXACT predicates against ε-shifted bounds (ε = one ulp, same derivation
  as runtime `~=`). Payoff: the trichotomy becomes a *provably total* match over a Decimal
  scrutinee — cracks "decimal matches need a default" for numeric comparisons. Totality proof is
  PARAMETRIC (partitions the line for any anchor `x` and ε≥0; kernel needs only `x−ε ≤ x+ε`), so
  ε may be runtime-derived; the real obligation is *all arms anchor the SAME `x`* (mixing anchors
  → pointed diagnostic). Nearly free: parser sugar + ε-derivation; the proof path exists.

## Match / patterns

- **Multi-varying-field struct totality** — genuine cross-product over field domains
  (`[Point(x>0,y>0)] | [Point(x<=0,y<=0)]` is non-exhaustive but the gap is 2-D); and struct
  *unions* in the scrutinee.
- **Literal-scrutinee totality** — `match -3 {…}` should be checkable now that literals narrow to
  singletons via the unified engine (`inferFloor` gives `[Int:@==-3]`, and an arm-free-of-vars
  domain isn't widened); **verify and pin** (the old `inferSort`-returns-null limitation may be
  resolved).
- Explicit-binding / rename in patterns (`(x as a)`); nested destructuring auto-bind (inner
  records still need explicit chains); `_` in let-bindings and function params (intentional
  discard — matters once actions exist); the **nominal-vs-structural** decision for pattern
  struct-names (currently cosmetic; pinned by `PartialPatternTest`).

## Aggregates (tuples + dicts landed; parked)

Arrays (homogeneous positional storage, iterable only via actions per `docs/streams.md`); deep
selectors `@(…)`; the collection-conservation atom model (the sorting case; String is the
forcing function).

---

## Boolean / predicates

- **Finite-range filters (`@%2==0`) — LOW PRIORITY (consumer).** Finite generator
  synthesis (`let evens:Stream[Int:0<=@<10 & @%2==0];`) parses and the synthesizer
  *can* evaluate `%` filters at materialization time, but the refinement is rejected
  earlier: `%`/`/`/`^` are rejected in **any** refinement predicate language-wide
  (`IrCompiler.compileBinOp`, "the discharge kernel is linear"). So a filtered range
  can't be written today. Range + direction (no filter) shipped on
  `war/finite-range-synthesis`; filters deferred (James, 2026-06-22). The enabling
  requirement is the next item.
- **REQUIREMENT — full modular arithmetic in the proof/discharge system.** `%` (and
  the divisibility it encodes) must become a first-class, sound citizen of the kernel,
  not a rejected operator. Two layers, both required:
  - **Constant modulus `@ % k` (k literal) — decidable, complete.** This is *not* a
    nonlinear theory: it's the **divisibility-by-constant** extension Presburger
    arithmetic already needs for quantifier-elimination closure (Cooper / the Omega
    test manufacture these atoms during projection). Add **congruence atoms**
    `x ≡ r (mod k)`; realize as **Granger's `aℤ+b` congruence domain** in a *reduced
    product* with the existing interval engine (matches the AI shape of `BoundAnalysis`,
    not a new SMT solver). New algebra is elementary number theory: **meet = CRT**
    (`x≡r₁(mod k₁) ∧ x≡r₂(mod k₂)` solvable iff `r₁≡r₂ (mod gcd(k₁,k₂))`, modulus
    `lcm`; failure of the gcd condition IS the disjointness/unsat proof), **join = gcd**,
    **× interval** = "does the residue class hit `[lo,hi]`" (one ceil/floor check;
    narrowing snaps endpoints to the nearest class member). Bridge from the `MOD` term:
    pattern-recognize `MOD(x,k) ⋈ r` → congruence atom; general position introduces
    `x = k·q + r ∧ 0 ≤ r < k` (k constant ⇒ stays linear).
  - **Variable modulus `@ % n` (n a variable) — piecewise-linear case-split driver,
    NOT a second theory.** Reduces to finitely many *constant*-modulus subgoals when the
    partition is finite + polyhedral. Three handles: **(1)** divisor pinned to a finite
    range (`n:[Int:1<=@<=4]`) → disjoin over each concrete `n`, each slice constant-modulus
    (complete; cost = range cardinality, so a count cap must fail *honestly* to residual,
    never silently truncate); **(2)** quotient bounded — esp. the cheap, high-value
    `x < n ⟹ x%n == x` (`q=0`) slice, detectable straight from `BoundAnalysis` intervals,
    probably the first thing to wire; **(3)** divisor-agnostic definitional facts
    (`0 ≤ x%n < n`) provable with `n·q` left opaque, no split. **The no-lie fence:**
    unbounded divisor AND unbounded quotient AND a divisibility goal = genuinely
    nonlinear (Diophantine, undecidable) → stays `[!!]`, never discharged.
  - **Placement:** integer-only (discreteness license — `Frac`/`Decimal` have no
    congruences); routes through `IntegerDischarge`/`BoundAnalysis`, NOT the rational
    `Sign` lattice. **Decide `%`'s sign convention first** (Euclidean `0≤r<k` vs C-style
    truncated) — the congruence reasoning assumes a canonical residue in `[0,k)`, so the
    lowering must agree on negatives or it's silently wrong. **Structural note:**
    `x = k·q + r ∧ 0≤r<k` is a bijection `x ↔ (q,r)` — the division algorithm is
    information-preserving (residue = lossy `ℤ→ℤ/kℤ` projection, quotient = conserved
    complement), so the congruence domain may be the integer instance of the COTT
    conservation split (`splitOn`) rather than a bolt-on. (Theory discussion 2026-06-22.)
- **Short-circuit `&&`/`||`** — currently strict; critical once actions exist.
- **No `Not` operator** — `[!=0]` works via `NE`, but real Boolean negation isn't expressible.
  Needs `SymExpr.Not` + a unary-op shape in IR (only `BinOp` exists).
- **`SignAnalysis` doesn't reason about `&&`/`||`** — can't infer bounds from
  `(x>0) && (x<10)`.

## Type system — smaller open items

- **Narrowing for non-`Var` match scrutinees** — `SortChecker` narrows inside a structural-
  pattern branch only when the scrutinee is an `IrExpr.Var`. The parser always emits a synthetic
  outer let (so it's a Var after desugar), but a hand-built `Match` with a non-Var scrutinee
  skips narrowing.
- **Refinement-predicate sort-checking deeper than `@.field`** — `@.field.subfield`,
  `@.method(...)`, call-shapes aren't recursively type-checked. Extend when nested struct
  refinements show up.
- **`Method` sort isn't runtime-validated** — a `[Method(Int):Int]` return doesn't check the
  body produces `Int→Int`. `Refinements.satisfiesFunction` exists; not wired in.
- **Destructuring through a type alias** — the parser's destructure desugar runs *before*
  `AliasResolver`, so it doesn't see an alias's structural shape. Fix: move destructuring to a
  post-`AliasResolver` IR pass (root cause #5 from the language inventory — the parse-time-vs-
  post-link shape-resolution seam; `DestructureResolver` already owns the cross-module path).
- **`toSymExpr` for `Closure`/`LambdaValue`** — passing a lambda through dispatch calls
  `toSymExpr`, which only knows Long/Boolean/RecordValue. Lift closures to `SymExpr.Lam`, or
  short-circuit for non-refined param positions.
- **`@` as the current concrete type (parked)** — eventually `@` may denote a Self-type in type
  position. Noted so the symbol isn't reused meanwhile.
- **Record-literal vs declared-sort mismatch (S-expr only)** — the `(record …)` form doesn't
  verify its field-set against a declared struct (the alt parser closes this at construction).

## Exception handling

- **`IllegalArgumentException`/`IllegalStateException` audit** in `IrCompiler` + AST validators —
  reclassify "framework bug" (stays unchecked) vs "user error" (→ `CompileException`).
- **`SelfRef` at runtime → `CompileException`** with origin (currently `IllegalStateException`).

## Architecture

- **`extractDottedName` builds a `Call` from any Var-rooted FieldAccess chain** without checking
  it's a declared function (treats `random.x.y(1,2)` as a call even when `random` is local).
  More visible with the module system.
- **`inferBaseSortName` only recognizes `IrExpr.Var` scrutinees** — a struct-literal or
  struct-returning-call scrutinee returns null, so contextual `[pred]` arms aren't usable
  (related to F1 above).

## Playground / dasum

- **`StandardInput.install(window, cursors)` helper upstream** in `dasum-core` (the playground's
  `wireInput` is ~110 lines of vendored boilerplate).
- **Origin → editor caret jump** — clicking an `<editor>:L:C` error should move the caret (needs
  `line:col → offset`).
- **Union return over mutual recursion isn't gate-provable (engine gap)** — `isEven`/`isOdd` with
  `[Int:0|1]` returns need reasoning from a disjunctive *hypothesis* (the Or-*goal* discharge
  doesn't cover it; can't be proof-authored — overloaded). Pinned by
  `PlaygroundIntegrationTest.unionMutualRecursion_…`.

---

## Alt syntax — not yet parsed (would error today)

- **Named-parameter method sorts** `[Method(x:Int):[Int:x+n]]` — lets a dependent return
  refinement reference the function's own parameter. Needs `IrSort.Method` to carry param names.
- **Inline lambda creation** — `[Method(...):Ret]` parses as a sort but no value syntax creates
  one. (Lambdas are being retired in favor of metareferences; design call whether to bother.)
- **Spec-only `let qualified.name:Sort`** with a maximally-specific sort and no `= value` (derive
  the body from the sort) — NoOp pending real synthesis.
- **Re-exports / import-aliasing / `Capital=type`** — needs an IR change (`IrStmt.Exports` must
  carry the re-exported module) plus parser work.

## New language features

- **User-defined constructors for structs (NEW — James reverses a prior ruling,
  2026-06-18).** Allow a struct to define a constructor with a custom body. **Hard
  constraint: the constructor's argument list must match the struct definition
  *exactly*** (same arity, same field names/sorts) — so it can't fabricate a different
  shape; it produces the same struct with possibly-transformed/validated field values.
  `Point(3, 4)` would then invoke the constructor rather than the default field-assign.
  Separate from the dispatch war; scope after it. Open design Qs: does it run on
  *every* construction (incl. internal/promotion paths)? how does it compose with the
  **construction gate** + no-lie (a body that lies about what's stored must be fenced —
  the "args match exactly" rule is the guardrail)? relationship to the existing
  **native constructors** (`Decimal(unscaled, scale)` — the bijection-contract registry,
  see memory `project_native_constructors`)? is it one default + overloads, or exactly
  one? **Why the reversal matters:** the original ruling kept construction transparent;
  the exact-match constraint is what lets a body back in without reopening the lie hole.
- **Per-call dispatch return narrowing for inferred let sorts** — `let q = factorial(3)` only
  gets `factorial`'s *declared* return at parse, not the matched overload's narrowing. (The
  reflector's shallow call-site specialization does this for the Narrowings view; the parser's
  let-sort inference still uses the declared return.)
- **Action classes / mutable semantics** — pure functions stay pure; actions are the controlled
  escape hatch (observed, ledgered, proof-licensed). Likely a side-by-side IR family
  (`IrAction`, `IrActionStmt`), not a tag on `IrExpr`. Drafted in `docs/actions.md`.

## Traits — follow-on

- ~~**Default method impls in trait bodies**~~ — LANDED 2026-06-25 (`TraitDefaultExpansion`,
  full self-reference; see docs/traits.md "Default method implementations"). **multi-trait
  constraints** in params (`a:Duck & Audible` — partially achievable via intersection sorts,
  needs a small parser extension); **trait inheritance** (sugar over multi-trait; defer);
  **primitives as trait implementors** (may `assign trait Int:Foo` register a built-in in a
  satisfier set? — low demand, reconsider whether worth keeping).

## Deep work — oracle territory

The receipt-graph format is the contract; none of this is Pontif's to ship.
- **Inductive postconditions beyond linear integer arithmetic** — `sum(n) == n*(n+1)/2`
  (nonlinear closed form) and multi-atom hypothesis constraints (`x+y>0` bounds neither alone —
  Fourier–Motzkin / Presburger). Linear integer arithmetic + product magnitude are now built-in.
- **Proof Authority (PA) trust model** — CA-style: designate trusted issuers whose receipts are
  accepted by attribution. Roadmap goal, low priority.
- **Decimal constructor arg naming** — `Decimal(unscaled, scale)` shipped with BigDecimal's
  vocabulary (provisional); revisit once Strings are fully implemented.

## Editor — go to definition (landed 2026-06-30, docs/editor-navigation.md)

Ctrl+click navigates to a name's definition (read-only Definition tab; builtins
reflected to Pontif source via `IrSourcePrinter`); Ctrl-hover underlines the word.
Forward-looking:
- **Editor-side usage highlighting** — highlight a symbol's references in the editor
  itself when the caret rests on it, not only in the opened Definition view.
- **Body-div parity in the Definition view** — the read-only view applies foreground
  syntax coloring but omits the editor's parser-backed function-body background tint
  (it carries cross-keystroke state and contends with the reference highlights). Add
  if full visual parity is wanted.
