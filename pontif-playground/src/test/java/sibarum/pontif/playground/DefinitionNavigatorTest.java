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

    // --- navigate-or-import support ----------------------------------------

    @Test
    void inScope_trueForLocalAndImported_falseOtherwise() {
        String src = """
                requires std.proof.{Split}
                struct Local(x:Int)
                0
                """;
        assertTrue(DefinitionNavigator.inScope(src, "Local"));   // declared here
        assertTrue(DefinitionNavigator.inScope(src, "Split"));   // already imported
        assertFalse(DefinitionNavigator.inScope(src, "Stream")); // neither
    }

    @Test
    void exporters_findsBuiltinModuleThatExportsTheName() {
        var mods = DefinitionNavigator.exporters("0", "Stream", null);
        assertTrue(mods.contains("pontif.core"), () -> mods.toString());
        // A name nothing exports yields no candidates.
        assertTrue(DefinitionNavigator.exporters("0", "Nonexistent", null).isEmpty());
    }

    @Test
    void exporters_ambiguousNameListsAllModules() {
        // Leaf is exported by both std.common and std.proof (a re-export).
        var mods = DefinitionNavigator.exporters("0", "Leaf", null);
        assertTrue(mods.contains("std.common") && mods.contains("std.proof"), () -> mods.toString());
    }

    @Test
    void insertRequires_addsFreshLineAfterModuleHeader() {
        String src = "module app\n\nmain ( 0 )\n";
        DefinitionNavigator.RequiresEdit e = DefinitionNavigator.insertRequires(src, "pontif.core", "Stream");
        assertTrue(e.changed());
        assertTrue(e.text().contains("requires pontif.core.{Stream}"), e::text);
        // Inserted right after the module header.
        assertTrue(e.text().startsWith("module app\nrequires pontif.core.{Stream}"), e::text);
    }

    @Test
    void insertRequires_mergesIntoExistingModuleLine() {
        String src = "requires std.proof.{Split}\n0\n";
        DefinitionNavigator.RequiresEdit e = DefinitionNavigator.insertRequires(src, "std.proof", "Leaf");
        assertTrue(e.changed());
        assertTrue(e.text().contains("requires std.proof.{Split, Leaf}"), e::text);
    }

    @Test
    void insertRequires_noopWhenAlreadyImported() {
        String src = "requires std.proof.{Split, Leaf}\n0\n";
        DefinitionNavigator.RequiresEdit e = DefinitionNavigator.insertRequires(src, "std.proof", "Leaf");
        assertFalse(e.changed());
        assertTrue(e.message().contains("already imported"), e::message);
    }

    @Test
    void insertRequires_preservesRenameEntries() {
        String src = "requires lib.{min -> lo}\n0\n";
        DefinitionNavigator.RequiresEdit e = DefinitionNavigator.insertRequires(src, "lib", "max");
        assertTrue(e.text().contains("requires lib.{min -> lo, max}"), e::text);
    }

    @Test
    void allModules_listsBuiltinsWithTheirExports() {
        var modules = DefinitionNavigator.allModules("0", null);
        // pontif.core is present and exports Stream + Nothing.
        var core = modules.stream().filter(m -> m.module().equals("pontif.core")).findFirst();
        assertTrue(core.isPresent(), () -> modules.toString());
        assertTrue(core.get().builtin());
        assertTrue(core.get().symbols().contains("Stream") && core.get().symbols().contains("Nothing"),
                () -> core.get().symbols().toString());
        // std.proof exports are surfaced too.
        var proof = modules.stream().filter(m -> m.module().equals("std.proof")).findFirst();
        assertTrue(proof.isPresent() && proof.get().symbols().contains("Split"), () -> modules.toString());
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
