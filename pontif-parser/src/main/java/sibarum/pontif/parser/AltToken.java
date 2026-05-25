package sibarum.pontif.parser;

import sibarum.pontif.core.Origin;

/**
 * Token type for {@link AltLexer} / {@link AltParser} — the alt syntax
 * frontend described in {@code docs/alternative-syntax.ptf}. Distinct from
 * the S-expression {@link Token} because the alt syntax tokenizes operator
 * characters as their own tokens (so {@code Int>0} splits into three).
 */
public record AltToken(Kind kind, String text, String source, int line, int column) {

    public enum Kind {
        // Atoms
        INTEGER,    // 42, -3
        IDENT,      // foo, Point, factorial — keywords are recognized at parse time

        // Brackets
        LPAREN, RPAREN,         // ( )
        LBRACKET, RBRACKET,     // [ ]
        LBRACE, RBRACE,         // { }

        // Punctuation
        COMMA,                  // ,
        COLON,                  // :
        DOT,                    // .
        AT,                     // @
        EQUALS,                 // = (value-level assignment; used by parseLet and by-name struct literals)
        ARROW,                  // ->

        // Operators (text carries the specific operator: +, -, *, /, <, <=, >, >=, ==, !=, &, |, !)
        // `|` here means logical/union OR, `&` means logical/intersection AND, `!` means NOT.
        OP,

        EOF
    }

    public Origin origin() {
        return Origin.at(source, line, column);
    }

    public Origin spanTo(AltToken end) {
        return Origin.span(source, line, column, end.line, end.column + end.text.length());
    }
}
