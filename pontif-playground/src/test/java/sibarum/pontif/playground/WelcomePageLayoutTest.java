package sibarum.pontif.playground;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.ui.Diagnostic;
import sibarum.dasum.gui.core.ui.Ui;

/**
 * Layout regression guard for the Welcome page: build it (headless — no GL, no screenshots, so
 * every thumbnail is a placeholder) and assert the dasum {@link Ui} layout checker finds no
 * ERROR-severity problems. This is what keeps the "collapsed onto the origin" bug from returning.
 */
class WelcomePageLayoutTest {

    @Test
    void welcomePageHasNoLayoutErrors() {
        Component page = WelcomePage.build(sample -> { /* no-op: not exercising the click path */ });

        List<Diagnostic> diagnostics = Ui.check(page);
        List<Diagnostic> errors = diagnostics.stream()
                .filter(d -> d.severity() == Diagnostic.Severity.ERROR)
                .toList();

        assertTrue(errors.isEmpty(), "Welcome page layout errors:\n"
                + String.join("\n", errors.stream().map(Diagnostic::toString).toList()));
    }
}
