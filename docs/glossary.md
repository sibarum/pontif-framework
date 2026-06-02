# Pontif Glossary

Terms invented or reframed for Pontif. Alphabetical, terse. When a name is
deliberately different from the conventional one, the entry says why.

---

**`@`** — Principal subject of the enclosing refinement: the value being
refined (in `[Sort:pred]`) or the scrutinee (in a match arm). Each refinement
binds its own `@`; nested refinements shadow. See `alternative-syntax.ptf`
principle 3.

**back-reference** — In a receipt-graph, a recursive call points back to the
same node rather than re-expanding. The no-duplicate-edges rule turns
well-foundedness into a graph property and brings the postcondition along
as an inductive hypothesis automatically.

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

**coherence rule (orphan rule)** — A trait impl `impl Trait for Type` may be
declared only in the module owning `Trait` or `Type`, never a third module
owning neither. Borrowed from Rust; closes the type-piracy hole Pontif's global
trait registry would otherwise open under multi-dispatch. Enforced at link time
(`CoherenceCheck`) over fully-qualified type names. See **module**.

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

**narrowing** — What `:` denotes everywhere. `x:T` reads "x narrows to T."
Used at every level — params, refinements, struct fields, function returns.
Chosen over "has type" / "is of sort" because it's descriptive and reads
left-to-right with the operator.

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

**self** — In a method, the injected receiver. Named explicitly so that
`@` remains unambiguously the value-under-refinement of the enclosing
bracket-form. See `alternative-syntax.ptf` principle 3.

**snake oil** — A closing receipt the notary can't refute (so it's
accepted) but also can't independently re-derive. Allowed and flagged.
Becomes "authentic by attribution" when signed by a trusted Proof
Authority — see PA entry.

**trait** — A named method contract that nominal struct types opt into.
Declared as a sort: `let Duck:Type{quack:[Function():Audio]}`. Types
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

**`Type`** — Pontif's kind name for the sort-of-sorts. A trait sort
has kind `Type`. The syntactic form `Type{methodName:FunctionSort, ...}`
constructs a trait contract; combined with `let X:Type{...}` it
declares a named trait. *Reserved keyword in the alt parser.*

**spec-only function** — A function declaration with no body, where the
return refinement pins a single value (e.g.,
`function timesTwo(n:Int):[Int:n*2]`). The body is synthesized from the
return refinement's `@==EXPR` form. A return that *doesn't* pin a value
(a plain base/struct sort like `:Vec2`, or a range like `[Int:@>0]`) has
no body to synthesize and is a **hard error** at the declaration — real
synthesis from such a spec is deferred program-search work. See TODO
under "Alt syntax — surface forms that parse but produce `IrStmt.NoOp`."
