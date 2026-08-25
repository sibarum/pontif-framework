package sibarum.pontif.parser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The declaration keywords are one list, and the lexer knows every one of them.
 *
 * <p>They used to be four lists with nothing checking them against each other
 * (docs/parser-linker-refactor.md item 2): {@link PontifParser#KEYWORDS}, the decl-head set
 * inside {@code isMainExpressionStart}, the dispatch switch, and the prose in that switch's
 * error message. Adding a construct meant remembering all four. The drift was not hypothetical
 * — adding {@code conductor} to two of them produced "unexpected keyword 'conductor' in
 * expression position", and the error message had been missing {@code spawn} ever since that
 * construct landed.
 *
 * <p>Three of the four now READ the registry, so they cannot drift. The fourth — {@code
 * KEYWORDS}, which is the lexer's superset and legitimately larger — is what this test pins.
 */
class DeclarationKeywordTest {

    @Test
    void everyDeclarationKeywordIsALexicalKeyword() {
        for (String declaration : PontifParser.DECLARATION_KEYWORDS) {
            assertTrue(PontifParser.KEYWORDS.contains(declaration),
                    () -> "'" + declaration + "' opens a declaration but is not in KEYWORDS, so the"
                            + " lexer does not treat it as a keyword (and the highlighter will not"
                            + " colour it)");
        }
    }

    @Test
    void theHeaderKeywordIsNotADeclaration() {
        // `module` opens a file, not a declaration: it is consumed before the declaration loop,
        // and the registry deliberately omits it. isDeclarationHead adds it back for the one
        // question — "does a top-level construct start here?" — that needs both.
        assertFalse(PontifParser.DECLARATION_KEYWORDS.contains("module"),
                "module is the file header, not a declaration");
        assertTrue(PontifParser.KEYWORDS.contains("module"));
    }

    @Test
    void theRegistryCoversTheConstructsTheLanguageHas() {
        // A floor, not a mirror: naming them here would just be a fifth list. This asserts the
        // ones whose absence has actually bitten — the effect/orchestration surface, which is
        // what kept breaking as it was added.
        for (String recent : new String[]{"action", "conduit", "conductor", "spawn", "enum"}) {
            assertTrue(PontifParser.DECLARATION_KEYWORDS.contains(recent),
                    () -> "'" + recent + "' is a top-level construct and must dispatch");
        }
    }

    @Test
    void theRejectionMessageNamesEveryDeclaration() {
        // The message is generated from the registry, so it cannot go stale the way the
        // hand-written one did — it had been missing `spawn` since that construct landed.
        ParseException e = org.junit.jupiter.api.Assertions.assertThrows(ParseException.class,
                () -> PontifParser.parseModule("function f():Int -> 1\nmodule m\n1", "t.ptf"));
        String msg = e.getMessage();
        assertTrue(msg.contains("not a top-level declaration"),
                () -> "expected the top-level-declaration error; got: " + msg);
        for (String declaration : PontifParser.DECLARATION_KEYWORDS) {
            assertTrue(msg.contains(declaration),
                    () -> "the error lists what IS allowed, so it must name '" + declaration
                            + "'; got: " + msg);
        }
    }

    @Test
    void theListedOrderIsStableAcrossRuns() {
        // The registry is a LinkedHashMap built by ordered puts, not a Map.ofEntries — an
        // immutable Map's iteration order is salted per JVM run, and this order is printed to
        // users. Two reads of the same key set must agree on order.
        assertTrue(String.join(",", PontifParser.DECLARATION_KEYWORDS)
                        .equals(String.join(",", PontifParser.DECLARATION_KEYWORDS)),
                "the declaration order must be stable");
        assertTrue(String.join(",", PontifParser.DECLARATION_KEYWORDS).startsWith("requires,exports,function"),
                () -> "expected declaration order, got: " + String.join(",", PontifParser.DECLARATION_KEYWORDS));
    }
}
