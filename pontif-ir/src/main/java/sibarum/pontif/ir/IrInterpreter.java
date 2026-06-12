package sibarum.pontif.ir;

import sibarum.pontif.ast.record.RecordValue;
import sibarum.pontif.core.Origin;
import sibarum.pontif.core.symbolic.DispatchResult;
import sibarum.pontif.core.symbolic.FunctionDecl;
import sibarum.pontif.core.symbolic.Refinements;
import sibarum.pontif.core.symbolic.RuntimeCheckException;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.symbolic.algebra.ProofResult;
import sibarum.pontif.core.types.Sort;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class IrInterpreter {

    private final Simplifier simplifier;

    public IrInterpreter(Simplifier simplifier) {
        this.simplifier = simplifier;
    }

    public Object eval(CompiledModule module) {
        // The Inquisition: every top-level let is force-evaluated before
        // main, declaration order — its claims (binding claims, construction
        // checks) are notarized whether or not anything references it. Pure
        // language: forcing is observationally invisible except where a
        // check fails or the value diverges — exactly the lies the lazy
        // ruling let an unreferenced binding tell. Genuine 0-arg functions
        // are NOT forced; a diverging body is legitimate until applied.
        for (String let : module.topLevelLets()) {
            eval(new IrExpr.Call(let, List.of(), Origin.NONE), Environment.empty(), module);
        }
        return eval(module.main(), Environment.empty(), module);
    }

    /**
     * The simplifier carrying this module's nominal-struct registry, so
     * {@link Refinements} can resolve a by-reference struct sort to its shape
     * when checking a value or dispatching. Without it a struct param sort is a
     * bare name treated as unconstrained — accepting any value.
     */
    private Simplifier checker(CompiledModule module) {
        return simplifier.withRegistry(module.structRegistry());
    }

    public Object eval(IrExpr expr, Environment env, CompiledModule module) {
        return switch (expr) {
            case IrExpr.Lit l -> l.value();
            case IrExpr.Dec d -> d.value();
            case IrExpr.Chr c -> new sibarum.pontif.core.types.CharValue(c.codePoint());
            case IrExpr.Str s -> new sibarum.pontif.core.types.StringValue(s.value());
            // A metareference evaluates to a first-class dispatch — built
            // from statics only; invocation reruns registry dispatch.
            case IrExpr.DispatchRef d -> {
                List<sibarum.pontif.core.types.Sort> keys = new ArrayList<>(d.keySorts().size());
                try {
                    for (IrSort k : d.keySorts()) keys.add(IrCompiler.compileSort(k));
                } catch (CompileException ce) {
                    throw new RuntimeCheckException(
                            "Metareference key sort failed to compile: " + ce.getMessage(),
                            d.origin());
                }
                yield new sibarum.pontif.core.types.DispatchValue(d.functionName(), keys);
            }
            case IrExpr.Bool b -> b.value();
            case IrExpr.Var v -> env.lookup(v.name());
            case IrExpr.SelfRef s -> throw new IllegalStateException(
                    "Self has no runtime value — it is a typing-context placeholder");
            case IrExpr.BinOp op -> evalBinOp(op, env, module);
            case IrExpr.LetIn l -> {
                Object value = eval(l.value(), env, module);
                // Binding claim kept by ConstructionGate (UNKNOWN verdict):
                // the declared sort whose fit was undecidable at compile time
                // is judged here, where the value is concrete. Fail-closed,
                // mirroring construction checks.
                if (l.claim() != null) {
                    Sort claim = module.sortFor(l.claim());
                    ProofResult pr = Refinements.satisfies(toSymExpr(value), claim, checker(module));
                    if (!(pr instanceof ProofResult.Passed)) {
                        throw new RuntimeCheckException(
                                "Binding claim violated: '" + l.name() + "' = " + value
                                        + " does not satisfy the declared sort " + claim,
                                l.origin());
                    }
                }
                Environment extended = env.extend(l.name(), value);
                yield eval(l.body(), extended, module);
            }
            case IrExpr.Call c -> evalCall(c, env, module);
            case IrExpr.Lambda lambda -> new Closure(lambda, env);
            case IrExpr.Apply apply -> evalApply(apply, env, module);
            case IrExpr.Match m -> evalMatch(m, env, module);
            case IrExpr.Record r -> {
                Map<String, Object> members = new LinkedHashMap<>();
                for (Map.Entry<String, IrExpr> e : r.members().entrySet()) {
                    members.put(e.getKey(), eval(e.getValue(), env, module));
                }
                // Construction-claim checks stamped by ConstructionGate: the
                // members whose fit was undecidable at compile time are judged
                // here, where the value is concrete. Fail-closed: anything
                // short of Passed rejects the construction.
                for (Map.Entry<String, IrSort> check : r.runtimeChecks().entrySet()) {
                    Object v = members.get(check.getKey());
                    Sort claim = module.sortFor(check.getValue());
                    ProofResult pr = Refinements.satisfies(toSymExpr(v), claim, checker(module));
                    if (!(pr instanceof ProofResult.Passed)) {
                        throw new RuntimeCheckException(
                                "Construction claim violated: '" + r.typeName() + "."
                                        + check.getKey() + "' = " + v
                                        + " does not satisfy the declared sort " + claim,
                                r.origin());
                    }
                }
                // A native constructor builds its carrier scalar (the bijection
                // contract's construct half), not a RecordValue.
                NativeConstructors.Entry nativeCons = NativeConstructors.get(r.typeName());
                if (nativeCons != null) {
                    yield nativeCons.construct().apply(
                            members.values().toArray(), r.origin());
                }
                yield new RecordValue(r.typeName(), members);
            }
            case IrExpr.FieldAccess fa -> {
                Object baseValue = eval(fa.base(), env, module);
                // Decimal anatomy projection — total; unscaled is the
                // canonical scale-0 Decimal (never an Int: one-way wall).
                if (baseValue instanceof BigDecimal dec) {
                    if (!sibarum.pontif.core.Decimals.isAnatomyField(fa.fieldName())) {
                        throw new RuntimeCheckException(
                                "Decimal has no field '." + fa.fieldName()
                                        + "' — its anatomy is (unscaled, scale)",
                                fa.origin());
                    }
                    yield "scale".equals(fa.fieldName())
                            ? (Object) sibarum.pontif.core.Decimals.projectScale(dec)
                            : sibarum.pontif.core.Decimals.projectUnscaled(dec);
                }
                if (!(baseValue instanceof RecordValue rec)) {
                    throw new RuntimeCheckException(
                            "Field access '." + fa.fieldName() + "' requires a record value, got "
                                    + (baseValue == null ? "null" : baseValue.getClass().getSimpleName())
                                    + ": " + baseValue,
                            fa.origin());
                }
                if (rec.members().containsKey(fa.fieldName())) {
                    yield rec.get(fa.fieldName(), fa.origin());
                }
                // Trait-view attribute access: the value carries no such stored
                // field, so resolve a computed projection — a `Type.attr(this)`
                // producer registered by an `assign trait` block. This is what
                // lets a struct be viewed through a trait that adds attributes.
                Object projected = tryAttributeProducer(rec, fa.fieldName(), module);
                if (projected != NO_ATTRIBUTE) {
                    yield projected;
                }
                yield rec.get(fa.fieldName(), fa.origin());  // re-throws the "no field" error
            }
        };
    }

    private Object evalMatch(IrExpr.Match match, Environment env, CompiledModule module) {
        Object value = eval(match.scrutinee(), env, module);
        SymExpr symbolicValue = toSymExpr(value);
        for (int i = 0; i < match.branches().size(); i++) {
            IrExpr.MatchBranch branch = match.branches().get(i);
            Sort pattern = module.sortFor(branch.pattern());
            ProofResult result = Refinements.satisfies(symbolicValue, pattern, checker(module));
            if (result instanceof ProofResult.Passed) {
                try {
                    return eval(branch.result(), env, module);
                } catch (RuntimeCheckException rce) {
                    if (rce.origin().isPresent()) {
                        throw rce;
                    }
                    throw new RuntimeCheckException(rce.getMessage(), match.origin(), rce);
                }
            }
            if (result instanceof ProofResult.Residual residual) {
                throw new RuntimeCheckException(
                        "Match branch " + i + " (pattern " + pattern
                                + ") could not be decided at runtime against value " + value
                                + "; residual obligation: " + residual.obligation(),
                        match.origin());
            }
        }
        StringBuilder patterns = new StringBuilder("[");
        for (int i = 0; i < match.branches().size(); i++) {
            if (i > 0) patterns.append(", ");
            patterns.append(module.sortFor(match.branches().get(i).pattern()));
        }
        patterns.append("]");
        throw new RuntimeCheckException(
                "No match branch accepted value " + value + " against patterns " + patterns,
                match.origin());
    }

    private Object evalApply(IrExpr.Apply apply, Environment env, CompiledModule module) {
        Object fnValue = eval(apply.fn(), env, module);
        if (!(fnValue instanceof Closure closure)) {
            throw new sibarum.pontif.core.symbolic.RuntimeCheckException(
                    "Apply expects a closure value, got "
                            + (fnValue == null ? "null" : fnValue.getClass().getSimpleName())
                            + ": " + fnValue,
                    apply.origin());
        }
        List<Object> argValues = new ArrayList<>();
        for (IrExpr argExpr : apply.args()) {
            argValues.add(eval(argExpr, env, module));
        }
        try {
            return closure.invoke(argValues, this, module);
        } catch (sibarum.pontif.core.symbolic.RuntimeCheckException rce) {
            if (rce.origin().isPresent()) {
                throw rce;
            }
            throw new sibarum.pontif.core.symbolic.RuntimeCheckException(
                    rce.getMessage(), apply.origin(), rce);
        }
    }

    private Object evalBinOp(IrExpr.BinOp op, Environment env, CompiledModule module) {
        Object l = eval(op.left(), env, module);
        Object r = eval(op.right(), env, module);
        // Decimal operands use BigDecimal arithmetic and compareTo-based
        // comparison/equality, so 2.0 == 2.00. `Decimal op Int` promotes the Int
        // to Decimal — the lossless direction of the embedding, matching the
        // static sort (inferMaximalSort already types mixed arithmetic Decimal).
        // Non-numeric operands meeting a Decimal stay a clear error.
        if (l instanceof BigDecimal || r instanceof BigDecimal) {
            return evalDecimalBinOp(op.op(), asDecimal(l, op), asDecimal(r, op), op.origin());
        }
        // Char compares only with Char, by code point. No arithmetic, no
        // promotion (there is no Char/Int tower) — anything else fails closed
        // with an origin-carrying error.
        if (l instanceof sibarum.pontif.core.types.CharValue
                || r instanceof sibarum.pontif.core.types.CharValue) {
            return evalCharBinOp(op, l, r);
        }
        // Strings order and compare lexicographically by code point. No
        // arithmetic, no indexing, no String/Char or String/Int tower.
        if (l instanceof sibarum.pontif.core.types.StringValue
                || r instanceof sibarum.pontif.core.types.StringValue) {
            return evalStringBinOp(op, l, r);
        }
        return switch (op.op()) {
            case ADD -> (Long) l + (Long) r;
            case MUL -> (Long) l * (Long) r;
            case SUB -> (Long) l - (Long) r;
            case DIV -> {
                if ((Long) r == 0L) throw new RuntimeCheckException(
                        "Integer division by zero: " + l + " / 0", op.origin());
                yield (Long) l / (Long) r;   // truncates toward zero
            }
            case MOD -> {
                if ((Long) r == 0L) throw new RuntimeCheckException(
                        "Integer remainder by zero: " + l + " % 0", op.origin());
                yield (Long) l % (Long) r;   // sign of dividend; pairs with DIV (a == (a/b)*b + a%b)
            }
            case POW -> {
                long base = (Long) l;
                long e = (Long) r;
                if (e < 0L) throw new RuntimeCheckException(
                        "Negative exponent on Int: " + base + " ^ " + e + " — not an integer",
                        op.origin());
                long acc = 1L;
                for (long i = 0; i < e; i++) acc *= base;
                yield acc;
            }
            case LT -> (Long) l < (Long) r;
            case LE -> (Long) l <= (Long) r;
            case GT -> (Long) l > (Long) r;
            case GE -> (Long) l >= (Long) r;
            case EQ -> java.util.Objects.equals(l, r);
            case NE -> !java.util.Objects.equals(l, r);
            // Without rounding in play, ~= coincides with == .
            case APPROX -> java.util.Objects.equals(l, r);
            case AND -> (Boolean) l && (Boolean) r;
            case OR -> (Boolean) l || (Boolean) r;
        };
    }

    /**
     * Char operations: ordering and equality by code point, both operands
     * Char. Arithmetic and logical ops on chars are errors, as are mixed
     * Char/non-Char comparisons — fail closed until a conversion pair
     * (ord/chr) is ruled.
     */
    private static Object evalCharBinOp(IrExpr.BinOp op, Object l, Object r) {
        if (!(l instanceof sibarum.pontif.core.types.CharValue lc)
                || !(r instanceof sibarum.pontif.core.types.CharValue rc)) {
            throw new RuntimeCheckException(
                    "Char compares only with Char — got " + l + " " + opSymbol(op.op())
                            + " " + r + " (no Char/Int tower; ord/chr conversion is not "
                            + "yet a ruled operation)", op.origin());
        }
        return switch (op.op()) {
            case LT -> lc.codePoint() < rc.codePoint();
            case LE -> lc.codePoint() <= rc.codePoint();
            case GT -> lc.codePoint() > rc.codePoint();
            case GE -> lc.codePoint() >= rc.codePoint();
            case EQ -> lc.codePoint() == rc.codePoint();
            case NE -> lc.codePoint() != rc.codePoint();
            // Code points are exact values — ~= coincides with == .
            case APPROX -> lc.codePoint() == rc.codePoint();
            case ADD, SUB, MUL, DIV, MOD, POW, AND, OR -> throw new RuntimeCheckException(
                    "Operator '" + opSymbol(op.op()) + "' is not defined for Char — "
                            + "chars order and compare; they don't compute", op.origin());
        };
    }

    /**
     * String operations: ordering and equality lexicographically by code
     * point, both operands String. Arithmetic and logical ops on strings are
     * errors, as are mixed String/non-String comparisons — fail closed.
     * Concatenation is the stream {@code concat} combinator, not an operator.
     */
    private static Object evalStringBinOp(IrExpr.BinOp op, Object l, Object r) {
        if (!(l instanceof sibarum.pontif.core.types.StringValue ls)
                || !(r instanceof sibarum.pontif.core.types.StringValue rs)) {
            throw new RuntimeCheckException(
                    "String compares only with String — got " + l + " " + opSymbol(op.op())
                            + " " + r + " (no String/Char or String/Int tower)", op.origin());
        }
        int c = compareStringsByCodePoint(ls.content(), rs.content());
        return switch (op.op()) {
            case LT -> c < 0;
            case LE -> c <= 0;
            case GT -> c > 0;
            case GE -> c >= 0;
            case EQ -> c == 0;
            case NE -> c != 0;
            // Code points are exact values — ~= coincides with == .
            case APPROX -> c == 0;
            case ADD, SUB, MUL, DIV, MOD, POW, AND, OR -> throw new RuntimeCheckException(
                    "Operator '" + opSymbol(op.op()) + "' is not defined for String — "
                            + "strings order and compare; they don't compute "
                            + "(concatenation is the stream concat combinator)", op.origin());
        };
    }

    /** Lexicographic by Unicode code point (not UTF-16 char) — see Cmp. */
    private static int compareStringsByCodePoint(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    private static String opSymbol(IrExpr.Op op) {
        return switch (op) {
            case ADD -> "+"; case SUB -> "-"; case MUL -> "*"; case DIV -> "/"; case MOD -> "%"; case POW -> "^";
            case LT -> "<"; case LE -> "<="; case GT -> ">"; case GE -> ">=";
            case EQ -> "=="; case NE -> "!="; case APPROX -> "~=";
            case AND -> "&"; case OR -> "|";
        };
    }

    /**
     * Coerces an operand of decimal arithmetic to BigDecimal. Int promotes —
     * the lossless direction ({@code Int → Decimal} embeds exactly; the reverse
     * stays forbidden). Anything else is a clear, origin-carrying error.
     */
    private static BigDecimal asDecimal(Object v, IrExpr.BinOp op) {
        if (v instanceof BigDecimal d) return d;
        if (v instanceof Long n) return BigDecimal.valueOf(n);
        if (v instanceof Integer n) return BigDecimal.valueOf(n);
        throw new RuntimeCheckException(
                "Operator '" + symbol(op.op()) + "' applied to " + runtimeTypeName(v)
                        + " and Decimal operands — only Int promotes to Decimal.",
                op.origin());
    }

    /** Surface symbol for an operator, for error messages. */
    private static String symbol(IrExpr.Op op) {
        return switch (op) {
            case ADD -> "+"; case SUB -> "-"; case MUL -> "*";
            case DIV -> "/"; case MOD -> "%"; case POW -> "^";
            case LT -> "<"; case LE -> "<="; case GT -> ">"; case GE -> ">=";
            case EQ -> "=="; case NE -> "!="; case APPROX -> "~=";
            case AND -> "&"; case OR -> "|";
        };
    }

    /** Pontif-facing name of a runtime value's type, for error messages. */
    private static String runtimeTypeName(Object v) {
        if (v instanceof Long || v instanceof Integer) return "Int";
        if (v instanceof BigDecimal) return "Decimal";
        if (v instanceof Boolean) return "Bool";
        if (v instanceof RecordValue r) return r.typeName();
        return v == null ? "null" : v.getClass().getSimpleName();
    }

    private static Object evalDecimalBinOp(IrExpr.Op op, BigDecimal l, BigDecimal r, Origin origin) {
        return switch (op) {
            case ADD -> l.add(r);
            case SUB -> l.subtract(r);
            case MUL -> l.multiply(r);
            case DIV -> {
                if (r.signum() == 0) throw new RuntimeCheckException(
                        "Decimal division by zero: " + l.toPlainString() + " / 0", origin);
                yield l.divide(r, MathContext.DECIMAL128);   // lossy by explicit policy
            }
            case MOD -> {
                if (r.signum() == 0) throw new RuntimeCheckException(
                        "Decimal remainder by zero: " + l.toPlainString() + " % 0", origin);
                yield l.remainder(r);
            }
            case POW -> {
                int e;
                try {
                    e = r.intValueExact();
                } catch (ArithmeticException notInt) {
                    throw new RuntimeCheckException(
                            "Non-integer exponent " + r.toPlainString()
                                    + " — a Decimal to a non-integer power is transcendental "
                                    + "(out of scope)", origin);
                }
                if (e < 0) throw new RuntimeCheckException(
                        "Negative exponent " + e + " — non-negative integer powers only", origin);
                yield l.pow(e);
            }
            case LT -> l.compareTo(r) < 0;
            case LE -> l.compareTo(r) <= 0;
            case GT -> l.compareTo(r) > 0;
            case GE -> l.compareTo(r) >= 0;
            case EQ -> l.compareTo(r) == 0;
            case NE -> l.compareTo(r) != 0;
            // Equal within one ulp at the working precision — the tolerance is
            // exactly the loss the division policy declared (see Decimals).
            case APPROX -> sibarum.pontif.core.Decimals.approxEqual(l, r);
            // Logical ops never have Decimal operands (they're Bool-typed).
            case AND, OR -> throw new IllegalStateException(
                    "Logical operator " + op + " applied to Decimal operands");
        };
    }

    private Object evalCall(IrExpr.Call call, Environment env, CompiledModule module) {
        // Lexical scope wins: if the name is locally bound (let / param), invoke
        // the bound value as a closure rather than dispatching by name.
        if (env.contains(call.functionName())) {
            Object fnValue = env.lookup(call.functionName());
            // A bound metareference: application reruns registry dispatch
            // under the REFERENCED name — `ref(2)` does what `inc(2)` does,
            // candidates and narrowings intact.
            if (fnValue instanceof sibarum.pontif.core.types.DispatchValue dv) {
                if (call.args().size() != dv.keySorts().size()) {
                    throw new RuntimeCheckException(
                            "Metareference " + dv + " takes " + dv.keySorts().size()
                                    + " argument(s); got " + call.args().size(),
                            call.origin());
                }
                return dispatchByName(dv.functionName(), call, env, module);
            }
            if (!(fnValue instanceof Closure closure)) {
                throw new RuntimeCheckException(
                        "'" + call.functionName() + "' is bound locally but is not a closure; got "
                                + (fnValue == null ? "null" : fnValue.getClass().getSimpleName())
                                + ": " + fnValue,
                        call.origin());
            }
            List<Object> args = new ArrayList<>();
            for (IrExpr argExpr : call.args()) {
                args.add(eval(argExpr, env, module));
            }
            try {
                return closure.invoke(args, this, module);
            } catch (RuntimeCheckException rce) {
                if (rce.origin().isPresent()) {
                    throw rce;
                }
                throw new RuntimeCheckException(rce.getMessage(), call.origin(), rce);
            }
        }

        return dispatchByName(call.functionName(), call, env, module);
    }

    /**
     * Registry dispatch under {@code name} — the shared tail for direct
     * calls and metareference application (where {@code name} is the
     * referenced function, not the bound variable).
     */
    private Object dispatchByName(
            String name, IrExpr.Call call, Environment env, CompiledModule module) {
        List<Object> argValues = new ArrayList<>();
        List<SymExpr> argSymbolics = new ArrayList<>();
        for (IrExpr argExpr : call.args()) {
            Object argValue = eval(argExpr, env, module);
            argValues.add(argValue);
            argSymbolics.add(toSymExpr(argValue));
        }

        DispatchResult dr = module.dispatch().resolve(name, argSymbolics, checker(module));
        switch (dr) {
            case DispatchResult.NoMatch nm -> {
                // Application through a top-level binding: a module-level
                // `let ref = inc[Int]` declares a ZERO-ARG function; applying
                // it with args is the ()-law reaching through that sugar —
                // evaluate the binding, and if it holds a metareference,
                // re-dispatch under the referenced name.
                if (!call.args().isEmpty()) {
                    DispatchResult zero = module.dispatch().resolve(
                            name, List.of(), checker(module));
                    if (zero instanceof DispatchResult.Resolved z) {
                        CompiledModule.CompiledFunction zf = module.functions().get(z.decl());
                        if (zf != null && eval(zf.body(), Environment.empty(), module)
                                instanceof sibarum.pontif.core.types.DispatchValue dv) {
                            return dispatchByName(dv.functionName(), call, env, module);
                        }
                    }
                }
                throw new RuntimeCheckException(
                        "Dispatch failed for '" + name + "': " + nm.reason(),
                        call.origin());
            }
            case DispatchResult.Ambiguous a -> throw new RuntimeCheckException(
                    "Ambiguous dispatch for '" + name + "' between "
                            + a.candidates().size() + " candidate(s)",
                    call.origin());
            case DispatchResult.Resolved resolved -> {
                try {
                    resolved.call().executeChecks(Map.of(), checker(module));
                } catch (RuntimeCheckException rce) {
                    if (rce.origin().isPresent()) {
                        throw rce;
                    }
                    throw new RuntimeCheckException(rce.getMessage(), call.origin(), rce);
                }
                CompiledModule.CompiledFunction func = module.functions().get(resolved.decl());
                if (func == null) {
                    throw new IllegalStateException(
                            "Dispatch resolved to '" + resolved.decl().name()
                                    + "' but no body was registered in the compiled module");
                }
                Environment funcEnv = Environment.empty();
                for (int i = 0; i < func.params().size(); i++) {
                    funcEnv = funcEnv.extend(func.params().get(i).name(), argValues.get(i));
                }
                return eval(func.body(), funcEnv, module);
            }
        }
    }

    /** Sentinel: no attribute producer is registered for the accessed name. */
    private static final Object NO_ATTRIBUTE = new Object();

    /**
     * Resolves a trait attribute access {@code rec.name} to the satisfier's
     * computed producer {@code <typeName>.<name>(this)} — a 0-user-arg function
     * registered by an {@code assign trait} block. Returns {@link #NO_ATTRIBUTE}
     * if no such producer is dispatchable (the caller then surfaces the normal
     * "no field" error).
     */
    private Object tryAttributeProducer(RecordValue rec, String name, CompiledModule module) {
        if (rec.typeName() == null) return NO_ATTRIBUTE;
        DispatchResult dr = module.dispatch().resolve(
                rec.typeName() + "." + name, List.of(toSymExpr(rec)), checker(module));
        if (!(dr instanceof DispatchResult.Resolved resolved)) return NO_ATTRIBUTE;
        CompiledModule.CompiledFunction func = module.functions().get(resolved.decl());
        if (func == null || func.params().size() != 1) return NO_ATTRIBUTE;
        Environment env = Environment.empty().extend(func.params().get(0).name(), rec);
        return eval(func.body(), env, module);
    }

    private static SymExpr toSymExpr(Object value) {
        if (value instanceof Long l) return SymExpr.lit(l);
        if (value instanceof Integer i) return SymExpr.lit(i.longValue());
        if (value instanceof BigDecimal d) return SymExpr.dec(d);
        if (value instanceof sibarum.pontif.core.types.CharValue c) {
            return SymExpr.chr(c.codePoint());
        }
        if (value instanceof sibarum.pontif.core.types.StringValue s) {
            return SymExpr.str(s.content());
        }
        if (value instanceof sibarum.pontif.core.types.DispatchValue dv) {
            return new SymExpr.DispatchRef(dv.functionName(), dv.keySorts());
        }
        if (value instanceof Boolean b) return SymExpr.bool(b);
        if (value instanceof RecordValue r) {
            LinkedHashMap<String, SymExpr> members = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : r.members().entrySet()) {
                members.put(e.getKey(), toSymExpr(e.getValue()));
            }
            return SymExpr.record(r.typeName(), members);
        }
        throw new IllegalArgumentException(
                "Cannot convert runtime value to SymExpr (type "
                        + (value == null ? "null" : value.getClass().getSimpleName()) + "): " + value);
    }
}
