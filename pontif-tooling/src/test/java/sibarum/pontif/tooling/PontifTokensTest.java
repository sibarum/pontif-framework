package sibarum.pontif.tooling;

import org.junit.jupiter.api.Test;
import sibarum.pontif.tooling.PontifTokens.Kind;
import sibarum.pontif.tooling.PontifTokens.Token;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PontifTokensTest {

    /** Each token as {@code KIND:text}, to compare a scan at a glance. */
    private static List<String> shown(String src) {
        return PontifTokens.scan(src).stream()
                .map(t -> t.kind() + ":" + src.substring(t.start(), t.end()))
                .toList();
    }

    @Test
    void scansEveryKind() {
        String src = "function twice(n:Int):Int -> n + 2.5 # doubled\nlet c = 'x'\n\"hi\" @";
        assertEquals(List.of(
                "KEYWORD:function", "NAME:twice", "NAME:n", "NAME:Int", "NAME:Int", "NAME:n", "NUMBER:2.5",
                "COMMENT:# doubled",
                "KEYWORD:let", "NAME:c", "CHAR:'x'",
                "STRING:\"hi\"", "SUBJECT:@"), shown(src));
    }

    @Test
    void keywordsComeFromTheParser() {
        // Every parser keyword scans as one, with no second list here to keep in step.
        for (String k : sibarum.pontif.parser.PontifParser.KEYWORDS) {
            if (!k.chars().allMatch(c -> PontifTokens.isIdentPart((char) c))) continue;
            assertEquals(List.of("KEYWORD:" + k), shown(k), k);
        }
    }

    @Test
    void thisAndThisTypeAreReserved() {
        assertEquals(List.of("KEYWORD:this", "KEYWORD:type"), shown("this.type"));
        assertEquals(List.of("KEYWORD:this", "NAME:size"), shown("this.size"));
    }

    @Test
    void aFieldAccessIsNotADecimal() {
        assertEquals(List.of("NUMBER:1", "NAME:x"), shown("1.x"));
    }

    @Test
    void whatDoesNotScanIsLeftOut_andNothingThrows() {
        // An unterminated string loses only its quote; what follows scans on as usual.
        assertEquals(List.of("NAME:unterminated"), shown("\"unterminated"));
        assertEquals(List.of(), shown("''"));
        // An unknown escape is no char literal: its quotes are dropped, its letter scans as a name.
        assertEquals(List.of("NAME:q", "NAME:a"), shown("'\\q' a"));
        assertEquals(List.of(), PontifTokens.scan(""));
    }

    @Test
    void tokensAreInOrderAndNeverOverlap() {
        String src = "struct P(x:Int) # c\nlet P.zero = P(0) 'a' \"s\"";
        int last = 0;
        for (Token t : PontifTokens.scan(src)) {
            assertTrue(t.start() >= last && t.end() > t.start(), t::toString);
            last = t.end();
        }
        assertTrue(PontifTokens.scan(src).stream().anyMatch(t -> t.kind() == Kind.COMMENT));
    }
}
