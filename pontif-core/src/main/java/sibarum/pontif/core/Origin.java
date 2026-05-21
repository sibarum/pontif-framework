package sibarum.pontif.core;

public record Origin(String source, Span span) {

    public static final Origin NONE = new Origin(null, null);

    public static Origin at(String source, int line, int column) {
        Position p = new Position(line, column);
        return new Origin(source, new Span(p, p));
    }

    public static Origin span(String source, int startLine, int startColumn, int endLine, int endColumn) {
        return new Origin(source,
                new Span(new Position(startLine, startColumn), new Position(endLine, endColumn)));
    }

    public static Origin span(String source, Position start, Position end) {
        return new Origin(source, new Span(start, end));
    }

    public boolean isPresent() {
        return source != null && span != null;
    }

    public boolean isPoint() {
        return isPresent() && span.start().equals(span.end());
    }

    @Override
    public String toString() {
        if (!isPresent()) {
            return "<unknown>";
        }
        Position start = span.start();
        Position end = span.end();
        if (start.equals(end)) {
            return source + ":" + start.line() + ":" + start.column();
        }
        if (start.line() == end.line()) {
            return source + ":" + start.line() + ":" + start.column() + "-" + end.column();
        }
        return source + ":" + start.line() + ":" + start.column()
                + "-" + end.line() + ":" + end.column();
    }

    public record Position(int line, int column) {

        public Position {
            if (line < 1) {
                throw new IllegalArgumentException("Line must be >= 1, got " + line);
            }
            if (column < 1) {
                throw new IllegalArgumentException("Column must be >= 1, got " + column);
            }
        }

        @Override
        public String toString() {
            return line + ":" + column;
        }
    }

    public record Span(Position start, Position end) {

        public Span {
            if (start == null || end == null) {
                throw new IllegalArgumentException("Span endpoints must be non-null");
            }
        }
    }
}
