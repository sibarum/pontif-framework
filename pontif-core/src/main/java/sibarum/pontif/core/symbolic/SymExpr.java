package sibarum.pontif.core.symbolic;

import sibarum.pontif.core.types.Sort;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public sealed interface SymExpr
        permits SymExpr.Var, SymExpr.Lit, SymExpr.Frac, SymExpr.Dec, SymExpr.Chr,
                SymExpr.Str, SymExpr.Bool, SymExpr.Self, SymExpr.DispatchRef,
                SymExpr.Add, SymExpr.Mul, SymExpr.Pow,
                SymExpr.Cmp, SymExpr.And, SymExpr.Or,
                SymExpr.Lam, SymExpr.App, SymExpr.Case,
                SymExpr.Record, SymExpr.FieldAccess {

    static Var var(String name) { return new Var(name); }
    static Lit lit(long value) { return new Lit(value); }
    static Frac frac(long num, long denom) { return new Frac(num, denom); }
    static Dec dec(BigDecimal value) { return new Dec(value); }
    static Chr chr(int codePoint) { return new Chr(codePoint); }
    static Str str(String value) { return new Str(value); }
    static Bool bool(boolean value) { return new Bool(value); }
    static Self self() { return Self.INSTANCE; }
    static Add add(SymExpr left, SymExpr right) { return new Add(left, right); }
    static Mul mul(SymExpr left, SymExpr right) { return new Mul(left, right); }
    static Pow pow(SymExpr base, SymExpr exponent) { return new Pow(base, exponent); }
    static Cmp cmp(SymExpr left, CmpOp op, SymExpr right) { return new Cmp(left, op, right); }
    static And and(SymExpr left, SymExpr right) { return new And(left, right); }
    static Or or(SymExpr left, SymExpr right) { return new Or(left, right); }
    static Lam lam(String param, SymExpr body) { return new Lam(param, null, body); }
    static Lam lam(String param, Sort paramType, SymExpr body) { return new Lam(param, paramType, body); }
    static App app(SymExpr fn, SymExpr arg) { return new App(fn, arg); }
    static Case case_(SymExpr scrutinee, List<CaseBranch> branches) { return new Case(scrutinee, branches); }
    static CaseBranch branch(Sort pattern, SymExpr result) { return new CaseBranch(pattern, result); }
    static Record record(Map<String, SymExpr> members) { return new Record(members, null); }
    static Record record(String typeName, Map<String, SymExpr> members) { return new Record(members, typeName); }
    static FieldAccess fieldAccess(SymExpr base, String fieldName) { return new FieldAccess(base, fieldName); }

    enum CmpOp { LT, LE, GT, GE, EQ, NE }

    record Var(String name) implements SymExpr {}
    record Lit(long value) implements SymExpr {}

    record Frac(long num, long denom) implements SymExpr {
        public Frac {
            if (denom == 0) {
                throw new ArithmeticException("Division by zero in Frac");
            }
            if (num == 0) {
                denom = 1;
            } else {
                long sign = denom < 0 ? -1 : 1;
                long g = gcd(Math.abs(num), Math.abs(denom));
                num = sign * num / g;
                denom = sign * denom / g;
            }
        }

        private static long gcd(long a, long b) {
            while (b != 0) {
                long t = b;
                b = a % b;
                a = t;
            }
            return a;
        }
    }

    /**
     * Decimal value leaf — arbitrary-precision exact decimal (BigDecimal).
     * A value atom like {@link Lit}/{@link Frac}; the integer-only discharge
     * engines abstain on it (as they do on {@link Frac}), so it never enters
     * integer reasoning. Carries only its value; {@code SignAnalysis} reads its
     * {@code signum()}.
     */
    record Dec(BigDecimal value) implements SymExpr {}

    /**
     * Character value leaf — a Unicode code point. A value atom like
     * {@link Lit}/{@link Dec}; the integer-only discharge engines abstain on
     * it for now. Char IS discrete (code points are integers), so routing
     * Char obligations through integer reasoning is legitimate — that's the
     * narrows slice, not this one.
     */
    record Chr(int codePoint) implements SymExpr {}

    /**
     * String value leaf — the first Char <em>collection</em>. A value atom
     * like {@link Lit}/{@link Chr}; the integer-only discharge engines abstain
     * on it, and — unlike {@link Chr}, which is discrete and has a future
     * integer-discharge narrows route — String has no such route, so the
     * abstention is permanent. Storage only; the stream view is a later slice.
     */
    record Str(String value) implements SymExpr {
        public Str {
            if (value == null) {
                throw new IllegalArgumentException("Str value must be non-null");
            }
        }
    }

    record Bool(boolean value) implements SymExpr {}

    /**
     * Dispatch-reference value leaf — a metareference ({@code inc[Int]})
     * lifted to the symbolic layer. A value atom built from statics only
     * (name + key sorts, no data content); the discharge engines abstain.
     *
     * <p>{@code typeName} (nullable) is the reference's concrete dispatch nominal
     * ({@code Dispatch} / {@code AlgebraicDispatch}) when it came from a first-class
     * metareference value; it lets trait-param dispatch see e.g. {@code AlgebraicDispatch
     * is-a Algebraic}. {@code null} for a static/compiled metareference (no nominal yet).
     * Key-sort matching against a {@code [Dispatch(…)]} sort ignores it.
     */
    record DispatchRef(String functionName, List<Sort> keySorts, String typeName)
            implements SymExpr {
        public DispatchRef {
            keySorts = List.copyOf(keySorts);
        }

        /** Back-compat: a metareference with no concrete nominal (a static reference). */
        public DispatchRef(String functionName, List<Sort> keySorts) {
            this(functionName, keySorts, null);
        }
    }

    record Self() implements SymExpr {
        public static final Self INSTANCE = new Self();
    }

    record Add(SymExpr left, SymExpr right) implements SymExpr {}
    record Mul(SymExpr left, SymExpr right) implements SymExpr {}
    record Pow(SymExpr base, SymExpr exponent) implements SymExpr {}

    record Cmp(SymExpr left, CmpOp op, SymExpr right) implements SymExpr {}

    record And(SymExpr left, SymExpr right) implements SymExpr {}
    record Or(SymExpr left, SymExpr right) implements SymExpr {}

    record Lam(String param, Sort paramType, SymExpr body) implements SymExpr {
        public Lam {
            if (param == null || param.isEmpty()) {
                throw new IllegalArgumentException("Lambda parameter name must be non-empty");
            }
            if (body == null) {
                throw new IllegalArgumentException("Lambda body must be non-null");
            }
        }
    }

    record App(SymExpr fn, SymExpr arg) implements SymExpr {}

    record Case(SymExpr scrutinee, List<CaseBranch> branches) implements SymExpr {
        public Case {
            if (scrutinee == null) {
                throw new IllegalArgumentException("Case scrutinee must be non-null");
            }
            branches = List.copyOf(branches);
        }
    }

    record CaseBranch(Sort pattern, SymExpr result) {
        public CaseBranch {
            if (pattern == null) {
                throw new IllegalArgumentException("CaseBranch pattern must be non-null");
            }
            if (result == null) {
                throw new IllegalArgumentException("CaseBranch result must be non-null");
            }
        }
    }

    /**
     * Symbolic record value. {@code typeName} carries the nominal struct
     * type (e.g., {@code "Point"}) when known — needed by the dispatch
     * table's trait-fallback rule to identify the concrete type of an
     * argument. May be {@code null} for anonymous records (S-expr
     * {@code (record ...)} construction, refinement-predicate fragments).
     */
    record Record(Map<String, SymExpr> members, String typeName) implements SymExpr {
        public Record {
            members = Map.copyOf(members);
        }
    }

    record FieldAccess(SymExpr base, String fieldName) implements SymExpr {
        public FieldAccess {
            if (base == null) {
                throw new IllegalArgumentException("FieldAccess base must be non-null");
            }
            if (fieldName == null || fieldName.isEmpty()) {
                throw new IllegalArgumentException("Field name must be non-empty");
            }
        }
    }
}
