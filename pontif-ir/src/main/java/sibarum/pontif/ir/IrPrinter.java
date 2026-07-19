package sibarum.pontif.ir;

import sibarum.pontif.core.Origin;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Readable, indented dump of the parsed IR — the tree the parser produced,
 * before Truffle lowering. Each expression node is one line tagged with its
 * kind and salient data; children indent two spaces under it. Sorts render
 * inline (they annotate the tree, they aren't its focus), with refinement
 * predicates shown in a compact infix form. Used by the Playground's IR/AST
 * inspector tab; purely structural, never throws.
 */
public final class IrPrinter {

    private static final String INDENT = "  ";

    private IrPrinter() {}

    public static String print(IrModule module) {
        StringBuilder sb = new StringBuilder();
        line(sb, 0, "module " + (module.name() == null ? "<anonymous>" : module.name()));
        for (IrStmt stmt : module.statements()) {
            printStmt(sb, stmt, 1);
        }
        line(sb, 1, "main");
        printExpr(sb, module.main(), 2);
        return sb.toString();
    }

    // --- Statements ---

    private static void printStmt(StringBuilder sb, IrStmt stmt, int d) {
        switch (stmt) {
            case IrStmt.FunctionDecl fd -> {
                String kind = fd.topLevelLet() ? "let" : "function";
                line(sb, d, kind + " " + fd.name() + "(" + params(fd.params()) + ") : "
                        + sort(fd.returnSort()) + at(fd.origin()));
                printExpr(sb, fd.body(), d + 1);
            }
            case IrStmt.TypeAlias ta ->
                    line(sb, d, "type " + ta.name() + " = " + sort(ta.sort()) + at(ta.origin()));
            case IrStmt.TraitImpl ti -> {
                line(sb, d, "assign trait " + ti.typeName() + " : " + ti.traitName() + at(ti.origin()));
                for (IrStmt.FunctionDecl m : ti.methods()) printStmt(sb, m, d + 1);
                for (IrStmt.FunctionDecl p : ti.attributeProducers()) printStmt(sb, p, d + 1);
            }
            case IrStmt.Coercion c -> {
                line(sb, d, "cast " + sort(c.targetSort()) + ":(" + c.paramName() + " : "
                        + sort(c.sourceSort()) + ")" + at(c.origin()));
                printExpr(sb, c.body(), d + 1);
            }
            case IrStmt.Proof p -> line(sb, d, "proof " + p.functionName() + at(p.origin()));
            case IrStmt.ReturnProof rp ->
                    line(sb, d, "return-proof " + rp.functionName() + "(" + params(rp.params())
                            + ") : " + sort(rp.grantedReturn()) + at(rp.origin()));
            case IrStmt.Requires r -> {
                line(sb, d, "requires " + r.targetModule() + at(r.origin()));
                for (IrStmt.RequireEntry e : r.entries()) {
                    line(sb, d + 1, e.remoteName()
                            + (e.remoteName().equals(e.localName()) ? "" : " as " + e.localName()));
                }
            }
            case IrStmt.Exports e ->
                    line(sb, d, "exports " + (e.self() ? "@ " : "") + String.join(", ", e.names()) + at(e.origin()));
            case IrStmt.NoOp n -> line(sb, d, "noop " + n.label() + at(n.origin()));
        }
    }

    // --- Expressions (the tree) ---

    private static void printExpr(StringBuilder sb, IrExpr e, int d) {
        switch (e) {
            case IrExpr.Lit l  -> line(sb, d, "Lit " + l.value() + at(l.origin()));
            case IrExpr.Dec dc -> line(sb, d, "Dec " + dc.value().toPlainString() + at(dc.origin()));
            case IrExpr.Chr c  -> line(sb, d, "Chr '"
                    + sibarum.pontif.core.types.CharValue.render(c.codePoint()) + "'" + at(c.origin()));
            case IrExpr.Str s  -> line(sb, d, "Str \""
                    + sibarum.pontif.core.types.StringValue.render(s.value()) + "\"" + at(s.origin()));
            case IrExpr.Bool b -> line(sb, d, "Bool " + b.value() + at(b.origin()));
            case IrExpr.Var v  -> line(sb, d, "Var " + v.name() + at(v.origin()));
            case IrExpr.SelfRef s -> line(sb, d, "@" + at(s.origin()));
            case IrExpr.BinOp op -> {
                line(sb, d, "BinOp " + opSymbol(op.op()) + at(op.origin()));
                printExpr(sb, op.left(), d + 1);
                printExpr(sb, op.right(), d + 1);
            }
            case IrExpr.LetIn l -> {
                String decl  = l.declaredSort() != null ? " : " + sort(l.declaredSort()) : "";
                String claim = l.claim() != null ? " claim=" + sort(l.claim()) : "";
                line(sb, d, "let " + l.name() + decl + claim + at(l.origin()));
                line(sb, d + 1, "value");
                printExpr(sb, l.value(), d + 2);
                line(sb, d + 1, "in");
                printExpr(sb, l.body(), d + 2);
            }
            case IrExpr.Call c -> {
                line(sb, d, "Call " + c.functionName() + at(c.origin()));
                for (IrExpr a : c.args()) printExpr(sb, a, d + 1);
            }
            case IrExpr.DispatchRef dr ->
                    line(sb, d, "DispatchRef " + dr.functionName() + "[" + sorts(dr.keySorts()) + "]" + at(dr.origin()));
            case IrExpr.Lambda lam -> {
                line(sb, d, "Lambda (" + params(lam.params()) + ") : " + sort(lam.returnSort()) + at(lam.origin()));
                printExpr(sb, lam.body(), d + 1);
            }
            case IrExpr.Apply ap -> {
                line(sb, d, "Apply" + at(ap.origin()));
                line(sb, d + 1, "fn");
                printExpr(sb, ap.fn(), d + 2);
                for (IrExpr a : ap.args()) printExpr(sb, a, d + 1);
            }
            case IrExpr.Match m -> {
                line(sb, d, "Match" + at(m.origin()));
                line(sb, d + 1, "scrutinee");
                printExpr(sb, m.scrutinee(), d + 2);
                for (IrExpr.MatchBranch br : m.branches()) {
                    line(sb, d + 1, "case " + sort(br.pattern()) + " ->");
                    printExpr(sb, br.result(), d + 2);
                }
            }
            case IrExpr.Record r -> {
                line(sb, d, "Record " + (r.typeName() == null ? "(anonymous)" : r.typeName()) + at(r.origin()));
                for (Map.Entry<String, IrExpr> me : r.members().entrySet()) {
                    line(sb, d + 1, me.getKey() + ":");
                    printExpr(sb, me.getValue(), d + 2);
                }
            }
            case IrExpr.FieldAccess fa -> {
                line(sb, d, "FieldAccess ." + fa.fieldName() + at(fa.origin()));
                printExpr(sb, fa.base(), d + 1);
            }
            case IrExpr.Cast cast -> {
                line(sb, d, "Cast (" + sort(cast.targetSort()) + ":…)" + at(cast.origin()));
                printExpr(sb, cast.value(), d + 1);
            }
            case IrExpr.Emit em -> {
                line(sb, d, "Emit" + at(em.origin()));
                printExpr(sb, em.event(), d + 1);
                printExpr(sb, em.body(), d + 1);
            }
            case IrExpr.MethodCall mc -> {
                line(sb, d, "MethodCall ." + mc.methodName() + " (unresolved)" + at(mc.origin()));
                printExpr(sb, mc.receiver(), d + 1);
                for (IrExpr a : mc.args()) printExpr(sb, a, d + 1);
            }
            case IrExpr.Iterate it -> {
                line(sb, d, "Iterate as " + it.element() + at(it.origin()));
                line(sb, d + 1, "source");
                printExpr(sb, it.source(), d + 2);
                for (IrExpr.OutputSpec os : it.outputs()) {
                    line(sb, d + 1, "output " + os.name() + " : " + os.kind());
                    if (os.init() != null) printExpr(sb, os.init(), d + 2);
                }
                for (IrExpr.Arm arm : it.arms()) {
                    line(sb, d + 1, "arm " + sort(arm.pattern()));
                    for (IrExpr.Write w : arm.writes()) {
                        line(sb, d + 2, "write -> " + w.output());
                        printExpr(sb, w.value(), d + 3);
                    }
                }
            }
        }
    }

    // --- Inline renderings (sorts + refinement predicates) ---

    static String sort(IrSort s) {
        if (s == null) return "_";
        return switch (s) {
            case IrSort.Named n        -> n.name();
            case IrSort.Refined r      -> "[" + r.name() + ":" + inline(r.predicate()) + "]";
            case IrSort.Structural st  -> st.name()
                    + (st.members().isEmpty() ? "" : "{" + members(st.members()) + "}");
            case IrSort.CallSig c      -> c.typeName().equals(IrSort.CallSig.METHOD)
                    ? "(" + sorts(c.paramSorts()) + ") -> " + sort(c.returnSort())
                    : c.typeName() + "[" + sorts(c.paramSorts()) + "] -> " + sort(c.returnSort());
            case IrSort.Trait t        -> t.name();
            case IrSort.Union u        -> "[" + u.branches().stream().map(IrPrinter::sort)
                    .collect(Collectors.joining(" | ")) + "]";
            case IrSort.Intersection i -> "[" + i.branches().stream().map(IrPrinter::sort)
                    .collect(Collectors.joining(" & ")) + "]";
        };
    }

    /** Compact infix form for the small expressions that appear in refinement predicates. */
    private static String inline(IrExpr e) {
        return switch (e) {
            case IrExpr.Lit l  -> Long.toString(l.value());
            case IrExpr.Dec d  -> d.value().toPlainString();
            case IrExpr.Chr c  -> "'" + sibarum.pontif.core.types.CharValue.render(c.codePoint()) + "'";
            case IrExpr.Str s  -> "\"" + sibarum.pontif.core.types.StringValue.render(s.value()) + "\"";
            case IrExpr.Bool b -> Boolean.toString(b.value());
            case IrExpr.Var v  -> v.name();
            case IrExpr.SelfRef s -> "@";
            case IrExpr.BinOp op -> "(" + inline(op.left()) + " " + opSymbol(op.op()) + " " + inline(op.right()) + ")";
            case IrExpr.Call c -> c.functionName() + "("
                    + c.args().stream().map(IrPrinter::inline).collect(Collectors.joining(", ")) + ")";
            case IrExpr.FieldAccess fa -> inline(fa.base()) + "." + fa.fieldName();
            case IrExpr.DispatchRef dr -> dr.functionName() + "[" + sorts(dr.keySorts()) + "]";
            default -> "…";   // Lambda/Apply/Match/Record/LetIn — rare in predicates
        };
    }

    private static String params(List<IrParam> ps) {
        return ps.stream().map(p -> p.name() + ": " + sort(p.sort())).collect(Collectors.joining(", "));
    }

    private static String sorts(List<IrSort> ss) {
        return ss.stream().map(IrPrinter::sort).collect(Collectors.joining(", "));
    }

    private static String members(Map<String, IrSort> ms) {
        return ms.entrySet().stream().map(e -> e.getKey() + ": " + sort(e.getValue()))
                .collect(Collectors.joining(", "));
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

    private static String at(Origin o) {
        return (o != null && o.isPresent()) ? " @" + o.span().start() : "";
    }

    private static void line(StringBuilder sb, int depth, String text) {
        sb.append(INDENT.repeat(depth)).append(text).append('\n');
    }
}
