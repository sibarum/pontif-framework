package sibarum.pontif.ir;

import sibarum.pontif.core.QualifiedName;

/**
 * The synthetic dispatch-key scheme for user-defined coercions
 * ({@code cast Target:(name:Source) -> body}, {@link IrStmt.Coercion}).
 *
 * <p>A coercion is registered in the ordinary dispatch table as a 1-param entry
 * under a reserved key keyed on the <b>target</b> base name, so the one shared
 * resolution engine selects the coercion by the value's runtime <b>source</b> sort
 * (multiple sources to one target, refined sources, and most-specific selection all
 * come free). The cast invocation {@code (Target:value)} looks the coercion up under
 * {@link #coerceKey} on the target's base.
 *
 * <p>The key is prefixed with {@code '#'}, which the lexer can never produce as an
 * identifier or operator name — so a coercion key can never collide with a real
 * function/operator/method dispatch key (see {@code CoercionsKeyTest}).
 */
public final class Coercions {

    /** Reserved prefix; {@code '#'} is not a legal IDENT/OP start, so collision-proof. */
    public static final String COERCE_PREFIX = "#coerce:";

    private Coercions() {}

    /**
     * The dispatch key a coercion to {@code targetSort} registers/resolves under.
     * Uses the target's base name (the {@code Int} of {@code [Int:@>0]}); a refined
     * target's refinement is enforced by the result-sort claim machinery, not the key.
     * Post-link the base is FQN'd ({@code mod/Type}); single-file it is bare —
     * matching how every other dispatch key is qualified.
     */
    public static String coerceKey(String targetBaseName) {
        return COERCE_PREFIX + targetBaseName;
    }

    /** The base (nominal) name of a sort, or null if it has none. */
    public static String baseName(IrSort sort) {
        if (sort == null) {
            return null;
        }
        return switch (sort) {
            case IrSort.Named n -> n.name();
            case IrSort.Refined r -> r.name();
            case IrSort.Structural s -> s.name();
            case IrSort.Trait t -> t.name();
            default -> null;
        };
    }

    /** The member (unqualified) base name — the local name a coercion's base resolves to. */
    public static String memberBaseName(IrSort sort) {
        String base = baseName(sort);
        return base == null ? null : QualifiedName.memberOf(base);
    }
}
