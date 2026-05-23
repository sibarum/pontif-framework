# Pontif TODO

Running list of tech debt and follow-up items flagged while building. Each
entry: one-line description + enough context to pick it up later. Resolved
items get removed (history is in git); this file is forward-looking.

---

## ⭐ Next priority — close the proof-engine gaps

Pontif's identity is "proof at compile time, not runtime." The dispatch and
field-access paths honor that today; the rest still falls back to runtime
checks. These four items move Pontif closer to its own stated principle:

1. **Compile-time match totality.** Today the runtime throws `RuntimeCheckException`
   when no arm matches. The compiler should prove that the disjunction of arm
   predicates is equivalent to the scrutinee's sort, and reject non-exhaustive
   matches at compile time. Needs the proof engine to discharge a
   disjunction-covers-sort obligation.
2. **Return-type proof for non-trivial bodies.** `SignAnalysis` handles simple
   monotonic cases (`x*x` produces non-negative). Anything more complex — chained
   conditions, refinement composition through calls, dependent equalities — silently
   trusts the body. Needs a proper proof engine for the body-satisfies-return
   obligation. This is what makes spec-only synthesis truly safe.
3. **`Function` sort runtime validation.** `Refinements.satisfiesFunction` exists
   but isn't wired into dispatch. Passing a closure of the wrong arity / wrong
   sort to a function-typed param currently succeeds at the dispatch step and
   errors later in unhelpful ways.
4. **Sort checking inside refinement predicates.** `SortChecker` doesn't recurse
   into `IrSort.Refined.predicate()`. Field accesses against `@` inside a
   refinement aren't validated. Needs a symbolic-layer extension.

(The full proof-engine arc is at the bottom under "Deep work" — this section
flags the four nearest-term items to bite off first.)

---

## Type system

- **Function return sorts in `SortChecker`.** Currently the type env carries only let-binding / param sorts. `(field (call f) x)` can't be validated because `f`'s declared return sort isn't propagated into expression-level inference. Building a `Map<String, IrSort>` of `functionName → returnSort` from `IrModule.statements()` at the start of `SortChecker.check` would close this.
- **Record-literal vs. declared-sort mismatch.** `(let p (struct P (x Int) (y Int)) (record (x 1)) ...)` declares two fields but the record only has one. `SortChecker` doesn't notice. Symmetric "record shape matches declared sort" check would catch it.
- **Narrowing for non-`Var` match scrutinees.** `SortChecker` narrows a scrutinee's sort inside a structural-pattern branch only when the scrutinee is an `IrExpr.Var`. The parser always emits a synthetic outer let (so the scrutinee IS a Var after desugar) — but if someone hand-builds an `IrExpr.Match` directly with a non-Var scrutinee, narrowing is skipped.
- **Sort checking inside refinement predicates.** `SortChecker` doesn't recurse into `IrSort.Refined.predicate()` because predicates compile through `compileSymExpr`/`SymExpr`. Field accesses against `self` inside a refinement currently aren't validated. Needs symbolic-layer extension.
- **`Function` sort isn't validated at runtime.** A function declared with return sort `(function (Int) Int)` doesn't actually check that the lambda body produces an `Int → Int`. `Refinements.satisfiesFunction` exists; not wired in.
- **Destructuring through a type alias.** Match patterns that name an alias (e.g., `(match p (Point body))` where `Point` aliases a struct sort) get correctly resolved by `AliasResolver` — but the parser's destructuring desugar runs *before* alias resolution, so it doesn't see the structural shape and skips field-binding. Users must use the literal struct sort in the pattern to get destructuring, or use explicit `(field p x)` in the body. Fix: move destructuring out of the parser into a post-`AliasResolver` IR pass.
- **`toSymExpr` for `Closure`/`LambdaValue`.** Passing a lambda/closure as an argument to a function call goes through dispatch, which calls `toSymExpr(arg)` to build symbolic args for refinement check. `toSymExpr` only knows Long / Integer / Boolean / RecordValue today; a closure throws. Either lift closures to `SymExpr.Lam` for dispatch, or short-circuit `toSymExpr` for non-refined param positions.

## Match / patterns

- **Compile-time totality proof.** The alt parser accepts any non-empty set of match arms; if no arm matches at runtime, `IrInterpreter.evalMatch` throws `RuntimeCheckException`. Per the doc principle 8, match must be total — the compiler should prove that the disjunction of arm predicates is equivalent to the scrutinee's sort. Needs the proof engine to discharge the disjunction-covers-sort obligation.
- **Explicit-binding / rename syntax.** E.g., `(struct Point ((x Int) as a) (y Int))` to rebind `x` as `a`. Not pressing while implicit binding covers the common case.
- **Nested destructuring.** Currently only top-level fields auto-bind; inner records still require `(field inner n)` chains. Recursive destructuring would let you write `(struct Outer (inner (struct Inner (n Int))))` and have `n` directly bound.
- **Underscore `_` in let-bindings and function params.** `(let _ Int sideEffectExpr body)` to discard a value; `((_ Int))` to declare a deliberately-unused param. Pure-mode use is just clarity (no warning to silence yet); becomes important once impure expressions / actions exist and a discarded result needs to read as intentional. (Note: the originally-flagged "underscore for ignored struct-pattern fields" turned out unnecessary — partial patterns work today; see `PartialPatternTest`.)
- **Pattern struct-name is currently cosmetic.** `(struct AnyName (x Int))` matches any value with a compatible `x` field, regardless of the value's declared sort name — matching is purely structural. Decide whether this is intended (Pontif as structurally-typed) or whether patterns should reject mismatched names (Pontif as nominally-typed). The current behavior is pinned by `PartialPatternTest.patternStructName_isCosmetic_matchesPurelyByShape`.

## Boolean / predicates

- **Short-circuit evaluation for `&&` and `||`.** Currently strict — both operands evaluate. `(|| expensive false)` evaluates `expensive` even though the right operand short-circuits it. Either add dedicated `ShortCircuitOr`/`And` nodes or special-case in interpreter/lowering. Critical once impure expressions (actions) exist.
- **No `Not` operator.** `[!= 0]` works via `NE` but real Boolean negation `(not (isPrime self))` isn't expressible. Needs `SymExpr.Not` + a unary-op shape in IR (currently only `BinOp` exists).
- **No `/` (division) operator.** `AltLexer` recognizes `/` as an `OP` token, but `IrExpr.Op` has no `DIV` and the interpreter has no case. Easy to add when needed.
- **`SignAnalysis` doesn't reason about `&&` / `||`.** It uses `instanceof` chains, not sealed switches, so adding the variants didn't break it — but it also can't infer bounds from `(x > 0) && (x < 10)`. Worth extending when refinement arithmetic gets harder.

## Exception handling

- **`IllegalArgumentException`/`IllegalStateException` audit.** Several throw sites in `IrCompiler` and the AST validators conflate "framework bug" (should stay unchecked) and "user error" (should be `CompileException`). Audit pass, reclassify case-by-case.
- **`SelfRef` at runtime → `CompileException`.** The interpreter throws `IllegalStateException("Self has no runtime value")` if `SelfRef` reaches it. Could become a `CompileException` with origin if you decide that's a user-level error worth surfacing properly.

## Architecture

- **`Closure` (`pontif-ir`) vs. `LambdaValue` (`pontif-ast`) parallel types.** Same conceptual role (closure value), different inner shapes because the interpreter and the Truffle-lowered AST need different representations (`Environment` vs `CallTarget + captures[]`). Not a bug, but two places to keep in sync if closure semantics ever change.

## Playground / dasum integration

- **`StandardInput.install(window, cursors)` helper upstream.** The playground's `wireInput` is ~110 lines of generic GLFW-callbacks-to-controllers boilerplate vendored from the demo. A reusable helper in `dasum-core` would let the playground (and any future dasum app) shrink that to one call.
- **Origin → editor caret jump.** When a status-ribbon error has an `<editor>:L:C` origin, clicking it should move the editor caret to that position. Needs `line:col → character offset` conversion + `TextStates.of(codeText).setCaretIndex(...)`.
- **Interactive verification.** The playground launches and renders cleanly under timeout but I can't drive button clicks from a shell. File dialog (Open/Save/Save As) and status-ribbon click-to-history dialog need eyes-on validation.
- **Playground uses the S-expr parser only.** `App.onRunClicked` calls `PontifCompiler.compile`, which uses the S-expr `Parser`. To run alt-syntax programs, the compiler or runner needs an `Engine`-style enum for "which frontend." Or a separate "alt" toolbar button. Or autodetection (try alt first, fall back to S-expr).

## Alt syntax — surface forms that parse but produce `IrStmt.NoOp`

Each needs IR semantics added to replace the placeholder. Ordered roughly by leverage / ease.

- **Top-level `let qualified.name:Sort`** with maximally-specific sort. Same synthesis problem spec-only functions handle, but at value level: `let Point.origin:Point[x:0, y:0]` derives the value `(record (x 0) (y 0))`. The qualified name becomes a 0-arg function declared in dispatch.
- **Under-specified return-type proof.** Spec-only declarations like `function f():[Int>=0]` (no body, return doesn't pin a single value) still emit `NoOp`. The right answer isn't synthesis — it's the proof engine. Either the user must provide a body and we discharge that it satisfies the spec, or we error with "no derivable body, please provide one". Currently we silently NoOp, which means the dispatch table doesn't know `f` exists. Either path is fine; pick when the proof engine is closer.
- **`requires`, `exports`.** No semantics until the module system lands. See "New language features" below.

## Alt syntax — surface forms not yet parsed (would error today)

- **Named-parameter function sorts: `[Function(x:Int):[Int:x+n]]`**. Lets dependent return refinements reference the function's own parameter. AltParser throws a clear "not yet supported" error when it sees `(name:Sort)` inside `Function(...)`. Needs `IrSort.Function` to carry param names — both IR and parser need extension. Anonymous-param form (`[Function(Int):Int]`) works today.
- **Inline lambda creation.** `[Function(...):Ret]` is parseable as a sort but you can't create a value of that sort from alt syntax. Probably want something like `(x:Int) -> x+1`. Design call.
- **Refinement of refined sorts via `&`**. `[Even & Positive]` (intersection) parses operator-wise but isn't represented in the IR — no `IrSort.Intersection`. Same for `[Int|Float]` union sorts. Defer until a use case forces it; the predicate-level union `[Int:0|1]` covers the common case.

## New language features

- **Module system: `requires`, `exports`, namespacing.** Currently `module` is a label, `requires`/`exports` are no-ops. Needs a loader, symbol resolver, and compile-time linking. Decide whether modules are file-scoped, whether names are auto-imported from same-module decls, how cycles are handled.
- **Action classes / mutable semantics.** Pure functions stay pure; actions are the controlled escape hatch. Likely as a side-by-side IR family (`IrAction`, `IrActionStmt`) rather than a tag on `IrExpr`, mirroring how `IrSort` and `IrExpr` are sealed siblings. Pre/post-condition refinements via `@` and `old.*` naturally extend the existing refinement machinery.

## Deep work — the proof engine

- **Underspecified return-type verification.** Verify that `function squareNonneg(x:[Int>=0]):[Int>=0] -> x*x` actually produces a non-negative result given a non-negative input. `Refinements.discharge` + `SignAnalysis` already handle some of this; full coverage is the long game and where Pontif's identity comes from.
