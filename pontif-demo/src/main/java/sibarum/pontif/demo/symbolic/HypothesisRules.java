package sibarum.pontif.demo.symbolic;

import sibarum.pontif.core.symbolic.Refinements;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.SymExpr;

import java.util.List;
import java.util.Optional;

public final class HypothesisRules {

    private HypothesisRules() {}

    public static final RewriteRule HYPOTHESIS_DISCHARGE = (expr, simp) -> {
        if (!(expr instanceof SymExpr.Cmp)) {
            return Optional.empty();
        }
        if (Refinements.discharge(simp.context().hypotheses(), expr)) {
            return Optional.of(SymExpr.bool(true));
        }
        return Optional.empty();
    };

    public static List<RewriteRule> all() {
        return List.of(HYPOTHESIS_DISCHARGE);
    }
}
