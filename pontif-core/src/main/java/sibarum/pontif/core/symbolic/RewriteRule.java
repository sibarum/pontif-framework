package sibarum.pontif.core.symbolic;

import java.util.Optional;

@FunctionalInterface
public interface RewriteRule {

    Optional<SymExpr> tryRewrite(SymExpr expr, Simplifier simplifier);
}
