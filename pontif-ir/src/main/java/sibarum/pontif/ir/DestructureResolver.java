package sibarum.pontif.ir;

import sibarum.pontif.core.Origin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Post-link pass that resolves DEFERRED positional destructuring patterns —
 * the one place struct-shape resolution and the arity-total rule live for the
 * cross-module case, shared by every positional destructuring form (match,
 * nested, positional param, tuple-of-structs).
 *
 * <p><b>Why this exists (cluster 2 — destructuring per-form seams):</b> the alt
 * parser resolves a {@code [Vec(x, y)]} pattern's field ORDER and field SORTS
 * from {@code declaredStructs}, which only sees structs declared earlier in the
 * SAME file. {@code requires}-linking happens after parsing, so a positional
 * pattern over an <em>imported</em> struct couldn't be resolved at parse time —
 * each destructuring form threw its own "struct decl not found" parse error
 * (while the {@code .{}} by-name form, which needs no field order, deferred and
 * worked). The forms diverged.
 *
 * <p>The fix lifts shape resolution out of the parser: for an imported struct
 * the parser emits a deferred pattern — the type name, the positional binder
 * names IN ORDER, renames, and literal/{@code _}-discard constraints encoded
 * symbolically (slot keys {@code _0.._n}; binder/discard roles encoded in the
 * slot sorts via {@link #DEFERRED_BIND}/{@link #DEFERRED_SKIP})
 * — WITHOUT resolving to declared field names. This pass runs on the combined,
 * FQN-resolved module (so it sees every module's structs via
 * {@link sibarum.pontif.types.TypeCatalog#fromModule}) and:
 * <ol>
 *   <li>maps each positional slot to the struct's declared field name, in order;
 *   <li>fills the slot's field sort from the declaration;
 *   <li>enforces the arity-total rule (too-few AND too-many) via the SAME helper
 *       the parser's local path uses ({@link #arityTotalError});
 *   <li>desugars the bindings — for a match branch, wraps the branch result with
 *       {@code let x = scrutinee.x}; for a struct param, reduces the param sort to
 *       {@code [Vec]} and wraps the function body the same way.
 * </ol>
 *
 * <p>Invoked <b>only by the module linker</b> (after {@link NameResolver} and
 * {@link StructLiteralRewriter}). A single-file compile never runs here and never
 * needs to — its struct patterns were resolved at parse time, so the pass is
 * additive and leaves the single-file path byte-for-byte unchanged.
 */
public final class DestructureResolver {

    private DestructureResolver() {}

    /**
     * Slot-sort encodings for a DEFERRED positional struct pattern, written by
     * the parser ({@code AltParser}) and decoded here. A slot bound to binder
     * {@code x} carries sort {@code Named("_$bind$x")}; a {@code _} discard
     * carries {@code Named("_$skip$")}. Defined in {@code pontif-ir} (the module
     * the parser depends on) so both sides share one constant.
     */
    public static final String DEFERRED_BIND = "_$bind$";
    public static final String DEFERRED_SKIP = "_$skip$";

    /**
     * The arity-total rule for a positional struct pattern (verdict B): a
     * {@code [Type(...)]} pattern wears the constructor's clothes, so it must
     * account for EVERY field — too few lies by omission, too many over-claims.
     * Returns the error message, or {@code null} when the arity matches. Shared
     * by the parser's local path and this pass's cross-module path so the rule is
     * defined ONCE and fires identically for too-few and too-many.
     */
    public static String arityTotalError(String typeName, int provided, int declared) {
        if (provided == declared) return null;
        if (provided > declared) {
            return "Too many fields for struct '" + typeName + "' (" + declared + " declared)";
        }
        return "Pattern [" + typeName + "(...)] lists " + provided + " of "
                + declared + " fields — a positional pattern must account "
                + "for every field. Use '_' to discard the unwanted ones "
                + "(e.g. [" + typeName + "(a, _, _)]) or focus by name with a refinement "
                + "[" + typeName + ":@.field …].";
    }

    public static IrModule rewrite(IrModule combined) throws CompileException {
        Map<String, IrSort.Structural> structs =
                sibarum.pontif.types.TypeCatalog.fromModule(combined).structShapes();

        List<IrStmt> out = new ArrayList<>(combined.statements().size());
        for (IrStmt stmt : combined.statements()) {
            out.add(switch (stmt) {
                case IrStmt.FunctionDecl fd -> rewriteFunction(fd, structs);
                case IrStmt.TraitImpl ti -> {
                    List<IrStmt.FunctionDecl> methods = new ArrayList<>(ti.methods().size());
                    for (IrStmt.FunctionDecl mm : ti.methods()) methods.add(rewriteFunction(mm, structs));
                    List<IrStmt.FunctionDecl> attrs = new ArrayList<>(ti.attributeProducers().size());
                    for (IrStmt.FunctionDecl a : ti.attributeProducers()) attrs.add(rewriteFunction(a, structs));
                    yield new IrStmt.TraitImpl(ti.typeName(), ti.traitName(), methods, attrs,
                            ti.typeBindings(), ti.typeParams(), ti.traitTypeArgs(), ti.origin());
                }
                case IrStmt.Proof p -> new IrStmt.Proof(
                        p.functionName(), rewriteExpr(p.proofTree(), structs), p.origin());
                default -> stmt;  // TypeAlias / Requires / Exports / NoOp carry no destructure expr
            });
        }
        // A null main() is legitimate (IrModule never requires it); carry it through unchanged,
        // as the sibling passes (ConstructionGate, AggregatePromotion, MethodOperatorResolver) do —
        // feeding null into rewriteExpr's switch(e) would NPE on the null selector.
        IrExpr main = combined.main() == null ? null : rewriteExpr(combined.main(), structs);
        return new IrModule(combined.name(), out, main);
    }

    private static IrStmt.FunctionDecl rewriteFunction(
            IrStmt.FunctionDecl fd, Map<String, IrSort.Structural> structs) throws CompileException {
        // A struct param written as a deferred positional pattern (`v:[Vec(x, y)]`)
        // reduces to the base sort `[Vec]`; the body is wrapped with the field
        // bindings the parser couldn't generate (field names were unknown).
        List<IrParam> params = new ArrayList<>(fd.params().size());
        List<ParamBinding> paramBindings = new ArrayList<>();
        for (IrParam p : fd.params()) {
            if (p.sort() instanceof IrSort.Structural sp && isDeferred(sp, structs)) {
                IrSort.Structural decl = lookup(sp, structs);
                // A positional struct param IS a destructure pattern — enforce the
                // arity-total rule (verdict B) here, the cross-module half of the
                // ONE place the rule lives (the parser's local path is the other).
                String arityErr = arityTotalError(sp.name(), sp.members().size(), decl.members().size());
                if (arityErr != null) throw new CompileException(arityErr, sp.origin());
                paramBindings.add(new ParamBinding(p.name(), sp, decl));
                params.add(new IrParam(p.name(), new IrSort.Named(sp.name(), sp.origin())));
            } else {
                params.add(p);
            }
        }
        IrExpr body = rewriteExpr(fd.body(), structs);
        for (int i = paramBindings.size() - 1; i >= 0; i--) {
            ParamBinding pb = paramBindings.get(i);
            body = wrapBindings(pb.pattern(), pb.decl(),
                    new IrExpr.Var(pb.paramName(), body.origin()), body, structs);
        }
        return new IrStmt.FunctionDecl(
                fd.name(), params, fd.returnSort(), body, fd.origin(),
                fd.topLevelLet(), fd.typeParams());
    }

    private record ParamBinding(String paramName, IrSort.Structural pattern, IrSort.Structural decl) {}

    private static IrExpr rewriteExpr(IrExpr e, Map<String, IrSort.Structural> structs)
            throws CompileException {
        return switch (e) {
            case IrExpr.Lit l -> l;
            case IrExpr.Dec d -> d;
            case IrExpr.Chr c -> c;
            case IrExpr.Str s -> s;
            case IrExpr.Bool b -> b;
            case IrExpr.Var v -> v;
            case IrExpr.SelfRef s -> s;
            case IrExpr.DispatchRef d -> d;
            case IrExpr.BinOp op -> new IrExpr.BinOp(
                    op.op(), rewriteExpr(op.left(), structs), rewriteExpr(op.right(), structs), op.origin());
            case IrExpr.LetIn l -> new IrExpr.LetIn(
                    l.name(), l.declaredSort(), rewriteExpr(l.value(), structs),
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
            case IrExpr.Match mt -> rewriteMatch(mt, structs);
            case IrExpr.Record r -> {
                Map<String, IrExpr> mem = new LinkedHashMap<>();
                for (Map.Entry<String, IrExpr> en : r.members().entrySet()) {
                    mem.put(en.getKey(), rewriteExpr(en.getValue(), structs));
                }
                yield new IrExpr.Record(r.typeName(), mem, r.origin());
            }
            case IrExpr.FieldAccess fa -> new IrExpr.FieldAccess(
                    rewriteExpr(fa.base(), structs), fa.fieldName(), fa.origin());
            case IrExpr.MethodCall mc -> {
                List<IrExpr> args = new ArrayList<>(mc.args().size());
                for (IrExpr a : mc.args()) args.add(rewriteExpr(a, structs));
                yield new IrExpr.MethodCall(
                        rewriteExpr(mc.receiver(), structs), mc.methodName(), args, mc.origin());
            }
            case IrExpr.Iterate it -> it;
            case IrExpr.Emit em -> new IrExpr.Emit(
                    rewriteExpr(em.event(), structs), rewriteExpr(em.body(), structs), em.origin());
            case IrExpr.Cast cast -> new IrExpr.Cast(
                    cast.targetSort(), rewriteExpr(cast.value(), structs), cast.origin());
        };
    }

    private static IrExpr rewriteMatch(IrExpr.Match mt, Map<String, IrSort.Structural> structs)
            throws CompileException {
        IrExpr scrutinee = rewriteExpr(mt.scrutinee(), structs);
        List<IrExpr.MatchBranch> branches = new ArrayList<>(mt.branches().size());
        for (IrExpr.MatchBranch b : mt.branches()) {
            IrExpr result = rewriteExpr(b.result(), structs);
            IrSort pattern = b.pattern();
            if (pattern instanceof IrSort.Structural sp && containsDeferred(sp, structs)) {
                // Resolve the pattern FIRST — it runs the arity-total check, so a
                // too-few/too-many pattern is reported here before wrapBindings
                // would index a missing/extra slot.
                pattern = resolvePattern(sp, structs);
                // The match scrutinee is always a Var after parse-time desugaring
                // (the parser introduces __scrutinee$N for non-Var scrutinees), so
                // it is a stable access root for the deferred bindings the parser
                // left for us to generate.
                result = wrapBindings(sp, null, scrutinee, result, structs);
            }
            branches.add(new IrExpr.MatchBranch(pattern, result));
        }
        return new IrExpr.Match(scrutinee, branches, mt.origin());
    }

    // ---- deferred-pattern detection / lookup ------------------------------

    /** A deferred pattern keys its slots positionally ({@code _0, _1, …}). */
    private static boolean isPositionalKeyed(IrSort.Structural sp) {
        int i = 0;
        for (String k : sp.members().keySet()) {
            if (!k.equals("_" + i)) return false;
            i++;
        }
        return i > 0;
    }

    /** True if {@code sp} itself is a deferred positional struct pattern. */
    private static boolean isDeferred(IrSort.Structural sp, Map<String, IrSort.Structural> structs) {
        return !sp.name().equals("_tuple") && !sp.name().equals("_record")
                && isPositionalKeyed(sp) && structs.containsKey(sp.name());
    }

    /** True if {@code sp} or any nested struct slot is a deferred pattern. */
    private static boolean containsDeferred(IrSort.Structural sp, Map<String, IrSort.Structural> structs) {
        if (isDeferred(sp, structs)) return true;
        for (IrSort m : sp.members().values()) {
            if (m instanceof IrSort.Structural nested && containsDeferred(nested, structs)) return true;
        }
        return false;
    }

    private static IrSort.Structural lookup(IrSort.Structural sp, Map<String, IrSort.Structural> structs)
            throws CompileException {
        IrSort.Structural decl = structs.get(sp.name());
        if (decl == null) {
            throw new CompileException(
                    "Pattern [" + sp.name() + "(...)] references struct '" + sp.name()
                            + "', which is not declared or imported.", sp.origin());
        }
        return decl;
    }

    // ---- pattern resolution (slot -> field name, arity-total) -------------

    /**
     * Rewrites a deferred positional pattern into the canonical field-name-keyed
     * {@link IrSort.Structural} the runtime matcher discriminates against —
     * mapping slot {@code _i} to the struct's i-th declared field, filling binder
     * slots with the declared field sort and keeping literal/refinement slots as
     * their constraint. Enforces the arity-total rule. Recurses into nested
     * struct/tuple slots (a nested non-deferred slot is left as-is).
     */
    private static IrSort.Structural resolvePattern(
            IrSort.Structural sp, Map<String, IrSort.Structural> structs) throws CompileException {
        if (!isDeferred(sp, structs)) {
            // A tuple wrapper ([(Vec(x,y), c)]) or already-resolved struct: only
            // its nested struct slots may need resolving.
            Map<String, IrSort> members = new LinkedHashMap<>();
            for (Map.Entry<String, IrSort> e : sp.members().entrySet()) {
                members.put(e.getKey(), e.getValue() instanceof IrSort.Structural nested
                        ? resolvePattern(nested, structs) : e.getValue());
            }
            return new IrSort.Structural(sp.name(), members, sp.baseSort(), sp.typeParams(), sp.origin());
        }
        IrSort.Structural decl = lookup(sp, structs);
        List<String> fields = new ArrayList<>(decl.members().keySet());
        String arityErr = arityTotalError(sp.name(), sp.members().size(), fields.size());
        if (arityErr != null) throw new CompileException(arityErr, sp.origin());

        Map<String, IrSort> members = new LinkedHashMap<>();
        int slot = 0;
        for (IrSort memberSort : sp.members().values()) {
            String field = fields.get(slot);
            IrSort declSort = decl.members().get(field);
            if (memberSort instanceof IrSort.Structural nested) {
                members.put(field, resolvePattern(nested, structs));     // nested pattern
            } else if (isBindEncoded(memberSort) || isSkipEncoded(memberSort)) {
                members.put(field, declSort);                            // binder or discard
            } else {
                members.put(field, memberSort);                          // literal / refinement constraint
            }
            slot++;
        }
        return new IrSort.Structural(sp.name(), members, sp.origin());
    }

    // ---- binding desugar (let binder = accessPath.field) ------------------

    /**
     * Generates the let-bindings the parser left to us, threading
     * {@code accessPath}. Two regimes, matching how the parser split the work:
     * <ul>
     *   <li><b>{@code sp} IS a deferred struct</b> ({@code [Outer(...)]}) — the
     *       parser skipped its WHOLE wrap, so bind every slot here: map slot
     *       {@code _i} to the i-th declared field, emit {@code let binder =
     *       accessPath.field} for binders, recurse into nested struct slots, and
     *       skip discard / literal slots.</li>
     *   <li><b>{@code sp} is a non-deferred wrapper</b> (a tuple, or a local
     *       struct that happens to nest a deferred struct, e.g.
     *       {@code [(Vec(x,y), c)]}) — the parser already bound its plain slots;
     *       descend ONLY into deferred nested struct slots (accessed by this
     *       wrapper's own slot key) so we don't double-bind.</li>
     * </ul>
     * Reverse order so the first field is the outermost let.
     */
    private static IrExpr wrapBindings(
            IrSort.Structural sp, IrSort.Structural decl, IrExpr accessPath, IrExpr result,
            Map<String, IrSort.Structural> structs) throws CompileException {
        boolean spDeferred = isDeferred(sp, structs);
        if (spDeferred && decl == null) decl = lookup(sp, structs);
        List<String> fields = spDeferred ? new ArrayList<>(decl.members().keySet()) : null;
        List<Map.Entry<String, IrSort>> entries = new ArrayList<>(sp.members().entrySet());
        for (int i = entries.size() - 1; i >= 0; i--) {
            Map.Entry<String, IrSort> e = entries.get(i);
            IrSort memberSort = e.getValue();
            if (spDeferred) {
                String field = fields.get(i);
                IrExpr access = new IrExpr.FieldAccess(accessPath, field, Origin.NONE);
                if (memberSort instanceof IrSort.Structural nested) {
                    result = wrapBindings(nested, null, access, result, structs);
                } else if (isBindEncoded(memberSort)) {
                    String binder = ((IrSort.Named) memberSort).name()
                            .substring(DEFERRED_BIND.length());
                    result = new IrExpr.LetIn(binder, decl.members().get(field), access, result, Origin.NONE);
                }
                // skip / literal slots bind nothing
            } else {
                // Non-deferred wrapper: the parser bound its plain slots; only a
                // deferred nested struct still needs binding, accessed by this
                // wrapper's own (already-correct) slot key.
                if (memberSort instanceof IrSort.Structural nested && isDeferred(nested, structs)) {
                    IrExpr access = new IrExpr.FieldAccess(accessPath, e.getKey(), Origin.NONE);
                    result = wrapBindings(nested, null, access, result, structs);
                }
            }
        }
        return result;
    }

    private static boolean isBindEncoded(IrSort s) {
        return s instanceof IrSort.Named n && n.name().startsWith(DEFERRED_BIND);
    }

    private static boolean isSkipEncoded(IrSort s) {
        return s instanceof IrSort.Named n && n.name().startsWith(DEFERRED_SKIP);
    }
}
