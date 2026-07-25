package sibarum.pontif.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.dasum.gui.mathtext.MathConstants;
import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.core.types.StringValue;
import sibarum.pontif.runtime.PontifCompiler;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;
import sibarum.pontif.runtime.PontifRunner;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The math-style config as a DATA MODULE (docs/plotting.md): a `requires $mathstyle` object literal
 * read into dasum's {@link MathConstants}. Verifies the bridge seam (record → constants, with
 * per-field fallback) and that the config genuinely works through the data-require pipeline.
 */
class MathTextConfigTest {

    @Test
    void mathConstantsFrom_readsOverrides_andFallsBackPerField() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("axisHeight", new BigDecimal("0.30"));         // overridden
        m.put("spaceRelation", new BigDecimal("0.33"));      // overridden
        m.put("fontGroup", new StringValue("mathAlt"));      // overridden
        MathConstants mc = MathText.mathConstantsFrom(new RecordValue("_record", m));

        assertEquals(0.30, mc.axisHeight(), 1e-9, "overridden field read from the record");
        assertEquals(0.33, mc.spaceRelation(), 1e-9);
        assertEquals("mathAlt", mc.fontGroup(), "fontGroup string read");
        // Omitted fields fall back to the STIX Two Math profile default.
        MathConstants d = MathConstants.stixTwoMath();
        assertEquals(d.scriptScale(), mc.scriptScale(), 1e-9, "omitted field falls back to default");
        assertEquals(d.radicalKernAfter(), mc.radicalKernAfter(), 1e-9);
    }

    @Test
    void nullConfig_isTheDefaultProfile() {
        assertEquals(MathConstants.stixTwoMath(), MathText.mathConstantsFrom(null),
                "no config → the baked default profile");
    }

    @Test
    void mathStyleConfig_asDataModule_compilesAndReads(@TempDir Path dir) throws IOException {
        // The whole point: the config is a data file, imported with `requires $mathstyle`, its fields
        // read like a record — statically, no struct/constructor boilerplate.
        Files.writeString(dir.resolve("$mathstyle.ptf"),
                "{ scriptScale = 0.7, axisHeight = 0.25, fontGroup = \"math\" }");
        CompileResult r = new PontifCompiler().compileAlt("""
                module app
                requires $mathstyle
                mathstyle.axisHeight
                """, "app.ptf", dir);
        assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "config data module should compile; got " + ((CompileResult.Failed) r).error().text());
        PontifRunner.RunResult run = new PontifRunner().run(r, PontifRunner.Engine.INTERPRETER);
        assertFalse(run.isError(), () -> "should read the field; got " + run.text());
        assertEquals("0.25", run.text(), "mathstyle.axisHeight read from the data module");
    }
}
