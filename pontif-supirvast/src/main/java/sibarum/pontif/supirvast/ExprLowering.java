package sibarum.pontif.supirvast;

import dev.supirvast.vastir.core.BinaryOp;
import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.LocalVar;
import dev.supirvast.vastir.core.Statement;
import dev.supirvast.vastir.core.UnaryOp;
import sibarum.pontif.ir.IrExpr;

import java.util.ArrayList;
import java.util.List;

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

            // --- unsupported in v1: each fails closed with a source-located witness ---
            case IrExpr.Chr chr -> throw LoweringError.charLiteral(chr);
            case IrExpr.Str str -> throw LoweringError.stringLiteral(str);
            case IrExpr.SelfRef self -> throw LoweringError.selfRef(self);
            case IrExpr.Record record -> throw LoweringError.record(record);
            case IrExpr.FieldAccess access -> throw LoweringError.fieldAccess(access);
            case IrExpr.MethodCall call -> throw LoweringError.methodCall(call);
            case IrExpr.Lambda lambda -> throw LoweringError.lambda(lambda);
            case IrExpr.Apply apply -> throw LoweringError.apply(apply);
            case IrExpr.DispatchRef ref -> throw LoweringError.dispatchRef(ref);
            case IrExpr.Cast cast -> throw LoweringError.cast(cast);
            case IrExpr.Call call -> throw LoweringError.unsupportedExpr(call, "Call '" + call.functionName() + "(...)'",
                    "user-function calls are lowered in a later slice (monomorphization)");
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
        List<Statement> stmts = new ArrayList<>(left.statements());
        stmts.addAll(right.statements());
        return new Block(stmts, applyOp(op, left.value(), right.value()));
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
}
