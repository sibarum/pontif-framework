package sibarum.pontif.supirvast;

import dev.supirvast.vastir.core.CoreModule;
import dev.supirvast.vastir.core.EntryPoint;
import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.Function;
import dev.supirvast.vastir.core.InterfaceVar;
import dev.supirvast.vastir.core.PushConstants;
import dev.supirvast.vastir.core.Region;
import dev.supirvast.vastir.core.ShaderStage;
import dev.supirvast.vastir.core.Statement;
import dev.supirvast.vastir.lower.CoreToSpirv;
import dev.supirvast.vastir.tools.Fullscreen;
import dev.supirvast.vastir.type.Type;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrParam;
import sibarum.pontif.ir.IrSort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lowers a Pontif shader function into a SuperVast {@code core} <em>fragment</em> shader — the graphics sibling
 * of {@link KernelLowering} (which shapes a compute kernel). Its coordinate parameters bind to the screen-space
 * {@code uv} the vertex stage passes down (there is no {@code gl_FragCoord} builtin in {@code core}); an optional
 * {@code Frame} parameter binds to the per-frame uniform block ({@link Fullscreen#standardUniforms}) the host
 * pushes each frame, so {@code f.time} / {@code f.resolution} lower to push-constant reads. The body is delegated
 * to {@link ExprLowering} and the scalar it produces is written as a grayscale color.
 *
 * <p>Parameter shapes: {@code shade(uv:Vec2)} or {@code shade(x, y)} for the coordinates, optionally followed by
 * {@code f:Frame}. The body lowers as a {@code float32} kernel, reusing {@code ExprLowering}'s shader vocabulary
 * (vectors-as-records, {@code pontif.math} intrinsics, arithmetic, {@code let}).
 */
public final class ScalarFieldFragment {

    private static final Type.Float F32 = Type.float32();
    private static final Type.Vector VEC2 = new Type.Vector(F32, 2);
    private static final Type.Vector VEC4 = new Type.Vector(F32, 4);

    /** The scope prefix a rewritten {@code frame.<field>} access binds under. */
    private static final String FRAME_PREFIX = "$frame_";
    /** {@code Frame} field → its member index in {@link Fullscreen#standardUniforms} (resolution@0, time@1). */
    private static final Map<String, Integer> FRAME_MEMBER = Map.of("resolution", 0, "time", 1);

    private ScalarFieldFragment() {
    }

    /** Lowers a shader function given only coordinate parameter names (no {@code Frame}) — a convenience. */
    public static byte[] lower(List<String> coordNames, IrExpr body) {
        List<IrParam> params = new ArrayList<>();
        for (String name : coordNames) {
            params.add(new IrParam(name, IrSort.named("Decimal")));
        }
        return lowerParams(params, body);
    }

    /**
     * Lowers {@code body} under {@code params} to fragment SPIR-V. Coordinate params bind to {@code uv} (a single
     * {@code Vec2} to the whole varying, or two scalars to its components); a {@code Frame} param binds to the
     * per-frame uniform block.
     */
    public static byte[] lowerParams(List<IrParam> params, IrExpr body) {
        InterfaceVar vUv = InterfaceVar.input("vUv", 0, VEC2);
        InterfaceVar fragColor = InterfaceVar.output("fragColor", 0, VEC4);
        Expr uv = new Expr.InterfaceRead(vUv);

        List<IrParam> coords = new ArrayList<>();
        IrParam frame = null;
        for (IrParam p : params) {
            if (isNamed(p.sort(), "Frame")) {
                frame = p;
            } else {
                coords.add(p);
            }
        }

        Scope scope = switch (coords.size()) {
            case 1 -> Scope.empty().with(coords.get(0).name(), uv);   // shade(uv:Vec2)
            case 2 -> Scope.empty()                                   // shade(x, y)
                    .with(coords.get(0).name(), new Expr.VectorExtract(uv, 0))
                    .with(coords.get(1).name(), new Expr.VectorExtract(uv, 1));
            default -> throw new IllegalArgumentException(
                    "a 2D field takes a single Vec2 parameter or two scalar (x, y) parameters, got " + coords);
        };

        IrExpr shaderBody = body;
        if (frame != null) {
            // Rewrite `f.<field>` accesses to synthetic vars, then bind those to the uniform block's members.
            shaderBody = bindFrameFields(body, frame.name());
            PushConstants uniforms = Fullscreen.standardUniforms();
            for (Map.Entry<String, Integer> e : FRAME_MEMBER.entrySet()) {
                scope = scope.with(FRAME_PREFIX + e.getKey(), uniforms.read(e.getValue()));
            }
        }

        ExprLowering.Block field = new ExprLowering(F32).lower(shaderBody, scope);
        Expr v = field.value();
        Expr color = new Expr.VectorConstruct(VEC4, List.of(v, v, v, new Expr.ConstFloat(F32, 1.0)));

        List<Statement> statements = new ArrayList<>(field.statements());
        statements.add(new Statement.InterfaceWrite(fragColor, color));
        statements.add(new Statement.ReturnVoid());

        Function main = new Function("main", new Type.FunctionType(Type.VOID, List.of()),
                Region.of(statements.toArray(new Statement[0])));
        return new CoreToSpirv()
                .lower(new CoreModule().addEntryPoint(EntryPoint.of(main, ShaderStage.FRAGMENT)))
                .toByteArray();
    }

    /** True when {@code sort} is the named type {@code typeName} (bare, ignoring any module qualifier). */
    private static boolean isNamed(IrSort sort, String typeName) {
        return sort instanceof IrSort.Named n && typeName.equals(bare(n.name()));
    }

    /**
     * Rewrites {@code frame.<field>} field accesses into synthetic variables ({@code $frame_<field>}) that the
     * caller binds to the uniform block's members — the {@code KernelLowering.bindElementRefs} pattern. Recurses
     * through the pure shapes a shader body takes; any other node passes through for {@link ExprLowering}.
     */
    private static IrExpr bindFrameFields(IrExpr e, String frameName) {
        return switch (e) {
            case IrExpr.FieldAccess fa when fa.base() instanceof IrExpr.Var v && v.name().equals(frameName) ->
                    new IrExpr.Var(FRAME_PREFIX + fa.fieldName(), fa.origin());
            case IrExpr.FieldAccess fa ->
                    new IrExpr.FieldAccess(bindFrameFields(fa.base(), frameName), fa.fieldName(), fa.origin());
            case IrExpr.BinOp b -> new IrExpr.BinOp(b.op(),
                    bindFrameFields(b.left(), frameName), bindFrameFields(b.right(), frameName), b.origin());
            case IrExpr.LetIn let -> new IrExpr.LetIn(let.name(), let.declaredSort(),
                    bindFrameFields(let.value(), frameName), bindFrameFields(let.body(), frameName),
                    let.origin(), let.claim());
            case IrExpr.Call c -> {
                List<IrExpr> args = new ArrayList<>();
                for (IrExpr a : c.args()) {
                    args.add(bindFrameFields(a, frameName));
                }
                yield new IrExpr.Call(c.functionName(), args, c.origin());
            }
            case IrExpr.Record r -> {
                Map<String, IrExpr> members = new LinkedHashMap<>();
                r.members().forEach((k, val) -> members.put(k, bindFrameFields(val, frameName)));
                yield new IrExpr.Record(r.typeName(), members, r.runtimeChecks(), r.origin());
            }
            default -> e;
        };
    }

    private static String bare(String name) {
        if (name == null) {
            return "";
        }
        int slash = name.lastIndexOf('/');
        return slash < 0 ? name : name.substring(slash + 1);
    }
}
