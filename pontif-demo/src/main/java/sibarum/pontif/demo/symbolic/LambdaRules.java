package sibarum.pontif.demo.symbolic;

import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.Substitute;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.types.Sort;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class LambdaRules {

    private LambdaRules() {}

    public static final RewriteRule BETA = (expr, simp) -> {
        if (expr instanceof SymExpr.App(SymExpr fn, SymExpr arg)
                && fn instanceof SymExpr.Lam(String param, Sort paramType, SymExpr body)) {
            SymExpr reduced = Substitute.apply(body, Map.of(param, arg));
            return Optional.of(reduced);
        }
        return Optional.empty();
    };

    public static List<RewriteRule> all() {
        return List.of(BETA);
    }
}
