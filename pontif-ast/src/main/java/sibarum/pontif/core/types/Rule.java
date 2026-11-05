package sibarum.pontif.core.types;

import sibarum.pontif.core.PontifNode;

import java.util.List;

@FunctionalInterface
public interface Rule<N extends PontifNode> {

    Sort apply(N node, List<Sort> childSorts, TypingContext ctx);
}
