package sibarum.pontif.ir;

import sibarum.pontif.types.TypeSystem;
import sibarum.pontif.core.QualifiedName;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.types.Sort;
import sibarum.pontif.predicates.ComplementResult;
import sibarum.pontif.predicates.PredicateArithmetic;
import sibarum.pontif.predicates.SatResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compile-time validation pass over a post-{@link AliasResolver} IR module.
 *
 * <p>Three responsibilities:
 * <ol>
 *   <li><b>Unknown sort names.</b> Every {@link IrSort.Named} and the base
 *       name of every {@link IrSort.Refined} must be a primitive or one of
 *       the internal sentinels produced by parser desugars. Alias references
 *       are already substituted away by {@link AliasResolver}, so anything
 *       still named must be a primitive — otherwise it's a typo / undeclared
 *       type and we error at compile time rather than runtime.</li>
 *   <li><b>Unknown function calls.</b> Every {@link IrExpr.Call} must refer
 *       to a declared function name. The set of declared names is collected
 *       up front from the module's {@link IrStmt.FunctionDecl}s.</li>
 *   <li><b>Field access against structural sorts.</b> For every
 *       {@link IrExpr.FieldAccess} whose base sort can be statically inferred
 *       as {@link IrSort.Structural}, the field name must exist in that
 *       sort. Sort inference looks at scope (params / let-bindings), nested
 *       field-access chains, and now {@code Call} expressions via the
 *       collected function-return map.</li>
 * </ol>
 *
 * <p>Errors are {@link CompileException}s carrying the offending node's
 * origin so the editor can point at the source location.
 */
public final class SortChecker {

    /**
     * Sort names that aren't aliases — accepted as terminal sort references.
     *
     * <p>{@code Int}, {@code Bool} are primitives. Function-typed bindings
     * use {@link IrSort.Method} (with explicit param + return shapes),
     * not a placeholder Named sort.
     *
     * <p>{@code "_"}, {@code "_record"}, and {@code "_tuple"} are internal
     * sentinels produced by parser desugars (match-destructure scrutinee
     * placeholder, anonymous by-name record inference fallback, and anonymous
     * positional aggregate / tuple, respectively). They aren't user-typable but
     * leak into the IR and must validate.
     */
    private static final Set<String> PRIMITIVE_SORT_NAMES = Set.of(
            "Int", "Bool", "Decimal", "Char", "String",
            "_", "_record", "_tuple");

    /**
     * Parametric trait names valid in a sort position even though they are not
     * declared structs (so {@link sibarum.pontif.types.TypeCatalog#structShapes} — structs only — does not
     * carry them). {@code Stream[T]} is the genuine {@code pontif.core} trait now
     * that the {@code Element|Leaf} cons-cell is retired (stream-trait war,
     * docs/stream-war.md §7); a tuple autoboxes into it and the autobox's element
     * gate is the membership check.
     *
     * <p>This is hardcoded rather than read off the module's traits because
     * {@link #validateSortNames} is not yet trait-aware — threading the trait
     * registry through its ~25 call sites is a separate refactor (the general
     * "any imported parametric trait is a valid sort name" gap). Until then this
     * is an <em>incompleteness</em>, not a lie: it correctly admits {@code Stream},
     * it just won't yet admit a user's own parametric trait in a let/param sort.
     */
    private static final Set<String> BUILTIN_PARAMETRIC_TYPES = Set.of("Stream", "Cell");

    private SortChecker() {}

    /**
     * Phase one, separable and run first: every DECLARED sort names a type that resolves.
     *
     * <p>This asks nothing of a value, so it depends on nothing the value-level passes do — and
     * that is why it is worth having on its own. Run ahead of the construction gate
     * ({@link IrCompiler}), it means a typo in an annotation is reported as a typo. Left where it
     * used to sit — inside the main {@code check} loop, after the gate — the gate spoke first and
     * answered a different question about the same mistake: {@code function f(x:Int):Box[Bad] -> x}
     * came back as "the returned Int is disjoint from Box", which is true, and useless, when what
     * the author needs to know is that {@code Bad} names nothing.
     *
     * <p>Idempotent and cheap; {@link #check} still calls it so a caller invoking only {@code check}
     * loses nothing.
     */
    public static void checkSortNames(IrModule module) throws CompileException {
        Map<String, IrSort.Trait> traitContracts = collectTraitContracts(module);
        Map<String, IrSort.Structural> structDefs =
                sibarum.pontif.types.TypeCatalog.fromModule(module).structShapes();
        Set<String> traitNames = traitContracts.keySet();
        // Declared type-alias names — a reusable-sort alias may reference another alias
        // (`let B:Type[A]`); AliasResolver does not inline names inside an alias declaration's own
        // target, so validating those targets must treat a declared alias name as known.
        Set<String> aliasNames = new HashSet<>();
        for (IrStmt s : module.statements()) {
            if (s instanceof IrStmt.TypeAlias ta) aliasNames.add(ta.name());
        }
        for (IrStmt stmt : module.statements()) {
            switch (stmt) {
                case IrStmt.FunctionDecl fd -> checkDeclaredSortNames(fd, structDefs, traitNames);
                // A trait IMPL's sorts stay with validateTraitImpl (phase two), which validates the
                // same names against the trait contract and so has a sharper account of what went
                // wrong — "the impl binds 2 type parameters, Box declares 1" rather than the bare
                // arity mismatch this phase would report first.
                // Every type declaration's target sort — a trait's member sorts, a struct's fields
                // and type-param bounds, a reusable alias's target. A STRUCT had been excluded on
                // the belief that its fields were validated "via the struct's own path", but the
                // only struct-specific path is validateStructBase, which validates the is-a base
                // and nothing else — so `struct Status(text:Str)` compiled with `Str` naming
                // nothing (docs/soundness-holes.md, "what is still open").
                case IrStmt.TypeAlias ta -> validateSortNames(ta.sort(), structDefs, aliasNames, traitNames);
                default -> { /* proofs, requires, exports, spawns declare no sort */ }
            }
        }
    }

    /** A declaration's param and return sorts, with its own type parameters in scope. */
    private static void checkDeclaredSortNames(
            IrStmt.FunctionDecl fd, Map<String, IrSort.Structural> structDefs, Set<String> traitNames)
            throws CompileException {
        // The function's `[type E]` parameters are bound type variables in scope for its param and
        // return sorts (docs/type-parameters.md §2.1), so `x:E` validates — exactly as a
        // struct/trait scopes its own type params.
        Set<String> typeVars = fd.typeParams().keySet();
        for (IrParam p : fd.params()) validateSortNames(p.sort(), structDefs, typeVars, traitNames);
        validateSortNames(fd.returnSort(), structDefs, typeVars, traitNames);
    }

    public static void check(IrModule module) throws CompileException {
        checkSortNames(module);
        Map<String, IrSort> functionReturns = collectFunctionReturns(module);
        Map<String, IrSort.Trait> traitContracts = collectTraitContracts(module);
        Map<String, IrSort.Structural> structDefs =
                sibarum.pontif.types.TypeCatalog.fromModule(module).structShapes();
        // Free-function / operator overloads by name (e.g. all `+` declarations) —
        // the mechanism-1 dispatch entries an operator trait contract is checked
        // against (does a coherent `+(T, T):T` exist for the satisfying type T?).
        Map<String, List<IrStmt.FunctionDecl>> overloads = collectOverloadsByName(module);
        // Functions carrying an `assign proof f:Algebraic` claim — so the field-existence
        // gate can stamp a metareference `$f[…]` as AlgebraicDispatch (its `.ast` resolves)
        // vs DispatchBase (`.ast` is a compile error). Mirrors IrCompiler/InferenceContext.
        Set<String> algebraicFunctions = collectAlgebraicFunctions(module);
        // The declared trait-satisfaction relation (type satisfies trait) — used
        // to check associated-type bounds. Built through the shared TraitRegistry
        // so the bound check sees the SAME transitive relation the runtime uses
        // (inherited impls via trait-extends / struct-inheritance chains), not a
        // flat impl-only string set that misses them.
        sibarum.pontif.core.symbolic.TraitRegistry satisfies = TraitRelations.from(module);
        // Declared type-alias names — a reusable-sort alias may reference another alias
        // (`let B:Type[A]`); AliasResolver does not inline names inside an alias declaration's own
        // target, so validating those targets (below) must treat a declared alias name as known.
        Set<String> aliasNames = new HashSet<>();
        for (IrStmt s : module.statements()) {
            if (s instanceof IrStmt.TypeAlias ta) {
                aliasNames.add(ta.name());
            }
        }

        // Which method short-names each type's OWN `assign trait` block provides,
        // keyed type -> trait -> {method}. A sub-struct may provide only some of a
        // trait's methods and inherit the rest from its base struct's impl (the
        // dispatcher already routes per-method up the is-a chain); the completeness
        // check consults this to credit those inherited methods.
        Map<String, Map<String, Set<String>>> traitMethodsByType = new HashMap<>();
        for (IrStmt stmt : module.statements()) {
            if (stmt instanceof IrStmt.TraitImpl ti) {
                Map<String, Set<String>> byTrait = traitMethodsByType
                        .computeIfAbsent(ti.typeName(), k -> new HashMap<>());
                Set<String> ms = byTrait.computeIfAbsent(ti.traitName(), k -> new HashSet<>());
                for (IrStmt.FunctionDecl m : ti.methods()) {
                    ms.add(m.name().substring(m.name().lastIndexOf('.') + 1));
                }
            }
        }

        // The methods each struct declares directly — its OWN `Type.method` decls,
        // whether written in a struct member block or as a standalone `method`. A
        // struct's traits are verified against this pool (the some-method rule: a
        // struct-block trait obligation `struct S:[…&T]{ … }` is discharged by the
        // struct's own method of the same name, signature-checked against T's
        // contract). Keyed by both the qualified type-name and its bare short name so
        // the lookup is robust to the linker's module qualification, mirroring the
        // short-name normalization the completeness check already uses.
        Map<String, Map<String, IrStmt.FunctionDecl>> methodsByType = new HashMap<>();
        for (IrStmt stmt : module.statements()) {
            if (stmt instanceof IrStmt.FunctionDecl fd) {
                int dot = fd.name().lastIndexOf('.');
                if (dot < 0) continue;
                String typePart = fd.name().substring(0, dot);
                String shortName = fd.name().substring(dot + 1);
                methodsByType.computeIfAbsent(typePart, k -> new HashMap<>())
                        .putIfAbsent(shortName, fd);
                methodsByType.computeIfAbsent(lastPathSegment(typePart), k -> new HashMap<>())
                        .putIfAbsent(shortName, fd);
            }
        }

        // Struct is-a relationships (`struct Name:[Base:rel](fields)`): the base
        // must resolve, and a struct-base morphism must functionally pin every
        // base field. Validated once per declared struct.
        for (IrStmt stmt : module.statements()) {
            if (stmt instanceof IrStmt.TypeAlias ta
                    && ta.sort() instanceof IrSort.Structural s
                    && s.baseSort() != null) {
                validateStructBase(s, structDefs);
            }
        }

        for (IrStmt stmt : module.statements()) {
            if (stmt instanceof IrStmt.FunctionDecl fd) {
                Map<String, IrSort> typeEnv = new HashMap<>();
                // The function's `[type E]` parameters are bound type variables in
                // scope for its param and return sorts (docs/type-parameters.md
                // §2.1), so `x:E` validates — exactly as a struct/trait scopes its
                // own type params.
                Set<String> fnTypeVars = fd.typeParams().keySet();
                for (IrParam p : fd.params()) typeEnv.put(p.name(), p.sort());
                checkExpr(fd.body(), typeEnv, functionReturns, structDefs, fnTypeVars, algebraicFunctions);
                // Operator bound propagation (dispatch-unification B1): an operator
                // applied to a value of a trait-bounded type parameter is checked
                // against the bound's operator contract members — `a + b` over
                // `E:Numeric` is licensed only if `Numeric` declares `+`. Makes
                // operator use over an abstract type decidable at definition time,
                // not at the call site's monomorphization.
                checkOperatorBounds(fd, typeEnv, traitContracts, functionReturns);
            } else if (stmt instanceof IrStmt.TraitImpl ti) {
                validateTraitImpl(ti, traitContracts, functionReturns, structDefs, satisfies, overloads, algebraicFunctions, traitMethodsByType, methodsByType);
            } else if (stmt instanceof IrStmt.TypeAlias ta && ta.sort() instanceof IrSort.Trait tr) {
                // Trait declarations are name-validated by checkSortNames, up front.
            }
        }
        checkExpr(module.main(), new HashMap<>(), functionReturns, structDefs, algebraicFunctions);
        checkMetareferencePropagation(module, traitContracts, overloads, algebraicFunctions);
        validateProofMarkers(module, structDefs, traitContracts);
    }

    /**
     * A {@code proof f = Marker(…)} / {@code assign proof f:Marker} statement names a TYPE
     * as its marker — the head that picks the proof system ({@code Algebraic}, {@code
     * DataConservative}, {@code Split}, …). That marker must be in scope like any other type
     * reference: a declared struct/trait, or one imported with {@code requires}. Proof-tree
     * heads are the one place a type name is written but never flows through {@link
     * #validateSortNames} (a proof tree is an expression, not a sort), so without this the
     * globally-recognized builtin markers ({@code Algebraic} and future ones, kept spellable
     * bare by {@link NameResolver}) could be used with no import — the reference narrows and
     * then its members fail far away with a misleading "no member" error. Checking the marker
     * here closes that hole generally: nothing is keyed on a specific marker name; any marker
     * whose type isn't in scope gets the ordinary {@link #unknownSort} error, so a metareference
     * proven {@code Algebraic} without {@code requires pontif.algebra} is rejected at the claim.
     */
    private static void validateProofMarkers(
            IrModule module, Map<String, IrSort.Structural> structDefs,
            Map<String, IrSort.Trait> traitContracts) throws CompileException {
        // The names of every type in scope, compared by MEMBER name (the segment after any
        // `module/` prefix). A marker head is FQN'd by the linker's call-name rule while the
        // matching declaration may stay bare (a builtin marker like `Algebraic` is kept bare
        // by NameResolver), so a raw-key lookup would spuriously miss; the member name is the
        // stable identity across that asymmetry and across the bare single-file path.
        Set<String> inScope = new HashSet<>();
        for (String k : structDefs.keySet()) inScope.add(sibarum.pontif.core.QualifiedName.memberOf(k));
        for (String k : traitContracts.keySet()) inScope.add(sibarum.pontif.core.QualifiedName.memberOf(k));
        for (IrStmt stmt : module.statements()) {
            if (!(stmt instanceof IrStmt.Proof p)) continue;
            String head = switch (p.proofTree()) {
                case IrExpr.Record r -> r.typeName();
                case IrExpr.Call c -> c.functionName();
                default -> null;   // a non-nominal proof tree carries no marker type to check
            };
            if (head == null) continue;
            String marker = sibarum.pontif.core.QualifiedName.memberOf(head);
            if (!inScope.contains(marker)) throw unknownSort(marker, p.origin());
        }
    }

    /**
     * The metareference-propagation gate (docs/dispatch-method-elimination.md §1d): a
     * metareference argument passed where a TRAIT is required must have a concrete dispatch
     * nominal that is-a that trait — so {@code g($poly[Decimal])} into {@code g(f:Algebraic)}
     * is accepted (AlgebraicDispatch is-a Algebraic) but {@code g($inc[Decimal])} is a compile
     * error (a plain Dispatch is not Algebraic). This closes the gap the direct
     * {@code $f[…].ast} gate can't see — the guarantee travelling THROUGH a parameter.
     *
     * <p>Deliberately narrow (sound, not complete — it only adds rejections, never removes
     * the permissive runtime path the general C3 call-gate will subsume): it fires only for a
     * <em>direct</em> {@code $f[…]} argument to a single-overload callee's <em>trait</em>
     * parameter. A metareference reached through a let/var, a multi-overload callee, or a
     * non-trait ({@code [Dispatch(…)]}) parameter still defers to runtime dispatch.
     */
    private static void checkMetareferencePropagation(
            IrModule module,
            Map<String, IrSort.Trait> traitContracts,
            Map<String, List<IrStmt.FunctionDecl>> overloads,
            Set<String> algebraicFunctions) throws CompileException {
        sibarum.pontif.types.AssignabilityContext actx =
                sibarum.pontif.types.AssignabilityContext.fromModule(module);
        List<IrExpr.Call> calls = new ArrayList<>();
        for (IrStmt stmt : module.statements()) {
            if (stmt instanceof IrStmt.FunctionDecl fd) {
                collectCalls(fd.body(), calls);
            } else if (stmt instanceof IrStmt.TraitImpl ti) {
                for (IrStmt.FunctionDecl m : ti.methods()) collectCalls(m.body(), calls);
                for (IrStmt.FunctionDecl a : ti.attributeProducers()) collectCalls(a.body(), calls);
            }
        }
        collectCalls(module.main(), calls);

        for (IrExpr.Call c : calls) {
            List<IrStmt.FunctionDecl> ovs =
                    overloads.getOrDefault(QualifiedName.memberOf(c.functionName()), List.of());
            if (ovs.size() != 1) continue;   // ambiguous / unknown → defer to runtime dispatch
            List<IrParam> params = ovs.get(0).params();
            for (int i = 0; i < c.args().size() && i < params.size(); i++) {
                if (!(c.args().get(i) instanceof IrExpr.DispatchRef d)) continue;
                IrSort paramSort = params.get(i).sort();
                // Only a TRAIT parameter carries a satisfiability obligation a metareference
                // could violate; a `[Dispatch(…)]` (CallSig) parameter is permissive. The trait
                // may be an inlined IrSort.Trait or a bare Named the catalog knows as a trait.
                String traitName = paramSort instanceof IrSort.Trait t ? t.name()
                        : paramSort instanceof IrSort.Named pn && traitContracts.containsKey(pn.name())
                                ? pn.name() : null;
                if (traitName == null) continue;
                String nominal = algebraicFunctions.contains(d.functionName())
                        ? sibarum.pontif.core.types.Metaref.ALGEBRAIC_DISPATCH
                        : sibarum.pontif.core.types.Metaref.DISPATCH;
                if (!sibarum.pontif.types.Assignability.isA(IrSort.named(nominal), paramSort, actx)) {
                    throw new CompileException(
                            "Metareference '$" + d.functionName() + "[…]' is a '" + nominal
                                    + "', which does not satisfy '" + traitName + "' required by "
                                    + "parameter " + (i + 1) + " of '" + c.functionName()
                                    + "' — only a reference proven algebraic (`assign proof "
                                    + d.functionName() + ":Algebraic`) satisfies it.",
                            d.origin());
                }
            }
        }
    }

    /** Gathers every {@link IrExpr.Call} reachable from {@code e} (into {@code out}). */
    private static void collectCalls(IrExpr e, List<IrExpr.Call> out) {
        switch (e) {
            case IrExpr.Call c -> {
                out.add(c);
                for (IrExpr a : c.args()) collectCalls(a, out);
            }
            case IrExpr.BinOp op -> { collectCalls(op.left(), out); collectCalls(op.right(), out); }
            case IrExpr.LetIn l -> { collectCalls(l.value(), out); collectCalls(l.body(), out); }
            case IrExpr.Lambda lam -> collectCalls(lam.body(), out);
            case IrExpr.Apply ap -> {
                collectCalls(ap.fn(), out);
                for (IrExpr a : ap.args()) collectCalls(a, out);
            }
            case IrExpr.Match m -> {
                collectCalls(m.scrutinee(), out);
                for (IrExpr.MatchBranch b : m.branches()) collectCalls(b.result(), out);
            }
            case IrExpr.Record r -> { for (IrExpr v : r.members().values()) collectCalls(v, out); }
            case IrExpr.FieldAccess fa -> collectCalls(fa.base(), out);
            case IrExpr.Cast cast -> collectCalls(cast.value(), out);
            case IrExpr.Emit em -> { collectCalls(em.event(), out); collectCalls(em.body(), out); }
            case IrExpr.Iterate it -> {
                collectCalls(it.source(), out);
                for (IrExpr cs : it.coSources()) collectCalls(cs, out);
                for (IrExpr.OutputSpec os : it.outputs()) {
                    if (os.init() != null) collectCalls(os.init(), out);
                }
                for (IrExpr.Arm arm : it.arms()) {
                    for (IrExpr.Write w : arm.writes()) {
                        if (w.key() != null) collectCalls(w.key(), out);
                        collectCalls(w.value(), out);
                    }
                }
            }
            // Leaves + MethodCall (resolved away before here): no nested calls to visit.
            default -> { }
        }
    }

    /** Names of functions with an {@code assign proof f:Algebraic} claim (docs/algebra). */
    private static Set<String> collectAlgebraicFunctions(IrModule module) {
        Set<String> out = new HashSet<>();
        for (IrStmt stmt : module.statements()) {
            if (stmt instanceof IrStmt.Proof p) {
                IrExpr tree = p.proofTree();
                String head = switch (tree) {
                    case IrExpr.Record r -> r.typeName();
                    case IrExpr.Call c -> c.functionName();
                    default -> null;
                };
                if (head != null
                        && "Algebraic".equals(sibarum.pontif.core.QualifiedName.memberOf(head))) {
                    out.add(p.functionName());
                }
            }
        }
        return out;
    }

    /**
     * Collects declared traits from preserved {@link IrStmt.TypeAlias}
     * statements whose sort is an {@link IrSort.Trait}.
     */
    private static Map<String, IrSort.Trait> collectTraitContracts(IrModule module) {
        Map<String, IrSort.Trait> map = new LinkedHashMap<>();
        for (IrStmt stmt : module.statements()) {
            if (stmt instanceof IrStmt.TypeAlias ta
                    && ta.sort() instanceof IrSort.Trait t) {
                map.put(t.name(), t);
                // A linked trait's name is FQN'd (`mod/Numeric`) while a
                // type-parameter bound that references it across modules keeps
                // the short form (`Numeric`). Register the member (short) name
                // too so operator-bound resolution finds the contract by either
                // spelling. Full names are canonical — only fill a short key
                // that isn't already taken by a same-named trait.
                String member = sibarum.pontif.core.QualifiedName.memberOf(t.name());
                if (!member.equals(t.name())) map.putIfAbsent(member, t);
            }
        }
        return map;
    }

    /**
     * Verifies a {@link IrStmt.TraitImpl} block against the trait's contract:
     * every contract method must have a matching implementation declared in
     * the block (matched by short name — stripping the {@code Type.}
     * prefix), and the impl's arity must equal {@code 1 + contract param
     * count} (self prepended). Also validates the methods' own sorts
     * recursively and checks their bodies via {@link #checkExpr}.
     */
    /**
     * Flattens a trait's base-trait chain into a single effective contract: own
     * members unioned with every transitive base trait's members (WAR(stream)
     * trait-extends-trait). Merged base-first so a derived trait may refine a base
     * member (own entry wins on a name collision). A base that names no declared
     * trait, or a cyclic chain, is a hard error. A root trait (no base) is returned
     * unchanged. (Base type-parameter substitution across a parametric base — e.g.
     * {@code IndexedStream[E] : Stream[E]} — is a follow-up; this merges members
     * structurally, which covers the non-parametric and same-binder cases.)
     */
    private static IrSort.Trait flattenTrait(
            IrSort.Trait trait, Map<String, IrSort.Trait> traitContracts,
            sibarum.pontif.core.Origin origin)
            throws CompileException {
        if (trait.baseTrait() == null) {
            return trait;
        }
        List<IrSort.Trait> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        IrSort.Trait cur = trait;
        while (cur != null) {
            if (!seen.add(cur.name())) {
                throw new CompileException(
                        "Trait '" + trait.name() + "' has a cyclic base-trait chain at '"
                                + cur.name() + "'", origin);
            }
            chain.add(cur);
            String base = cur.baseTrait();
            if (base == null) break;
            IrSort.Trait next = traitContracts.get(base);
            if (next == null) {
                throw new CompileException(
                        "Trait '" + cur.name() + "' extends unknown trait '" + base
                                + "' (no trait declaration in scope)", origin);
            }
            cur = next;
        }
        Map<String, IrSort.CallSig> methods = new LinkedHashMap<>();
        Map<String, IrSort> attributes = new LinkedHashMap<>();
        Map<String, IrSort> associatedTypes = new LinkedHashMap<>();
        Map<String, IrSort> typeParams = new LinkedHashMap<>();
        Map<String, IrSort.CallSig> operators = new LinkedHashMap<>();
        Map<String, IrStmt.FunctionDecl> methodDefaults = new LinkedHashMap<>();
        Map<String, IrExpr.Lambda> returnShells = new LinkedHashMap<>();
        Map<String, Map<Integer, IrExpr.Lambda>> argShells = new LinkedHashMap<>();
        for (int i = chain.size() - 1; i >= 0; i--) {  // root-first → derived overrides base
            IrSort.Trait c = chain.get(i);
            methods.putAll(c.methods());
            attributes.putAll(c.attributes());
            associatedTypes.putAll(c.associatedTypes());
            typeParams.putAll(c.typeParams());
            operators.putAll(c.operators());
            methodDefaults.putAll(c.methodDefaults());  // a derived default overrides a base one
            returnShells.putAll(c.returnShells());      // …likewise its return shell
            argShells.putAll(c.argShells());            // …and its argument shells
        }
        return new IrSort.Trait(trait.name(), methods, attributes, associatedTypes,
                typeParams, operators, trait.baseTrait(), List.of(), methodDefaults,
                returnShells, argShells, trait.origin());
    }

    private static void validateTraitImpl(
            IrStmt.TraitImpl ti,
            Map<String, IrSort.Trait> traitContracts,
            Map<String, IrSort> functionReturns,
            Map<String, IrSort.Structural> structDefs,
            sibarum.pontif.core.symbolic.TraitRegistry satisfies,
            Map<String, List<IrStmt.FunctionDecl>> overloads,
            Set<String> algebraicFunctions,
            Map<String, Map<String, Set<String>>> traitMethodsByType,
            Map<String, Map<String, IrStmt.FunctionDecl>> methodsByType) throws CompileException {
        IrSort.Trait ownContract = traitContracts.get(ti.traitName());
        if (ownContract == null) {
            throw new CompileException(
                    "Trait impl '" + ti.typeName() + " : " + ti.traitName()
                            + "' references unknown trait '" + ti.traitName()
                            + "' (no trait declaration in module)",
                    ti.origin());
        }
        // WAR(stream) trait-extends-trait: the EFFECTIVE contract an impl must satisfy
        // is the trait's own members plus, transitively, every base trait's members
        // (`trait IndexedStream : Stream` ⟹ an impl of IndexedStream must also provide
        // Stream's contract). Flattened base-first so a derived trait may refine a base
        // member; a missing/cyclic base is a hard error.
        IrSort.Trait contract = flattenTrait(ownContract, traitContracts, ti.origin());

        // Associated types: each `type X` the trait declares must be bound
        // exactly once (`type X = [Sort]`), and a binding for an undeclared
        // associated type is over-assignment. The bound must name a known type.
        for (String declared : contract.associatedTypes().keySet()) {
            if (!ti.typeBindings().containsKey(declared)) {
                throw new CompileException(
                        "Trait impl '" + ti.typeName() + " : " + ti.traitName()
                                + "' is missing associated-type binding '" + declared
                                + "' — trait declares `type " + declared
                                + "`; supply `type " + declared + " = [...]`",
                        ti.origin());
            }
        }
        for (Map.Entry<String, IrSort> b : ti.typeBindings().entrySet()) {
            if (!contract.associatedTypes().containsKey(b.getKey())) {
                throw new CompileException(
                        "Trait impl '" + ti.typeName() + " : " + ti.traitName()
                                + "' binds associated type '" + b.getKey()
                                + "', which trait '" + ti.traitName()
                                + "' does not declare — over-assignment",
                        ti.origin());
            }
            validateSortNames(b.getValue(), structDefs);
        }

        // Bound satisfaction (`type X:R`): the bound type the impl supplies must
        // satisfy R — there must be an `assign trait <bound>:R` in scope (or the
        // bound IS R). A type bound is a refinement on the type, checked the same
        // way a value refinement is — fail-closed.
        for (Map.Entry<String, IrSort> at : contract.associatedTypes().entrySet()) {
            IrSort bound = at.getValue();
            if (bound == null) continue;  // unbounded `type X`
            IrSort binding = ti.typeBindings().get(at.getKey());
            if (binding == null) continue;  // missing — already reported above
            String boundType = sortBaseName(binding);
            String reqTrait = sortBaseName(bound);
            if (reqTrait == null) continue;  // unknown bound shape — nothing to check
            boolean ok = boundType != null
                    && (boundType.equals(reqTrait)
                            || satisfies.satisfies(reqTrait, boundType));
            if (!ok) {
                throw new CompileException(
                        "Trait impl '" + ti.typeName() + " : " + ti.traitName()
                                + "' binds `type " + at.getKey() + " = "
                                + describeDomain(binding) + "`, but trait '" + ti.traitName()
                                + "' requires that type to satisfy '" + reqTrait
                                + "' — no `assign trait " + boundType + ":" + reqTrait
                                + "` is in scope",
                        ti.origin());
            }
        }

        // The impl's own `[type T]` variables (`assign trait Element[type T]:…`,
        // docs/type-parameters.md §2.1) are in scope for the trait args, the
        // impl's type-param bounds, and the method/producer sorts — the binder
        // is what tells `Stream[T]` (a variable forwarded) apart from a concrete
        // `Stream[SomeType]`. If the subject struct is itself parametric, the
        // impl must bind the same number of variables (no claiming a different
        // arity than the struct declares).
        Set<String> implTypeVars = ti.typeParams().keySet();
        IrSort.Structural subjectStruct = structDefs.get(ti.typeName());
        if (!ti.typeParams().isEmpty() && subjectStruct != null
                && ti.typeParams().size() != subjectStruct.typeParams().size()) {
            throw new CompileException(
                    "Trait impl '" + ti.typeName() + " : " + ti.traitName() + "' binds "
                            + ti.typeParams().size() + " type parameter(s), but '"
                            + ti.typeName() + "' declares " + subjectStruct.typeParams().size(),
                    ti.origin());
        }
        for (IrSort bound : ti.typeParams().values()) {
            if (bound != null) validateSortNames(bound, structDefs, implTypeVars);
        }

        // A parametric trait is concretized by the impl's applied type arguments
        // (`…:Stream[T]` / `…:Stream[Int]`): the count must match the trait's
        // declared `[type E]` parameters, and each arg must name a known sort or
        // an in-scope impl variable.
        List<String> traitParams = new ArrayList<>(contract.typeParams().keySet());
        if (traitParams.size() != ti.traitTypeArgs().size()) {
            throw new CompileException(
                    "Trait impl '" + ti.typeName() + " : " + ti.traitName() + "' supplies "
                            + ti.traitTypeArgs().size() + " type argument(s), but trait '"
                            + ti.traitName() + "' declares " + traitParams.size()
                            + " type parameter(s)",
                    ti.origin());
        }
        for (IrSort arg : ti.traitTypeArgs()) {
            validateSortNames(arg, structDefs, implTypeVars);
        }

        // Build short-name -> impl map. The method name is the final segment — robust
        // whether it is bare (`Type.method`) or the linker module-qualified it
        // (`mod/Type.method`), the latter when the impl type is a bare builtin like
        // AlgebraicDispatch (mirrors the attribute-producer short-name below).
        Map<String, IrStmt.FunctionDecl> implByShortName = new LinkedHashMap<>();
        for (IrStmt.FunctionDecl m : ti.methods()) {
            String shortName = m.name().substring(m.name().lastIndexOf('.') + 1);
            implByShortName.put(shortName, m);
        }
        // The struct's OWN declared methods (its member block / standalone `method`
        // decls) — the some-method pool a struct-block trait obligation is checked
        // against. A synthesized `assign trait S:T {}` (from `struct S:[…&T]{ … }`)
        // carries no methods of its own; each contract method is discharged by the
        // struct's like-named method, run through the SAME signature check below.
        Map<String, IrStmt.FunctionDecl> ownMethods = methodsByType.getOrDefault(
                ti.typeName(),
                methodsByType.getOrDefault(lastPathSegment(ti.typeName()), Map.of()));

        // The type variables to substitute when checking a dependent contract
        // method against this impl: the `type X` associated types (bound by the
        // impl's `type X = […]`) plus the implicit `this.type` self-type, which
        // every impl binds to its own concrete type. Substituting `this.type ↦
        // <implType>` and requiring the impl's `copy` to match IS the type-
        // preservation gate (a `copy` that returns a sibling type fails to match).
        Set<String> typeVarNames = new HashSet<>(contract.associatedTypes().keySet());
        typeVarNames.add(IrSort.SELF_TYPE);
        Map<String, IrSort> typeVarSubst = new HashMap<>(ti.typeBindings());
        typeVarSubst.put(IrSort.SELF_TYPE, IrSort.named(ti.typeName()));
        // A parametric trait's `[type E]` parameters bind to the impl's applied
        // arguments (`Stream[T]` ⟹ E↦T, `Stream[Int]` ⟹ E↦Int), so a contract
        // sig like `head:[Method():E]` becomes `head:[Method():T]` / `…:Int]`
        // before being matched against the impl's own declared sorts.
        for (int i = 0; i < traitParams.size(); i++) {
            typeVarNames.add(traitParams.get(i));
            typeVarSubst.put(traitParams.get(i), ti.traitTypeArgs().get(i));
        }

        // Verify every contract method has a matching impl with self-prepended arity.
        for (Map.Entry<String, IrSort.CallSig> e : contract.methods().entrySet()) {
            String methodName = e.getKey();
            IrSort.CallSig contractSig = e.getValue();
            IrStmt.FunctionDecl impl = implByShortName.get(methodName);
            // Not in this impl block? The struct may still declare the method itself
            // (a member block, or a standalone `method`). Resolve it from the pool so
            // it rides the SAME arity/return signature check the contract demands —
            // an incompatible signature is rejected, a true overlap (one method
            // satisfying several traits) passes each trait's check independently.
            if (impl == null) impl = ownMethods.get(methodName);
            if (impl == null) {
                // A partial override: this impl omits the method, but a base struct
                // in the is-a chain provides it (its signature already validated on
                // that base's own impl). The dispatcher routes there per-method, so
                // the omission is not a hole — credit it and move on.
                if (baseStructProvides(ti.typeName(), ti.traitName(), methodName,
                        satisfies, traitMethodsByType)) {
                    continue;
                }
                throw new CompileException(
                        "Trait impl '" + ti.typeName() + " : " + ti.traitName()
                                + "' is missing method '" + methodName + "'",
                        ti.origin());
            }
            int expectedArity = 1 + contractSig.paramSorts().size();
            if (impl.params().size() != expectedArity) {
                throw new CompileException(
                        "Method '" + impl.name() + "' has " + impl.params().size()
                                + " param(s); trait '" + ti.traitName()
                                + "' contract requires " + expectedArity
                                + " (self + " + contractSig.paramSorts().size()
                                + " from contract)",
                        impl.origin());
            }
            // Type-variable conformance: for a contract method that mentions an
            // associated type or `this.type`, substitute this impl's bindings
            // (T ↦ [Int], this.type ↦ <implType>) into the contract signature and
            // require the impl's own declared sorts to match — so `type T = [Int]`
            // binds `evaluate:[Method():T]` to a `():Int` obligation, and
            // `copy:[Method():this.type]` binds to a `():<implType>` obligation.
            // Methods that mention neither keep the prior arity-only check.
            if (mentionsAny(contractSig, typeVarNames)) {
                IrSort.CallSig want = (IrSort.CallSig)
                        substituteTypeVars(contractSig, typeVarSubst);
                if (!sameBaseSort(impl.returnSort(), want.returnSort())) {
                    throw new CompileException(
                            "Method '" + impl.name() + "' returns "
                                    + describeDomain(impl.returnSort())
                                    + " but the trait contract (with the impl's type bindings)"
                                    + " requires " + describeDomain(want.returnSort()),
                            impl.origin());
                }
                for (int i = 0; i < want.paramSorts().size(); i++) {
                    // impl param 0 is the injected `this`; user params follow.
                    IrSort implParam = impl.params().get(i + 1).sort();
                    if (!sameBaseSort(implParam, want.paramSorts().get(i))) {
                        throw new CompileException(
                                "Method '" + impl.name() + "' parameter " + (i + 1)
                                        + " is " + describeDomain(implParam)
                                        + " but the trait contract (with the impl's type"
                                        + " bindings) requires " + describeDomain(want.paramSorts().get(i)),
                                impl.origin());
                    }
                }
            } else if (!contract.returnShells().containsKey(methodName)) {
                // Non-dependent contract method (no associated type / this.type / trait
                // param in its signature). Closes the hole where only the type-var
                // branch checked returns, so a plain `simplify():Int` impl against a
                // `simplify():Expr` contract slipped through arity-only.
                //
                // CONSERVATIVE by design: fire only on a DEFINITE nominal-head mismatch.
                // Compare on the last path segment of each return's baseName(), which
                // (a) covers trait-typed returns — Stream/Expr are IrSort.Trait, whose
                // name baseName() yields — and (b) normalizes the module qualifier, so a
                // bare `Stream` impl matches a `pontif.core/Stream` contract. Type args
                // are intentionally NOT compared: `Stream[[Int]]` vs `Stream[Int]` share
                // the head and are the rest of the pipeline's to reconcile. A headless
                // composite (Union/Intersection) yields null → skip. Shelled contract
                // returns are exempt (kernel returns the clause-chain domain; the
                // contract carries the terminus).
                String implBase = lastPathSegment(impl.returnSort().baseName());
                String wantBase = lastPathSegment(contractSig.returnSort().baseName());
                if (implBase != null && wantBase != null && !implBase.equals(wantBase)) {
                    throw new CompileException(
                            "Method '" + impl.name() + "' returns "
                                    + describeDomain(impl.returnSort())
                                    + " but trait '" + ti.traitName() + "' declares it returns "
                                    + describeDomain(contractSig.returnSort()),
                            impl.origin());
                }
            }
        }

        // Over-assignment (methods): an impl method that isn't a contract member
        // is unreachable through the trait view — defining it asserts dead
        // structure (the spec calls this a lie). Reject it.
        for (String implName : implByShortName.keySet()) {
            if (!contract.methods().containsKey(implName)) {
                throw new CompileException(
                        "Trait impl '" + ti.typeName() + " : " + ti.traitName()
                                + "' defines method '" + implName + "', which trait '"
                                + ti.traitName() + "' does not declare — over-assignment "
                                + "(a member the view can't reach is dead structure)",
                        ti.origin());
            }
        }

        // Operator contract members (dispatch-unification B1): each
        // `op:[Dispatch(this.type, this.type):this.type]` requires a coherent
        // mechanism-1 overload `op(T, T):T` for the satisfying type T — NOT a
        // method in this block (an operator is a free overload). Verified by
        // lookup in the overload table; the bound this proves is exactly what a
        // `[type E:Trait]` parameter then carries into generic code, making
        // operator use decidable at definition time instead of at a runtime miss.
        // (Built-in primitives have no FunctionDecl overload for their operators —
        // their `+` rides the BinOp fast-path — so this targets user types; that is
        // consistent with traits being user-types-only today. Cross-module witness
        // overloads are the linker's coherence concern, not checked here.)
        String implType = ti.typeName();
        for (Map.Entry<String, IrSort.CallSig> op : contract.operators().entrySet()) {
            String opSym = op.getKey();
            List<IrStmt.FunctionDecl> candidates = overloads.getOrDefault(opSym, List.of());
            boolean witnessed = candidates.stream()
                    .anyMatch(o -> isHomogeneousOverload(o, implType));
            if (!witnessed) {
                throw new CompileException(
                        "Trait impl '" + ti.typeName() + " : " + ti.traitName()
                                + "' requires operator '" + opSym + "' — trait '" + ti.traitName()
                                + "' declares the contract member '" + opSym
                                + ":[Dispatch(this.type, this.type):this.type]', but no overload '"
                                + opSym + "(" + implType + ", " + implType + "):" + implType
                                + "' is declared. Define `function " + opSym + "(a:" + implType
                                + ", b:" + implType + "):" + implType + " -> …`.",
                        ti.origin());
            }
        }

        // Attribute members: a required attribute is satisfied by EITHER a
        // matching struct field OR a computed producer in this block — exactly
        // one (never both: re-providing a present field is over-assignment;
        // neither is incomplete). A trait attribute is a computed projection,
        // which is what makes coercion information-conserving in both directions.
        Map<String, IrStmt.FunctionDecl> producerByShortName = new LinkedHashMap<>();
        for (IrStmt.FunctionDecl a : ti.attributeProducers()) {
            // The attribute name is the final segment — robust whether the producer is
            // bare (`Type.attr`) or the linker module-qualified it (`mod/Type.attr`), the
            // latter arising when the impl type is a bare builtin (AlgebraicDispatch).
            String shortName = a.name().substring(a.name().lastIndexOf('.') + 1);
            producerByShortName.put(shortName, a);
        }
        IrSort.Structural satisfier = structDefs.get(ti.typeName());
        Map<String, IrSort> fields = satisfier == null ? Map.of() : satisfier.members();

        for (Map.Entry<String, IrSort> e : contract.attributes().entrySet()) {
            String attrName = e.getKey();
            // A parametric trait's attribute sort mentions its `[type E]`
            // parameters (`value:T`); concretize them with the impl's bindings
            // (E↦Int) before checking the field, exactly as the method sigs are.
            IrSort attrSort = substituteTypeVars(e.getValue(), typeVarSubst);
            boolean hasField = fields.containsKey(attrName);
            boolean hasProducer = producerByShortName.containsKey(attrName);

            if (hasField && hasProducer) {
                throw new CompileException(
                        "Trait impl '" + ti.typeName() + " : " + ti.traitName()
                                + "' re-provides attribute '" + attrName + "', which "
                                + ti.typeName() + " already has as a field — over-assignment",
                        ti.origin());
            }
            if (!hasField && !hasProducer) {
                throw new CompileException(
                        "Trait impl '" + ti.typeName() + " : " + ti.traitName()
                                + "' is missing attribute '" + attrName + "': trait '"
                                + ti.traitName() + "' requires it and " + ti.typeName()
                                + " neither declares the field nor provides a producer",
                        ti.origin());
            }
            if (hasField) {
                // Fail-closed: the field's declared sort must already satisfy the
                // attribute's requirement. Producer-provided attributes instead
                // ride the return-refinement gate (Drafter drafts them).
                requireFieldSatisfies(ti, attrName, fields.get(attrName), attrSort);
            }
        }

        // Over-assignment (attributes): a producer for a member the trait does
        // not declare as an attribute.
        for (String prodName : producerByShortName.keySet()) {
            if (!contract.attributes().containsKey(prodName)) {
                throw new CompileException(
                        "Trait impl '" + ti.typeName() + " : " + ti.traitName()
                                + "' provides attribute '" + prodName + "', which trait '"
                                + ti.traitName() + "' does not declare — over-assignment",
                        ti.origin());
            }
        }

        // Validate the impl method + producer bodies themselves. The impl's
        // `[type T]` variables are in scope for their param/return sorts (so
        // `head(this):T` validates — T is the bound variable, not an unknown).
        for (IrStmt.FunctionDecl m : ti.methods()) {
            validateImplBody(m, functionReturns, structDefs, implTypeVars, algebraicFunctions);
        }
        for (IrStmt.FunctionDecl a : ti.attributeProducers()) {
            validateImplBody(a, functionReturns, structDefs, implTypeVars, algebraicFunctions);
        }
    }

    /**
     * Whether some STRICT ancestor of {@code type} in its is-a chain provides
     * {@code method} in its own {@code assign trait …:trait} block. A sub-struct
     * may override a subset of a trait's methods and inherit the rest from its
     * base struct's impl — the dispatcher routes each method up the is-a chain to
     * the nearest provider (DispatchResolver.routeMethod), so a method the sub
     * omits but a base supplies is not a hole. Reuses the shared, cycle-guarded
     * {@link sibarum.pontif.core.symbolic.TraitRegistry#structAncestry} walk (the
     * same chain runtime dispatch and Assignability consult); index 0 is {@code
     * type} itself, so only indices ≥1 are strict ancestors.
     */
    private static boolean baseStructProvides(
            String type, String trait, String method,
            sibarum.pontif.core.symbolic.TraitRegistry satisfies,
            Map<String, Map<String, Set<String>>> traitMethodsByType) {
        List<String> ancestry = satisfies.structAncestry(type);
        for (int i = 1; i < ancestry.size(); i++) {
            Map<String, Set<String>> byTrait = traitMethodsByType.get(ancestry.get(i));
            Set<String> ms = byTrait == null ? null : byTrait.get(trait);
            if (ms != null && ms.contains(method)) return true;
        }
        return false;
    }

    private static void validateImplBody(
            IrStmt.FunctionDecl m,
            Map<String, IrSort> functionReturns,
            Map<String, IrSort.Structural> structDefs,
            Set<String> typeVars,
            Set<String> algebraicFunctions) throws CompileException {
        Map<String, IrSort> typeEnv = new HashMap<>();
        for (IrParam p : m.params()) {
            validateSortNames(p.sort(), structDefs, typeVars);
            typeEnv.put(p.name(), p.sort());
        }
        validateSortNames(m.returnSort(), structDefs, typeVars);
        checkExpr(m.body(), typeEnv, functionReturns, structDefs, typeVars, algebraicFunctions);
    }

    /**
     * Fail-closed check that a satisfier's existing field discharges a trait
     * attribute requirement. The bases must match; an unrefined requirement
     * (existence + type) needs only that. A refined requirement (e.g.
     * {@code [Int:@>0]}) requires the field to <em>imply</em> it, decided by the
     * shared {@link Refinements#imply} kernel — the same subsumption check the
     * construction gate and {@code Assignability} use, so a stronger-predicate
     * field ({@code [Int:@>0]} against a {@code [Int:@>=0]} requirement) is
     * accepted here instead of demanding a syntactically identical predicate.
     * Abstains fail-closed (the kernel's non-Passed verdict → rejection).
     */
    private static void requireFieldSatisfies(
            IrStmt.TraitImpl ti, String attrName, IrSort fieldSort, IrSort attrSort)
            throws CompileException {
        String attrBase = sortBaseName(attrSort);
        String fieldBase = sortBaseName(fieldSort);
        if (attrBase != null && !attrBase.equals(fieldBase)) {
            throw new CompileException(
                    "Trait impl '" + ti.typeName() + " : " + ti.traitName()
                            + "': field '" + attrName + "' is " + fieldBase
                            + " but trait '" + ti.traitName() + "' requires " + attrBase,
                    ti.origin());
        }
        if (attrSort instanceof IrSort.Refined && !fieldImpliesAttr(fieldSort, attrSort)) {
            throw new CompileException(
                    "Trait impl '" + ti.typeName() + " : " + ti.traitName()
                            + "': field '" + attrName + "' does not provably satisfy the "
                            + "refined requirement trait '" + ti.traitName() + "' places on it "
                            + "— declare the field with a refinement that implies it, or provide a "
                            + "producer (which is proof-checked)",
                    ti.origin());
        }
    }

    /** Does {@code fieldSort} imply {@code attrSort} via the shared subsumption kernel? Fail-closed on any abstention. */
    private static boolean fieldImpliesAttr(IrSort fieldSort, IrSort attrSort) {
        try {
            return sibarum.pontif.core.symbolic.Refinements.imply(
                    IrCompiler.compileSort(fieldSort), IrCompiler.compileSort(attrSort),
                    new sibarum.pontif.core.symbolic.Simplifier(java.util.List.of())).isPassed();
        } catch (Exception abstain) {  // compileSort's CompileException, or a non-linear predicate
            return false;
        }
    }

    /**
     * The single null-safe wrapper over {@link IrSort#baseName()} — the nominal
     * head of a sort (Named/Refined/Structural/Trait/CallSig), or null for a
     * null sort or a headless composite (Union/Intersection). Traits ARE named
     * (via {@code baseName()}), so a trait bound or a trait branch of an
     * intersection resolves here rather than being silently dropped.
     */
    private static String sortBaseName(IrSort sort) {
        return sort == null ? null : sort.baseName();
    }

    /**
     * Builds {@code functionName → returnSort} from the module's function
     * declarations. With overloads, the last-encountered overload's return
     * sort wins — best-effort, since cross-overload return narrowing waits
     * on the in-progress dispatch-inference work.
     */
    static Map<String, IrSort> collectFunctionReturns(IrModule module) {  // reused by CallNameCheck
        Map<String, IrSort> map = new LinkedHashMap<>();
        for (IrStmt stmt : module.statements()) {
            if (stmt instanceof IrStmt.FunctionDecl fd) {
                map.put(fd.name(), fd.returnSort());
            } else if (stmt instanceof IrStmt.TraitImpl ti) {
                // TraitImpl methods are first-class FunctionDecls in the
                // dispatch table — include their return sorts here so call
                // sites validate against them.
                for (IrStmt.FunctionDecl m : ti.methods()) {
                    map.put(m.name(), m.returnSort());
                }
                for (IrStmt.FunctionDecl a : ti.attributeProducers()) {
                    map.put(a.name(), a.returnSort());
                }
            } else if (stmt instanceof IrStmt.TypeAlias ta
                    && ta.sort() instanceof IrSort.Trait t) {
                // Trait.member call names are valid even with no impl yet —
                // the dispatch fallback resolves them to ConcreteType.member
                // at runtime against the trait registry. Return sort is the
                // contract's declared return (method) / attribute sort.
                for (Map.Entry<String, IrSort.CallSig> e : t.methods().entrySet()) {
                    map.put(t.name() + "." + e.getKey(), e.getValue().returnSort());
                }
                for (Map.Entry<String, IrSort> e : t.attributes().entrySet()) {
                    map.put(t.name() + "." + e.getKey(), e.getValue());
                }
            }
        }
        return map;
    }

    /**
     * Recursively validates sort references. Compound sorts recurse into
     * their members / params. The two relevant cases:
     * <ul>
     *   <li>{@link IrSort.Named}: must be a primitive — alias references
     *       have been substituted away by {@link AliasResolver}.</li>
     *   <li>{@link IrSort.Refined}: base must be a primitive <em>or</em> a
     *       declared struct name (so {@code [Point:@.x > 0]} is legal).
     *       When the base is a struct, the predicate's {@code @.field}
     *       references are validated against the struct's declared members.</li>
     * </ul>
     *
     * <p>{@link IrSort.Structural#name()} is intentionally NOT validated —
     * it's the sort's own identity, not a reference to another type. A
     * structural sort can be inline-defined without a preceding {@code
     * struct} declaration.
     */
    /**
     * Validates that a {@code [Decimal:...]} predicate is one of Decimal's
     * three narrows — sign, range, or equality-up-to-precision. Concretely: an
     * And-tree whose leaves are comparisons of {@code @} against a numeric
     * constant. Sign is the zero-bound case, range is the conjunction case,
     * equality is the {@code ==} case; thresholds ({@code @>=1.5}) are
     * degenerate ranges. Anything richer (arithmetic on {@code @},
     * disjunctions, non-constant bounds) is rejected — not a type-narrowing
     * problem, per the Decimal design.
     */
    /** {@code @.unscaled} / {@code @.scale} — a projection of Decimal's anatomy. */
    private static boolean isAnatomyProjection(IrExpr e) {
        return e instanceof IrExpr.FieldAccess fa
                && fa.base() instanceof IrExpr.SelfRef
                && sibarum.pontif.core.Decimals.isAnatomyField(fa.fieldName());
    }

    private static void validateDecimalNarrow(IrExpr predicate, sibarum.pontif.core.Origin origin)
            throws CompileException {
        if (predicate instanceof IrExpr.BinOp op) {
            switch (op.op()) {
                case AND -> {
                    validateDecimalNarrow(op.left(), origin);
                    validateDecimalNarrow(op.right(), origin);
                    return;
                }
                case LT, LE, GT, GE, EQ, NE -> {
                    boolean selfVsConst =
                            (op.left() instanceof IrExpr.SelfRef && isNumericConst(op.right()))
                                    || (op.right() instanceof IrExpr.SelfRef && isNumericConst(op.left()));
                    if (selfVsConst) {
                        return;
                    }
                    // The anatomy narrows (slice 2 of the native-constructor
                    // registry): comparisons of @.unscaled / @.scale against a
                    // numeric constant. These are DISCRETE obligations (scale
                    // is an Int; unscaled is integer-valued), so they sit on
                    // the integer side of the ruled discreteness boundary —
                    // they don't widen the three Decimal-domain narrows.
                    boolean anatomyVsConst =
                            (isAnatomyProjection(op.left()) && isNumericConst(op.right()))
                                    || (isAnatomyProjection(op.right()) && isNumericConst(op.left()));
                    if (anatomyVsConst) {
                        return;
                    }
                }
                default -> { /* falls through to rejection */ }
            }
        }
        throw new CompileException(
                "Not a Decimal narrow. Decimal refinements are limited to sign / range / "
                        + "equality — comparisons of '@' against a numeric constant, optionally "
                        + "joined with '&': e.g. [Decimal:@>0], [Decimal:@>=0 & @<=1], "
                        + "[Decimal:@==2.5].",
                origin);
    }

    private static boolean isNumericConst(IrExpr e) {
        return e instanceof IrExpr.Lit || e instanceof IrExpr.Dec;
    }

    /** The one "this name is not a type in scope" diagnostic — shared by sort-name
     *  validation and the proof-marker check, so both read identically. */
    private static CompileException unknownSort(String name, sibarum.pontif.core.Origin origin) {
        return new CompileException(
                "Unknown sort '" + name + "' — not a primitive, a declared "
                        + "struct or trait, or a type parameter in scope. Did you "
                        + "misspell it, or forget to declare or import it?",
                origin);
    }

    private static void validateSortNames(IrSort sort, Map<String, IrSort.Structural> structDefs)
            throws CompileException {
        validateSortNames(sort, structDefs, Set.of(), Set.of());
    }

    private static void validateSortNames(
            IrSort sort, Map<String, IrSort.Structural> structDefs, Set<String> typeVars)
            throws CompileException {
        validateSortNames(sort, structDefs, typeVars, Set.of());
    }

    /**
     * A parametric application's type-argument COUNT must equal the head's declared type-parameter
     * count. Enforced only for a declared struct applied to a NON-EMPTY arg list: a bare reference
     * (zero args) is the existential "of anything" and is admitted by the widen rules, and a
     * non-struct head (a builtin parametric trait like {@code Stream}) is the documented
     * not-yet-trait-aware gap. Invariance of the args themselves is a separate, later check
     * (see {@link #enforceParametricBase}); this closes only the arity hole in sort positions.
     */
    private static void checkParametricArity(
            String name, List<IrSort> typeArgs, Map<String, IrSort.Structural> structDefs,
            sibarum.pontif.core.Origin origin) throws CompileException {
        if (typeArgs.isEmpty()) return;
        IrSort.Structural decl = structDefs.get(name);
        if (decl == null) return;  // non-struct head — resolution/incompleteness handled elsewhere
        int declared = decl.typeParams().size();
        if (declared != typeArgs.size()) {
            throw new CompileException(
                    "Type '" + name + "' applied to " + typeArgs.size()
                            + " type argument(s), but it declares " + declared
                            + " type parameter(s).",
                    origin);
        }
    }

    /**
     * @param typeVars in-scope associated-type names — the {@code type X}
     *     members of an enclosing trait. A {@code Named} matching one of these
     *     is a bound type variable, not an unknown sort. Empty at the top level;
     *     extended by the {@link IrSort.Trait} case as it descends into a
     *     trait's own member sorts.
     * @param traitNames every trait declared or imported in the module (from
     *     {@link #collectTraitContracts}). Used ONLY to sharpen the "unknown
     *     sort" diagnostic: a name matching a trait is reported as an unresolved
     *     trait reference — the usual cause being a self-referential trait nested
     *     in a parametric type whose head is not in scope (e.g. {@code Stream[Expr]}
     *     without {@code requires pontif.core.{Stream}}) — instead of being
     *     mislabelled a missing struct. Empty from callers that don't thread the
     *     set; the diagnostic then falls back to the generic wording.
     */
    private static void validateSortNames(
            IrSort sort, Map<String, IrSort.Structural> structDefs, Set<String> typeVars,
            Set<String> traitNames)
            throws CompileException {
        switch (sort) {
            case IrSort.Named n -> {
                // A surviving Named is a primitive, a nominal struct reference
                // (structs are no longer inlined — see AliasResolver), or an
                // in-scope associated TYPE variable of an enclosing trait.
                // Resolving by name, rather than recursing into the body, keeps
                // this terminating on a recursive type: struct Node(next:Node)
                // validates without unrolling Node.
                if (!PRIMITIVE_SORT_NAMES.contains(n.name())
                        && !BUILTIN_PARAMETRIC_TYPES.contains(n.name())
                        // A builtin call-kind head (Method / Dispatch / AlgebraicDispatch …) is a
                        // known name, sourced from the capability registry rather than a bespoke
                        // marker set — e.g. an AlgebraicDispatch impl's `this` self-sort
                        // (docs/dispatch-method-elimination.md §2).
                        && CallKinds.builtin(n.name()) == null
                        && !structDefs.containsKey(n.name())
                        && !typeVars.contains(n.name())) {
                    // A name that IS a declared trait but survived as a bare
                    // reference is not "unknown" — it's an unresolved trait
                    // reference. The usual cause: a self-referential trait nested
                    // in a parametric type whose head isn't in scope, so
                    // AliasResolver never descended into the type-argument to
                    // shell the self-reference (e.g. `Stream[Expr]` inside `Expr`
                    // without importing Stream). Point at that, not at a missing
                    // struct.
                    if (traitNames.contains(n.name())) {
                        throw new CompileException(
                                "Sort '" + n.name() + "' is a declared trait, but here it "
                                        + "appears as an unresolved bare reference. A "
                                        + "self-referential trait used inside a parametric "
                                        + "type (e.g. `Stream[" + n.name() + "]`) resolves "
                                        + "only when that outer type is in scope — for the "
                                        + "builtin Stream, add `requires pontif.core.{Stream}`.",
                                n.origin());
                    }
                    throw unknownSort(n.name(), n.origin());
                }
                // A parametric application's arg COUNT must match the head's declared arity
                // (e.g. `Box[Int, Bool]` for a one-param `Box` is a hard error) — mirroring the
                // check enforceParametricBase / validateTraitImpl already make for is-a bases and
                // trait args.
                checkParametricArity(n.name(), n.typeArgs(), structDefs, n.origin());
                // Type arguments of a parametric application (`Element[Int]`,
                // `Element[T]`) are themselves sorts — validate each in scope.
                for (IrSort arg : n.typeArgs()) {
                    validateSortNames(arg, structDefs, typeVars, traitNames);
                }
            }
            case IrSort.Refined r -> {
                // A parametric base's type arguments (`[Literal[Int]:…]`) are
                // sorts — validate each in scope, like a Named application.
                for (IrSort arg : r.typeArgs()) {
                    validateSortNames(arg, structDefs, typeVars, traitNames);
                }
                if (r.name().equals("Decimal")) {
                    // Decimal's refinement vocabulary is exactly three narrows —
                    // sign, range, and equality-up-to-precision — i.e. And-trees
                    // of comparisons of @ against a numeric constant. Anything
                    // richer isn't a type-narrowing problem (and the dense
                    // discharge path covers exactly these shapes).
                    validateDecimalNarrow(r.predicate(), r.origin());
                    break;
                }
                if (PRIMITIVE_SORT_NAMES.contains(r.name())) {
                    // Primitive-base refinement; predicate not yet structurally
                    // validated — refinement-predicate sort-checking is its
                    // own future work (TODO: "Sort checking inside refinement
                    // predicates").
                    break;
                }
                IrSort.Structural baseStruct = structDefs.get(r.name());
                if (baseStruct == null) {
                    throw new CompileException(
                            "Unknown base sort '" + r.name() + "' in refinement "
                                    + "[" + r.name() + ":...] — refinements must be "
                                    + "over a primitive (Int, Bool) or a declared struct.",
                            r.origin());
                }
                // Same arity gate as the Named application above, for a parametric refined base
                // (`[Literal[Int]:…]`).
                checkParametricArity(r.name(), r.typeArgs(), structDefs, r.origin());
                validateSelfFieldAccesses(r.predicate(), baseStruct, structDefs, r.origin());
            }
            case IrSort.Structural s -> {
                // A struct's `[type T]` parameters are bound type variables IN
                // SCOPE for its own field sorts (and is-a), so `value:T`
                // validates (T is the variable, not an unknown sort) — exactly as
                // the Trait case scopes associated types. A present bound
                // (`[type T:R]`) must itself name a known sort.
                Set<String> inner = typeVars;
                if (!s.typeParams().isEmpty()) {
                    inner = new HashSet<>(typeVars);
                    inner.addAll(s.typeParams().keySet());
                }
                for (IrSort bound : s.typeParams().values()) {
                    if (bound != null) validateSortNames(bound, structDefs, inner, traitNames);
                }
                for (IrSort member : s.members().values()) {
                    validateSortNames(member, structDefs, inner, traitNames);
                }
            }
            case IrSort.CallSig c -> {
                for (IrSort p : c.paramSorts()) validateSortNames(p, structDefs, typeVars, traitNames);
                validateSortNames(c.returnSort(), structDefs, typeVars, traitNames);
            }
            case IrSort.Trait t -> {
                // The trait's name identifies the trait — not a reference to
                // another sort. Its `type X` associated types are bound type
                // variables IN SCOPE for the trait's own member sorts, so a
                // method like `[Method():T]` validates (T is the variable, not
                // an unknown sort). Recurse into method/contract sorts with that
                // extended scope; a present bound (`type X:R`) must itself name a
                // known sort.
                // Every trait implicitly scopes `this.type` (the self-type) over
                // its own member sorts, alongside any `type X` associated types.
                Set<String> inner = new HashSet<>(typeVars);
                inner.add(IrSort.SELF_TYPE);
                inner.addAll(t.associatedTypes().keySet());
                inner.addAll(t.typeParams().keySet());
                for (IrSort bound : t.associatedTypes().values()) {
                    if (bound != null) validateSortNames(bound, structDefs, inner, traitNames);
                }
                // The arguments APPLIED to the trait are sorts like any other — `Stream[Widgit]`
                // names a type that must exist. They were unvalidated, which is how an undeclared
                // element type survived in the linked path: once AliasResolver resolves the bare
                // `Stream` reference to its trait sort, the argument rides along inside this node
                // rather than the Named case that does check its args. Validated in the trait's own
                // scope, so a self-reference to one of its `[type E]` parameters still resolves.
                for (IrSort arg : t.typeArgs()) validateSortNames(arg, structDefs, inner, traitNames);
                for (IrSort.CallSig f : t.methods().values()) validateSortNames(f, structDefs, inner, traitNames);
            }
            case IrSort.Union u -> {
                for (IrSort b : u.branches()) validateSortNames(b, structDefs, typeVars, traitNames);
            }
            case IrSort.Intersection i -> {
                for (IrSort b : i.branches()) validateSortNames(b, structDefs, typeVars, traitNames);
            }
        }
    }

    /**
     * Validates a struct's declared is-a relationship
     * ({@code struct Name:[Base:rel](fields)}): the base must be a declared
     * STRUCT (a primitive can only be encapsulated as a field, not an is-a base —
     * record-is-a-scalar is an open decision, deferred), and the demotion
     * morphism must functionally pin every base field — each base {@code @.field}
     * needs a top-level {@code @.field == <expr>} conjunct, so the demotion
     * (project Name → Base) is total and deterministic.
     */
    private static void validateStructBase(
            IrSort.Structural s,
            Map<String, IrSort.Structural> structDefs) throws CompileException {
        IrSort base = s.baseSort();
        // Primitives can only be ENCAPSULATED (as a field), never an is-a base —
        // a struct's `:[Base:…]` must name a declared struct. (Record-is-a-scalar
        // is an open design decision; deferred.)
        String baseName = switch (base) {
            case IrSort.Named n -> n.name();
            case IrSort.Refined r -> r.name();
            default -> null;
        };
        if (baseName != null && PRIMITIVE_SORT_NAMES.contains(baseName)
                && !baseName.startsWith("_")) {
            throw new CompileException(
                    "struct '" + s.name() + "' cannot be-a primitive '" + baseName
                            + "' — primitives can only be encapsulated (use a field, e.g. "
                            + "`struct " + s.name() + "(value:" + baseName + ", …)`).",
                    s.origin());
        }
        // The base resolves; its `@.field` refs exist. The child's own `[type T]`
        // parameters are in scope, so a forwarding base (`Wrapper[type T]:[Box[T]]`)
        // validates — T is the child's variable.
        validateSortNames(base, structDefs, s.typeParams().keySet());
        if (baseName != null && structDefs.containsKey(baseName)) {
            IrSort.Structural baseStruct = structDefs.get(baseName);
            // A base field is DETERMINED (so the demotion Child → Base is total)
            // if the morphism pins it (`@.f == <expr>`) OR the child carries it
            // through — a same-named field in the SAME constructor position. Any
            // base field that is neither carried nor pinned must be pinned
            // explicitly; a bare `struct Exp:BiOp(left, right)` that drops the
            // parent's `op` field is rejected here (James 2026-08-11).
            Set<String> pinned = new HashSet<>();
            if (base instanceof IrSort.Refined r) {
                collectPinnedBaseFields(r.predicate(), pinned);
            }
            List<String> childFields = new ArrayList<>(s.constructorMembers().keySet());
            int i = 0;
            // Extension fields are computed at every construction (the child's
            // included), never constructor-determined — they are not subject to
            // the carried-or-pinned rule.
            for (String field : baseStruct.constructorMembers().keySet()) {
                boolean carried = i < childFields.size() && childFields.get(i).equals(field);
                if (!pinned.contains(field) && !carried) {
                    throw new CompileException(
                            "struct '" + s.name() + "' is-a '" + baseName
                                    + "' but base field '@." + field + "' is not determined"
                                    + " — it is neither carried by a same-named field in the"
                                    + " same constructor position nor pinned by the morphism;"
                                    + " pin it (e.g. '@." + field + " == <expr>') so the"
                                    + " demotion to '" + baseName + "' is total.",
                            s.origin());
                }
                i++;
            }
        }
        // A constructor-extension field may only ADD — a name any is-a ancestor
        // already declares (constructor field or its own extension) would be a
        // reassignment of a default-constructed value.
        for (String ext : s.extensions().keySet()) {
            if (inheritedFieldOnIsaChain(s, ext, structDefs)) {
                throw new CompileException(
                        "Extension field '" + ext + "' of '" + s.name() + "' reassigns a field "
                                + "an is-a ancestor already binds — a constructor extension may "
                                + "only ADD fields, never reassign inherited ones.",
                        s.origin());
            }
        }
        enforceParametricBase(s, base, baseName, structDefs);
    }

    /**
     * For a PARAMETRIC is-a base (`struct IntLit:[Literal[Int]:…]`), the type
     * argument is invariant: substituting it into the base struct's fields
     * (`value:T` ⟹ `value:Int`), the child-side sort providing each base field
     * must be EXACTLY that sort — not merely a refinement of it. A struct that
     * declares it is-a {@code Literal[Int]} while holding a {@code value:Bool}
     * (or a {@code value:[Int:@>0]}) is asserting a falsehood. Runs only when the
     * base carries type arguments; a non-parametric base keeps its prior
     * (lenient, name-pinning-only) behaviour.
     */
    private static void enforceParametricBase(
            IrSort.Structural s, IrSort base, String baseName,
            Map<String, IrSort.Structural> structDefs) throws CompileException {
        List<IrSort> baseArgs = switch (base) {
            case IrSort.Named n -> n.typeArgs();
            case IrSort.Refined r -> r.typeArgs();
            default -> List.of();
        };
        if (baseArgs.isEmpty() || baseName == null) return;
        IrSort.Structural baseStruct = structDefs.get(baseName);
        if (baseStruct == null) return;  // resolution already reported

        List<String> bParams = new ArrayList<>(baseStruct.typeParams().keySet());
        if (bParams.size() != baseArgs.size()) {
            throw new CompileException(
                    "struct '" + s.name() + "' is-a '" + baseName + "' applied to "
                            + baseArgs.size() + " type argument(s), but '" + baseName
                            + "' declares " + bParams.size() + " type parameter(s)",
                    s.origin());
        }
        Map<String, IrSort> binds = new HashMap<>();
        for (int i = 0; i < bParams.size(); i++) binds.put(bParams.get(i), baseArgs.get(i));

        // How each base field is provided on the child: a morphism pin
        // (`@.f == <expr>`) names the child expression; absent a morphism, a
        // same-named child field provides it directly.
        Map<String, IrExpr> pins = new HashMap<>();
        if (base instanceof IrSort.Refined r) collectPinnedFieldExprs(r.predicate(), pins);

        for (Map.Entry<String, IrSort> bf : baseStruct.constructorMembers().entrySet()) {
            String field = bf.getKey();
            IrSort want = substituteTypeVars(bf.getValue(), binds);  // base field, concretized
            IrSort childSort;
            IrExpr pin = pins.get(field);
            if (pin instanceof IrExpr.Var v) {
                childSort = s.members().get(v.name());
            } else if (pin == null) {
                childSort = s.members().get(field);  // bare base: match by name
            } else {
                childSort = null;  // pinned to a non-field expression — can't compare here
            }
            if (childSort == null) continue;  // undeterminable / totality is the pinning check's job
            if (!sortsExactlyEqual(childSort, want)) {
                throw new CompileException(
                        "struct '" + s.name() + "' is-a '" + baseName + "' but the field "
                                + "providing base field '" + field + "' is "
                                + describeDomain(childSort) + ", which is not exactly the "
                                + "base's " + describeDomain(want) + " (a parametric is-a "
                                + "base's type argument is invariant — the sorts must match "
                                + "exactly, not merely refine).",
                        s.origin());
            }
        }
    }

    /**
     * Records each top-level {@code @.F == <expr>} conjunct as {@code F -> expr}
     * (and {@code <expr> == @.F} symmetrically), so the is-a-base check can find
     * the child expression a base field is pinned to. Package-visible: {@link
     * ConstructionGate} reuses it to materialize a pinned base field on the
     * sub-struct value at construction (a discriminant like {@code @.op=="+"}).
     */
    static void collectPinnedFieldExprs(IrExpr pred, Map<String, IrExpr> out) {
        if (pred instanceof IrExpr.BinOp op) {
            switch (op.op()) {
                case AND -> {
                    collectPinnedFieldExprs(op.left(), out);
                    collectPinnedFieldExprs(op.right(), out);
                }
                case EQ -> {
                    String l = selfFieldName(op.left());
                    if (l != null) out.put(l, op.right());
                    String rhs = selfFieldName(op.right());
                    if (rhs != null) out.put(rhs, op.left());
                }
                default -> { }
            }
        }
    }

    /**
     * Exact structural sort equality, ignoring {@link sibarum.pontif.core.Origin}.
     * Used by the parametric is-a-base check (the type argument is invariant): a
     * refinement is NOT equal to its bare base, and two different bases never
     * match. Conservative on shapes it can't compare (returns false).
     */
    private static boolean sortsExactlyEqual(IrSort a, IrSort b) {
        if (a == b) return true;
        return switch (a) {
            case IrSort.Named na -> b instanceof IrSort.Named nb
                    && na.name().equals(nb.name())
                    && sortListsExactlyEqual(na.typeArgs(), nb.typeArgs());
            case IrSort.Refined ra -> b instanceof IrSort.Refined rb
                    && ra.name().equals(rb.name())
                    && sortListsExactlyEqual(ra.typeArgs(), rb.typeArgs())
                    && exprExactlyEqual(ra.predicate(), rb.predicate());
            case IrSort.CallSig ca -> b instanceof IrSort.CallSig cb
                    && ca.typeName().equals(cb.typeName())   // Method != Dispatch by head type
                    && sortListsExactlyEqual(ca.paramSorts(), cb.paramSorts())
                    && sortsExactlyEqual(ca.returnSort(), cb.returnSort());
            case IrSort.Union ua -> b instanceof IrSort.Union ub
                    && sortListsExactlyEqual(ua.branches(), ub.branches());
            case IrSort.Intersection ia -> b instanceof IrSort.Intersection ib
                    && sortListsExactlyEqual(ia.branches(), ib.branches());
            case IrSort.Structural sa -> b instanceof IrSort.Structural sb
                    && sa.name().equals(sb.name());  // nominal identity
            case IrSort.Trait ta -> b instanceof IrSort.Trait tb && ta.name().equals(tb.name());
        };
    }

    private static boolean sortListsExactlyEqual(List<IrSort> a, List<IrSort> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!sortsExactlyEqual(a.get(i), b.get(i))) return false;
        }
        return true;
    }

    /**
     * Structural equality of refinement-predicate expressions, ignoring origin.
     * Covers the node shapes a refinement predicate uses (comparisons of
     * {@code @}/fields/literals); unrecognised shapes compare false
     * (conservative — the is-a-base check then rejects a match it can't confirm).
     */
    private static boolean exprExactlyEqual(IrExpr a, IrExpr b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return switch (a) {
            case IrExpr.Lit la -> b instanceof IrExpr.Lit lb && la.value() == lb.value();
            case IrExpr.Bool ba -> b instanceof IrExpr.Bool bb && ba.value() == bb.value();
            case IrExpr.Var va -> b instanceof IrExpr.Var vb && va.name().equals(vb.name());
            case IrExpr.SelfRef sa -> b instanceof IrExpr.SelfRef;
            case IrExpr.FieldAccess fa -> b instanceof IrExpr.FieldAccess fb
                    && fa.fieldName().equals(fb.fieldName())
                    && exprExactlyEqual(fa.base(), fb.base());
            case IrExpr.BinOp oa -> b instanceof IrExpr.BinOp ob
                    && oa.op() == ob.op()
                    && exprExactlyEqual(oa.left(), ob.left())
                    && exprExactlyEqual(oa.right(), ob.right());
            default -> false;
        };
    }

    /** Collects base fields F appearing as a top-level {@code @.F == …} conjunct. */
    private static void collectPinnedBaseFields(IrExpr pred, Set<String> out) {
        if (pred instanceof IrExpr.BinOp op) {
            switch (op.op()) {
                case AND -> {
                    collectPinnedBaseFields(op.left(), out);
                    collectPinnedBaseFields(op.right(), out);
                }
                case EQ -> {
                    String l = selfFieldName(op.left());
                    if (l != null) out.add(l);
                    String rhs = selfFieldName(op.right());
                    if (rhs != null) out.add(rhs);
                }
                default -> { }
            }
        }
    }

    /** The field name of a {@code @.field} access, or null if {@code e} isn't one. */
    private static String selfFieldName(IrExpr e) {
        return e instanceof IrExpr.FieldAccess fa && fa.base() instanceof IrExpr.SelfRef
                ? fa.fieldName()
                : null;
    }

    /**
     * Walks a struct-refinement's predicate looking for {@code @}-rooted
     * field-access chains ({@link IrExpr.FieldAccess} whose base is
     * transitively {@link IrExpr.SelfRef}) and validates every hop of the
     * chain, projecting the struct type field-by-field so that
     * {@code @.nested.structs.value} checks {@code nested} against the base
     * struct, {@code structs} against {@code nested}'s struct, and so on
     * (docs/keyed.md "Slice 0"). A hop naming a field the current struct
     * lacks, or projecting a further field off a non-struct sort, is a
     * compile error reported at the offending path.
     */
    private static void validateSelfFieldAccesses(
            IrExpr predicate,
            IrSort.Structural baseStruct,
            Map<String, IrSort.Structural> structDefs,
            sibarum.pontif.core.Origin refOrigin) throws CompileException {
        switch (predicate) {
            case IrExpr.FieldAccess fa -> {
                if (isSelfRooted(fa)) {
                    // A `@`-rooted chain: validate every hop by projecting the
                    // struct type down the path. Do NOT recurse into the base —
                    // projectSelfPath already visited it.
                    projectSelfPath(fa, baseStruct, structDefs, refOrigin);
                } else {
                    // Some other base (a field off a let-bound value, etc.):
                    // it may still contain a Self-rooted chain deeper in.
                    validateSelfFieldAccesses(fa.base(), baseStruct, structDefs, refOrigin);
                }
            }
            case IrExpr.BinOp op -> {
                validateSelfFieldAccesses(op.left(), baseStruct, structDefs, refOrigin);
                validateSelfFieldAccesses(op.right(), baseStruct, structDefs, refOrigin);
            }
            case IrExpr.LetIn l -> {
                validateSelfFieldAccesses(l.value(), baseStruct, structDefs, refOrigin);
                validateSelfFieldAccesses(l.body(), baseStruct, structDefs, refOrigin);
            }
            case IrExpr.Call c -> {
                for (IrExpr a : c.args()) validateSelfFieldAccesses(a, baseStruct, structDefs, refOrigin);
            }
            case IrExpr.Apply a -> {
                validateSelfFieldAccesses(a.fn(), baseStruct, structDefs, refOrigin);
                for (IrExpr arg : a.args()) validateSelfFieldAccesses(arg, baseStruct, structDefs, refOrigin);
            }
            case IrExpr.Lambda lam -> validateSelfFieldAccesses(lam.body(), baseStruct, structDefs, refOrigin);
            case IrExpr.Match m -> {
                validateSelfFieldAccesses(m.scrutinee(), baseStruct, structDefs, refOrigin);
                for (IrExpr.MatchBranch b : m.branches()) {
                    validateSelfFieldAccesses(b.result(), baseStruct, structDefs, refOrigin);
                }
            }
            case IrExpr.Record r -> {
                for (IrExpr v : r.members().values()) {
                    validateSelfFieldAccesses(v, baseStruct, structDefs, refOrigin);
                }
            }
            case IrExpr.Lit ignored -> {}
            case IrExpr.Dec ignored -> {}
            case IrExpr.Chr ignored -> {}
            case IrExpr.Str ignored -> {}
            case IrExpr.Bool ignored -> {}
            case IrExpr.Var ignored -> {}
            case IrExpr.SelfRef ignored -> {}
            case IrExpr.DispatchRef ignored -> {}
            case IrExpr.MethodCall mc -> {
                validateSelfFieldAccesses(mc.receiver(), baseStruct, structDefs, refOrigin);
                for (IrExpr arg : mc.args()) validateSelfFieldAccesses(arg, baseStruct, structDefs, refOrigin);
            }
            // An iteration construct never appears inside a refinement predicate.
            case IrExpr.Iterate ignored -> {}
            // Nor does an emit statement.
            case IrExpr.Emit ignored -> {}
            case IrExpr.Cast cast -> validateSelfFieldAccesses(cast.value(), baseStruct, structDefs, refOrigin);
        }
    }

    /** True when {@code fa}'s base is transitively the {@code @} self-reference. */
    private static boolean isSelfRooted(IrExpr e) {
        return switch (e) {
            case IrExpr.SelfRef ignored -> true;
            case IrExpr.FieldAccess fa -> isSelfRooted(fa.base());
            default -> false;
        };
    }

    /**
     * Projects the sort reached by a {@code @}-rooted access chain, validating
     * each hop against the struct it reads from. {@code @} itself resolves to
     * {@code rootStruct}; each field hop looks the field up in the struct the
     * base projects to and returns that field's sort, resolved to a struct for
     * the next hop. Throws at the first field a struct lacks, or when a further
     * hop is taken off a non-struct (e.g. {@code @.count.foo} where
     * {@code count:Int}).
     */
    private static IrSort projectSelfPath(
            IrExpr expr,
            IrSort.Structural rootStruct,
            Map<String, IrSort.Structural> structDefs,
            sibarum.pontif.core.Origin refOrigin) throws CompileException {
        if (expr instanceof IrExpr.SelfRef) {
            return rootStruct;
        }
        IrExpr.FieldAccess fa = (IrExpr.FieldAccess) expr;
        IrSort baseSort = projectSelfPath(fa.base(), rootStruct, structDefs, refOrigin);
        IrSort.Structural baseStruct = asStruct(baseSort, structDefs);
        sibarum.pontif.core.Origin where = fa.origin() != null ? fa.origin() : refOrigin;
        if (baseStruct == null) {
            throw new CompileException(
                    "Refinement [" + rootStruct.name() + ":…] projects @." + dottedSelfPath(fa)
                            + " but @." + dottedSelfPath((IrExpr.FieldAccess) fa.base())
                            + " has sort " + baseSort + ", which is not a struct with fields.",
                    where);
        }
        IrSort fieldSort = baseStruct.members().get(fa.fieldName());
        if (fieldSort == null) {
            throw new CompileException(
                    "Refinement [" + rootStruct.name() + ":…] references @." + dottedSelfPath(fa)
                            + " but struct '" + baseStruct.name() + "' has no such field '"
                            + fa.fieldName() + "'; available: " + baseStruct.members().keySet(),
                    where);
        }
        return fieldSort;
    }

    /**
     * Resolves a member sort to the struct definition it denotes, so a nested
     * path can keep projecting: a {@link IrSort.Structural} is itself; a
     * {@link IrSort.Named} or {@link IrSort.Refined} is looked up by base name
     * in the module's struct table (a refinement over a struct still projects
     * its fields). Anything else (a primitive, a type variable, an unresolved
     * name) is not a struct — {@code null}.
     */
    private static IrSort.Structural asStruct(IrSort sort, Map<String, IrSort.Structural> structDefs) {
        return switch (sort) {
            case IrSort.Structural s -> s;
            case IrSort.Named n -> structDefs.get(n.name());
            case IrSort.Refined r -> structDefs.get(r.name());
            default -> null;
        };
    }

    /** The dotted field path of a {@code @}-rooted chain, e.g. {@code a.b.c}. */
    private static String dottedSelfPath(IrExpr.FieldAccess fa) {
        return fa.base() instanceof IrExpr.FieldAccess base
                ? dottedSelfPath(base) + "." + fa.fieldName()
                : fa.fieldName();
    }

    private static void checkExpr(IrExpr expr, Map<String, IrSort> typeEnv,
                                  Map<String, IrSort> functionReturns,
                                  Map<String, IrSort.Structural> structDefs,
                                  Set<String> algebraicFunctions)
            throws CompileException {
        checkExpr(expr, typeEnv, functionReturns, structDefs, Set.of(), algebraicFunctions);
    }

    /**
     * @param typeVars the enclosing function/method's {@code [type E]} parameters —
     *     bound type variables in scope for every sort written in the body, including
     *     a nested fragment's param/return sorts ({@code &s:[ (el:A) -> … ]}). Without
     *     threading these, a fragment's {@code A} reads as an unknown sort
     *     (docs/type-parameters.md; the streams-motivated generics slice).
     */
    private static void checkExpr(IrExpr expr, Map<String, IrSort> typeEnv,
                                  Map<String, IrSort> functionReturns,
                                  Map<String, IrSort.Structural> structDefs,
                                  Set<String> typeVars,
                                  Set<String> algebraicFunctions)
            throws CompileException {
        switch (expr) {
            case IrExpr.Lit l -> {}
            case IrExpr.Dec d -> {}
            case IrExpr.Chr c -> {}
            case IrExpr.Str s -> {}
            case IrExpr.Bool b -> {}
            case IrExpr.SelfRef s -> {}
            case IrExpr.Var v -> {
                // A bare variable must resolve to something in scope — a param,
                // a let-binding, a lambda/iteration binder, or a pattern binder
                // (match destructures desugar to lets before this runs). Top-level
                // lets and 0-arg functions are rewritten to Calls by the parser, so
                // a Var that reaches here and isn't bound is genuinely unbound —
                // a compile error with a location, not a runtime NoSuchElement.
                if (!typeEnv.containsKey(v.name())) {
                    throw new CompileException(
                            "Unbound variable '" + v.name() + "' — no parameter, let-binding, "
                                    + "or pattern binder of that name is in scope.",
                            v.origin());
                }
            }
            // A metareference must name a declared function — zero candidates
            // is a compile error, not a runtime surprise. Key sorts validate
            // like any other sort reference.
            case IrExpr.DispatchRef d -> {
                for (IrSort k : d.keySorts()) validateSortNames(k, structDefs, typeVars);
                if (!functionReturns.containsKey(d.functionName())) {
                    throw new CompileException(
                            "Metareference '" + d.functionName()
                                    + "[...]' names no declared function",
                            d.origin());
                }
            }
            case IrExpr.BinOp op -> {
                checkExpr(op.left(), typeEnv, functionReturns, structDefs, typeVars, algebraicFunctions);
                checkExpr(op.right(), typeEnv, functionReturns, structDefs, typeVars, algebraicFunctions);
            }
            case IrExpr.LetIn l -> {
                validateSortNames(l.declaredSort(), structDefs, typeVars);
                checkExpr(l.value(), typeEnv, functionReturns, structDefs, typeVars, algebraicFunctions);
                Map<String, IrSort> extended = new HashMap<>(typeEnv);
                IrSort bound = l.declaredSort();
                // An undeclared binder ("_") takes the value's inferred sort,
                // so a match on the binding has a real domain to prove
                // totality over (the parser's synthetic scrutinee lets and
                // bare `let x = …` both land here).
                if (bound instanceof IrSort.Named n && n.name().equals("_")) {
                    IrSort inferred = inferSort(l.value(), typeEnv, functionReturns, structDefs, algebraicFunctions);
                    if (inferred != null) bound = inferred;
                }
                extended.put(l.name(), bound);
                checkExpr(l.body(), extended, functionReturns, structDefs, typeVars, algebraicFunctions);
            }
            case IrExpr.Call c -> {
                // The name might be a top-level function/method, OR a locally
                // bound callable (let-bound lambda, function param of Function
                // sort). Either is legal. Only reject when neither is true.
                // A reserved synthetic call (`#…#`, e.g. #assign-self#) is a lowered
                // construct the interpreter handles — non-lexable, so never a user function.
                if (!c.functionName().startsWith("#")
                        && !functionReturns.containsKey(c.functionName())
                        && !typeEnv.containsKey(c.functionName())) {
                    throw new CompileException(
                            "Unknown function '" + c.functionName() + "' — no "
                                    + "matching declaration (overload-mismatch errors "
                                    + "happen at dispatch time; this means no overload "
                                    + "of '" + c.functionName() + "' exists at all).",
                            c.origin());
                }
                for (IrExpr arg : c.args()) checkExpr(arg, typeEnv, functionReturns, structDefs, typeVars, algebraicFunctions);
            }
            case IrExpr.Lambda lam -> {
                // The enclosing function's type params are in scope for a fragment's
                // own param/return sorts — `&s:[ (el:A) -> … ]` inside a generic fn
                // (the streams-motivated generics slice).
                for (IrParam p : lam.params()) validateSortNames(p.sort(), structDefs, typeVars);
                validateSortNames(lam.returnSort(), structDefs, typeVars);
                Map<String, IrSort> extended = new HashMap<>(typeEnv);
                for (IrParam p : lam.params()) extended.put(p.name(), p.sort());
                checkExpr(lam.body(), extended, functionReturns, structDefs, typeVars, algebraicFunctions);
            }
            case IrExpr.Apply app -> {
                // Applying something that can statically NEVER be a function —
                // a literal, a struct literal, an arithmetic/comparison result —
                // is always a bug; reject at compile time rather than letting
                // it sit inert until (never) invoked.
                IrExpr fn = app.fn();
                if (fn instanceof IrExpr.Lit || fn instanceof IrExpr.Dec
                        || fn instanceof IrExpr.Bool || fn instanceof IrExpr.Record
                        || fn instanceof IrExpr.BinOp) {
                    throw new CompileException(
                            "This expression is not callable (it is a "
                                    + fn.getClass().getSimpleName()
                                    + ", which can never be a function) — applied with "
                                    + app.args().size() + " argument(s).",
                            app.origin());
                }
                checkExpr(app.fn(), typeEnv, functionReturns, structDefs, typeVars, algebraicFunctions);
                for (IrExpr a : app.args()) checkExpr(a, typeEnv, functionReturns, structDefs, typeVars, algebraicFunctions);
            }
            case IrExpr.Match m -> {
                checkExpr(m.scrutinee(), typeEnv, functionReturns, structDefs, typeVars, algebraicFunctions);
                for (IrExpr.MatchBranch b : m.branches()) {
                    validateSortNames(b.pattern(), structDefs, typeVars);
                    Map<String, IrSort> branchEnv = new HashMap<>(typeEnv);
                    if (m.scrutinee() instanceof IrExpr.Var v
                            && b.pattern() instanceof IrSort.Structural) {
                        branchEnv.put(v.name(), b.pattern());
                    }
                    checkExpr(b.result(), branchEnv, functionReturns, structDefs, typeVars, algebraicFunctions);
                }
                checkMatchTotality(m, typeEnv, functionReturns, structDefs, algebraicFunctions);
            }
            case IrExpr.Record r -> {
                for (IrExpr v : r.members().values()) checkExpr(v, typeEnv, functionReturns, structDefs, typeVars, algebraicFunctions);
            }
            case IrExpr.FieldAccess fa -> {
                checkExpr(fa.base(), typeEnv, functionReturns, structDefs, typeVars, algebraicFunctions);
                IrSort baseSort = inferSort(fa.base(), typeEnv, functionReturns, structDefs, algebraicFunctions);
                // Intersection base: the member exists iff SOME branch provides it
                // (the some-branch rule — `[A & B]` carries A's members and B's). A
                // member on no branch is the error; presence on any branch passes.
                if (baseSort instanceof IrSort.Intersection inter) {
                    boolean provided = false;
                    for (IrSort branch : inter.branches()) {
                        if (branchProvidesField(
                                branch, fa.fieldName(), functionReturns, structDefs)) {
                            provided = true;
                            break;
                        }
                    }
                    if (!provided) {
                        throw new CompileException(
                                "No member '" + fa.fieldName() + "' on any branch of "
                                        + describeIntersection(inter),
                                fa.origin());
                    }
                } else {
                    IrSort.Structural sp = resolveNominal(baseSort, structDefs);
                    // Field-existence is decided by the value's EFFECTIVE sort, not by
                    // whether that sort is nominal. An anonymous aggregate ('_record' /
                    // '_tuple') is minted only by fully enumerating its members — a
                    // literal or a declared shape — so its member set is closed and the
                    // check is sound (abstain-never-bluff still holds: where inference
                    // can't pin a structural shape it yields '_'/null, which resolveNominal
                    // maps to null and we defer, exactly as before). A stream stays
                    // 'Stream[T]' until a runtime seal, so it never surfaces here as an
                    // under-counted '_tuple'.
                    if (sp != null) {
                        // A trait attribute reads as a field but is stored as a
                        // computed projection: `x.weight` resolves to the satisfier's
                        // `Type.weight(this)` producer. Allow it when no real field
                        // exists but such an accessor is registered. (Anonymous
                        // aggregates carry no producers, so this stays false for them.)
                        boolean isAttributeProjection =
                                functionReturns.containsKey(sp.name() + "." + fa.fieldName());
                        // An inferred effective sort may be a rebuilt Structural
                        // carrying only the literal's constructor members — the
                        // DECLARED shape is authoritative for extension fields
                        // (materialized at construction, present on every value).
                        IrSort.Structural declared = structDefs.get(sp.name());
                        boolean isExtensionField = declared != null
                                && declared.extensions().containsKey(fa.fieldName());
                        if (!sp.members().containsKey(fa.fieldName()) && !isAttributeProjection
                                && !isExtensionField
                                && !inheritedFieldOnIsaChain(sp, fa.fieldName(), structDefs)) {
                            String subject = sp.name().startsWith("_")
                                    ? "Anonymous " + (sp.name().equals("_tuple") ? "tuple" : "record")
                                    : "Record of sort '" + sp.name() + "'";
                            throw new CompileException(
                                    subject + " has no field '"
                                            + fa.fieldName() + "'; available fields: "
                                            + sp.members().keySet(),
                                    fa.origin());
                        }
                    } else if (baseSort instanceof IrSort.CallSig cs
                            && CallKinds.builtin(cs.typeName()) == CallKinds.Kind.DISPATCH) {
                        // A dispatch nominal (Dispatch / AlgebraicDispatch) has a CLOSED member
                        // set — only the attributes its trait impls register (e.g. Algebraic's
                        // `ast` via `AlgebraicDispatch.ast`). No such producer → the member does
                        // not exist: `$poly[Decimal].ast` (AlgebraicDispatch) resolves, while
                        // `$inc[Decimal].ast` (plain Dispatch) is a compile error. (Function-style
                        // call sigs — a lambda's Method — stay lenient, as before.)
                        if (!hasAttributeProducer(functionReturns, cs.typeName(), fa.fieldName())) {
                            throw new CompileException(
                                    "'" + cs.typeName() + "' has no member '" + fa.fieldName()
                                            + "' — a metareference exposes only the attributes its "
                                            + "type provides (e.g. `.ast` on an algebraic reference)",
                                    fa.origin());
                        }
                    } else if (baseSort != null) {
                        // Native anatomies get the same typo coverage as structs.
                        String base = sortBaseName(baseSort);
                        if (base != null && NativeConstructors.has(base)
                                && !NativeConstructors.get(base).shape().members()
                                        .containsKey(fa.fieldName())) {
                            throw new CompileException(
                                    "'" + base + "' has no field '" + fa.fieldName()
                                            + "' — its anatomy is "
                                            + NativeConstructors.get(base).shape().members().keySet(),
                                    fa.origin());
                        }
                    }
                }
            }
            case IrExpr.MethodCall mc -> throw MethodResolver.unresolved(mc, "SortChecker");
            case IrExpr.Iterate it -> {
                // The source and accumulator inits are evaluated outside the loop;
                // the element binder and the accumulator names are in scope inside
                // each arm's writes — bind them so the unbound-variable check (the
                // Var case) doesn't flag them.
                checkExpr(it.source(), typeEnv, functionReturns, structDefs, typeVars, algebraicFunctions);
                Map<String, IrSort> iterEnv = new HashMap<>(typeEnv);
                iterEnv.put(it.element(), IrSort.named("_"));
                for (IrExpr.OutputSpec os : it.outputs()) {
                    if (os.init() != null) {
                        checkExpr(os.init(), typeEnv, functionReturns, structDefs, typeVars, algebraicFunctions);
                    }
                    if (os.kind() == IrExpr.OutputKind.ACCUMULATOR) {
                        iterEnv.put(os.name(), IrSort.named("_"));
                    }
                }
                for (IrExpr.Arm arm : it.arms()) {
                    validateSortNames(arm.pattern(), structDefs, typeVars);
                    for (IrExpr.Write w : arm.writes()) {
                        if (w.key() != null) checkExpr(w.key(), iterEnv, functionReturns, structDefs, typeVars, algebraicFunctions);
                        checkExpr(w.value(), iterEnv, functionReturns, structDefs, typeVars, algebraicFunctions);
                    }
                }
                // The always-on conservation discipline (§4): output-kind/write
                // agreement and no-silent-erase. (home-vs-observe ownership and
                // fold-carry content accounting stay deferred — docs/iteration.md §10.)
                checkIterationConservation(it);
            }
            case IrExpr.Emit em -> {
                // emit EVENT  BODY: both are ordinary sub-expressions; the event is
                // checked (e.g. StdOut("..") is a valid construction) and the body
                // continues the scope. emit binds nothing.
                checkExpr(em.event(), typeEnv, functionReturns, structDefs, typeVars, algebraicFunctions);
                checkExpr(em.body(), typeEnv, functionReturns, structDefs, typeVars, algebraicFunctions);
            }
            case IrExpr.Cast cast -> {
                // The target names a sort (validate it like any reference); the
                // value is an ordinary sub-expression. Whether the source→target
                // coercion is actually supported is enforced at eval (slice 1 =
                // built-in renders to String) — fail-closed there, not here.
                validateSortNames(cast.targetSort(), structDefs, typeVars);
                checkExpr(cast.value(), typeEnv, functionReturns, structDefs, typeVars, algebraicFunctions);
            }
        }
    }

    /**
     * The iteration construct's structural conservation discipline
     * (docs/iteration.md §4, §2.8) — always enforced, never an opt-in property,
     * because "no silent erase" is the no-lie law. Two laws over the per-element
     * arms (slice 1: STREAM + ACCUMULATOR outputs):
     *
     * <ul>
     *   <li><b>Output-kind / write agreement.</b> A KEYED write carries a key;
     *       every other write does not. An ACCUMULATOR declares an init value;
     *       the empty-on-start kinds (STREAM/KEYED/REWRITE) declare none. Every
     *       write names a declared output.
     *   <li><b>No silent erase.</b> Each arm accounts for the element: places it
     *       into exactly one stream, OR absorbs it into an accumulator, OR — an
     *       empty arm — falls through to the default/primary stream when one
     *       exists. An unaccounted arm is a bare drop (a compile error, never a
     *       silent loss); placing into two streams is an emission (a creation —
     *       a later slice), not a free copy.
     * </ul>
     *
     * <p>Sound for the STREAM/ACCUMULATOR arms that parse today; the
     * conservation graph (pontif-conservation) drafts the matching ledger
     * entries for audit. Content-flow absorption accounting for genuine folds
     * is a later slice (docs/iteration.md §10).
     */
    private static void checkIterationConservation(IrExpr.Iterate it) throws CompileException {
        Map<String, IrExpr.OutputKind> kinds = new java.util.LinkedHashMap<>();
        boolean hasDefaultStream = false;
        for (IrExpr.OutputSpec os : it.outputs()) {
            kinds.put(os.name(), os.kind());
            boolean isAccumulator = os.kind() == IrExpr.OutputKind.ACCUMULATOR;
            if (isAccumulator && os.init() == null) {
                throw new CompileException(
                        "Iteration accumulator '" + os.name() + "' needs an initial value.",
                        it.origin());
            }
            if (!isAccumulator && os.init() != null) {
                throw new CompileException(
                        "Iteration output '" + os.name() + "' (" + os.kind()
                                + ") starts empty — it must not declare an initial value.",
                        it.origin());
            }
            if (os.kind() == IrExpr.OutputKind.STREAM && os.name().equals("default")) {
                hasDefaultStream = true;
            }
        }
        for (int i = 0; i < it.arms().size(); i++) {
            IrExpr.Arm arm = it.arms().get(i);
            java.util.Set<String> placements = new java.util.LinkedHashSet<>();
            int absorptions = 0;
            boolean hasFan = false;
            for (IrExpr.Write w : arm.writes()) {
                // A fan write distributes a tuple return to every declared output
                // positionally (the multi-channel fragment shape, docs/stream-war.md
                // §3) — it accounts for all channels by construction, so the
                // single-placement accounting below does not apply.
                if (w.output().equals(IrExpr.Write.FAN)) {
                    hasFan = true;
                    continue;
                }
                // A stop write is a control-flow disposition (halt the iteration,
                // docs/stream-war.md §3, takeWhile), not a placement — it routes to no
                // output. The element that triggers it is intentionally not emitted; the
                // declared domain guard is the acknowledgement (as filter's null is).
                if (w.output().equals(IrExpr.Write.STOP)) {
                    continue;
                }
                IrExpr.OutputKind kind = kinds.get(w.output());
                if (kind == null) {
                    throw new CompileException(
                            "Iteration arm #" + (i + 1) + " writes to undeclared output '"
                                    + w.output() + "'.", it.origin());
                }
                boolean keyed = kind == IrExpr.OutputKind.KEYED;
                if (keyed && w.key() == null) {
                    throw new CompileException(
                            "Write to keyed output '" + w.output() + "' needs a key.", it.origin());
                }
                if (!keyed && w.key() != null) {
                    throw new CompileException(
                            "Write to '" + w.output() + "' (" + kind
                                    + ") must not carry a key.", it.origin());
                }
                switch (kind) {
                    case STREAM, KEYED, REWRITE -> placements.add(w.output());
                    case ACCUMULATOR -> absorptions++;
                }
            }
            if (!hasFan && placements.size() > 1) {
                throw new CompileException(
                        "Iteration arm #" + (i + 1) + " places the element into "
                                + placements.size() + " streams " + placements
                                + " — slice 1 places each element once; routing it to a"
                                + " second stream is an emission (a creation), a later slice"
                                + " (docs/iteration.md §4).", it.origin());
            }
            if (!hasFan && placements.isEmpty() && absorptions == 0 && !hasDefaultStream) {
                throw new CompileException(
                        "Iteration arm #" + (i + 1) + " accounts for nothing — the element is"
                                + " neither placed into a stream nor absorbed, and there is no"
                                + " default stream. A bare drop is not expressible; route"
                                + " removal to a named output (docs/iteration.md §4).",
                        it.origin());
            }
        }
    }

    /**
     * Match totality (Pontif-syntax principle 8): every value of the scrutinee's
     * sort must be covered by some arm. <b>The conservation rule: if totality
     * cannot be determined at compile time, a default arm is required.</b>
     * No value may fall through a match unhandled, and "we'll find out at
     * runtime" is not a proof.
     *
     * <p>A match passes iff one of:
     * <ul>
     *   <li>it has a <b>catch-all arm</b> — {@code _} (which the parser
     *       desugars to the precise complement where computable, the universal
     *       {@code [_]} pattern otherwise), an explicit {@code [_]}, or a bare
     *       arm of the scrutinee's own base sort — total by construction;</li>
     *   <li>a proof tier <b>determines</b> totality: Tier A (a bare structural
     *       destructure arm covers the whole struct), Tier C (a union scrutinee
     *       with every branch covered by a bare arm of that branch's type),
     *       Tier B (single-varying-field struct coverage), or the refined-arm
     *       complement check over the decidable {@link PredicateArithmetic}
     *       fragment.</li>
     * </ul>
     * Anything else — unknown scrutinee sort, arms outside the decidable
     * fragment (Decimal predicates, multi-field struct refinements, …), or a
     * kernel {@code Unknown} — is a compile error directing the user to add a
     * {@code _} default.
     */
    private static void checkMatchTotality(
            IrExpr.Match m,
            Map<String, IrSort> typeEnv,
            Map<String, IrSort> functionReturns,
            Map<String, IrSort.Structural> structDefs,
            Set<String> algebraicFunctions) throws CompileException {
        IrSort scrutineeIr = widenOpenPinToBase(
                inferSort(m.scrutinee(), typeEnv, functionReturns, structDefs, algebraicFunctions));

        // A catch-all arm makes the match total by construction, regardless of
        // what the other arms look like (ordered match: it catches the rest).
        if (hasCatchAllArm(m, scrutineeIr)) {
            return;
        }
        if (scrutineeIr == null) {
            throw cannotProveTotality(m, null, "the scrutinee's sort is not statically known");
        }

        // Tier E: the enum cover. A sealed enum's value-set IS its case list, so
        // totality is set arithmetic over a finite table rather than predicate
        // complementation — decided case by case by {@link EnumCover}, no solver.
        if (tryEnumCover(m, scrutineeIr, structDefs)) {
            return;  // total (or threw, naming the cases no arm covers)
        }

        // Struct totality (Tier A): a bare structural arm (no refined fields)
        // whose field set is a subset of the scrutinee's fields matches every
        // value of that struct shape — per Pontif's subset-semantics structural
        // matching — so the match is trivially total.
        IrSort.Structural scrutineeStruct = resolveNominal(scrutineeIr, structDefs);
        if (scrutineeStruct != null) {
            for (IrExpr.MatchBranch b : m.branches()) {
                if (isBareStructuralCovering(b.pattern(), scrutineeStruct, structDefs)) {
                    return;  // trivially total
                }
            }
        }

        // Tier C: union scrutinee where every branch is covered by a bare arm
        // of that branch's type ([Int] / [Ternion(z,n,w)] over [Int|Ternion]) —
        // the canonical sum-type match, determined total structurally.
        if (unionCoveredByBareArms(m, scrutineeIr, structDefs)) {
            return;
        }

        // Tier B: single-varying-field struct totality. If every arm is a
        // structural pattern refining the SAME single field (with all others
        // bare), totality reduces to "does the union of arms' refinements on
        // that field cover the field's domain?" — a single-field problem the
        // existing kernel decides.
        if (tryTierBSingleField(m, scrutineeIr, structDefs)) {
            return;  // total (or threw on non-exhaustive)
        }

        SymExpr union = null;
        for (IrExpr.MatchBranch b : m.branches()) {
            if (!(b.pattern() instanceof IrSort.Refined refined)) {
                throw cannotProveTotality(m, scrutineeIr,
                        "arm patterns are outside the decidable fragment");
            }
            SymExpr armPred;
            try {
                armPred = IrCompiler.compileSymExpr(refined.predicate());
            } catch (CompileException e) {
                throw cannotProveTotality(m, scrutineeIr,
                        "an arm predicate is outside the decidable fragment");
            }
            union = (union == null) ? armPred : SymExpr.or(union, armPred);
        }
        if (union == null) return;  // no arms (the parser rejects empty matches anyway)

        Sort domain;
        try {
            domain = IrCompiler.compileSort(scrutineeIr);
        } catch (CompileException e) {
            throw cannotProveTotality(m, scrutineeIr,
                    "the scrutinee's sort is outside the decidable fragment");
        }

        // Uncovered = domain ∧ ¬(union of arms).
        ComplementResult cr = PredicateArithmetic.complement(union, domain);
        if (!(cr instanceof ComplementResult.Computed computed)) {
            throw cannotProveTotality(m, scrutineeIr,
                    "coverage over this domain is undecidable");
        }
        SymExpr uncovered = computed.predicate();
        SatResult sat = PredicateArithmetic.satisfiable(uncovered, domain);
        if (sat.isYes()) {
            throw new CompileException(
                    "match over " + describeDomain(scrutineeIr) + " is not exhaustive — no arm covers "
                            + renderPredicate(uncovered)
                            + " (every match must be total; add the missing arm or a '_' default)",
                    m.origin());
        }
        if (!(sat instanceof SatResult.No)) {
            throw cannotProveTotality(m, scrutineeIr,
                    "coverage over this domain is undecidable");
        }
    }

    /**
     * <b>Tier E — the enum cover.</b> When the scrutinee is a sealed {@code enum}
     * (or one of its cases), its domain is a finite, declared set of cases, so
     * totality is decided by subtracting what each arm covers from that set. This is
     * the payoff of sealing: {@code [E.A] [E.B] [E.C]} is total with no {@code [_]},
     * and the failure message can name the case the author forgot instead of
     * rendering an uncovered predicate.
     *
     * <p>Returns false — deferring to the generic tiers, which will demand a default
     * arm — when the scrutinee is not an enum, or when any arm's coverage is
     * undecidable. Throws when every arm is decidable and cases remain uncovered.
     */
    private static boolean tryEnumCover(
            IrExpr.Match m, IrSort scrutineeIr,
            Map<String, IrSort.Structural> structDefs) throws CompileException {
        String head = sortBaseName(scrutineeIr);
        IrSort.Structural decl = head == null ? null : structDefs.get(head);
        if (decl == null) return false;

        IrSort.Structural enumBase;
        Set<String> domain;
        if (decl.isSealed()) {
            enumBase = decl;
            domain = new LinkedHashSet<>(decl.sealedCases());
        } else {
            // A case sort: the enum is its is-a base, and the domain is that one case.
            String parent = decl.baseSort() instanceof IrSort.Refined r ? r.name() : null;
            IrSort.Structural p = parent == null ? null : structDefs.get(parent);
            if (p == null || !p.isSealed() || !p.sealedCases().contains(head)) return false;
            enumBase = p;
            domain = new LinkedHashSet<>(List.of(head));
        }
        // A refined scrutinee ([E:@.driver=="NTFS"]) narrows the domain before the
        // arms are subtracted — the same cover question, asked of the scrutinee.
        if (scrutineeIr instanceof IrSort.Refined) {
            Set<String> narrowed = EnumCover.covered(scrutineeIr, enumBase, structDefs);
            if (narrowed != null) domain.retainAll(narrowed);
        }

        Set<String> uncovered = new LinkedHashSet<>(domain);
        for (IrExpr.MatchBranch b : m.branches()) {
            Set<String> reach = EnumCover.covered(b.pattern(), enumBase, structDefs);
            if (reach == null) return false;   // an arm outside the closed fragment
            uncovered.removeAll(reach);
        }
        if (uncovered.isEmpty()) return true;
        throw new CompileException(
                "match over enum '" + enumBase.name() + "' is not exhaustive — no arm covers "
                        + uncovered.stream().map(EnumCover::display).toList()
                        + " (every match must be total; add the missing arm(s) or a '_' default)",
                m.origin());
    }

    /**
     * Totality reasons about coverage of the scrutinee's <em>domain</em>. Now that
     * {@code infer} produces exact value-pins (docs/inference-unification.md), a
     * scrutinee like {@code n + 1} narrows to {@code [Int:@==n+1]} — an <em>open</em>
     * pin whose free variable {@code n} is universally quantified for totality. Such
     * a pin must widen to its base ({@code Int}): the arms partition the base, not the
     * single value. A <em>closed</em> refinement (a literal singleton {@code [@==5]},
     * a bound {@code [@>=2]}, a self-field struct refinement {@code [@.x>0]}) has no
     * free variable and stays as the domain.
     */
    private static IrSort widenOpenPinToBase(IrSort sort) {
        if (sort instanceof IrSort.Refined r && predicateHasFreeVar(r.predicate())) {
            return IrSort.named(r.name());
        }
        return sort;
    }

    /** Whether a refinement predicate references a free variable (not {@code @}/self). */
    private static boolean predicateHasFreeVar(IrExpr e) {
        return switch (e) {
            case IrExpr.Var ignored -> true;
            case IrExpr.BinOp op -> predicateHasFreeVar(op.left()) || predicateHasFreeVar(op.right());
            case IrExpr.FieldAccess fa -> predicateHasFreeVar(fa.base());
            case IrExpr.Call c -> c.args().stream().anyMatch(SortChecker::predicateHasFreeVar);
            case IrExpr.Cast c -> predicateHasFreeVar(c.value());
            default -> false;  // SelfRef, Lit, Bool, Dec, Chr, Str, etc.
        };
    }

    /** The conservation rule's rejection: undeterminable totality, no default. */
    private static CompileException cannotProveTotality(
            IrExpr.Match m, IrSort scrutineeIr, String reason) {
        String domain = scrutineeIr == null ? "" : " over " + describeDomain(scrutineeIr);
        return new CompileException(
                "cannot prove this match" + domain + " is exhaustive — " + reason
                        + ". Every match must be total: add a '_' default arm "
                        + "(or an explicit catch-all like [_]).",
                m.origin());
    }

    /**
     * A catch-all arm: the universal {@code [_]} pattern (also what {@code _}
     * desugars to outside the complement-computable fragment), or a bare arm
     * of the scrutinee's own base sort (e.g. {@code [Ternion]} over a
     * {@code Ternion} scrutinee).
     */
    private static boolean hasCatchAllArm(IrExpr.Match m, IrSort scrutineeIr) {
        String scrutineeBase = scrutineeIr == null ? null : sortBaseName(scrutineeIr);
        for (IrExpr.MatchBranch b : m.branches()) {
            if (b.pattern() instanceof IrSort.Named n) {
                if (n.name().equals("_")) return true;
                if (scrutineeBase != null && n.name().equals(scrutineeBase)) return true;
            }
        }
        return false;
    }

    /**
     * Tier C: a union scrutinee is determined total when every union branch is
     * covered by a bare arm of that branch's type — a bare {@code [Type]} arm
     * or a bare structural destructure {@code [Type(a, b)]}. Refined union
     * branches or arms fall back to the other tiers.
     */
    private static boolean unionCoveredByBareArms(
            IrExpr.Match m, IrSort scrutineeIr, Map<String, IrSort.Structural> structDefs) {
        if (!(scrutineeIr instanceof IrSort.Union u)) return false;
        for (IrSort branch : u.branches()) {
            if (branch instanceof IrSort.Refined) return false;
            String base = sortBaseName(branch);
            if (base == null) return false;
            IrSort.Structural branchStruct = resolveNominal(branch, structDefs);
            boolean covered = false;
            for (IrExpr.MatchBranch b : m.branches()) {
                IrSort p = b.pattern();
                if (p instanceof IrSort.Named n && n.name().equals(base)) {
                    covered = true;
                    break;
                }
                if (branchStruct != null && p instanceof IrSort.Structural st
                        && base.equals(st.name())
                        && isBareStructuralCovering(p, branchStruct, structDefs)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) return false;
        }
        return true;
    }

    /**
     * Whether an overload is a homogeneous binary operator over {@code type}:
     * exactly two params, both of base sort {@code type}, returning base sort
     * {@code type}. This is the shape an operator trait contract
     * {@code (this.type, this.type):this.type} requires of its satisfier (v1;
     * wider/narrowed witnesses — e.g. a {@code +(Number, Number)} covering a
     * subtype — are a later slice, see the doc's open questions).
     */
    private static boolean isHomogeneousOverload(IrStmt.FunctionDecl o, String type) {
        if (o.params().size() != 2) {
            return false;
        }
        return type.equals(sortBaseName(o.params().get(0).sort()))
                && type.equals(sortBaseName(o.params().get(1).sort()))
                && type.equals(sortBaseName(o.returnSort()));
    }

    /** The overloadable operator symbols (mirrors the parser's OVERLOADABLE_OPS). */
    private static final Set<String> OPERATOR_SYMBOLS = Set.of(
            "+", "-", "*", "/", "%", "^", "<", "<=", ">", ">=", "==", "!=");

    private static String operatorSymbol(IrExpr.Op op) {
        return switch (op) {
            case ADD -> "+"; case SUB -> "-"; case MUL -> "*"; case DIV -> "/";
            case MOD -> "%"; case POW -> "^";
            case LT -> "<"; case LE -> "<="; case GT -> ">"; case GE -> ">=";
            case EQ -> "=="; case NE -> "!=";
            // APPROX/AND/OR aren't overloadable operator contract members.
            case APPROX, AND, OR -> null;
        };
    }

    /**
     * Operator bound propagation (dispatch-unification B1): within a generic
     * function body, an operator applied to a value whose sort is a trait-bounded
     * type parameter {@code E} is licensed only if {@code E}'s bound declares that
     * operator as a contract member ({@code +:[Dispatch(this.type,this.type):this.type]}).
     * This is what carries the {@code assign trait} proof into generic code, so
     * {@code a + b} over {@code E:Numeric} is decidable at definition time. Only
     * functions with type parameters can carry such obligations — others are
     * skipped. The walk returns each sub-expression's sort when it is (provably) the
     * type parameter, so a {@code let c = a + b} propagates {@code c:E} into the
     * body (the homogeneous contract result is the self type).
     */
    private static void checkOperatorBounds(
            IrStmt.FunctionDecl fd, Map<String, IrSort> paramEnv,
            Map<String, IrSort.Trait> traitContracts,
            Map<String, IrSort> functionReturns) throws CompileException {
        if (fd.typeParams().isEmpty()) {
            return;
        }
        // type-param name → its bound trait (null when unbounded or a non-trait bound).
        Map<String, IrSort.Trait> boundOf = new HashMap<>();
        for (Map.Entry<String, IrSort> tp : fd.typeParams().entrySet()) {
            IrSort bound = tp.getValue();
            String boundName = bound == null ? null : sortBaseName(bound);
            boundOf.put(tp.getKey(), boundName == null ? null : traitContracts.get(boundName));
        }
        walkOperatorBounds(fd.body(), new HashMap<>(paramEnv), fd, boundOf, functionReturns);
    }

    /**
     * Recursive walk for {@link #checkOperatorBounds}: returns the expression's
     * sort when it provably is one of {@code fd}'s type parameters (so let-bound
     * results flow), {@code null} otherwise. Throws on an operator over a
     * type-parameter operand the bound does not license.
     */
    private static IrSort walkOperatorBounds(
            IrExpr expr, Map<String, IrSort> env, IrStmt.FunctionDecl fd,
            Map<String, IrSort.Trait> boundOf, Map<String, IrSort> functionReturns)
            throws CompileException {
        switch (expr) {
            case IrExpr.Var v -> {
                return env.get(v.name());
            }
            case IrExpr.LetIn l -> {
                IrSort valueSort = walkOperatorBounds(l.value(), env, fd, boundOf, functionReturns);
                Map<String, IrSort> extended = new HashMap<>(env);
                if (valueSort != null) {
                    extended.put(l.name(), valueSort);
                } else {
                    extended.remove(l.name());
                }
                return walkOperatorBounds(l.body(), extended, fd, boundOf, functionReturns);
            }
            case IrExpr.Call c -> {
                List<IrSort> argSorts = new ArrayList<>(c.args().size());
                for (IrExpr a : c.args()) {
                    argSorts.add(walkOperatorBounds(a, env, fd, boundOf, functionReturns));
                }
                if (OPERATOR_SYMBOLS.contains(c.functionName()) && c.args().size() == 2) {
                    return checkOperatorOverTypeParam(
                            c.functionName(), argSorts, fd, boundOf, c.origin());
                }
                return functionReturns.get(c.functionName());
            }
            case IrExpr.BinOp op -> {
                IrSort ls = walkOperatorBounds(op.left(), env, fd, boundOf, functionReturns);
                IrSort rs = walkOperatorBounds(op.right(), env, fd, boundOf, functionReturns);
                String sym = operatorSymbol(op.op());
                if (sym != null) {
                    return checkOperatorOverTypeParam(
                            sym, List.of(ls == null ? IrSort.named("_") : ls,
                                    rs == null ? IrSort.named("_") : rs),
                            fd, boundOf, op.origin());
                }
                return null;
            }
            case IrExpr.FieldAccess fa -> {
                walkOperatorBounds(fa.base(), env, fd, boundOf, functionReturns);
                return null;
            }
            case IrExpr.Match m -> {
                walkOperatorBounds(m.scrutinee(), env, fd, boundOf, functionReturns);
                for (IrExpr.MatchBranch b : m.branches()) {
                    walkOperatorBounds(b.result(), env, fd, boundOf, functionReturns);
                }
                return null;
            }
            case IrExpr.Cast cast -> {
                walkOperatorBounds(cast.value(), env, fd, boundOf, functionReturns);
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * The core check: given an operator applied to two operand sorts, if an
     * operand provably is a type parameter {@code E} of {@code fd}, the homogeneous
     * contract {@code (E, E):E} must be licensed by {@code E}'s bound. Returns
     * {@code E} (the contract result) when licensed; throws otherwise. Returns
     * {@code null} when no operand is a type parameter (not this check's concern).
     */
    private static IrSort checkOperatorOverTypeParam(
            String opSym, List<IrSort> argSorts, IrStmt.FunctionDecl fd,
            Map<String, IrSort.Trait> boundOf, sibarum.pontif.core.Origin origin)
            throws CompileException {
        String typeParam = null;
        for (IrSort s : argSorts) {
            String base = s == null ? null : sortBaseName(s);
            if (base != null && fd.typeParams().containsKey(base)) {
                typeParam = base;
                break;
            }
        }
        if (typeParam == null) {
            return null;  // no abstract operand — ordinary dispatch governs it
        }
        IrSort.Trait bound = boundOf.get(typeParam);
        // Licensed iff: BOTH operands are exactly this type parameter (the v1
        // homogeneous shape) AND its bound declares the operator.
        boolean homogeneous = argSorts.size() == 2
                && typeParam.equals(sortBaseName(argSorts.get(0)))
                && typeParam.equals(sortBaseName(argSorts.get(1)));
        if (homogeneous && bound != null && bound.operators().containsKey(opSym)) {
            return IrSort.named(typeParam);  // contract result is the self type
        }
        String why = bound == null
                ? "type parameter '" + typeParam + "' is unbounded (or its bound is not a trait)"
                : "its bound trait '" + bound.name() + "' does not declare operator '" + opSym
                        + "' (it declares: " + bound.operators().keySet() + ")";
        throw new CompileException(
                "In '" + fd.name() + "', operator '" + opSym + "' is applied to a value of type "
                        + "parameter '" + typeParam + "', but " + why
                        + ". Generic operator use is licensed only by the parameter's trait bound — "
                        + "bound '" + typeParam + "' by a trait with the contract member '" + opSym
                        + ":[Dispatch(this.type, this.type):this.type]'.",
                origin);
    }

    /**
     * Free-function / operator overloads grouped by their <b>member</b> name —
     * operators register under their symbol (`+`), so this is the lookup an
     * operator trait contract is verified against. TraitImpl methods are included
     * for parity with the dispatch table, though operators are declared as free
     * functions.
     *
     * <p>Keying is by {@link QualifiedName#memberOf} (the local key), NOT the raw
     * declared name. Post-link a free overload's name is FQN'd ({@code num.vector/+}),
     * but the contract check queries by the bare symbol ({@code +}); keying by the
     * member makes the witness findable across the module boundary (and the
     * division overload {@code num.frac//} keys correctly under {@code "/"}, where a
     * {@code lastIndexOf('/')} split would have lost it).
     */
    private static Map<String, List<IrStmt.FunctionDecl>> collectOverloadsByName(IrModule module) {
        Map<String, List<IrStmt.FunctionDecl>> byName = new LinkedHashMap<>();
        for (IrStmt stmt : module.statements()) {
            if (stmt instanceof IrStmt.FunctionDecl fd) {
                byName.computeIfAbsent(QualifiedName.memberOf(fd.name()), k -> new ArrayList<>()).add(fd);
            } else if (stmt instanceof IrStmt.TraitImpl ti) {
                for (IrStmt.FunctionDecl m : ti.methods()) {
                    byName.computeIfAbsent(QualifiedName.memberOf(m.name()), k -> new ArrayList<>()).add(m);
                }
            }
        }
        return byName;
    }

    /** Whether {@code sort} references any of the given (associated-type) names. */
    private static boolean mentionsAny(IrSort sort, Set<String> names) {
        return switch (sort) {
            case IrSort.Named n -> names.contains(n.name())
                    || n.typeArgs().stream().anyMatch(a -> mentionsAny(a, names));
            case IrSort.Refined r -> names.contains(r.name());
            case IrSort.CallSig c -> c.paramSorts().stream().anyMatch(p -> mentionsAny(p, names))
                    || mentionsAny(c.returnSort(), names);
            case IrSort.Union u -> u.branches().stream().anyMatch(b -> mentionsAny(b, names));
            case IrSort.Intersection i -> i.branches().stream().anyMatch(b -> mentionsAny(b, names));
            case IrSort.Structural s -> s.members().values().stream().anyMatch(mm -> mentionsAny(mm, names));
            case IrSort.Trait t -> false;  // a trait shell/ref doesn't name the variable
        };
    }

    /**
     * Substitutes associated-type variables with their per-impl bindings:
     * a {@link IrSort.Named} whose name is a key in {@code bindings} becomes the
     * bound sort. Used to make a trait's dependent contract concrete for one impl.
     *
     * <p>Public so {@link AliasResolver} (inlining a parametric trait reference,
     * {@code Stream[Int]} ⟹ substitute {@code E↦Int}) and the parser (specializing a
     * generic function at an explicit type-application {@code map[Int,String](…)},
     * docs/stream-war.md §8b) can reuse it.
     */
    public static IrSort substituteTypeVars(IrSort sort, Map<String, IrSort> bindings) {
        return switch (sort) {
            case IrSort.Named n -> {
                // A bound type variable is replaced wholesale; otherwise the head
                // stays and any type arguments are substituted (`Element[T]` with
                // T↦Int becomes `Element[Int]`).
                if (bindings.containsKey(n.name())) yield bindings.get(n.name());
                if (n.typeArgs().isEmpty()) yield n;
                yield new IrSort.Named(n.name(),
                        n.typeArgs().stream().map(a -> substituteTypeVars(a, bindings)).toList(),
                        n.origin());
            }
            case IrSort.CallSig c -> new IrSort.CallSig(c.typeName(),
                    c.paramSorts().stream().map(p -> substituteTypeVars(p, bindings)).toList(),
                    c.paramNames(), substituteTypeVars(c.returnSort(), bindings), c.origin());
            case IrSort.Union u -> new IrSort.Union(
                    u.branches().stream().map(b -> substituteTypeVars(b, bindings)).toList(), u.origin());
            case IrSort.Intersection i -> new IrSort.Intersection(
                    i.branches().stream().map(b -> substituteTypeVars(b, bindings)).toList(), i.origin());
            case IrSort.Structural s -> {
                Map<String, IrSort> mem = new LinkedHashMap<>();
                for (Map.Entry<String, IrSort> e : s.members().entrySet()) {
                    mem.put(e.getKey(), substituteTypeVars(e.getValue(), bindings));
                }
                yield new IrSort.Structural(
                        s.name(), mem,
                        s.baseSort() == null ? null : substituteTypeVars(s.baseSort(), bindings),
                        java.util.Map.of(), s.extensions(), s.sealedCases(), s.origin());
            }
            // A Refined's name is its base, not a substitution site; but a
            // parametric base's type args (`[Literal[T]:…]`) are substituted.
            case IrSort.Refined r -> r.typeArgs().isEmpty() ? r : new IrSort.Refined(
                    r.name(),
                    r.typeArgs().stream().map(a -> substituteTypeVars(a, bindings)).toList(),
                    r.predicate(), r.origin());
            // A Trait stays nominal.
            case IrSort.Trait t -> t;
        };
    }

    /** The segment after the last {@code /} — strips a module qualifier ({@code mod/T} → {@code T}). */
    private static String lastPathSegment(String name) {
        return name == null ? null : name.substring(name.lastIndexOf('/') + 1);
    }

    /** Conservative conformance: two sorts share a base name (both non-null). */
    private static boolean sameBaseSort(IrSort a, IrSort b) {
        String an = sortBaseName(a);
        String bn = sortBaseName(b);
        return an != null && an.equals(bn);
    }

    /**
     * Tier B struct totality: when every arm is a structural pattern refining
     * the same single field (others bare), reduce to that field's domain-
     * coverage problem (which the kernel decides). Returns:
     * <ul>
     *   <li>{@code true} if the case applies and the union of arms' field
     *       refinements provably covers the field's domain — total;</li>
     *   <li>throws {@link CompileException} when the case applies but the
     *       union provably leaves the field's domain uncovered;</li>
     *   <li>{@code false} when this isn't the single-varying-field shape, or
     *       the kernel can't decide — defer to the outer check / runtime.</li>
     * </ul>
     */
    private static boolean tryTierBSingleField(
            IrExpr.Match m, IrSort scrutineeIr,
            Map<String, IrSort.Structural> structDefs) throws CompileException {
        IrSort.Structural scrutineeStruct = resolveNominal(scrutineeIr, structDefs);
        if (scrutineeStruct == null) return false;

        String varyingField = null;
        SymExpr union = null;
        for (IrExpr.MatchBranch b : m.branches()) {
            if (!(b.pattern() instanceof IrSort.Structural sp)) return false;
            // Find the arm's single refined field; reject 2+ refined fields or
            // nested-structural fields.
            String armVarying = null;
            IrSort.Refined armRefined = null;
            for (Map.Entry<String, IrSort> entry : sp.members().entrySet()) {
                IrSort fs = entry.getValue();
                if (fs instanceof IrSort.Refined r) {
                    if (armVarying != null) return false;  // 2+ refined fields
                    armVarying = entry.getKey();
                    armRefined = r;
                } else if (fs instanceof IrSort.Structural) {
                    return false;  // nested structural → defer
                }
            }
            if (armVarying == null) return false;  // bare arm — Tier A's territory
            if (varyingField == null) {
                varyingField = armVarying;
            } else if (!varyingField.equals(armVarying)) {
                return false;  // different varying field across arms
            }
            SymExpr armPred;
            try {
                armPred = IrCompiler.compileSymExpr(armRefined.predicate());
            } catch (CompileException e) {
                return false;
            }
            union = (union == null) ? armPred : SymExpr.or(union, armPred);
        }
        if (varyingField == null || union == null) return false;

        // The field's domain — the declared field sort on the scrutinee struct.
        IrSort fieldDeclaredIr = scrutineeStruct.members().get(varyingField);
        if (fieldDeclaredIr == null) return false;  // arm refines a field the struct lacks
        Sort fieldDomain;
        try {
            fieldDomain = IrCompiler.compileSort(fieldDeclaredIr);
        } catch (CompileException e) {
            return false;
        }

        ComplementResult cr = PredicateArithmetic.complement(union, fieldDomain);
        if (!(cr instanceof ComplementResult.Computed computed)) return false;
        SymExpr uncovered = computed.predicate();
        if (PredicateArithmetic.satisfiable(uncovered, fieldDomain).isYes()) {
            throw new CompileException(
                    "match over " + describeDomain(scrutineeIr)
                            + " is not exhaustive — no arm covers field '"
                            + varyingField + "' where " + renderPredicate(uncovered)
                            + " (every match must be total)",
                    m.origin());
        }
        return true;  // total
    }

    /**
     * Resolves a sort to its declared struct definition — directly when it's
     * {@link IrSort.Structural}, by name via {@code structDefs} for a nominal
     * {@link IrSort.Named}/{@link IrSort.Refined} reference. {@code null} when
     * the sort isn't a struct. Resolving by a single name lookup (never
     * recursing into the struct body) is what keeps every consumer terminating
     * on a recursive type.
     */
    /**
     * Whether an is-a ANCESTOR struct of {@code sp} declares {@code field}. The
     * is-a coverage check guarantees every base field is materialized on a
     * sub-struct value — carried by same-name (so it is already in {@code
     * sp.members()}) or pinned (materialized at construction by {@link
     * ConstructionGate}) — so a base-only field like {@code op} on an {@code
     * Exp:BiOp} is genuinely present and {@code e.op} resolves it through the
     * chain. This is the field-access mirror of the method-dispatch base-chain
     * walk (DispatchResolver.routeMethod); {@link Coercions#baseName} names the
     * base at each hop (shared with TraitRelations / InferenceContext), and the
     * seen-set guards a cycle.
     */
    private static boolean inheritedFieldOnIsaChain(
            IrSort.Structural sp, String field, Map<String, IrSort.Structural> structDefs) {
        for (IrSort.Structural ancestor : StructAncestry.ancestors(structDefs, sp)) {
            if (ancestor.members().containsKey(field)) return true;
        }
        return false;
    }

    private static IrSort.Structural resolveNominal(
            IrSort sort, Map<String, IrSort.Structural> structDefs) {
        if (sort == null) return null;  // unknown inferred sort — not a struct
        return switch (sort) {
            case IrSort.Structural s -> s;
            case IrSort.Named n -> structDefs.get(n.name());
            case IrSort.Refined r -> structDefs.get(r.name());
            default -> null;
        };
    }

    /**
     * Whether a single (non-intersection) branch of an intersection base provides
     * {@code field} — as a real struct field, a registered trait-attribute producer
     * ({@code Type.field(this)}), or a native anatomy field. The some-branch member
     * rule (a member of {@code [A & B]} is a member of A or of B) consults this per
     * branch; an anonymous aggregate branch defers to the runtime check.
     */
    private static boolean branchProvidesField(
            IrSort branch, String field,
            Map<String, IrSort> functionReturns, Map<String, IrSort.Structural> structDefs) {
        IrSort.Structural sp = resolveNominal(branch, structDefs);
        if (sp != null) {
            if (sp.name().startsWith("_")) return true;   // anonymous — defers to runtime
            if (sp.members().containsKey(field)
                    || functionReturns.containsKey(sp.name() + "." + field)) {
                return true;
            }
        }
        String base = sortBaseName(branch);
        if (base != null) {
            // A member contributed by a non-struct branch (e.g. a trait) — the
            // attribute producer is keyed by the branch's own name.
            if (functionReturns.containsKey(base + "." + field)) return true;
            if (NativeConstructors.has(base)
                    && NativeConstructors.get(base).shape().members().containsKey(field)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a {@code Type.field(this)} attribute producer is registered — tolerant to the
     * linker's module qualification (a builtin type stays bare, but a producer key may be
     * bare or {@code module/Type.field}).
     */
    private static boolean hasAttributeProducer(
            Map<String, IrSort> functionReturns, String typeName, String field) {
        String bare = typeName + "." + field;
        if (functionReturns.containsKey(bare)) return true;
        String suffix = "/" + bare;
        return functionReturns.keySet().stream().anyMatch(k -> k.endsWith(suffix));
    }

    /** {@code "[A & B & …]"} over an intersection's branch base names, for diagnostics. */
    private static String describeIntersection(IrSort.Intersection inter) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < inter.branches().size(); i++) {
            if (i > 0) sb.append(" & ");
            String name = sortBaseName(inter.branches().get(i));
            sb.append(name != null ? name : inter.branches().get(i));
        }
        return sb.append("]").toString();
    }

    /** Field set of a struct scrutinee, or {@code null} for non-structs. */
    private static Set<String> scrutineeFieldSet(
            IrSort scrutineeIr, Map<String, IrSort.Structural> structDefs) {
        IrSort.Structural def = resolveNominal(scrutineeIr, structDefs);
        return def != null ? def.members().keySet() : null;
    }

    /**
     * An arm "covers" a struct scrutinee iff it's a {@link IrSort.Structural}
     * with <em>no refined fields</em> (and no nested structural fields, which
     * could themselves be partial) and whose field set is a subset of the
     * scrutinee's. By Pontif's subset-match semantics that arm matches every
     * value of that struct shape — sufficient for trivial totality.
     */
    private static boolean isBareStructuralCovering(
            IrSort armPattern, IrSort.Structural scrutinee,
            Map<String, IrSort.Structural> structDefs) {
        if (!(armPattern instanceof IrSort.Structural arm)) return false;
        for (Map.Entry<String, IrSort> e : arm.members().entrySet()) {
            IrSort declared = scrutinee.members().get(e.getKey());
            if (declared == null) return false;  // field not in the scrutinee → constrains
            IrSort member = e.getValue();
            if (member instanceof IrSort.Refined) return false;  // refines → constrains
            if (member instanceof IrSort.Structural nested) {
                // A nested destructure covers iff it covers the DECLARED nested
                // shape recursively (a different nested shape would constrain).
                IrSort.Structural declaredStruct = resolveNominal(declared, structDefs);
                if (declaredStruct == null
                        || !isBareStructuralCovering(nested, declaredStruct, structDefs)) {
                    return false;
                }
            }
            // bare Named members are binder annotations — non-constraining,
            // matching the pre-existing Tier A behavior.
        }
        return true;
    }

    private static String describeDomain(IrSort sort) {
        return switch (sort) {
            case IrSort.Named n -> n.name();
            case IrSort.Refined r -> r.name();
            default -> sort.toString();
        };
    }

    /** Renders a predicate from the integer-comparison fragment over {@code @}. */
    private static String renderPredicate(SymExpr e) {
        return switch (e) {
            case SymExpr.Bool b -> Boolean.toString(b.value());
            case SymExpr.Self ignored -> "@";
            case SymExpr.Lit l -> Long.toString(l.value());
            case SymExpr.Dec d -> d.value().toPlainString();
            case SymExpr.Cmp(SymExpr l, SymExpr.CmpOp op, SymExpr r) ->
                    renderPredicate(l) + " " + renderCmpOp(op) + " " + renderPredicate(r);
            case SymExpr.And(SymExpr l, SymExpr r) ->
                    renderPredicate(l) + " & " + renderPredicate(r);
            case SymExpr.Or(SymExpr l, SymExpr r) ->
                    "(" + renderPredicate(l) + " | " + renderPredicate(r) + ")";
            default -> e.toString();
        };
    }

    private static String renderCmpOp(SymExpr.CmpOp op) {
        return switch (op) {
            case LT -> "<";
            case LE -> "<=";
            case GT -> ">";
            case GE -> ">=";
            case EQ -> "==";
            case NE -> "!=";
        };
    }

    /**
     * Determines the static sort of an expression where possible. Returns
     * null when no inference is available (e.g., complex expressions whose
     * sort can't be derived from local context).
     *
     * <p>Now consults {@code functionReturns} for {@link IrExpr.Call}
     * expressions, so {@code (call f).x} validates against {@code f}'s
     * declared return sort — closes the TODO item about return-sort
     * propagation.
     */
    private static IrSort inferSort(IrExpr expr, Map<String, IrSort> typeEnv,
                                    Map<String, IrSort> functionReturns,
                                    Map<String, IrSort.Structural> structDefs,
                                    Set<String> algebraicFunctions) {
        // One engine: route through NarrowingInference.inferFloor — infer's
        // narrowed sort where it has one (so the FieldAccess refinement
        // projection and arithmetic bounds reach SortChecker for free), else the
        // coarse base sort the totality/field-existence consumers need. The
        // algebraic-function set lets the floor stamp a metareference's concrete
        // nominal (AlgebraicDispatch/DispatchBase) so `.ast` field-existence decides.
        return TypeSystem.standard().inferFloor(
                expr, floorContext(typeEnv, functionReturns, structDefs, algebraicFunctions));
    }

    /**
     * Builds the {@link InferenceContext} for the floor delegation. Strips
     * null-valued entries: {@code typeEnv}/{@code functionReturns} may carry
     * nulls (where a typer abstained), but {@link InferenceContext}'s canonical
     * constructor does {@code Map.copyOf}, which rejects null values. Overloads
     * stay empty so the floor's {@code Call} case falls back to
     * {@code functionReturns} — exactly the old {@code inferSort} behavior.
     */
    private static InferenceContext floorContext(
            Map<String, IrSort> typeEnv,
            Map<String, IrSort> functionReturns,
            Map<String, IrSort.Structural> structDefs,
            Set<String> algebraicFunctions) {
        return new InferenceContext(
                stripNullValues(typeEnv),
                stripNullValues(functionReturns),
                structDefs,
                Map.of(),
                Map.of(),
                Map.of(),
                java.util.Set.of(),
                algebraicFunctions,
                Map.of());
    }

    private static <V> Map<String, V> stripNullValues(Map<String, V> m) {
        Map<String, V> copy = new LinkedHashMap<>();
        for (Map.Entry<String, V> e : m.entrySet()) {
            if (e.getValue() != null) copy.put(e.getKey(), e.getValue());
        }
        return copy;
    }
}
