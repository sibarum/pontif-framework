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
import java.util.function.BinaryOperator;
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

    /**
     * Thrown at a point where the expression is genuinely <b>undefined</b> — a pole (division by
     * zero), an even root of a negative, {@code 0} to a negative power, or a non-finite
     * transcendental. This is deliberately <em>distinct</em> from a structural/type error
     * (not an {@code AlgExpr} node, an unbound name): those are real bugs and must still abort.
     * {@code eval} lets a domain exception propagate (fail-closed, the strict evaluator);
     * {@code evalSafe} catches <em>only</em> this type and turns it into the {@code Undefined}
     * value, so a plot can sample across an asymptote without crashing — fail-closed surfaced as
     * a value, never as a fabricated number.
     */
    static final class AlgebraicDomainException extends RuntimeCheckException {
        AlgebraicDomainException(String message) { super(message, Origin.NONE); }
    }

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
        // evalSafe: a TOTAL single-variable eval. Where `eval` fails closed at a domain gap (a
        // pole, an even root of a negative, a non-finite transcendental), `evalSafe` returns the
        // `Undefined` sentinel instead — so a plot can walk across an asymptote without aborting.
        // Only genuine domain gaps are caught; a structural error (not an AlgExpr, unbound name)
        // is a real bug and still propagates. `x` is read outside the try for the same reason.
        m.put("evalSafe", (args, ctx) -> {
            BigDecimal x = decimal(args.get(1));
            try {
                return evalNode(args.get(0), name -> x);
            } catch (AlgebraicDomainException undefinedHere) {
                return new RecordValue(qn("Undefined"), new LinkedHashMap<>());
            }
        });
        // evalInterval: the INTERVAL evaluator — a sound enclosure of { f(x) : x in [lo, hi] }, the
        // reliable-plotting substrate (docs/reliable-plotting.md slice 1). It is the generalisation
        // of evalSafe from a point to a whole pixel column: returns Interval(lo,hi) | Unbounded |
        // Undefined. Inexact endpoints round OUTWARD, so the result is ALWAYS a true superset of the
        // real range — the no-lie law at the pixel (never miss the curve, never place it falsely).
        m.put("evalInterval", (args, ctx) ->
                encToRecord(encOf(args.get(0), decimal(args.get(1)), decimal(args.get(2)))));
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
            case "Div" -> divChecked(evalNode(r.members().get("left"), env),
                    evalNode(r.members().get("right"), env));
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

    /**
     * {@code a / b}, but a zero divisor is a genuine domain gap (a pole), not a Java
     * {@code ArithmeticException} — surfaced as an {@link AlgebraicDomainException} so
     * {@code evalSafe} can render it as {@code Undefined}. Never a fabricated quotient.
     */
    private static BigDecimal divChecked(BigDecimal a, BigDecimal b) {
        if (b.signum() == 0) {
            throw new AlgebraicDomainException("eval: division by zero (" + a + " / 0)");
        }
        return a.divide(b, MathContext.DECIMAL128);
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
                throw new AlgebraicDomainException("eval: zero raised to a negative power");
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
                throw new AlgebraicDomainException(
                        "eval: even root of a negative number is not real (base " + base
                                + ", exponent " + num + "/" + den + ")");
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

    // --- interval arithmetic (docs/reliable-plotting.md, slice 1) --------------
    //
    // UNIT 1 — interval algebra. A three-way enclosure: a bounded [lo, hi], an Unbounded column
    // (a ±inf spill — a pole or dense feature), or Undefined (no real value ANYWHERE on the
    // column). Every operation is a SOUND over-approximation — the returned enclosure provably
    // contains the true range — and inexact endpoints round OUTWARD, so a plot built on this can
    // never miss the curve nor place it where it isn't. This unit knows nothing of the AlgExpr AST.

    private enum EncKind { BOUNDED, UNBOUNDED, UNDEFINED }

    private record Enc(EncKind kind, BigDecimal lo, BigDecimal hi) {
        static final Enc UNBOUNDED = new Enc(EncKind.UNBOUNDED, null, null);
        static final Enc UNDEFINED = new Enc(EncKind.UNDEFINED, null, null);
        static Enc of(BigDecimal lo, BigDecimal hi) { return new Enc(EncKind.BOUNDED, lo, hi); }
        boolean straddlesZero() {
            return kind == EncKind.BOUNDED && lo.signum() <= 0 && hi.signum() >= 0;
        }
    }

    /** Division rounding for outward-rounded quotient endpoints (toward −∞ / +∞ respectively). */
    private static final MathContext DIV_DOWN = new MathContext(34, RoundingMode.FLOOR);
    private static final MathContext DIV_UP = new MathContext(34, RoundingMode.CEILING);
    /**
     * Soundness margin for the double-backed transcendentals (their error is ~1e-16 relative). A
     * generous 1e-12 relative+absolute over-widening — invisible at pixel scale, and it guarantees
     * the enclosure stays a true superset despite double rounding.
     */
    private static final BigDecimal MARGIN_REL = new BigDecimal("1e-12");
    private static final BigDecimal MARGIN_ABS = new BigDecimal("1e-12");
    private static final BigDecimal NEG_ONE = BigDecimal.ONE.negate();

    private static BigDecimal outward(BigDecimal v, boolean up) {
        BigDecimal margin = v.abs().multiply(MARGIN_REL).add(MARGIN_ABS);
        return up ? v.add(margin) : v.subtract(margin);
    }

    private static BigDecimal minBD(BigDecimal a, BigDecimal b) { return a.compareTo(b) <= 0 ? a : b; }
    private static BigDecimal maxBD(BigDecimal a, BigDecimal b) { return a.compareTo(b) >= 0 ? a : b; }

    /** A binary interval op with the shared propagation: Undefined wins, then Unbounded, then the op. */
    private static Enc binOp(Enc a, Enc b, BinaryOperator<Enc> op) {
        if (a.kind() == EncKind.UNDEFINED || b.kind() == EncKind.UNDEFINED) return Enc.UNDEFINED;
        if (a.kind() == EncKind.UNBOUNDED || b.kind() == EncKind.UNBOUNDED) return Enc.UNBOUNDED;
        return op.apply(a, b);
    }

    private static Enc iAdd(Enc a, Enc b) {
        return binOp(a, b, (x, y) -> Enc.of(x.lo().add(y.lo()), x.hi().add(y.hi())));
    }

    private static Enc iSub(Enc a, Enc b) {
        return binOp(a, b, (x, y) -> Enc.of(x.lo().subtract(y.hi()), x.hi().subtract(y.lo())));
    }

    private static Enc iMul(Enc a, Enc b) {
        return binOp(a, b, (x, y) -> {                 // min/max of the four endpoint products (exact)
            BigDecimal p1 = x.lo().multiply(y.lo()), p2 = x.lo().multiply(y.hi());
            BigDecimal p3 = x.hi().multiply(y.lo()), p4 = x.hi().multiply(y.hi());
            return Enc.of(minBD(minBD(p1, p2), minBD(p3, p4)), maxBD(maxBD(p1, p2), maxBD(p3, p4)));
        });
    }

    private static Enc iDiv(Enc a, Enc b) {
        return binOp(a, b, (x, y) -> {
            if (y.straddlesZero()) return Enc.UNBOUNDED;   // THE pole — a divisor range containing 0
            BigDecimal lo = null, hi = null;
            BigDecimal[] num = {x.lo(), x.hi()}, den = {y.lo(), y.hi()};
            for (BigDecimal p : num) {
                for (BigDecimal q : den) {
                    BigDecimal down = p.divide(q, DIV_DOWN), up = p.divide(q, DIV_UP);
                    lo = lo == null ? down : minBD(lo, down);
                    hi = hi == null ? up : maxBD(hi, up);
                }
            }
            return Enc.of(lo, hi);
        });
    }

    private static Enc iIntPow(Enc b, int n) {
        if (n == 0) return Enc.of(BigDecimal.ONE, BigDecimal.ONE);
        if (n < 0) return iDiv(Enc.of(BigDecimal.ONE, BigDecimal.ONE), iIntPow(b, -n)); // 1/xⁿ; pole→Unb
        BigDecimal lo = b.lo(), hi = b.hi();
        if (n % 2 == 0) {                              // even: U-shaped
            if (lo.signum() >= 0) return Enc.of(lo.pow(n), hi.pow(n));
            if (hi.signum() <= 0) return Enc.of(hi.pow(n), lo.pow(n));
            return Enc.of(BigDecimal.ZERO, maxBD(lo.abs().pow(n), hi.abs().pow(n)));
        }
        return Enc.of(lo.pow(n), hi.pow(n));           // odd: monotone increasing
    }

    private static Enc iFracPow(Enc b, BigDecimal exp) {
        Rational r = Rational.of(exp);
        boolean evenRoot = !r.den().testBit(0);        // even denominator ⇒ needs a non-negative base
        BigDecimal lo = b.lo(), hi = b.hi();
        if (evenRoot) {
            if (hi.signum() < 0) return Enc.UNDEFINED;             // wholly out of domain
            if (lo.signum() < 0) lo = BigDecimal.ZERO;             // clamp to the defined part
        }
        try {
            if (exp.signum() >= 0) {                    // increasing where defined
                BigDecimal p = outward(powRat(lo, r), false), q = outward(powRat(hi, r), true);
                return Enc.of(minBD(p, q), maxBD(p, q));
            }
            if (lo.signum() == 0 || b.straddlesZero()) return Enc.UNBOUNDED;  // pole at 0 for a neg power
            BigDecimal p = outward(powRat(hi, r), false), q = outward(powRat(lo, r), true);
            return Enc.of(minBD(p, q), maxBD(p, q));
        } catch (AlgebraicDomainException edge) {
            return Enc.UNBOUNDED;   // unexpected domain edge → paint the column (sound), never a false gap
        }
    }

    private static Enc iExp(Enc a) {
        if (a.kind() != EncKind.BOUNDED) return a.kind() == EncKind.UNDEFINED ? Enc.UNDEFINED : Enc.UNBOUNDED;
        double ehi = Math.exp(a.hi().doubleValue());
        if (!Double.isFinite(ehi)) return Enc.UNBOUNDED;           // overflow → unbounded above
        double elo = Math.exp(a.lo().doubleValue());
        return Enc.of(outward(BigDecimal.valueOf(elo), false), outward(BigDecimal.valueOf(ehi), true));
    }

    private static Enc iLog(Enc a) {
        if (a.kind() != EncKind.BOUNDED) return a.kind() == EncKind.UNDEFINED ? Enc.UNDEFINED : Enc.UNBOUNDED;
        if (a.hi().signum() <= 0) return Enc.UNDEFINED;            // wholly non-positive → out of domain
        if (a.lo().signum() <= 0) return Enc.UNBOUNDED;            // touches 0 → log → −∞
        return Enc.of(outward(BigDecimal.valueOf(Math.log(a.lo().doubleValue())), false),
                      outward(BigDecimal.valueOf(Math.log(a.hi().doubleValue())), true));
    }

    private static Enc iSinCos(Enc a, boolean sin) {
        if (a.kind() == EncKind.UNDEFINED) return Enc.UNDEFINED;
        if (a.kind() == EncKind.UNBOUNDED) return Enc.of(NEG_ONE, BigDecimal.ONE);  // bounded regardless
        double lo = a.lo().doubleValue(), hi = a.hi().doubleValue();
        if (hi - lo >= 2 * Math.PI) return Enc.of(NEG_ONE, BigDecimal.ONE);         // ≥ full period
        double flo = sin ? Math.sin(lo) : Math.cos(lo);
        double fhi = sin ? Math.sin(hi) : Math.cos(hi);
        double mn = Math.min(flo, fhi), mx = Math.max(flo, fhi);
        double maxAt = sin ? Math.PI / 2 : 0.0;        // where the function attains +1
        double minAt = sin ? -Math.PI / 2 : Math.PI;   // where it attains −1
        if (containsCongruent(lo, hi, maxAt, 2 * Math.PI)) mx = 1.0;
        if (containsCongruent(lo, hi, minAt, 2 * Math.PI)) mn = -1.0;
        return Enc.of(maxBD(NEG_ONE, outward(BigDecimal.valueOf(mn), false)),
                      minBD(BigDecimal.ONE, outward(BigDecimal.valueOf(mx), true)));
    }

    private static Enc iTan(Enc a) {
        if (a.kind() != EncKind.BOUNDED) return a.kind() == EncKind.UNDEFINED ? Enc.UNDEFINED : Enc.UNBOUNDED;
        double lo = a.lo().doubleValue(), hi = a.hi().doubleValue();
        if (hi - lo >= Math.PI) return Enc.UNBOUNDED;                          // spans a full period
        if (containsCongruent(lo, hi, Math.PI / 2, Math.PI)) return Enc.UNBOUNDED;  // a pole in the column
        double tlo = Math.tan(lo), thi = Math.tan(hi);                         // one branch → increasing
        return Enc.of(outward(BigDecimal.valueOf(Math.min(tlo, thi)), false),
                      outward(BigDecimal.valueOf(Math.max(tlo, thi)), true));
    }

    /** Does {@code [lo, hi]} contain a point {@code base + k·period} for some integer {@code k}? */
    private static boolean containsCongruent(double lo, double hi, double base, double period) {
        return Math.ceil((lo - base) / period) <= Math.floor((hi - base) / period);
    }

    // UNIT 2 — the AlgExpr walk. Maps a node tree onto UNIT 1, over the column [xlo, xhi]. Mirrors
    // evalNode's structure; the interval algebra above carries all the soundness. `Param` is the
    // column itself; anything else recurses and combines. A constant exponent routes to the exact
    // integer power or the outward-rounded rational power; a symbolic exponent is conservatively
    // Unbounded (rare, and sound — it paints the column rather than bounding it falsely).

    private static Enc encOf(Object node, BigDecimal xlo, BigDecimal xhi) {
        if (!(node instanceof RecordValue r) || r.typeName() == null) {
            throw new RuntimeCheckException("evalInterval: not an AlgExpr node: " + node, Origin.NONE);
        }
        Map<String, Object> m = r.members();
        return switch (QualifiedName.memberOf(r.typeName())) {
            case "Const" -> { BigDecimal v = decimal(m.get("value")); yield Enc.of(v, v); }
            case "Param" -> Enc.of(xlo, xhi);
            case "Add" -> iAdd(encOf(m.get("left"), xlo, xhi), encOf(m.get("right"), xlo, xhi));
            case "Sub" -> iSub(encOf(m.get("left"), xlo, xhi), encOf(m.get("right"), xlo, xhi));
            case "Mul" -> iMul(encOf(m.get("left"), xlo, xhi), encOf(m.get("right"), xlo, xhi));
            case "Div" -> iDiv(encOf(m.get("left"), xlo, xhi), encOf(m.get("right"), xlo, xhi));
            case "Pow" -> iPow(encOf(m.get("base"), xlo, xhi), m.get("exponent"), xlo, xhi);
            case "Sin" -> iSinCos(encOf(m.get("arg"), xlo, xhi), true);
            case "Cos" -> iSinCos(encOf(m.get("arg"), xlo, xhi), false);
            case "Tan" -> iTan(encOf(m.get("arg"), xlo, xhi));
            case "Exp" -> iExp(encOf(m.get("arg"), xlo, xhi));
            case "Log" -> iLog(encOf(m.get("arg"), xlo, xhi));
            default -> throw new RuntimeCheckException(
                    "evalInterval: unknown AlgExpr node '" + r.typeName() + "'", Origin.NONE);
        };
    }

    private static Enc iPow(Enc base, Object expNode, BigDecimal xlo, BigDecimal xhi) {
        if (base.kind() == EncKind.UNDEFINED) return Enc.UNDEFINED;
        BigDecimal exp = constValue(expNode);
        if (exp == null) return Enc.UNBOUNDED;                     // symbolic exponent → conservative
        if (base.kind() == EncKind.UNBOUNDED) return Enc.UNBOUNDED;
        BigDecimal stripped = exp.stripTrailingZeros();
        if (stripped.scale() <= 0) return iIntPow(base, stripped.intValueExact());  // integer — exact
        return iFracPow(base, exp);                                 // rational — roots, outward-rounded
    }

    /** The value of a {@code Const} node, or {@code null} if the node is anything else. */
    private static BigDecimal constValue(Object node) {
        if (node instanceof RecordValue r && r.typeName() != null
                && QualifiedName.memberOf(r.typeName()).equals("Const")) {
            return decimal(r.members().get("value"));
        }
        return null;
    }

    /** An enclosure as its Pontif value: {@code Interval(lo,hi)} | {@code Unbounded} | {@code Undefined}. */
    private static RecordValue encToRecord(Enc e) {
        return switch (e.kind()) {
            case BOUNDED -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("lo", e.lo());
                m.put("hi", e.hi());
                yield new RecordValue(qn("Interval"), m);
            }
            case UNBOUNDED -> new RecordValue(qn("Unbounded"), new LinkedHashMap<>());
            case UNDEFINED -> new RecordValue(qn("Undefined"), new LinkedHashMap<>());
        };
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
            throw new AlgebraicDomainException(
                    "eval: transcendental produced a non-finite result (" + d
                            + ") — outside its real domain");
        }
        BigDecimal r = new BigDecimal(d, DOUBLE_SIG).stripTrailingZeros();
        return r.scale() < 0 ? r.setScale(0) : r;   // keep integers as "2", not "2E+0"
    }

    private static final String SOURCE = """
            exports @.{AlgExpr, Const, Param, Add, Sub, Mul, Div, Pow,
                       Sin, Cos, Tan, Exp, Log, Algebraic, Undefined, Interval, Unbounded,
                       eval, evalAt, evalSafe, evalInterval}

            # Undefined: the honest result of evaluating an expression at a point outside its
            # domain — a pole (1/0), an even root of a negative, a non-finite transcendental. It
            # is NOT an AlgExpr node (you can't build with it); it is what `evalSafe` returns in
            # place of a value. So a consumer that must not crash on an asymptote (the plotter)
            # matches `[Decimal | Undefined]` and treats Undefined as "no point here".
            struct Undefined()

            # Interval / Unbounded: the other two outcomes of `evalInterval` (docs/reliable-plotting
            # .md). An `Interval(lo, hi)` is a bounded, sound enclosure of a curve over a pixel column;
            # `Unbounded` is a column that spills to ±∞ (a pole or dense feature); `Undefined` (above)
            # is a column wholly outside the domain. Like Undefined, neither is an AlgExpr node — they
            # are enclosure results, matched as `[Interval | Unbounded | Undefined]`.
            struct Interval(lo:Decimal, hi:Decimal)
            struct Unbounded()

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

            # AlgExpr is the CLOSED union of its node types (not an open trait). Every `match` over
            # it is exhaustive with no catch-all, and adding a node becomes a compile error in every
            # operation that matches AlgExpr until the new case is handled — no silent fallthrough.
            # (Undefined / Interval / Unbounded are deliberately NOT members: they are evaluation
            # results, not buildable nodes.)
            let AlgExpr:Type[Const | Param | Add | Sub | Mul | Div | Pow | Sin | Cos | Tan | Exp | Log]

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
            # evalSafe: the TOTAL sibling of `eval`. At a domain gap (a pole, an even root of a
            # negative, a non-finite transcendental) it yields `Undefined` instead of failing
            # closed — so a caller can sample across an asymptote. Body is a placeholder (the
            # native runs); the union return is the honest signature.
            function evalSafe(e:AlgExpr, x:Decimal):[Decimal | Undefined] -> Undefined()
            # evalInterval: a SOUND enclosure of the curve over a whole column [lo, hi] — the
            # reliable-plotting substrate (docs/reliable-plotting.md). `Interval(ylo, yhi)` bounds
            # the curve, `Unbounded` marks a pole/dense column, `Undefined` a wholly-out-of-domain
            # one. Placeholder body (the native runs); the three-way union is the honest signature.
            function evalInterval(e:AlgExpr, lo:Decimal, hi:Decimal):[Interval | Unbounded | Undefined] -> Unbounded()
            # evalAt binds each variable by NAME from a point dict `{x = …, y = …}` — the
            # N-argument surface (`eval` is the one-variable convenience). The binding is
            # dynamically typed (`_`); a statically-typed point is the next slice.
            function evalAt(e:AlgExpr, at:_):Decimal -> 0.0

            0
            """;
}
