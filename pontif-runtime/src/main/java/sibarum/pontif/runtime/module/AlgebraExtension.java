package sibarum.pontif.runtime.module;

import sibarum.pontif.ast.record.RecordValue;
import sibarum.pontif.core.Origin;
import sibarum.pontif.core.QualifiedName;
import sibarum.pontif.core.symbolic.RuntimeCheckException;
import sibarum.pontif.core.types.StringValue;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.NativeCalls;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The builtin <b>algebra extension</b> ({@code pontif.algebra}) — the runtime side of
 * "differential programming". It reflects an {@code assign proof f:Algebraic} function's
 * body into an inspectable, first-class {@code AlgExpr} AST and evaluates that AST.
 *
 * <ul>
 *   <li>{@code astOf(f)} — takes a first-class function VALUE (a metareference
 *       {@code $f[Decimal]} or a fragment) and returns its expression tree as an
 *       {@code AlgExpr}. Nested calls to other algebraic functions are <b>inlined</b>
 *       (finite: recursion is banned by the compile gate). The compile-time
 *       {@code Algebraic} claim is the static guarantee; this walk is the mechanism and
 *       fails closed on any non-algebraic node it meets.</li>
 *   <li>{@code eval(e, x)} — evaluates an {@code AlgExpr} over a single variable {@code x}
 *       to a {@code Decimal}, in exact {@code BigDecimal} arithmetic (DECIMAL128 division).</li>
 * </ul>
 *
 * <p>The AST is an ordinary Pontif trait union, so a program can {@code match} on it and
 * write its own evaluator / differentiator — that is the whole point (docs/metatypes.md).
 * Pure-JDK, so installed by default like {@link MathExtExtension}.
 */
public final class AlgebraExtension implements Extension {

    public static final AlgebraExtension INSTANCE = new AlgebraExtension();

    private AlgebraExtension() {}

    @Override
    public String moduleName() {
        return "pontif.algebra";
    }

    @Override
    public String pontifSource() {
        return SOURCE;
    }

    @Override
    public Map<String, NativeCalls.NativeCall> calls() {
        Map<String, NativeCalls.NativeCall> m = new LinkedHashMap<>();
        m.put("astOf", (args, ctx) -> {
            Object fn = args.get(0);
            NativeCalls.ReflectedFunction reflected = ctx.reflectFunction(fn);
            if (reflected == null) {
                throw new RuntimeCheckException(
                        "astOf: argument is not a reflectable function value: " + fn, Origin.NONE);
            }
            Map<String, RecordValue> env = new LinkedHashMap<>();
            for (var p : reflected.params()) {
                env.put(p.name(), paramNode(p.name()));
            }
            return walk(reflected.body(), env, ctx);
        });
        m.put("eval", (args, ctx) -> evalNode(args.get(0), decimal(args.get(1))));
        return m;
    }

    // --- IR body -> AlgExpr AST ------------------------------------------------

    private static RecordValue walk(IrExpr e, Map<String, RecordValue> env, NativeCalls.Context ctx) {
        return switch (e) {
            case IrExpr.Lit l -> constNode(BigDecimal.valueOf(l.value()));
            case IrExpr.Dec d -> constNode(d.value());
            case IrExpr.Var v -> {
                RecordValue bound = env.get(v.name());
                if (bound == null) {
                    throw new RuntimeCheckException(
                            "astOf: unbound name '" + v.name() + "' in algebraic body", v.origin());
                }
                yield bound;
            }
            case IrExpr.Cast c -> walk(c.value(), env, ctx);  // numeric cast is transparent to the AST
            case IrExpr.BinOp op -> switch (op.op()) {
                case ADD -> binary("Add", "left", op.left(), "right", op.right(), env, ctx);
                case SUB -> binary("Sub", "left", op.left(), "right", op.right(), env, ctx);
                case MUL -> binary("Mul", "left", op.left(), "right", op.right(), env, ctx);
                case DIV -> binary("Div", "left", op.left(), "right", op.right(), env, ctx);
                case POW -> binary("Pow", "base", op.left(), "exponent", op.right(), env, ctx);
                default -> throw new RuntimeCheckException(
                        "astOf: non-algebraic operator '" + op.op() + "'", op.origin());
            };
            case IrExpr.LetIn let -> {
                RecordValue value = walk(let.value(), env, ctx);
                Map<String, RecordValue> extended = new LinkedHashMap<>(env);
                extended.put(let.name(), value);
                yield walk(let.body(), extended, ctx);
            }
            case IrExpr.Call call -> {
                NativeCalls.ReflectedFunction callee =
                        ctx.reflectFunctionByName(call.functionName(), call.args().size());
                if (callee == null) {
                    throw new RuntimeCheckException(
                            "astOf: cannot reflect nested call '" + call.functionName()
                                    + "' — is it algebraic?", call.origin());
                }
                Map<String, RecordValue> inner = new LinkedHashMap<>();
                for (int i = 0; i < callee.params().size(); i++) {
                    inner.put(callee.params().get(i).name(), walk(call.args().get(i), env, ctx));
                }
                yield walk(callee.body(), inner, ctx);  // inline (finite: no recursion)
            }
            default -> throw new RuntimeCheckException(
                    "astOf: non-algebraic construct (" + e.getClass().getSimpleName() + ")",
                    e.origin());
        };
    }

    /**
     * AST node structs are minted with their fully-qualified nominal
     * ({@code pontif.algebra/Add}) — the wire form a cross-module-constructed value
     * carries, so a user's {@code match [Add(...)]} and {@code eval}'s {@code AlgExpr}
     * trait dispatch both recognize them (bare {@code "Add"} would be treated as an
     * unrelated same-module type). {@link QualifiedName} owns the wire form.
     */
    private static final String MODULE = "pontif.algebra";

    private static String qn(String node) {
        return MODULE + "/" + node;
    }

    private static RecordValue binary(
            String type, String aName, IrExpr a, String bName, IrExpr b,
            Map<String, RecordValue> env, NativeCalls.Context ctx) {
        Map<String, Object> members = new LinkedHashMap<>();
        members.put(aName, walk(a, env, ctx));
        members.put(bName, walk(b, env, ctx));
        return new RecordValue(qn(type), members);
    }

    private static RecordValue constNode(BigDecimal value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", value);
        return new RecordValue(qn("Const"), m);
    }

    private static RecordValue paramNode(String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", new StringValue(name));
        return new RecordValue(qn("Param"), m);
    }

    // --- AlgExpr AST evaluation ------------------------------------------------

    private static BigDecimal evalNode(Object node, BigDecimal x) {
        if (!(node instanceof RecordValue r) || r.typeName() == null) {
            throw new RuntimeCheckException("eval: not an AlgExpr node: " + node, Origin.NONE);
        }
        return switch (QualifiedName.memberOf(r.typeName())) {
            case "Const" -> decimal(r.members().get("value"));
            case "Param" -> x;
            case "Add" -> evalNode(r.members().get("left"), x)
                    .add(evalNode(r.members().get("right"), x));
            case "Sub" -> evalNode(r.members().get("left"), x)
                    .subtract(evalNode(r.members().get("right"), x));
            case "Mul" -> evalNode(r.members().get("left"), x)
                    .multiply(evalNode(r.members().get("right"), x));
            case "Div" -> evalNode(r.members().get("left"), x)
                    .divide(evalNode(r.members().get("right"), x), MathContext.DECIMAL128);
            case "Pow" -> pow(evalNode(r.members().get("base"), x),
                    evalNode(r.members().get("exponent"), x));
            default -> throw new RuntimeCheckException(
                    "eval: unknown AlgExpr node '" + r.typeName() + "'", Origin.NONE);
        };
    }

    private static BigDecimal pow(BigDecimal base, BigDecimal exp) {
        try {
            int e = exp.intValueExact();
            if (e >= 0) {
                return base.pow(e, MathContext.DECIMAL128);
            }
            return BigDecimal.ONE.divide(base.pow(-e, MathContext.DECIMAL128), MathContext.DECIMAL128);
        } catch (ArithmeticException notInteger) {
            return BigDecimal.valueOf(Math.pow(base.doubleValue(), exp.doubleValue()));
        }
    }

    private static BigDecimal decimal(Object v) {
        if (v instanceof BigDecimal d) return d;
        if (v instanceof Long l) return BigDecimal.valueOf(l);
        if (v instanceof Integer i) return BigDecimal.valueOf(i.longValue());
        throw new RuntimeCheckException("eval: expected a Decimal, got " + v, Origin.NONE);
    }

    private static final String SOURCE = """
            exports @.{AlgExpr, Const, Param, Add, Sub, Mul, Div, Pow, astOf, eval}

            trait AlgExpr{}

            struct Const(value:Decimal)
            struct Param(name:String)
            struct Add(left:AlgExpr, right:AlgExpr)
            struct Sub(left:AlgExpr, right:AlgExpr)
            struct Mul(left:AlgExpr, right:AlgExpr)
            struct Div(left:AlgExpr, right:AlgExpr)
            struct Pow(base:AlgExpr, exponent:AlgExpr)

            assign trait Const:AlgExpr{}
            assign trait Param:AlgExpr{}
            assign trait Add:AlgExpr{}
            assign trait Sub:AlgExpr{}
            assign trait Mul:AlgExpr{}
            assign trait Div:AlgExpr{}
            assign trait Pow:AlgExpr{}

            # astOf reflects an algebraic function VALUE into its AST; eval evaluates the
            # AST over one variable. Both bodies are placeholders — a resolved call runs
            # this extension's Java object (AlgebraExtension.calls) instead.
            function astOf(f:[Dispatch(Decimal):Decimal]):AlgExpr -> Const(0.0)
            function eval(e:AlgExpr, x:Decimal):Decimal -> 0.0

            0
            """;
}
