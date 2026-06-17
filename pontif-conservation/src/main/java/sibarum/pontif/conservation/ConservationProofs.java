package sibarum.pontif.conservation;

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
 * The conservation half of the proof surface, restated over the ratified
 * algebra: binds {@code proof f = …} statements whose tree head is a
 * {@code std.conservation} property constructor, and evaluates the bound
 * assertions against the drafted {@link ConservationGraph}s. One {@code proof}
 * statement, two ledgers — the head's vocabulary picks which.
 *
 * <p>The headline property is {@code DataConservative} (the name `Lossless`
 * is RESERVED for the cross-ledger algebraic+conservation property): every
 * Int/Decimal input atom flows into the return; every Bool atom flows or is
 * spent in branching — the capacity law.
 */
public final class ConservationProofs {

    private ConservationProofs() {}

    /** The property-constructor local names this binder claims. */
    public static final Set<String> HEAD_NAMES = Set.of(
            "DataConservative", "Reversible", "NoDuplication", "DataConservativeExcept");

    /** The named conservation properties. */
    public sealed interface Assertion {
        record DataConservative() implements Assertion {}
        record Reversible() implements Assertion {}
        record NoDuplication() implements Assertion {}
        /** Intentional erasure: everything conserved EXCEPT {@code dropped}, which must not flow. */
        record DataConservativeExcept(AttributePath dropped) implements Assertion {}
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
     * Binds every conservation-headed {@code proof} statement. Never throws —
     * malformed trees become problems. No refined-return or single-branch
     * requirement (branch quantification lives inside the properties).
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

    private static Assertion interpret(IrExpr tree, IrStmt.FunctionDecl target) {
        String head = headLocalName(tree);
        List<IrExpr> args = argumentsOf(tree);
        return switch (head) {
            case "DataConservative" -> new Assertion.DataConservative();
            case "Reversible" -> new Assertion.Reversible();
            case "NoDuplication" -> new Assertion.NoDuplication();
            case "DataConservativeExcept" -> {
                if (args.size() != 1) {
                    throw new IllegalArgumentException(
                            "DataConservativeExcept takes exactly one dropped attribute (got "
                                    + args.size() + ")");
                }
                yield new Assertion.DataConservativeExcept(
                        attributePathOf(args.get(0), target));
            }
            default -> throw new IllegalArgumentException("unknown property '" + head + "'");
        };
    }

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

    /**
     * Evaluates an assertion against its function's graph. Empty = proven;
     * otherwise a failure message ending with the printed graph — the error
     * IS the receipt. Fail-closed on residual flow throughout.
     */
    public static Optional<String> evaluate(
            String functionName, Assertion assertion, ConservationGraph graph) {
        Optional<String> failure = switch (assertion) {
            case Assertion.DataConservative a ->
                    ConservationQueries.dataConservative(graph);
            case Assertion.DataConservativeExcept a ->
                    ConservationQueries.dataConservativeExcept(graph, a.dropped());
            case Assertion.Reversible a -> ConservationQueries.reversible(graph);
            case Assertion.NoDuplication a -> ConservationQueries.duplicated(graph)
                    ? Optional.of("an input attribute's content is placed more than once")
                    : Optional.empty();
        };
        return failure.map(reason -> "Conservation proof for '" + functionName
                + "' failed: " + reason + "\n\n"
                + ConservationLedgerPrinter.printNode(graph));
    }

    private static String headLocalName(IrExpr tree) {
        String name = switch (tree) {
            case IrExpr.Record r -> r.typeName();
            case IrExpr.Call c -> c.functionName();
            default -> null;
        };
        if (name == null) return null;
        return sibarum.pontif.core.QualifiedName.memberOf(name);
    }

    private static List<IrExpr> argumentsOf(IrExpr tree) {
        return switch (tree) {
            case IrExpr.Record r -> List.copyOf(r.members().values());
            case IrExpr.Call c -> c.args();
            default -> List.of();
        };
    }
}
