package sibarum.pontif.core.symbolic;

import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.Substitute;
import sibarum.pontif.core.symbolic.SymExpr;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class StructuralRules {

    private StructuralRules() {}

    /**
     * Projects a member out of a literal record, substituting Self in the projected
     * value with the enclosing record. This is what makes method bodies work:
     * `record.method` returns the lambda with Self already bound to the record,
     * so subsequent App / further field accesses see the receiver correctly.
     * For data members, Self substitution is a no-op (Self typically doesn't appear).
     */
    public static final RewriteRule FIELD_ACCESS_ON_RECORD = (expr, simp) -> {
        if (expr instanceof SymExpr.FieldAccess(SymExpr.Record record, String name)
                && record.members().containsKey(name)) {
            SymExpr memberValue = record.members().get(name);
            return Optional.of(Substitute.applySelf(memberValue, record));
        }
        return Optional.empty();
    };

    public static List<RewriteRule> all() {
        return List.of(FIELD_ACCESS_ON_RECORD);
    }
}
