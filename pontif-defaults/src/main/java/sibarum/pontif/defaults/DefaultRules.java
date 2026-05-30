package sibarum.pontif.defaults;

import sibarum.pontif.core.symbolic.ArithmeticRules;
import sibarum.pontif.core.symbolic.BooleanRules;
import sibarum.pontif.core.symbolic.CaseRules;
import sibarum.pontif.core.symbolic.HypothesisRules;
import sibarum.pontif.core.symbolic.LambdaRules;
import sibarum.pontif.core.symbolic.RefinementRules;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.StructuralRules;
import sibarum.pontif.core.symbolic.TotalExpressionRules;
import sibarum.pontif.core.symbolic.FunctionCheck;

import java.util.ArrayList;
import java.util.List;

/**
 * Canonical rule-set factories — the single source of truth for which
 * {@link RewriteRule}s the simplifier runs.
 *
 * <p>Lives in {@code pontif-defaults}, layered above both
 * {@code pontif-core} (rule definitions) and {@code pontif-predicates}
 * (decidability engines), so the canonical "production rule set" can
 * include rules backed by either. Putting it lower (in {@code pontif-core})
 * would gate it from the predicate-layer engines; putting it higher (in
 * {@code pontif-runtime}) would make it unreachable from {@code pontif-ir}
 * and {@code pontif-predicates} tests. This module is exactly the
 * crossroads.
 *
 * <p>Historically, ~28 test files each derived their own
 * {@code defaultRules()} / {@code combinedRules()} / {@code allRules()}
 * helpers. The drift hid a real bug: {@code StructuralRules} was missing
 * from the production set for a stretch, undetected because every demo
 * test that needed it pulled it in via its own local {@code allRules()}.
 * Centralizing the canonical sets here so test helpers and production
 * callers both delegate eliminates the drift.
 *
 * <h2>Sets</h2>
 * <ul>
 *   <li>{@link #production()} — the set Pontif's production compiler
 *       runs. The behavior under {@code PontifCompiler} with default
 *       configuration. Currently:
 *       {@link RefinementRules} + {@link ArithmeticRules} +
 *       {@link BooleanRules} + {@link StructuralRules} +
 *       {@link HypothesisRules} + {@link BoundAnalysisRules}.</li>
 *   <li>{@link #full()} — {@code production()} plus {@link LambdaRules}
 *       (for beta-reduction). Lambda evaluation isn't on the production
 *       path because lambda values are evaluated by the IR interpreter /
 *       Truffle backend, not by the simplifier.</li>
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
     *
     * <p>{@code HypothesisRules} gives the compile-time function-check
     * path ({@link FunctionCheck#verifyDefinition}) the same sign
     * reasoning the receipt-graph path has via {@code IntegerDischarge}.
     * {@code BoundAnalysisRules} layers linear-bound reasoning on top —
     * the threshold cases sign analysis alone can't decide
     * (e.g. {@code [Int:@>1]} from a hypothesis {@code @>=1}).
     */
    public static List<RewriteRule> production() {
        List<RewriteRule> rules = new ArrayList<>();
        rules.addAll(RefinementRules.all());
        rules.addAll(ArithmeticRules.all());
        rules.addAll(BooleanRules.all());
        rules.addAll(StructuralRules.all());
        rules.addAll(HypothesisRules.all());
        rules.addAll(BoundAnalysisRules.all());
        return rules;
    }

    /**
     * {@link #production()} plus {@link LambdaRules} — the set demo
     * tests treat as "everything the simplifier can do." Not currently
     * in production defaults; lambda evaluation lives in the IR
     * interpreter / Truffle backend.
     */
    public static List<RewriteRule> full() {
        List<RewriteRule> rules = production();
        rules.addAll(LambdaRules.all());
        return rules;
    }
}
