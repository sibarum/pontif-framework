package sibarum.pontif.core.symbolic;

import java.util.ArrayList;
import java.util.List;

/**
 * Canonical rule-set factories — the single source of truth for which
 * {@link RewriteRule}s the simplifier runs.
 *
 * <p>Historically, ~28 test files each derived their own
 * {@code defaultRules()} / {@code combinedRules()} / {@code allRules()}
 * helpers. The drift hid a real bug: {@code StructuralRules} was missing
 * from {@code PontifCompiler.defaultRules()} for a stretch, undetected
 * because every demo test that needed it pulled it in via its own local
 * {@code allRules()}. Centralizing the canonical sets here so test
 * helpers and production callers both delegate eliminates the drift.
 *
 * <h2>Sets</h2>
 * <ul>
 *   <li>{@link #production()} — the set Pontif's production compiler
 *       runs. The behavior under {@code PontifCompiler} with default
 *       configuration. Currently:
 *       {@link RefinementRules} + {@link ArithmeticRules} +
 *       {@link BooleanRules} + {@link StructuralRules}.</li>
 *   <li>{@link #full()} — {@code production()} plus the optional sets
 *       most demo tests need: {@link HypothesisRules} (for dispatch /
 *       function-check reasoning) and {@link LambdaRules} (for
 *       beta-reduction). Whether either should join production is a
 *       deliberate downstream decision, not an accidental test-drift
 *       outcome.</li>
 * </ul>
 *
 * <p>Each factory returns a fresh mutable {@code ArrayList} so callers
 * can append bespoke rules ({@link CaseRules}, {@link TotalExpressionRules},
 * etc.) without mutating shared state.
 */
public final class DefaultRules {

    private DefaultRules() {}

    /**
     * The rule set Pontif's production compiler runs.
     *
     * <p>{@code StructuralRules} is required for refined struct sorts
     * ({@code [Point:@.x > 0]}) to actually reduce — the field-projection
     * rule turns {@code Record(...).x} into the field value so the
     * comparison can fold against a literal.
     */
    public static List<RewriteRule> production() {
        List<RewriteRule> rules = new ArrayList<>();
        rules.addAll(RefinementRules.all());
        rules.addAll(ArithmeticRules.all());
        rules.addAll(BooleanRules.all());
        rules.addAll(StructuralRules.all());
        return rules;
    }

    /**
     * {@link #production()} plus {@link HypothesisRules} and
     * {@link LambdaRules} — the set most demo tests treat as "everything
     * the simplifier can do." Not currently in production defaults;
     * promoting either is a deliberate decision (see the
     * sign/linear-discharge-in-production item in {@code docs/TODO.md}).
     */
    public static List<RewriteRule> full() {
        List<RewriteRule> rules = production();
        rules.addAll(HypothesisRules.all());
        rules.addAll(LambdaRules.all());
        return rules;
    }
}
