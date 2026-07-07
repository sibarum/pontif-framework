package sibarum.pontif.types;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import sibarum.pontif.ir.IrSort;

/**
 * A dispatch question as a <em>constraint set</em> — the single query that unifies every kind of static
 * and dynamic dispatch (docs, ratified 2026-07-07). It carries whatever is known about a call, each
 * constraint ranging from fully symbolic ({@code [Int]}) to a value-pin ({@code [Int:@==5]}), and asks:
 * over the declared targets, is this satisfiable, and by how many? (See {@link DispatchResult}.)
 *
 * <p>The same query serves the whole determinacy gradient: coarse name-routing (broad arg sorts →
 * a target family), refinement selection and the call gate (narrowed sorts), and runtime dispatch (every
 * argument a constant pin — the fully-determined instance, evaluated by a fast path but the same query).
 *
 * <p>The field set is deliberately open — more constraints get added as callers need them, which is
 * cheap now that the query is isolated (James 2026-07-07). Today {@link #selector} and {@link #args}
 * drive free-function / operator dispatch; {@link #receiver} (method dispatch, with a future {@code this}
 * binding for dependent sorts) and {@link #expectedReturn} (return-directed dispatch) are carried for the
 * slices that consume them.
 */
public record DispatchQuery(
        String selector,
        Optional<IrSort> receiver,
        List<ArgConstraint> args,
        Optional<IrSort> expectedReturn) {

    public DispatchQuery {
        args = List.copyOf(args);
    }

    /**
     * One argument's constraint: an optional {@code name} (for by-name arguments) and an optional
     * {@code sort} narrowing ({@code null} = unconstrained/unknown, treated as residual).
     */
    public record ArgConstraint(String name, IrSort sort) {
        public static ArgConstraint ofSort(IrSort sort) {
            return new ArgConstraint(null, sort);
        }
    }

    /** A free-function / operator call: a selector and positional argument narrowings. */
    public static DispatchQuery forCall(String selector, List<IrSort> argSorts) {
        List<ArgConstraint> as = new ArrayList<>(argSorts.size());
        for (IrSort s : argSorts) as.add(ArgConstraint.ofSort(s));
        return new DispatchQuery(selector, Optional.empty(), as, Optional.empty());
    }

    /**
     * Coarse <em>operator</em> name-routing: which operator family does {@code symbol} route to for
     * operands of these (broad) sorts? The selector is the bare operator symbol ({@code "+"}), not a
     * resolved Call name — that is what tells the resolver to answer by operand base-name family match
     * rather than refinement selection. The determinacy is deliberately broad (base names, refinement
     * blind): it names the target family, and picking the most-specific member is left to a
     * more-determined query (ultimately runtime dispatch).
     */
    public static DispatchQuery forOperator(String symbol, IrSort left, IrSort right) {
        return new DispatchQuery(symbol, Optional.empty(),
                List.of(ArgConstraint.ofSort(left), ArgConstraint.ofSort(right)), Optional.empty());
    }

    /**
     * Coarse <em>method</em> name-routing: does {@code methodName} route to a method on the receiver's
     * (base) type? The {@link #receiver} constraint is what marks this a method query. Method routing
     * today keys only on the receiver type and selector (it does not constrain on argument sorts), so
     * this carries no {@link #args} — a future arg-directed method-dispatch slice adds them.
     */
    public static DispatchQuery forMethod(String methodName, IrSort receiver) {
        return new DispatchQuery(methodName, Optional.of(receiver), List.of(), Optional.empty());
    }
}
