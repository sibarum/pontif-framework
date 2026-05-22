package sibarum.pontif.demo.parser;

import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrParam;
import sibarum.pontif.ir.IrSort;
import sibarum.pontif.ir.IrStmt;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent S-expression parser that produces Pontif IR.
 * <p>
 * The set of keywords and binary-operator spellings is provided by a
 * {@link LanguageDef}; the parser itself contains no magic strings. Pass
 * {@link LanguageDef#defaults()} for the standard surface syntax, or override
 * fields on it to spell forms differently without changing parser code.
 * <p>
 * Grammar shape (the words are configurable; the structure is not):
 * <pre>
 *   module     := '(' MODULE   SYMBOL '(' decl* ')' expr ')'
 *   decl       := '(' DEFN     SYMBOL '(' param* ')' sort expr ')'
 *   param      := '(' SYMBOL sort ')'
 *   sort       := SYMBOL                                  ; named
 *               | '(' REFINED SYMBOL expr ')'             ; refined
 *   expr       := INTEGER | TRUE | FALSE | SELF | SYMBOL
 *               | '(' BINOP expr expr ')'
 *               | '(' LET     SYMBOL sort expr expr ')'
 *               | '(' CALL    SYMBOL expr* ')'
 *               | '(' MATCH   expr branch+ ')'
 *   branch     := '(' sort expr ')'
 * </pre>
 */
public final class Parser {

    private final List<Token> tokens;
    private final LanguageDef language;
    private int pos;

    public Parser(List<Token> tokens) {
        this(tokens, LanguageDef.defaults());
    }

    public Parser(List<Token> tokens, LanguageDef language) {
        if (language == null) {
            throw new IllegalArgumentException("LanguageDef must be non-null");
        }
        this.tokens = List.copyOf(tokens);
        this.language = language;
    }

    public static IrExpr parseExpr(String src, String source) {
        return parseExpr(src, source, LanguageDef.defaults());
    }

    public static IrExpr parseExpr(String src, String source, LanguageDef language) {
        Parser p = new Parser(new Lexer(src, source).tokenize(), language);
        IrExpr expr = p.parseExpr();
        p.expect(Token.Kind.EOF);
        return expr;
    }

    public static IrSort parseSort(String src, String source) {
        return parseSort(src, source, LanguageDef.defaults());
    }

    public static IrSort parseSort(String src, String source, LanguageDef language) {
        Parser p = new Parser(new Lexer(src, source).tokenize(), language);
        IrSort sort = p.parseSort();
        p.expect(Token.Kind.EOF);
        return sort;
    }

    public static IrModule parseModule(String src, String source) {
        return parseModule(src, source, LanguageDef.defaults());
    }

    public static IrModule parseModule(String src, String source, LanguageDef language) {
        Parser p = new Parser(new Lexer(src, source).tokenize(), language);
        IrModule module = p.parseModule();
        p.expect(Token.Kind.EOF);
        return module;
    }

    // --- Token cursor helpers ---

    private Token peek() {
        return tokens.get(pos);
    }

    private Token consume() {
        return tokens.get(pos++);
    }

    private Token expect(Token.Kind kind) {
        Token t = peek();
        if (t.kind() != kind) {
            throw new ParseException(
                    "Expected " + kind + " but got " + t.kind() + " ('" + t.text() + "')",
                    t.origin());
        }
        return consume();
    }

    private Token expectSymbol(String text) {
        Token t = expect(Token.Kind.SYMBOL);
        if (!t.text().equals(text)) {
            throw new ParseException(
                    "Expected '" + text + "' but got '" + t.text() + "'",
                    t.origin());
        }
        return t;
    }

    // --- Top-level parsers ---

    public IrModule parseModule() {
        expect(Token.Kind.LPAREN);
        expectSymbol(language.moduleKeyword());
        Token nameTok = expect(Token.Kind.SYMBOL);
        expect(Token.Kind.LPAREN);
        List<IrStmt> decls = new ArrayList<>();
        while (peek().kind() != Token.Kind.RPAREN) {
            decls.add(parseFunctionDecl());
        }
        expect(Token.Kind.RPAREN);
        IrExpr main = parseExpr();
        expect(Token.Kind.RPAREN);
        return new IrModule(nameTok.text(), decls, main);
    }

    public IrStmt.FunctionDecl parseFunctionDecl() {
        Token open = expect(Token.Kind.LPAREN);
        expectSymbol(language.functionDeclKeyword());
        Token nameTok = expect(Token.Kind.SYMBOL);
        expect(Token.Kind.LPAREN);
        List<IrParam> params = new ArrayList<>();
        while (peek().kind() != Token.Kind.RPAREN) {
            params.add(parseParam());
        }
        expect(Token.Kind.RPAREN);
        IrSort returnSort = parseSort();
        IrExpr body = parseExpr();
        Token close = expect(Token.Kind.RPAREN);
        return new IrStmt.FunctionDecl(nameTok.text(), params, returnSort, body, open.spanTo(close));
    }

    private IrParam parseParam() {
        expect(Token.Kind.LPAREN);
        Token nameTok = expect(Token.Kind.SYMBOL);
        IrSort sort = parseSort();
        expect(Token.Kind.RPAREN);
        return new IrParam(nameTok.text(), sort);
    }

    public IrSort parseSort() {
        Token t = peek();
        if (t.kind() == Token.Kind.SYMBOL) {
            consume();
            return new IrSort.Named(t.text(), t.origin());
        }
        if (t.kind() == Token.Kind.LPAREN) {
            Token open = consume();
            Token head = expect(Token.Kind.SYMBOL);
            if (head.text().equals(language.refinedSortKeyword())) {
                Token sortNameTok = expect(Token.Kind.SYMBOL);
                IrExpr predicate = parseExpr();
                Token close = expect(Token.Kind.RPAREN);
                return new IrSort.Refined(sortNameTok.text(), predicate, open.spanTo(close));
            }
            throw new ParseException(
                    "Unknown sort form: '" + head.text() + "' (expected '"
                            + language.refinedSortKeyword() + "')",
                    head.origin());
        }
        throw new ParseException(
                "Expected a sort (SYMBOL or '(" + language.refinedSortKeyword()
                        + " ...)' form); got " + t.kind() + " '" + t.text() + "'",
                t.origin());
    }

    // --- Expression parser ---

    public IrExpr parseExpr() {
        Token t = peek();
        return switch (t.kind()) {
            case INTEGER -> {
                consume();
                yield new IrExpr.Lit(Long.parseLong(t.text()), t.origin());
            }
            case SYMBOL -> {
                consume();
                String text = t.text();
                if (text.equals(language.trueLiteral())) {
                    yield new IrExpr.Bool(true, t.origin());
                }
                if (text.equals(language.falseLiteral())) {
                    yield new IrExpr.Bool(false, t.origin());
                }
                if (text.equals(language.selfReference())) {
                    yield new IrExpr.SelfRef(t.origin());
                }
                if (language.isReserved(text)) {
                    throw new ParseException(
                            "Reserved word '" + text + "' used as a variable; "
                                    + "wrap it in a form like '(" + text + " ...)' instead",
                            t.origin());
                }
                yield new IrExpr.Var(text, t.origin());
            }
            case LPAREN -> parseCompoundExpr();
            case RPAREN -> throw new ParseException("Unexpected ')'", t.origin());
            case EOF -> throw new ParseException("Unexpected end of input; expected an expression", t.origin());
        };
    }

    private IrExpr parseCompoundExpr() {
        Token open = expect(Token.Kind.LPAREN);
        Token head = expect(Token.Kind.SYMBOL);
        String h = head.text();
        if (language.isBinaryOp(h)) {
            IrExpr left = parseExpr();
            IrExpr right = parseExpr();
            Token close = expect(Token.Kind.RPAREN);
            return new IrExpr.BinOp(language.binaryOpFor(h).orElseThrow(), left, right, open.spanTo(close));
        }
        if (h.equals(language.letKeyword())) return parseLet(open);
        if (h.equals(language.callKeyword())) return parseCall(open);
        if (h.equals(language.matchKeyword())) return parseMatch(open);
        throw new ParseException(
                "Unknown form: '" + h + "'",
                head.origin());
    }

    private IrExpr parseLet(Token open) {
        Token nameTok = expect(Token.Kind.SYMBOL);
        IrSort sort = parseSort();
        IrExpr value = parseExpr();
        IrExpr body = parseExpr();
        Token close = expect(Token.Kind.RPAREN);
        return new IrExpr.LetIn(nameTok.text(), sort, value, body, open.spanTo(close));
    }

    private IrExpr parseCall(Token open) {
        Token nameTok = expect(Token.Kind.SYMBOL);
        List<IrExpr> args = new ArrayList<>();
        while (peek().kind() != Token.Kind.RPAREN) {
            args.add(parseExpr());
        }
        Token close = expect(Token.Kind.RPAREN);
        return new IrExpr.Call(nameTok.text(), args, open.spanTo(close));
    }

    private IrExpr parseMatch(Token open) {
        IrExpr scrutinee = parseExpr();
        List<IrExpr.MatchBranch> branches = new ArrayList<>();
        while (peek().kind() != Token.Kind.RPAREN) {
            expect(Token.Kind.LPAREN);
            IrSort pattern = parseSort();
            IrExpr result = parseExpr();
            expect(Token.Kind.RPAREN);
            branches.add(new IrExpr.MatchBranch(pattern, result));
        }
        Token close = expect(Token.Kind.RPAREN);
        if (branches.isEmpty()) {
            throw new ParseException(
                    "Match form must have at least one branch",
                    open.origin());
        }
        return new IrExpr.Match(scrutinee, branches, open.spanTo(close));
    }
}
