package sibarum.pontif.core.types;

import sibarum.pontif.core.PontifNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class RuleEngine {

    private final Map<Class<? extends PontifNode>, Rule<?>> rules = new HashMap<>();

    public <N extends PontifNode> RuleEngine register(Class<N> nodeType, Rule<N> rule) {
        rules.put(nodeType, rule);
        return this;
    }

    public Sort check(PontifNode node) {
        return check(node, TypingContext.empty());
    }

    public Sort check(PontifNode node, TypingContext ctx) {
        List<Sort> childSorts = node.children().stream()
                .map(child -> check(child, ctx))
                .toList();
        @SuppressWarnings("unchecked")
        Rule<PontifNode> rule = (Rule<PontifNode>) rules.get(node.getClass());
        if (rule == null) {
            throw new RuleViolation(
                    "No rule registered for node " + node.getClass().getSimpleName());
        }
        return rule.apply(node, childSorts, ctx);
    }
}
