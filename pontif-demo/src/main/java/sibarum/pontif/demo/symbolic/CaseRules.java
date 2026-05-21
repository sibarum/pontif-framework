package sibarum.pontif.demo.symbolic;

import sibarum.pontif.core.symbolic.Refinements;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.Substitute;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.symbolic.algebra.ProofResult;

import java.util.List;
import java.util.Optional;

public final class CaseRules {

    private CaseRules() {}

    public static final RewriteRule CASE_REDUCE = (expr, simp) -> {
        if (!(expr instanceof SymExpr.Case(SymExpr scrutinee, List<SymExpr.CaseBranch> branches))) {
            return Optional.empty();
        }
        for (SymExpr.CaseBranch branch : branches) {
            ProofResult result = Refinements.satisfies(scrutinee, branch.pattern(), simp);
            if (result instanceof ProofResult.Passed) {
                return Optional.of(Substitute.applySelf(branch.result(), scrutinee));
            }
            if (result instanceof ProofResult.Residual) {
                // Earlier branch is undecidable; we can't commit to any later branch
                // without violating first-match semantics. Leave the whole Case unreduced.
                return Optional.empty();
            }
            // Failed: try next branch
        }
        // No branch matched. Leave the Case unreduced — it represents an honest residual.
        return Optional.empty();
    };

    public static List<RewriteRule> all() {
        return List.of(CASE_REDUCE);
    }
}
