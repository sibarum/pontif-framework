package sibarum.pontif.ir;

import sibarum.pontif.core.symbolic.RuntimeCheckException;

import java.util.List;

/**
 * Runtime representation of a lambda value. Captures the lexical environment at
 * the point of lambda creation. Invocation extends the captured environment with
 * the supplied argument values and evaluates the body via the interpreter.
 */
public record Closure(IrExpr.Lambda lambda, Environment captured) {

    public Object invoke(List<Object> args, IrInterpreter interpreter, CompiledModule module) {
        if (args.size() != lambda.params().size()) {
            throw new RuntimeCheckException(
                    "Closure arity mismatch: expected " + lambda.params().size()
                            + " argument(s), got " + args.size(),
                    lambda.origin());
        }
        Environment env = captured;
        for (int i = 0; i < args.size(); i++) {
            env = env.extend(lambda.params().get(i).name(), args.get(i));
        }
        return interpreter.eval(lambda.body(), env, module);
    }

    @Override
    public String toString() {
        return "<closure " + lambda.params().size() + "-ary at " + lambda.origin() + ">";
    }
}
