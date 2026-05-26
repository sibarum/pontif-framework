package sibarum.pontif.ir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only context for {@link NarrowingInference}. Carries the
 * type environment (bound names → currently-known narrowed sorts),
 * function return signatures (Phase A fallback for {@code Call}),
 * struct definitions (Phase C field projection), and the overload
 * table (Phase D static dispatch).
 *
 * <p>Threaded through inference recursion via {@link #withVar}-style
 * extensions; never mutated in place.
 *
 * <p>{@code overloads} supersedes {@code functionReturns} when
 * populated: {@link NarrowingInference} runs {@link StaticDispatch}
 * first, falls back to the declared return on Unresolved.
 */
public record InferenceContext(
        Map<String, IrSort> typeEnv,
        Map<String, IrSort> functionReturns,
        Map<String, IrSort.Structural> structDefs,
        Map<String, List<IrStmt.FunctionDecl>> overloads) {

    public InferenceContext {
        typeEnv = Map.copyOf(typeEnv);
        functionReturns = Map.copyOf(functionReturns);
        structDefs = Map.copyOf(structDefs);
        // Map.copyOf doesn't preserve inner-list mutability — for safety,
        // also defensively copy inner lists.
        Map<String, List<IrStmt.FunctionDecl>> ovCopy = new LinkedHashMap<>();
        for (Map.Entry<String, List<IrStmt.FunctionDecl>> e : overloads.entrySet()) {
            ovCopy.put(e.getKey(), List.copyOf(e.getValue()));
        }
        overloads = Map.copyOf(ovCopy);
    }

    public static InferenceContext empty() {
        return new InferenceContext(Map.of(), Map.of(), Map.of(), Map.of());
    }

    /** Convenience for tests / callers with just an env. */
    public static InferenceContext of(Map<String, IrSort> typeEnv) {
        return new InferenceContext(typeEnv, Map.of(), Map.of(), Map.of());
    }

    /** Convenience for callers with an env and function-return map. */
    public static InferenceContext of(
            Map<String, IrSort> typeEnv,
            Map<String, IrSort> functionReturns) {
        return new InferenceContext(typeEnv, functionReturns, Map.of(), Map.of());
    }

    /**
     * Builds a context from an {@link IrModule}: collects per-name
     * overload lists from function decls and trait impl methods,
     * mirrors the same data into {@code functionReturns} for the Phase A
     * fallback path, and collects struct definitions from preserved
     * type-alias statements. Intended for end-to-end consumers.
     */
    public static InferenceContext fromModule(IrModule module) {
        Map<String, List<IrStmt.FunctionDecl>> overloads = new LinkedHashMap<>();
        Map<String, IrSort> returns = new LinkedHashMap<>();
        Map<String, IrSort.Structural> structs = new LinkedHashMap<>();
        for (IrStmt stmt : module.statements()) {
            if (stmt instanceof IrStmt.FunctionDecl fd) {
                overloads.computeIfAbsent(fd.name(), k -> new ArrayList<>()).add(fd);
                returns.put(fd.name(), fd.returnSort());
            } else if (stmt instanceof IrStmt.TraitImpl ti) {
                for (IrStmt.FunctionDecl m : ti.methods()) {
                    overloads.computeIfAbsent(m.name(), k -> new ArrayList<>()).add(m);
                    returns.put(m.name(), m.returnSort());
                }
            } else if (stmt instanceof IrStmt.TypeAlias ta
                    && ta.sort() instanceof IrSort.Structural s) {
                structs.put(s.name(), s);
            }
        }
        return new InferenceContext(Map.of(), returns, structs, overloads);
    }

    /** Returns a new context with the given name bound to {@code sort}. */
    public InferenceContext withVar(String name, IrSort sort) {
        Map<String, IrSort> extended = new HashMap<>(typeEnv);
        extended.put(name, sort);
        return new InferenceContext(extended, functionReturns, structDefs, overloads);
    }

    /** Returns a new context with the struct-defs map replaced. */
    public InferenceContext withStructDefs(Map<String, IrSort.Structural> defs) {
        return new InferenceContext(typeEnv, functionReturns, defs, overloads);
    }

    /** Returns a new context with the overload map replaced. */
    public InferenceContext withOverloads(Map<String, List<IrStmt.FunctionDecl>> ovs) {
        return new InferenceContext(typeEnv, functionReturns, structDefs, ovs);
    }
}
