package sibarum.pontif.ir;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Reflects an {@link IrModule} back into <em>Pontif alt-syntax source</em> — a
 * faithful unparse of its declarations, NOT the structural debug dump of
 * {@link IrPrinter}. Where {@code IrPrinter} tags every node with its kind for
 * inspection, this re-emits {@code struct …}, {@code trait …}, {@code function …},
 * {@code requires}/{@code exports} as they'd be written.
 *
 * <p>Its reason to exist: builtin modules built directly from IR
 * ({@code std.common}/{@code std.proof}/{@code std.conservation}) ship no
 * {@code .ptf}, so the editor's "go to definition" has no source to show. This
 * turns their IR back into readable Pontif. (Source-authored modules are shown
 * verbatim and never routed here.)
 *
 * <p>Distinct from {@link IrSourceReflector}, which re-emits source with declared
 * sorts replaced by <em>inferred narrowings</em> from a call-graph walk — a window
 * into compilation. This printer is a straight, declared-as-written unparse. Sorts
 * render as references (a struct member of type {@code Point} prints {@code Point},
 * not its expanded shape); the struct/trait declaration itself is what expands.
 */
public final class IrSourcePrinter {

    private static final String INDENT = "  ";

    private IrSourcePrinter() {}

    public static String print(IrModule module) {
        StringBuilder sb = new StringBuilder();
        if (module.name() != null) sb.append("module ").append(module.name()).append("\n\n");
        for (IrStmt stmt : module.statements()) {
            String s = stmt(stmt);
            if (!s.isEmpty()) sb.append(s).append("\n");
        }
        // Skip a trivial trailing `0` main (the builtins' placeholder); show a real one.
        IrExpr main = module.main();
        if (main != null && !(main instanceof IrExpr.Lit l && l.value() == 0L)) {
            sb.append("\n").append(expr(main)).append("\n");
        }
        return sb.toString();
    }

    // --- Statements ---------------------------------------------------------

    private static String stmt(IrStmt stmt) {
        return switch (stmt) {
            case IrStmt.Requires r -> "requires " + r.targetModule() + ".{"
                    + r.entries().stream()
                        .map(e -> e.remoteName().equals(e.localName())
                                ? e.remoteName()
                                : e.remoteName() + " -> " + e.localName())
                        .collect(Collectors.joining(", "))
                    + "}";
            case IrStmt.Exports e -> "exports " + (e.self() ? "@." : "")
                    + "{" + String.join(", ", e.names()) + "}";
            case IrStmt.TypeAlias ta -> typeDecl(ta);
            case IrStmt.FunctionDecl fd -> functionDecl(fd);
            case IrStmt.TraitImpl ti -> traitImpl(ti);
            case IrStmt.Coercion c -> "cast " + sortRef(c.targetSort()) + ":("
                    + c.paramName() + ":" + sortRef(c.sourceSort()) + ") -> " + expr(c.body());
            case IrStmt.Proof p -> "proof " + p.functionName() + " = " + expr(p.proofTree());
            case IrStmt.ReturnProof rp -> "assign proof " + rp.functionName()
                    + "(" + params(rp.params()) + "):" + sortRef(rp.grantedReturn());
            case IrStmt.ConductorDecl cd -> conductorDecl(cd);
            case IrStmt.NoOp n -> "# " + n.label();
        };
    }

    /** Re-emits a conductor declaration in surface form (docs/orchestration.md, §Authoring). */
    private static String conductorDecl(IrStmt.ConductorDecl cd) {
        java.util.List<String> members = new java.util.ArrayList<>();
        for (IrStmt.ConductorDecl.StateField f : cd.state()) {
            members.add(f.name() + ":" + sortRef(f.sort()) + " = " + expr(f.init()));
        }
        for (Map.Entry<String, IrSort.CallSig> h : cd.handlers().entrySet()) {
            members.add(h.getKey() + ":" + sortRef(h.getValue()));
        }
        return "conductor " + cd.name() + " {" + String.join(", ", members) + "}";
    }

    /** A type declaration: a struct, a trait, or a plain alias, by the alias's sort. */
    private static String typeDecl(IrStmt.TypeAlias ta) {
        return switch (ta.sort()) {
            case IrSort.Structural st -> "struct " + ta.name() + typeParamSlot(st.typeParams())
                    + "(" + st.members().entrySet().stream()
                        .map(m -> m.getKey() + ":" + sortRef(m.getValue()))
                        .collect(Collectors.joining(", "))
                    + ")";
            case IrSort.Trait t -> traitDecl(ta.name(), t);
            default -> "type " + ta.name() + " = " + sortRef(ta.sort());
        };
    }

    private static String traitDecl(String name, IrSort.Trait t) {
        StringBuilder sb = new StringBuilder("trait ").append(name).append(typeParamSlot(t.typeParams()));
        if (t.baseTrait() != null) sb.append(" : ").append(t.baseTrait());
        List<String> members = new java.util.ArrayList<>();
        for (Map.Entry<String, IrSort.CallSig> m : t.methods().entrySet()) {
            members.add(m.getKey() + "(" + sorts(m.getValue().paramSorts()) + "):"
                    + sortRef(m.getValue().returnSort()));
        }
        for (Map.Entry<String, IrSort> a : t.attributes().entrySet()) {
            members.add(a.getKey() + ":" + sortRef(a.getValue()));
        }
        if (members.isEmpty()) return sb.append("{}").toString();
        return sb.append(" {\n").append(INDENT)
                .append(String.join(",\n" + INDENT, members)).append("\n}").toString();
    }

    private static String functionDecl(IrStmt.FunctionDecl fd) {
        if (fd.topLevelLet()) {
            return "let " + fd.name() + " = " + expr(fd.body());
        }
        return "function " + fd.name() + typeParamSlot(fd.typeParams())
                + "(" + params(fd.params()) + "):" + sortRef(fd.returnSort())
                + " -> " + expr(fd.body());
    }

    private static String traitImpl(IrStmt.TraitImpl ti) {
        StringBuilder sb = new StringBuilder("assign trait ")
                .append(ti.typeName()).append(":").append(ti.traitName());
        if (ti.methods().isEmpty() && ti.attributeProducers().isEmpty()) {
            return sb.append("{}").toString();
        }
        sb.append(" {\n");
        for (IrStmt.FunctionDecl m : ti.methods()) {
            sb.append(INDENT).append(functionDecl(m)).append("\n");
        }
        for (IrStmt.FunctionDecl a : ti.attributeProducers()) {
            sb.append(INDENT).append(functionDecl(a)).append("\n");
        }
        return sb.append("}").toString();
    }

    private static String typeParamSlot(Map<String, IrSort> typeParams) {
        if (typeParams == null || typeParams.isEmpty()) return "";
        return "[" + typeParams.entrySet().stream()
                .map(e -> "type " + e.getKey() + (e.getValue() == null ? "" : ":" + sortRef(e.getValue())))
                .collect(Collectors.joining(", ")) + "]";
    }

    private static String params(List<IrParam> ps) {
        return ps.stream().map(p -> p.name() + ":" + sortRef(p.sort()))
                .collect(Collectors.joining(", "));
    }

    // --- Sorts (reference form — a member/return type, not an expanded decl) ---

    static String sortRef(IrSort s) {
        if (s == null) return "_";
        return switch (s) {
            case IrSort.Named n        -> n.name();
            case IrSort.Trait t        -> t.name();
            case IrSort.Structural st  -> st.name();               // reference by name
            case IrSort.Refined r      -> "[" + r.name() + ":" + expr(r.predicate()) + "]";
            case IrSort.Union u        -> "[" + u.branches().stream()
                    .map(IrSourcePrinter::sortRef).collect(Collectors.joining(" | ")) + "]";
            case IrSort.Intersection i -> "[" + i.branches().stream()
                    .map(IrSourcePrinter::sortRef).collect(Collectors.joining(" & ")) + "]";
            case IrSort.CallSig c      -> "[" + c.typeName() + "(" + sorts(c.paramSorts()) + "):"
                    + sortRef(c.returnSort()) + "]";
        };
    }

    private static String sorts(List<IrSort> ss) {
        return ss.stream().map(IrSourcePrinter::sortRef).collect(Collectors.joining(", "));
    }

    // --- Expressions (compact; rich enough for predicates + simple bodies) ---

    private static String expr(IrExpr e) {
        if (e == null) return "…";
        return switch (e) {
            case IrExpr.Lit l   -> Long.toString(l.value());
            case IrExpr.Dec d   -> d.value().toPlainString();
            case IrExpr.Chr c   -> "'" + sibarum.pontif.core.types.CharValue.render(c.codePoint()) + "'";
            case IrExpr.Str s   -> "\"" + sibarum.pontif.core.types.StringValue.render(s.value()) + "\"";
            case IrExpr.Bool b  -> Boolean.toString(b.value());
            case IrExpr.Var v   -> v.name();
            case IrExpr.SelfRef ignored -> "@";
            case IrExpr.BinOp op -> "(" + expr(op.left()) + " " + opSymbol(op.op())
                    + " " + expr(op.right()) + ")";
            case IrExpr.Call c  -> (isOperator(c.functionName()) && c.args().size() == 2)
                    ? "(" + expr(c.args().get(0)) + " " + c.functionName() + " " + expr(c.args().get(1)) + ")"
                    : c.functionName() + "(" + args(c.args()) + ")";
            case IrExpr.FieldAccess fa -> expr(fa.base()) + "." + fa.fieldName();
            case IrExpr.MethodCall mc -> expr(mc.receiver()) + "." + mc.methodName()
                    + "(" + args(mc.args()) + ")";
            case IrExpr.Record r -> r.typeName() != null && !r.typeName().startsWith("_")
                    ? r.typeName() + "(" + args(new java.util.ArrayList<>(r.members().values())) + ")"
                    : "{" + r.members().entrySet().stream()
                        .map(m -> m.getKey() + " = " + expr(m.getValue()))
                        .collect(Collectors.joining(", ")) + "}";
            case IrExpr.Cast cast -> "(" + sortRef(cast.targetSort()) + ":" + expr(cast.value()) + ")";
            case IrExpr.Emit em -> "emit " + expr(em.event()) + "  " + expr(em.body());
            case IrExpr.DispatchRef dr -> dr.functionName() + "[" + sorts(dr.keySorts()) + "]";
            default -> "…";  // LetIn / Match / Apply / Lambda / Iterate — elided in this compact form
        };
    }

    private static String args(List<IrExpr> args) {
        return args.stream().map(IrSourcePrinter::expr).collect(Collectors.joining(", "));
    }

    private static boolean isOperator(String name) {
        return switch (name) {
            case "+", "-", "*", "/", "%", "^", "<", "<=", ">", ">=", "==", "!=" -> true;
            default -> false;
        };
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
