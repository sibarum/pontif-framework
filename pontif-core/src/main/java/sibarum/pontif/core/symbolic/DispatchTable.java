package sibarum.pontif.core.symbolic;

import sibarum.pontif.core.types.Sort;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class DispatchTable {

    private final Map<String, List<FunctionDecl>> declarations = new HashMap<>();
    private final TraitRegistry traitRegistry;

    public DispatchTable() {
        this(new TraitRegistry());
    }

    public DispatchTable(TraitRegistry traitRegistry) {
        this.traitRegistry = traitRegistry;
    }

    public DispatchTable register(FunctionDecl decl) {
        declarations.computeIfAbsent(decl.name(), k -> new ArrayList<>()).add(decl);
        return this;
    }

    public TraitRegistry traitRegistry() {
        return traitRegistry;
    }

    public List<FunctionDecl> declarationsFor(String name) {
        return List.copyOf(declarations.getOrDefault(name, List.of()));
    }

    public DispatchResult resolve(String name, List<SymExpr> arguments, Simplifier simplifier) {
        DispatchResult direct = resolveDirect(name, arguments, simplifier);
        if (!(direct instanceof DispatchResult.NoMatch)) return direct;
        // Trait-fallback: if the call name is `Trait.method` and the first
        // argument's concrete type satisfies that trait, redirect to
        // `<ConcreteType>.method`. Only fires when the direct lookup
        // produced NoMatch — ambiguous resolutions stay as-is.
        DispatchResult traitFallback = resolveTraitFallback(name, arguments, simplifier);
        if (traitFallback != null) return traitFallback;
        return direct;
    }

    private DispatchResult resolveDirect(String name, List<SymExpr> arguments, Simplifier simplifier) {
        List<FunctionDecl> candidates = declarations.getOrDefault(name, List.of());
        if (candidates.isEmpty()) {
            return DispatchResult.noMatch("No declarations registered for '" + name + "'");
        }
        // Working copy: see resolveTraitFallback's "compileCall + trait promotion" wrapper below.

        record MatchingCandidate(FunctionDecl decl, CompiledCall call) {}
        List<MatchingCandidate> matching = new ArrayList<>();
        for (FunctionDecl c : candidates) {
            if (c.parameters().size() != arguments.size()) continue;
            CompiledCall call = FunctionCheck.compileCall(c, arguments, simplifier);
            // Trait-typed param matching: bare-named sorts whose names are
            // registered traits get strict satisfaction-checking, overriding
            // Refinements.satisfies's default permissive Passed.
            call = enforceTraitParams(call, c, arguments);
            if (call.canExecute()) {
                matching.add(new MatchingCandidate(c, call));
            }
        }

        if (matching.isEmpty()) {
            return DispatchResult.noMatch(
                    "No matching declaration of '" + name + "' for the given arguments");
        }

        List<MatchingCandidate> mostSpecific = new ArrayList<>();
        for (MatchingCandidate c : matching) {
            boolean dominated = false;
            for (MatchingCandidate other : matching) {
                if (other == c) continue;
                if (isStrictlyMoreSpecific(other.decl(), c.decl(), simplifier)) {
                    dominated = true;
                    break;
                }
            }
            if (!dominated) {
                mostSpecific.add(c);
            }
        }

        if (mostSpecific.size() == 1) {
            return DispatchResult.resolved(mostSpecific.get(0).decl(), mostSpecific.get(0).call());
        }
        return DispatchResult.ambiguous(mostSpecific.stream().map(MatchingCandidate::decl).toList());
    }

    /**
     * Enforces trait satisfaction on trait-typed parameters. A bare-named
     * sort is treated as a trait if the {@link TraitRegistry} has at least
     * one registered satisfier for that name. For each such param:
     * <ul>
     *   <li>If the arg is a {@link SymExpr.Record} whose {@code typeName} is
     *       a registered satisfier → outcome is {@code StaticallyPassed}.
     *   <li>Otherwise (anonymous record, primitive, non-satisfying type) →
     *       outcome is {@code StaticallyFailed}.
     * </ul>
     *
     * <p>This overrides the default permissive behavior of
     * {@link Refinements#satisfies} for bare-named sorts (which returns
     * {@code Passed} unconditionally). Non-trait bare-named sorts keep the
     * permissive behavior.
     *
     * <p>Caveat: a trait with no registered satisfiers is treated as a
     * non-trait name here (the heuristic relies on satisfier set being
     * non-empty). Acceptable in practice — a function taking a trait with
     * no impls can't be called usefully anyway.
     */
    private CompiledCall enforceTraitParams(
            CompiledCall call, FunctionDecl decl, List<SymExpr> arguments) {
        List<CompiledCall.ParameterOutcome> updated = new ArrayList<>(call.outcomes().size());
        boolean changed = false;
        for (CompiledCall.ParameterOutcome outcome : call.outcomes()) {
            int i = outcome.parameterIndex();
            sibarum.pontif.core.types.Sort paramSort = decl.parameters().get(i).sort();
            if (!isTraitNameSort(paramSort)) {
                updated.add(outcome);
                continue;
            }
            SymExpr arg = arguments.get(i);
            boolean satisfies = arg instanceof SymExpr.Record r
                    && r.typeName() != null
                    && traitRegistry.satisfies(paramSort.name(), r.typeName());
            if (satisfies) {
                if (outcome instanceof CompiledCall.ParameterOutcome.StaticallyPassed) {
                    updated.add(outcome);
                } else {
                    updated.add(new CompiledCall.ParameterOutcome.StaticallyPassed(i));
                    changed = true;
                }
            } else {
                if (outcome instanceof CompiledCall.ParameterOutcome.StaticallyFailed) {
                    updated.add(outcome);
                } else {
                    String argDesc = arg instanceof SymExpr.Record r && r.typeName() != null
                            ? "type '" + r.typeName() + "'"
                            : "argument";
                    updated.add(new CompiledCall.ParameterOutcome.StaticallyFailed(
                            i,
                            argDesc + " does not satisfy trait '" + paramSort.name() + "'"));
                    changed = true;
                }
            }
        }
        return changed ? new CompiledCall(decl, arguments, updated) : call;
    }

    /**
     * True iff {@code sort} is a bare-named sort whose name is registered
     * as a trait (has at least one satisfier). False for refined,
     * structural, function, or unrecognized bare-named sorts.
     */
    private boolean isTraitNameSort(sibarum.pontif.core.types.Sort sort) {
        if (sort.isRefined() || sort.isStructural() || sort.isFunction()) {
            return false;
        }
        return traitRegistry.isDeclaredTrait(sort.name());
    }

    /**
     * Returns a {@link DispatchResult} from the trait-fallback path when
     * the call name has the form {@code "Trait.method"} and the first
     * argument's concrete type is a registered satisfier of the trait.
     * Returns {@code null} (not NoMatch) when the fallback isn't
     * applicable — letting the caller surface the original direct-lookup
     * result instead of overwriting its diagnostic.
     *
     * <p>Resolution: extract {@code Trait} from the call name's leading
     * dotted segment, peek at the first argument as a
     * {@link SymExpr.Record} to find its concrete type name, check the
     * {@link TraitRegistry}, then re-resolve against
     * {@code ConcreteType.method}.
     */
    private DispatchResult resolveTraitFallback(
            String name, List<SymExpr> arguments, Simplifier simplifier) {
        // A key may be module-qualified (module/Trait.method); the module part
        // can itself contain dots (a.b), so split off the module at the '/'
        // boundary FIRST, then do the Trait.method split on the local part.
        int slash = name.indexOf('/');
        String modulePrefix = slash >= 0 ? name.substring(0, slash + 1) : "";  // "module/" or ""
        String local = slash >= 0 ? name.substring(slash + 1) : name;
        int dot = local.indexOf('.');
        if (dot <= 0 || dot >= local.length() - 1) return null;
        String traitName = modulePrefix + local.substring(0, dot);  // module/Trait (or bare Trait)
        String methodName = local.substring(dot + 1);
        if (arguments.isEmpty()) return null;
        SymExpr first = arguments.get(0);
        if (!(first instanceof SymExpr.Record r)) return null;
        String concreteType = r.typeName();  // already an FQN when linked (module/Type)
        if (concreteType == null) return null;
        if (!traitRegistry.satisfies(traitName, concreteType)) return null;
        String redirected = concreteType + "." + methodName;  // module/Type.method
        DispatchResult result = resolveDirect(redirected, arguments, simplifier);
        // Suppress NoMatch fallback (let the caller report the original
        // trait-lookup error); a real Resolved or Ambiguous result wins.
        if (result instanceof DispatchResult.NoMatch) return null;
        return result;
    }

    private static boolean isStrictlyMoreSpecific(FunctionDecl a, FunctionDecl b, Simplifier simp) {
        if (!isAtLeastAsSpecific(a, b, simp)) return false;
        return !isAtLeastAsSpecific(b, a, simp);
    }

    private static boolean isAtLeastAsSpecific(FunctionDecl a, FunctionDecl b, Simplifier simp) {
        if (a.parameters().size() != b.parameters().size()) return false;
        for (int i = 0; i < a.parameters().size(); i++) {
            Sort aSort = a.parameters().get(i).sort();
            Sort bSort = b.parameters().get(i).sort();
            if (!Refinements.imply(aSort, bSort, simp).isPassed()) {
                return false;
            }
        }
        return true;
    }
}
