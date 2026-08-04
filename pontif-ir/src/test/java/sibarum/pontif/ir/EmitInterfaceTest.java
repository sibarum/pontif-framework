package sibarum.pontif.ir;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.Origin;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link EmitInterface} — the static emits-interface extraction (slice 2 of the
 * orchestration authoring model, docs/orchestration.md). The consumes side of a handler's
 * event interface is already static (keyed by first param sort); this recovers the emits side
 * from the body so the routing graph becomes checkable. Two properties matter: it finds every
 * emit-site (nested in match arms, let bodies, effect sequences, and the event leg itself), and
 * it is <b>honest</b> — a non-constructor event expression sets {@code hasOpaque} rather than
 * silently claiming the known set is complete.
 */
class EmitInterfaceTest {

    /** {@code emit event  cont} — the write-only node. */
    private static IrExpr emit(IrExpr event, IrExpr cont) {
        return new IrExpr.Emit(event, cont, Origin.NONE);
    }

    /** A constructor-shaped event {@code E(args…)}. */
    private static IrExpr ctor(String type, IrExpr... args) {
        return IrExpr.call(type, List.of(args));
    }

    // --- the statically-apparent cases -----------------------------------

    @Test
    void singleConstructorEmit_isKnown_notOpaque() {
        EmitInterface.EmittedTypes r = EmitInterface.of(emit(ctor("Tick", IrExpr.lit(42)), IrExpr.lit(0)));
        assertEquals(Set.of("Tick"), r.known());
        assertFalse(r.hasOpaque());
    }

    @Test
    void nominalRecordEmit_isKnownByTypeName() {
        IrExpr rec = IrExpr.record("Saved", Map.of("at", IrExpr.lit(1)));
        EmitInterface.EmittedTypes r = EmitInterface.of(emit(rec, IrExpr.lit(0)));
        assertEquals(Set.of("Saved"), r.known());
        assertFalse(r.hasOpaque());
    }

    @Test
    void moduleQualifiedEmit_isBared() {
        EmitInterface.EmittedTypes r = EmitInterface.of(emit(ctor("pontif.events/StdOut", IrExpr.str("hi")), IrExpr.lit(0)));
        assertEquals(Set.of("StdOut"), r.known(), "emitted type name is bared to match the router's keys");
    }

    // --- exhaustive traversal: emits are found wherever they hide --------

    @Test
    void sequentialEmits_inEffectContinuation_allFound() {
        // emit A  (emit B  (emit C  0))
        IrExpr body = emit(ctor("A"), emit(ctor("B"), emit(ctor("C"), IrExpr.lit(0))));
        assertEquals(Set.of("A", "B", "C"), EmitInterface.of(body).known());
    }

    @Test
    void emitNestedInMatchArm_found() {
        IrExpr m = IrExpr.match(IrExpr.var("x"), List.of(
                IrExpr.matchBranch(IrSort.named("_"), emit(ctor("Chosen"), IrExpr.lit(0)))));
        assertEquals(Set.of("Chosen"), EmitInterface.of(m).known());
    }

    @Test
    void emitNestedInLetBody_found() {
        IrExpr let = IrExpr.letIn("y", IrSort.named("Int"), IrExpr.lit(1), emit(ctor("Bound"), IrExpr.lit(0)));
        assertEquals(Set.of("Bound"), EmitInterface.of(let).known());
    }

    @Test
    void emitInsideEventArg_ofAnotherEmit_found() {
        // The event leg can itself contain a nested emit (an argument built by an emitting call).
        IrExpr inner = emit(ctor("Inner"), IrExpr.lit(0));
        IrExpr outer = emit(ctor("Outer", inner), IrExpr.lit(0));
        assertEquals(Set.of("Outer", "Inner"), EmitInterface.of(outer).known());
    }

    // --- honesty: unresolvable emits are opaque, never silently dropped --

    @Test
    void nonConstructorEmit_setsOpaque_withoutFabricatingAName() {
        // `emit e  0` — the event is a bound variable; its concrete type is not statically known.
        EmitInterface.EmittedTypes r = EmitInterface.of(emit(IrExpr.var("e"), IrExpr.lit(0)));
        assertTrue(r.known().isEmpty());
        assertTrue(r.hasOpaque(), "an unpinnable event type must set hasOpaque, not vanish");
    }

    @Test
    void mixedKnownAndOpaque_reportsBoth() {
        IrExpr body = emit(ctor("Known"), emit(IrExpr.var("dynamic"), IrExpr.lit(0)));
        EmitInterface.EmittedTypes r = EmitInterface.of(body);
        assertEquals(Set.of("Known"), r.known());
        assertTrue(r.hasOpaque(), "known is a lower bound when any emit is opaque");
    }

    @Test
    void noEmits_isTheExactEmptySet_notOpaque() {
        EmitInterface.EmittedTypes r = EmitInterface.of(IrExpr.binOp(IrExpr.Op.ADD, IrExpr.lit(1), IrExpr.lit(2)));
        assertEquals(EmitInterface.EmittedTypes.NONE, r);
        assertFalse(r.hasOpaque());
    }
}
