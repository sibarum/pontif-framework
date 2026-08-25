package sibarum.pontif.ir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sibarum.pontif.core.QualifiedName;
import sibarum.pontif.types.TypeSystem;

/**
 * Inserts implicit {@code Int → Decimal} coercions at value boundaries. The
 * closed primitive tower is the one coercion Pontif keeps implicit
 * (docs/dispatch-unification.md → "Coercion"): a lossless in-domain embedding the
 * user can neither extend nor shadow, so promoting it at a boundary is
 * unambiguous and total. Everything else stays an explicit {@code (Type:value)}
 * cast.
 *
 * <p>{@link DecimalPromotion} already rewrites Int <em>literals</em> ({@code 12}
 * → {@code 12.0}) at declared boundaries. This pass covers the value-level cases
 * that a syntactic literal rewrite cannot — a {@code Var}, {@code FieldAccess},
 * or {@code Call} whose sort is {@code Int} meeting a declared {@code Decimal}:
 * let-claims, function/method/attribute returns, record members, and single-
 * overload call arguments. The inserted node is an {@link IrExpr.Cast} to
 * {@code Decimal} — the same coercion the interpreter and (later) an explicit
 * cast run, so the conservation ledger records a coercion, not phantom
 * arithmetic.
 *
 * <p>Runs after {@link DecimalPromotion} (so literals are already {@code Dec} and
 * infer as Decimal, never re-wrapped) and before {@link ConstructionGate} (so the
 * claim gate judges a value already at its Decimal sort). Casts it inserts live
 * only in the compiled IR — {@code CastGate}, which runs on the pre-compile
 * module, never sees them, so user-written {@code (Decimal:x)} casts stay gated
 * while these do not.
 */
final class NumericCoercion {

    private NumericCoercion() {}

    static IrModule rewrite(IrModule module) {
        InferenceContext base = InferenceContext.fromModule(module);
        Map<String, IrSort.Structural> structs =
                sibarum.pontif.types.TypeCatalog.fromModule(module).structShapes();
        // Single-overload param sorts by member name — the callee side of a
        // call-argument boundary. Overloaded names abstain (which parameter sort
        // applies is the dispatcher's call, not this pass's).
        Map<String, List<IrParam>> soleOverload = new HashMap<>();
        Map<String, Boolean> overloaded = new HashMap<>();
        for (IrStmt stmt : module.statements()) {
            if (stmt instanceof IrStmt.FunctionDecl fd) {
                String key = QualifiedName.memberOf(fd.name());
                if (soleOverload.put(key, fd.params()) != null) overloaded.put(key, true);
            }
        }
        Ctx c = new Ctx(structs, soleOverload, overloaded);

        List<IrStmt> out = new ArrayList<>(module.statements().size());
        for (IrStmt stmt : module.statements()) {
            out.add(switch (stmt) {
                case IrStmt.FunctionDecl fd -> rewriteFunction(fd, base, c);
                case IrStmt.TraitImpl ti -> {
                    List<IrStmt.FunctionDecl> methods = new ArrayList<>(ti.methods().size());
                    for (IrStmt.FunctionDecl m : ti.methods()) methods.add(rewriteFunction(m, base, c));
                    List<IrStmt.FunctionDecl> attrs = new ArrayList<>(ti.attributeProducers().size());
                    for (IrStmt.FunctionDecl a : ti.attributeProducers()) attrs.add(rewriteFunction(a, base, c));
                    yield new IrStmt.TraitImpl(ti.typeName(), ti.traitName(), methods, attrs,
                            ti.typeBindings(), ti.typeParams(), ti.traitTypeArgs(), ti.origin());
                }
                default -> stmt;
            });
        }
        IrExpr main = module.main() == null ? null : rewriteExpr(module.main(), base, c);
        return new IrModule(module.name(), out, main);
    }

    /** Immutable per-module carry: struct shapes + the call-arg overload view. */
    private record Ctx(Map<String, IrSort.Structural> structs,
                       Map<String, List<IrParam>> soleOverload,
                       Map<String, Boolean> overloaded) {}

    private static IrStmt.FunctionDecl rewriteFunction(IrStmt.FunctionDecl fd, InferenceContext base, Ctx c) {
        InferenceContext ctx = base.withParams(fd.params());
        IrExpr body = rewriteExpr(fd.body(), ctx, c);
        // Return boundary: the body's tail value meets the declared return sort.
        body = coerce(body, fd.returnSort(), ctx);
        return new IrStmt.FunctionDecl(fd.name(), fd.params(), fd.returnSort(), body,
                fd.origin(), fd.topLevelLet(), fd.typeParams());
    }

    private static IrExpr rewriteExpr(IrExpr e, InferenceContext ctx, Ctx c) {
        return switch (e) {
            case IrExpr.Lit l -> l;
            case IrExpr.Dec d -> d;
            case IrExpr.Chr ch -> ch;
            case IrExpr.Str s -> s;
            case IrExpr.Bool b -> b;
            case IrExpr.Var v -> v;
            case IrExpr.SelfRef s -> s;
            case IrExpr.DispatchRef d -> d;
            case IrExpr.BinOp op -> new IrExpr.BinOp(op.op(),
                    rewriteExpr(op.left(), ctx, c), rewriteExpr(op.right(), ctx, c), op.origin());
            case IrExpr.LetIn l -> {
                IrExpr value = rewriteExpr(l.value(), ctx, c);
                IrSort declared = l.claim() != null ? l.claim() : l.declaredSort();
                value = coerce(value, declared, ctx);
                IrSort bound = declared != null ? declared : TypeSystem.standard().infer(l.value(), ctx);
                InferenceContext bodyCtx = bound != null ? ctx.withVar(l.name(), bound) : ctx;
                yield new IrExpr.LetIn(l.name(), l.declaredSort(), value,
                        rewriteExpr(l.body(), bodyCtx, c), l.origin(), l.claim());
            }
            case IrExpr.Call call -> {
                List<IrExpr> args = new ArrayList<>(call.args().size());
                String key = QualifiedName.memberOf(call.functionName());
                List<IrParam> params = c.overloaded().containsKey(key) ? null : c.soleOverload().get(key);
                for (int i = 0; i < call.args().size(); i++) {
                    IrExpr arg = rewriteExpr(call.args().get(i), ctx, c);
                    if (params != null && i < params.size() && params.get(i).sort() != null) {
                        arg = coerce(arg, params.get(i).sort(), ctx);
                    }
                    args.add(arg);
                }
                yield new IrExpr.Call(call.functionName(), args, call.origin());
            }
            case IrExpr.Lambda lam -> {
                InferenceContext bodyCtx = ctx.withParams(lam.params());
                IrExpr body = rewriteExpr(lam.body(), bodyCtx, c);
                body = coerce(body, lam.returnSort(), bodyCtx);
                yield new IrExpr.Lambda(lam.params(), lam.returnSort(), body, lam.origin());
            }
            case IrExpr.Apply app -> {
                List<IrExpr> args = new ArrayList<>(app.args().size());
                for (IrExpr a : app.args()) args.add(rewriteExpr(a, ctx, c));
                yield new IrExpr.Apply(rewriteExpr(app.fn(), ctx, c), args, app.origin());
            }
            case IrExpr.Match m -> {
                List<IrExpr.MatchBranch> bs = new ArrayList<>(m.branches().size());
                for (IrExpr.MatchBranch b : m.branches()) {
                    bs.add(new IrExpr.MatchBranch(b.pattern(), rewriteExpr(b.result(), ctx, c)));
                }
                yield new IrExpr.Match(rewriteExpr(m.scrutinee(), ctx, c), bs, m.origin());
            }
            case IrExpr.Record r -> {
                IrSort.Structural decl = r.typeName() == null ? null : c.structs().get(r.typeName());
                Map<String, IrExpr> members = new LinkedHashMap<>();
                for (Map.Entry<String, IrExpr> en : r.members().entrySet()) {
                    IrExpr member = rewriteExpr(en.getValue(), ctx, c);
                    IrSort memberSort = decl == null ? null : decl.members().get(en.getKey());
                    if (memberSort != null) member = coerce(member, memberSort, ctx);
                    members.put(en.getKey(), member);
                }
                yield new IrExpr.Record(r.typeName(), members, r.runtimeChecks(), r.origin());
            }
            case IrExpr.FieldAccess fa -> new IrExpr.FieldAccess(
                    rewriteExpr(fa.base(), ctx, c), fa.fieldName(), fa.origin());
            case IrExpr.MethodCall mc -> throw MethodResolver.unresolved(mc, "NumericCoercion");
            case IrExpr.Iterate it -> it;
            case IrExpr.Emit em -> new IrExpr.Emit(
                    rewriteExpr(em.event(), ctx, c), rewriteExpr(em.body(), ctx, c), em.origin());
            case IrExpr.Cast cast -> new IrExpr.Cast(cast.targetSort(),
                    rewriteExpr(cast.value(), ctx, c), cast.origin());
        };
    }

    /**
     * Wrap {@code value} in an {@code Int → Decimal} cast when it meets a declared
     * {@code Decimal} boundary but its own inferred sort is {@code Int}. Abstains
     * when inference is unknown (never a spurious cast) and is idempotent (a value
     * already inferring Decimal — including a literal already promoted to
     * {@code Dec} — is left alone).
     */
    private static IrExpr coerce(IrExpr value, IrSort declared, InferenceContext ctx) {
        // An anonymous shape carries its members' declared sorts, so it is a value
        // boundary like any other: recurse so `{d = 3}` promotes exactly as a struct
        // field would. A named struct's members are already coerced from the registry in
        // the Record case above; this supplies the shape the anonymous faces have no
        // registry entry for. BOTH faces — by-name ([{d:Decimal}], a literal with a null
        // typeName) and positional ([{Decimal, Int}], a literal the parser stamps
        // `_tuple` with keys `_0 .. _n`) — because a slot is a declared boundary
        // wherever it is written, and a `Decimal` slot silently holding an Int is the
        // same lie in either spelling.
        if (declared instanceof IrSort.Structural shape
                && anonymousShape(shape.name())
                && value instanceof IrExpr.Record rec
                && shape.name().equals(rec.typeName() == null ? RECORD_SENTINEL : rec.typeName())) {
            Map<String, IrExpr> members = new LinkedHashMap<>();
            boolean changed = false;
            for (Map.Entry<String, IrExpr> en : rec.members().entrySet()) {
                IrSort memberSort = shape.members().get(en.getKey());
                IrExpr member = memberSort == null
                        ? en.getValue() : coerce(en.getValue(), memberSort, ctx);
                changed |= member != en.getValue();
                members.put(en.getKey(), member);
            }
            return changed
                    ? new IrExpr.Record(rec.typeName(), members, rec.runtimeChecks(), rec.origin())
                    : value;
        }
        if (!isDecimalSort(declared)) return value;
        IrSort inferred = TypeSystem.standard().infer(value, ctx);
        if (!isIntSort(inferred)) return value;
        return new IrExpr.Cast(IrSort.named("Decimal"), value, value.origin());
    }

    /** Structural-sort name marking an anonymous BY-NAME aggregate (a record shape). */
    private static final String RECORD_SENTINEL = "_record";

    /** Structural-sort name marking an anonymous POSITIONAL aggregate (a tuple). */
    private static final String TUPLE_SENTINEL = "_tuple";

    /** The two anonymous-aggregate shapes, whose slots are declared value boundaries. */
    private static boolean anonymousShape(String name) {
        return RECORD_SENTINEL.equals(name) || TUPLE_SENTINEL.equals(name);
    }

    private static boolean isDecimalSort(IrSort sort) {
        return sort != null && "Decimal".equals(sort.baseName());
    }

    private static boolean isIntSort(IrSort sort) {
        return sort != null && "Int".equals(sort.baseName());
    }
}
