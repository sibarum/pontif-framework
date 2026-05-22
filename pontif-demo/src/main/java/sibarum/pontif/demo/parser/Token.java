package sibarum.pontif.demo.parser;

import sibarum.pontif.core.Origin;

public record Token(Kind kind, String text, String source, int line, int column) {

    public enum Kind {
        LPAREN,
        RPAREN,
        INTEGER,
        SYMBOL,
        EOF
    }

    public Origin origin() {
        return Origin.at(source, line, column);
    }

    public Origin spanTo(Token end) {
        return Origin.span(source, line, column, end.line, end.column + end.text.length());
    }
}
