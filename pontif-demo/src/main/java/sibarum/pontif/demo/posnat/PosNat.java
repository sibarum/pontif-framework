package sibarum.pontif.demo.posnat;

import sibarum.pontif.ast.binary.Add;
import sibarum.pontif.core.types.RuleEngine;
import sibarum.pontif.core.types.RuleViolation;
import sibarum.pontif.core.types.Sort;

public final class PosNat {

    public static final Sort SORT = Sort.of("PosNat");

    private PosNat() {}

    public static RuleEngine engine() {
        return new RuleEngine()
                .register(PosLit.class, (node, kids, ctx) -> {
                    if (node.value() <= 0) {
                        throw new RuleViolation(
                                "PosLit value must be positive; got " + node.value());
                    }
                    return SORT;
                });
    }

    public static RuleEngine engineWithAdd() {
        return engine()
                .register(Add.class, (node, kids, ctx) -> {
                    if (!SORT.equals(kids.get(0)) || !SORT.equals(kids.get(1))) {
                        throw new RuleViolation(
                                "Add requires (PosNat, PosNat); got (" + kids.get(0) + ", " + kids.get(1) + ")");
                    }
                    return SORT;
                });
    }
}
