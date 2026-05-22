package sibarum.pontif.ast.lambda;

import com.oracle.truffle.api.CallTarget;
import sibarum.pontif.core.Origin;
import sibarum.pontif.core.symbolic.RuntimeCheckException;

public final class LambdaValue {

    private final CallTarget callTarget;
    private final Object[] captures;
    private final int arity;
    private final Origin origin;

    public LambdaValue(CallTarget callTarget, Object[] captures, int arity, Origin origin) {
        this.callTarget = callTarget;
        this.captures = captures.clone();
        this.arity = arity;
        this.origin = origin == null ? Origin.NONE : origin;
    }

    public int arity() {
        return arity;
    }

    public Origin origin() {
        return origin;
    }

    public Object invoke(Object[] args) {
        if (args.length != arity) {
            throw new RuntimeCheckException(
                    "Closure arity mismatch: expected " + arity + " argument(s), got " + args.length,
                    origin);
        }
        Object[] combined = new Object[captures.length + args.length];
        System.arraycopy(captures, 0, combined, 0, captures.length);
        System.arraycopy(args, 0, combined, captures.length, args.length);
        return callTarget.call(combined);
    }

    @Override
    public String toString() {
        return "<closure " + arity + "-ary at " + origin + ">";
    }
}
