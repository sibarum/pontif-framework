package sibarum.pontif.playground;

import org.junit.jupiter.api.Test;
import sibarum.pontif.tooling.DefinitionNavigator;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editor's navigator reaches {@code pontif.gui} through {@link OptionalGui} without installing it. The
 * navigator itself is tested in pontif-tooling; this pins the one module only this editor adds.
 */
class GuiNavigationTest {

    @Test
    void resolvesGuiExtensionStruct_withoutGlobalInstall() {
        Optional<DefinitionNavigator.Target> t = OptionalGui.navigator().resolve(
                "requires pontif.gui.{Box}\n0", "Box", null);
        assertTrue(t.isPresent());
        assertTrue(t.get().moduleLabel().contains("pontif.gui"), () -> t.get().moduleLabel());
        assertEquals("Box", t.get().sourceText().substring(t.get().selStart(), t.get().selEnd()));
    }
}
