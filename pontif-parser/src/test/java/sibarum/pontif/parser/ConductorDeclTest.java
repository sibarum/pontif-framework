package sibarum.pontif.parser;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ir.CallKinds;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrSort;
import sibarum.pontif.ir.IrStmt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code conductor} declaration — the third authorable type (docs/orchestration.md, §Authoring),
 * alongside {@code struct} and {@code trait}. Cut 1 is parse + represent: {@code conductor Name {
 * field:Sort = init, handler:[Action(e:E):_] }} lowers to an {@link IrStmt.ConductorDecl} carrying
 * its <b>mutable single-owner state fields</b> and its <b>event handlers</b> (the Action/Conduit
 * members from the member-unification slice). Runtime seating is a later slice; here the conductor
 * is authored but inert.
 */
class ConductorDeclTest {

    private static IrStmt.ConductorDecl parseConductor(String src) throws ParseException {
        IrModule m = AltParser.parseModule(src, "t");
        return assertInstanceOf(IrStmt.ConductorDecl.class, m.statements().get(0));
    }

    @Test
    void stateFieldOnly_parsesWithInitializer() throws Exception {
        IrStmt.ConductorDecl cd = parseConductor("conductor Editor { doc:Int = 0 }");
        assertEquals("Editor", cd.name());
        assertEquals(1, cd.state().size());
        IrStmt.ConductorDecl.StateField f = cd.state().get(0);
        assertEquals("doc", f.name());
        assertEquals("Int", ((IrSort.Named) f.sort()).name());
        assertTrue(cd.handlers().isEmpty());
    }

    @Test
    void actionHandler_parsesAndCarriesActionKind() throws Exception {
        IrStmt.ConductorDecl cd = parseConductor("conductor Meter { onTick:[Action(e:Tick):_] }");
        assertTrue(cd.state().isEmpty());
        assertTrue(cd.handlers().containsKey("onTick"));
        assertEquals(CallKinds.Kind.ACTION, CallKinds.builtin(cd.handlers().get("onTick").typeName()));
    }

    @Test
    void conduitHandler_parsesAndCarriesConduitKind() throws Exception {
        IrStmt.ConductorDecl cd = parseConductor("conductor Counter { tick:[Conduit(e:Tick,s:Int):Int] }");
        assertEquals(CallKinds.Kind.CONDUIT, CallKinds.builtin(cd.handlers().get("tick").typeName()));
    }

    @Test
    void mixedStateAndHandlers_parseInOneBody() throws Exception {
        IrStmt.ConductorDecl cd = parseConductor("""
                conductor Editor {
                  doc:Int = 0,
                  cursor:Int = 1,
                  onKey:[Action(e:KeyPress):_],
                  onSaved:[Action(e:Saved):_]
                }
                """);
        assertEquals(2, cd.state().size(), "two mutable state fields");
        assertEquals(2, cd.handlers().size(), "two event handlers");
        assertEquals("doc", cd.state().get(0).name());
        assertEquals("cursor", cd.state().get(1).name());
    }

    @Test
    void duplicateMember_isRejected() {
        ParseException ex = assertThrows(ParseException.class, () ->
                parseConductor("conductor Bad { doc:Int = 0, doc:[Action(e:Tick):_] }"));
        assertTrue(ex.getMessage().toLowerCase().contains("duplicate"));
    }

    @Test
    void handlerWithoutInitOrCallSig_isRejected() {
        // A bare `name:Sort` (no `= init`, not an Action/Conduit) is neither a state field nor a
        // handler — rejected, rather than silently misread.
        ParseException ex = assertThrows(ParseException.class, () ->
                parseConductor("conductor Bad { doc:Int }"));
        assertTrue(ex.getMessage().toLowerCase().contains("state field")
                || ex.getMessage().toLowerCase().contains("handler"));
    }
}
