package sibarum.pontif.ir;

import java.util.HashSet;
import java.util.Set;

/**
 * The event-emission guard (docs/events.md): an {@code emit EVENT} may only emit an
 * <b>Event</b>. On master this was the {@code e:Event} parameter-sort check of the
 * (now-retired) {@code emit} function; with {@code emit} a statement keyword the
 * contract has no natural home in the per-node passes — {@link SortChecker} has no
 * trait access, and {@link ConstructionGate}'s claim gate deliberately skips marker
 * traits — so it lives here as one small dedicated walk.
 *
 * <p><b>Honesty rule</b>, mirroring the old parameter check: reject only a
 * <em>provable</em> non-Event — an event whose nominal type is a declared struct that
 * does not assign {@code trait Event}. Unknown / type-variable / non-nominal events
 * stay lenient (no false rejection); a misrouted emit that slips through still fails
 * closed at runtime ({@link IrInterpreter} {@code evalEmit}) with a clear "no conduit"
 * message.
 *
 * <p>Runs post-link (names FQN-qualified) so the builtin {@code pontif.events/StdOut}
 * / {@code StdErr} (which assign Event in pontif.events) and user
 * {@code assign trait X:Event{}} are both visible as {@link IrStmt.TraitImpl}s.
 */
final class EventEmitCheck {

    private EventEmitCheck() {}

    static void check(IrModule module) throws CompileException {
        Set<String> declaredStructs =
                sibarum.pontif.types.TypeCatalog.fromModule(module).structShapes().keySet();
        Set<String> eventStructs = new HashSet<>();
        for (IrStmt s : module.statements()) {
            if (s instanceof IrStmt.TraitImpl ti && isEventTrait(ti.traitName())) {
                eventStructs.add(ti.typeName());
            }
        }
        for (IrStmt s : module.statements()) {
            switch (s) {
                case IrStmt.FunctionDecl fd -> walk(fd.body(), declaredStructs, eventStructs);
                case IrStmt.TraitImpl ti -> {
                    for (IrStmt.FunctionDecl m : ti.methods()) walk(m.body(), declaredStructs, eventStructs);
                    for (IrStmt.FunctionDecl a : ti.attributeProducers()) walk(a.body(), declaredStructs, eventStructs);
                }
                default -> { }
            }
        }
        walk(module.main(), declaredStructs, eventStructs);
    }

    private static boolean isEventTrait(String traitName) {
        return traitName != null && (traitName.equals("Event") || traitName.endsWith("/Event"));
    }

    /** The nominal type an event expression constructs, or null if not a nominal construction. */
    private static String eventTypeName(IrExpr event) {
        return switch (event) {
            case IrExpr.Record r -> r.typeName();
            case IrExpr.Call c -> c.functionName();   // pre-StructLiteralRewriter (single-file) form
            default -> null;
        };
    }

    /** Recursively visits every sub-expression, checking each {@link IrExpr.Emit}. */
    private static void walk(IrExpr e, Set<String> declaredStructs, Set<String> eventStructs)
            throws CompileException {
        switch (e) {
            case IrExpr.Emit em -> {
                String type = eventTypeName(em.event());
                if (type != null && declaredStructs.contains(type) && !eventStructs.contains(type)) {
                    throw new CompileException(
                            "emit expects an Event, but '" + type + "' does not assign trait Event "
                                    + "— declare `assign trait " + type + ":Event{}` (docs/events.md)",
                            em.origin());
                }
                walk(em.event(), declaredStructs, eventStructs);
                walk(em.body(), declaredStructs, eventStructs);
            }
            case IrExpr.LetIn l -> {
                walk(l.value(), declaredStructs, eventStructs);
                walk(l.body(), declaredStructs, eventStructs);
            }
            case IrExpr.BinOp op -> {
                walk(op.left(), declaredStructs, eventStructs);
                walk(op.right(), declaredStructs, eventStructs);
            }
            case IrExpr.Call c -> {
                for (IrExpr a : c.args()) walk(a, declaredStructs, eventStructs);
            }
            case IrExpr.Apply a -> {
                walk(a.fn(), declaredStructs, eventStructs);
                for (IrExpr arg : a.args()) walk(arg, declaredStructs, eventStructs);
            }
            case IrExpr.Match m -> {
                walk(m.scrutinee(), declaredStructs, eventStructs);
                for (IrExpr.MatchBranch b : m.branches()) walk(b.result(), declaredStructs, eventStructs);
            }
            case IrExpr.Record r -> {
                for (IrExpr v : r.members().values()) walk(v, declaredStructs, eventStructs);
            }
            case IrExpr.FieldAccess fa -> walk(fa.base(), declaredStructs, eventStructs);
            case IrExpr.MethodCall mc -> {
                walk(mc.receiver(), declaredStructs, eventStructs);
                for (IrExpr arg : mc.args()) walk(arg, declaredStructs, eventStructs);
            }
            case IrExpr.Cast cast -> walk(cast.value(), declaredStructs, eventStructs);
            case IrExpr.Lambda lam -> walk(lam.body(), declaredStructs, eventStructs);
            case IrExpr.Iterate it -> {
                walk(it.source(), declaredStructs, eventStructs);
                for (IrExpr cs : it.coSources()) walk(cs, declaredStructs, eventStructs);
                for (IrExpr.OutputSpec os : it.outputs()) {
                    if (os.init() != null) walk(os.init(), declaredStructs, eventStructs);
                }
                for (IrExpr.Arm arm : it.arms()) {
                    for (IrExpr.Write w : arm.writes()) {
                        if (w.key() != null) walk(w.key(), declaredStructs, eventStructs);
                        walk(w.value(), declaredStructs, eventStructs);
                    }
                }
            }
            // Leaves carry no sub-expression: Lit, Dec, Chr, Str, Bool, Var, SelfRef, DispatchRef.
            default -> { }
        }
    }
}
