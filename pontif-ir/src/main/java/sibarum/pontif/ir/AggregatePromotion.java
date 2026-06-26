package sibarum.pontif.ir;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Promotes <em>anonymous</em> aggregate literals ({@link IrExpr.Record} with a
 * null typeName — dictionary literals, S-expr {@code (record …)} forms) to a
 * <em>declared named struct</em> at assertion boundaries: let bindings with a
 * declared struct sort, call arguments against struct-typed params, and return
 * positions. {@code let p:Point = {x = 1, y = 2}} is checked construction with
 * the redundant name elided — the user asserts the type right there, so
 * stamping it is sugar, not a lie.
 *
 * <p>The claim framework (see docs/TODO.md, aggregate grid): <b>construction is
 * where claims are made; matching is where they're tested; nothing in between
 * invents one.</b> Accordingly this pass never touches <em>question</em>
 * positions — match scrutinees/patterns and {@code ==} operands — where no
 * assertion exists to authorize a claim.
 *
 * <p>Stamping is gated by construction totality: the literal must provide
 * <em>exactly</em> the declared field set (a missing field or an unknown extra
 * key is a compile error), and members are canonicalized into declared order.
 * Nested anonymous members promote recursively against their declared member
 * sorts.
 *
 * <p>Sibling of {@link DecimalPromotion} and deliberately ordered <b>before</b>
 * it in {@link IrCompiler#compile}: stamping the name first lets
 * DecimalPromotion's member-literal promotion ({@code 1 → 1.0} for Decimal
 * fields) fire on the now-named record.
 *
 * <p>Unlike DecimalPromotion, call arguments ARE covered (a typed param is an
 * assertion position). When a call name resolves to multiple declarations that
 * <em>disagree</em> about the struct expected at a position, an anonymous
 * literal there is rejected as ambiguous — construct explicitly
 * ({@code Point{…}}) to pick.
 */
final class AggregatePromotion {

    private AggregatePromotion() {}

    static IrModule rewrite(IrModule module) throws CompileException {
        Map<String, IrSort.Structural> structs = TypeRegistry.collect(module);
        Map<String, List<IrStmt.FunctionDecl>> fns = collectFunctions(module);
        List<IrStmt> out = new ArrayList<>(module.statements().size());
        for (IrStmt stmt : module.statements()) {
            out.add(switch (stmt) {
                case IrStmt.FunctionDecl fd -> new IrStmt.FunctionDecl(
                        fd.name(), fd.params(), fd.returnSort(),
                        rewriteExpr(fd.body(), fd.returnSort(), structs, fns), fd.origin(),
                        fd.topLevelLet(), fd.typeParams());
                case IrStmt.TraitImpl ti -> {
                    List<IrStmt.FunctionDecl> methods = new ArrayList<>(ti.methods().size());
                    for (IrStmt.FunctionDecl m : ti.methods()) {
                        methods.add(new IrStmt.FunctionDecl(
                                m.name(), m.params(), m.returnSort(),
                                rewriteExpr(m.body(), m.returnSort(), structs, fns), m.origin()));
                    }
                    List<IrStmt.FunctionDecl> attrs = new ArrayList<>(ti.attributeProducers().size());
                    for (IrStmt.FunctionDecl a : ti.attributeProducers()) {
                        attrs.add(new IrStmt.FunctionDecl(
                                a.name(), a.params(), a.returnSort(),
                                rewriteExpr(a.body(), a.returnSort(), structs, fns), a.origin()));
                    }
                    yield new IrStmt.TraitImpl(ti.typeName(), ti.traitName(), methods, attrs,
                            ti.typeBindings(), ti.typeParams(), ti.traitTypeArgs(), ti.origin());
                }
                default -> stmt;  // TypeAlias / Proof / Requires / Exports / NoOp
            });
        }
        return new IrModule(module.name(), out,
                module.main() == null ? null : rewriteExpr(module.main(), null, structs, fns));
    }

    private static Map<String, List<IrStmt.FunctionDecl>> collectFunctions(IrModule module) {
        Map<String, List<IrStmt.FunctionDecl>> fns = new LinkedHashMap<>();
        for (IrStmt stmt : module.statements()) {
            if (stmt instanceof IrStmt.FunctionDecl fd) {
                fns.computeIfAbsent(fd.name(), k -> new ArrayList<>()).add(fd);
            } else if (stmt instanceof IrStmt.TraitImpl ti) {
                for (IrStmt.FunctionDecl m : ti.methods()) {
                    fns.computeIfAbsent(m.name(), k -> new ArrayList<>()).add(m);
                }
            }
        }
        return fns;
    }

    /**
     * Rewrites {@code e} under an {@code expected} sort context ({@code null} =
     * no assertion in effect). The expected sort flows into <em>tail</em>
     * positions only (let bodies, match-branch results) and into the value side
     * of declared boundaries; it is deliberately dropped at question positions.
     */
    private static IrExpr rewriteExpr(
            IrExpr e, IrSort expected,
            Map<String, IrSort.Structural> structs,
            Map<String, List<IrStmt.FunctionDecl>> fns) throws CompileException {
        return switch (e) {
            case IrExpr.Lit l -> l;
            case IrExpr.Dec d -> d;
            case IrExpr.Chr c -> c;
            case IrExpr.Str s -> s;
            case IrExpr.Bool b -> b;
            case IrExpr.Var v -> v;
            case IrExpr.SelfRef s -> s;
            case IrExpr.DispatchRef d -> d;
            // Operands of any BinOp (including ==) carry no assertion.
            case IrExpr.BinOp op -> new IrExpr.BinOp(
                    op.op(),
                    rewriteExpr(op.left(), null, structs, fns),
                    rewriteExpr(op.right(), null, structs, fns),
                    op.origin());
            case IrExpr.LetIn l -> new IrExpr.LetIn(
                    l.name(), l.declaredSort(),
                    // A claimed sort is the assertion when present (the
                    // narrowing slot holds the inferred shape, which for an
                    // anonymous aggregate value is no assertion at all).
                    rewriteExpr(l.value(),
                            l.claim() != null ? l.claim() : l.declaredSort(),
                            structs, fns),
                    // The let's body is the enclosing tail — the outer
                    // assertion (e.g. a function's return sort) flows through.
                    rewriteExpr(l.body(), expected, structs, fns),
                    l.origin(), l.claim());
            case IrExpr.Call c -> {
                List<IrStmt.FunctionDecl> candidates = fns.getOrDefault(c.functionName(), List.of());
                List<IrExpr> args = new ArrayList<>(c.args().size());
                for (int i = 0; i < c.args().size(); i++) {
                    IrExpr arg = c.args().get(i);
                    IrSort paramExpected = agreedParamSort(candidates, c.args().size(), i, structs);
                    if (paramExpected == null
                            && arg instanceof IrExpr.Record r && r.typeName() == null
                            && disagreeingStructParams(candidates, c.args().size(), i, structs)) {
                        throw new CompileException(
                                "Anonymous aggregate argument to '" + c.functionName()
                                        + "' is ambiguous — overloads disagree on the expected "
                                        + "type at position " + i + "; construct explicitly "
                                        + "(e.g. TypeName{...})",
                                arg.origin());
                    }
                    args.add(rewriteExpr(arg, paramExpected, structs, fns));
                }
                yield new IrExpr.Call(c.functionName(), args, c.origin());
            }
            case IrExpr.Lambda lam -> new IrExpr.Lambda(
                    lam.params(), lam.returnSort(),
                    rewriteExpr(lam.body(), lam.returnSort(), structs, fns), lam.origin());
            case IrExpr.Apply app -> {
                List<IrExpr> args = new ArrayList<>(app.args().size());
                for (IrExpr a : app.args()) args.add(rewriteExpr(a, null, structs, fns));
                yield new IrExpr.Apply(rewriteExpr(app.fn(), null, structs, fns), args, app.origin());
            }
            case IrExpr.Match m -> {
                List<IrExpr.MatchBranch> bs = new ArrayList<>(m.branches().size());
                for (IrExpr.MatchBranch b : m.branches()) {
                    // Branch results are tail positions; the scrutinee is a
                    // QUESTION — no assertion flows into it.
                    bs.add(new IrExpr.MatchBranch(
                            b.pattern(), rewriteExpr(b.result(), expected, structs, fns)));
                }
                yield new IrExpr.Match(
                        rewriteExpr(m.scrutinee(), null, structs, fns), bs, m.origin());
            }
            case IrExpr.Record r -> rewriteRecord(r, expected, structs, fns);
            case IrExpr.FieldAccess fa -> new IrExpr.FieldAccess(
                    rewriteExpr(fa.base(), null, structs, fns), fa.fieldName(), fa.origin());
            case IrExpr.MethodCall mc -> throw MethodResolver.unresolved(mc, "AggregatePromotion");
            // REVISIT (docs/iteration.md §10): pass-through — no aggregate stamping
            // into the source / arm writes yet (slice 1 builds those explicitly).
            case IrExpr.Iterate it -> it;
            // emit's event is a question position; the body is the tail, so the
            // enclosing assertion flows through (docs/events.md).
            case IrExpr.Emit em -> new IrExpr.Emit(
                    rewriteExpr(em.event(), null, structs, fns),
                    rewriteExpr(em.body(), expected, structs, fns), em.origin());
            // The cast's value is a question position — no assertion flows in.
            case IrExpr.Cast cast -> new IrExpr.Cast(cast.targetSort(),
                    rewriteExpr(cast.value(), null, structs, fns), cast.origin());
        };
    }

    private static IrExpr rewriteRecord(
            IrExpr.Record r, IrSort expected,
            Map<String, IrSort.Structural> structs,
            Map<String, List<IrStmt.FunctionDecl>> fns) throws CompileException {
        IrSort.Structural target = r.typeName() == null
                ? resolveStruct(expected, structs)
                : structs.get(r.typeName());
        if (r.typeName() == null && target != null) {
            // Stamp: construction totality first — the literal must provide
            // exactly the declared field set.
            for (String declared : target.members().keySet()) {
                if (!r.members().containsKey(declared)) {
                    throw new CompileException(
                            "Aggregate literal for '" + target.name() + "' is missing field '"
                                    + declared + "'; required fields: " + target.members().keySet(),
                            r.origin());
                }
            }
            for (String provided : r.members().keySet()) {
                if (!target.members().containsKey(provided)) {
                    throw new CompileException(
                            "Aggregate literal for '" + target.name() + "' has no field '"
                                    + provided + "'; declared fields: " + target.members().keySet(),
                            r.origin());
                }
            }
            // Canonicalize into declared field order (order is load-bearing),
            // promoting nested members against their declared sorts.
            Map<String, IrExpr> ordered = new LinkedHashMap<>();
            for (Map.Entry<String, IrSort> en : target.members().entrySet()) {
                ordered.put(en.getKey(), rewriteExpr(
                        r.members().get(en.getKey()), en.getValue(), structs, fns));
            }
            return new IrExpr.Record(target.name(), ordered, r.origin());
        }
        // Already-named record (or no assertion): recurse members; a named
        // record's members still carry their declared sorts as assertions so
        // nested anonymous literals promote.
        Map<String, IrExpr> members = new LinkedHashMap<>();
        for (Map.Entry<String, IrExpr> en : r.members().entrySet()) {
            IrSort memberExpected = target == null ? null : target.members().get(en.getKey());
            members.put(en.getKey(), rewriteExpr(en.getValue(), memberExpected, structs, fns));
        }
        return new IrExpr.Record(r.typeName(), members, r.origin());
    }

    /**
     * Resolves an expected sort to the named struct it asserts, or null when it
     * asserts no (single, real-named) struct. Sentinel names ({@code _},
     * {@code _record}, {@code _tuple}) assert nothing.
     */
    private static IrSort.Structural resolveStruct(
            IrSort expected, Map<String, IrSort.Structural> structs) {
        if (expected == null) return null;
        String name = switch (expected) {
            case IrSort.Named n -> n.name();
            case IrSort.Refined ref -> ref.name();
            case IrSort.Structural s -> s.name();
            default -> null;
        };
        if (name == null || name.equals("_") || name.equals("_record") || name.equals("_tuple")) {
            return null;
        }
        // Only a REGISTRY-DECLARED struct is a construction target. An inline
        // structural sort (S-expr style, possibly a partial field view) is a
        // shape REQUIREMENT, not a definition — there is no ground truth to
        // totality-check a literal against, and its name is a label on a
        // shape, not a nominal type.
        return structs.get(name);
    }

    /**
     * The struct-typed param sort all candidate declarations agree on at
     * {@code position} (for calls of the given arity), or null when there is no
     * candidate, no struct param there, or disagreement.
     */
    private static IrSort agreedParamSort(
            List<IrStmt.FunctionDecl> candidates, int arity, int position,
            Map<String, IrSort.Structural> structs) {
        IrSort.Structural agreed = null;
        IrSort agreedSort = null;
        boolean any = false;
        for (IrStmt.FunctionDecl fd : candidates) {
            if (fd.params().size() != arity) continue;
            IrSort paramSort = fd.params().get(position).sort();
            IrSort.Structural target = resolveStruct(paramSort, structs);
            if (target == null) return null;  // a non-struct candidate → no assertion
            if (agreed != null && !agreed.name().equals(target.name())) return null;  // disagreement
            agreed = target;
            agreedSort = paramSort;
            any = true;
        }
        return any ? agreedSort : null;
    }

    /** True when arity-matching candidates expect DIFFERENT structs at {@code position}. */
    private static boolean disagreeingStructParams(
            List<IrStmt.FunctionDecl> candidates, int arity, int position,
            Map<String, IrSort.Structural> structs) {
        String seen = null;
        for (IrStmt.FunctionDecl fd : candidates) {
            if (fd.params().size() != arity) continue;
            IrSort.Structural target = resolveStruct(fd.params().get(position).sort(), structs);
            if (target == null) return false;  // non-struct candidate → not a struct-vs-struct clash
            if (seen != null && !seen.equals(target.name())) return true;
            seen = target.name();
        }
        return false;
    }
}
