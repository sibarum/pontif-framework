package sibarum.pontif.playground;

import org.junit.jupiter.api.Test;
import sibarum.dasum.gui.core.render.PngDecoder;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the Welcome-page screenshots: every {@code welcome/shots/*.png} must be named
 * after a real sample id (else its card silently shows "screenshot pending") and must
 * decode as a PNG. Catches a mis-named or corrupt drop-in — the failure mode that would
 * otherwise pass unnoticed until someone opened the Info tab.
 */
class WelcomeShotsTest {

    private static final Path SHOTS_DIR =
            Path.of("src/main/resources/welcome/shots");

    private static Set<String> sampleIds() {
        return Samples.ALL.stream().map(Samples.Sample::id).collect(Collectors.toSet());
    }

    @Test
    void everyShotFileMatchesASampleIdAndDecodes() throws Exception {
        Set<String> ids = sampleIds();
        try (Stream<Path> files = Files.list(SHOTS_DIR)) {
            for (Path p : files.filter(f -> f.getFileName().toString().endsWith(".png")).toList()) {
                String name = p.getFileName().toString();
                String id = name.substring(0, name.length() - ".png".length());
                assertTrue(ids.contains(id),
                        () -> "screenshot '" + name + "' matches no sample id (it would never show)");
                // Decode via the same path WelcomePage uses, off the classpath resource.
                try (InputStream in = WelcomePage.class.getResourceAsStream("/welcome/shots/" + name)) {
                    assertNotNull(in, () -> "shot not on the classpath: " + name);
                    PngDecoder.DecodedImage img = PngDecoder.decode(in);
                    assertTrue(img.width() > 0 && img.height() > 0,
                            () -> "shot decoded to an empty image: " + name);
                }
            }
        }
    }
}
