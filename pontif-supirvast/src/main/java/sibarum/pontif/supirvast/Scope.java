package sibarum.pontif.supirvast;

import dev.supirvast.vastir.core.Expr;

/**
 * A lexical scope mapping a Pontif variable name to the SuperVast {@link Expr} it lowers to — a
 * {@code Read} of a local for a let-binding, a {@code BufferLoad} for a kernel input, an {@code InvocationId}
 * for the index parameter, and so on. Immutable and chained: {@link #with} returns a child that shadows; lookup
 * walks outward to the root.
 */
public final class Scope {

    private final Scope parent;
    private final String name;
    private final Expr value;

    private Scope(Scope parent, String name, Expr value) {
        this.parent = parent;
        this.name = name;
        this.value = value;
    }

    public static Scope empty() {
        return new Scope(null, null, null);
    }

    /** A child scope binding {@code name} to {@code value}, shadowing any outer binding of the same name. */
    public Scope with(String name, Expr value) {
        return new Scope(this, name, value);
    }

    /** The expression bound to {@code name}, or {@code null} if unbound. */
    public Expr lookup(String name) {
        for (Scope s = this; s != null; s = s.parent) {
            if (name.equals(s.name)) {
                return s.value;
            }
        }
        return null;
    }
}
