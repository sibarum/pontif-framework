package sibarum.pontif.ir;

import sibarum.pontif.core.types.Sort;
import sibarum.pontif.predicates.Synthesis;

import java.util.List;
import java.util.Optional;

/**
 * Bridges the parser's {@link IrSort} to the prover's value synthesis
 * ({@link Synthesis}). The parser holds no synthesis logic of its own: it hands a
 * refinement {@code IrSort} here, this compiles it to a {@link Sort} and runs the
 * prover (the {@code BoundAnalysis} domain + the {@code Refinements.satisfies}
 * membership filter — the same engine that guards parameters), and the parser only
 * turns the resulting integers into IR literals.
 *
 * <p>A predicate outside the linear fragment (e.g. {@code %}, {@code /}, {@code ^})
 * fails to compile to a {@code SymExpr} and is reported, honestly, as not
 * synthesizable — so the single place that gates the fragment for guards gates it
 * for synthesis too.
 */
public final class SynthesisBridge {

    private SynthesisBridge() {}

    /**
     * The finite integer extension of a refinement {@code IrSort}, via the prover;
     * empty when not finitely synthesizable (a non-Int base, an unbounded side, or a
     * predicate outside the linear fragment).
     */
    public static Optional<List<Long>> enumerateInt(IrSort refinement) {
        Sort sort;
        try {
            sort = IrCompiler.compileSort(refinement);
        } catch (CompileException outsideFragment) {
            return Optional.empty();
        }
        return Synthesis.enumerateIntExtension(sort);
    }
}
