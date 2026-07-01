package sibarum.pontif.net;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.module.Extensions;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * The {@code pontif.net} module source must parse and install cleanly, so a program can
 * {@code requires pontif.net.{...}} like any builtin. Install parses the Pontif-side interface and
 * registers the native calls; a source typo would fail here rather than at first use.
 */
class NetExtensionParseTest {

    @Test
    void extensionInstalls() {
        assertDoesNotThrow(() -> Extensions.install(new NetExtension()));
    }
}
