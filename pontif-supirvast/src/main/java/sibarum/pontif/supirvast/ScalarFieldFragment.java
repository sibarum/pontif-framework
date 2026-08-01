package sibarum.pontif.supirvast;

import dev.supirvast.vastir.core.CoreModule;
import dev.supirvast.vastir.core.EntryPoint;
import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.Function;
import dev.supirvast.vastir.core.InterfaceVar;
import dev.supirvast.vastir.core.Region;
import dev.supirvast.vastir.core.ShaderStage;
import dev.supirvast.vastir.core.Statement;
import dev.supirvast.vastir.lower.CoreToSpirv;
import dev.supirvast.vastir.type.Type;
import sibarum.pontif.ir.IrExpr;

import java.util.ArrayList;
import java.util.List;

/**
 * Lowers a Pontif scalar expression of two coordinates {@code (x, y)} into a SuperVast {@code core}
 * <em>fragment</em> shader — the graphics sibling of {@link KernelLowering} (which shapes a compute kernel).
 * Where {@code KernelLowering} binds the per-element sources to buffer loads at the invocation id, this binds
 * the two named parameters to the screen-space {@code (u, v)} the vertex stage passes down (there is no
 * {@code gl_FragCoord} builtin in {@code core}), delegates the body to {@link ExprLowering}, and writes the
 * scalar it produces as a grayscale color.
 *
 * <p>The body is lowered as a <b>float</b> kernel ({@code float32}), reusing exactly the scalar subset
 * {@code ExprLowering} already supports — arithmetic and {@code let}. This is the first
 * Pontif&nbsp;→&nbsp;{@code core}&nbsp;→&nbsp;SPIR-V fragment: a 2D field, no vectors in the Pontif source,
 * no {@code gl_FragCoord}. Richer fields (math intrinsics, then SDF shapes) grow {@code ExprLowering}, not this
 * wrapper.
 */
public final class ScalarFieldFragment {

    private static final Type.Float F32 = Type.float32();
    private static final Type.Vector VEC2 = new Type.Vector(F32, 2);
    private static final Type.Vector VEC4 = new Type.Vector(F32, 4);

    private ScalarFieldFragment() {
    }

    /**
     * Lowers {@code body} (a Pontif expression in the two coordinate parameters named by {@code paramNames}) to
     * fragment SPIR-V. The parameters bind to the {@code vUv} varying's two components; the scalar result is
     * written to {@code fragColor} as {@code vec4(v, v, v, 1)}.
     */
    public static byte[] lower(List<String> paramNames, IrExpr body) {
        if (paramNames.size() != 2) {
            throw new IllegalArgumentException(
                    "a 2D scalar field takes exactly two coordinate parameters, got " + paramNames);
        }
        InterfaceVar vUv = InterfaceVar.input("vUv", 0, VEC2);
        InterfaceVar fragColor = InterfaceVar.output("fragColor", 0, VEC4);

        Expr uv = new Expr.InterfaceRead(vUv);
        Scope scope = Scope.empty()
                .with(paramNames.get(0), new Expr.VectorExtract(uv, 0))
                .with(paramNames.get(1), new Expr.VectorExtract(uv, 1));

        ExprLowering.Block field = new ExprLowering(F32).lower(body, scope);
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
}
