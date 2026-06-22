package sibarum.pontif.ir;

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
     * Compiler-known parametric types that are valid in a sort position even
     * though they are not declared structs. {@code Stream[T]} is the homogeneous
     * sequence a tuple autoboxes into (docs/iteration.md §8.6); figurative for now
     * (no member contract checked here — the autobox's element gate is the check).
     */
    private static final Set<String> BUILTIN_PARAMETRIC_TYPES = Set.of("Stream");

    private SortChecker() {}

    public static void check(IrModule module) throws CompileException {
        Map<String, IrSort> functionReturns = collectFunctionReturns(module);
        Map<String, IrSort.Trait> traitContracts = collectTraitContracts(module);
        Map<String, IrSort.Structural> structDefs = TypeRegistry.collect(module);
        // Free-function / operator overloads by name (e.g. all `+` declarations) —
        // the mechanism-1 dispatch entries an operator trait contract is checked
        // against (does a coherent `+(T, T):T` exist for the satisfying type T?).
        Map<String, List<IrStmt.FunctionDecl>> overloads = collectOverloadsByName(module);
        // The declared trait-satisfaction relation (type satisfies trait), from
        // every `assign trait` block — used to check associated-type bounds.
        Set<String> satisfies = new HashSet<>();
        for (IrStmt s : module.statements()) {
            if (s instanceof IrStmt.TraitImpl t) {
                satisfies.add(t.typeName() + " " + t.traitName());
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
                for (IrParam p : fd.params()) {
                    validateSortNames(p.sort(), structDefs, fnTypeVars);
                    typeEnv.put(p.name(), p.sort());
                }
                validateSortNames(fd.returnSort(), structDefs, fnTypeVars);
                checkExpr(fd.body(), typeEnv, functionReturns, structDefs, fnTypeVars);
                // Operator bound propagation (dispatch-unification B1): an operator
                // applied to a value of a trait-bounded type parameter is checked
                // against the bound's operator contract members — `a + b` over
                // `E:Numeric` is licensed only if `Numeric` declares `+`. Makes
                // operator use over an abstract type decidable at definition time,
                // not at the call site's monomorphization.
                checkOperatorBounds(fd, typeEnv, traitContracts, functionReturns);
            } else if (stmt instanceof IrStmt.TraitImpl ti) {
                validateTraitImpl(ti, traitContracts, functionReturns, structDefs, satisfies, overloads);
            } else if (stmt instanceof IrStmt.TypeAlias ta && ta.sort() instanceof IrSort.Trait tr) {
                // Validate a trait DECLARATION end-to-end: its member sorts must
                // reference only known sorts — primitives, declared types, or the
                // trait's own `type X` associated types (scoped by the Trait case
                // of validateSortNames). Catches `[Method():Undeclared]` while
                // admitting `[Method():T]` for a declared `type T`.
                validateSortNames(tr, structDefs);
            }
        }
        checkExpr(module.main(), new HashMap<>(), functionReturns, structDefs);
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
        Map<String, IrSort.Method> methods = new LinkedHashMap<>();
        Map<String, IrSort> attributes = new LinkedHashMap<>();
        Map<String, IrSort> associatedTypes = new LinkedHashMap<>();
        Map<String, IrSort> typeParams = new LinkedHashMap<>();
        Map<String, IrSort.Dispatch> operators = new LinkedHashMap<>();
        for (int i = chain.size() - 1; i >= 0; i--) {  // root-first → derived overrides base
            IrSort.Trait c = chain.get(i);
            methods.putAll(c.methods());
            attributes.putAll(c.attributes());
            associatedTypes.putAll(c.associatedTypes());
            typeParams.putAll(c.typeParams());
            operators.putAll(c.operators());
        }
        return new IrSort.Trait(trait.name(), methods, attributes, associatedTypes,
                typeParams, operators, trait.baseTrait(), trait.origin());
    }

    private static void validateTraitImpl(
            IrStmt.TraitImpl ti,
            Map<String, IrSort.Trait> traitContracts,
            Map<String, IrSort> functionReturns,
            Map<String, IrSort.Structural> structDefs,
            Set<String> satisfies,
            Map<String, List<IrStmt.FunctionDecl>> overloads) throws CompileException {
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
            String boundType = boundName(binding);
            String reqTrait = boundName(bound);
            if (reqTrait == null) continue;  // unknown bound shape — nothing to check
            boolean ok = boundType != null
                    && (boundType.equals(reqTrait)
                            || satisfies.contains(boundType + " " + reqTrait));
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

        // Build short-name -> impl map (stripping the "Type." prefix).
        Map<String, IrStmt.FunctionDecl> implByShortName = new LinkedHashMap<>();
        String prefix = ti.typeName() + ".";
        for (IrStmt.FunctionDecl m : ti.methods()) {
            String shortName = m.name().startsWith(prefix)
                    ? m.name().substring(prefix.length())
                    : m.name();
            implByShortName.put(shortName, m);
        }

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
        for (Map.Entry<String, IrSort.Method> e : contract.methods().entrySet()) {
            String methodName = e.getKey();
            IrSort.Method contractSig = e.getValue();
            IrStmt.FunctionDecl impl = implByShortName.get(methodName);
            if (impl == null) {
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
                IrSort.Method want = (IrSort.Method)
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
        for (Map.Entry<String, IrSort.Dispatch> op : contract.operators().entrySet()) {
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
        String prodPrefix = ti.typeName() + ".";
        for (IrStmt.FunctionDecl a : ti.attributeProducers()) {
            String shortName = a.name().startsWith(prodPrefix)
                    ? a.name().substring(prodPrefix.length())
                    : a.name();
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
            validateImplBody(m, functionReturns, structDefs, implTypeVars);
        }
        for (IrStmt.FunctionDecl a : ti.attributeProducers()) {
            validateImplBody(a, functionReturns, structDefs, implTypeVars);
        }
    }

    private static void validateImplBody(
            IrStmt.FunctionDecl m,
            Map<String, IrSort> functionReturns,
            Map<String, IrSort.Structural> structDefs,
            Set<String> typeVars) throws CompileException {
        Map<String, IrSort> typeEnv = new HashMap<>();
        for (IrParam p : m.params()) {
            validateSortNames(p.sort(), structDefs, typeVars);
            typeEnv.put(p.name(), p.sort());
        }
        validateSortNames(m.returnSort(), structDefs, typeVars);
        checkExpr(m.body(), typeEnv, functionReturns, structDefs, typeVars);
    }

    /**
     * Fail-closed check that a satisfier's existing field discharges a trait
     * attribute requirement. The bases must match; an unrefined requirement
     * (existence + type) needs only that. A refined requirement (e.g.
     * {@code [Int:@>0]}) requires the field to already carry a refinement that
     * matches structurally — a conservative, sound rule: a field whose stronger
     * predicate merely <em>implies</em> the requirement (but isn't identical) is
     * rejected here, fail-closed (full predicate-implication is deferred; the
     * user can instead provide a producer, which rides the proof gate).
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
        if (attrSort instanceof IrSort.Refined attrRef) {
            if (!(fieldSort instanceof IrSort.Refined fieldRef)
                    || !predicatesEqual(fieldRef.predicate(), attrRef.predicate())) {
                throw new CompileException(
                        "Trait impl '" + ti.typeName() + " : " + ti.traitName()
                                + "': field '" + attrName + "' does not provably satisfy the "
                                + "refined requirement trait '" + ti.traitName() + "' places on it "
                                + "— declare the field with the matching refinement, or provide a "
                                + "producer (which is proof-checked)",
                        ti.origin());
            }
        }
    }

    /** Base sort name for an attribute/field sort (null if structureless). */
    private static String sortBaseName(IrSort sort) {
        return switch (sort) {
            case IrSort.Named n -> n.name();
            case IrSort.Refined r -> r.name();
            case IrSort.Structural s -> s.name();
            default -> null;
        };
    }

    /** Structural predicate equality, ignoring {@link sibarum.pontif.core.Origin}. */
    private static boolean predicatesEqual(IrExpr a, IrExpr b) {
        if (a == b) return true;
        if (a == null || b == null || a.getClass() != b.getClass()) return false;
        return switch (a) {
            case IrExpr.SelfRef ignored -> true;
            case IrExpr.Var v -> v.name().equals(((IrExpr.Var) b).name());
            case IrExpr.Lit l -> java.util.Objects.equals(l.value(), ((IrExpr.Lit) b).value());
            case IrExpr.BinOp op -> {
                IrExpr.BinOp ob = (IrExpr.BinOp) b;
                yield op.op() == ob.op()
                        && predicatesEqual(op.left(), ob.left())
                        && predicatesEqual(op.right(), ob.right());
            }
            case IrExpr.FieldAccess fa -> {
                IrExpr.FieldAccess fb = (IrExpr.FieldAccess) b;
                yield fa.fieldName().equals(fb.fieldName())
                        && predicatesEqual(fa.base(), fb.base());
            }
            default -> a.equals(b);
        };
    }

    /**
     * Builds {@code functionName → returnSort} from the module's function
     * declarations. With overloads, the last-encountered overload's return
     * sort wins — best-effort, since cross-overload return narrowing waits
     * on the in-progress dispatch-inference work.
     */
    private static Map<String, IrSort> collectFunctionReturns(IrModule module) {
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
                for (Map.Entry<String, IrSort.Method> e : t.methods().entrySet()) {
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

    private static void validateSortNames(IrSort sort, Map<String, IrSort.Structural> structDefs)
            throws CompileException {
        validateSortNames(sort, structDefs, Set.of());
    }

    /**
     * @param typeVars in-scope associated-type names — the {@code type X}
     *     members of an enclosing trait. A {@code Named} matching one of these
     *     is a bound type variable, not an unknown sort. Empty at the top level;
     *     extended by the {@link IrSort.Trait} case as it descends into a
     *     trait's own member sorts.
     */
    private static void validateSortNames(
            IrSort sort, Map<String, IrSort.Structural> structDefs, Set<String> typeVars)
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
                        && !structDefs.containsKey(n.name())
                        && !typeVars.contains(n.name())) {
                    throw new CompileException(
                            "Unknown sort '" + n.name() + "' — not a primitive "
                                    + "and not a declared type (did you forget a "
                                    + "'struct " + n.name() + "(...)' declaration?)",
                            n.origin());
                }
                // Type arguments of a parametric application (`Element[Int]`,
                // `Element[T]`) are themselves sorts — validate each in scope.
                for (IrSort arg : n.typeArgs()) {
                    validateSortNames(arg, structDefs, typeVars);
                }
            }
            case IrSort.Refined r -> {
                // A parametric base's type arguments (`[Literal[Int]:…]`) are
                // sorts — validate each in scope, like a Named application.
                for (IrSort arg : r.typeArgs()) {
                    validateSortNames(arg, structDefs, typeVars);
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
                validateSelfFieldAccesses(r.predicate(), baseStruct, r.origin());
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
                    if (bound != null) validateSortNames(bound, structDefs, inner);
                }
                for (IrSort member : s.members().values()) {
                    validateSortNames(member, structDefs, inner);
                }
            }
            case IrSort.Method f -> {
                for (IrSort p : f.paramSorts()) validateSortNames(p, structDefs, typeVars);
                validateSortNames(f.returnSort(), structDefs, typeVars);
            }
            case IrSort.Dispatch d -> {
                for (IrSort k : d.keySorts()) validateSortNames(k, structDefs, typeVars);
                validateSortNames(d.returnSort(), structDefs, typeVars);
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
                    if (bound != null) validateSortNames(bound, structDefs, inner);
                }
                for (IrSort.Method f : t.methods().values()) validateSortNames(f, structDefs, inner);
            }
            case IrSort.Union u -> {
                for (IrSort b : u.branches()) validateSortNames(b, structDefs, typeVars);
            }
            case IrSort.Intersection i -> {
                for (IrSort b : i.branches()) validateSortNames(b, structDefs, typeVars);
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
        if (base instanceof IrSort.Refined r && structDefs.containsKey(r.name())) {
            IrSort.Structural baseStruct = structDefs.get(r.name());
            Set<String> pinned = new HashSet<>();
            collectPinnedBaseFields(r.predicate(), pinned);
            for (String field : baseStruct.members().keySet()) {
                if (!pinned.contains(field)) {
                    throw new CompileException(
                            "struct '" + s.name() + "' demotes to '" + r.name()
                                    + "' but its morphism does not pin base field '@."
                                    + field + "' — every base field must be functionally "
                                    + "determined (e.g. '@." + field + " == <expr>'); pinned: "
                                    + pinned,
                            s.origin());
                }
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

        for (Map.Entry<String, IrSort> bf : baseStruct.members().entrySet()) {
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
     * the child expression a base field is pinned to.
     */
    private static void collectPinnedFieldExprs(IrExpr pred, Map<String, IrExpr> out) {
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
            case IrSort.Method ma -> b instanceof IrSort.Method mb
                    && sortListsExactlyEqual(ma.paramSorts(), mb.paramSorts())
                    && sortsExactlyEqual(ma.returnSort(), mb.returnSort());
            case IrSort.Dispatch da -> b instanceof IrSort.Dispatch db
                    && sortListsExactlyEqual(da.keySorts(), db.keySorts())
                    && sortsExactlyEqual(da.returnSort(), db.returnSort());
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
     * Walks a struct-refinement's predicate looking for {@code @.field}
     * accesses (i.e., {@link IrExpr.FieldAccess} whose base is
     * {@link IrExpr.SelfRef}) and verifies each {@code field} exists in
     * the struct. Nested field-access chains are not yet validated past
     * the first level — covered when sort inference can statically
     * project nested field sorts.
     */
    private static void validateSelfFieldAccesses(
            IrExpr predicate,
            IrSort.Structural baseStruct,
            sibarum.pontif.core.Origin refOrigin) throws CompileException {
        switch (predicate) {
            case IrExpr.FieldAccess fa -> {
                if (fa.base() instanceof IrExpr.SelfRef) {
                    if (!baseStruct.members().containsKey(fa.fieldName())) {
                        throw new CompileException(
                                "Refinement [" + baseStruct.name() + ":…] references "
                                        + "@." + fa.fieldName() + " but struct '"
                                        + baseStruct.name() + "' has no such field; "
                                        + "available: " + baseStruct.members().keySet(),
                                fa.origin() != null ? fa.origin() : refOrigin);
                    }
                }
                validateSelfFieldAccesses(fa.base(), baseStruct, refOrigin);
            }
            case IrExpr.BinOp op -> {
                validateSelfFieldAccesses(op.left(), baseStruct, refOrigin);
                validateSelfFieldAccesses(op.right(), baseStruct, refOrigin);
            }
            case IrExpr.LetIn l -> {
                validateSelfFieldAccesses(l.value(), baseStruct, refOrigin);
                validateSelfFieldAccesses(l.body(), baseStruct, refOrigin);
            }
            case IrExpr.Call c -> {
                for (IrExpr a : c.args()) validateSelfFieldAccesses(a, baseStruct, refOrigin);
            }
            case IrExpr.Apply a -> {
                validateSelfFieldAccesses(a.fn(), baseStruct, refOrigin);
                for (IrExpr arg : a.args()) validateSelfFieldAccesses(arg, baseStruct, refOrigin);
            }
            case IrExpr.Lambda lam -> validateSelfFieldAccesses(lam.body(), baseStruct, refOrigin);
            case IrExpr.Match m -> {
                validateSelfFieldAccesses(m.scrutinee(), baseStruct, refOrigin);
                for (IrExpr.MatchBranch b : m.branches()) {
                    validateSelfFieldAccesses(b.result(), baseStruct, refOrigin);
                }
            }
            case IrExpr.Record r -> {
                for (IrExpr v : r.members().values()) {
                    validateSelfFieldAccesses(v, baseStruct, refOrigin);
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
                validateSelfFieldAccesses(mc.receiver(), baseStruct, refOrigin);
                for (IrExpr arg : mc.args()) validateSelfFieldAccesses(arg, baseStruct, refOrigin);
            }
            // An iteration construct never appears inside a refinement predicate.
            case IrExpr.Iterate ignored -> {}
            case IrExpr.Cast cast -> validateSelfFieldAccesses(cast.value(), baseStruct, refOrigin);
        }
    }

    private static void checkExpr(IrExpr expr, Map<String, IrSort> typeEnv,
                                  Map<String, IrSort> functionReturns,
                                  Map<String, IrSort.Structural> structDefs)
            throws CompileException {
        checkExpr(expr, typeEnv, functionReturns, structDefs, Set.of());
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
                                  Set<String> typeVars)
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
                checkExpr(op.left(), typeEnv, functionReturns, structDefs, typeVars);
                checkExpr(op.right(), typeEnv, functionReturns, structDefs, typeVars);
            }
            case IrExpr.LetIn l -> {
                validateSortNames(l.declaredSort(), structDefs, typeVars);
                checkExpr(l.value(), typeEnv, functionReturns, structDefs, typeVars);
                Map<String, IrSort> extended = new HashMap<>(typeEnv);
                IrSort bound = l.declaredSort();
                // An undeclared binder ("_") takes the value's inferred sort,
                // so a match on the binding has a real domain to prove
                // totality over (the parser's synthetic scrutinee lets and
                // bare `let x = …` both land here).
                if (bound instanceof IrSort.Named n && n.name().equals("_")) {
                    IrSort inferred = inferSort(l.value(), typeEnv, functionReturns, structDefs);
                    if (inferred != null) bound = inferred;
                }
                extended.put(l.name(), bound);
                checkExpr(l.body(), extended, functionReturns, structDefs, typeVars);
            }
            case IrExpr.Call c -> {
                // The name might be a top-level function/method, OR a locally
                // bound callable (let-bound lambda, function param of Function
                // sort). Either is legal. Only reject when neither is true.
                if (!functionReturns.containsKey(c.functionName())
                        && !typeEnv.containsKey(c.functionName())) {
                    throw new CompileException(
                            "Unknown function '" + c.functionName() + "' — no "
                                    + "matching declaration (overload-mismatch errors "
                                    + "happen at dispatch time; this means no overload "
                                    + "of '" + c.functionName() + "' exists at all).",
                            c.origin());
                }
                for (IrExpr arg : c.args()) checkExpr(arg, typeEnv, functionReturns, structDefs, typeVars);
            }
            case IrExpr.Lambda lam -> {
                // The enclosing function's type params are in scope for a fragment's
                // own param/return sorts — `&s:[ (el:A) -> … ]` inside a generic fn
                // (the streams-motivated generics slice).
                for (IrParam p : lam.params()) validateSortNames(p.sort(), structDefs, typeVars);
                validateSortNames(lam.returnSort(), structDefs, typeVars);
                Map<String, IrSort> extended = new HashMap<>(typeEnv);
                for (IrParam p : lam.params()) extended.put(p.name(), p.sort());
                checkExpr(lam.body(), extended, functionReturns, structDefs, typeVars);
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
                checkExpr(app.fn(), typeEnv, functionReturns, structDefs, typeVars);
                for (IrExpr a : app.args()) checkExpr(a, typeEnv, functionReturns, structDefs, typeVars);
            }
            case IrExpr.Match m -> {
                checkExpr(m.scrutinee(), typeEnv, functionReturns, structDefs, typeVars);
                for (IrExpr.MatchBranch b : m.branches()) {
                    validateSortNames(b.pattern(), structDefs, typeVars);
                    Map<String, IrSort> branchEnv = new HashMap<>(typeEnv);
                    if (m.scrutinee() instanceof IrExpr.Var v
                            && b.pattern() instanceof IrSort.Structural) {
                        branchEnv.put(v.name(), b.pattern());
                    }
                    checkExpr(b.result(), branchEnv, functionReturns, structDefs, typeVars);
                }
                checkMatchTotality(m, typeEnv, functionReturns, structDefs);
            }
            case IrExpr.Record r -> {
                for (IrExpr v : r.members().values()) checkExpr(v, typeEnv, functionReturns, structDefs, typeVars);
            }
            case IrExpr.FieldAccess fa -> {
                checkExpr(fa.base(), typeEnv, functionReturns, structDefs, typeVars);
                IrSort baseSort = inferSort(fa.base(), typeEnv, functionReturns, structDefs);
                IrSort.Structural sp = resolveNominal(baseSort, structDefs);
                // Anonymous aggregates ('_record'/'_tuple') defer field-existence to
                // the runtime check (RuntimeCheckException carries the access origin);
                // only NOMINAL structs are compile-checked here. (The floor now gives
                // an anonymous record a structural shape for the parser's base-name
                // needs, but that must not change this check's nominal-only contract.)
                if (sp != null && sp.name().startsWith("_")) {
                    sp = null;
                }
                if (sp != null) {
                    // A trait attribute reads as a field but is stored as a
                    // computed projection: `x.weight` resolves to the satisfier's
                    // `Type.weight(this)` producer. Allow it when no real field
                    // exists but such an accessor is registered.
                    boolean isAttributeProjection =
                            functionReturns.containsKey(sp.name() + "." + fa.fieldName());
                    if (!sp.members().containsKey(fa.fieldName()) && !isAttributeProjection) {
                        throw new CompileException(
                                "Record of sort '" + sp.name() + "' has no field '"
                                        + fa.fieldName() + "'; available fields: "
                                        + sp.members().keySet(),
                                fa.origin());
                    }
                } else if (baseSort != null) {
                    // Native anatomies get the same typo coverage as structs.
                    String base = matchBaseName(baseSort);
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
            case IrExpr.MethodCall mc -> throw MethodResolver.unresolved(mc, "SortChecker");
            case IrExpr.Iterate it -> {
                // The source and accumulator inits are evaluated outside the loop;
                // the element binder and the accumulator names are in scope inside
                // each arm's writes — bind them so the unbound-variable check (the
                // Var case) doesn't flag them.
                checkExpr(it.source(), typeEnv, functionReturns, structDefs, typeVars);
                Map<String, IrSort> iterEnv = new HashMap<>(typeEnv);
                iterEnv.put(it.element(), IrSort.named("_"));
                for (IrExpr.OutputSpec os : it.outputs()) {
                    if (os.init() != null) {
                        checkExpr(os.init(), typeEnv, functionReturns, structDefs, typeVars);
                    }
                    if (os.kind() == IrExpr.OutputKind.ACCUMULATOR) {
                        iterEnv.put(os.name(), IrSort.named("_"));
                    }
                }
                for (IrExpr.Arm arm : it.arms()) {
                    validateSortNames(arm.pattern(), structDefs, typeVars);
                    for (IrExpr.Write w : arm.writes()) {
                        if (w.key() != null) checkExpr(w.key(), iterEnv, functionReturns, structDefs, typeVars);
                        checkExpr(w.value(), iterEnv, functionReturns, structDefs, typeVars);
                    }
                }
                // The always-on conservation discipline (§4): output-kind/write
                // agreement and no-silent-erase. (home-vs-observe ownership and
                // fold-carry content accounting stay deferred — docs/iteration.md §10.)
                checkIterationConservation(it);
            }
            case IrExpr.Cast cast -> {
                // The target names a sort (validate it like any reference); the
                // value is an ordinary sub-expression. Whether the source→target
                // coercion is actually supported is enforced at eval (slice 1 =
                // built-in renders to String) — fail-closed there, not here.
                validateSortNames(cast.targetSort(), structDefs, typeVars);
                checkExpr(cast.value(), typeEnv, functionReturns, structDefs, typeVars);
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
     * Match totality (alt-syntax principle 8): every value of the scrutinee's
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
            Map<String, IrSort.Structural> structDefs) throws CompileException {
        IrSort scrutineeIr = widenOpenPinToBase(
                inferSort(m.scrutinee(), typeEnv, functionReturns, structDefs));

        // A catch-all arm makes the match total by construction, regardless of
        // what the other arms look like (ordered match: it catches the rest).
        if (hasCatchAllArm(m, scrutineeIr)) {
            return;
        }
        if (scrutineeIr == null) {
            throw cannotProveTotality(m, null, "the scrutinee's sort is not statically known");
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
        String scrutineeBase = scrutineeIr == null ? null : matchBaseName(scrutineeIr);
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
            String base = matchBaseName(branch);
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

    private static String matchBaseName(IrSort sort) {
        return switch (sort) {
            case IrSort.Named n -> n.name();
            case IrSort.Refined r -> r.name();
            case IrSort.Structural s -> s.name();
            default -> null;
        };
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
        return type.equals(matchBaseName(o.params().get(0).sort()))
                && type.equals(matchBaseName(o.params().get(1).sort()))
                && type.equals(matchBaseName(o.returnSort()));
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
            String boundName = bound == null ? null : matchBaseName(bound);
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
            String base = s == null ? null : matchBaseName(s);
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
                && typeParam.equals(matchBaseName(argSorts.get(0)))
                && typeParam.equals(matchBaseName(argSorts.get(1)));
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
            case IrSort.Method f -> f.paramSorts().stream().anyMatch(p -> mentionsAny(p, names))
                    || mentionsAny(f.returnSort(), names);
            case IrSort.Union u -> u.branches().stream().anyMatch(b -> mentionsAny(b, names));
            case IrSort.Intersection i -> i.branches().stream().anyMatch(b -> mentionsAny(b, names));
            case IrSort.Structural s -> s.members().values().stream().anyMatch(mm -> mentionsAny(mm, names));
            case IrSort.Dispatch d -> d.keySorts().stream().anyMatch(k -> mentionsAny(k, names))
                    || mentionsAny(d.returnSort(), names);
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
            case IrSort.Method f -> new IrSort.Method(
                    f.paramSorts().stream().map(p -> substituteTypeVars(p, bindings)).toList(),
                    substituteTypeVars(f.returnSort(), bindings), f.origin());
            case IrSort.Union u -> new IrSort.Union(
                    u.branches().stream().map(b -> substituteTypeVars(b, bindings)).toList(), u.origin());
            case IrSort.Intersection i -> new IrSort.Intersection(
                    i.branches().stream().map(b -> substituteTypeVars(b, bindings)).toList(), i.origin());
            case IrSort.Dispatch d -> new IrSort.Dispatch(
                    d.keySorts().stream().map(k -> substituteTypeVars(k, bindings)).toList(),
                    substituteTypeVars(d.returnSort(), bindings), d.origin());
            case IrSort.Structural s -> {
                Map<String, IrSort> mem = new LinkedHashMap<>();
                for (Map.Entry<String, IrSort> e : s.members().entrySet()) {
                    mem.put(e.getKey(), substituteTypeVars(e.getValue(), bindings));
                }
                yield new IrSort.Structural(
                        s.name(), mem,
                        s.baseSort() == null ? null : substituteTypeVars(s.baseSort(), bindings),
                        s.origin());
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

    /** Nominal name of a sort, including a trait (which {@link #matchBaseName} omits). */
    private static String boundName(IrSort sort) {
        return switch (sort) {
            case IrSort.Named n -> n.name();
            case IrSort.Refined r -> r.name();
            case IrSort.Structural s -> s.name();
            case IrSort.Trait t -> t.name();
            default -> null;
        };
    }

    /** Conservative conformance: two sorts share a base name (both non-null). */
    private static boolean sameBaseSort(IrSort a, IrSort b) {
        String an = matchBaseName(a);
        String bn = matchBaseName(b);
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
                                    Map<String, IrSort.Structural> structDefs) {
        // One engine: route through NarrowingInference.inferFloor — infer's
        // narrowed sort where it has one (so the FieldAccess refinement
        // projection and arithmetic bounds reach SortChecker for free), else the
        // coarse base sort the totality/field-existence consumers need.
        return NarrowingInference.inferFloor(
                expr, floorContext(typeEnv, functionReturns, structDefs));
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
            Map<String, IrSort.Structural> structDefs) {
        return new InferenceContext(
                stripNullValues(typeEnv),
                stripNullValues(functionReturns),
                structDefs,
                Map.of(),
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
