package sibarum.pontif.ir;

import sibarum.pontif.core.Origin;

import java.util.List;

public sealed interface IrExpr
        permits IrExpr.Lit, IrExpr.Bool, IrExpr.Var, IrExpr.SelfRef,
                IrExpr.BinOp, IrExpr.LetIn, IrExpr.Call,
                IrExpr.Lambda, IrExpr.Apply {

    Origin origin();

    enum Op {
        ADD, MUL, SUB,
        LT, LE, GT, GE, EQ, NE
    }

    static Lit lit(long value) { return new Lit(value, Origin.NONE); }
    static Bool bool(boolean value) { return new Bool(value, Origin.NONE); }
    static Var var(String name) { return new Var(name, Origin.NONE); }
    static SelfRef self() { return new SelfRef(Origin.NONE); }
    static BinOp binOp(Op op, IrExpr left, IrExpr right) { return new BinOp(op, left, right, Origin.NONE); }
    static LetIn letIn(String name, IrSort sort, IrExpr value, IrExpr body) { return new LetIn(name, sort, value, body, Origin.NONE); }
    static Call call(String functionName, List<IrExpr> args) { return new Call(functionName, args, Origin.NONE); }
    static Lambda lambda(List<IrParam> params, IrSort returnSort, IrExpr body) { return new Lambda(params, returnSort, body, Origin.NONE); }
    static Apply apply(IrExpr fn, List<IrExpr> args) { return new Apply(fn, args, Origin.NONE); }

    record Lit(long value, Origin origin) implements IrExpr {}

    record Bool(boolean value, Origin origin) implements IrExpr {}

    record Var(String name, Origin origin) implements IrExpr {}

    record SelfRef(Origin origin) implements IrExpr {}

    record BinOp(Op op, IrExpr left, IrExpr right, Origin origin) implements IrExpr {}

    record LetIn(String name, IrSort declaredSort, IrExpr value, IrExpr body, Origin origin) implements IrExpr {}

    record Call(String functionName, List<IrExpr> args, Origin origin) implements IrExpr {
        public Call {
            args = List.copyOf(args);
        }
    }

    record Lambda(List<IrParam> params, IrSort returnSort, IrExpr body, Origin origin) implements IrExpr {
        public Lambda {
            params = List.copyOf(params);
            if (returnSort == null) {
                throw new IllegalArgumentException("Lambda returnSort must be non-null");
            }
            if (body == null) {
                throw new IllegalArgumentException("Lambda body must be non-null");
            }
        }
    }

    record Apply(IrExpr fn, List<IrExpr> args, Origin origin) implements IrExpr {
        public Apply {
            args = List.copyOf(args);
            if (fn == null) {
                throw new IllegalArgumentException("Apply function expression must be non-null");
            }
        }
    }
}
