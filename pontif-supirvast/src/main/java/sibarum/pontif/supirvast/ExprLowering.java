package sibarum.pontif.supirvast;

import dev.supirvast.vastir.core.BinaryOp;
import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.LocalVar;
import dev.supirvast.vastir.core.MathFn;
import dev.supirvast.vastir.core.Statement;
import dev.supirvast.vastir.core.UnaryOp;
import dev.supirvast.vastir.type.Type;
import sibarum.pontif.ir.IrExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lowers a Pontif {@link IrExpr} (the Int-only v1 subset) to a SuperVast {@code core} value expression, plus
 * any statements that must run before it. Pontif expressions are pure trees, but {@code core} separates
 * value-producing {@link Expr} from effectful {@link Statement} (a {@code LetIn} becomes a {@code DeclareVar}
 * statement feeding a {@code Read}), so a lowering yields a {@link Block}: the preceding statements and the
 * final value.
 *
 * <p>Every construct outside the supported subset throws a {@link LoweringError} routed through the catalogue —
 * the lowering <em>is</em> the eligibility check. Slice 1 handles literals, variables, binary operators, and
 * let-bindings; calls, matches, and the kernel-shaping {@code Iterate} arrive in later slices.
 */
public final class ExprLowering {

    /**
     * The numeric type scalar literals lower to: {@code int64} (an Int kernel) or {@code float32} (a Decimal
     * kernel — the ruled lossy Decimal→f32 cast). In a float kernel an Int literal promotes to f32 too, so
     * {@code x * 2} beside a Decimal reads as float. Arithmetic type is inferred from operands by SuperVast,
     * so only the leaves (literals + column loads) need the right type.
     */
    private final dev.supirvast.vastir.type.Type numericType;

    /** An Int-kernel lowerer (literals → {@code int64}) — the default and backward-compatible form. */
    public ExprLowering() {
        this(dev.supirvast.vastir.type.Type.int64());
    }

    public ExprLowering(dev.supirvast.vastir.type.Type numericType) {
        this.numericType = numericType;
    }

    private boolean isFloat() {
        return numericType instanceof dev.supirvast.vastir.type.Type.Float;
    }

    /** {@code numericType} as a {@code Type.Float} — call only under {@link #isFloat()}. */
    private dev.supirvast.vastir.type.Type.Float floatType() {
        return (dev.supirvast.vastir.type.Type.Float) numericType;
    }

    /** A lowered expression: the statements that must precede it, then the value it produces. */
    public record Block(List<Statement> statements, Expr value) {
        public Block {
            statements = List.copyOf(statements);
        }

        static Block value(Expr value) {
            return new Block(List.of(), value);
        }
    }

    /** Lowers {@code expr} under {@code scope}, throwing {@link LoweringError} on the first unsupported node. */
    public Block lower(IrExpr expr, Scope scope) {
        return switch (expr) {
            // A whole-number literal: f32 in a Decimal kernel (promotion), else i64.
            case IrExpr.Lit lit -> Block.value(isFloat()
                    ? new Expr.ConstFloat(floatType(), (double) lit.value())
                    : new Expr.ConstInt(dev.supirvast.vastir.type.Type.int64(), lit.value()));
            // A Decimal literal → f32 (lossy). Only valid in a Decimal kernel; in an Int kernel this fails
            // closed (a mixed Int/Decimal kernel is a later slice).
            case IrExpr.Dec dec -> {
                if (!isFloat()) {
                    throw LoweringError.decimalLiteral(dec);
                }
                yield Block.value(new Expr.ConstFloat(floatType(), dec.value().doubleValue()));
            }
            case IrExpr.Bool bool -> Block.value(new Expr.ConstBool(bool.value()));
            case IrExpr.Var var -> {
                Expr bound = scope.lookup(var.name());
                if (bound == null) {
                    throw LoweringError.unboundVariable(var);
                }
                yield Block.value(bound);
            }
            case IrExpr.BinOp op -> lowerBinOp(op, scope);
            case IrExpr.LetIn let -> lowerLet(let, scope);

            // --- shader vocabulary (float kernels only): vectors-as-records + pontif.math intrinsics ---
            // A Vec2/Vec3/Vec4 record → a core vector; a .x/.y/.z/.w swizzle → a component extract; a
            // pontif.math call (or a vecN constructor) → a core MathCall/VectorConstruct. These are the
            // building blocks a fragment/vertex stage needs; they stay closed to non-vector records and
            // non-intrinsic calls (a general user-function call is still a later slice).
            case IrExpr.Record record -> lowerVectorRecord(record, scope);
            case IrExpr.FieldAccess access -> lowerSwizzle(access, scope);
            case IrExpr.Call call -> lowerIntrinsic(call, scope);

            // --- unsupported in v1: each fails closed with a source-located witness ---
            case IrExpr.Chr chr -> throw LoweringError.charLiteral(chr);
            case IrExpr.Str str -> throw LoweringError.stringLiteral(str);
            case IrExpr.SelfRef self -> throw LoweringError.selfRef(self);
            case IrExpr.MethodCall call -> throw LoweringError.methodCall(call);
            case IrExpr.Lambda lambda -> throw LoweringError.lambda(lambda);
            case IrExpr.Apply apply -> throw LoweringError.apply(apply);
            case IrExpr.DispatchRef ref -> throw LoweringError.dispatchRef(ref);
            case IrExpr.Cast cast -> throw LoweringError.cast(cast);
            case IrExpr.Match match -> throw LoweringError.matchExpr(match,
                    "match lowering to structured if/else arrives in a later slice");
            case IrExpr.Iterate iterate -> throw LoweringError.iterate(iterate,
                    "the iteration construct is shaped into a kernel by the kernel lowering, not here");

            // Any IR node not explicitly handled above (e.g. `emit`, and any variant added to the
            // sealed IrExpr after this lowerer was written) fails closed — attempting to lower IS
            // the eligibility check, so an unrecognized construct is a source-located error, never a
            // silently-wrong kernel.
            default -> throw LoweringError.unsupportedExpr(expr,
                    expr.getClass().getSimpleName(),
                    "this construct has no GPU-kernel lowering in the supported (Int-only, v1) subset");
        };
    }

    private Block lowerBinOp(IrExpr.BinOp op, Scope scope) {
        Block left = lower(op.left(), scope);
        Block right = lower(op.right(), scope);
        Expr l = left.value();
        Expr r = right.value();
        // Scalar broadcast (core has no vector·scalar ops): in an arithmetic op mixing a vector and a
        // scalar, splat the scalar to the vector's width so both sides are the same vector type — e.g.
        // `p * 2` becomes a component-wise multiply. Comparisons/logic keep their scalar operands.
        if (isArithmetic(op.op())) {
            if (l.type() instanceof Type.Vector v && r.type() instanceof Type.Float) {
                r = splat(r, v);
            } else if (r.type() instanceof Type.Vector v && l.type() instanceof Type.Float) {
                l = splat(l, v);
            }
        }
        List<Statement> stmts = new ArrayList<>(left.statements());
        stmts.addAll(right.statements());
        return new Block(stmts, applyOp(op, l, r));
    }

    private static boolean isArithmetic(IrExpr.Op op) {
        return op == IrExpr.Op.ADD || op == IrExpr.Op.SUB || op == IrExpr.Op.MUL
                || op == IrExpr.Op.DIV || op == IrExpr.Op.MOD;
    }

    /**
     * Maps a Pontif operator onto a {@code core} expression. SuperVast's {@code BinaryOp} carries only
     * {@code LESS_THAN}/{@code GREATER_THAN}/{@code EQUAL}, so {@code <=}, {@code >=}, and {@code !=} desugar to
     * the negation of their complement.
     */
    private Expr applyOp(IrExpr.BinOp op, Expr l, Expr r) {
        return switch (op.op()) {
            case ADD -> new Expr.Binary(BinaryOp.ADD, l, r);
            case SUB -> new Expr.Binary(BinaryOp.SUB, l, r);
            case MUL -> new Expr.Binary(BinaryOp.MUL, l, r);
            case DIV -> new Expr.Binary(BinaryOp.DIV, l, r);
            case MOD -> new Expr.Binary(BinaryOp.MOD, l, r);
            case LT -> new Expr.Binary(BinaryOp.LESS_THAN, l, r);
            case GT -> new Expr.Binary(BinaryOp.GREATER_THAN, l, r);
            case EQ -> new Expr.Binary(BinaryOp.EQUAL, l, r);
            case LE -> not(new Expr.Binary(BinaryOp.GREATER_THAN, l, r));   // a <= b  ≡  !(a > b)
            case GE -> not(new Expr.Binary(BinaryOp.LESS_THAN, l, r));      // a >= b  ≡  !(a < b)
            case NE -> not(new Expr.Binary(BinaryOp.EQUAL, l, r));          // a != b  ≡  !(a == b)
            case AND -> new Expr.Binary(BinaryOp.LOGICAL_AND, l, r);
            case OR -> new Expr.Binary(BinaryOp.LOGICAL_OR, l, r);
            case POW -> throw LoweringError.powOperator(op);
            case APPROX -> throw LoweringError.approxOperator(op);
        };
    }

    private static Expr not(Expr boolExpr) {
        return new Expr.Unary(UnaryOp.LOGICAL_NOT, boolExpr);
    }

    private Block lowerLet(IrExpr.LetIn let, Scope scope) {
        Block bound = lower(let.value(), scope);
        LocalVar local = new LocalVar(let.name(), bound.value().type());
        List<Statement> stmts = new ArrayList<>(bound.statements());
        stmts.add(new Statement.DeclareVar(local, bound.value()));
        Block body = lower(let.body(), scope.with(let.name(), new Expr.Read(local)));
        stmts.addAll(body.statements());
        return new Block(stmts, body.value());
    }

    // --- shader vocabulary: vectors-as-records + pontif.math intrinsics -------------------------------

    /** {@code pontif.math} intrinsic names → their {@code core} {@link MathFn}. */
    private static final Map<String, MathFn> MATH = mathTable();

    /** Intrinsics that reduce a vector to its scalar component type (result is {@code float}, not a vector). */
    private static final Set<MathFn> REDUCING = Set.of(MathFn.LENGTH, MathFn.DOT, MathFn.DISTANCE);

    /** {@code vecN} constructor names → their width. */
    private static final Map<String, Integer> VEC_CONSTRUCTOR = Map.of("vec2", 2, "vec3", 3, "vec4", 4);

    /** Swizzle component names → index (both {@code xyzw} position and {@code rgba} color spellings). */
    private static final Map<String, Integer> COMPONENT = Map.of(
            "x", 0, "y", 1, "z", 2, "w", 3, "r", 0, "g", 1, "b", 2, "a", 3);

    /**
     * A {@code Vec2}/{@code Vec3}/{@code Vec4} record literal → a {@code core} vector. The members lower to the
     * vector's components in field order; the record's name must match its arity ({@code VecN}). Non-vector
     * records (and vectors outside a float kernel) still fail closed.
     */
    private Block lowerVectorRecord(IrExpr.Record record, Scope scope) {
        int n = record.members().size();
        if (!isFloat() || n < 2 || n > 4 || !("Vec" + n).equals(record.typeName())) {
            throw LoweringError.record(record);
        }
        List<Statement> stmts = new ArrayList<>();
        List<Expr> components = new ArrayList<>();
        for (IrExpr member : record.members().values()) {
            Block b = lower(member, scope);
            stmts.addAll(b.statements());
            components.add(b.value());
        }
        return new Block(stmts, new Expr.VectorConstruct(vectorType(n), components));
    }

    /** A {@code v.x}/{@code .y}/{@code .z}/{@code .w} (or {@code .r/.g/.b/.a}) swizzle → a component extract. */
    private Block lowerSwizzle(IrExpr.FieldAccess access, Scope scope) {
        Block base = lower(access.base(), scope);
        Integer index = COMPONENT.get(access.fieldName());
        if (index == null || !(base.value().type() instanceof Type.Vector v) || index >= v.count()) {
            throw LoweringError.fieldAccess(access);
        }
        return new Block(base.statements(), new Expr.VectorExtract(base.value(), index));
    }

    /**
     * A {@code vecN(...)} constructor → a {@code core} vector, or a {@code pontif.math} call → a {@code core}
     * {@link Expr.MathCall}. The result type is the vector arg's type for element-wise intrinsics (with scalar
     * args broadcast to that width), or the scalar component type for reducing ones ({@code length}/{@code dot}/
     * {@code distance}). Any other call still fails closed.
     */
    private Block lowerIntrinsic(IrExpr.Call call, Scope scope) {
        String name = bare(call.functionName());
        List<Statement> stmts = new ArrayList<>();
        List<Expr> args = new ArrayList<>();
        for (IrExpr arg : call.args()) {
            Block b = lower(arg, scope);
            stmts.addAll(b.statements());
            args.add(b.value());
        }

        Integer width = VEC_CONSTRUCTOR.get(name);
        if (width != null) {
            if (args.size() != width) {
                throw LoweringError.unsupportedExpr(call, "Call '" + name + "(...)'",
                        name + " takes " + width + " components, got " + args.size());
            }
            return new Block(stmts, new Expr.VectorConstruct(vectorType(width), args));
        }

        MathFn fn = isFloat() ? MATH.get(name) : null;
        if (fn == null) {
            throw LoweringError.unsupportedExpr(call, "Call '" + name + "(...)'",
                    "only pontif.math intrinsics and vecN constructors lower in a shader body; user-function "
                            + "calls are a later slice");
        }
        Type result = intrinsicResultType(fn, args);
        if (result instanceof Type.Vector v && !REDUCING.contains(fn)) {
            for (int i = 0; i < args.size(); i++) {
                if (args.get(i).type() instanceof Type.Float) {
                    args.set(i, splat(args.get(i), v));   // broadcast e.g. mix(a, b, t)'s scalar t to the vector
                }
            }
        }
        return new Block(stmts, new Expr.MathCall(fn, result, args));
    }

    /** Element-wise intrinsics take the vector arg's type; reducing ones ({@code length}…) yield its component. */
    private Type intrinsicResultType(MathFn fn, List<Expr> args) {
        if (REDUCING.contains(fn)) {
            for (Expr a : args) {
                if (a.type() instanceof Type.Vector v) {
                    return v.component();
                }
            }
            return floatType();
        }
        for (Expr a : args) {
            if (a.type() instanceof Type.Vector) {
                return a.type();
            }
        }
        return args.isEmpty() ? floatType() : args.get(0).type();
    }

    private Type.Vector vectorType(int width) {
        return new Type.Vector(floatType(), width);
    }

    /** Repeats {@code scalar} across a vector of type {@code v} — the broadcast of a scalar to vector width. */
    private static Expr splat(Expr scalar, Type.Vector v) {
        List<Expr> components = new ArrayList<>();
        for (int i = 0; i < v.count(); i++) {
            components.add(scalar);
        }
        return new Expr.VectorConstruct(v, components);
    }

    /** Strips a module qualifier: {@code pontif.math/length} → {@code length}. */
    private static String bare(String name) {
        if (name == null) {
            return "";
        }
        int slash = name.lastIndexOf('/');
        return slash < 0 ? name : name.substring(slash + 1);
    }

    private static Map<String, MathFn> mathTable() {
        Map<String, MathFn> m = new java.util.HashMap<>();
        m.put("length", MathFn.LENGTH);
        m.put("dot", MathFn.DOT);
        m.put("distance", MathFn.DISTANCE);
        m.put("normalize", MathFn.NORMALIZE);
        m.put("cross", MathFn.CROSS);
        m.put("reflect", MathFn.REFLECT);
        m.put("pow", MathFn.POW);
        m.put("sqrt", MathFn.SQRT);
        m.put("abs", MathFn.ABS);
        m.put("sign", MathFn.SIGN);
        m.put("min", MathFn.MIN);
        m.put("max", MathFn.MAX);
        m.put("clamp", MathFn.CLAMP);
        m.put("mix", MathFn.MIX);
        m.put("step", MathFn.STEP);
        m.put("smoothstep", MathFn.SMOOTHSTEP);
        m.put("exp", MathFn.EXP);
        m.put("log", MathFn.LOG);
        m.put("sin", MathFn.SIN);
        m.put("cos", MathFn.COS);
        m.put("tan", MathFn.TAN);
        m.put("asin", MathFn.ASIN);
        m.put("acos", MathFn.ACOS);
        m.put("atan", MathFn.ATAN);
        m.put("atan2", MathFn.ATAN2);
        m.put("radians", MathFn.RADIANS);
        m.put("degrees", MathFn.DEGREES);
        m.put("floor", MathFn.FLOOR);
        m.put("ceil", MathFn.CEIL);
        m.put("fract", MathFn.FRACT);
        return Map.copyOf(m);
    }
}
