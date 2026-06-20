# Pontif Glossary

Terms invented or reframed for Pontif. Alphabetical, terse. When a name is
deliberately different from the conventional one, the entry says why.

---

**`@`** — Principal subject of the enclosing refinement: the value being
refined (in `[Sort:pred]`) or the scrutinee (in a match arm). Each refinement
binds its own `@`; nested refinements shadow. See `alternative-syntax.ptf`
principle 3.

**`~=`** — Approximate equality. Equal within one unit in the last place at
the working precision (DECIMAL128, 34 significant digits), scaled to the
larger operand's magnitude. The tolerance is *derived, not chosen*: it is
exactly the loss the division policy declared, no free parameters. Coincides
with `==` wherever no rounding exists (Ints, exact Decimals); `x ~= 0` for
nonzero `x` is false (relative tolerance has no jurisdiction at zero). Not
allowed in sort position — narrows and predicates stay exact; the proof layer
never forgives. Motivated by `t * t.inv() == one` failing under exact equality
with a 34-nines rounding artifact.

**aggregate** — The single substrate behind structs, tuples, and dictionaries:
an ordered `name → value` map (`RecordValue` / `IrSort.Structural`). Two
orthogonal knobs distinguish the surface forms — **bracket = access face**
(positional `( )` vs by-name `{ }`) and **type-name = nominal toggle**:

|            | positional `( )`   | by-name `{ }`        |
| ---------- | ------------------ | -------------------- |
| anonymous  | tuple `(1, 2)`     | dictionary `{a=1}`   |
| named      | `Point(1, 2)`      | `Point{x=1}`         |

A named sort claims "this **is** a `Point`"; an anonymous sort claims only its
shape. Tuples/dictionaries are the anonymous cells; structs are the same cells
with the name (and behavior) turned on. Tuples are stored with positional keys
`_0 .. _n` under the reserved `_tuple` sentinel (the positional sibling of the
`_record` sentinel).

**back-reference** — In a receipt-graph, a recursive call points back to the
same node rather than re-expanding. The no-duplicate-edges rule turns
well-foundedness into a graph property and brings the postcondition along
as an inductive hypothesis automatically. The conservation ledger's recursion
fixpoint is the same move on the second ledger: the recursive call
substitutes its own function's converged summary by reference.

**backward language design** — Pontif's construction method
(`docs/backward-language-design.md`): implement from the execution layer
upward — Truffle AST → IR → reference language → alt syntax — each layer
testable via the one below; the primary syntax is sugar over what already
executes. **Generalized (2026-06-02):** the theory (information conservation,
the algebraic kernel) is layer zero, beneath the execution AST. Design
*decisions* flow backward too — each surface question is answered by the
deepest layer with jurisdiction (let-pattern refutability ← the totality
rule; `Int→Decimal` promotion ← losslessness; truncating `/` ← the `/`+`%`
recovery identity). The whole language is one big syntactic sugar for the
theory.

**base-inference** — When a single base sort is unambiguous from context,
the base may be omitted in a bracket-form refinement: `match (x:Int) { [@<0]
-> ... }` has the base `Int` inferred. Required to be explicit when context
is ambiguous (union scrutinee, top-level function return, etc.).

**claim** — A value's type name, as established at construction. The claim rule
(derived by the **no-lie law**): *construction is where claims are made;
matching is where they're tested; nothing in between invents one.* A
**declared** name bites — a sort naming a registered nominal type accepts only
values claiming exactly that type (no re-badging: a `Vec` never passes as a
same-shaped `Point`), and a question position (`match`, `==`) never coerces.
An anonymous literal at an **assertion** position (`let p:Point = {x=1,y=2}`, a
typed param, a return) is *not* a lie — the user asserts the type right there,
so it's checked construction with the redundant name elided (the
`AggregatePromotion` pass stamps it; missing/extra fields are compile errors).
Sentinel names (`_record`, `_tuple`) and inline shape-labels (unregistered)
stay shape-only — nothing nominal exists to falsely claim. Anonymous sorts
accept named values (struct ⊑ **aggregate**, the directional rule), with
positional (`_tuple`) sorts additionally arity-exact. Native `==` follows
matching ("if it wouldn't match, it's not equal"); user `==` overloads are
ordinary dispatch the kernel never consults.

**exchange** — The stream combinator that is the third no-erase answer
(ruled 2026-06-06, `streams.md`): neither erase nor split — *focus*.
Matching elements are lent out, modified, and placed back; the result is
the full stream with modifications woven in at their original positions,
non-matching elements untouched. Per-element borrow → transform → return:
the conservation coin at stream granularity. Silent discard is structurally
inexpressible (classic discard-filter = `partition` + a declared drop).
The `when`-arm's semantics inside the pure world.

**Queue / Array (stream jurisdictions)** — The Stream substrate's two
implementors mark *jurisdiction*, not data structure (`streams.md`,
ratified 2026-06-06): **Queue** is the inductive view
(`Element(head, rest:[Element|Leaf])`) — pure-side, consumed by structural
recursion, termination certifiable by descent; **Array** is native storage
— action-side, iterable only via observation (memory order is runtime
dynamics, the observer world's business). A proven-linear Queue may be
array-backed in the lowering: same memory, different jurisdiction.

**std.common** — The builtin home for structs with cross-domain reuse value
(ruled 2026-06-06). Founding resident: `Leaf()`, the canonical terminal —
one freestanding nominal borrowed by `[Leaf|Split]` (proof trees) and
`[Element|Leaf]` (streams) alike, served through **re-exports** (a module
exporting a name it imports; importers resolve through the chase to the
declaring origin, so one nominal stays one nominal however many doors it
arrives by).

**construction gate** — The claim rule's enforcement half (ruled 2026-06-05,
`ConstructionGate`): a constructor argument is judged against its declared
field sort at the construction site, three ways. *Provable fit* (the
argument's narrowing implies the field sort) passes with no runtime check —
the proof discharged it. *Provable miss* (disjoint) is a compile error — the
value would be born lying. *Genuine overlap / undecidable* compiles with a
runtime check at the construction site (fail-closed; both engines). Decided
by the same kernel as match totality; bare unregistered field sorts keep the
substrate's leniency, and the Int→Decimal embedding is never ruled disjoint.

**circular design** — The generalization backward design forced on itself
(`docs/conservation-algebra.md`): for a feature whose artifact transcribes the
IR, the IR is the theory's *alphabet* — classifications are DERIVED per sealed
form, never hypothesized over it. The tell of a hypothesized taxonomy is a
`default ->` case; a derived one has none, and the sealed-switch exhaustiveness
check is the theory's standing completeness proof: any future IR variant must
declare what it conserves before the ledger compiles. The theory audits the
language; the language type-checks the theory.

**coherence rule (orphan rule)** — A trait impl `impl Trait for Type` may be
declared only in the module owning `Trait` or `Type`, never a third module
owning neither. Borrowed from Rust; closes the type-piracy hole Pontif's global
trait registry would otherwise open under multi-dispatch. Enforced at link time
(`CoherenceCheck`) over fully-qualified type names. See **module**.

**discard (`_`)** — In a positional pattern, `_` occupies a slot and binds
nothing: `[Point(a, _)]`, `[(a, _, c)]`. It is what keeps a positional pattern
*arity-total* (see **positional totality**) while honestly declining a
component — more honest than naming an unused binder. Generalizes the `[_]`
default-arm marker from the whole value to one slot.

**conservation receipts** — The second ledger (`pontif-conservation`,
`docs/conservation-receipts.md`): the receipt graph proves what values *are*;
this proves where they *went*. Per function, a dataflow graph of exactly three
node kinds — **Computation** (operations + resolved calls, op-classed with
recoverability verdicts), **Branch** (matchers + dispatches — discrimination),
**Construction** (constructors + function returns) — with metadata (constants,
naming, binding, projection) on flow edges. Per-branch-path *role multisets*
per input atom; residual flow (lambdas, applications, unresolved calls) is
the located ignorance every query fails closed on. Properties attach
via the same `proof f = …` statement as algebraic proofs (`std.conservation`);
callee summaries compose over the call DAG, and a cycle's summaries are the
Kleene fixpoint from the optimistic seed — recursion traces instead of
staying residual (ruling in `conservation-algebra.md`). Not a sort, never narrows, nothing
in the runtime: receipts are for auditors, sorts are for callers.

**Char** — The fourth scalar: a Unicode code point (full range, not just the
BMP), written `'a'` / `'\n'` (escapes: `\n \t \' \\`). Ordered and compared
by code point; **chars order and compare — they don't compute** (no
arithmetic), and there is no Char/Int tower (mixed comparisons fail closed;
an `ord`/`chr` conversion pair is a future ruling — a bijection, so a
natural `Reversible` witness). Under the capacity law a Char is ~21 bits:
branching spends one bit of many, never exhausting it (the numeric rule).
Unlike `Decimal`, Char IS discrete — code points are integers — so Char
narrows (`[Char:@=='a']`, code-point ranges) may legitimately route through
integer discharge; that's the follow-up slice. Char and `String` **coerce**:
Char→String is free (a code point is always a one-char string), String→Char is
a guarded length-1 narrowing. See **String** for the first Char **collection**.

**String** — The first Char **collection** (`strings.md`, the spec; the
sequence substrate is `streams.md`). A native-storage value (the Char analog
of `Array` — storage is representation), with an inductive `Queue(Char)` view
underneath. Written `"..."` or `'...'` (interchangeable; escapes
`\n \t \" \' \\`, full Unicode incl. astral). **Strings are privileged** —
unlike streams/arrays/sets they earn bespoke sugar: an infix `+`
(concatenation; `String+Int` renders the int, `String+Decimal` formats it),
concatenation by **adjacency** to a literal, and (later) pattern matching as
parsing. Ordering is **lexicographic by code point** (not `String.compareTo`,
so astral ranks correctly); there is **no indexing** (random access is an
Array/action concern). Char and String **coerce** (Char→String free;
String→Char a guarded length-1 narrowing; a char literal is a one-char string
coerced to `Char`). Under the capacity law a String is one opaque `OTHER` atom
for now: the **collection conservation atom model** is parked until a layer has
jurisdiction, and String is its eventual forcing-function. Slice 1 (value +
literal + ordering) landed; the compute sugar, the `Queue(Char)` view, and
string-pattern parsing are the forward slices (`strings.md`).

**Data-Conservative** — The headline conservation property, sort-aware under
the **capacity law**: *measurement counts as conservation exactly when it
exhausts the measured content.* Every `Int`/`Decimal`/`Char` input atom's
content must reach the return (verbatim or derived — a chain through a
comparison carries one bit, not content); every `Bool` atom must reach the
return **or** be spent in branching (its whole content is one bit). Claims consumption, not
recoverability — `Lossless` is reserved for the future cross-ledger property
(algebraic + conservation combined: the output *determines* the input).
`DataConservativeExcept(s.email)` declares an intentional drop and goes stale
(compile error) the moment the drop disappears.

**decomposition (`.{}`)** — Pontif's one named-decomposition operation: "from
X, take {these}." Three consumers, one payload: `requires math.{min, max}`,
`exports @.{factorial}`, and `let person.{name, age}`. Deliberate forethought —
the requires/exports syntax was designed (project day 3) to *be* dictionary
decomposition, because imports/exports ARE destructuring. An entry renames
inline with `->` ("becomes", the same arrow as match arms/function bodies):
`requires math.{min -> minimum}`, `let person.{name -> username}` — LHS is the
name where the symbol already lives, RHS its name in the receiving context.
Each entry is an abbreviated let. By-name reads are *projections*
(partial-honest); an unknown key against a statically-known source is a lie and
is rejected; positional keys are excluded (tuples are destructure-only). Not a
value: `.{}` always binds into a receiving context. *(Exports rename — public ≠
internal name — is parked.)*

**dictionary** — The anonymous by-name **aggregate**: `{a = 1, b = 2}` — a
struct with the type name (and behavior) turned off; the by-name sibling of the
tuple, riding the same record substrate with no dedicated node. Free-form at
construction (no completeness obligation — there's no named type to be complete
*of*; the named `Point{x=1}` form is total by construction). Fields read by name
(`d.a`) or by **decomposition** (`let d.{a, b -> bee}`).

**dispatch unification** — The planned effort (`docs/dispatch-unification.md`)
to put every dispatched call on the **one shared resolution engine**
(`StaticDispatch` + `Refinements.imply` + most-specific) and delete the ad-hoc
accidents layered on top — the built-in-operator `BinOp` bypass, the
`Type.method` name-mangling, and the parse-time sort inference. It does **not**
merge methods and free functions into one namespace: Pontif keeps **two
separately-governed mechanisms** — (1) free functions + operators as open,
symmetric, promotion-capable global multi-dispatch (built-in `Int`/`Bool`
operators registered as real overloads); (2) methods/static/traits/inheritance
as localized, rigid, receiver-rooted dispatch. Operators live in mechanism 1
(`+(Vector,Vector)`, not `Vector.+`); method resolution moves post-typecheck
within mechanism 2, which delivers the `recv.method()` cross-module case as a
side effect.

**drafter** — Pontif's built-in deterministic component that produces
receipt-graphs from source. Single job, no reasoning. Immutable —
changes only across Pontif language versions. Not pluggable. Lives in
`pontif-receipts`. The verb "drafts" is what gives the role its name;
the noun for the artifact is just "receipt-graph."

**implicit `@==EXPR` sugar** — When a refinement predicate is a plain
expression with no top-level comparison, an implicit `@==` is inserted.
Applied per-disjunct/conjunct: `[Int:0|1]` ≡ `[Int:@==0 | @==1]`. See
`alternative-syntax.ptf` principle 5.

**issuer** — The role that *produces closing receipts about* a
receipt-graph. Issuers don't change the graph — they produce *separate*
closing-receipt artifacts that reference into it. Pontif ships a
built-in default issuer (`SignAnalysis` + equality) trusted by the
notary by default; oracle modules (Z3, custom, AI) integrate
Maven-plugin style *(deferred — gated on Pontif's package-management /
build tool)*; hand-written receipts are the escape hatch.
Scope-configurable (per-module / per-file / per-region),
overlap-allowed. Issuers only close — they don't verify or refute.
Chosen over "closer" to avoid collisions with `Closure` in the IR
layer and with the comparative "more close" in prose. "Closer" is fine
colloquially.

**module** — A `.ptf` source file declaring `module a.b` at its top. The unit of
namespacing: every function and type it declares gets a fully-qualified key
(`module/name` internally; `/` is the module↔local boundary) so distinct modules
can reuse names without colliding. `requires pkg.{names}` imports; `exports
@.{names}` lists what it makes visible (`@` = this module). A project is a
directory tree of modules linked into one program, entry named in
`module.ptf.toml`. See **coherence rule**.

**metareference** — A first-class reference to the META level, sorted
narrowly enough to use safely. Function references don't exist in Pontif —
`$inc[Int]` reifies the DISPATCH keyed at those sorts (sort
`[Dispatch(Int):Int]`): not a function pointer but a name-keyed candidate
set; invocation is application (`ref(2)`, per the bracket/paren law: `[]`
for types, `()` for values) and reruns runtime dispatch, narrowings intact —
it resolves exactly how `inc(2)` resolves. The `$` sigil marks a NAME —
quoted, not evaluated — the third element of the notation law (`[]` types,
`()` values, `$` names); the general production is the $fqn literal
(`$com.ns.fn[Int]` parses unambiguously), with bare `$Type` reserved for the
type-reference slice. `[Dispatch(...)]` and `[Method(...)]`
mirror the two dispatch mechanisms and never cross-assign: a Method is one
body (a lambda, a trait contract); a Dispatch is a reified dispatch site.
Zero candidates at the reference is a compile error. Creating a reference
consumes nothing (a constant in the conservation ledger); invocation through
a binding is residual, fail-closed, per the Lambda/Apply ruling — the
captured candidate set makes dispatch-as-Branch a later upgrade. Type
references (`[Type(...)]`/`[Type{...}]`) are the second slice.

**narrowing** — What `:` denotes everywhere. `x:T` reads "x narrows to T."
Used at every level — params, refinements, struct fields, function returns.
Chosen over "has type" / "is of sort" because it's descriptive and reads
left-to-right with the operator.

**no-lie law** — Pontif's overriding design constraint: the language is never
allowed to assert something false. The grain of the substrate is followed
(leniency is welcome where it's honest), *except* where leniency would let the
system lie — there honesty wins. The sharp edge of information conservation
(a lie fabricates information). Design procedure: is the substrate lenient
here? then, does that leniency lie? — lenient+honest ⇒ allow, lenient+dishonest
⇒ fence. It *derives* rules rather than choosing them (e.g. **positional
totality**, the named-vs-anonymous matching split).

**NoHalt** — The conservation ledger's divergence fact: *this function
provably never completes*. A module-wide greatest fixpoint over the ledger's
branch-paths: keep exactly the functions whose every branch-path contains a
call into the kept set or a verbatim self re-entry (the params passed
unchanged, any permutation — the orbit is finite). Sound by infinite descent
under pure, strict evaluation; callers of never-halting functions inherit the
fact. The complement of the recursion fixpoint's inductive hypothesis: "if it
completes, it conserves" / "it cannot complete." Decides only a sound corner
of *non*-halting — silence is no claim, never a halting verdict, and
termination is never proven (that descent is arithmetic — receipt-graph
territory). Printed as a `no-halt:` line and per-path markers; a fact, not a
property verdict — its consumption (vacuity-annotated certificates, the
receipt-graph IH) is an open ruling.

**notary** — Pontif's built-in receipt-graph verifier. Three independent
verifications: (1) a graph exists, (2) a receipt-graph's skeleton
matches what the drafter produces from the same source, (3) a
hypothesis (typically a closing receipt's conclusion) is *supported*
— not refuted — by the graph. From any closing receipt, the notary
reads only `(issuer, conclusion)` and the graph reference; the rest of
the receipt's payload is opaque (it's for 3rd-party verifiers, audit
trails, etc.). Confirms existence and consent, not correctness.
Refutation-only — never confirms validity. Immutable — changes only
across Pontif language versions. Not pluggable. *Chosen over "kernel,"
which implies OS-style resource management this subsystem doesn't do.*

**oracle module** — A third-party issuer the user explicitly trusts to
produce receipts. Pontif won't audit it; if it has a bug, expect runtime
errors. Examples: Z3, custom solvers, AI provers, hand-written receipts.
From Pontif's perspective the name is precise, not metaphorical — oracles
are opaque sources of trusted-by-fiat results.

**positional totality** — A positional `( )` pattern must account for every
slot of what it destructures — a subset like `[Ternion(a)]` (3-field struct) or
`[(a, b)]` on a 3-tuple is *lying by omission* and is rejected. The constructor
wouldn't let you omit, and the pattern wears the constructor's clothes. Discard
unwanted slots with `_` (see **discard**) or focus by name with a refinement
`[T:@.field …]`, which makes no false completeness claim. This is
no-erase-no-duplicate (the conservation rule) made syntactic — derived by the
**no-lie law**, not chosen.

**proof** — A hand-authored, in-source discharge for a declared return
refinement the built-in engine can't prove on its own:
`proof f = Split(p, whenTrue, whenFalse)` / `Leaf()` — a tree of case-splits the
kernel validates at the return gate. Conservative: it can rescue a
true-but-hard return but never launder a false one (a false leaf simply won't
discharge). Distinct from a **receipt** (which records a discharge the engine
made) — a proof *supplies* one the engine couldn't.

**Proof Authority (PA)** — *Roadmap goal, not yet implemented.* A trusted
issuer whose receipts are accepted by attribution rather than independent
validation. Mirrors how Certificate Authorities work without literally
being them. Snake oil becomes a *status* (receipts from any unrecognized
issuer) rather than a class of receipt. See TODO → "Deep work — oracle
territory."

**receipt** — A fact about a sub-computation. Two kinds, in two
locations:
- **Initial / body receipts** — transcribed deterministically from the
  source (the body equation `r_0 = n * r_1`, the arm guard `n_0 > 0`,
  etc.); produced by the drafter; live *inside* the receipt-graph.
- **Closing / derived receipts** — produced by an issuer to discharge
  an obligation (`r_0 >= 1`, etc.); *separate* artifacts that reference
  into the graph but never extend it. Each carries an issuer
  identifier, a conclusion, a graph reference, and arbitrary
  issuer-specific payload. The notary reads only `(issuer, conclusion,
  reference)`.

Not strictly accurate as a term, but "show me the receipts" carries it.

**receipt-graph** — The data structure produced by the drafter from
source. Nodes are call sites; leaves carry the body's symbolic
equations / inequalities (`r_0 = 1`, `r_0 = n * r_1`, …); edges encode
control flow plus recursion as back-references. **One artifact,
immutable** — once drafted, never changes. Closing receipts produced
by issuers are *separate* (see "receipt") and reference into the graph
but never extend it. Lives in `pontif-receipts`. See `receipt-graph.md`
for the worked example.

**this** — The statement's subject. In a method, the injected receiver
(referenced `this.field`); generalizes to the type under declaration in a struct
decl. Distinct from `@`, which stays the value-under-refinement of the enclosing
bracket-form — the IR node `IrExpr.SelfRef` / `SymExpr.Self` is `@`, despite the
legacy name. Renamed from `self` (2026-06-08): `this` is the implicit-receiver
convention; `self`-as-explicit-arg is Python's. See `alternative-syntax.ptf`
principle 3.

**snake oil** — A closing receipt the notary can't refute (so it's
accepted) but also can't independently re-derive. Allowed and flagged.
Becomes "authentic by attribution" when signed by a trusted Proof
Authority — see PA entry.

**trait** — A named method contract that nominal struct types opt into.
Declared as a sort: `trait Duck{quack:[Method():Audio]}`. Types
satisfy a trait via `assign trait T:Duck { ... }`, which both declares
the impl methods and registers the (`T`, `Duck`) pair in the
`TraitRegistry`. *Pontif's polymorphism mechanism is narrowing, not a
parallel dispatch axis* — the existing sort system + `:` operator
handles trait satisfaction the same way it handles refinement
narrowing. The runtime dispatch table has a fallback rule that
redirects `Trait.method(value, ...)` calls to `Type.method(value, ...)`
when the value's concrete type satisfies the trait. (This redirect is slated to
collapse into ordinary mechanism-2 receiver-sort resolution under **dispatch
unification**, Phase 3.) See
`docs/traits.md` for the full design.

**tuple** — The anonymous positional **aggregate**: value `(1, true)`, sort
`[(Int, Bool)]`, destructure `[(a, b)]` / `[(a, _, c)]`. A struct with the type
name (and behavior) turned off; stored with positional keys `_0 .. _n` under the
`_tuple` sentinel, so it rides the record substrate with no dedicated node.
Components are **destructure-only** — there is no value-level `t._0` (only `@._0`
inside a sort refinement). Arity ≥ 2. Positional patterns obey **positional
totality**. A tuple carries only *independent* per-component constraints
(`[([Int:@>0], Bool)]`); a constraint that *relates* components (`_0 > _1`) is
rejected by design — a relationship is a named concept (a struct, fields by
name: `[Interval:@.lo <= @.hi]`), not anonymous data.

**`Type`** — Pontif's kind name for the sort-of-sorts. A trait sort
has kind `Type`. The syntactic form `Type{methodName:FunctionSort, ...}`
constructs a trait contract; combined with `trait X{...}` it
declares a named trait. *Reserved keyword in the alt parser.*

**spec-only function** — A function declaration with no body, where the
return refinement pins a single value (e.g.,
`function timesTwo(n:Int):[Int:n*2]`). The body is synthesized from the
return refinement's `@==EXPR` form. A return that *doesn't* pin a value
(a plain base/struct sort like `:Vec2`, or a range like `[Int:@>0]`) has
no body to synthesize and is a **hard error** at the declaration — real
synthesis from such a spec is deferred program-search work. See TODO
under "Alt syntax — surface forms that parse but produce `IrStmt.NoOp`."

**univocal** — Pontif's organizing principle (`docs/univocal-language-design.md`):
one algebra *said in one sense of many subjects* — refinements, coercions,
queries, streams, and records all speak it while each keeps its own
specialization. "Many ways to do the same thing" is therefore principled
(projections of one algebra), not accidental redundancy. Crucially a *lens, not a
cage*: the unification is descriptive (shared machinery, the synthesizer reading
one term-shape) and never an enforced canonical form — honesty stays
local-per-claim at the gates, not global spelling-conformance. Term: *univocity*,
the antonym of polysemy; the working title *polylexic* is retired.

**struct-extension** — the one construct `struct Name:[Base:rel](fields)`:
`:[…]` is the is-a face, `(…)` the has-a face (the bracket/paren law). Unifies
struct inheritance and union supertypes; `Structural.baseSort` carries the parsed
`[Base:rel]`. **The is-a base must be a declared STRUCT** — a primitive can only
be encapsulated as a field, not is-a'd (so record-is-a-scalar / newtypes /
refinement-subtyping *over a primitive*, e.g. `Complex:[Decimal:@==r](r,i)`, is a
deferred decision — ruled 2026-06-08). See `docs/univocal-language-design.md`.

**demotion / promotion** — the two subtype casts, governed by *lose freely,
fabricate never*. **Demotion** (subtype → supertype, `let b:Point = p`) runs the
declared **morphism** — a total functional map pinning every base field
(`@.x==x & @.y==y`) — dropping unmentioned fields: a clean forget, no surviving
tag (`b.z` errors). **Promotion** (supertype → subtype) can't conjure the missing
fields, so it's never an implicit cast — an explicit, synthesized construction:
a function, a method, or a value-pin merge (`let q:[Point3D:@.z==0] = b;`). Both
ride the construction gate; `a/b` dropping its remainder is demotion's analogue
(the conservative pair is its promote).

**synthesis directive (`;`)** — a trailing `;` in place of a body/value: the
explicit, sole trigger for spec-only synthesis (functions, methods, lets).
Bodyless-without-`;` is an error; `;` on a sort that pins no value is a "does not
pin" error. Synthesis is opt-in and visible — never implicit.

**construction pin** — a return sort `Name{e1, …}` over a declared struct:
synthesis sugar for `[Name:@ == Name(e1, …)]` (values positional into declared
fields), so a `;` function's body is the construction. Definitional — the
declared return collapses to the bare struct. Sibling of the value pin (`@==EXPR`).

**in-type pipeline** — a staged synthesis directive in sort position,
`[let x:S = E -> … -> Base:@==witness]`: the `let` stages compute (calling global
functions by name — no import), the final pin returns. Desugars to
`@ == (let x = E in … in witness)` and rides the synthesis path; the `->` is the
same bind as streams / queries, now inside a type. Not a new executable sort kind.
The final pin may also carry a **postcondition**: in `[Int:@==r & @>0]` the `@==`
conjunct DEFINES the body, the rest (`@>0`) is the property the gate PROVES —
synthesis and verification in one pin (e.g. the recursive `factorial` defined and
proven-positive at once).

**`^`** — the power operator. `Int^Int` (repeated multiplication, exponent ≥ 0)
and Decimal-promoted `Decimal^Int`; binds tighter than `*`. Fenced from
refinement predicates (the discharge kernel is linear) and opaque in the receipt
drafter (like `/`). Negative and non-integer (transcendental) exponents are
runtime errors — honestly rejected, never approximated.
