package sibarum.pontif.types;

/**
 * The decision {@link TypeSystem#coercionFor} returns for "a value of sort {@code from} is bound where
 * sort {@code to} is claimed" — <em>which</em> coercion (if any) the caller should emit, or that the two
 * sorts are simply different. It is a returned VALUE, not an inline insertion: the type system decides
 * <em>whether</em> and <em>how</em> the value coerces; the caller (today the parser's let-binding
 * lowering) turns that verdict into IR — the recorded binding sort and whether the claim travels on a
 * {@code LetIn} the construction gate judges. This is the seam that stops the parser from re-deciding
 * coercion itself (docs/language-inventory.md §4).
 *
 * <p>The variants mirror the cases the parser used to flag inline:
 * <ul>
 *   <li>{@link None} — the sorts agree (or the type system must abstain: an unknown floor, or a declared
 *       sort that is an unresolved alias). Bind at the value's own inferred sort.</li>
 *   <li>{@link IntToDecimal} — an {@code Int} value at a {@code Decimal} boundary: the lossless embedding
 *       (docs, {@code DecimalPromotion}). Bind at bare {@code Decimal}.</li>
 *   <li>{@link RecordPromotion} — an anonymous aggregate ({@code _record}) against a declared struct name:
 *       the promotion sugar {@code AggregatePromotion} stamps and validates at IR time. Bind at the
 *       declared sort; the stamp's own gate judgment is the claim check.</li>
 *   <li>{@link Demote} — the value's struct carries a base sort that demotes to the claimed base
 *       ({@code struct Point3D:[Point:…]}): a valid projection the {@code ConstructionGate} runs.</li>
 *   <li>{@link TraitCast} — a struct↔trait view (implicit both directions; the trait's attributes are
 *       information-conserving projections).</li>
 *   <li>{@link Autobox} — a tuple boxed as {@code Stream[T]} (docs/iteration.md §8.6): a clean forget of
 *       positional identity, gated by every element converting to {@code T}. Carries no runtime claim.</li>
 *   <li>{@link Mismatch} — the sorts are genuinely different and no coercion applies. {@code detail} is a
 *       specific reason (the tuple-element gate) or {@code null} to let the caller phrase the generic
 *       message with the binding's name.</li>
 * </ul>
 */
public sealed interface Coercion {

    /** The sorts agree, or the type system abstains — bind at the value's inferred sort. */
    record None() implements Coercion {}

    /** {@code Int} value → {@code Decimal} claim: the lossless embedding. Bind at bare {@code Decimal}. */
    record IntToDecimal() implements Coercion {}

    /** Anonymous aggregate → declared struct name: the promotion sugar. Bind at the declared sort. */
    record RecordPromotion() implements Coercion {}

    /** Struct base demotes to the claimed base: a projection the construction gate runs. */
    record Demote() implements Coercion {}

    /** Struct↔trait view — information-conserving, implicit in both directions. */
    record TraitCast() implements Coercion {}

    /** Tuple boxed as {@code Stream[T]}: a clean forget of positional identity. */
    record Autobox() implements Coercion {}

    /**
     * The sorts are different and no coercion applies. {@code detail} carries a specific reason (the
     * tuple-element gate names which element failed and against what); {@code null} means the generic
     * base mismatch, which the caller phrases with the binding's name (e.g. the parser's "let 'x' is
     * declared A but its value is B — these are different types.").
     */
    record Mismatch(String detail) implements Coercion {
        public static Mismatch generic() {
            return new Mismatch(null);
        }
    }
}
