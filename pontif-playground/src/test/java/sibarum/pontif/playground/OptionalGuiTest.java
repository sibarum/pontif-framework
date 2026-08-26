package sibarum.pontif.playground;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The names {@link OptionalGui} resolves by string actually resolve.
 *
 * <p>This is the test that reflection <em>owes</em>. Making the windowed extension optional means the compiler no
 * longer checks those two references, so renaming {@code GuiLauncher} or {@code GuiExtension} — or moving either
 * out of {@code sibarum.pontif.gui} — would not break the build. It would silently turn the editor's Run-as-GUI
 * button into "no windowed extension on the classpath", which reads exactly like the extension having been
 * removed on purpose. A silent downgrade to a correct-looking message is the worst failure available here, and
 * these assertions are what stand in for the type check that was traded away.
 *
 * <p>Only the <b>present</b> path is asserted, because the extension is a {@code runtime}-scoped dependency and so
 * is on this test's classpath. The absent path — the editor compiling and running with the extension deleted — is
 * not something a test inside one JVM can honestly stage; it is rehearsed at the build level instead, and what
 * that rehearsal costs is written down in docs/plotting.md, §The cut, when it comes.
 */
class OptionalGuiTest {

    @Test
    void theLauncherNameResolves() {
        assertTrue(OptionalGui.present(),
                () -> OptionalGui.LAUNCHER + " is not on the classpath. If pontif-builtin-gui was deliberately "
                        + "removed, delete this test with it; if it was renamed or moved, fix the constant — the "
                        + "editor's GUI-run path is resolved by that exact name and nothing else checks it.");
    }

    @Test
    void theExtensionAnswersWithAModuleTheNavigatorCanRead() {
        // Not just "the class is there": DefinitionNavigator needs the SOURCE, so pontifSource() has to answer
        // with the module text. A present class whose method was renamed loses navigation just as quietly.
        String src = OptionalGui.moduleSource();
        assertNotNull(src, "the windowed extension must answer with its Pontif module source");
        assertTrue(src.contains("exports"), () -> "not a module source: " + head(src));
        assertTrue(src.contains("Label"), () -> "pontif.gui should declare Label: " + head(src));
    }

    private static String head(String s) {
        return s.length() <= 120 ? s : s.substring(0, 120) + "…";
    }
}
