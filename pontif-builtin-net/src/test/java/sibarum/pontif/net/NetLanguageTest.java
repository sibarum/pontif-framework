package sibarum.pontif.net;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import sibarum.pontif.runtime.PontifCompiler;
import sibarum.pontif.runtime.PontifRunner;
import sibarum.pontif.runtime.module.Extensions;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end at the LANGUAGE level: a real {@code .ptf} program opens {@code pontif.net} conduits,
 * sends events, and receives them back through the compiler and interpreter. This is the proof that
 * {@code pontif.net} is usable from Pontif source, not just from Java — the conduit flows through
 * dispatch as a first-class {@code NetConduit} value and {@code receiveN} yields a stream the
 * program iterates with the ordinary {@code &} spread.
 */
class NetLanguageTest {

    private static PontifRunner.RunResult run(String source) {
        Extensions.install(new NetExtension());
        return new PontifRunner()
                .run(new PontifCompiler().compileAlt(source, "net-lang-test"), PontifRunner.Engine.INTERPRETER);
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void sendAndReceiveOverLocalTransportFromPontifSource() {
        // Open a listener + a client on the same in-VM endpoint, send two events, then pull them
        // back as a bounded stream and spread over it. The result is the received batch.
        PontifRunner.RunResult result = run("""
                requires pontif.net.{localListen, local, send, receiveN}
                struct Tick(n:Int)
                main (
                  let server = localListen("lang-test-ep")
                  let client = local("lang-test-ep")
                  let a = send(client, Tick(10))
                  let b = send(client, Tick(20))
                  let batch = receiveN(server, 2)
                  &batch:[ (e:_) -> e ]
                )
                """);

        assertFalse(result.isError(), "program should run cleanly, got: " + result.text());
        // The two Ticks made the round trip and came back as the batch {Tick{n:10}, Tick{n:20}}.
        String text = result.text();
        assertTrue(text.contains("10") && text.contains("20"),
                "received batch should carry both events; got: " + text);
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void sendOnlyRunsCleanly() {
        PontifRunner.RunResult result = run("""
                requires pontif.net.{localListen, local, send}
                struct Ping(seq:Int)
                main (
                  let server = localListen("lang-test-send-ep")
                  let client = local("lang-test-send-ep")
                  let sent = send(client, Ping(1))
                  0
                )
                """);

        assertFalse(result.isError(), "send-only program should run cleanly, got: " + result.text());
        assertTrue(result.text().contains("0"), "main returns 0; got: " + result.text());
    }
}
