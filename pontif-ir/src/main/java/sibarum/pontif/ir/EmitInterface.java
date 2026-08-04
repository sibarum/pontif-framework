package sibarum.pontif.ir;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Static extraction of a handler body's <b>emits interface</b> — the set of event type
 * names it may fire through {@code emit}. This is the missing half of the routing graph:
 * the <em>consumes</em> side is already keyed at compile time (a conduit/action by its first
 * parameter sort, see {@link CompiledModule}), but the <em>emits</em> side has until now been
 * recovered only at runtime, from the constructed {@code RecordValue.typeName()} in
 * {@link IrInterpreter#fireEvent}. This walker recovers it statically so the graph checks the
 * orchestration model wants — the no-consumer diagnostic, cross-conductor cycle detection —
 * have something to read (docs/orchestration.md, "the routing graph is type-checked").
 *
 * <h2>Honesty: the {@code hasOpaque} flag</h2>
 * An {@code emit}'s event expression is usually a constructor ({@code emit Tick(42)} — an
 * {@link IrExpr.Call} to the type name, or a nominal {@link IrExpr.Record}), whose type is
 * statically apparent. But it can also be computed ({@code emit mkEvent(x)}, {@code emit e}) —
 * a shape whose concrete event type is not knowable without evaluation. Those set
 * {@link EmittedTypes#hasOpaque()}, which means precisely: <b>{@code known} is a lower bound —
 * the handler may emit types beyond it.</b> A downstream no-consumer check must treat
 * {@code hasOpaque} as "cannot prove the gap," and never warn when it is set — the same
 * fail-open honesty the conservation ledger applies to untraceable flow.
 *
 * <p><b>Surface names, not resolved constructors.</b> A {@code Call}'s callee name is taken at
 * face value as the emitted type name; distinguishing a constructor from a function that merely
 * <em>returns</em> an event needs the struct registry and is the consumer's job (it keys by the
 * same bare name the router uses, {@link #bare}). This keeps the extractor a pure,
 * registry-free tree analysis.
 */
public final class EmitInterface {

    /**
     * The emits interface of a body: the statically-apparent emitted type names, and whether
     * some {@code emit} had an event type that could not be pinned statically (so {@code known}
     * is a lower bound, not the exact set).
     */
    public record EmittedTypes(Set<String> known, boolean hasOpaque) {
        public EmittedTypes {
            known = Set.copyOf(known);
        }

        /** No emits at all — the exact empty set (not opaque). */
        public static final EmittedTypes NONE = new EmittedTypes(Set.of(), false);
    }

    private EmitInterface() {}

    /** The emits interface of {@code body}. */
    public static EmittedTypes of(IrExpr body) {
        LinkedHashSet<String> known = new LinkedHashSet<>();
        boolean[] opaque = {false};
        collect(body, known, opaque);
        return new EmittedTypes(known, opaque[0]);
    }

    /**
     * Structural walk over the sealed {@link IrExpr}. The {@code switch} is exhaustive by
     * design — the compiler forces a case per node kind, so no emit-site can be silently
     * missed when a new node is added (the same discipline as {@link IrFreeVars}). Only
     * {@link IrExpr.Emit} contributes; every other node just recurses into its children.
     */
    private static void collect(IrExpr expr, Set<String> known, boolean[] opaque) {
        switch (expr) {
            case IrExpr.Lit l -> {}
            case IrExpr.Dec d -> {}
            case IrExpr.Chr c -> {}
            case IrExpr.Str s -> {}
            case IrExpr.Bool b -> {}
            case IrExpr.SelfRef s -> {}
            case IrExpr.DispatchRef d -> {}
            case IrExpr.Var v -> {}
            case IrExpr.BinOp op -> {
                collect(op.left(), known, opaque);
                collect(op.right(), known, opaque);
            }
            case IrExpr.LetIn l -> {
                collect(l.value(), known, opaque);
                collect(l.body(), known, opaque);
            }
            case IrExpr.Call c -> {
                for (IrExpr arg : c.args()) collect(arg, known, opaque);
            }
            case IrExpr.Lambda lam -> collect(lam.body(), known, opaque);
            case IrExpr.Apply app -> {
                collect(app.fn(), known, opaque);
                for (IrExpr a : app.args()) collect(a, known, opaque);
            }
            case IrExpr.Match m -> {
                collect(m.scrutinee(), known, opaque);
                for (IrExpr.MatchBranch b : m.branches()) collect(b.result(), known, opaque);
            }
            case IrExpr.Record r -> {
                for (IrExpr v : r.members().values()) collect(v, known, opaque);
            }
            case IrExpr.Cast cast -> collect(cast.value(), known, opaque);
            case IrExpr.Emit em -> {
                noteEmittedType(em.event(), known, opaque);
                // Recurse into BOTH legs: the event expression may itself contain nested
                // emits (an argument built by an emitting call), and the continuation body
                // certainly can (the next statement in the effect sequence).
                collect(em.event(), known, opaque);
                collect(em.body(), known, opaque);
            }
            case IrExpr.FieldAccess fa -> collect(fa.base(), known, opaque);
            case IrExpr.MethodCall mc -> {
                collect(mc.receiver(), known, opaque);
                for (IrExpr a : mc.args()) collect(a, known, opaque);
            }
            case IrExpr.Iterate it -> {
                collect(it.source(), known, opaque);
                for (IrExpr cs : it.coSources()) collect(cs, known, opaque);
                for (IrExpr.OutputSpec os : it.outputs()) {
                    if (os.init() != null) collect(os.init(), known, opaque);
                }
                for (IrExpr.Arm arm : it.arms()) {
                    for (IrExpr.Write w : arm.writes()) {
                        if (w.key() != null) collect(w.key(), known, opaque);
                        collect(w.value(), known, opaque);
                    }
                }
            }
        }
    }

    /**
     * Records the emitted type of a single {@code emit}'s event expression: the callee name of
     * a constructor {@link IrExpr.Call}, or a nominal {@link IrExpr.Record}'s type name. Any
     * other shape (a variable, a field access, an anonymous record) is not statically pinnable —
     * it sets {@code hasOpaque}.
     */
    private static void noteEmittedType(IrExpr event, Set<String> known, boolean[] opaque) {
        switch (event) {
            case IrExpr.Call c -> known.add(bare(c.functionName()));
            case IrExpr.Record r when r.typeName() != null -> known.add(bare(r.typeName()));
            default -> opaque[0] = true;
        }
    }

    /**
     * The bare (module-path-stripped) name, matching how {@link CompiledModule} keys its
     * consumer buckets — so an extracted emit name is directly comparable to a consumer key.
     */
    private static String bare(String name) {
        int slash = name.lastIndexOf('/');
        return slash < 0 ? name : name.substring(slash + 1);
    }
}
