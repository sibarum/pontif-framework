package sibarum.pontif.ir;

import sibarum.pontif.ast.record.RecordValue;
import sibarum.pontif.core.symbolic.DispatchResult;
import sibarum.pontif.core.symbolic.FunctionDecl;
import sibarum.pontif.core.symbolic.Refinements;
import sibarum.pontif.core.symbolic.RuntimeCheckException;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.symbolic.algebra.ProofResult;
import sibarum.pontif.core.types.Sort;

import java.math.BigDecimal;
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
            case IrExpr.Bool b -> b.value();
            case IrExpr.Var v -> env.lookup(v.name());
            case IrExpr.SelfRef s -> throw new IllegalStateException(
                    "Self has no runtime value — it is a typing-context placeholder");
            case IrExpr.BinOp op -> evalBinOp(op, env, module);
            case IrExpr.LetIn l -> {
                Object value = eval(l.value(), env, module);
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
                yield new RecordValue(r.typeName(), members);
            }
            case IrExpr.FieldAccess fa -> {
                Object baseValue = eval(fa.base(), env, module);
                if (!(baseValue instanceof RecordValue rec)) {
                    throw new RuntimeCheckException(
                            "Field access '." + fa.fieldName() + "' requires a record value, got "
                                    + (baseValue == null ? "null" : baseValue.getClass().getSimpleName())
                                    + ": " + baseValue,
                            fa.origin());
                }
                yield rec.get(fa.fieldName(), fa.origin());
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
        // Decimal operands (same-type only in this slice) use BigDecimal
        // arithmetic and compareTo-based comparison/equality, so 2.0 == 2.00.
        if (l instanceof BigDecimal || r instanceof BigDecimal) {
            return evalDecimalBinOp(op.op(), (BigDecimal) l, (BigDecimal) r);
        }
        return switch (op.op()) {
            case ADD -> (Long) l + (Long) r;
            case MUL -> (Long) l * (Long) r;
            case SUB -> (Long) l - (Long) r;
            case LT -> (Long) l < (Long) r;
            case LE -> (Long) l <= (Long) r;
            case GT -> (Long) l > (Long) r;
            case GE -> (Long) l >= (Long) r;
            case EQ -> java.util.Objects.equals(l, r);
            case NE -> !java.util.Objects.equals(l, r);
            case AND -> (Boolean) l && (Boolean) r;
            case OR -> (Boolean) l || (Boolean) r;
        };
    }

    private static Object evalDecimalBinOp(IrExpr.Op op, BigDecimal l, BigDecimal r) {
        return switch (op) {
            case ADD -> l.add(r);
            case SUB -> l.subtract(r);
            case MUL -> l.multiply(r);
            case LT -> l.compareTo(r) < 0;
            case LE -> l.compareTo(r) <= 0;
            case GT -> l.compareTo(r) > 0;
            case GE -> l.compareTo(r) >= 0;
            case EQ -> l.compareTo(r) == 0;
            case NE -> l.compareTo(r) != 0;
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

        List<Object> argValues = new ArrayList<>();
        List<SymExpr> argSymbolics = new ArrayList<>();
        for (IrExpr argExpr : call.args()) {
            Object argValue = eval(argExpr, env, module);
            argValues.add(argValue);
            argSymbolics.add(toSymExpr(argValue));
        }

        DispatchResult dr = module.dispatch().resolve(call.functionName(), argSymbolics, checker(module));
        switch (dr) {
            case DispatchResult.NoMatch nm -> throw new RuntimeCheckException(
                    "Dispatch failed for '" + call.functionName() + "': " + nm.reason(),
                    call.origin());
            case DispatchResult.Ambiguous a -> throw new RuntimeCheckException(
                    "Ambiguous dispatch for '" + call.functionName() + "' between "
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

    private static SymExpr toSymExpr(Object value) {
        if (value instanceof Long l) return SymExpr.lit(l);
        if (value instanceof Integer i) return SymExpr.lit(i.longValue());
        if (value instanceof BigDecimal d) return SymExpr.dec(d);
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
