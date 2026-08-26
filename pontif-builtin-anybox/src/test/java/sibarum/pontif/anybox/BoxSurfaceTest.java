package sibarum.pontif.anybox;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.ir.NativeCalls;
import sibarum.pontif.runtime.PontifCompiler;
import sibarum.pontif.runtime.PontifRunner;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code pontif.gui} surface, headless: what a program's {@code Box} tree looks like by the time
 * it reaches {@code window}. No window opens — {@code window} is stubbed to capture its root — so
 * this runs anywhere, and what it asserts is the <b>surface's</b> contract rather than the
 * renderer's.
 *
 * <p>Most of the module is written in Pontif ({@code pontif.gui.ptf}: a trait, three enums, sixteen
 * style atoms and seventeen methods), so a compile of any program that uses it is already a check
 * that the whole surface still type-checks. The assertions below add the part a compile cannot see:
 * that chaining a method <em>appends</em> an atom in order, and that the atoms carry what the
 * walker will read out of them.
 */
class BoxSurfaceTest {

    /**
     * Run {@code src} with {@code window} stubbed, and return the root Box it was handed.
     *
     * <p>The stub is installed <b>after</b> the compile, and that ordering is load-bearing: the
     * extension registers the real {@code window} from ServiceLoader discovery, which is triggered
     * by the first compile. Registering the stub before that would have it overwritten — and the
     * real {@code window} opens a Vulkan window and blocks forever, so the test hangs rather than
     * fails.
     */
    private static RecordValue rootOf(String src, String name) {
        PontifCompiler.CompileResult compiled = new PontifCompiler().compile(src, name);
        Object[] captured = new Object[1];
        NativeCalls.register("pontif.gui/window", (args, ctx) -> {
            captured[0] = args.size() > 1 ? args.get(1) : null;
            return new IrInterpreter.DriveResult();
        });
        PontifRunner.RunResult r = new PontifRunner().run(compiled, PontifRunner.Engine.INTERPRETER);
        assertFalse(r.isError(), () -> name + " should run; got " + r.text());
        assertNotNull(captured[0], "window should have received a root Box");
        return (RecordValue) captured[0];
    }

    private static String bare(Object o) {
        return o instanceof RecordValue rv ? Atoms.bareType(rv) : "";
    }

    private static List<Object> style(RecordValue box) {
        return Atoms.items(box.members().get("style"));
    }

    private static List<Object> children(RecordValue box) {
        return Atoms.items(box.members().get("children"));
    }

    @Test
    void aChainAppendsOneAtomPerCallInOrder() {
        RecordValue root = rootOf("""
                requires pontif.gui.{column, text, Rem, Role, window}
                main ( window({title = "t"},
                  column({ text("hi") }).gap(Rem(0.5)).padding(Rem(1.0)).background(Role.Panel)) )
                """, "chain.ptf");

        assertEquals("Box", Atoms.bareType(root));
        assertEquals("COLUMN", Atoms.str((RecordValue) root.members().get("kind"), "key"));
        assertEquals(List.of("Gap", "Pad", "Fills"), style(root).stream().map(BoxSurfaceTest::bare).toList(),
                "each chained method appends exactly one atom, in call order");
    }

    /** The bridge reads an enum case's {@code key}, never its ordinal or its {@code E$Case} name. */
    @Test
    void enumCasesCarryTheirVexelrayKey() {
        RecordValue root = rootOf("""
                requires pontif.gui.{box, Role, Placement, window}
                main ( window({}, box({}).background(Role.Accent).justify(Placement.Between)) )
                """, "keys.ptf");

        RecordValue fills = (RecordValue) style(root).get(0);
        RecordValue runs = (RecordValue) style(root).get(1);
        assertEquals("ACCENT", Atoms.str((RecordValue) fills.members().get("role"), "key"));
        assertEquals("SPACE_BETWEEN", Atoms.str((RecordValue) runs.members().get("way"), "key"));
    }

    /** Last wins, like a stylesheet — the fold applies every atom, so the final one is what shows. */
    @Test
    void repeatingAPropertyKeepsBothAtomsSoTheLastOneWins() {
        RecordValue root = rootOf("""
                requires pontif.gui.{box, Rem, window}
                main ( window({}, box({}).padding(Rem(1.0)).padding(Rem(2.0))) )
                """, "last-wins.ptf");

        List<Object> atoms = style(root);
        assertEquals(2, atoms.size(), "both atoms are kept; precedence is the fold's job, not the surface's");
        RecordValue last = (RecordValue) atoms.get(1);
        assertEquals(2.0, Atoms.num(((RecordValue) last.members().get("size")).members().get("v")), 1e-9);
    }

    /** A button and a field are Boxes whose kind implies their two atoms; nothing is hidden in Java. */
    @Test
    void buttonAndFieldAreBoxesCarryingIdentAndContent() {
        RecordValue root = rootOf("""
                requires pontif.gui.{row, button, field, window}
                main ( window({}, row({ button("go", "Go"), field("expr", "x^2") })) )
                """, "widgets.ptf");

        List<Object> kids = children(root);
        assertEquals(2, kids.size());

        RecordValue b = (RecordValue) kids.get(0);
        assertEquals("BUTTON", Atoms.str((RecordValue) b.members().get("kind"), "key"));
        assertEquals(List.of("Ident", "Content"), style(b).stream().map(BoxSurfaceTest::bare).toList());
        assertEquals("go", Atoms.str((RecordValue) style(b).get(0), "name"));
        assertEquals("Go", Atoms.str((RecordValue) style(b).get(1), "text"));

        RecordValue f = (RecordValue) kids.get(1);
        assertEquals("FIELD", Atoms.str((RecordValue) f.members().get("kind"), "key"));
        assertEquals("x^2", Atoms.str((RecordValue) style(f).get(1), "text"));
    }

    /** Nesting is ordinary value nesting: a child is styled before it is placed, and stays styled. */
    @Test
    void childrenKeepTheirOwnStyle() {
        RecordValue root = rootOf("""
                requires pontif.gui.{column, text, Rem, window}
                main ( window({}, column({ text("a").id("first").textSize(Rem(3.0)), text("b") })) )
                """, "nested.ptf");

        RecordValue first = (RecordValue) children(root).get(0);
        assertEquals(List.of("Content", "Ident", "Sized"),
                style(first).stream().map(BoxSurfaceTest::bare).toList());
        assertTrue(style((RecordValue) children(root).get(1)).size() == 1,
                "an unstyled text carries only its Content");
    }
}
