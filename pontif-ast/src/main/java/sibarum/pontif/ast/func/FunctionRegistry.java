package sibarum.pontif.ast.func;

import com.oracle.truffle.api.CallTarget;
import sibarum.pontif.core.symbolic.FunctionDecl;

import java.util.HashMap;
import java.util.Map;

public final class FunctionRegistry {

    private final Map<FunctionDecl, CallTarget> targets = new HashMap<>();

    public void register(FunctionDecl decl, CallTarget target) {
        targets.put(decl, target);
    }

    public CallTarget callTarget(FunctionDecl decl) {
        return targets.get(decl);
    }

    public int size() {
        return targets.size();
    }
}
