package sibarum.pontif.runtime.module;

import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.core.Origin;
import sibarum.pontif.core.QualifiedName;
import sibarum.pontif.core.symbolic.RuntimeCheckException;
import sibarum.pontif.core.types.StringValue;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.NativeCalls;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

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
 *       to a {@code Decimal}, in exact {@code BigDecimal} arithmetic (DECIMAL128 division). A
 *       fractional power ({@code Pow} with a rational exponent — the way a square root is
 *       written, {@code Pow(x, 0.5)}) is exact when the result is a terminating decimal (perfect
 *       roots: {@code 4^0.5 = 2}, {@code 8^(1/3) = 2}) and correctly rounded to DECIMAL128
 *       otherwise; the exponent is read as an exact rational from the tree, and an even root of a
 *       negative number fails closed (no {@code double}, no silent NaN).</li>
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
        // eval: single-variable convenience — bind EVERY Param to the one point `x`.
        m.put("eval", (args, ctx) -> {
            BigDecimal x = decimal(args.get(1));
            return evalNode(args.get(0), name -> x);
        });
        // evalAt: N-variable — bind each Param by NAME from a point dict `{x = …, y = …}`
        // (the AST already carries a distinct Param per argument, by name). This is the
        // multi-argument surface; the binding is a dynamically-typed record (param sort `_`).
        m.put("evalAt", (args, ctx) -> {
            Object at = args.get(1);
            if (!(at instanceof RecordValue point)) {
                throw new RuntimeCheckException(
                        "evalAt: the binding must be a record `{name = value, …}`, got " + at,
                        Origin.NONE);
            }
            return evalNode(args.get(0), name -> {
                Object v = point.members().get(name);
                if (v == null) {
                    throw new RuntimeCheckException(
                            "evalAt: no binding for variable '" + name + "' in " + point, Origin.NONE);
                }
                return decimal(v);
            });
        });
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
                // A reflectable math primitive (sqrt, sin, …) translates DIRECTLY to an AST node
                // — it is never inlined, because its native body is a placeholder. Everything else
                // is inlined by reflecting its (algebraic) body.
                RecordValue prim = primitive(call.functionName(), call.args(), env, ctx);
                if (prim != null) yield prim;
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

    // --- reflectable math primitives -------------------------------------------
    //
    // Selected `pontif.math` functions are "algebraic primitives": a call to one is admitted by
    // AlgebraicCheck (via {@link #ALGEBRAIC_PRIMITIVES}) and translated here to an AST node,
    // instead of inlining the function's placeholder native body. The power family maps onto the
    // existing exact {@code Pow} node; the transcendentals get dedicated unary nodes evaluated in
    // double precision (see {@link #dbl}).

    /** The qualified `pontif.math` names AlgebraicCheck admits as algebraic primitives. */
    public static final Set<String> ALGEBRAIC_PRIMITIVES = Set.of(
            "pontif.math/sqrt", "pontif.math/inverseSqrt", "pontif.math/exp2", "pontif.math/pow",
            "pontif.math/sin", "pontif.math/cos", "pontif.math/tan", "pontif.math/exp", "pontif.math/log");

    /** The AST node a math-primitive call reflects to, or {@code null} if the call isn't a primitive. */
    private static RecordValue primitive(
            String name, List<IrExpr> args, Map<String, RecordValue> env, NativeCalls.Context ctx) {
        return switch (name) {
            // Power family — exact, reusing the Pow node (eval handles fractional powers exactly).
            case "pontif.math/sqrt"        -> powNode(walk(args.get(0), env, ctx), constNode(new BigDecimal("0.5")));
            case "pontif.math/inverseSqrt" -> powNode(walk(args.get(0), env, ctx), constNode(new BigDecimal("-0.5")));
            case "pontif.math/exp2"        -> powNode(constNode(BigDecimal.valueOf(2)), walk(args.get(0), env, ctx));
            case "pontif.math/pow"         -> powNode(walk(args.get(0), env, ctx), walk(args.get(1), env, ctx));
            // Transcendentals — dedicated nodes, evaluated in double precision.
            case "pontif.math/sin" -> unaryNode("Sin", args.get(0), env, ctx);
            case "pontif.math/cos" -> unaryNode("Cos", args.get(0), env, ctx);
            case "pontif.math/tan" -> unaryNode("Tan", args.get(0), env, ctx);
            case "pontif.math/exp" -> unaryNode("Exp", args.get(0), env, ctx);
            case "pontif.math/log" -> unaryNode("Log", args.get(0), env, ctx);
            default -> null;
        };
    }

    private static RecordValue powNode(RecordValue base, RecordValue exponent) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("base", base);
        m.put("exponent", exponent);
        return new RecordValue(qn("Pow"), m);
    }

    private static RecordValue unaryNode(
            String type, IrExpr arg, Map<String, RecordValue> env, NativeCalls.Context ctx) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("arg", walk(arg, env, ctx));
        return new RecordValue(qn(type), m);
    }

    // --- AlgExpr AST evaluation ------------------------------------------------

    private static BigDecimal evalNode(
            Object node, java.util.function.Function<String, BigDecimal> env) {
        if (!(node instanceof RecordValue r) || r.typeName() == null) {
            throw new RuntimeCheckException("eval: not an AlgExpr node: " + node, Origin.NONE);
        }
        return switch (QualifiedName.memberOf(r.typeName())) {
            case "Const" -> decimal(r.members().get("value"));
            // Each Param carries the source parameter's NAME; the environment binds it to a
            // point. A single-variable eval binds every name to one value; evalAt binds by name.
            case "Param" -> env.apply(paramName(r));
            case "Add" -> evalNode(r.members().get("left"), env)
                    .add(evalNode(r.members().get("right"), env));
            case "Sub" -> evalNode(r.members().get("left"), env)
                    .subtract(evalNode(r.members().get("right"), env));
            case "Mul" -> evalNode(r.members().get("left"), env)
                    .multiply(evalNode(r.members().get("right"), env));
            case "Div" -> evalNode(r.members().get("left"), env)
                    .divide(evalNode(r.members().get("right"), env), MathContext.DECIMAL128);
            // The exponent is read as an EXACT rational straight from the node tree (so
            // `Pow(x, Div(1,3))` keeps 1/3 exactly, not a truncated decimal) — the base^(p/q)
            // evaluation is then exact when the result is a terminating decimal (perfect roots)
            // and correctly rounded to DECIMAL128 otherwise. Never `Math.pow` (double).
            case "Pow" -> powRat(evalNode(r.members().get("base"), env),
                    ratOf(r.members().get("exponent"), env));
            // Transcendentals are evaluated in double precision (by ruling); `dbl` emits a Decimal
            // with an honest number of significant digits, not 34 that would overstate it.
            case "Sin" -> dbl(Math.sin(evalNode(r.members().get("arg"), env).doubleValue()));
            case "Cos" -> dbl(Math.cos(evalNode(r.members().get("arg"), env).doubleValue()));
            case "Tan" -> dbl(Math.tan(evalNode(r.members().get("arg"), env).doubleValue()));
            case "Exp" -> dbl(Math.exp(evalNode(r.members().get("arg"), env).doubleValue()));
            case "Log" -> dbl(Math.log(evalNode(r.members().get("arg"), env).doubleValue()));
            default -> throw new RuntimeCheckException(
                    "eval: unknown AlgExpr node '" + r.typeName() + "'", Origin.NONE);
        };
    }

    /** The source parameter name a {@code Param} node carries. */
    private static String paramName(RecordValue param) {
        Object n = param.members().get("name");
        return n instanceof StringValue s ? s.content() : String.valueOf(n);
    }

    // --- exact / precision-honest fractional powers ---------------------------
    //
    // base^(num/den): exact when the true value is a terminating decimal (integer powers,
    // perfect roots like 4^0.5=2, 8^(1/3)=2, 2.25^0.5=1.5), and correctly rounded to
    // DECIMAL128 when it is irrational or a non-terminating rational. No `double` anywhere:
    // the exponent is an exact rational and the root is taken in exact integer / BigDecimal
    // arithmetic, so results are accurate to the full claimed (DECIMAL128) precision.

    /** The precision `eval` claims for an inexact result (matches the Div path). */
    private static final MathContext CLAIMED = MathContext.DECIMAL128;
    /** Working precision for Newton iteration — guard digits above the claimed precision. */
    private static final MathContext WORK =
            new MathContext(CLAIMED.getPrecision() + 8, RoundingMode.HALF_EVEN);
    /** Largest root index we evaluate exactly; beyond this we fail closed rather than lie. */
    private static final int MAX_ROOT = 1_000_000;

    /** An exact rational, kept in lowest terms with a positive denominator. */
    private record Rational(BigInteger num, BigInteger den) {
        static Rational reduce(BigInteger n, BigInteger d) {
            if (d.signum() == 0) {
                throw new RuntimeCheckException("eval: division by zero in an exponent", Origin.NONE);
            }
            if (d.signum() < 0) { n = n.negate(); d = d.negate(); }
            BigInteger g = n.gcd(d);
            return g.signum() == 0 ? new Rational(n, d) : new Rational(n.divide(g), d.divide(g));
        }
        static Rational of(BigDecimal v) {
            BigInteger u = v.unscaledValue();
            int s = v.scale();
            return s >= 0 ? reduce(u, BigInteger.TEN.pow(s))
                          : reduce(u.multiply(BigInteger.TEN.pow(-s)), BigInteger.ONE);
        }
        Rational mul(Rational o) { return reduce(num.multiply(o.num), den.multiply(o.den)); }
        Rational div(Rational o) { return reduce(num.multiply(o.den), den.multiply(o.num)); }
        Rational add(Rational o) {
            return reduce(num.multiply(o.den).add(o.num.multiply(den)), den.multiply(o.den));
        }
        Rational sub(Rational o) {
            return reduce(num.multiply(o.den).subtract(o.num.multiply(den)), den.multiply(o.den));
        }
    }

    /**
     * Read a numeric sub-expression as an EXACT rational from the node tree. Arithmetic over
     * integer/decimal {@code Const}s and {@code Div}/{@code Mul}/{@code Add}/{@code Sub} stays
     * exact (so {@code Div(1,3)} is 1/3, not a truncated decimal); anything else falls back to
     * the node's evaluated {@code BigDecimal} (itself an exact decimal fraction).
     */
    private static Rational ratOf(Object node, Function<String, BigDecimal> env) {
        if (!(node instanceof RecordValue r) || r.typeName() == null) {
            return Rational.of(evalNode(node, env));
        }
        Map<String, Object> m = r.members();
        return switch (QualifiedName.memberOf(r.typeName())) {
            case "Const" -> Rational.of(decimal(m.get("value")));
            case "Param" -> Rational.of(env.apply(paramName(r)));
            case "Div" -> ratOf(m.get("left"), env).div(ratOf(m.get("right"), env));
            case "Mul" -> ratOf(m.get("left"), env).mul(ratOf(m.get("right"), env));
            case "Add" -> ratOf(m.get("left"), env).add(ratOf(m.get("right"), env));
            case "Sub" -> ratOf(m.get("left"), env).sub(ratOf(m.get("right"), env));
            default -> Rational.of(evalNode(node, env));
        };
    }

    /** {@code base} raised to the exact rational {@code e}. */
    private static BigDecimal powRat(BigDecimal base, Rational e) {
        BigInteger num = e.num(), den = e.den();          // den > 0, lowest terms
        if (num.signum() == 0) return BigDecimal.ONE;      // x^0 = 1 (incl. 0^0 = 1, by convention)
        int n = intRoot(den);
        int baseSign = base.signum();
        if (baseSign == 0) {
            if (num.signum() < 0) {
                throw new RuntimeCheckException("eval: zero raised to a negative power", Origin.NONE);
            }
            return BigDecimal.ZERO;
        }
        int p = intRoot(num.abs());
        BigDecimal powered = base.abs().pow(p);            // exact integer power of the magnitude
        if (num.signum() < 0) {                            // reciprocal: exact if terminating
            try { powered = BigDecimal.ONE.divide(powered); }
            catch (ArithmeticException nonTerminating) { powered = BigDecimal.ONE.divide(powered, CLAIMED); }
        }
        BigDecimal magnitude = rootOf(powered, n);
        if (baseSign < 0) {
            if (n % 2 == 0) {
                throw new RuntimeCheckException(
                        "eval: even root of a negative number is not real (base " + base
                                + ", exponent " + num + "/" + den + ")", Origin.NONE);
            }
            if (num.testBit(0)) magnitude = magnitude.negate();   // odd numerator keeps the sign
        }
        return magnitude;
    }

    /** The {@code n}-th root of a non-negative {@code v}: exact if a terminating decimal, else DECIMAL128. */
    private static BigDecimal rootOf(BigDecimal v, int n) {
        if (n == 1 || v.signum() == 0) return v;
        // Exact rational A/B of v, then integer n-th roots of numerator and denominator.
        BigInteger A = v.unscaledValue();
        int s = v.scale();
        BigInteger B;
        if (s >= 0) { B = BigInteger.TEN.pow(s); }
        else { A = A.multiply(BigInteger.TEN.pow(-s)); B = BigInteger.ONE; }
        Rational red = Rational.reduce(A, B);
        BigInteger[] rn = iroot(red.num(), n);
        BigInteger[] rd = iroot(red.den(), n);
        if (rn[1].signum() != 0 && rd[1].signum() != 0) {         // both perfect n-th powers
            BigDecimal exact = new BigDecimal(rn[0]);
            BigDecimal denom = new BigDecimal(rd[0]);
            try { return exact.divide(denom); }                   // exact terminating decimal
            catch (ArithmeticException nonTerminating) { return exact.divide(denom, CLAIMED); }
        }
        return nthRoot(v, n);                                      // irrational — correctly rounded
    }

    /** Floor integer {@code n}-th root of {@code a >= 0}, with an exactness flag as element [1] (1/0). */
    private static BigInteger[] iroot(BigInteger a, int n) {
        if (a.signum() == 0) return new BigInteger[]{BigInteger.ZERO, BigInteger.ONE};
        if (n == 1) return new BigInteger[]{a, BigInteger.ONE};
        BigInteger x = BigInteger.valueOf((long) Math.max(1.0, Math.pow(a.doubleValue(), 1.0 / n)));
        BigInteger N = BigInteger.valueOf(n), n1 = BigInteger.valueOf(n - 1);
        while (true) {                                            // integer Newton descent
            BigInteger next = n1.multiply(x).add(a.divide(x.pow(n - 1))).divide(N);
            if (next.compareTo(x) >= 0) break;
            x = next;
        }
        while (x.pow(n).compareTo(a) > 0) x = x.subtract(BigInteger.ONE);
        while (x.add(BigInteger.ONE).pow(n).compareTo(a) <= 0) x = x.add(BigInteger.ONE);
        return new BigInteger[]{x, x.pow(n).equals(a) ? BigInteger.ONE : BigInteger.ZERO};
    }

    /** The {@code n}-th root of {@code v > 0} correctly rounded to the claimed precision (Newton). */
    private static BigDecimal nthRoot(BigDecimal v, int n) {
        if (n == 2) return v.sqrt(CLAIMED);                       // JDK built-in, correctly rounded
        double seed = Math.pow(v.doubleValue(), 1.0 / n);
        BigDecimal x = (Double.isFinite(seed) && seed > 0) ? BigDecimal.valueOf(seed) : BigDecimal.ONE;
        BigDecimal N = BigDecimal.valueOf(n), n1 = BigDecimal.valueOf(n - 1);
        for (int i = 0; i < 200; i++) {
            BigDecimal next = n1.multiply(x, WORK)
                    .add(v.divide(x.pow(n - 1, WORK), WORK), WORK)
                    .divide(N, WORK);
            if (x.subtract(next).abs().compareTo(next.ulp().movePointRight(1)) < 0) { x = next; break; }
            x = next;
        }
        return x.round(CLAIMED);
    }

    /** An index that must fit a plain {@code int} root; beyond {@link #MAX_ROOT} we fail closed. */
    private static int intRoot(BigInteger v) {
        if (v.bitLength() > 31 || v.compareTo(BigInteger.valueOf(MAX_ROOT)) > 0) {
            throw new RuntimeCheckException(
                    "eval: exponent numerator/denominator " + v + " is too large for exact evaluation "
                            + "(limit " + MAX_ROOT + ")", Origin.NONE);
        }
        return v.intValueExact();
    }

    private static BigDecimal decimal(Object v) {
        if (v instanceof BigDecimal d) return d;
        if (v instanceof Long l) return BigDecimal.valueOf(l);
        if (v instanceof Integer i) return BigDecimal.valueOf(i.longValue());
        throw new RuntimeCheckException("eval: expected a Decimal, got " + v, Origin.NONE);
    }

    /**
     * The significant digits an IEEE-754 {@code double} honestly carries (DBL_DIG = 15 — the most
     * that survive a decimal→double→decimal round trip). Transcendentals evaluate in double
     * precision (by ruling), so their result is rounded to this many significant digits: the
     * emitted {@code Decimal} neither overstates precision (34 DECIMAL128 digits) nor exposes the
     * 17-digit round-trip artifact.
     */
    private static final MathContext DOUBLE_SIG = new MathContext(15, RoundingMode.HALF_EVEN);

    /** A double result as a Decimal with honest significant digits; a non-finite value fails closed. */
    private static BigDecimal dbl(double d) {
        if (!Double.isFinite(d)) {
            throw new RuntimeCheckException(
                    "eval: transcendental produced a non-finite result (" + d
                            + ") — outside its real domain", Origin.NONE);
        }
        BigDecimal r = new BigDecimal(d, DOUBLE_SIG).stripTrailingZeros();
        return r.scale() < 0 ? r.setScale(0) : r;   // keep integers as "2", not "2E+0"
    }

    private static final String SOURCE = """
            exports @.{AlgExpr, Const, Param, Add, Sub, Mul, Div, Pow,
                       Sin, Cos, Tan, Exp, Log, Algebraic, eval, evalAt}

            trait AlgExpr{}

            struct Const(value:Decimal)
            struct Param(name:String)
            struct Add(left:AlgExpr, right:AlgExpr)
            struct Sub(left:AlgExpr, right:AlgExpr)
            struct Mul(left:AlgExpr, right:AlgExpr)
            struct Div(left:AlgExpr, right:AlgExpr)
            struct Pow(base:AlgExpr, exponent:AlgExpr)
            # Transcendental primitives — reflected from the pontif.math functions of the same
            # name (sqrt/pow/exp2/inverseSqrt reflect to Pow instead). Evaluated in double precision.
            struct Sin(arg:AlgExpr)
            struct Cos(arg:AlgExpr)
            struct Tan(arg:AlgExpr)
            struct Exp(arg:AlgExpr)
            struct Log(arg:AlgExpr)

            assign trait Const:AlgExpr{}
            assign trait Param:AlgExpr{}
            assign trait Add:AlgExpr{}
            assign trait Sub:AlgExpr{}
            assign trait Mul:AlgExpr{}
            assign trait Div:AlgExpr{}
            assign trait Pow:AlgExpr{}
            assign trait Sin:AlgExpr{}
            assign trait Cos:AlgExpr{}
            assign trait Tan:AlgExpr{}
            assign trait Exp:AlgExpr{}
            assign trait Log:AlgExpr{}

            # Algebraic: the trait a metareference proven algebraic is-a. Its `ast` attribute
            # IS the AST surface — `$f[Decimal].ast` reads it (docs/dispatch-method-elimination
            # .md E2). The `eval` METHOD evaluates the reference at a point — `$f[Decimal].eval(x)`
            # — so the metareference behaves as a first-class differentiable object. A
            # metareference $f[…] narrows to the builtin nominal AlgebraicDispatch when f carries
            # `assign proof f:Algebraic`, else the plain Dispatch (no `.ast`, no `.eval`).
            trait Algebraic{ast:AlgExpr, eval(x:Decimal):Decimal}

            # AlgebraicDispatch provides both members by reflecting the referent function:
            # `.ast` via astOf; `.eval(x)` by evaluating that AST at x (the free `eval` over the
            # AST, reached by its distinct 2-arg signature). `this` is the metareference itself.
            assign trait AlgebraicDispatch:Algebraic {
              ast:AlgExpr -> astOf(this)
              eval(x:Decimal):Decimal -> eval(this.ast, x)
            }

            # astOf reflects an algebraic function VALUE into its AST — NON-EXPORTED, so
            # `$f[Decimal].ast`/`.eval` are the only surface. The free `eval` evaluates the AST
            # over one variable (the `.eval` method delegates to it). Both bodies are
            # placeholders — a resolved call runs this extension's Java object
            # (AlgebraExtension.calls) instead. astOf's param is Algebraic, so a non-algebraic
            # reference is rejected at the type level.
            function astOf(f:Algebraic):AlgExpr -> Const(0.0)
            function eval(e:AlgExpr, x:Decimal):Decimal -> 0.0
            # evalAt binds each variable by NAME from a point dict `{x = …, y = …}` — the
            # N-argument surface (`eval` is the one-variable convenience). The binding is
            # dynamically typed (`_`); a statically-typed point is the next slice.
            function evalAt(e:AlgExpr, at:_):Decimal -> 0.0

            0
            """;
}
