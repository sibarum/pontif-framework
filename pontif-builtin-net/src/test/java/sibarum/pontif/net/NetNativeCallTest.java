package sibarum.pontif.net;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import sibarum.pontif.ast.record.RecordValue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Drives the {@code pontif.net} native calls exactly as the interpreter would — with
 * {@code RecordValue}/scalar args — to isolate the native-call + registry + transport layer from
 * the language surface.
 */
class NetNativeCallTest {

    private static final AtomicInteger EP = new AtomicInteger();

    private static RecordValue tick(long n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("n", n);
        return new RecordValue("app/Tick", m);
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void nativeOpenSendReceive() {
        var host = new sibarum.pontif.core.types.StringValue("native-ep-" + EP.incrementAndGet());
        RecordValue server = (RecordValue) NetConduits.localListen().call(List.of(host), null);
        RecordValue client = (RecordValue) NetConduits.local().call(List.of(host), null);

        NetConduits.send().call(List.of(client, tick(10)), null);
        NetConduits.send().call(List.of(client, tick(20)), null);

        RecordValue batch = (RecordValue) NetConduits.receiveN().call(List.of(server, 2L), null);
        assertEquals(2, batch.members().size(), "should receive both events; got " + batch);
    }
}
