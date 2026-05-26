package sibarum.pontif.core.symbolic;

import sibarum.pontif.core.types.Sort;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class Simplifier {

    private final List<RewriteRule> rules;
    private final Context context;

    public Simplifier(List<RewriteRule> rules) {
        this(rules, Context.EMPTY);
    }

    public Simplifier(List<RewriteRule> rules, Context context) {
        this.rules = List.copyOf(rules);
        this.context = context;
    }

    public static Simplifier of(RewriteRule... rules) {
        return new Simplifier(List.of(rules));
    }

    public Simplifier withContext(Context context) {
        return new Simplifier(rules, context);
    }

    public Context context() {
        return context;
    }

    public SymExpr simplify(SymExpr expr) {
        SymExpr withReducedChildren = switch (expr) {
            case SymExpr.Var v -> v;
            case SymExpr.Lit l -> l;
            case SymExpr.Frac f -> f;
            case SymExpr.Bool b -> b;
            case SymExpr.Self s -> s;
            case SymExpr.Add(SymExpr l, SymExpr r) -> new SymExpr.Add(simplify(l), simplify(r));
            case SymExpr.Mul(SymExpr l, SymExpr r) -> new SymExpr.Mul(simplify(l), simplify(r));
            case SymExpr.Pow(SymExpr b, SymExpr e) -> new SymExpr.Pow(simplify(b), simplify(e));
            case SymExpr.Cmp(SymExpr l, SymExpr.CmpOp op, SymExpr r) -> new SymExpr.Cmp(simplify(l), op, simplify(r));
            case SymExpr.And(SymExpr l, SymExpr r) -> new SymExpr.And(simplify(l), simplify(r));
            case SymExpr.Or(SymExpr l, SymExpr r) -> new SymExpr.Or(simplify(l), simplify(r));
            case SymExpr.Lam(String param, Sort paramType, SymExpr body) -> new SymExpr.Lam(param, paramType, simplify(body));
            case SymExpr.App(SymExpr fn, SymExpr arg) -> new SymExpr.App(simplify(fn), simplify(arg));
            case SymExpr.Case(SymExpr scrutinee, List<SymExpr.CaseBranch> branches) -> {
                SymExpr newScrutinee = simplify(scrutinee);
                List<SymExpr.CaseBranch> newBranches = branches.stream()
                        .map(b -> new SymExpr.CaseBranch(b.pattern(), simplify(b.result())))
                        .toList();
                yield new SymExpr.Case(newScrutinee, newBranches);
            }
            case SymExpr.Record(Map<String, SymExpr> members, String typeName) -> {
                Map<String, SymExpr> simpMembers = new LinkedHashMap<>();
                for (Map.Entry<String, SymExpr> e : members.entrySet()) {
                    simpMembers.put(e.getKey(), simplify(e.getValue()));
                }
                yield new SymExpr.Record(simpMembers, typeName);
            }
            case SymExpr.FieldAccess(SymExpr base, String name) -> new SymExpr.FieldAccess(simplify(base), name);
        };

        for (RewriteRule rule : rules) {
            Optional<SymExpr> rewritten = rule.tryRewrite(withReducedChildren, this);
            if (rewritten.isPresent()) {
                return simplify(rewritten.get());
            }
        }
        return withReducedChildren;
    }
}
