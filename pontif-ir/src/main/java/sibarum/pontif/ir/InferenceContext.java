package sibarum.pontif.ir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import sibarum.pontif.core.QualifiedName;

/**
 * Read-only context for {@link NarrowingInference}. Carries the
 * type environment (bound names → currently-known narrowed sorts),
 * function return signatures (Phase A fallback for {@code Call}),
 * struct definitions (Phase C field projection), the overload
 * table (Phase D static dispatch), and the {@code assign proof}
 * return proofs keyed by function name (call-site return narrowing).
 *
 * <p>Threaded through inference recursion via {@link #withVar}-style
 * extensions; never mutated in place.
 *
 * <p>{@code overloads} supersedes {@code functionReturns} when
 * populated: {@link NarrowingInference} runs {@link StaticDispatch}
 * first, falls back to the declared return on Unresolved. A
 * region-matching {@code returnProof} supersedes both (the granted
 * return is what a caller in that region observes).
 */
public record InferenceContext(
        Map<String, IrSort> typeEnv,
        Map<String, IrSort> functionReturns,
        Map<String, IrSort.Structural> structDefs,
        Map<String, List<IrStmt.FunctionDecl>> overloads,
        Map<String, List<IrStmt.ReturnProof>> returnProofs,
        Map<String, List<IrStmt.FunctionDecl>> operatorOverloads,
        Set<String> methodKeys,
        Set<String> algebraicFunctions) {

    public InferenceContext {
        typeEnv = Map.copyOf(typeEnv);
        functionReturns = Map.copyOf(functionReturns);
        structDefs = Map.copyOf(structDefs);
        // Map.copyOf doesn't preserve inner-list mutability — for safety,
        // also defensively copy the inner lists of the two name→list maps.
        overloads = copyOfLists(overloads);
        returnProofs = copyOfLists(returnProofs);
        operatorOverloads = copyOfLists(operatorOverloads);
        methodKeys = Set.copyOf(methodKeys);
        algebraicFunctions = Set.copyOf(algebraicFunctions);
    }

    private static <T> Map<String, List<T>> copyOfLists(Map<String, List<T>> m) {
        Map<String, List<T>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<T>> e : m.entrySet()) {
            copy.put(e.getKey(), List.copyOf(e.getValue()));
        }
        return Map.copyOf(copy);
    }

    public static InferenceContext empty() {
        return new InferenceContext(
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Set.of(), Set.of());
    }

    /** Convenience for tests / callers with just an env. */
    public static InferenceContext of(Map<String, IrSort> typeEnv) {
        return new InferenceContext(
                typeEnv, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Set.of(), Set.of());
    }

    /** Convenience for callers with an env and function-return map. */
    public static InferenceContext of(
            Map<String, IrSort> typeEnv,
            Map<String, IrSort> functionReturns) {
        return new InferenceContext(
                typeEnv, functionReturns, Map.of(), Map.of(), Map.of(), Map.of(), Set.of(), Set.of());
    }

    /**
     * Builds a context from an {@link IrModule}: collects per-name
     * overload lists from function decls and trait impl methods,
     * mirrors the same data into {@code functionReturns} for the Phase A
     * fallback path, collects struct definitions from preserved
     * type-alias statements, and collects {@code assign proof}
     * ({@link IrStmt.ReturnProof}) declarations per target function for
     * call-site return narrowing. Intended for end-to-end consumers.
     */
    public static InferenceContext fromModule(IrModule module) {
        Map<String, List<IrStmt.FunctionDecl>> overloads = new LinkedHashMap<>();
        Map<String, IrSort> returns = new LinkedHashMap<>();
        Map<String, List<IrStmt.ReturnProof>> returnProofs = new LinkedHashMap<>();
        // The two canonical name-routing tables (docs/dispatch-query.md): operator overloads keyed by
        // symbol (base-name family routing) and every key a method call may resolve to. Built here, in
        // statement order, so the dispatch() resolver and MethodOperatorResolver share one source
        // rather than the resolver rebuilding its own copies.
        Map<String, List<IrStmt.FunctionDecl>> operatorOverloads = new LinkedHashMap<>();
        Set<String> methodKeys = new LinkedHashSet<>();
        // Functions carrying an `assign proof f:Algebraic` claim — the set inference
        // consults to stamp a metareference `$f[…]` as `[Dispatch & Algebraic]`. The
        // claim (not the discharged proof) suffices: a false claim is a hard compile
        // error (AlgebraicCheck), so no program with a bad claim ever runs.
        Set<String> algebraicFunctions = new LinkedHashSet<>();
        for (IrStmt stmt : module.statements()) {
            if (stmt instanceof IrStmt.Proof p && "Algebraic".equals(proofHeadName(p.proofTree()))) {
                algebraicFunctions.add(p.functionName());
            }
            if (stmt instanceof IrStmt.FunctionDecl fd) {
                overloads.computeIfAbsent(fd.name(), k -> new ArrayList<>()).add(fd);
                returns.put(fd.name(), fd.returnSort());
                methodKeys.add(fd.name());
                if (fd.params().size() == 2) {
                    String sym = QualifiedName.memberOf(fd.name());
                    if (MethodOperatorResolver.isOperatorSymbol(sym)) {
                        operatorOverloads.computeIfAbsent(sym, k -> new ArrayList<>()).add(fd);
                    }
                }
            } else if (stmt instanceof IrStmt.TraitImpl ti) {
                for (IrStmt.FunctionDecl m : ti.methods()) {
                    overloads.computeIfAbsent(m.name(), k -> new ArrayList<>()).add(m);
                    returns.put(m.name(), m.returnSort());
                    methodKeys.add(m.name());
                }
                for (IrStmt.FunctionDecl a : ti.attributeProducers()) {
                    overloads.computeIfAbsent(a.name(), k -> new ArrayList<>()).add(a);
                    returns.put(a.name(), a.returnSort());
                    methodKeys.add(a.name());
                }
            } else if (stmt instanceof IrStmt.ReturnProof rp) {
                returnProofs.computeIfAbsent(rp.functionName(), k -> new ArrayList<>()).add(rp);
            } else if (stmt instanceof IrStmt.TypeAlias ta
                    && ta.sort() instanceof IrSort.Trait t) {
                // Trait contract methods route as `Trait.method` on a bare-trait receiver (the
                // existential boundary); a concrete receiver resolves to `ConcreteType.method`.
                for (String methodName : t.methods().keySet()) {
                    methodKeys.add(t.name() + "." + methodName);
                }
                // Existential boundary (docs/associated-types.md §3.2, §7.3). A
                // contract method whose return mentions an associated type or
                // `this.type`, called on a *bare-trait* receiver, resolves to the
                // call key `Trait.method` — a *concrete* receiver resolves to
                // `ConcreteType.method` instead, so this entry is consulted only
                // at the existential boundary. Its registered return is the type
                // variable *existentialized*: an associated type `∃T:R` to its
                // bound `R` (usable as `R` — a chained `b.get().describe()` types
                // through R; unbounded stays opaque), and `this.type` to the
                // owning trait (so `e.copy()` on `e:Expr` flows out as `Expr`).
                Map<String, IrSort> exVars = new LinkedHashMap<>(t.associatedTypes());
                exVars.put(IrSort.SELF_TYPE, IrSort.named(t.name()));
                for (Map.Entry<String, IrSort.Method> m : t.methods().entrySet()) {
                    IrSort ret = m.getValue().returnSort();
                    if (mentionsAssociatedType(ret, exVars.keySet())) {
                        returns.put(t.name() + "." + m.getKey(), existentialize(ret, exVars));
                    }
                }
            }
        }
        Map<String, IrSort.Structural> structs =
                sibarum.pontif.types.TypeCatalog.fromModule(module).structShapes();
        return new InferenceContext(Map.of(), returns, structs, overloads, returnProofs,
                operatorOverloads, methodKeys, algebraicFunctions);
    }

    /** The local (module-stripped) head-constructor name of a {@code proof} tree, or null. */
    private static String proofHeadName(IrExpr tree) {
        String name = switch (tree) {
            case IrExpr.Record r -> r.typeName();
            case IrExpr.Call c -> c.functionName();
            default -> null;
        };
        return name == null ? null : QualifiedName.memberOf(name);
    }

    /** Whether {@code sort} references any of the given associated-type names. */
    private static boolean mentionsAssociatedType(IrSort sort, Set<String> names) {
        return switch (sort) {
            case IrSort.Named n -> names.contains(n.name())
                    || n.typeArgs().stream().anyMatch(a -> mentionsAssociatedType(a, names));
            case IrSort.Refined r -> names.contains(r.name());
            case IrSort.Method m -> mentionsAssociatedType(m.returnSort(), names)
                    || m.paramSorts().stream().anyMatch(p -> mentionsAssociatedType(p, names));
            case IrSort.Union u -> u.branches().stream().anyMatch(b -> mentionsAssociatedType(b, names));
            case IrSort.Intersection i -> i.branches().stream().anyMatch(b -> mentionsAssociatedType(b, names));
            case IrSort.Dispatch d -> mentionsAssociatedType(d.returnSort(), names)
                    || d.keySorts().stream().anyMatch(k -> mentionsAssociatedType(k, names));
            default -> false;
        };
    }

    /**
     * Replaces each associated-type name in {@code sort} with its declared
     * bound (the {@code R} in {@code type T:R}) — the type-level reading of the
     * {@code refine} operator. A name bound to {@code null} (unbounded
     * {@code type T}) is left as-is: the existential stays opaque, with no
     * interface to call into. Structural recursion mirrors
     * {@code SortChecker.substituteTypeVars}, but maps a name to its bound
     * rather than to a per-impl binding.
     */
    private static IrSort existentialize(IrSort sort, Map<String, IrSort> assoc) {
        return switch (sort) {
            case IrSort.Named n -> {
                if (assoc.containsKey(n.name())) {
                    IrSort bound = assoc.get(n.name());
                    yield bound != null ? bound : n;
                }
                if (n.typeArgs().isEmpty()) yield n;
                yield new IrSort.Named(n.name(),
                        n.typeArgs().stream().map(a -> existentialize(a, assoc)).toList(),
                        n.origin());
            }
            case IrSort.Method m -> new IrSort.Method(
                    m.paramSorts().stream().map(p -> existentialize(p, assoc)).toList(),
                    existentialize(m.returnSort(), assoc), m.origin());
            case IrSort.Union u -> new IrSort.Union(
                    u.branches().stream().map(b -> existentialize(b, assoc)).toList(), u.origin());
            case IrSort.Intersection i -> new IrSort.Intersection(
                    i.branches().stream().map(b -> existentialize(b, assoc)).toList(), i.origin());
            case IrSort.Dispatch d -> new IrSort.Dispatch(
                    d.keySorts().stream().map(k -> existentialize(k, assoc)).toList(),
                    existentialize(d.returnSort(), assoc), d.origin());
            // Refined/Structural/Trait: an associated type appears as a bare
            // Named in practice; these aren't substitution sites for this slice.
            default -> sort;
        };
    }

    /** Returns a new context with the given name bound to {@code sort}. */
    public InferenceContext withVar(String name, IrSort sort) {
        Map<String, IrSort> extended = new HashMap<>(typeEnv);
        extended.put(name, sort);
        return new InferenceContext(extended, functionReturns, structDefs, overloads, returnProofs,
                operatorOverloads, methodKeys, algebraicFunctions);
    }

    /** Returns a new context with the struct-defs map replaced. */
    public InferenceContext withStructDefs(Map<String, IrSort.Structural> defs) {
        return new InferenceContext(typeEnv, functionReturns, defs, overloads, returnProofs,
                operatorOverloads, methodKeys, algebraicFunctions);
    }

    /** Returns a new context with the overload map replaced. */
    public InferenceContext withOverloads(Map<String, List<IrStmt.FunctionDecl>> ovs) {
        return new InferenceContext(typeEnv, functionReturns, structDefs, ovs, returnProofs,
                operatorOverloads, methodKeys, algebraicFunctions);
    }

    /**
     * The nominal-struct registry (name → structural {@link sibarum.pontif.core.types.Sort})
     * for {@link StaticDispatch}, so subsumption between by-reference struct
     * sorts is decided structurally rather than treating them as unconstrained.
     * Best-effort: a struct whose definition fails to compile is omitted (the
     * consumer then degrades to the conservative bare-name comparison).
     */
    public Map<String, sibarum.pontif.core.types.Sort> sortRegistry() {
        Map<String, sibarum.pontif.core.types.Sort> reg = new LinkedHashMap<>();
        for (Map.Entry<String, IrSort.Structural> e : structDefs.entrySet()) {
            try {
                reg.put(e.getKey(), IrCompiler.compileSort(e.getValue()));
            } catch (CompileException ignored) {
                // Skip — consumer falls back to bare-name comparison for this one.
            }
        }
        return reg;
    }
}
