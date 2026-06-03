package sibarum.pontif.conservation;

import sibarum.pontif.conservation.ConservationLedger.ConservationBranch;
import sibarum.pontif.conservation.ConservationLedger.ConservationNode;
import sibarum.pontif.conservation.ConservationQueries.InputFate;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrStmt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The conservation half of the proof surface: binds {@code proof f = …}
 * statements whose tree head is a {@code std.conservation} property
 * constructor, and evaluates the bound assertions against the drafted
 * {@link ConservationLedger}. The sibling of the receipts module's
 * {@code ProofBinding}/{@code RefinementProof} pair — one {@code proof}
 * statement, two ledgers, the head's vocabulary picks which.
 *
 * <p>Unlike algebraic proofs, conservation assertions need NO refined return
 * sort and accept multi-branch targets — branch quantification lives inside
 * the property (e.g. losslessness is an every-branch claim by definition).
 *
 * <p>Property names are provisional pending vocabulary review.
 */
public final class ConservationProofs {

    private ConservationProofs() {}

    /** The property-constructor local names this binder claims. */
    public static final Set<String> HEAD_NAMES =
            Set.of("Lossless", "Reversible", "NoDuplication", "LosslessExcept");

    /** The named conservation properties — the v1 assertion library. */
    public sealed interface Assertion {
        record Lossless() implements Assertion {}
        record Reversible() implements Assertion {}
        record NoDuplication() implements Assertion {}
        /** Intentional erasure: everything reaches output EXCEPT {@code dropped}, which must not. */
        record LosslessExcept(AttributePath dropped) implements Assertion {}
    }

    public record Result(Map<String, Assertion> assertions, List<String> problems) {
        public Result {
            assertions = Map.copyOf(assertions);
            problems = List.copyOf(problems);
        }
    }

    /** True when the proof tree's head is a conservation property constructor. */
    public static boolean isConservationTree(IrExpr tree) {
        String head = headLocalName(tree);
        return head != null && HEAD_NAMES.contains(head);
    }

    /**
     * Binds every conservation-headed {@code proof} statement to its target
     * function. Never throws — malformed trees become problems. No
     * refined-return or single-branch requirement (contrast ProofBinding).
     */
    public static Result bind(IrModule module) {
        Map<String, IrStmt.FunctionDecl> fns = new LinkedHashMap<>();
        for (IrStmt stmt : module.statements()) {
            if (stmt instanceof IrStmt.FunctionDecl fd) fns.putIfAbsent(fd.name(), fd);
        }
        Map<String, Assertion> assertions = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (IrStmt stmt : module.statements()) {
            if (!(stmt instanceof IrStmt.Proof p) || !isConservationTree(p.proofTree())) {
                continue;
            }
            IrStmt.FunctionDecl target = fns.get(p.functionName());
            if (target == null) {
                problems.add("Conservation proof references unknown function '"
                        + p.functionName() + "'.");
                continue;
            }
            try {
                Assertion assertion = interpret(p.proofTree(), target);
                if (assertions.put(p.functionName(), assertion) != null) {
                    problems.add("Duplicate conservation proof for '" + p.functionName()
                            + "' — at most one per function.");
                }
            } catch (IllegalArgumentException bad) {
                problems.add("Conservation proof for '" + p.functionName()
                        + "' could not be used: " + bad.getMessage());
            }
        }
        return new Result(assertions, problems);
    }

    /**
     * Interprets one (unevaluated) property tree. {@code LosslessExcept}'s
     * argument is an attribute expression over the target's params
     * ({@code s.email}), interpreted to the ledger's call-instance path
     * ({@code s_0.email}) — the same param-renaming convention as the drafter.
     */
    private static Assertion interpret(IrExpr tree, IrStmt.FunctionDecl target) {
        String head = headLocalName(tree);
        List<IrExpr> args = argumentsOf(tree);
        return switch (head) {
            case "Lossless" -> new Assertion.Lossless();
            case "Reversible" -> new Assertion.Reversible();
            case "NoDuplication" -> new Assertion.NoDuplication();
            case "LosslessExcept" -> {
                if (args.size() != 1) {
                    throw new IllegalArgumentException(
                            "LosslessExcept takes exactly one dropped attribute (got "
                                    + args.size() + ")");
                }
                yield new Assertion.LosslessExcept(attributePathOf(args.get(0), target));
            }
            default -> throw new IllegalArgumentException("unknown property '" + head + "'");
        };
    }

    /** A {@code Var}/{@code FieldAccess} chain over a param → its ledger path. */
    private static AttributePath attributePathOf(IrExpr expr, IrStmt.FunctionDecl target) {
        if (expr instanceof IrExpr.Var v) {
            boolean isParam = target.params().stream()
                    .anyMatch(p -> p.name().equals(v.name()));
            if (!isParam) {
                throw new IllegalArgumentException("'" + v.name()
                        + "' is not a parameter of the target function");
            }
            return AttributePath.of(v.name() + "_0");
        }
        if (expr instanceof IrExpr.FieldAccess fa) {
            return attributePathOf(fa.base(), target).child(fa.fieldName());
        }
        throw new IllegalArgumentException(
                "expected an attribute expression over a parameter (e.g. s.email)");
    }

    // --- evaluation ---------------------------------------------------------

    /**
     * Evaluates an assertion against its function's ledger node. Empty =
     * proven; otherwise a failure message ending with the printed node — the
     * error IS the receipt. Fail-closed throughout: opaque or call-mediated
     * flow never certifies.
     */
    public static Optional<String> evaluate(
            String functionName, Assertion assertion, ConservationNode node) {
        Optional<String> failure = switch (assertion) {
            case Assertion.Lossless a -> ConservationQueries.lossless(node)
                    ? Optional.empty()
                    : firstFateViolation(node, Set.of(
                            InputFate.EMITTED_VERBATIM, InputFate.FLOWS_DERIVED), null);
            case Assertion.Reversible a -> ConservationQueries.verbatimBijection(node)
                    ? Optional.empty()
                    : Optional.of("dataflow is not a verbatim bijection "
                            + "(combination, duplication, drop, or untraceable flow present)");
            case Assertion.NoDuplication a -> ConservationQueries.duplicated(node)
                    ? Optional.of("an input attribute's content is emitted more than once")
                    : Optional.empty();
            case Assertion.LosslessExcept a -> evaluateLosslessExcept(node, a.dropped());
        };
        return failure.map(reason -> "Conservation proof for '" + functionName
                + "' failed: " + reason + "\n\n" + ConservationLedgerPrinter.printNode(node));
    }

    /**
     * Intentional erasure, both directions: every atom NOT covered by
     * {@code dropped} must reach an output in every branch, and every atom
     * covered by it must NOT — if the drop disappears, the proof is stale and
     * fails (future changes to the algorithm are protected).
     */
    private static Optional<String> evaluateLosslessExcept(
            ConservationNode node, AttributePath dropped) {
        boolean coversAnything = node.inputs().stream().anyMatch(dropped::covers);
        if (!coversAnything) {
            return Optional.of("'" + dropped + "' names no input attribute of this function");
        }
        for (ConservationBranch branch : node.branches()) {
            for (AttributePath atom : node.inputs()) {
                InputFate fate = ConservationQueries.fateOf(branch, atom);
                if (dropped.covers(atom)) {
                    if (fate == InputFate.EMITTED_VERBATIM || fate == InputFate.FLOWS_DERIVED) {
                        return Optional.of("'" + atom + "' is declared dropped but now flows "
                                + "into the output — the proof is stale; update or remove it");
                    }
                    if (fate == InputFate.OPAQUE) {
                        return Optional.of("'" + atom + "' is inside untraceable flow — "
                                + "cannot certify the declared drop");
                    }
                } else if (fate != InputFate.EMITTED_VERBATIM
                        && fate != InputFate.FLOWS_DERIVED) {
                    return Optional.of("'" + atom + "' does not reach the output ("
                            + fate + ") and is not in the declared-dropped set");
                }
            }
        }
        return Optional.empty();
    }

    /** First lossless violation, for the diagnostic line. */
    private static Optional<String> firstFateViolation(
            ConservationNode node, Set<InputFate> allowed, AttributePath except) {
        for (ConservationBranch branch : node.branches()) {
            for (AttributePath atom : node.inputs()) {
                if (except != null && except.covers(atom)) continue;
                InputFate fate = ConservationQueries.fateOf(branch, atom);
                if (!allowed.contains(fate)) {
                    return Optional.of("'" + atom + "' is " + fate
                            + " in some branch — every input must reach the output");
                }
            }
        }
        return Optional.of("losslessness does not hold");
    }

    private static String headLocalName(IrExpr tree) {
        String name = switch (tree) {
            case IrExpr.Record r -> r.typeName();
            case IrExpr.Call c -> c.functionName();
            default -> null;
        };
        if (name == null) return null;
        int slash = name.lastIndexOf('/');
        return slash >= 0 ? name.substring(slash + 1) : name;
    }

    private static List<IrExpr> argumentsOf(IrExpr tree) {
        return switch (tree) {
            case IrExpr.Record r -> List.copyOf(r.members().values());
            case IrExpr.Call c -> c.args();
            default -> List.of();
        };
    }
}
