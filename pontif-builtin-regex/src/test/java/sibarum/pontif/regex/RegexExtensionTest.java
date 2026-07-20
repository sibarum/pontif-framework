package sibarum.pontif.regex;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.core.types.StringValue;
import sibarum.pontif.ir.NativeCalls;
import sibarum.pontif.runtime.module.Extensions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Validates the regex extension end to end at the Java seam: the Pontif source parses/installs,
 * the native call registers under its FQN, and an anchored full match yields the capture groups
 * (or NoMatch). The language-level behaviour (backtick literal + match arms) is covered separately
 * once the parser/interpreter arm lands.
 */
class RegexExtensionTest {

    private static RecordValue regex(String source) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", new StringValue(source));
        return new RecordValue("Regex", m);
    }

    private static NativeCalls.NativeCall installed() {
        Extensions.install(new RegexExtension());  // parses the Pontif source — throws if invalid
        NativeCalls.NativeCall call = NativeCalls.get(RegexExtension.TRY_MATCH);
        assertNotNull(call, "regex/tryMatch native must register under its FQN");
        return call;
    }

    @Test
    void install_parsesSource_andRegistersNative() {
        installed();  // the assertion is inside — a parse failure throws IllegalStateException
    }

    @Test
    void anchoredMatch_yieldsCaptureGroupsAsTuple() {
        NativeCalls.NativeCall call = installed();
        Object result = call.call(List.of(regex("(\\d+)-(\\d+)"), new StringValue("12-34")), null);
        RecordValue tuple = assertInstanceOf(RecordValue.class, result);
        assertEquals("_tuple", tuple.typeName());
        assertEquals("12", ((StringValue) tuple.members().get("_0")).content());
        assertEquals("34", ((StringValue) tuple.members().get("_1")).content());
    }

    @Test
    void noMatch_yieldsNoMatchMarker() {
        NativeCalls.NativeCall call = installed();
        Object result = call.call(List.of(regex("(\\d+)"), new StringValue("not a number")), null);
        RecordValue rv = assertInstanceOf(RecordValue.class, result);
        assertEquals(RegexExtension.NO_MATCH, rv.typeName());
    }

    @Test
    void anchoredMatch_isFullMatch_notSearch() {
        NativeCalls.NativeCall call = installed();
        // A partial match must NOT count: `\d+` against "a12b" spans only "12", so matches() fails.
        Object result = call.call(List.of(regex("\\d+"), new StringValue("a12b")), null);
        assertEquals(RegexExtension.NO_MATCH,
                assertInstanceOf(RecordValue.class, result).typeName());
    }

    @Test
    void zeroGroupMatch_yieldsEmptyTuple() {
        NativeCalls.NativeCall call = installed();
        Object result = call.call(List.of(regex("\\s*#.*"), new StringValue("   # a comment")), null);
        RecordValue tuple = assertInstanceOf(RecordValue.class, result);
        assertEquals("_tuple", tuple.typeName());
        assertEquals(0, tuple.members().size());
    }
}
