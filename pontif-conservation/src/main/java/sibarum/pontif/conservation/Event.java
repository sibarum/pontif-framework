package sibarum.pontif.conservation;

import java.util.List;

/**
 * One dataflow event in a branch's ledger. The taxonomy is James's: reads
 * have kinds — read to determine branching ({@link Consult}), read to operate
 * with another value ({@link Combine}), set into a return object
 * ({@link Emit}) — and conservation outcomes are functions of the event
 * combination and sequence, not read counts. Duplication is multiple
 * <em>emissions</em>, never multiple reads.
 *
 * <p>Names are provisional — to be renamed once the printed ledgers have
 * been reviewed and the vocabulary settles.
 */
public sealed interface Event {

    /**
     * Read to determine branching: the listed attribute paths were consulted
     * by this branch's guard. Content is not moved — control now depends on it.
     */
    record Consult(List<AttributePath> subjects) implements Event {
        public Consult { subjects = List.copyOf(subjects); }
    }

    /**
     * Read to operate: the operands were combined via {@code op}, producing
     * the derived value {@code id}. Carries the operator so invertibility
     * verdicts can attach in a later slice.
     */
    record Combine(List<Provenance> operands, String op, String id) implements Event {
        public Combine { operands = List.copyOf(operands); }
    }

    /** Set into a return slot: {@code source} flows into output {@code target}. */
    record Emit(Provenance source, AttributePath target) implements Event {}

    /**
     * A call, recorded by reference (never re-expanded — the
     * no-duplicate-edges rule). Its result is untraced in v1; queries treat
     * flow through calls as unproven (fail-closed) until callee-summary
     * substitution lands.
     */
    record Call(String target, List<Provenance> args, String id) implements Event {
        public Call { args = List.copyOf(args); }
    }

    /**
     * A region v1 cannot trace. {@code touched} over-approximates the
     * attribute paths involved (via free-variable analysis). Honest
     * ignorance: never counted as conserved OR dropped; all conservation
     * queries fail closed on it.
     */
    record Opaque(String reason, List<AttributePath> touched) implements Event {
        public Opaque { touched = List.copyOf(touched); }
    }
}
