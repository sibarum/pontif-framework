package sibarum.pontif.receipts;

import sibarum.pontif.core.types.Sort;

/**
 * A symbolic variable with its declared sort. Used for result variables
 * ({@code r_0 : Int}) and for the result variables on {@link CallRef sub-calls}.
 */
public record Var(String name, Sort sort) {

    public Var {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Var name must be non-empty");
        }
        if (sort == null) {
            throw new IllegalArgumentException("Var sort must be non-null");
        }
    }
}
