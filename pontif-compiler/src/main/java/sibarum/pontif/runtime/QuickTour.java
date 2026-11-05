package sibarum.pontif.runtime;

/**
 * The canonical "Pontif quick tour" sample program — the playground's default
 * editor content. Lives here (rather than in {@code pontif-playground}) so it
 * has a single home both the playground app (downstream) and the runtime test
 * suite (which pins that it compiles, gates, and runs) can reference. Keeping
 * it in one place is load-bearing: when the proof vocabulary moved to the
 * builtin {@code std.proof} module, a hand-kept copy went stale — this constant
 * removes that hazard.
 */
public final class QuickTour {

    private QuickTour() {}

    /** Pontif-syntax source of the quick tour. Evaluates to {@code 25}. */
    public static final String SOURCE = """
            # Pontif quick tour — click Run to compile and evaluate this module.
            # Pontif PROVES every declared return refinement at compile time, or
            # rejects it. When the built-in prover falls short, you hand it a
            # proof. Comments start with #.

            module tour

            # The proof vocabulary (Leaf, Split) ships as a builtin module you
            # import — like a standard library. The `requires` line below brings
            # them into scope; no need to declare them yourself.
            requires std.proof.{Leaf, Split}

            # Most returns prove themselves. inc's declared return [Int:@>1] is
            # a linear bound: given x >= 1, the engine sees x + 1 lands in
            # [2, infinity) and clears the > 1 bar on its own — no help needed.
            function inc(x:[Int:@>=1]):[Int:@>1] -> x + 1

            # Some don't. quirk(x) = (x - 3) * (x + 5) dips to a minimum of -16
            # (at x = -1), so it's always >= -16 — but it's an opaque product
            # whose low point sits in the interior, and the built-in engine can't
            # see that. Declaring [Int:@>=-16] would be rejected on its own. So we
            # hand it a PROOF.

            # A proof is a tree of case-splits, built from Leaf and Split (the
            # types imported above). Split refers to itself through a
            # [Leaf|Split] union — it's a recursive type, like lists and trees.

            # Cut where interval reasoning works, and let the prover peel the rest:
            #   x >= 3   ->  both factors >= 0, so the product >= 0
            #   x <= -6  ->  both factors <= 0, so the product >= 9
            #   the leftover middle [-5, 2] is finite, so the prover enumerates it
            # The combinators are conservative: a bogus split can never validate,
            # so a proof rescues a true-but-hard return but never launders a
            # false one. (Delete the proof line and Run — quirk is rejected.)
            function quirk(x:Int):[Int:@>=-16] -> (x - 3) * (x + 5)
            proof quirk = Split(x >= 3, Leaf(), Split(x <= -6, Leaf(), Leaf()))

            # Main expression — runs when you click Run.
            # inc(4) = 5, quirk(5) = 20.  Sum: 25.
            inc(4) + quirk(5)
            """;
}
