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
        // A fragment with a generator codomain (a tuple mixing a Stream[T] output
        // channel with accumulator channels, no `&` input) is an unfold/generator:
        // applying it drives the step-until-the-guard-fails loop, not a single body
        // evaluation (docs/stream-war.md §7.9, slice 2f).
        if (IrInterpreter.isGeneratorCodomain(lambda.returnSort())) {
            return interpreter.driveGenerator(lambda, args, captured, module);
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
