package sibarum.pontif.ir;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Promotes {@code Int} literals to {@code Decimal} where the <em>declared</em>
 * sort says Decimal: struct-literal members ({@code Ternion(0, 1.2, 0)} with
 * {@code Decimal} fields) and let bindings with a declared Decimal sort.
 *
 * <p>Sound and information-conserving: {@code Int → Decimal} is a lossless
 * embedding (every integer is exactly representable), so promoting a literal
 * erases nothing. The reverse direction stays forbidden.
 *
 * <p>Runs inside {@link IrCompiler#compile} right after alias resolution, so
 * both the single-file path (parser-produced {@code Record}s) and the linked
 * path ({@code StructLiteralRewriter}-produced {@code Record}s) are covered.
 *
 * <p><b>Scope:</b> literal promotion at declared boundaries only. A runtime
 * {@code Int} <em>value</em> meeting a {@code Decimal} in arithmetic is not
 * auto-promoted — the interpreter raises a clear mixed-operand error instead
 * (value-level mixing is the deferred mixed-arithmetic slice). Call arguments
 * against {@code Decimal} params are also not promoted here (overload-
 * sensitive); same clear error applies.
 */
final class DecimalPromotion {

    private DecimalPromotion() {}

    static IrModule rewrite(IrModule module) {
        Map<String, IrSort.Structural> structs =
                sibarum.pontif.types.TypeCatalog.fromModule(module).structShapes();
        List<IrStmt> out = new ArrayList<>(module.statements().size());
        for (IrStmt stmt : module.statements()) {
            out.add(switch (stmt) {
                case IrStmt.FunctionDecl fd -> new IrStmt.FunctionDecl(
                        fd.name(), fd.params(), fd.returnSort(),
                        rewriteExpr(fd.body(), structs), fd.origin(), fd.topLevelLet(),
                        fd.typeParams());
                case IrStmt.TraitImpl ti -> {
                    List<IrStmt.FunctionDecl> methods = new ArrayList<>(ti.methods().size());
                    for (IrStmt.FunctionDecl m : ti.methods()) {
                        methods.add(new IrStmt.FunctionDecl(
                                m.name(), m.params(), m.returnSort(),
                                rewriteExpr(m.body(), structs), m.origin()));
                    }
                    List<IrStmt.FunctionDecl> attrs = new ArrayList<>(ti.attributeProducers().size());
                    for (IrStmt.FunctionDecl a : ti.attributeProducers()) {
                        attrs.add(new IrStmt.FunctionDecl(
                                a.name(), a.params(), a.returnSort(),
                                rewriteExpr(a.body(), structs), a.origin()));
                    }
                    yield new IrStmt.TraitImpl(ti.typeName(), ti.traitName(), methods, attrs,
                            ti.typeBindings(), ti.typeParams(), ti.traitTypeArgs(), ti.origin());
                }
                default -> stmt;  // TypeAlias / Proof / Requires / Exports / NoOp
            });
        }
        return new IrModule(module.name(), out, rewriteExpr(module.main(), structs));
    }

    private static IrExpr rewriteExpr(IrExpr e, Map<String, IrSort.Structural> structs) {
        return switch (e) {
            case IrExpr.Lit l -> l;
            case IrExpr.Dec d -> d;
            // Char does NOT promote — there is no Char/Int tower; mixed
            // comparisons fail closed at the type/runtime layers.
            case IrExpr.Chr c -> c;
            // String does NOT promote either — no tower of any kind.
            case IrExpr.Str s -> s;
            case IrExpr.Bool b -> b;
            case IrExpr.Var v -> v;
            case IrExpr.SelfRef s -> s;
            case IrExpr.DispatchRef d -> d;
            case IrExpr.BinOp op -> new IrExpr.BinOp(
                    op.op(), rewriteExpr(op.left(), structs), rewriteExpr(op.right(), structs), op.origin());
            case IrExpr.LetIn l -> new IrExpr.LetIn(
                    l.name(), l.declaredSort(),
                    // The user's claim is the declared boundary when present
                    // (the narrowing slot holds the value's own inferred sort,
                    // which for an Int literal is an Int refinement — no
                    // promotion signal there).
                    promote(rewriteExpr(l.value(), structs),
                            l.claim() != null ? l.claim() : l.declaredSort()),
                    rewriteExpr(l.body(), structs), l.origin(), l.claim());
            case IrExpr.Call c -> {
                List<IrExpr> args = new ArrayList<>(c.args().size());
                for (IrExpr a : c.args()) args.add(rewriteExpr(a, structs));
                yield new IrExpr.Call(c.functionName(), args, c.origin());
            }
            case IrExpr.Lambda lam -> new IrExpr.Lambda(
                    lam.params(), lam.returnSort(), rewriteExpr(lam.body(), structs), lam.origin());
            case IrExpr.Apply app -> {
                List<IrExpr> args = new ArrayList<>(app.args().size());
                for (IrExpr a : app.args()) args.add(rewriteExpr(a, structs));
                yield new IrExpr.Apply(rewriteExpr(app.fn(), structs), args, app.origin());
            }
            case IrExpr.Match m -> {
                List<IrExpr.MatchBranch> bs = new ArrayList<>(m.branches().size());
                for (IrExpr.MatchBranch b : m.branches()) {
                    bs.add(new IrExpr.MatchBranch(b.pattern(), rewriteExpr(b.result(), structs)));
                }
                yield new IrExpr.Match(rewriteExpr(m.scrutinee(), structs), bs, m.origin());
            }
            case IrExpr.Record r -> {
                IrSort.Structural decl = r.typeName() == null ? null : structs.get(r.typeName());
                Map<String, IrExpr> members = new LinkedHashMap<>();
                for (Map.Entry<String, IrExpr> en : r.members().entrySet()) {
                    IrExpr member = rewriteExpr(en.getValue(), structs);
                    if (decl != null) {
                        IrSort memberSort = decl.members().get(en.getKey());
                        if (memberSort != null) {
                            member = promote(member, memberSort);
                        }
                    }
                    members.put(en.getKey(), member);
                }
                yield new IrExpr.Record(r.typeName(), members, r.origin());
            }
            case IrExpr.FieldAccess fa -> new IrExpr.FieldAccess(
                    rewriteExpr(fa.base(), structs), fa.fieldName(), fa.origin());
            case IrExpr.MethodCall mc -> throw MethodResolver.unresolved(mc, "DecimalPromotion");
            // REVISIT (docs/iteration.md §10): no Int→Decimal promotion inside
            // the source / arm writes yet (slice 1 builds those explicitly).
            case IrExpr.Iterate it -> it;
            case IrExpr.Emit em -> new IrExpr.Emit(
                    rewriteExpr(em.event(), structs), rewriteExpr(em.body(), structs), em.origin());
            case IrExpr.Cast cast -> new IrExpr.Cast(cast.targetSort(),
                    rewriteExpr(cast.value(), structs), cast.origin());
        };
    }

    /** Lit → Dec when the declared sort is Decimal (bare or narrowed); otherwise unchanged. */
    private static IrExpr promote(IrExpr value, IrSort declared) {
        if (value instanceof IrExpr.Lit lit && isDecimalSort(declared)) {
            return new IrExpr.Dec(BigDecimal.valueOf(lit.value()), lit.origin());
        }
        return value;
    }

    private static boolean isDecimalSort(IrSort sort) {
        return switch (sort) {
            case IrSort.Named n -> n.name().equals("Decimal");
            case IrSort.Refined r -> r.name().equals("Decimal");
            default -> false;
        };
    }
}
