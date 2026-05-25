package sibarum.pontif.predicates;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.types.Sort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PredicateArithmeticTest {

    private static final Sort INT = Sort.of("Int");

    // --- Single-comparison shapes -------------------------------------------

    @Test
    void equality_to_zero_is_satisfiable_over_int() {
        SatResult result = PredicateArithmetic.satisfiable(cmp(SymExpr.CmpOp.EQ, 0), INT);
        assertTrue(result.isYes(), () -> "expected Yes, got " + result);
    }

    @Test
    void greater_than_zero_is_satisfiable_over_int() {
        SatResult result = PredicateArithmetic.satisfiable(cmp(SymExpr.CmpOp.GT, 0), INT);
        assertTrue(result.isYes(), () -> "expected Yes, got " + result);
    }

    @Test
    void flipped_comparison_subject_is_handled() {
        // 0 == @  (literal on left, subject on right)
        SymExpr predicate = SymExpr.cmp(SymExpr.lit(0), SymExpr.CmpOp.EQ, SymExpr.self());
        SatResult result = PredicateArithmetic.satisfiable(predicate, INT);
        assertTrue(result.isYes(), () -> "expected Yes, got " + result);
    }

    // --- Overload-overlap cases (the headline use) --------------------------

    @Test
    void factorial_overloads_are_disjoint() {
        // [Int:0] vs [Int:@>0] — should be disjoint, no overlap.
        SymExpr a = cmp(SymExpr.CmpOp.EQ, 0);
        SymExpr b = cmp(SymExpr.CmpOp.GT, 0);
        SatResult result = PredicateArithmetic.satisfiable(SymExpr.and(a, b), INT);
        assertTrue(result.isNo(), () -> "expected No (disjoint), got " + result);
    }

    @Test
    void overlapping_overloads_are_detected() {
        // [Int:@>=0] vs [Int:0] — overlaps at 0, should be detected.
        SymExpr a = cmp(SymExpr.CmpOp.GE, 0);
        SymExpr b = cmp(SymExpr.CmpOp.EQ, 0);
        SatResult result = PredicateArithmetic.satisfiable(SymExpr.and(a, b), INT);
        assertTrue(result.isYes(), () -> "expected Yes (overlaps at 0), got " + result);
    }

    @Test
    void positive_and_negative_overloads_are_disjoint() {
        // [Int:@>0] vs [Int:@<0]
        SymExpr a = cmp(SymExpr.CmpOp.GT, 0);
        SymExpr b = cmp(SymExpr.CmpOp.LT, 0);
        SatResult result = PredicateArithmetic.satisfiable(SymExpr.and(a, b), INT);
        assertTrue(result.isNo(), () -> "expected No, got " + result);
    }

    @Test
    void overlapping_bounded_ranges_are_satisfiable() {
        // @>=5 ∧ @<10 → integers 5..9
        SymExpr a = cmp(SymExpr.CmpOp.GE, 5);
        SymExpr b = cmp(SymExpr.CmpOp.LT, 10);
        SatResult result = PredicateArithmetic.satisfiable(SymExpr.and(a, b), INT);
        assertTrue(result.isYes(), () -> "expected Yes, got " + result);
    }

    @Test
    void adjacent_strict_bounds_have_no_integer_in_between() {
        // @>5 ∧ @<6 — empty over integers (5 < @ < 6 is impossible)
        SymExpr a = cmp(SymExpr.CmpOp.GT, 5);
        SymExpr b = cmp(SymExpr.CmpOp.LT, 6);
        SatResult result = PredicateArithmetic.satisfiable(SymExpr.and(a, b), INT);
        assertTrue(result.isNo(), () -> "expected No, got " + result);
    }

    @Test
    void boundary_inclusive_meets_strict_is_empty() {
        // @>=6 ∧ @<6 — empty
        SymExpr a = cmp(SymExpr.CmpOp.GE, 6);
        SymExpr b = cmp(SymExpr.CmpOp.LT, 6);
        SatResult result = PredicateArithmetic.satisfiable(SymExpr.and(a, b), INT);
        assertTrue(result.isNo(), () -> "expected No, got " + result);
    }

    // --- Refined domain folds into the check --------------------------------

    @Test
    void domain_refinement_excludes_value_not_in_domain() {
        // predicate @==0, domain [Int:@>0] — zero not in domain.
        SymExpr predicate = cmp(SymExpr.CmpOp.EQ, 0);
        Sort domain = Sort.refined("Int", cmp(SymExpr.CmpOp.GT, 0));
        SatResult result = PredicateArithmetic.satisfiable(predicate, domain);
        assertTrue(result.isNo(), () -> "expected No (0 not in [Int:@>0]), got " + result);
    }

    @Test
    void domain_refinement_allows_values_compatible_with_predicate() {
        // predicate @>=0, domain [Int:@>=5] — every value in domain satisfies @>=0.
        SymExpr predicate = cmp(SymExpr.CmpOp.GE, 0);
        Sort domain = Sort.refined("Int", cmp(SymExpr.CmpOp.GE, 5));
        SatResult result = PredicateArithmetic.satisfiable(predicate, domain);
        assertTrue(result.isYes(), () -> "expected Yes, got " + result);
    }

    @Test
    void domain_refinement_and_predicate_can_be_disjoint() {
        // predicate @<0, domain [Int:@>0]
        SymExpr predicate = cmp(SymExpr.CmpOp.LT, 0);
        Sort domain = Sort.refined("Int", cmp(SymExpr.CmpOp.GT, 0));
        SatResult result = PredicateArithmetic.satisfiable(predicate, domain);
        assertTrue(result.isNo(), () -> "expected No, got " + result);
    }

    // --- Boolean literal predicates -----------------------------------------

    @Test
    void bool_true_predicate_is_always_satisfiable() {
        SatResult result = PredicateArithmetic.satisfiable(SymExpr.bool(true), INT);
        assertTrue(result.isYes());
    }

    @Test
    void bool_false_predicate_is_never_satisfiable() {
        SatResult result = PredicateArithmetic.satisfiable(SymExpr.bool(false), INT);
        assertTrue(result.isNo());
    }

    // --- OR support (interval-set union) ------------------------------------

    @Test
    void or_of_two_singletons_is_satisfiable() {
        // @==0 | @==1 → {0, 1}
        SymExpr predicate = SymExpr.or(cmp(SymExpr.CmpOp.EQ, 0), cmp(SymExpr.CmpOp.EQ, 1));
        SatResult result = PredicateArithmetic.satisfiable(predicate, INT);
        assertTrue(result.isYes(), () -> "expected Yes, got " + result);
    }

    @Test
    void or_then_intersect_with_singleton_in_the_set() {
        // (@==0 | @==1) ∧ @==1 → {1}, non-empty
        SymExpr predicate = SymExpr.and(
                SymExpr.or(cmp(SymExpr.CmpOp.EQ, 0), cmp(SymExpr.CmpOp.EQ, 1)),
                cmp(SymExpr.CmpOp.EQ, 1));
        SatResult result = PredicateArithmetic.satisfiable(predicate, INT);
        assertTrue(result.isYes(), () -> "expected Yes, got " + result);
    }

    @Test
    void or_then_intersect_with_singleton_outside_the_set() {
        // (@==0 | @==1) ∧ @==2 → empty
        SymExpr predicate = SymExpr.and(
                SymExpr.or(cmp(SymExpr.CmpOp.EQ, 0), cmp(SymExpr.CmpOp.EQ, 1)),
                cmp(SymExpr.CmpOp.EQ, 2));
        SatResult result = PredicateArithmetic.satisfiable(predicate, INT);
        assertTrue(result.isNo(), () -> "expected No, got " + result);
    }

    @Test
    void or_of_half_lines_covers_everything_except_one_value() {
        // @<5 | @>5 — every integer except 5
        SymExpr predicate = SymExpr.or(cmp(SymExpr.CmpOp.LT, 5), cmp(SymExpr.CmpOp.GT, 5));
        SatResult result = PredicateArithmetic.satisfiable(predicate, INT);
        assertTrue(result.isYes(), () -> "expected Yes, got " + result);
    }

    @Test
    void or_of_adjacent_half_lines_covers_all_integers() {
        // @<=0 | @>=1 — covers everything (adjacent at the 0/1 boundary)
        SymExpr predicate = SymExpr.or(cmp(SymExpr.CmpOp.LE, 0), cmp(SymExpr.CmpOp.GE, 1));
        SatResult result = PredicateArithmetic.satisfiable(predicate, INT);
        assertTrue(result.isYes(), () -> "expected Yes, got " + result);
    }

    @Test
    void isEven_isOdd_return_sort_pattern() {
        // The playground's tour writes isEven/isOdd return as [Int:0|1].
        // Domain = [Int:@==0 | @==1]; ask: is @==1 satisfiable?
        SymExpr returnPred = SymExpr.or(cmp(SymExpr.CmpOp.EQ, 0), cmp(SymExpr.CmpOp.EQ, 1));
        Sort domain = Sort.refined("Int", returnPred);
        SatResult result = PredicateArithmetic.satisfiable(cmp(SymExpr.CmpOp.EQ, 1), domain);
        assertTrue(result.isYes(), () -> "expected Yes, got " + result);
    }

    @Test
    void isEven_isOdd_return_does_not_admit_two() {
        // Same domain [Int:0|1]; ask: is @==2 satisfiable? No.
        SymExpr returnPred = SymExpr.or(cmp(SymExpr.CmpOp.EQ, 0), cmp(SymExpr.CmpOp.EQ, 1));
        Sort domain = Sort.refined("Int", returnPred);
        SatResult result = PredicateArithmetic.satisfiable(cmp(SymExpr.CmpOp.EQ, 2), domain);
        assertTrue(result.isNo(), () -> "expected No, got " + result);
    }

    // --- NE support (now expressible as OR of two half-lines) ---------------

    @Test
    void not_equal_is_satisfiable() {
        // @ != 5  — many integers besides 5.
        SatResult result = PredicateArithmetic.satisfiable(cmp(SymExpr.CmpOp.NE, 5), INT);
        assertTrue(result.isYes(), () -> "expected Yes, got " + result);
    }

    @Test
    void not_equal_intersected_with_equality_is_unsatisfiable() {
        // (@ != 5) ∧ (@ == 5) → empty
        SymExpr predicate = SymExpr.and(cmp(SymExpr.CmpOp.NE, 5), cmp(SymExpr.CmpOp.EQ, 5));
        SatResult result = PredicateArithmetic.satisfiable(predicate, INT);
        assertTrue(result.isNo(), () -> "expected No, got " + result);
    }

    @Test
    void not_equal_intersected_with_unrelated_equality_is_satisfiable() {
        // (@ != 5) ∧ (@ == 3) → {3}, non-empty
        SymExpr predicate = SymExpr.and(cmp(SymExpr.CmpOp.NE, 5), cmp(SymExpr.CmpOp.EQ, 3));
        SatResult result = PredicateArithmetic.satisfiable(predicate, INT);
        assertTrue(result.isYes(), () -> "expected Yes, got " + result);
    }

    // --- Unsupported shapes return Unknown ----------------------------------

    @Test
    void multiplication_in_predicate_returns_unknown() {
        // @*2 == 0  — outside the comparison-against-literal fragment.
        SymExpr predicate = SymExpr.cmp(
                SymExpr.mul(SymExpr.self(), SymExpr.lit(2)),
                SymExpr.CmpOp.EQ,
                SymExpr.lit(0));
        SatResult result = PredicateArithmetic.satisfiable(predicate, INT);
        assertTrue(result.isUnknown(), () -> "expected Unknown, got " + result);
    }

    @Test
    void non_int_domain_returns_unknown() {
        // Bool base not in current slice.
        SatResult result = PredicateArithmetic.satisfiable(
                cmp(SymExpr.CmpOp.EQ, 0), Sort.of("Bool"));
        assertTrue(result.isUnknown(), () -> "expected Unknown for non-Int base, got " + result);
    }

    // --- Complement ---------------------------------------------------------

    @Test
    void complement_of_ge_zero_over_int_is_lt_zero() {
        // complement(@>=0, Int) → @<=-1  (canonical form for [MIN, -1])
        ComplementResult result = PredicateArithmetic.complement(
                cmp(SymExpr.CmpOp.GE, 0), INT);
        assertTrue(result.isComputed(), () -> "expected Computed, got " + result);
        SymExpr expected = SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.LE, SymExpr.lit(-1));
        assertEquals(expected, ((ComplementResult.Computed) result).predicate());
    }

    @Test
    void complement_of_singleton_zero_over_int_is_two_half_lines() {
        // complement(@==0, Int) → @<=-1 | @>=1
        ComplementResult result = PredicateArithmetic.complement(
                cmp(SymExpr.CmpOp.EQ, 0), INT);
        assertTrue(result.isComputed());
        SymExpr expected = SymExpr.or(
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.LE, SymExpr.lit(-1)),
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GE, SymExpr.lit(1)));
        assertEquals(expected, ((ComplementResult.Computed) result).predicate());
    }

    @Test
    void complement_of_bool_true_is_bool_false() {
        ComplementResult result = PredicateArithmetic.complement(SymExpr.bool(true), INT);
        assertTrue(result.isComputed());
        assertEquals(SymExpr.bool(false), ((ComplementResult.Computed) result).predicate());
    }

    @Test
    void complement_of_bool_false_is_bool_true() {
        ComplementResult result = PredicateArithmetic.complement(SymExpr.bool(false), INT);
        assertTrue(result.isComputed());
        assertEquals(SymExpr.bool(true), ((ComplementResult.Computed) result).predicate());
    }

    @Test
    void complement_constrained_to_domain_drops_outside() {
        // domain = [Int:@>=0]; predicate = @>=5
        // complement should be: values >=0 that don't satisfy @>=5 → {0..4}
        // Encoded as @>=0 ∧ @<=4
        Sort domain = Sort.refined("Int", cmp(SymExpr.CmpOp.GE, 0));
        ComplementResult result = PredicateArithmetic.complement(cmp(SymExpr.CmpOp.GE, 5), domain);
        assertTrue(result.isComputed(), () -> "expected Computed, got " + result);
        SymExpr expected = SymExpr.and(
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GE, SymExpr.lit(0)),
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.LE, SymExpr.lit(4)));
        assertEquals(expected, ((ComplementResult.Computed) result).predicate());
    }

    @Test
    void complement_fully_contained_in_domain_yields_false() {
        // domain = [Int:@>=5]; predicate = @>=0
        // every value in domain satisfies predicate → complement is empty
        Sort domain = Sort.refined("Int", cmp(SymExpr.CmpOp.GE, 5));
        ComplementResult result = PredicateArithmetic.complement(cmp(SymExpr.CmpOp.GE, 0), domain);
        assertTrue(result.isComputed());
        assertEquals(SymExpr.bool(false), ((ComplementResult.Computed) result).predicate());
    }

    @Test
    void match_underscore_desugar_for_sign_trichotomy() {
        // sign(n:Int) match arms: [@<0], [@==0], [_->?]
        // Caller union: @<0 | @==0
        // Complement over Int: @>=1  (i.e., @>0 in Int-canonical form is @>=1)
        SymExpr unionOfArms = SymExpr.or(cmp(SymExpr.CmpOp.LT, 0), cmp(SymExpr.CmpOp.EQ, 0));
        ComplementResult result = PredicateArithmetic.complement(unionOfArms, INT);
        assertTrue(result.isComputed());
        SymExpr expected = SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GE, SymExpr.lit(1));
        assertEquals(expected, ((ComplementResult.Computed) result).predicate());
    }

    @Test
    void totality_check_via_complement_succeeds_for_sign_trichotomy() {
        // sign trichotomy: arms = [@<0], [@==0], [@>0]
        // union = @<0 | @==0 | @>0
        // complement over Int must be empty (i.e., false) for totality.
        SymExpr unionOfArms = SymExpr.or(
                SymExpr.or(cmp(SymExpr.CmpOp.LT, 0), cmp(SymExpr.CmpOp.EQ, 0)),
                cmp(SymExpr.CmpOp.GT, 0));
        ComplementResult result = PredicateArithmetic.complement(unionOfArms, INT);
        assertTrue(result.isComputed());
        assertEquals(SymExpr.bool(false), ((ComplementResult.Computed) result).predicate());
    }

    @Test
    void totality_check_via_complement_finds_gap_for_non_total_match() {
        // arms = [@<0], [@>0]  — missing @==0; not total over Int
        SymExpr unionOfArms = SymExpr.or(cmp(SymExpr.CmpOp.LT, 0), cmp(SymExpr.CmpOp.GT, 0));
        ComplementResult result = PredicateArithmetic.complement(unionOfArms, INT);
        assertTrue(result.isComputed());
        // Gap is @==0
        SymExpr expected = SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.EQ, SymExpr.lit(0));
        assertEquals(expected, ((ComplementResult.Computed) result).predicate());
    }

    @Test
    void complement_of_unsupported_predicate_returns_unknown() {
        // @*2 == 0 — outside the fragment.
        SymExpr predicate = SymExpr.cmp(
                SymExpr.mul(SymExpr.self(), SymExpr.lit(2)),
                SymExpr.CmpOp.EQ,
                SymExpr.lit(0));
        ComplementResult result = PredicateArithmetic.complement(predicate, INT);
        assertTrue(result.isUnknown(), () -> "expected Unknown, got " + result);
    }

    @Test
    void complement_with_non_int_domain_returns_unknown() {
        ComplementResult result = PredicateArithmetic.complement(
                cmp(SymExpr.CmpOp.EQ, 0), Sort.of("Bool"));
        assertTrue(result.isUnknown());
    }

    // --- Helpers ------------------------------------------------------------

    /** Builds {@code @ op n} as a SymExpr. */
    private static SymExpr cmp(SymExpr.CmpOp op, long literal) {
        return SymExpr.cmp(SymExpr.self(), op, SymExpr.lit(literal));
    }
}
