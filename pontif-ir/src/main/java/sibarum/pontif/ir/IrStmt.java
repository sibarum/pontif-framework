package sibarum.pontif.ir;

import sibarum.pontif.core.Origin;

import java.util.List;
import java.util.Map;

public sealed interface IrStmt permits IrStmt.FunctionDecl, IrStmt.TypeAlias, IrStmt.TraitImpl, IrStmt.Coercion, IrStmt.Proof, IrStmt.ReturnProof, IrStmt.Requires, IrStmt.Exports, IrStmt.ConductorDecl, IrStmt.Spawn, IrStmt.NoOp {

    Origin origin();

    static FunctionDecl functionDecl(
            String name,
            List<IrParam> params,
            IrSort returnSort,
            IrExpr body) {
        return new FunctionDecl(name, params, returnSort, body, Origin.NONE);
    }

    static TypeAlias typeAlias(String name, IrSort sort) {
        return new TypeAlias(name, sort, Origin.NONE);
    }

    static NoOp noOp(String label) {
        return new NoOp(label, Origin.NONE);
    }

    static TraitImpl traitImpl(String typeName, String traitName, List<FunctionDecl> methods) {
        return new TraitImpl(typeName, traitName, methods, Origin.NONE);
    }

    static Proof proof(String functionName, IrExpr proofTree) {
        return new Proof(functionName, proofTree, Origin.NONE);
    }

    static ReturnProof returnProof(
            String functionName, List<IrParam> params, IrSort grantedReturn, IrExpr body) {
        return new ReturnProof(functionName, params, grantedReturn, body, Origin.NONE);
    }

    /** Same-name entries (no renames) — the common shorthand form. */
    static Requires requires(String targetModule, List<String> names) {
        return new Requires(
                targetModule,
                names.stream().map(n -> new RequireEntry(n, n)).toList(),
                Origin.NONE);
    }

    static Requires requiresEntries(String targetModule, List<RequireEntry> entries) {
        return new Requires(targetModule, entries, Origin.NONE);
    }

    static Exports exports(List<String> names, boolean self) {
        return new Exports(names, self, Origin.NONE);
    }

    /**
     * Function declaration. {@code topLevelLet} marks the 0-arg lowering of
     * a top-level {@code let} — these are <b>force-evaluated at program
     * start</b> (declaration order, before main), so a binding's claims are
     * notarized whether or not anything references it. The lazy ruling
     * (2026-06-05) was overturned 2026-06-07: an unreferenced binding was a
     * loophole where an unproven claim's runtime check never ran. Genuine
     * functions ({@code topLevelLet} false) are never forced — a diverging
     * or erroring body is legitimate until applied.
     */
    record FunctionDecl(
            String name,
            List<IrParam> params,
            IrSort returnSort,
            IrExpr body,
            Origin origin,
            boolean topLevelLet,
            Map<String, IrSort> typeParams) implements IrStmt {
        public FunctionDecl {
            params = List.copyOf(params);
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("Function name must be non-empty");
            }
            if (typeParams == null) {
                throw new IllegalArgumentException("Function typeParams must be non-null");
            }
            // `[type E]` slot params (docs/type-parameters.md §2.1), name → bound
            // (null = unbounded). Null values permitted (so LinkedHashMap, not
            // Map.copyOf), order preserved, like Structural.typeParams.
            typeParams = java.util.Collections.unmodifiableMap(
                    new java.util.LinkedHashMap<>(typeParams));
        }

        /** Back-compat: a function with no type parameters. */
        public FunctionDecl(
                String name, List<IrParam> params, IrSort returnSort,
                IrExpr body, Origin origin, boolean topLevelLet) {
            this(name, params, returnSort, body, origin, topLevelLet, java.util.Map.of());
        }

        /** Ordinary function declaration — not a top-level let, no type parameters. */
        public FunctionDecl(
                String name, List<IrParam> params, IrSort returnSort,
                IrExpr body, Origin origin) {
            this(name, params, returnSort, body, origin, false, java.util.Map.of());
        }
    }

    /**
     * A user-defined coercion: {@code cast Target:(name:Source) -> body} — the
     * value-space sibling of a refinement, the definition the cast invocation
     * {@code (Target:value)} resolves to (docs/dispatch-unification.md →
     * "Coercion"; docs/cross-module-dispatch.md). Pontif's answer to Julia-style
     * implicit promotion: a named, explicit {@code Source → Target} transform, not
     * a searched promotion hierarchy.
     *
     * <p>Lowered by {@link IrCompiler} to a 1-param dispatch entry under a reserved
     * synthetic key ({@link Coercions#coerceKey} on the target's base name), so the
     * one shared resolution engine (most-specific + refinement matching) selects the
     * coercion by the value's runtime source sort; consulted by
     * {@code IrInterpreter.evalCast}. Validated by {@code CoercionCheck}: no
     * primitive↔primitive (the closed {@code Int→Decimal} tower stays built-in), the
     * orphan rule (the declaring module owns the source or target base), and at most
     * one coercion per {@code (sourceBase, targetBase)} pair.
     */
    record Coercion(
            IrSort sourceSort,
            IrSort targetSort,
            String paramName,
            IrExpr body,
            Origin origin) implements IrStmt {
        public Coercion {
            if (sourceSort == null) {
                throw new IllegalArgumentException("Coercion sourceSort must be non-null");
            }
            if (targetSort == null) {
                throw new IllegalArgumentException("Coercion targetSort must be non-null");
            }
            if (paramName == null || paramName.isEmpty()) {
                throw new IllegalArgumentException("Coercion paramName must be non-empty");
            }
            if (body == null) {
                throw new IllegalArgumentException("Coercion body must be non-null");
            }
        }
    }

    /**
     * Binds a name to a sort. Resolved away by {@link AliasResolver} before
     * the rest of the compilation pipeline runs — every {@link IrSort.Named}
     * whose name matches a {@code TypeAlias} declaration gets substituted
     * with the aliased sort, transitively.
     */
    record TypeAlias(String name, IrSort sort, Origin origin) implements IrStmt {
        public TypeAlias {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("Type alias name must be non-empty");
            }
            if (sort == null) {
                throw new IllegalArgumentException("Type alias sort must be non-null");
            }
        }
    }

    /**
     * A conductor declaration — the third authorable type (docs/orchestration.md, §Authoring),
     * alongside {@code struct} and {@code trait}. A conductor is a live worker authored as a
     * type: it owns <b>mutable, single-owner state</b> (the one place mutation is provably safe,
     * because one thread drains it) and a set of <b>event handlers</b> (its {@code Action} /
     * {@code Conduit} members — the sort-carried effectful members from the member-unification
     * slice). Surface form: {@code conductor Name { field:Sort = init  handler:[Action(e:E):_] … }}.
     *
     * <p><b>Cut 1 (this record) is parse + represent only</b> — a conductor is inert in the compile
     * / execute pipeline (like {@link Exports}: carried for tooling, contributes nothing to
     * execution) until the seating slice wires it to the runtime {@code Conductor}/{@code Player}
     * and gives its state and handlers meaning. It is authored but not yet seated.
     *
     * @param state     the mutable single-owner state fields, in declaration order
     * @param handlers  the ABSTRACT event handlers by name — {@code Action}/{@code Conduit}
     *                  call-signature contracts with no body (their {@link IrSort.CallSig#typeName()}
     *                  carries which kind)
     * @param reactions the CONCRETE (body-bearing) handlers, each already lowered to a
     *                  {@code #action#}-keyed reaction {@link FunctionDecl} (event param, return
     *                  {@code _}, body). Inert until the conductor is seated (a {@link Spawn}), when
     *                  the linker injects them into the active routing — an unseated conductor's
     *                  reactions never fire (libraries define, the entry point activates).
     */
    record ConductorDecl(String name, List<StateField> state,
                         Map<String, IrSort.CallSig> handlers,
                         List<FunctionDecl> reactions, Origin origin) implements IrStmt {
        public ConductorDecl {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("Conductor name must be non-empty");
            }
            state = List.copyOf(state);
            handlers = Map.copyOf(handlers);
            reactions = List.copyOf(reactions);
        }

        /** One mutable single-owner state field of a conductor: {@code name:sort = init}. */
        public record StateField(String name, IrSort sort, IrExpr init) {
            public StateField {
                if (name == null || name.isEmpty()) {
                    throw new IllegalArgumentException("Conductor state field name must be non-empty");
                }
                if (sort == null || init == null) {
                    throw new IllegalArgumentException("Conductor state field needs a sort and an initializer");
                }
            }
        }
    }

    /**
     * A seating statement — {@code spawn ConductorName} (docs/orchestration.md, §Seating). It
     * <b>activates</b> a declared conductor: the linker injects that conductor's body-bearing
     * handler reactions into the program's active routing so emitted events reach them. Seating is
     * <b>entry-point-only</b> — only a {@code spawn} in the entry module takes effect (honored via
     * the same entry-module selection that makes a required module's {@code main} inert), so
     * {@code requires}-ing a library never silently stands up its conductors.
     *
     * <p><b>Placement</b> (docs/orchestration.md, §Seating — the tier matrix): a bare {@code spawn C}
     * seats on the {@link Placement#MAIN_LANE} (cooperative, synchronous — the same thread as
     * {@code main}); {@code spawn C over thread} seats on its own {@link Placement#THREAD} (the
     * same-process-thread tier). Placement rides the seat, not the conductor's definition — the *app*
     * chooses where each worker runs.
     */
    record Spawn(String conductorName, Placement placement, Origin origin) implements IrStmt {
        public Spawn {
            if (conductorName == null || conductorName.isEmpty()) {
                throw new IllegalArgumentException("Spawn conductor name must be non-empty");
            }
            if (placement == null) {
                throw new IllegalArgumentException("Spawn placement must be non-null");
            }
        }

        /** Which tier a seated conductor runs on (the `over X` axis). More tiers (process, host) later. */
        public enum Placement { MAIN_LANE, THREAD }
    }

    /**
     * Trait impl block — assigns a trait to a struct type and bundles
     * the impl methods together. At compile time, methods are registered
     * in the dispatch table as regular {@link FunctionDecl}s (with the
     * type-qualified name and self-prepended params) and the
     * {@code (typeName, traitName)} pair is added to the
     * {@link sibarum.pontif.core.symbolic.TraitRegistry}.
     *
     * <p>SortChecker validates each contract method has a matching impl
     * (after self-prepending). Surface form (Pontif syntax):
     * {@code assign trait T:Tr { ... }}.
     */
    record TraitImpl(
            String typeName,
            String traitName,
            List<FunctionDecl> methods,
            List<FunctionDecl> attributeProducers,
            java.util.Map<String, IrSort> typeBindings,
            java.util.Map<String, IrSort> typeParams,
            List<IrSort> traitTypeArgs,
            Origin origin) implements IrStmt {
        public TraitImpl {
            if (typeName == null || typeName.isEmpty()) {
                throw new IllegalArgumentException("TraitImpl typeName must be non-empty");
            }
            if (traitName == null || traitName.isEmpty()) {
                throw new IllegalArgumentException("TraitImpl traitName must be non-empty");
            }
            methods = List.copyOf(methods);
            attributeProducers = List.copyOf(attributeProducers);
            // member name -> bound type supplied by `type X = [Sort]`. Map.copyOf
            // is fine (bound sorts are never null here, unlike a trait's optional
            // associated-type bound).
            typeBindings = java.util.Map.copyOf(typeBindings);
            // The impl's own `[type T]` binder (`assign trait Element[type T]:…`,
            // docs/type-parameters.md §2.1) — name → bound, null = unbounded; in
            // scope over the trait args and the method sigs. Distinct from
            // typeBindings (which binds the TRAIT's associated types). LinkedHashMap
            // permits null bounds, like a struct's typeParams.
            typeParams = java.util.Collections.unmodifiableMap(
                    new java.util.LinkedHashMap<>(typeParams));
            // The type arguments applied to the trait name (`…:Stream[T]` → [T],
            // `…:Stream[Int]` → [Int]); empty for a non-parametric trait. Each is
            // matched against the trait's declared `[type E]` parameters.
            traitTypeArgs = List.copyOf(traitTypeArgs);
        }

        /** Back-compat: a non-parametric impl (no `[type T]` binder, no trait args). */
        public TraitImpl(String typeName, String traitName,
                         List<FunctionDecl> methods, List<FunctionDecl> attributeProducers,
                         java.util.Map<String, IrSort> typeBindings, Origin origin) {
            this(typeName, traitName, methods, attributeProducers, typeBindings,
                    java.util.Map.of(), List.of(), origin);
        }

        /** Back-compat: an impl with no associated-type bindings. */
        public TraitImpl(String typeName, String traitName,
                         List<FunctionDecl> methods, List<FunctionDecl> attributeProducers,
                         Origin origin) {
            this(typeName, traitName, methods, attributeProducers, java.util.Map.of(), origin);
        }

        /** Back-compat: a methods-only impl (no provided data attributes). */
        public TraitImpl(String typeName, String traitName,
                         List<FunctionDecl> methods, Origin origin) {
            this(typeName, traitName, methods, List.of(), java.util.Map.of(), origin);
        }
    }

    /**
     * A hand-authored proof for a function's declared return refinement,
     * written in-source as a struct-literal tree (a {@code Refinement}-shaped
     * {@code Leaf}/{@code Split} value). The {@code proofTree} is the
     * <b>unevaluated</b> {@link IrExpr} — never compiled or evaluated, so its
     * {@code Split} predicates stay symbolic. The return-refinement gate
     * ({@code PontifCompiler}) translates it to a
     * {@link sibarum.pontif.receipts.Refinement} and validates it against the
     * named function's obligation; a proof that no longer discharges is a hard
     * compile error. Surface form (Pontif syntax): {@code proof f = Split(...)}.
     */
    record Proof(String functionName, IrExpr proofTree, Origin origin) implements IrStmt {
        public Proof {
            if (functionName == null || functionName.isEmpty()) {
                throw new IllegalArgumentException("Proof functionName must be non-empty");
            }
            if (proofTree == null) {
                throw new IllegalArgumentException("Proof tree must be non-null");
            }
        }
    }

    /**
     * A return-refinement proof attached via {@code assign proof}. Distinct from
     * the shared {@link Proof} statement (the {@code proof f = <tree>} form, also
     * used by the conservation ledger): this carries its own parameter signature,
     * the return refinement it <b>grants and proves</b> ({@code grantedReturn}),
     * and an optional case-function {@code body} whose ordered {@code [guard] ->}
     * arms cut the domain into regions the engine can discharge. A {@code null}
     * body asks the engine to discharge {@code grantedReturn} natively.
     *
     * <p>The return-refinement gate ({@code PontifCompiler}) rewrites the target
     * function's declared return to {@code grantedReturn}, lowers the body to a
     * {@link sibarum.pontif.receipts.Refinement}, and validates it. The function
     * itself declares only a base return; the proof is where the refinement lives
     * (so dispatched proofs can grant different refinements per argument region).
     * Surface (Pontif syntax): {@code assign proof f(params):[ (match s ...) -> [Sort] ]}
     * or {@code assign proof f(params):[Sort]}.
     */
    record ReturnProof(
            String functionName,
            List<IrParam> params,
            IrSort grantedReturn,
            IrExpr body,
            Origin origin) implements IrStmt {
        public ReturnProof {
            if (functionName == null || functionName.isEmpty()) {
                throw new IllegalArgumentException("ReturnProof functionName must be non-empty");
            }
            params = List.copyOf(params);
            if (grantedReturn == null) {
                throw new IllegalArgumentException("ReturnProof grantedReturn must be non-null");
            }
            // body may be null — a bodyless proof asks for native discharge.
        }
    }

    /**
     * One {@code .{…}} decomposition entry: {@code remoteName} is the symbol's
     * name where it already lives (the source module), {@code localName} is its
     * name in the receiving context. The shorthand form {@code min} is
     * {@code (min, min)}; the rename form {@code min -> minimum} is
     * {@code (min, minimum)} — the arrow reads "becomes", uniformly with match
     * arms and function bodies.
     */
    record RequireEntry(String remoteName, String localName) {
        public RequireEntry {
            if (remoteName == null || remoteName.isEmpty()) {
                throw new IllegalArgumentException("RequireEntry remoteName must be non-empty");
            }
            if (localName == null || localName.isEmpty()) {
                throw new IllegalArgumentException("RequireEntry localName must be non-empty");
            }
        }
    }

    /**
     * Import declaration: {@code requires a.b.{name, name -> alias, …}} — pulls
     * the named symbols from module {@code targetModule} (a dotted module name)
     * into this module's scope, each optionally renamed. Consumed by the module
     * loader/linker and the name resolver; inert when a single file is compiled
     * on its own.
     */
    record Requires(String targetModule, List<RequireEntry> entries, Origin origin) implements IrStmt {
        public Requires {
            if (targetModule == null || targetModule.isEmpty()) {
                throw new IllegalArgumentException("Requires targetModule must be non-empty");
            }
            entries = List.copyOf(entries);
        }

        /** The local (receiving-context) names, in declaration order. */
        public List<String> localNames() {
            return entries.stream().map(RequireEntry::localName).toList();
        }
    }

    /**
     * Export declaration: {@code exports @.{name, …}} (this module; {@code self}
     * true) lists the local symbols this module makes visible to importers.
     * Consumed by the linker's visibility check; inert for a single file.
     */
    record Exports(List<String> names, boolean self, Origin origin) implements IrStmt {
        public Exports {
            names = List.copyOf(names);
        }
    }

    /**
     * Placeholder for syntactic forms the parser recognizes but the IR
     * doesn't yet support (e.g., spec-only functions, {@code method}
     * declarations, top-level {@code let} without a body). Carries a
     * human-readable {@code label} of the original form for
     * diagnostics; otherwise contributes nothing to compilation or execution.
     */
    record NoOp(String label, Origin origin) implements IrStmt {
        public NoOp {
            if (label == null) {
                throw new IllegalArgumentException("NoOp label must be non-null");
            }
        }
    }
}
