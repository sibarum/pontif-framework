package sibarum.pontif.shape;

import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.core.types.StringValue;
import sibarum.pontif.ir.CompiledModule;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrParam;
import sibarum.pontif.ir.NativeCalls;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lowers a {@code pontif.shape} {@code SdfShape} value to a GLSL {@code float map(vec3 p)} for
 * the renderer's raymarch layer (docs/sdf-glsl.md) — a real GPU sphere-traced surface instead of
 * the sampled 24³ glow of {@code previewGradientField}.
 *
 * <p><b>The general body-inlining lowerer (slice 1).</b> It reads each shape's <em>actual</em>
 * {@code distance} method IR (via {@link NativeCalls.Context#methodImpl}) and inlines it into one
 * GLSL expression by partial evaluation of the runtime shape tree:
 * <ul>
 *   <li>{@code this.<field>} → the value's concrete field (a float literal for a {@code Decimal});
 *   <li>{@code distanceAt(this.<child>, ex, ey, ez)} → the child's {@code distance}, lowered
 *       recursively at the transformed point {@code (ex, ey, ez)};
 *   <li>a {@code pontif.math} call → the same-named GLSL intrinsic; {@code let} → textual inline;
 *       arithmetic → the GLSL operator.
 * </ul>
 * Because it reads the real {@code distance} code it cannot silently diverge from the Pontif
 * formula (no-lie), and it handles user-defined {@code assign trait X:SdfShape} for free. Anything
 * outside the GLSL-expressible subset is a source-of-truth-preserving hard error ({@link Unsupported}),
 * never a wrong shader. Emitting GLSL text (not shipping a function) is what lets a user SDF cross
 * to the GPU at all — dissolving the "user SDF can only be sampled" limit of docs/shapes.md.
 *
 * <p>Decimal→float is a rendering <em>view</em>, exempt from the no-lie law (James): the image
 * approximates, it does not assert exact geometry.
 */
public final class SdfGlsl {

    private SdfGlsl() {}

    /** A shape construct that has no GLSL lowering — reported, never mis-compiled. */
    static final class Unsupported extends RuntimeException {
        Unsupported(String message) { super("sdfMap: " + message); }
    }

    /** {@code pontif.math} functions whose GLSL builtin is the same name (scalar-safe). */
    private static final Set<String> MATH = Set.of(
            "sqrt", "abs", "sign", "floor", "ceil", "fract", "mod", "pow", "exp", "log",
            "exp2", "log2", "min", "max", "clamp", "mix", "step", "smoothstep",
            "sin", "cos", "tan", "asin", "acos", "atan", "radians", "degrees");

    /** Native {@code sdfMap(s:[SdfShape]):String} — the full {@code float map(vec3 p){…}}. */
    public static Object map(List<Object> args, NativeCalls.Context ctx) {
        if (args.isEmpty() || !(args.get(0) instanceof RecordValue shape)) {
            throw new Unsupported("expects a shape value");
        }
        String body = new Lowering(ctx).shape(shape, "p.x", "p.y", "p.z");
        return new StringValue("float map(vec3 p){ return " + body + "; }");
    }

    /** One lowering pass, carrying the interpreter {@link NativeCalls.Context} for method-IR reads. */
    private static final class Lowering {
        private final NativeCalls.Context ctx;

        Lowering(NativeCalls.Context ctx) { this.ctx = ctx; }

        /** The GLSL signed-distance expression for {@code shape} at the point {@code (px,py,pz)}. */
        String shape(RecordValue shape, String px, String py, String pz) {
            CompiledModule.CompiledFunction fn = ctx.methodImpl(shape, "distance");
            if (fn == null) {
                throw new Unsupported("no `distance` impl for '" + bare(shape.typeName())
                        + "' (the SDF→GLSL lowerer needs the interpreter Context — run via the"
                        + " interpreter engine)");
            }
            List<IrParam> ps = fn.params();          // [this, x, y, z]
            Env env = new Env(shape, ps.get(0).name());
            env.bind(ps.get(1).name(), px);
            env.bind(ps.get(2).name(), py);
            env.bind(ps.get(3).name(), pz);
            return expr(fn.body(), env);
        }

        private String expr(IrExpr e, Env env) {
            return switch (e) {
                case IrExpr.Dec d -> glslFloat(d.value().doubleValue());
                case IrExpr.Lit l -> glslFloat((double) l.value());
                case IrExpr.Var v -> {
                    String g = env.vars.get(v.name());
                    if (g == null) throw new Unsupported("unbound variable '" + v.name()
                            + "' in a distance body");
                    yield g;
                }
                case IrExpr.BinOp b -> binOp(b, env);
                case IrExpr.LetIn let -> {
                    env.bind(let.name(), expr(let.value(), env));   // pure → inline textually
                    yield expr(let.body(), env);
                }
                case IrExpr.FieldAccess fa -> glslFloat(scalarField(fa, env));
                case IrExpr.Call c -> call(c, env);
                // An implicit Int→Decimal coercion (NumericCoercion) is identity in
                // the GLSL subset — every scalar is a float — so lower the operand.
                case IrExpr.Cast cast when "Decimal".equals(cast.targetSort().baseName()) ->
                        expr(cast.value(), env);
                default -> throw new Unsupported("unsupported expression "
                        + e.getClass().getSimpleName() + " in a distance body (the GLSL subset is"
                        + " arithmetic, pontif.math calls, let, this.<field>, and distanceAt)");
            };
        }

        private String binOp(IrExpr.BinOp b, Env env) {
            String l = expr(b.left(), env), r = expr(b.right(), env);
            return switch (b.op()) {
                case ADD -> "(" + l + " + " + r + ")";
                case SUB -> "(" + l + " - " + r + ")";
                case MUL -> "(" + l + " * " + r + ")";
                case DIV -> "(" + l + " / " + r + ")";
                case MOD -> "mod(" + l + ", " + r + ")";     // GLSL % is integer-only; float → mod()
                case POW -> "pow(" + l + ", " + r + ")";
                default -> throw new Unsupported("operator " + b.op() + " has no GLSL lowering in a"
                        + " distance body (comparisons/logic arrive with match support)");
            };
        }

        private String call(IrExpr.Call c, Env env) {
            String fn = bare(c.functionName());
            if (fn.equals("distanceAt")) {                 // the recursion: distanceAt(child, x, y, z)
                RecordValue child = childShape(c.args().get(0), env);
                return shape(child, expr(c.args().get(1), env),
                        expr(c.args().get(2), env), expr(c.args().get(3), env));
            }
            if (MATH.contains(fn)) {
                StringBuilder sb = new StringBuilder(fn).append("(");
                for (int i = 0; i < c.args().size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(expr(c.args().get(i), env));
                }
                return sb.append(")").toString();
            }
            throw new Unsupported("function '" + fn + "' has no GLSL lowering in a distance body"
                    + " (supported: distanceAt + pontif.math " + MATH + ")");
        }

        /** A {@code this.<field>} whose value is a scalar (Decimal/Int) → its number. */
        private double scalarField(IrExpr.FieldAccess fa, Env env) {
            Object v = fieldOwner(fa.base(), env).members().get(fa.fieldName());
            if (v instanceof BigDecimal d) return d.doubleValue();
            if (v instanceof Long l) return l;
            throw new Unsupported("field '" + fa.fieldName() + "' is not a scalar constant"
                    + " (only scalar fields become GLSL literals; child shapes are reached via"
                    + " distanceAt)");
        }

        /** The child shape value passed to {@code distanceAt} — a {@code this.<field>} holding one. */
        private RecordValue childShape(IrExpr arg, Env env) {
            if (arg instanceof IrExpr.FieldAccess fa
                    && fieldOwner(fa.base(), env).members().get(fa.fieldName()) instanceof RecordValue child) {
                return child;
            }
            throw new Unsupported("distanceAt's shape argument must be a `this.<child>` field");
        }

        /** The record whose field is being read — {@code this} resolves to the current shape value. */
        private RecordValue fieldOwner(IrExpr base, Env env) {
            if (base instanceof IrExpr.Var v && v.name().equals(env.self)) return env.value;
            throw new Unsupported("unsupported field-access base "
                    + base.getClass().getSimpleName() + " (only `this.<field>` is supported)");
        }
    }

    /** Lowering scope for one shape: its value (for {@code this.<field>}) + GLSL var bindings. */
    private static final class Env {
        final RecordValue value;
        final String self;                              // the receiver param name (e.g. "this")
        final Map<String, String> vars = new HashMap<>();

        Env(RecordValue value, String self) { this.value = value; this.self = self; }

        void bind(String name, String glsl) { vars.put(name, glsl); }
    }

    /** Formats a constant as a GLSL float literal (always carrying a decimal point). */
    private static String glslFloat(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            throw new Unsupported("non-finite constant " + v);
        }
        return Double.toString(v);   // e.g. "1.0", "0.8" — a valid GLSL float literal
    }

    private static String bare(String name) {
        if (name == null) return "";
        int slash = name.lastIndexOf('/');
        return slash < 0 ? name : name.substring(slash + 1);
    }
}
