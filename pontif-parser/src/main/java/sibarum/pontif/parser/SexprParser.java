package sibarum.pontif.parser;

import sibarum.pontif.core.Origin;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrParam;
import sibarum.pontif.ir.IrSort;
import sibarum.pontif.ir.IrStmt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 *   decl       := '(' DEFN     SYMBOL '(' param* ')' sort expr ')'   ; function
 *               | '(' DEFTYPE  SYMBOL sort ')'                        ; type alias
 *   param      := '(' SYMBOL sort ')'
 *   sort       := SYMBOL                                  ; named
 *               | '(' REFINED  SYMBOL expr ')'            ; refined
 *               | '(' FUNCTION '(' sort* ')' sort ')'     ; function
 *               | '(' STRUCT   SYMBOL ('(' SYMBOL sort ')')* ')'  ; structural
 *   expr       := INTEGER | TRUE | FALSE | SELF | SYMBOL
 *               | '(' BINOP  expr expr ')'
 *               | '(' LET    SYMBOL sort expr expr ')'
 *               | '(' CALL   (SYMBOL | expr) expr* ')'
 *               | '(' MATCH  expr branch+ ')'
 *               | '(' LAMBDA '(' param* ')' sort expr ')'
 *               | '(' RECORD ('(' SYMBOL expr ')')* ')'   ; record construction
 *               | '(' FIELD  expr SYMBOL ')'              ; field access
 *   branch     := '(' sort expr ')'
 * </pre>
 * <p>
 * The unified {@code call} form handles both named-function invocation and
 * value-level closure application:
 * <ul>
 *   <li>Bare-symbol head: parses to {@link sibarum.pontif.ir.IrExpr.Call}.
 *       At runtime, the name is looked up in the local scope first
 *       (let-bindings / parameters); if absent, dispatch falls through to the
 *       module-level dispatch table.</li>
 *   <li>Compound head (an inline {@code lambda}, a nested {@code call}, etc.):
 *       parses to {@link sibarum.pontif.ir.IrExpr.Apply}. The head expression
 *       is evaluated, must produce a closure, and that closure is invoked.</li>
 * </ul>
 */
public final class SexprParser {

    private final List<SexprToken> tokens;
    private final LanguageDef language;
    private int pos;
    /** Counter for synthesizing unique names during desugaring (match destructuring, etc.). */
    private int syntheticNameCounter = 0;

    public SexprParser(List<SexprToken> tokens) {
        this(tokens, LanguageDef.defaults());
    }

    public SexprParser(List<SexprToken> tokens, LanguageDef language) {
        if (language == null) {
            throw new IllegalArgumentException("LanguageDef must be non-null");
        }
        this.tokens = List.copyOf(tokens);
        this.language = language;
    }

    public static IrExpr parseExpr(String src, String source) throws ParseException {
        return parseExpr(src, source, LanguageDef.defaults());
    }

    public static IrExpr parseExpr(String src, String source, LanguageDef language) throws ParseException {
        SexprParser p = new SexprParser(new SexprLexer(src, source).tokenize(), language);
        IrExpr expr = p.parseExpr();
        p.expect(SexprToken.Kind.EOF);
        return expr;
    }

    public static IrSort parseSort(String src, String source) throws ParseException {
        return parseSort(src, source, LanguageDef.defaults());
    }

    public static IrSort parseSort(String src, String source, LanguageDef language) throws ParseException {
        SexprParser p = new SexprParser(new SexprLexer(src, source).tokenize(), language);
        IrSort sort = p.parseSort();
        p.expect(SexprToken.Kind.EOF);
        return sort;
    }

    public static IrModule parseModule(String src, String source) throws ParseException {
        return parseModule(src, source, LanguageDef.defaults());
    }

    public static IrModule parseModule(String src, String source, LanguageDef language) throws ParseException {
        SexprParser p = new SexprParser(new SexprLexer(src, source).tokenize(), language);
        IrModule module = p.parseModule();
        p.expect(SexprToken.Kind.EOF);
        return module;
    }

    // --- SexprToken cursor helpers ---

    private SexprToken peek() {
        return tokens.get(pos);
    }

    private SexprToken consume() {
        return tokens.get(pos++);
    }

    private SexprToken expect(SexprToken.Kind kind) throws ParseException {
        SexprToken t = peek();
        if (t.kind() != kind) {
            throw new ParseException(
                    "Expected " + kind + " but got " + t.kind() + " ('" + t.text() + "')",
                    t.origin());
        }
        return consume();
    }

    private SexprToken expectSymbol(String text) throws ParseException {
        SexprToken t = expect(SexprToken.Kind.SYMBOL);
        if (!t.text().equals(text)) {
            throw new ParseException(
                    "Expected '" + text + "' but got '" + t.text() + "'",
                    t.origin());
        }
        return t;
    }

    // --- Top-level parsers ---

    public IrModule parseModule() throws ParseException {
        expect(SexprToken.Kind.LPAREN);
        expectSymbol(language.moduleKeyword());
        SexprToken nameTok = expect(SexprToken.Kind.SYMBOL);
        expect(SexprToken.Kind.LPAREN);
        List<IrStmt> decls = new ArrayList<>();
        while (peek().kind() != SexprToken.Kind.RPAREN) {
            decls.add(parseTopLevelDecl());
        }
        expect(SexprToken.Kind.RPAREN);
        IrExpr main = parseExpr();
        expect(SexprToken.Kind.RPAREN);
        return new IrModule(nameTok.text(), decls, main);
    }

    /**
     * Peek the head of the next (...) form to choose between a function
     * declaration and a type alias. Both are valid top-level declarations.
     */
    private IrStmt parseTopLevelDecl() throws ParseException {
        // Need two tokens of lookahead: LPAREN then the head SYMBOL.
        if (peek().kind() != SexprToken.Kind.LPAREN) {
            throw new ParseException(
                    "Expected '(' starting a top-level declaration; got " + peek().kind(),
                    peek().origin());
        }
        SexprToken headSymbol = tokens.get(pos + 1);
        if (headSymbol.kind() == SexprToken.Kind.SYMBOL) {
            String head = headSymbol.text();
            if (head.equals(language.typeAliasKeyword())) {
                return parseTypeAlias();
            }
            if (head.equals(language.interfaceKeyword())) {
                return parseInterface();
            }
            if (head.equals(language.implKeyword())) {
                return parseImpl();
            }
        }
        return parseFunctionDecl();
    }

    /**
     * Trait declaration: {@code (interface Name (methodName (paramSort*) returnSort) ...)}.
     * Lowers to {@code IrStmt.TypeAlias(Name, IrSort.Trait(Name, methods))}.
     * Each method's signature is param-sorts and return-sort *without* the
     * implicit {@code self} — SortChecker prepends it per implementor.
     */
    public IrStmt.TypeAlias parseInterface() throws ParseException {
        SexprToken open = expect(SexprToken.Kind.LPAREN);
        expectSymbol(language.interfaceKeyword());
        SexprToken nameTok = expect(SexprToken.Kind.SYMBOL);
        java.util.LinkedHashMap<String, IrSort.CallSig> methods = new java.util.LinkedHashMap<>();
        while (peek().kind() != SexprToken.Kind.RPAREN) {
            expect(SexprToken.Kind.LPAREN);
            SexprToken methodName = expect(SexprToken.Kind.SYMBOL);
            expect(SexprToken.Kind.LPAREN);
            List<IrSort> paramSorts = new ArrayList<>();
            while (peek().kind() != SexprToken.Kind.RPAREN) {
                paramSorts.add(parseSort());
            }
            expect(SexprToken.Kind.RPAREN);
            IrSort returnSort = parseSort();
            expect(SexprToken.Kind.RPAREN);
            methods.put(methodName.text(),
                    new IrSort.CallSig(IrSort.CallSig.METHOD, paramSorts, returnSort, methodName.origin()));
        }
        SexprToken close = expect(SexprToken.Kind.RPAREN);
        Origin origin = open.spanTo(close);
        return new IrStmt.TypeAlias(
                nameTok.text(),
                new IrSort.Trait(nameTok.text(), methods, origin),
                origin);
    }

    /**
     * Trait impl: {@code (impl TypeName TraitName (function methodName (params*) returnSort body) ...)}.
     * Each method is parsed as a regular function decl; the parser
     * automatically:
     * <ul>
     *   <li>prepends a {@code (self TypeName)} param, and
     *   <li>rewrites the method's name to {@code TypeName.methodName}.
     * </ul>
     * Lowers to {@link IrStmt.TraitImpl}.
     */
    public IrStmt.TraitImpl parseImpl() throws ParseException {
        SexprToken open = expect(SexprToken.Kind.LPAREN);
        expectSymbol(language.implKeyword());
        SexprToken typeNameTok = expect(SexprToken.Kind.SYMBOL);
        SexprToken traitNameTok = expect(SexprToken.Kind.SYMBOL);
        String typeName = typeNameTok.text();
        IrSort selfSort = new IrSort.Named(typeName, typeNameTok.origin());

        List<IrStmt.FunctionDecl> methods = new ArrayList<>();
        while (peek().kind() != SexprToken.Kind.RPAREN) {
            IrStmt.FunctionDecl raw = parseFunctionDecl();
            // Prepend (self : TypeName) and qualify the name.
            List<IrParam> params = new ArrayList<>(raw.params().size() + 1);
            params.add(new IrParam("self", selfSort));
            params.addAll(raw.params());
            methods.add(new IrStmt.FunctionDecl(
                    typeName + "." + raw.name(),
                    params,
                    raw.returnSort(),
                    raw.body(),
                    raw.origin()));
        }
        SexprToken close = expect(SexprToken.Kind.RPAREN);
        return new IrStmt.TraitImpl(
                typeName, traitNameTok.text(), methods, open.spanTo(close));
    }

    public IrStmt.TypeAlias parseTypeAlias() throws ParseException {
        SexprToken open = expect(SexprToken.Kind.LPAREN);
        expectSymbol(language.typeAliasKeyword());
        SexprToken nameTok = expect(SexprToken.Kind.SYMBOL);
        IrSort sort = parseSort();
        SexprToken close = expect(SexprToken.Kind.RPAREN);
        return new IrStmt.TypeAlias(nameTok.text(), sort, open.spanTo(close));
    }

    public IrStmt.FunctionDecl parseFunctionDecl() throws ParseException {
        SexprToken open = expect(SexprToken.Kind.LPAREN);
        expectSymbol(language.functionDeclKeyword());
        SexprToken nameTok = expect(SexprToken.Kind.SYMBOL);
        expect(SexprToken.Kind.LPAREN);
        List<IrParam> params = new ArrayList<>();
        while (peek().kind() != SexprToken.Kind.RPAREN) {
            params.add(parseParam());
        }
        expect(SexprToken.Kind.RPAREN);
        IrSort returnSort = parseSort();
        IrExpr body = parseExpr();
        SexprToken close = expect(SexprToken.Kind.RPAREN);
        return new IrStmt.FunctionDecl(nameTok.text(), params, returnSort, body, open.spanTo(close));
    }

    private IrParam parseParam() throws ParseException {
        expect(SexprToken.Kind.LPAREN);
        SexprToken nameTok = expect(SexprToken.Kind.SYMBOL);
        IrSort sort = parseSort();
        expect(SexprToken.Kind.RPAREN);
        return new IrParam(nameTok.text(), sort);
    }

    public IrSort parseSort() throws ParseException {
        SexprToken t = peek();
        if (t.kind() == SexprToken.Kind.SYMBOL) {
            consume();
            return new IrSort.Named(t.text(), t.origin());
        }
        if (t.kind() == SexprToken.Kind.LPAREN) {
            SexprToken open = consume();
            SexprToken head = expect(SexprToken.Kind.SYMBOL);
            if (head.text().equals(language.refinedSortKeyword())) {
                SexprToken sortNameTok = expect(SexprToken.Kind.SYMBOL);
                IrExpr predicate = parseExpr();
                SexprToken close = expect(SexprToken.Kind.RPAREN);
                return new IrSort.Refined(sortNameTok.text(), predicate, open.spanTo(close));
            }
            if (head.text().equals(language.functionSortKeyword())) {
                // (function (paramSort1 paramSort2 ...) returnSort)
                expect(SexprToken.Kind.LPAREN);
                List<IrSort> paramSorts = new ArrayList<>();
                while (peek().kind() != SexprToken.Kind.RPAREN) {
                    paramSorts.add(parseSort());
                }
                expect(SexprToken.Kind.RPAREN);
                IrSort returnSort = parseSort();
                SexprToken close = expect(SexprToken.Kind.RPAREN);
                return new IrSort.CallSig(IrSort.CallSig.METHOD, paramSorts, returnSort, open.spanTo(close));
            }
            if (head.text().equals(language.structSortKeyword())) {
                // (struct Name (field1 sort1) (field2 sort2) ...)
                SexprToken sortNameTok = expect(SexprToken.Kind.SYMBOL);
                java.util.Map<String, IrSort> members = new java.util.LinkedHashMap<>();
                while (peek().kind() != SexprToken.Kind.RPAREN) {
                    expect(SexprToken.Kind.LPAREN);
                    SexprToken memberNameTok = expect(SexprToken.Kind.SYMBOL);
                    IrSort memberSort = parseSort();
                    expect(SexprToken.Kind.RPAREN);
                    members.put(memberNameTok.text(), memberSort);
                }
                SexprToken close = expect(SexprToken.Kind.RPAREN);
                return new IrSort.Structural(sortNameTok.text(), members, open.spanTo(close));
            }
            throw new ParseException(
                    "Unknown sort form: '" + head.text() + "' (expected '"
                            + language.refinedSortKeyword() + "', '"
                            + language.functionSortKeyword() + "', or '"
                            + language.structSortKeyword() + "')",
                    head.origin());
        }
        throw new ParseException(
                "Expected a sort (SYMBOL or sort form); got "
                        + t.kind() + " '" + t.text() + "'",
                t.origin());
    }

    // --- Expression parser ---

    public IrExpr parseExpr() throws ParseException {
        SexprToken t = peek();
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

    private IrExpr parseCompoundExpr() throws ParseException {
        SexprToken open = expect(SexprToken.Kind.LPAREN);
        SexprToken head = expect(SexprToken.Kind.SYMBOL);
        String h = head.text();
        if (language.isBinaryOp(h)) {
            IrExpr left = parseExpr();
            IrExpr right = parseExpr();
            SexprToken close = expect(SexprToken.Kind.RPAREN);
            return new IrExpr.BinOp(language.binaryOpFor(h).orElseThrow(), left, right, open.spanTo(close));
        }
        if (h.equals(language.letKeyword()))    return parseLet(open);
        if (h.equals(language.callKeyword()))   return parseCall(open);
        if (h.equals(language.matchKeyword()))  return parseMatch(open);
        if (h.equals(language.lambdaKeyword())) return parseLambda(open);
        if (h.equals(language.recordKeyword())) return parseRecord(open);
        if (h.equals(language.fieldKeyword()))  return parseFieldAccess(open);
        throw new ParseException(
                "Unknown form: '" + h + "'",
                head.origin());
    }

    private IrExpr parseLet(SexprToken open) throws ParseException {
        SexprToken nameTok = expect(SexprToken.Kind.SYMBOL);
        IrSort sort = parseSort();
        IrExpr value = parseExpr();
        IrExpr body = parseExpr();
        SexprToken close = expect(SexprToken.Kind.RPAREN);
        return new IrExpr.LetIn(nameTok.text(), sort, value, body, open.spanTo(close));
    }

    private IrExpr parseCall(SexprToken open) throws ParseException {
        // The head can be either a bare SYMBOL (looked up first in the local
        // scope, then in the dispatch table) or a compound form (an inline
        // lambda, a (call ...) that returns a closure, etc.). The two shapes
        // map to the IR's Call vs Apply variants respectively.
        SexprToken head = peek();
        if (head.kind() == SexprToken.Kind.SYMBOL) {
            SexprToken nameTok = consume();
            List<IrExpr> args = new ArrayList<>();
            while (peek().kind() != SexprToken.Kind.RPAREN) {
                args.add(parseExpr());
            }
            SexprToken close = expect(SexprToken.Kind.RPAREN);
            return new IrExpr.Call(nameTok.text(), args, open.spanTo(close));
        }
        IrExpr fn = parseExpr();
        List<IrExpr> args = new ArrayList<>();
        while (peek().kind() != SexprToken.Kind.RPAREN) {
            args.add(parseExpr());
        }
        SexprToken close = expect(SexprToken.Kind.RPAREN);
        return new IrExpr.Apply(fn, args, open.spanTo(close));
    }

    private IrExpr parseMatch(SexprToken open) throws ParseException {
        IrExpr scrutinee = parseExpr();
        List<IrExpr.MatchBranch> branches = new ArrayList<>();
        while (peek().kind() != SexprToken.Kind.RPAREN) {
            expect(SexprToken.Kind.LPAREN);
            IrSort pattern = parseSort();
            IrExpr result = parseExpr();
            expect(SexprToken.Kind.RPAREN);
            branches.add(new IrExpr.MatchBranch(pattern, result));
        }
        SexprToken close = expect(SexprToken.Kind.RPAREN);
        if (branches.isEmpty()) {
            throw new ParseException(
                    "Match form must have at least one branch",
                    open.origin());
        }
        Origin matchOrigin = open.spanTo(close);

        // Desugar destructuring: if any branch uses a structural pattern, the
        // field names from the pattern become bindings in the branch's result.
        // We rewrite each such branch from
        //     ((struct N (a S1) (b S2)) result)
        // into
        //     ((struct N (a S1) (b S2)) (let a S1 (field SCR a) (let b S2 (field SCR b) result)))
        // where SCR is a Var reference to the scrutinee. If the scrutinee isn't
        // already a Var, we wrap the whole match in an outer let so it's
        // evaluated once.
        boolean anyStructural = branches.stream().anyMatch(
                b -> b.pattern() instanceof IrSort.Structural);
        if (!anyStructural) {
            return new IrExpr.Match(scrutinee, branches, matchOrigin);
        }

        boolean needsOuterLet = !(scrutinee instanceof IrExpr.Var);
        String outerLetName = null;
        IrExpr scrutineeRef;
        if (needsOuterLet) {
            outerLetName = "__scrutinee$" + (syntheticNameCounter++);
            scrutineeRef = new IrExpr.Var(outerLetName, Origin.NONE);
        } else {
            scrutineeRef = scrutinee;
        }

        List<IrExpr.MatchBranch> wrappedBranches = new ArrayList<>(branches.size());
        for (IrExpr.MatchBranch b : branches) {
            IrExpr wrappedResult = b.result();
            if (b.pattern() instanceof IrSort.Structural sp) {
                // Wrap in reverse insertion order so the outermost let is the
                // first field; reading the resulting IR top-to-bottom matches
                // the field declaration order.
                List<Map.Entry<String, IrSort>> entries =
                        new ArrayList<>(sp.members().entrySet());
                for (int i = entries.size() - 1; i >= 0; i--) {
                    Map.Entry<String, IrSort> e = entries.get(i);
                    wrappedResult = new IrExpr.LetIn(
                            e.getKey(),
                            e.getValue(),
                            new IrExpr.FieldAccess(scrutineeRef, e.getKey(), Origin.NONE),
                            wrappedResult,
                            Origin.NONE);
                }
            }
            wrappedBranches.add(new IrExpr.MatchBranch(b.pattern(), wrappedResult));
        }

        IrExpr match = new IrExpr.Match(scrutineeRef, wrappedBranches, matchOrigin);
        if (!needsOuterLet) {
            return match;
        }
        return new IrExpr.LetIn(outerLetName, IrSort.named("_"), scrutinee, match, matchOrigin);
    }

    private IrExpr parseRecord(SexprToken open) throws ParseException {
        // (record (field1 value1) (field2 value2) ...)
        java.util.Map<String, IrExpr> members = new java.util.LinkedHashMap<>();
        while (peek().kind() != SexprToken.Kind.RPAREN) {
            expect(SexprToken.Kind.LPAREN);
            SexprToken nameTok = expect(SexprToken.Kind.SYMBOL);
            IrExpr value = parseExpr();
            expect(SexprToken.Kind.RPAREN);
            members.put(nameTok.text(), value);
        }
        SexprToken close = expect(SexprToken.Kind.RPAREN);
        return new IrExpr.Record(members, open.spanTo(close));
    }

    private IrExpr parseFieldAccess(SexprToken open) throws ParseException {
        // (field base fieldName)
        IrExpr base = parseExpr();
        SexprToken nameTok = expect(SexprToken.Kind.SYMBOL);
        SexprToken close = expect(SexprToken.Kind.RPAREN);
        return new IrExpr.FieldAccess(base, nameTok.text(), open.spanTo(close));
    }

    private IrExpr parseLambda(SexprToken open) throws ParseException {
        // Same shape as a defn body: '(' params ')' sort body, sans name.
        expect(SexprToken.Kind.LPAREN);
        List<IrParam> params = new ArrayList<>();
        while (peek().kind() != SexprToken.Kind.RPAREN) {
            params.add(parseParam());
        }
        expect(SexprToken.Kind.RPAREN);
        IrSort returnSort = parseSort();
        IrExpr body = parseExpr();
        SexprToken close = expect(SexprToken.Kind.RPAREN);
        return new IrExpr.Lambda(params, returnSort, body, open.spanTo(close));
    }
}
