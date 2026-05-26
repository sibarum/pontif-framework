package sibarum.pontif.ast.match;

import com.oracle.truffle.api.frame.VirtualFrame;
import sibarum.pontif.core.PontifNode;
import sibarum.pontif.core.symbolic.Refinements;
import sibarum.pontif.core.symbolic.RuntimeCheckException;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.symbolic.algebra.ProofResult;
import sibarum.pontif.core.types.Sort;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class MatchNode extends PontifNode {

    @Child private PontifNode scrutinee;
    private final Sort[] patterns;
    @Children private final PontifNode[] results;
    private final Simplifier simplifier;

    private MatchNode(PontifNode scrutinee, Sort[] patterns, PontifNode[] results, Simplifier simplifier) {
        this.scrutinee = scrutinee;
        this.patterns = patterns;
        this.results = results;
        this.simplifier = simplifier;
    }

    public static MatchNode of(PontifNode scrutinee, Simplifier simplifier, List<Branch> branches) {
        if (scrutinee == null) {
            throw new IllegalArgumentException("Match scrutinee must be non-null");
        }
        if (simplifier == null) {
            throw new IllegalArgumentException("Match simplifier must be non-null");
        }
        if (branches.isEmpty()) {
            throw new IllegalArgumentException("Match must have at least one branch");
        }
        Sort[] patterns = new Sort[branches.size()];
        PontifNode[] results = new PontifNode[branches.size()];
        for (int i = 0; i < branches.size(); i++) {
            Branch b = branches.get(i);
            patterns[i] = b.pattern();
            results[i] = b.result();
        }
        return new MatchNode(scrutinee, patterns, results, simplifier);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object value = scrutinee.execute(frame);
        SymExpr symbolicValue = toSymExpr(value);
        for (int i = 0; i < patterns.length; i++) {
            ProofResult result = Refinements.satisfies(symbolicValue, patterns[i], simplifier);
            if (result instanceof ProofResult.Passed) {
                return results[i].execute(frame);
            }
            if (result instanceof ProofResult.Residual residual) {
                throw new RuntimeCheckException(
                        "Match branch " + i + " (pattern " + patterns[i]
                                + ") could not be decided at runtime against value " + value
                                + "; residual obligation: " + residual.obligation(),
                        origin());
            }
        }
        throw new RuntimeCheckException(
                "No match branch accepted value " + value + " against patterns " + Arrays.toString(patterns),
                origin());
    }

    @Override
    public List<PontifNode> children() {
        List<PontifNode> all = new ArrayList<>(1 + results.length);
        all.add(scrutinee);
        all.addAll(Arrays.asList(results));
        return all;
    }

    private static SymExpr toSymExpr(Object value) {
        if (value instanceof Long l) return SymExpr.lit(l);
        if (value instanceof Integer i) return SymExpr.lit(i.longValue());
        if (value instanceof Boolean b) return SymExpr.bool(b);
        if (value instanceof sibarum.pontif.ast.record.RecordValue r) {
            java.util.Map<String, SymExpr> members = new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<String, Object> e : r.members().entrySet()) {
                members.put(e.getKey(), toSymExpr(e.getValue()));
            }
            return SymExpr.record(r.typeName(), members);
        }
        throw new IllegalArgumentException(
                "Cannot convert runtime value to SymExpr (type "
                        + (value == null ? "null" : value.getClass().getSimpleName()) + "): " + value);
    }

    public record Branch(Sort pattern, PontifNode result) {
        public Branch {
            if (pattern == null) {
                throw new IllegalArgumentException("Branch pattern must be non-null");
            }
            if (result == null) {
                throw new IllegalArgumentException("Branch result must be non-null");
            }
        }

        public static Branch of(Sort pattern, PontifNode result) {
            return new Branch(pattern, result);
        }
    }
}
