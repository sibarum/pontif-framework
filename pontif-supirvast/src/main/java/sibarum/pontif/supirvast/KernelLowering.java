package sibarum.pontif.supirvast;

import dev.supirvast.vastir.core.Buffer;
import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.Function;
import dev.supirvast.vastir.core.Region;
import dev.supirvast.vastir.core.Statement;
import dev.supirvast.vastir.tools.KernelColumn;
import dev.supirvast.vastir.tools.KernelSpec;
import dev.supirvast.vastir.type.Type;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrSort;

import java.util.ArrayList;
import java.util.List;

/**
 * Lowers a Pontif {@link IrExpr.Iterate} — the iteration construct streams compile to — into a SuperVast
 * {@code core} compute kernel ({@link KernelSpec}). This is the kernel-shaping companion to {@link ExprLowering}
 * (which lowers the scalar per-element body): {@code KernelLowering} turns the <em>structure</em> (sources →
 * input columns, the stream output → an output column, the per-element index → the GPU invocation id) into a
 * {@code main} function of the canonical data-parallel shape
 * {@code out[gid] = f(in0[gid], in1[gid], …)}, then delegates {@code f} to {@link ExprLowering}.
 *
 * <p>It consumes exactly the {@code Iterate} shape the parser produces for stream spread/zip
 * ({@code PontifParser.lowerSpreadCall}): a single {@code STREAM} output written by one wildcard arm.
 *
 * <ul>
 *   <li><b>map</b> — {@code double(&s)} / {@code &s:[x -> x*x]}: one source, the body reads the element
 *       directly ({@code Var(element)});</li>
 *   <li><b>zip</b> — {@code (&a, &b):[(x,y) -> x+y]}: N sources walked in lockstep, the body reads the
 *       i-th source via {@code element._i} ({@code FieldAccess}).</li>
 * </ul>
 *
 * Vector-add is the canonical zip kernel — the construct that motivated the GPU integration.
 *
 * <p><b>Honest scope (v1).</b> Pontif {@code Int} lowers to {@code int64} columns (two words on the wire,
 * {@link ValueMarshaller}) — no narrowing to {@code int32}, so a value can never silently overflow the
 * boundary. Anything outside the map/zip shape fails closed with a source-located {@link LoweringError}: an
 * accumulator/fan output (fold/scan/fork), a guarded/multi-arm body (filter/takeWhile), or a {@code STOP}/{@code FAN}
 * write. The act of lowering is the eligibility check. (Sources may be any expression — the runner supplies
 * their data positionally as columns; the source's identity is not part of the kernel.)
 */
public final class KernelLowering {

    private static final String OUTPUT_COLUMN = "out";

    /** Lowers an Int kernel ({@code int64} columns) — the backward-compatible default. */
    public KernelSpec lower(IrExpr.Iterate it) {
        return lower(it, Type.int64());
    }

    /**
     * Lowers a stream {@code Iterate} to a registrable kernel over {@code element}-typed columns
     * ({@code int64} for an Int kernel, {@code float32} for a Decimal kernel — the ruled lossy cast), or
     * throws {@link LoweringError}. v1 kernels are homogeneous: every column shares {@code element}.
     */
    public KernelSpec lower(IrExpr.Iterate it, Type element) {
        requireMapOrZipShape(it);
        ExprLowering exprLowering = new ExprLowering(element);

        List<IrExpr> sources = new ArrayList<>();
        sources.add(it.source());
        sources.addAll(it.coSources());

        // The single write's value is the per-element body. A tuple return (`… -> {a, b}`) is a
        // MULTI-OUTPUT kernel: one output column per tuple member (struct-of-arrays), reassembled into a
        // Stream[{…}] by the runner. A scalar return is the single-output case. Element references
        // (Var(element) for map, element._i for zip) are rewritten to the synthetic column vars.
        IrExpr body = bindElementRefs(it.arms().get(0).writes().get(0).value(), it.element(), sources.size());
        List<IrExpr> outputs = outputExprs(body);
        int nOut = outputs.size();

        // Outputs occupy slots 0..nOut-1; inputs follow at nOut..nOut+sources-1 (bindings stay contiguous).
        List<KernelColumn> columns = new ArrayList<>();
        List<Buffer> outBuffers = new ArrayList<>();
        for (int k = 0; k < nOut; k++) {
            String name = nOut == 1 ? OUTPUT_COLUMN : OUTPUT_COLUMN + k;
            columns.add(KernelColumn.output(name, k, element));
            outBuffers.add(new Buffer(name, k, element));
        }

        Expr gid = new Expr.InvocationId();
        Scope scope = Scope.empty();
        for (int i = 0; i < sources.size(); i++) {
            String name = inputColumnName(sources.get(i), i);
            int slot = nOut + i;
            columns.add(KernelColumn.input(name, slot, element));
            Buffer in = new Buffer(name, slot, element);
            // Each element reference resolves to a load of its column at the current invocation.
            scope = scope.with(columnVar(i), new Expr.BufferLoad(in, gid));
        }

        // Lower each output expression and store it to its column (out_k[gid] = f_k(in…[gid])).
        List<Statement> statements = new ArrayList<>();
        for (int k = 0; k < nOut; k++) {
            ExprLowering.Block lowered = exprLowering.lower(outputs.get(k), scope);
            statements.addAll(lowered.statements());
            statements.add(new Statement.BufferStore(outBuffers.get(k), gid, lowered.value()));
        }
        statements.add(new Statement.ReturnVoid());

        Function kernel = new Function("main",
                new Type.FunctionType(Type.VOID, List.of()),
                Region.of(statements.toArray(new Statement[0])));
        return new KernelSpec(kernel, columns);
    }

    /**
     * The kernel's output expressions: the members of a tuple return ({@code -> {a, b}}), else the
     * scalar body as a single output. Shared with {@code GpuKernelRunner} (via {@link #outputArity}) so
     * the runner marshals exactly the columns this produces.
     */
    private static List<IrExpr> outputExprs(IrExpr body) {
        if (body instanceof IrExpr.Record rec && isPositionalTuple(rec)) {
            return new ArrayList<>(rec.members().values());
        }
        return List.of(body);
    }

    /** How many output columns {@code body} lowers to: a tuple's arity, else 1 (a scalar). */
    public static int outputArity(IrExpr body) {
        return body instanceof IrExpr.Record rec && isPositionalTuple(rec) ? rec.members().size() : 1;
    }

    /** A Record whose keys are exactly {@code _0.._{n-1}} in order — a positional tuple, not a named struct. */
    private static boolean isPositionalTuple(IrExpr.Record rec) {
        if (rec.members().isEmpty()) {
            return false;
        }
        int i = 0;
        for (String key : rec.members().keySet()) {
            if (!("_" + i++).equals(key)) {
                return false;
            }
        }
        return true;
    }

    // --- shape validation (fail-closed) ----------------------------------------------------------------

    private static void requireMapOrZipShape(IrExpr.Iterate it) {
        if (it.outputs().size() != 1) {
            throw LoweringError.iterate(it,
                    "a GPU kernel lowers a single output stream; this has " + it.outputs().size()
                            + " channels (fold/scan/fork — accumulators and fan-out — are a later slice)");
        }
        IrExpr.OutputKind kind = it.outputs().get(0).kind();
        if (kind != IrExpr.OutputKind.STREAM) {
            throw LoweringError.iterate(it,
                    "the output channel is " + kind + "; only a STREAM channel (map/zip) lowers in v1 "
                            + "(ACCUMULATOR=fold/scan, KEYED/REWRITE — later slices)");
        }
        if (it.arms().size() != 1) {
            throw LoweringError.iterate(it,
                    "a GPU kernel body is a single unconditional transform; this has " + it.arms().size()
                            + " arms (guarded iteration — filter/takeWhile — is a later slice)");
        }
        IrExpr.Arm arm = it.arms().get(0);
        if (!isWildcard(arm.pattern())) {
            throw LoweringError.iterate(it,
                    "the body is guarded by a pattern; only an unconditional (wildcard) transform lowers in v1 "
                            + "(element guards — takeWhile/filter — are a later slice)");
        }
        if (arm.writes().size() != 1) {
            throw LoweringError.iterate(it,
                    "the body performs " + arm.writes().size() + " writes; a map/zip kernel writes its one "
                            + "output exactly once");
        }
        IrExpr.Write write = arm.writes().get(0);
        if (IrExpr.Write.FAN.equals(write.output())) {
            throw LoweringError.iterate(it, "fan-out (fork) writes are a later slice");
        }
        if (IrExpr.Write.STOP.equals(write.output())) {
            throw LoweringError.iterate(it, "a stop disposition (takeWhile) has no data-parallel kernel form");
        }
        if (write.key() != null) {
            throw LoweringError.iterate(it, "keyed (grouping) writes have no v1 kernel form");
        }
    }

    private static boolean isWildcard(IrSort pattern) {
        return pattern instanceof IrSort.Named n && "_".equals(n.name());
    }

    // --- element-reference binding -------------------------------------------------------------------

    /** The synthetic scope name a column's per-element load is bound under. */
    private static String columnVar(int i) {
        return "$gpu_in_" + i;
    }

    private static String inputColumnName(IrExpr source, int index) {
        // The name is only the column's LABEL — data binds positionally (the column's slot + the
        // synthetic columnVar the body's element-refs are rewritten to), and the source expression is
        // never lowered (only counted). So a named stream labels its column with its name for
        // readability; any other source (a let-bound or literal stream — the common real-program case)
        // gets a positional label. The source's identity is immaterial to the kernel.
        if (source instanceof IrExpr.Var v) {
            return v.name();
        }
        return "in" + index;
    }

    /**
     * Rewrites references to the iteration element into the synthetic column variables bound in scope:
     * {@code element._i} (zip) and a bare {@code element} (single-source map) become {@code columnVar(i)}.
     * Recurses through the pure compound shapes the body can take ({@code BinOp}, {@code LetIn}); any other
     * node is returned unchanged for {@link ExprLowering} to lower or reject — so an unsupported construct
     * still produces its own precise {@link LoweringError}.
     */
    private static IrExpr bindElementRefs(IrExpr e, String element, int sourceCount) {
        return switch (e) {
            // element._i  →  the i-th column's load (zip).
            case IrExpr.FieldAccess fa when fa.base() instanceof IrExpr.Var v && v.name().equals(element) -> {
                int i = positionalIndex(fa.fieldName());
                if (i < 0 || i >= sourceCount) {
                    throw LoweringError.unsupportedExpr(fa, "Field access '." + fa.fieldName() + "'",
                            "the zip element has " + sourceCount + " positions; '." + fa.fieldName()
                                    + "' is not one of them");
                }
                yield new IrExpr.Var(columnVar(i), fa.origin());
            }
            // bare element  →  the sole column's load (single-source map).
            case IrExpr.Var v when v.name().equals(element) -> new IrExpr.Var(columnVar(0), v.origin());
            case IrExpr.BinOp op -> new IrExpr.BinOp(op.op(),
                    bindElementRefs(op.left(), element, sourceCount),
                    bindElementRefs(op.right(), element, sourceCount), op.origin());
            case IrExpr.LetIn let -> new IrExpr.LetIn(let.name(), let.declaredSort(),
                    bindElementRefs(let.value(), element, sourceCount),
                    bindElementRefs(let.body(), element, sourceCount), let.origin(), let.claim());
            // A tuple return (multi-output): rewrite element refs inside each member.
            case IrExpr.Record rec -> {
                java.util.LinkedHashMap<String, IrExpr> members = new java.util.LinkedHashMap<>();
                rec.members().forEach((key, v) -> members.put(key, bindElementRefs(v, element, sourceCount)));
                yield new IrExpr.Record(rec.typeName(), members, rec.runtimeChecks(), rec.origin());
            }
            default -> e;
        };
    }

    /** {@code "_3"} → 3; -1 when the field is not a positional tuple member. */
    private static int positionalIndex(String field) {
        if (field.length() < 2 || field.charAt(0) != '_') {
            return -1;
        }
        try {
            return Integer.parseInt(field.substring(1));
        } catch (NumberFormatException notPositional) {
            return -1;
        }
    }
}
