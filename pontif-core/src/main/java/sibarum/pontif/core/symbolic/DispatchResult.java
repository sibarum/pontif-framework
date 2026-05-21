package sibarum.pontif.core.symbolic;

import java.util.List;

public sealed interface DispatchResult
        permits DispatchResult.Resolved, DispatchResult.Ambiguous, DispatchResult.NoMatch {

    record Resolved(FunctionDecl decl, CompiledCall call) implements DispatchResult {}

    record Ambiguous(List<FunctionDecl> candidates) implements DispatchResult {
        public Ambiguous {
            candidates = List.copyOf(candidates);
        }
    }

    record NoMatch(String reason) implements DispatchResult {}

    static DispatchResult resolved(FunctionDecl decl, CompiledCall call) {
        return new Resolved(decl, call);
    }

    static DispatchResult ambiguous(List<FunctionDecl> candidates) {
        return new Ambiguous(candidates);
    }

    static DispatchResult noMatch(String reason) {
        return new NoMatch(reason);
    }

    default boolean isResolved() {
        return this instanceof Resolved;
    }
}
