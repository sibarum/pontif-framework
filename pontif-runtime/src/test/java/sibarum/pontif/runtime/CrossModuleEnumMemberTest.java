package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.pontif.runtime.PontifRunner.Engine;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code Enum.Case} in a value position, for an <b>imported</b> enum.
 *
 * <p>Same parser-blindness as an imported struct literal, and now fixed in the same place. A case
 * name is a type-level member (docs/enums.md): in a value position it reads as the case's one
 * inhabitant, and the parser rewrites it to that construction — but only for an enum declared in
 * the same file, since its type registry holds nothing else. An enum reached through
 * {@code requires} therefore survived parsing as a field read of a variable that does not exist,
 * and failed as {@code Unbound variable 'Role'} — which made {@code enum} a same-file-only feature
 * the moment a program had more than one file.
 *
 * <p>{@link sibarum.pontif.ir.StructLiteralRewriter} now completes the rewrite post-link, where the
 * enum is FQN'd and visible. These guard the four ways the spelling is used, because the fix is a
 * name match and a name match is exactly the kind of thing that silently stops matching.
 */
class CrossModuleEnumMemberTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private String run(Path dir, String entryFile) throws Exception {
        var compiled = compiler.compile(Files.readString(dir.resolve(entryFile)), entryFile, dir);
        var result = runner.run(compiled, Engine.INTERPRETER);
        assertFalse(result.isError(), () -> "expected success; got: " + result.text());
        return result.text();
    }

    /** A payload-carrying enum and a payload-free one, exported from a module of their own. */
    private void writePalette(Path dir) throws Exception {
        Files.writeString(dir.resolve("palette.ptf"), """
                module palette
                exports @.{Role, Facing, keyOf, Swatch, paint, facingName}
                enum Role(key:String) { Ink("INK"); Panel("PANEL"); Accent("ACCENT") }
                enum Facing { Up; Down }
                struct Swatch(role:Role, label:String)
                function keyOf(r:Role):String -> r.key
                function paint(s:Swatch):String -> s.label + ":" + s.role.key
                function facingName(f:Facing):String -> match f { [Facing.Up] -> "up"  [Facing.Down] -> "down" }
                """);
    }

    @Test
    void aCaseIsAValueAcrossTheBoundary(@TempDir Path dir) throws Exception {
        writePalette(dir);
        Files.writeString(dir.resolve("app.ptf"), """
                requires palette.{Role, keyOf}
                main ( keyOf(Role.Accent) )
                """);
        assertEquals("\"ACCENT\"", run(dir, "app.ptf"));
    }

    /** The payload is carried, not lost: the case is its inhabitant, pinned fields and all. */
    @Test
    void anImportedCaseCarriesItsPayload(@TempDir Path dir) throws Exception {
        writePalette(dir);
        Files.writeString(dir.resolve("app.ptf"), """
                requires palette.{Role}
                main ( Role.Panel.key )
                """);
        assertEquals("\"PANEL\"", run(dir, "app.ptf"));
    }

    /** A payload-free enum has only {@code _ordinal} to tell its cases apart — it must still work. */
    @Test
    void aPayloadFreeImportedCaseIsAValue(@TempDir Path dir) throws Exception {
        writePalette(dir);
        Files.writeString(dir.resolve("app.ptf"), """
                requires palette.{Facing, facingName}
                main ( facingName(Facing.Down) )
                """);
        assertEquals("\"down\"", run(dir, "app.ptf"));
    }

    /**
     * <b>Known gap, pinned deliberately.</b> {@code Enum.Case} in a <em>pattern</em> position is
     * still same-file only, and unlike the value position it cannot be finished after linking: the
     * sort parser rewrites {@code [Facing.Up]} to the case's internal name inline, so for an
     * imported enum the dot is a syntax error and nothing survives for a later pass to repair.
     *
     * <p>Fixing it means what {@code DestructureResolver} does for imported struct patterns —
     * parse the dotted name as deferred and resolve it post-link. Until then a match over an
     * imported enum has to go through a function in the enum's own module. This test asserts the
     * <em>current</em> error so that fixing it fails here, loudly, with somewhere to read why.
     */
    @Test
    void aCaseInPatternPositionIsStillParserBlind(@TempDir Path dir) throws Exception {
        writePalette(dir);
        Files.writeString(dir.resolve("app.ptf"), """
                requires palette.{Facing}
                main ( match Facing.Down { [Facing.Up] -> "up"  [Facing.Down] -> "down" } )
                """);
        var result = runner.run(
                compiler.compile(Files.readString(dir.resolve("app.ptf")), "app.ptf", dir),
                Engine.INTERPRETER);
        assertTrue(result.isError(), "if this now succeeds, the gap is closed — delete this test");
        assertTrue(result.text().contains("Expected RBRACKET but got DOT"),
                () -> "expected the parse error that marks this gap; got: " + result.text());
    }

    /** The case as a constructor argument — the shape a styled element vocabulary is built out of. */
    @Test
    void anImportedCaseConstructsAnImportedStruct(@TempDir Path dir) throws Exception {
        writePalette(dir);
        Files.writeString(dir.resolve("app.ptf"), """
                requires palette.{Role, Swatch, paint}
                main ( paint(Swatch(Role.Ink, "body")) )
                """);
        assertEquals("\"body:INK\"", run(dir, "app.ptf"));
    }

    /**
     * A local binding still wins over a same-file enum — the parser skips its own rewrite when the
     * name is in scope, and that has to keep holding now that a second rewriter exists.
     */
    @Test
    void aLocalBindingStillShadowsASameFileEnum(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("app.ptf"), """
                enum Role(key:String) { Ink("INK") }
                struct Holder(Ink:String)
                main ( let Role = Holder("shadowed")
                       Role.Ink )
                """);
        assertEquals("\"shadowed\"", run(dir, "app.ptf"));
    }
}
