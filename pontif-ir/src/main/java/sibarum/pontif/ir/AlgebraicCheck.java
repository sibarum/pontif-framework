package sibarum.pontif.ir;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Verifies the {@code assign proof f:Algebraic} claim: a function marked
 * <em>algebraic</em> must have a body built only from the algebraic fragment —
 * numeric literals, its own parameters, arithmetic ({@code + - * / ^}), local
 * {@code let}-bindings, field access on parameters, and <b>calls to other
 * algebraic functions</b> — and the algebraic call-graph must be <b>acyclic</b>
 * (no recursion, direct or mutual). Anything else (a {@code match}, a lambda, a
 * comparison, {@code %}, a call to a non-algebraic function, …) is a compile
 * error, so the claim is a proof the compiler holds to its word, not a hint.
 *
 * <p>This is deliberately a raw walk over the (alias- and method-resolved)
 * {@link IrExpr} body — no receipt graph, no drafter. By the time it runs, a
 * {@code MethodCall} has already been rewritten to {@code Call("Type.method", …)}
 * and arithmetic is a {@link IrExpr.BinOp}, never a {@link IrExpr.Call}.
 */
public final class AlgebraicCheck {

    private AlgebraicCheck() {}

    /**
     * The first problem across all algebraic-claimed functions, or empty if every
     * claim holds. {@code algebraic} is the set of function names claimed
     * algebraic; {@code functionsByName} resolves each to its (unique, non-overloaded
     * — enforced by the caller) declaration.
     */
    public static Optional<String> check(
            Set<String> algebraic, Map<String, IrStmt.FunctionDecl> functionsByName) {
        return check(algebraic, functionsByName, Set.of());
    }

    /**
     * As {@link #check(Set, Map)}, plus a set of built-in <b>algebraic primitives</b> — qualified
     * names (e.g. {@code pontif.math/sin}) an algebraic body may call even though they are not
     * themselves {@code assign proof}-claimed. A primitive is a leaf: it reflects to a dedicated
     * AST node rather than being inlined, so it has no algebraic body of its own to verify and
     * never participates in the recursion (acyclicity) check.
     */
    public static Optional<String> check(
            Set<String> algebraic, Map<String, IrStmt.FunctionDecl> functionsByName,
            Set<String> primitives) {
        for (String name : algebraic) {
            IrStmt.FunctionDecl fd = functionsByName.get(name);
            if (fd == null) {
                return Optional.of("Algebraic proof references unknown function '" + name + "'.");
            }
            Set<String> params = new HashSet<>();
            for (IrParam p : fd.params()) params.add(p.name());
            Optional<String> frag = checkFragment(fd.body(), name, params, algebraic, primitives);
            if (frag.isPresent()) return frag;
        }
        return acyclic(algebraic, functionsByName);
    }

    /** The algebraic operators — everything else on a {@link IrExpr.BinOp} is rejected. */
    private static boolean isAlgebraicOp(IrExpr.Op op) {
        return switch (op) {
            case ADD, SUB, MUL, DIV, POW -> true;
            default -> false;  // MOD, comparisons, AND/OR, APPROX
        };
    }

    private static Optional<String> checkFragment(
            IrExpr expr, String fn, Set<String> bound, Set<String> algebraic, Set<String> primitives) {
        switch (expr) {
            case IrExpr.Lit l -> { return Optional.empty(); }
            case IrExpr.Dec d -> { return Optional.empty(); }
            case IrExpr.Var v -> {
                if (!bound.contains(v.name())) {
                    return reject(fn, "references '" + v.name()
                            + "', which is neither a parameter nor a local let");
                }
                return Optional.empty();
            }
            case IrExpr.BinOp op -> {
                if (!isAlgebraicOp(op.op())) {
                    return reject(fn, "uses the non-algebraic operator '" + op.op() + "'");
                }
                Optional<String> l = checkFragment(op.left(), fn, bound, algebraic, primitives);
                if (l.isPresent()) return l;
                return checkFragment(op.right(), fn, bound, algebraic, primitives);
            }
            case IrExpr.LetIn let -> {
                Optional<String> v = checkFragment(let.value(), fn, bound, algebraic, primitives);
                if (v.isPresent()) return v;
                Set<String> extended = new HashSet<>(bound);
                extended.add(let.name());
                return checkFragment(let.body(), fn, extended, algebraic, primitives);
            }
            case IrExpr.Call c -> {
                if (!algebraic.contains(c.functionName()) && !primitives.contains(c.functionName())) {
                    return reject(fn, "calls '" + c.functionName()
                            + "', which is not marked algebraic (nested calls are allowed only to "
                            + "other algebraic functions or built-in algebraic primitives)");
                }
                for (IrExpr arg : c.args()) {
                    Optional<String> a = checkFragment(arg, fn, bound, algebraic, primitives);
                    if (a.isPresent()) return a;
                }
                return Optional.empty();
            }
            case IrExpr.FieldAccess fa -> {
                // Field access is algebraic only over a parameter/local (e.g. a struct
                // param's `v.x`); the base carries the burden of proof.
                return checkFragment(fa.base(), fn, bound, algebraic, primitives);
            }
            default -> {
                return reject(fn, "contains a non-algebraic construct ("
                        + expr.getClass().getSimpleName() + ")");
            }
        }
    }

    private static Optional<String> reject(String fn, String what) {
        return Optional.of("Function '" + fn + "' is claimed algebraic but its body " + what
                + ". An algebraic function is built only from arithmetic (+ - * / ^), its "
                + "parameters, local lets, field access, and calls to other algebraic functions.");
    }

    /** DFS over the algebraic call-graph; a back-edge is recursion → rejected. */
    private static Optional<String> acyclic(
            Set<String> algebraic, Map<String, IrStmt.FunctionDecl> functionsByName) {
        Map<String, Set<String>> edges = new java.util.LinkedHashMap<>();
        for (String name : algebraic) {
            IrStmt.FunctionDecl fd = functionsByName.get(name);
            LinkedHashSet<String> callees = new LinkedHashSet<>();
            collectCallees(fd.body(), algebraic, callees);
            edges.put(name, callees);
        }
        Set<String> done = new HashSet<>();
        Set<String> onStack = new HashSet<>();
        List<String> path = new ArrayList<>();
        for (String name : algebraic) {
            Optional<String> cycle = dfs(name, edges, done, onStack, path);
            if (cycle.isPresent()) return cycle;
        }
        return Optional.empty();
    }

    private static Optional<String> dfs(
            String node, Map<String, Set<String>> edges,
            Set<String> done, Set<String> onStack, List<String> path) {
        if (done.contains(node)) return Optional.empty();
        if (onStack.contains(node)) {
            int from = path.indexOf(node);
            List<String> cycle = new ArrayList<>(path.subList(from, path.size()));
            cycle.add(node);
            return Optional.of("Algebraic function '" + node + "' is recursive — recursion is "
                    + "not allowed in an algebraic function. Cycle: " + String.join(" -> ", cycle));
        }
        onStack.add(node);
        path.add(node);
        for (String callee : edges.getOrDefault(node, Set.of())) {
            Optional<String> cycle = dfs(callee, edges, done, onStack, path);
            if (cycle.isPresent()) return cycle;
        }
        path.remove(path.size() - 1);
        onStack.remove(node);
        done.add(node);
        return Optional.empty();
    }

    /** Collects the names of all calls in {@code expr} that target an algebraic function. */
    private static void collectCallees(IrExpr expr, Set<String> algebraic, Set<String> out) {
        switch (expr) {
            case IrExpr.Call c -> {
                if (algebraic.contains(c.functionName())) out.add(c.functionName());
                for (IrExpr arg : c.args()) collectCallees(arg, algebraic, out);
            }
            case IrExpr.BinOp op -> {
                collectCallees(op.left(), algebraic, out);
                collectCallees(op.right(), algebraic, out);
            }
            case IrExpr.LetIn let -> {
                collectCallees(let.value(), algebraic, out);
                collectCallees(let.body(), algebraic, out);
            }
            case IrExpr.FieldAccess fa -> collectCallees(fa.base(), algebraic, out);
            default -> {}  // non-algebraic nodes never survive checkFragment; ignore here
        }
    }
}
