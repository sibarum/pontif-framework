package sibarum.pontif.receipts;

import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.types.Sort;

import java.util.List;
import java.util.Map;

/**
 * Renders a {@link ReceiptGraph} as a readable indented-text tree. This is
 * the review mechanism for the drafter — emitted as a build artifact so a
 * human can eyeball the graph a program produces (not a visualization;
 * plain text by design).
 *
 * <p>Format (mirrors the worked example in {@code docs/receipt-graph.md}):
 * <pre>
 * factorial(n_0: [Int: @ &gt;= 0]) : r_0: [Int: @ &gt;= 1]
 *   branch [n_0 &gt; 0]:
 *     call: factorial(n_0 - 1) -&gt; r_1: [Int: @ &gt;= 1]
 *     receipt: r_0 == (n_0 * r_1)
 * </pre>
 *
 * <p>Includes clean infix renderers for {@link SymExpr} and {@link Sort}
 * so the output reads like source rather than record {@code toString()}.
 */
public final class ReceiptGraphPrinter {

    private ReceiptGraphPrinter() {}

    /** Renders an entire graph; nodes in declaration order, blank-line separated. */
    public static String print(ReceiptGraph graph) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Node node : graph.roots()) {
            if (!first) sb.append('\n');
            sb.append(printNode(node));
            first = false;
        }
        return sb.toString();
    }

    /** Renders a single node (function root). */
    public static String printNode(Node node) {
        StringBuilder sb = new StringBuilder();
        sb.append(node.functionName()).append('(');
        for (int i = 0; i < node.params().size(); i++) {
            if (i > 0) sb.append(", ");
            Param p = node.params().get(i);
            sb.append(p.name()).append(": ").append(renderSort(p.sort()));
        }
        sb.append(") : ")
                .append(node.resultVar().name()).append(": ")
                .append(renderSort(node.resultVar().sort()))
                .append('\n');

        for (Branch branch : node.branches()) {
            sb.append("  branch ");
            sb.append(branch.guard()
                    .map(g -> "[" + renderSym(g) + "]")
                    .orElse("(unconditional)"));
            sb.append(":\n");
            for (CallRef call : branch.calls()) {
                sb.append("    call: ").append(call.targetFunctionName()).append('(');
                for (int i = 0; i < call.argBindings().size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(renderSym(call.argBindings().get(i)));
                }
                sb.append(") -> ")
                        .append(call.resultVar().name()).append(": ")
                        .append(renderSort(call.resultVar().sort()))
                        .append('\n');
            }
            for (InitialReceipt receipt : branch.initialReceipts()) {
                sb.append("    receipt: ").append(renderSym(receipt.claim())).append('\n');
            }
        }
        return sb.toString();
    }

    // --- Sort rendering ----------------------------------------------------

    /** Renders a {@link Sort} in surface-like notation. */
    public static String renderSort(Sort sort) {
        if (sort.isFunction()) {
            StringBuilder sb = new StringBuilder("(");
            List<Sort> params = sort.functionParams();
            for (int i = 0; i < params.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(renderSort(params.get(i)));
            }
            return sb.append(") -> ").append(renderSort(sort.functionReturnSort())).toString();
        }
        if (sort.isStructural()) {
            StringBuilder sb = new StringBuilder(sort.name()).append("{");
            boolean first = true;
            for (Map.Entry<String, Sort> e : sort.members().entrySet()) {
                if (!first) sb.append(", ");
                sb.append(e.getKey()).append(": ").append(renderSort(e.getValue()));
                first = false;
            }
            return sb.append("}").toString();
        }
        if (sort.isUnion()) {
            return joinBranches(sort.unionBranches(), " | ");
        }
        if (sort.isIntersection()) {
            return joinBranches(sort.intersectionBranches(), " & ");
        }
        if (sort.isRefined()) {
            return "[" + sort.name() + ": " + renderSym(sort.predicate()) + "]";
        }
        return sort.name();
    }

    private static String joinBranches(List<Sort> branches, String sep) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < branches.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(renderSort(branches.get(i)));
        }
        return sb.append("]").toString();
    }

    // --- SymExpr rendering (precedence-aware infix) ------------------------

    private static final int PREC_OR = 1;
    private static final int PREC_AND = 2;
    private static final int PREC_CMP = 3;
    private static final int PREC_ADD = 4;
    private static final int PREC_MUL = 5;
    private static final int PREC_POW = 6;
    private static final int PREC_ATOM = 7;

    /** Renders a {@link SymExpr} as infix text with minimal parentheses. */
    public static String renderSym(SymExpr expr) {
        return render(expr, 0);
    }

    private static String render(SymExpr expr, int parentPrec) {
        return switch (expr) {
            case SymExpr.Var v -> v.name();
            case SymExpr.Lit l -> Long.toString(l.value());
            case SymExpr.Frac f -> f.num() + "/" + f.denom();
            case SymExpr.Dec d -> d.value().toPlainString();
            case SymExpr.Chr c -> "'" + sibarum.pontif.core.types.CharValue.render(c.codePoint()) + "'";
            case SymExpr.Bool b -> Boolean.toString(b.value());
            case SymExpr.Self s -> "@";
            // Sub is encoded as Add(l, Mul(-1, r)) by the IR compiler — render
            // it back as subtraction when the shape matches.
            case SymExpr.Add(SymExpr l, SymExpr r) -> {
                if (r instanceof SymExpr.Mul(SymExpr.Lit(long neg), SymExpr inner) && neg == -1L) {
                    yield wrap(render(l, PREC_ADD) + " - " + render(inner, PREC_ADD + 1),
                            PREC_ADD, parentPrec);
                }
                yield wrap(render(l, PREC_ADD) + " + " + render(r, PREC_ADD), PREC_ADD, parentPrec);
            }
            case SymExpr.Mul(SymExpr l, SymExpr r) ->
                    wrap(render(l, PREC_MUL) + " * " + render(r, PREC_MUL), PREC_MUL, parentPrec);
            case SymExpr.Pow(SymExpr b, SymExpr e) ->
                    wrap(render(b, PREC_POW + 1) + "^" + render(e, PREC_POW + 1), PREC_POW, parentPrec);
            case SymExpr.Cmp(SymExpr l, SymExpr.CmpOp op, SymExpr r) ->
                    wrap(render(l, PREC_CMP + 1) + " " + cmpOp(op) + " " + render(r, PREC_CMP + 1),
                            PREC_CMP, parentPrec);
            case SymExpr.And(SymExpr l, SymExpr r) ->
                    wrap(render(l, PREC_AND) + " && " + render(r, PREC_AND), PREC_AND, parentPrec);
            case SymExpr.Or(SymExpr l, SymExpr r) ->
                    wrap(render(l, PREC_OR) + " || " + render(r, PREC_OR), PREC_OR, parentPrec);
            case SymExpr.Lam(String param, var type, SymExpr body) ->
                    wrap("(" + param + ") -> " + render(body, 0), PREC_ATOM, parentPrec);
            case SymExpr.App(SymExpr fn, SymExpr arg) ->
                    render(fn, PREC_ATOM) + "(" + render(arg, 0) + ")";
            case SymExpr.Case(SymExpr scrutinee, var branches) ->
                    "match " + render(scrutinee, 0) + " { … }";
            case SymExpr.Record(Map<String, SymExpr> members, String typeName) -> {
                StringBuilder sb = new StringBuilder(typeName != null ? typeName : "");
                sb.append("{");
                boolean first = true;
                for (Map.Entry<String, SymExpr> e : members.entrySet()) {
                    if (!first) sb.append(", ");
                    sb.append(e.getKey()).append("=").append(render(e.getValue(), 0));
                    first = false;
                }
                yield sb.append("}").toString();
            }
            case SymExpr.FieldAccess(SymExpr base, String name) ->
                    render(base, PREC_ATOM) + "." + name;
        };
    }

    /** Wraps {@code text} in parens if its precedence binds looser than the parent context. */
    private static String wrap(String text, int myPrec, int parentPrec) {
        return myPrec < parentPrec ? "(" + text + ")" : text;
    }

    private static String cmpOp(SymExpr.CmpOp op) {
        return switch (op) {
            case LT -> "<";
            case LE -> "<=";
            case GT -> ">";
            case GE -> ">=";
            case EQ -> "==";
            case NE -> "!=";
        };
    }
}
