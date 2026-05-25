package sibarum.pontif.receipts;

import sibarum.pontif.core.types.Sort;

/** A parameter binding at a call site (e.g., {@code n_0 : Int}). */
public record Param(String name, Sort sort) {

    public Param {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Param name must be non-empty");
        }
        if (sort == null) {
            throw new IllegalArgumentException("Param sort must be non-null");
        }
    }
}
