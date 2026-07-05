package sibarum.pontif.supirvast;

import sibarum.pontif.core.Origin;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrSort;

/**
 * The witness for a Pontif construct that cannot be lowered to a SuperVast GPU kernel — the embodiment of the
 * guiding principle that <em>attempting to lower is the validation</em>. Lowering is a total function over the
 * supported (Int-only, v1) subset; the first node outside it throws one of these, carrying the offending
 * construct's {@link Origin} and a concrete reason, so a front end can point at the source rather than emit a
 * silently wrong kernel.
 *
 * <p>The static factories are the single catalogue of what v1 does not support, mirroring how
 * {@code BuiltinOperators}/{@code OperatorCompletenessCheck} centralize operator availability — every
 * unsupported {@code IrExpr}/{@code IrSort} variant and {@code Op} routes through exactly one factory here, so
 * coverage is auditable in one place.
 */
public final class LoweringError extends RuntimeException {

    private final transient Origin origin;
    private final String construct;
    private final String reason;

    private LoweringError(Origin origin, String construct, String reason) {
        super(construct + " cannot be lowered to a GPU kernel: " + reason
                + " (at " + (origin == null ? Origin.NONE : origin) + ")");
        this.origin = origin == null ? Origin.NONE : origin;
        this.construct = construct;
        this.reason = reason;
    }

    public Origin origin() {
        return origin;
    }

    /** The Pontif construct that could not be lowered, e.g. {@code "Decimal literal"}. */
    public String construct() {
        return construct;
    }

    /** Why it could not be lowered, in front-end-renderable prose. */
    public String reason() {
        return reason;
    }

    // --- expression-level constructs -------------------------------------------------------------------

    static LoweringError unsupportedExpr(IrExpr expr, String construct, String reason) {
        return new LoweringError(expr.origin(), construct, reason);
    }

    static LoweringError decimalLiteral(IrExpr.Dec dec) {
        return unsupportedExpr(dec, "Decimal literal",
                "the GPU is IEEE floating-point only while Decimal is exact-rational; v1 lowers Int only, so "
                        + "no Decimal crosses the boundary (the float story is settled before any shader work)");
    }

    static LoweringError charLiteral(IrExpr.Chr chr) {
        return unsupportedExpr(chr, "Char literal", "v1 lowers Int and Bool only; Char has no GPU representation yet");
    }

    static LoweringError stringLiteral(IrExpr.Str str) {
        return unsupportedExpr(str, "String literal",
                "strings are a Char collection with no GPU buffer representation in v1");
    }

    static LoweringError selfRef(IrExpr.SelfRef self) {
        return unsupportedExpr(self, "Self-reference (@)",
                "refinement-self is a Pontif-level value with no GPU kernel meaning");
    }

    static LoweringError record(IrExpr.Record record) {
        String name = record.typeName() == null ? "anonymous record" : "struct '" + record.typeName() + "'";
        return unsupportedExpr(record, "Record literal (" + name + ")",
                "aggregates are not yet decomposed into kernel columns; v1 carries scalar Int/Bool only");
    }

    static LoweringError fieldAccess(IrExpr.FieldAccess access) {
        return unsupportedExpr(access, "Field access '." + access.fieldName() + "'",
                "struct fields are not yet projected onto kernel columns in v1");
    }

    static LoweringError methodCall(IrExpr.MethodCall call) {
        return unsupportedExpr(call, "Method call '." + call.methodName() + "(...)'",
                "instance-method dispatch is not lowered in v1 (and should already be resolved to a Call)");
    }

    static LoweringError lambda(IrExpr.Lambda lambda) {
        return unsupportedExpr(lambda, "Lambda",
                "first-class functions need defunctionalization, which v1 does not perform");
    }

    static LoweringError apply(IrExpr.Apply apply) {
        return unsupportedExpr(apply, "Apply (higher-order call)",
                "applying a computed function value needs defunctionalization, which v1 does not perform");
    }

    static LoweringError dispatchRef(IrExpr.DispatchRef ref) {
        return unsupportedExpr(ref, "Metareference '" + ref.functionName() + "[...]'",
                "a reified dispatch site is a Pontif-level value with no GPU kernel representation");
    }

    static LoweringError cast(IrExpr.Cast cast) {
        return unsupportedExpr(cast, "Cast",
                "explicit coercions are not lowered in v1 (the Int-only subset has no cross-type crossings)");
    }

    static LoweringError matchExpr(IrExpr.Match match, String detail) {
        return unsupportedExpr(match, "Match", detail);
    }

    static LoweringError iterate(IrExpr.Iterate iterate, String detail) {
        return unsupportedExpr(iterate, "Iterate", detail);
    }

    static LoweringError unboundVariable(IrExpr.Var var) {
        return unsupportedExpr(var, "Variable '" + var.name() + "'",
                "not bound in the kernel's scope (no matching let-binding, parameter, or buffer)");
    }

    static LoweringError powOperator(IrExpr.BinOp op) {
        return unsupportedExpr(op, "Operator '^' (power)",
                "there is no integer power in the GPU core; raise it via repeated multiplication instead");
    }

    static LoweringError approxOperator(IrExpr.BinOp op) {
        return unsupportedExpr(op, "Operator '~' (approximate-equals)",
                "approximate comparison is floating-point only and has no Int meaning");
    }

    // --- sort-level constructs -------------------------------------------------------------------------

    static LoweringError unsupportedSort(IrSort sort, String construct, String reason) {
        return new LoweringError(sort.origin(), construct, reason);
    }

    static LoweringError unsupportedScalar(IrSort sort, String name) {
        return unsupportedSort(sort, "Type '" + name + "'",
                "v1 lowers only Int (→ int64) and Bool; '" + name + "' has no GPU scalar representation");
    }

    static LoweringError aggregateSort(IrSort sort, String kind) {
        return unsupportedSort(sort, kind,
                "aggregate and contract sorts are not lowered in v1; kernels carry scalar Int/Bool columns only");
    }
}
