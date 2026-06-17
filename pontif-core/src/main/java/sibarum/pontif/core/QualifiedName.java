package sibarum.pontif.core;

/**
 * A fully-qualified compiler name, split into its two structural parts so the
 * module qualifier is never re-derived by hand-splitting a string.
 *
 * <p>Pontif's wire form for a linked name is {@code module + "/" + member}:
 * <ul>
 *   <li>{@code module} is a module FQN — dotted ({@code num.vector}), and it
 *       NEVER contains {@code '/'}. So the <b>first</b> {@code '/'} is the sole
 *       module↔member boundary.</li>
 *   <li>{@code member} is the local key. It keeps its own grammar: a method is
 *       {@code Type.method}; an operator overload's member is literally the
 *       operator symbol, which <b>may itself be {@code "/"}</b> (division). So a
 *       division overload in module {@code num.frac} has wire form
 *       {@code num.frac//} — member {@code "/"}, not {@code ""}.</li>
 * </ul>
 *
 * <p>That last case is exactly why hand-rolled splitting is fragile:
 * {@code lastIndexOf('/')} drops the division overload (member becomes
 * {@code ""}), and a bare {@code indexOf('/')} mistakes the leading slash of a
 * bare {@code "/"} operator for a separator. {@link #parse} encodes the one
 * correct rule — the first {@code '/'} is a separator only when a non-empty
 * module prefix precedes it — in a single place, so every call site agrees.
 *
 * <p>A bare (single-file / pre-link) name has an empty {@code module}; its
 * {@link #fqn()} is just the member, identical to today's un-linked keys.
 */
public record QualifiedName(String module, String member) {

    public QualifiedName {
        if (module == null) module = "";
        if (member == null) {
            throw new IllegalArgumentException("member must not be null");
        }
    }

    /**
     * Parses a wire-form name into (module, member). The first {@code '/'} is
     * the separator <b>only when a non-empty module prefix precedes it</b>:
     * <ul>
     *   <li>{@code "num.vector/+"} → module {@code "num.vector"}, member {@code "+"}</li>
     *   <li>{@code "num.frac//"}   → module {@code "num.frac"},   member {@code "/"}</li>
     *   <li>{@code "/"}            → bare (module {@code ""}),     member {@code "/"}
     *       (a leading slash is the division operator, not a separator)</li>
     *   <li>{@code "foo"}          → bare (module {@code ""}),     member {@code "foo"}</li>
     *   <li>{@code "num.frac/Frac"}→ module {@code "num.frac"},    member {@code "Frac"}</li>
     * </ul>
     */
    public static QualifiedName parse(String name) {
        int slash = name.indexOf('/');
        if (slash <= 0) {
            return new QualifiedName("", name);
        }
        return new QualifiedName(name.substring(0, slash), name.substring(slash + 1));
    }

    /** A qualified name from its parts. */
    public static QualifiedName of(String module, String member) {
        return new QualifiedName(module, member);
    }

    /** The member (local key) of a wire-form name — the part after the module. */
    public static String memberOf(String name) {
        return parse(name).member();
    }

    /** Whether this name carries a (non-empty) module qualifier. */
    public boolean isQualified() {
        return !module.isEmpty();
    }

    /** The wire form: {@code module/member}, or just {@code member} when bare. */
    public String fqn() {
        return module.isEmpty() ? member : module + "/" + member;
    }

    @Override
    public String toString() {
        return fqn();
    }
}
