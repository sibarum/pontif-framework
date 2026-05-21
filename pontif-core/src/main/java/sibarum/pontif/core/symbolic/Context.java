package sibarum.pontif.core.symbolic;

import java.util.ArrayList;
import java.util.List;

public record Context(List<SymExpr> hypotheses) {

    public static final Context EMPTY = new Context(List.of());

    public Context {
        hypotheses = List.copyOf(hypotheses);
    }

    public static Context of(SymExpr... hypotheses) {
        return new Context(List.of(hypotheses));
    }

    public Context with(SymExpr hypothesis) {
        List<SymExpr> next = new ArrayList<>(hypotheses);
        next.add(hypothesis);
        return new Context(next);
    }

    public Context withAll(List<SymExpr> additional) {
        List<SymExpr> next = new ArrayList<>(hypotheses);
        next.addAll(additional);
        return new Context(next);
    }

    public boolean isEmpty() {
        return hypotheses.isEmpty();
    }
}
