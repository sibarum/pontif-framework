package sibarum.pontif.ir;

import sibarum.pontif.core.Origin;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public sealed interface IrExpr
        permits IrExpr.Lit, IrExpr.Dec, IrExpr.Chr, IrExpr.Bool, IrExpr.Var, IrExpr.SelfRef,
                IrExpr.BinOp, IrExpr.LetIn, IrExpr.Call,
                IrExpr.Lambda, IrExpr.Apply, IrExpr.Match,
                IrExpr.Record, IrExpr.FieldAccess {

    Origin origin();

    enum Op {
        ADD, MUL, SUB, DIV, MOD,
        LT, LE, GT, GE, EQ, NE, APPROX,
        AND, OR
    }

    static Lit lit(long value) { return new Lit(value, Origin.NONE); }
    static Dec dec(BigDecimal value) { return new Dec(value, Origin.NONE); }
    static Chr chr(int codePoint) { return new Chr(codePoint, Origin.NONE); }
    static Bool bool(boolean value) { return new Bool(value, Origin.NONE); }
    static Var var(String name) { return new Var(name, Origin.NONE); }
    static SelfRef self() { return new SelfRef(Origin.NONE); }
    static BinOp binOp(Op op, IrExpr left, IrExpr right) { return new BinOp(op, left, right, Origin.NONE); }
    static LetIn letIn(String name, IrSort sort, IrExpr value, IrExpr body) { return new LetIn(name, sort, value, body, Origin.NONE); }
    static Call call(String functionName, List<IrExpr> args) { return new Call(functionName, args, Origin.NONE); }
    static Lambda lambda(List<IrParam> params, IrSort returnSort, IrExpr body) { return new Lambda(params, returnSort, body, Origin.NONE); }
    static Apply apply(IrExpr fn, List<IrExpr> args) { return new Apply(fn, args, Origin.NONE); }
    static Match match(IrExpr scrutinee, List<MatchBranch> branches) { return new Match(scrutinee, branches, Origin.NONE); }
    static MatchBranch matchBranch(IrSort pattern, IrExpr result) { return new MatchBranch(pattern, result); }
    static Record record(Map<String, IrExpr> members) { return new Record(null, members, Origin.NONE); }
    static Record record(String typeName, Map<String, IrExpr> members) { return new Record(typeName, members, Origin.NONE); }
    static FieldAccess fieldAccess(IrExpr base, String fieldName) { return new FieldAccess(base, fieldName, Origin.NONE); }

    record Lit(long value, Origin origin) implements IrExpr {}

    /** Decimal literal — arbitrary-precision exact decimal (BigDecimal-backed). */
    record Dec(BigDecimal value, Origin origin) implements IrExpr {
        public Dec {
            if (value == null) {
                throw new IllegalArgumentException("Dec value must be non-null");
            }
        }
    }

    /**
     * Character literal — a Unicode code point (full range, not just the
     * BMP). The fourth scalar; ordered by code point, no arithmetic.
     */
    record Chr(int codePoint, Origin origin) implements IrExpr {
        public Chr {
            if (!Character.isValidCodePoint(codePoint)) {
                throw new IllegalArgumentException(
                        "Chr code point out of Unicode range: " + codePoint);
            }
        }
    }

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

    record Match(IrExpr scrutinee, List<MatchBranch> branches, Origin origin) implements IrExpr {
        public Match {
            if (scrutinee == null) {
                throw new IllegalArgumentException("Match scrutinee must be non-null");
            }
            branches = List.copyOf(branches);
            if (branches.isEmpty()) {
                throw new IllegalArgumentException("Match must have at least one branch");
            }
        }
    }

    record MatchBranch(IrSort pattern, IrExpr result) {
        public MatchBranch {
            if (pattern == null) {
                throw new IllegalArgumentException("MatchBranch pattern must be non-null");
            }
            if (result == null) {
                throw new IllegalArgumentException("MatchBranch result must be non-null");
            }
        }
    }

    /**
     * Record literal. {@code typeName} carries the nominal struct type
     * when known (e.g., {@code "Point"} for {@code Point(1, 2)}), null
     * for anonymous records (S-expr {@code (record ...)} construction).
     * Used downstream by the dispatch table's trait-fallback rule to
     * identify the concrete type of an argument.
     */
    record Record(String typeName, Map<String, IrExpr> members, Origin origin) implements IrExpr {
        public Record {
            if (members == null) {
                throw new IllegalArgumentException("Record members must be non-null");
            }
            // Preserve insertion order — record-literal field iteration order is
            // load-bearing for destructure desugar, error messages, and Truffle
            // lowering. Map.copyOf would silently strip the order.
            members = Collections.unmodifiableMap(new LinkedHashMap<>(members));
        }

        /** Backward-compat constructor used by tests and S-expr parser. */
        public Record(Map<String, IrExpr> members, Origin origin) {
            this(null, members, origin);
        }
    }

    record FieldAccess(IrExpr base, String fieldName, Origin origin) implements IrExpr {
        public FieldAccess {
            if (base == null) {
                throw new IllegalArgumentException("FieldAccess base must be non-null");
            }
            if (fieldName == null || fieldName.isEmpty()) {
                throw new IllegalArgumentException("FieldAccess field name must be non-empty");
            }
        }
    }
}
