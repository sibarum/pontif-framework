package sibarum.pontif.ir;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reflects a compiled {@link IrModule} back as <em>source-shaped</em> text — the
 * program as the compiler sees it — with declared sorts replaced by the
 * <em>inferred narrowings</em> the one engine ({@link NarrowingInference})
 * derives. It is deliberately a re-emitter, not a source-splicer: it can show
 * things that aren't in the original text (a tighter return, a value-pin on an
 * undeclared let; later: synthesized bodies, inlined calls, lost information).
 * The output is best-effort and not guaranteed to round-trip — it is a window
 * into compilation, not a decompiler.
 *
 * <h2>The walk: one node per reachable function, no duplicate edges</h2>
 * From an entrypoint (a function/let name, or the module's {@code main} when
 * none is given) it walks the static call graph, emitting each reachable function
 * <em>once</em>. A call to an already-emitted function is a back-edge — it appears
 * as ordinary text in the body, never re-expanded — exactly the no-duplicate-edges
 * discipline the receipt graph and conservation ledger already use. That is what
 * makes recursion terminate (the recursive call is a back-edge, the stack is never
 * unfolded) and what keeps specialization <em>shallow</em>:
 *
 * <h2>Shallow specialization</h2>
 * Each function's parameters are seeded with the argument narrowings of the edge
 * that <em>first</em> reached it (first-edge-wins), and its return + body are
 * inferred under that seeding. Deeper / recursive re-specialization is a fixpoint
 * (abstract interpretation with widening) — deliberately NOT done here; a recursive
 * self-call simply bottoms out at the node's already-shown narrowing.
 */
public final class IrSourceReflector {

    private IrSourceReflector() {}

    private static final String INDENT = "  ";
    private static final int MAX_NODES = 500;  // runaway guard for pathological graphs

    private static final Set<String> OPERATOR_NAMES = Set.of(
            "+", "-", "*", "/", "%", "^", "<", "<=", ">", ">=", "==", "!=");

    /** A function to emit, with the param narrowings of the edge that first reached it. */
    private record Node(IrStmt.FunctionDecl fd, Map<String, IrSort> paramNarrowing) {}

    /**
     * Reflects {@code module}. {@code entryName} selects the root: a function or
     * top-level-let name, or {@code null} to root at {@code main}. Only the
     * functions reachable from the root are emitted.
     */
    public static String reflect(IrModule module, String entryName) {
        InferenceContext baseCtx = InferenceContext.fromModule(module);
        Map<String, IrStmt.FunctionDecl> byName = functionsByName(module);

        StringBuilder sb = new StringBuilder();
        sb.append("# Reflected source — declared sorts replaced by inferred narrowings\n");
        sb.append("# Best-effort re-emission; not a faithful round-trip.\n");
        sb.append("# entrypoint: ").append(entryName != null ? entryName : "main").append("\n\n");

        // Structs first, for context (they carry no narrowing — declared shape only).
        for (IrStmt stmt : module.statements()) {
            if (stmt instanceof IrStmt.TypeAlias ta && ta.sort() instanceof IrSort.Structural st
                    && !st.name().startsWith("_")) {
                sb.append(renderStruct(st)).append("\n");
            }
        }

        Set<String> discovered = new LinkedHashSet<>();
        Deque<Node> work = new ArrayDeque<>();

        if (entryName != null && byName.containsKey(entryName)) {
            IrStmt.FunctionDecl root = byName.get(entryName);
            discovered.add(entryName);
            work.add(new Node(root, declaredNarrowing(root)));
        } else {
            // main is the root: render it, and its calls seed the reachable functions.
            IrExpr main = module.main();
            enqueueCallees(main, baseCtx, byName, discovered, work);
            sb.append("# entry\n").append(renderExpr(main, 0, baseCtx)).append("\n\n");
        }

        int count = 0;
        while (!work.isEmpty() && count++ < MAX_NODES) {
            Node n = work.poll();
            InferenceContext seeded = seed(baseCtx, n.fd(), n.paramNarrowing());
            sb.append(renderFunction(n.fd(), n.paramNarrowing(), seeded)).append("\n");
            // Discover callees under THIS node's seeded context (shallow: their param
            // narrowings come from the call-site args evaluated here).
            enqueueCallees(n.fd().body(), seeded, byName, discovered, work);
        }
        if (count >= MAX_NODES) sb.append("# … (reachable-function cap reached)\n");
        return sb.toString();
    }

    // --- The walk -----------------------------------------------------------

    /** Seeds a context with a function's params bound to their (specialized or declared) narrowings. */
    private static InferenceContext seed(
            InferenceContext base, IrStmt.FunctionDecl fd, Map<String, IrSort> narrowing) {
        InferenceContext ctx = base;
        for (IrParam p : fd.params()) {
            ctx = ctx.withVar(p.name(), narrowing.getOrDefault(p.name(), p.sort()));
        }
        return ctx;
    }

    /** Param narrowings = declared sorts (the root has no caller to specialize it). */
    private static Map<String, IrSort> declaredNarrowing(IrStmt.FunctionDecl fd) {
        Map<String, IrSort> m = new LinkedHashMap<>();
        for (IrParam p : fd.params()) m.put(p.name(), p.sort());
        return m;
    }

    /**
     * Walks {@code expr} for calls to known functions; the FIRST time each is
     * reached it's enqueued with its params seeded from the call-site arg
     * narrowings (inferred under {@code ctx}). Already-discovered callees are
     * back-edges — skipped (no-duplicate-edges).
     */
    private static void enqueueCallees(
            IrExpr expr, InferenceContext ctx, Map<String, IrStmt.FunctionDecl> byName,
            Set<String> discovered, Deque<Node> work) {
        if (expr == null) return;
        if (expr instanceof IrExpr.Call c && byName.containsKey(c.functionName())
                && !discovered.contains(c.functionName())) {
            discovered.add(c.functionName());
            IrStmt.FunctionDecl callee = byName.get(c.functionName());
            Map<String, IrSort> narrowing = new LinkedHashMap<>();
            List<IrParam> ps = callee.params();
            for (int i = 0; i < ps.size() && i < c.args().size(); i++) {
                IrSort argN = NarrowingInference.inferFloor(c.args().get(i), ctx);
                narrowing.put(ps.get(i).name(), argN != null ? argN : ps.get(i).sort());
            }
            work.add(new Node(callee, narrowing));
        }
        for (IrExpr child : children(expr)) {
            enqueueCallees(child, ctx, byName, discovered, work);
        }
    }

    /** Immediate sub-expressions, for the call-discovery walk. */
    private static List<IrExpr> children(IrExpr e) {
        return switch (e) {
            case IrExpr.BinOp op -> List.of(op.left(), op.right());
            case IrExpr.Call c -> c.args();
            case IrExpr.Apply a -> concat(a.fn(), a.args());
            case IrExpr.LetIn l -> List.of(l.value(), l.body());
            case IrExpr.Match m -> {
                List<IrExpr> xs = new ArrayList<>();
                xs.add(m.scrutinee());
                for (IrExpr.MatchBranch b : m.branches()) xs.add(b.result());
                yield xs;
            }
            case IrExpr.Record r -> new ArrayList<>(r.members().values());
            case IrExpr.FieldAccess fa -> List.of(fa.base());
            case IrExpr.MethodCall mc -> concat(mc.receiver(), mc.args());
            case IrExpr.Cast cast -> List.of(cast.value());
            case IrExpr.Iterate it -> {
                List<IrExpr> xs = new ArrayList<>();
                xs.add(it.source());
                for (IrExpr.Arm arm : it.arms()) {
                    for (IrExpr.Write w : arm.writes()) {
                        if (w.key() != null) xs.add(w.key());
                        xs.add(w.value());
                    }
                }
                yield xs;
            }
            case IrExpr.Lambda lam -> List.of(lam.body());
            default -> List.of();  // leaves
        };
    }

    private static List<IrExpr> concat(IrExpr head, List<IrExpr> tail) {
        List<IrExpr> xs = new ArrayList<>(tail.size() + 1);
        xs.add(head);
        xs.addAll(tail);
        return xs;
    }

    /** Functions reachable as call targets: free functions, top-level lets, trait methods (first decl per name). */
    private static Map<String, IrStmt.FunctionDecl> functionsByName(IrModule module) {
        Map<String, IrStmt.FunctionDecl> m = new LinkedHashMap<>();
        for (IrStmt stmt : module.statements()) {
            if (stmt instanceof IrStmt.FunctionDecl fd) {
                m.putIfAbsent(fd.name(), fd);
            } else if (stmt instanceof IrStmt.TraitImpl ti) {
                for (IrStmt.FunctionDecl mth : ti.methods()) m.putIfAbsent(mth.name(), mth);
                for (IrStmt.FunctionDecl a : ti.attributeProducers()) m.putIfAbsent(a.name(), a);
            }
        }
        return m;
    }

    // --- Rendering ----------------------------------------------------------

    private static String renderStruct(IrSort.Structural st) {
        StringBuilder sb = new StringBuilder("struct ").append(st.name()).append("(");
        boolean first = true;
        for (Map.Entry<String, IrSort> e : st.members().entrySet()) {
            if (!first) sb.append(", ");
            sb.append(e.getKey()).append(":").append(IrPrinter.sort(e.getValue()));
            first = false;
        }
        return sb.append(")\n").toString();
    }

    private static String renderFunction(
            IrStmt.FunctionDecl fd, Map<String, IrSort> paramNarrowing, InferenceContext seeded) {
        IrSort inferredReturn = NarrowingInference.closeOver(
                NarrowingInference.infer(fd.body(), seeded), paramNames(fd), seeded);
        IrSort declaredReturn = fd.returnSort();
        IrSort shownReturn = inferredReturn != null ? inferredReturn : declaredReturn;
        String returnNote = (inferredReturn != null && !inferredReturn.equals(declaredReturn))
                ? "    # return was: " + IrPrinter.sort(declaredReturn) : "";

        // A top-level let lowered to a 0-arg function: reflect it as `let name:S = value`.
        // Always annotate — the source typically wrote none, so the inferred sort IS
        // the thing to surface.
        if (fd.topLevelLet()) {
            return "let " + fd.name() + ":" + IrPrinter.sort(shownReturn)
                    + " = " + renderExpr(fd.body(), 0, seeded) + "\n";
        }

        StringBuilder sig = new StringBuilder("function ").append(fd.name()).append("(");
        List<IrParam> ps = fd.params();
        for (int i = 0; i < ps.size(); i++) {
            if (i > 0) sig.append(", ");
            IrSort shown = paramNarrowing.getOrDefault(ps.get(i).name(), ps.get(i).sort());
            sig.append(ps.get(i).name()).append(":").append(IrPrinter.sort(shown));
        }
        sig.append("):").append(IrPrinter.sort(shownReturn)).append(returnNote).append("\n");
        sig.append(INDENT).append(renderExpr(fd.body(), 1, seeded)).append("\n");
        return sig.toString();
    }

    private static Set<String> paramNames(IrStmt.FunctionDecl fd) {
        Set<String> names = new LinkedHashSet<>();
        for (IrParam p : fd.params()) names.add(p.name());
        return names;
    }

    /**
     * Best-effort alt-syntax for an expression. {@code indent} is the current
     * nesting depth (multi-line forms — match, let — indent their parts by it).
     */
    private static String renderExpr(IrExpr e, int indent, InferenceContext ctx) {
        return switch (e) {
            case IrExpr.Lit l -> Long.toString(l.value());
            case IrExpr.Dec d -> d.value().toPlainString();
            case IrExpr.Chr c -> "'" + sibarum.pontif.core.types.CharValue.render(c.codePoint()) + "'";
            case IrExpr.Str s -> "\"" + sibarum.pontif.core.types.StringValue.render(s.value()) + "\"";
            case IrExpr.Bool b -> Boolean.toString(b.value());
            case IrExpr.Var v -> v.name();
            case IrExpr.SelfRef ignored -> "this";
            case IrExpr.DispatchRef dr -> "$" + dr.functionName() + "[" + renderSorts(dr.keySorts()) + "]";
            case IrExpr.BinOp op ->
                    "(" + renderExpr(op.left(), indent, ctx) + " " + opSymbol(op.op())
                            + " " + renderExpr(op.right(), indent, ctx) + ")";
            case IrExpr.Call c -> renderCall(c, indent, ctx);
            case IrExpr.FieldAccess fa -> renderExpr(fa.base(), indent, ctx) + "." + fa.fieldName();
            case IrExpr.MethodCall mc -> renderExpr(mc.receiver(), indent, ctx) + "." + mc.methodName()
                    + "(" + renderArgs(mc.args(), indent, ctx) + ")";
            case IrExpr.Record r -> renderRecord(r, indent, ctx);
            case IrExpr.Cast cast -> "(" + IrPrinter.sort(cast.targetSort()) + ":"
                    + renderExpr(cast.value(), indent, ctx) + ")";
            case IrExpr.LetIn l -> renderLet(l, indent, ctx);
            case IrExpr.Match m -> renderMatch(m, indent, ctx);
            case IrExpr.Apply a -> renderExpr(a.fn(), indent, ctx)
                    + "(" + renderArgs(a.args(), indent, ctx) + ")";
            case IrExpr.Lambda lam -> "([" + lam.params().stream()
                    .map(p -> p.name() + ":" + IrPrinter.sort(p.sort()))
                    .reduce((x, y) -> x + ", " + y).orElse("") + "] -> "
                    + renderExpr(lam.body(), indent, ctx) + ")";
            case IrExpr.Iterate ignored -> "iterate(…)";  // best-effort; iteration desugar later
        };
    }

    private static String renderCall(IrExpr.Call c, int indent, InferenceContext ctx) {
        String name = c.functionName();
        // An operator surfaced as a dispatch call (`+(a, b)`) reads as infix.
        if (OPERATOR_NAMES.contains(name) && c.args().size() == 2) {
            return "(" + renderExpr(c.args().get(0), indent, ctx) + " " + name + " "
                    + renderExpr(c.args().get(1), indent, ctx) + ")";
        }
        return name + "(" + renderArgs(c.args(), indent, ctx) + ")";
    }

    private static String renderArgs(List<IrExpr> args, int indent, InferenceContext ctx) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(renderExpr(args.get(i), indent, ctx));
        }
        return sb.toString();
    }

    private static String renderRecord(IrExpr.Record r, int indent, InferenceContext ctx) {
        String inner = renderArgs(new ArrayList<>(r.members().values()), indent, ctx);
        // A nominal struct reads as a constructor call; an anonymous record as {k = v}.
        if (r.typeName() != null && !r.typeName().startsWith("_")) {
            return r.typeName() + "(" + inner + ")";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, IrExpr> e : r.members().entrySet()) {
            if (!first) sb.append(", ");
            sb.append(e.getKey()).append(" = ").append(renderExpr(e.getValue(), indent, ctx));
            first = false;
        }
        return sb.append("}").toString();
    }

    private static String renderLet(IrExpr.LetIn l, int indent, InferenceContext ctx) {
        // Show the binding's inferred narrowing — the open value-pin, in scope here.
        IrSort valueN = NarrowingInference.infer(l.value(), ctx);
        String ann = valueN != null ? ":" + IrPrinter.sort(valueN) : "";
        InferenceContext bodyCtx = valueN != null ? ctx.withVar(l.name(), valueN)
                : (l.declaredSort() != null ? ctx.withVar(l.name(), l.declaredSort()) : ctx);
        String pad = INDENT.repeat(indent);
        return "let " + l.name() + ann + " = " + renderExpr(l.value(), indent, ctx) + "\n"
                + pad + renderExpr(l.body(), indent, bodyCtx);
    }

    private static String renderMatch(IrExpr.Match m, int indent, InferenceContext ctx) {
        String pad = INDENT.repeat(indent);
        String armPad = INDENT.repeat(indent + 1);
        StringBuilder sb = new StringBuilder("match ")
                .append(renderExpr(m.scrutinee(), indent, ctx)).append(" {\n");
        for (IrExpr.MatchBranch b : m.branches()) {
            // Inside the arm the scrutinee is narrowed to the pattern (when it's a Var).
            InferenceContext armCtx = m.scrutinee() instanceof IrExpr.Var v
                    ? ctx.withVar(v.name(), b.pattern()) : ctx;
            sb.append(armPad).append(IrPrinter.sort(b.pattern())).append(" -> ")
                    .append(renderExpr(b.result(), indent + 1, armCtx)).append("\n");
        }
        return sb.append(pad).append("}").toString();
    }

    private static String renderSorts(List<IrSort> sorts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sorts.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(IrPrinter.sort(sorts.get(i)));
        }
        return sb.toString();
    }

    private static String opSymbol(IrExpr.Op op) {
        return switch (op) {
            case ADD -> "+"; case SUB -> "-"; case MUL -> "*"; case DIV -> "/";
            case MOD -> "%"; case POW -> "^";
            case LT -> "<"; case LE -> "<="; case GT -> ">"; case GE -> ">=";
            case EQ -> "=="; case NE -> "!="; case APPROX -> "~=";
            case AND -> "&"; case OR -> "|";
        };
    }
}
