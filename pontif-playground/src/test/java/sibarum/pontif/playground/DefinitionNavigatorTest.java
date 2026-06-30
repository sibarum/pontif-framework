package sibarum.pontif.playground;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resolution tests for {@link DefinitionNavigator} — the logic behind the
 * editor's Ctrl+click "go to definition". The GUI wiring (tab switch, underline)
 * is exercised by hand; this pins the name → source + highlight-span lookup.
 */
class DefinitionNavigatorTest {

    /** The highlighted slice of a resolved target — what the editor selects. */
    private static String highlighted(DefinitionNavigator.Target t) {
        return t.sourceText().substring(t.selStart(), t.selEnd());
    }

    @Test
    void resolvesLocalFunction_inEditorBuffer() {
        String src = """
                function twice(n:Int):Int -> n + n
                twice(21)
                """;
        Optional<DefinitionNavigator.Target> t = DefinitionNavigator.resolve(src, "twice", null);
        assertTrue(t.isPresent());
        assertEquals("(this file)", t.get().moduleLabel());
        // The highlighted span is the declaration's name, not the call site.
        assertEquals("twice", highlighted(t.get()));
        // ...and it's the DECLARATION occurrence (offset 9, after "function "), not the call.
        assertEquals(src.indexOf("twice"), t.get().selStart());
    }

    @Test
    void resolvesLocalStruct() {
        String src = """
                struct Point(x:Int, y:Int)
                Point(1, 2)
                """;
        Optional<DefinitionNavigator.Target> t = DefinitionNavigator.resolve(src, "Point", null);
        assertTrue(t.isPresent());
        assertEquals("Point", highlighted(t.get()));
        assertEquals(src.indexOf("Point"), t.get().selStart());
    }

    @Test
    void resolvesDottedStaticByLastSegment() {
        // Clicking `zero` finds `let Point.zero` (matched as the last dotted segment).
        String src = """
                struct Point(x:Int, y:Int)
                let Point.zero = Point(0, 0)
                Point.zero
                """;
        Optional<DefinitionNavigator.Target> t = DefinitionNavigator.resolve(src, "zero", null);
        assertTrue(t.isPresent());
        assertEquals("zero", highlighted(t.get()));
    }

    @Test
    void resolvesBuiltin_stdProofSplit_reflectedAsPontifSource() {
        // Split is declared in std.proof, which is built from IR (no shipped .ptf):
        // it's reflected back to real Pontif source via IrSourcePrinter.
        String src = """
                requires std.proof.{Split, Leaf}
                0
                """;
        Optional<DefinitionNavigator.Target> t = DefinitionNavigator.resolve(src, "Split", null);
        assertTrue(t.isPresent());
        assertTrue(t.get().moduleLabel().contains("std.proof"), () -> t.get().moduleLabel());
        assertEquals("Split", highlighted(t.get()));
        // The reflection reads as Pontif source — a struct declaration, not an IR dump.
        assertTrue(t.get().sourceText().contains("struct Split("),
                () -> t.get().sourceText());
    }

    @Test
    void resolvesBuiltin_pontifCoreStream_showsRealSource() {
        Optional<DefinitionNavigator.Target> t = DefinitionNavigator.resolve(
                "requires pontif.core.{Stream}\n0", "Stream", null);
        assertTrue(t.isPresent());
        assertTrue(t.get().moduleLabel().contains("pontif.core"), () -> t.get().moduleLabel());
        assertEquals("Stream", highlighted(t.get()));
        // pontif.core is source-authored, so its real source (with the trait) is shown.
        assertTrue(t.get().sourceText().contains("trait Stream"), () -> t.get().sourceText());
    }

    @Test
    void referencesFindsEveryWholeWordOccurrence() {
        String text = "function twice(twiceArg:Int):Int -> twice(twice(1))\ntwice";
        // "twice" appears 4× as a whole word; "twiceArg" must NOT match.
        var refs = DefinitionNavigator.references(text, "twice");
        assertEquals(4, refs.size());
        for (int[] r : refs) {
            assertEquals("twice", text.substring(r[0], r[1]));
        }
    }

    @Test
    void resolvesGuiExtensionStruct_withoutGlobalInstall() {
        // Label lives in the GUI extension, which the editor never installs globally;
        // the navigator parses its source locally for lookup.
        Optional<DefinitionNavigator.Target> t = DefinitionNavigator.resolve(
                "requires pontif.gui.{Label}\n0", "Label", null);
        assertTrue(t.isPresent());
        assertTrue(t.get().moduleLabel().contains("pontif.gui"), () -> t.get().moduleLabel());
        assertEquals("Label", highlighted(t.get()));
    }

    @Test
    void primitivesAreFlaggedNotResolved() {
        assertTrue(DefinitionNavigator.isPrimitive("Int"));
        assertTrue(DefinitionNavigator.isPrimitive("String"));
        assertFalse(DefinitionNavigator.isPrimitive("Point"));
    }

    @Test
    void unknownName_resolvesToNothing() {
        Optional<DefinitionNavigator.Target> t = DefinitionNavigator.resolve(
                "function f(n:Int):Int -> n\nf(1)", "nonexistent", null);
        assertTrue(t.isEmpty());
    }

    @Test
    void unparseableEditorBuffer_stillResolvesBuiltins() {
        // A mid-edit buffer that doesn't parse must not block navigating to a builtin.
        String broken = "function (((  \nrequires std.proof.{Split}";
        Optional<DefinitionNavigator.Target> t = DefinitionNavigator.resolve(broken, "Split", null);
        assertTrue(t.isPresent());
        assertEquals("Split", highlighted(t.get()));
    }
}
