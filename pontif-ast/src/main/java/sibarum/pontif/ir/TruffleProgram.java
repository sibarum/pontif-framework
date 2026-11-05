package sibarum.pontif.ir;

import com.oracle.truffle.api.CallTarget;
import sibarum.pontif.ast.func.FunctionRegistry;

public record TruffleProgram(CallTarget mainTarget, FunctionRegistry registry) {

    public Object run() {
        return mainTarget.call();
    }
}
