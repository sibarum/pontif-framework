package sibarum.pontif.runtime.module;

/**
 * The builtin <b>polynomial extension</b> ({@code pontif.poly}) — a small computer-algebra core
 * for tame polynomials, written <b>entirely in Pontif</b> over {@link AlgebraExtension}'s
 * {@code AlgExpr} union. Unlike the algebra extension it backs <b>no</b> native calls: every
 * function has a real Pontif body, so this is purely an interface module whose source is the
 * implementation (inspectable in the editor like any builtin).
 *
 * <ul>
 *   <li>{@code substitute(e, name, r)} — replace variable {@code name} with expression {@code r}
 *       (total, structural).</li>
 *   <li>{@code expand(e)} — multiply out: distribute {@code Mul} over {@code Add}, unroll an
 *       integer {@code Pow}, and normalize {@code Sub} to add-a-negation. Result is a flat
 *       sum-of-products.</li>
 *   <li>{@code simplify(e)} — combine like terms by <b>monomial</b>: two expanded terms merge only
 *       when their non-constant parts are the same monomial, so a non-polynomial subtree (e.g.
 *       {@code sin(x)}) is an opaque atom that survives untouched. Variable-agnostic (multivariate)
 *       and eval-preserving — "can't combine" means "left unchanged", never a fabricated value.</li>
 *   <li>{@code differentiate(e, x)} — symbolic derivative w.r.t. {@code x} by structural recursion
 *       (sum / product / quotient / power / chain rules + the transcendental pushforwards). Total;
 *       the result is correct but unsimplified — pipe through {@code simplify} for a tidy form.</li>
 *   <li>{@code Expression} — a chainable wrapper struct around an {@code AlgExpr} exposing the
 *       transforms as methods ({@code Expression(t).expand().simplify("x").eval(3.0)}). Use a bare
 *       {@code AlgExpr} when you only want the tree; wrap it to build a transformation pipeline.
 *       The methods are thin wrappers over the free functions; this module owns the struct, so the
 *       coherence rule is satisfied without pushing operations down into {@code pontif.algebra}.</li>
 * </ul>
 *
 * <p><b>Scope (v1):</b> tame polynomials. A degree-100 blow-up is acceptable (beyond the 80%
 * case). Two follow-on rungs are deliberately unbuilt: <b>multivariate</b> {@code simplify}
 * (needs monomial canonicalization — sort factors by name + merge exponents), and a
 * <b>display-normalization</b> pass (fold {@code Mul(Const,Const)}, drop {@code *1}, collapse a
 * repeated {@code Mul} into {@code Pow}) — the result is eval-exact but not display-minimal.
 *
 * <p>Pure Pontif + pure-JDK (it only {@code requires pontif.algebra}), so it is installed by
 * default alongside {@link AlgebraExtension}.
 */
public final class PolynomialExtension implements Extension {

    public static final PolynomialExtension INSTANCE = new PolynomialExtension();

    private PolynomialExtension() {}

    @Override
    public String moduleName() {
        return "pontif.poly";
    }

    @Override
    public String pontifSource() {
        return SOURCE;
    }

    private static final String SOURCE = """
            requires pontif.algebra.{AlgExpr, Const, Param, Add, Sub, Mul, Div, Pow,
                                     Sin, Cos, Tan, Exp, Log, eval}
            exports @.{substitute, expand, simplify, differentiate, Expression}

            # substitute: replace variable `name` with expression `r` (total, structural).
            function substitute(e:AlgExpr, name:String, r:AlgExpr):AlgExpr -> match e {
              [Const(v)]   -> Const(v)
              [Param(pn)]  -> match (pn == name) { [Bool:true] -> r  [Bool:false] -> Param(pn) }
              [Add(l, rr)] -> Add(substitute(l, name, r), substitute(rr, name, r))
              [Sub(l, rr)] -> Sub(substitute(l, name, r), substitute(rr, name, r))
              [Mul(l, rr)] -> Mul(substitute(l, name, r), substitute(rr, name, r))
              [Div(l, rr)] -> Div(substitute(l, name, r), substitute(rr, name, r))
              [Pow(b, x)]  -> Pow(substitute(b, name, r), substitute(x, name, r))
              [_]          -> e
            }

            # expand: distribute Mul over Add, unroll integer Pow, normalize Sub.
            # `mul` multiplies two operands, distributing over any Add it meets.
            function mul(a:AlgExpr, b:AlgExpr):AlgExpr -> match a {
              [Add(a1, a2)] -> Add(mul(a1, b), mul(a2, b))
              [_] -> match b {
                [Add(b1, b2)] -> Add(mul(a, b1), mul(a, b2))
                [_]           -> Mul(a, b)
              }
            }

            # powExpand: b^n as repeated multiplication (n a non-negative integer literal).
            function powExpand(b:AlgExpr, n:Decimal):AlgExpr -> match (n <= 0.0) {
              [Bool:true]  -> Const(1.0)
              [Bool:false] -> mul(b, powExpand(b, n - 1.0))
            }

            function expand(e:AlgExpr):AlgExpr -> match e {
              [Add(l, r)] -> Add(expand(l), expand(r))
              [Sub(l, r)] -> Add(expand(l), mul(Const(-1.0), expand(r)))
              [Mul(l, r)] -> mul(expand(l), expand(r))
              [Pow(b, x)] -> match x {
                [Const(n)] -> powExpand(expand(b), n)
                [_]        -> Pow(expand(b), expand(x))
              }
              [_] -> e
            }

            # simplify: combine like terms by MONOMIAL. Two expanded terms combine only when their
            # non-constant parts (their monomial) are the same; anything that isn't the same monomial
            # is left alone. So a non-polynomial subtree (sin(x), …) is an opaque atom — its own
            # monomial — and survives untouched. Eval-preserving; variable-agnostic (multivariate).

            function both(a:Bool, b:Bool):Bool -> match a { [Bool:true] -> b  [Bool:false] -> false }

            # structural equality of two expressions (used to compare monomials).
            function sameExpr(a:AlgExpr, b:AlgExpr):Bool -> match a {
              [Const(x)]   -> match b { [Const(y)] -> x == y  [_] -> false }
              [Param(x)]   -> match b { [Param(y)] -> x == y  [_] -> false }
              [Add(a1,a2)] -> match b { [Add(b1,b2)] -> both(sameExpr(a1,b1), sameExpr(a2,b2))  [_] -> false }
              [Sub(a1,a2)] -> match b { [Sub(b1,b2)] -> both(sameExpr(a1,b1), sameExpr(a2,b2))  [_] -> false }
              [Mul(a1,a2)] -> match b { [Mul(b1,b2)] -> both(sameExpr(a1,b1), sameExpr(a2,b2))  [_] -> false }
              [Div(a1,a2)] -> match b { [Div(b1,b2)] -> both(sameExpr(a1,b1), sameExpr(a2,b2))  [_] -> false }
              [Pow(a1,a2)] -> match b { [Pow(b1,b2)] -> both(sameExpr(a1,b1), sameExpr(a2,b2))  [_] -> false }
              [Sin(a1)]    -> match b { [Sin(b1)] -> sameExpr(a1,b1)  [_] -> false }
              [Cos(a1)]    -> match b { [Cos(b1)] -> sameExpr(a1,b1)  [_] -> false }
              [Tan(a1)]    -> match b { [Tan(b1)] -> sameExpr(a1,b1)  [_] -> false }
              [Exp(a1)]    -> match b { [Exp(b1)] -> sameExpr(a1,b1)  [_] -> false }
              [Log(a1)]    -> match b { [Log(b1)] -> sameExpr(a1,b1)  [_] -> false }
              [_] -> false
            }

            # coefficient of a term = product of its Const factors.
            function coeffOf(t:AlgExpr):Decimal -> match t {
              [Const(v)] -> v
              [Mul(a,b)] -> coeffOf(a) * coeffOf(b)
              [_] -> 1.0
            }
            # monomial of a term = product of its non-Const factors (Const(1.0) if none).
            function mulMono(a:AlgExpr, b:AlgExpr):AlgExpr -> match a {
              [Const(_)] -> b
              [_] -> match b { [Const(_)] -> a  [_] -> Mul(a, b) }
            }
            function monoOf(t:AlgExpr):AlgExpr -> match t {
              [Const(_)] -> Const(1.0)
              [Mul(a,b)] -> mulMono(monoOf(a), monoOf(b))
              [_] -> t
            }

            # a canonical term is Mul(Const(coeff), monomial).
            function makeTerm(c:Decimal, m:AlgExpr):AlgExpr -> Mul(Const(c), m)
            function monoPart(t:AlgExpr):AlgExpr -> match t { [Mul(_, m)] -> m  [_] -> Const(1.0) }
            function coeffPart(t:AlgExpr):Decimal -> match t { [Mul(Const(c), _)] -> c  [_] -> 1.0 }

            # insert coeff*monomial into the accumulated sum, merging into a like-monomial term.
            function insertTerm(acc:AlgExpr, c:Decimal, m:AlgExpr):AlgExpr -> match acc {
              [Const(_)] -> makeTerm(c, m)
              [Add(head, rest)] -> match sameExpr(monoPart(head), m) {
                  [Bool:true]  -> Add(makeTerm(coeffPart(head) + c, m), rest)
                  [Bool:false] -> Add(head, insertTerm(rest, c, m))
                }
              [_] -> match sameExpr(monoPart(acc), m) {
                  [Bool:true]  -> makeTerm(coeffPart(acc) + c, m)
                  [Bool:false] -> Add(makeTerm(c, m), acc)
                }
            }
            function foldTerms(e:AlgExpr, acc:AlgExpr):AlgExpr -> match e {
              [Add(l, r)] -> foldTerms(r, foldTerms(l, acc))
              [_] -> insertTerm(acc, coeffOf(e), monoOf(e))
            }

            function simplify(e:AlgExpr):AlgExpr -> foldTerms(expand(e), Const(0.0))

            # differentiate: symbolic derivative w.r.t. `x`, by structural recursion — one rule per
            # node (sum/product/quotient/power/chain, plus the transcendental pushforwards). Total.
            # The result is correct but unsimplified (e.g. d(x^2) = 2*x^1*1) — pipe through simplify.
            function differentiate(e:AlgExpr, x:String):AlgExpr -> match e {
              [Const(_)]  -> Const(0.0)
              [Param(n)]  -> match (n == x) { [Bool:true] -> Const(1.0)  [Bool:false] -> Const(0.0) }
              [Add(a, b)] -> Add(differentiate(a, x), differentiate(b, x))
              [Sub(a, b)] -> Sub(differentiate(a, x), differentiate(b, x))
              [Mul(a, b)] -> Add(Mul(differentiate(a, x), b), Mul(a, differentiate(b, x)))
              [Div(a, b)] -> Div(Sub(Mul(differentiate(a, x), b), Mul(a, differentiate(b, x))), Mul(b, b))
              [Pow(a, b)] -> match b {
                # power rule for a literal exponent; general a^b via the log rule otherwise.
                [Const(n)] -> Mul(Mul(Const(n), Pow(a, Const(n - 1.0))), differentiate(a, x))
                [_]        -> Mul(Pow(a, b),
                                  Add(Mul(differentiate(b, x), Log(a)),
                                      Div(Mul(b, differentiate(a, x)), a)))
              }
              [Sin(a)]    -> Mul(Cos(a), differentiate(a, x))
              [Cos(a)]    -> Mul(Sub(Const(0.0), Sin(a)), differentiate(a, x))
              [Tan(a)]    -> Div(differentiate(a, x), Mul(Cos(a), Cos(a)))
              [Exp(a)]    -> Mul(Exp(a), differentiate(a, x))
              [Log(a)]    -> Div(differentiate(a, x), a)
              [_]         -> Const(0.0)
            }

            # Expression: a chainable wrapper around the internal AlgExpr AST — a nicer public API
            # for transformation pipelines. Use a bare AlgExpr when you only want the tree (match,
            # build by hand, $f[Decimal].ast); wrap it in an Expression to chain transforms. Each
            # transform returns a new Expression; `.ast` drops back to the raw tree, `.eval(x)` ends
            # a chain with a value. The methods are thin wrappers over the free functions above.
            struct Expression(ast:AlgExpr)
            method Expression.substitute(name:String, r:AlgExpr):Expression -> Expression(substitute(this.ast, name, r))
            method Expression.expand():Expression -> Expression(expand(this.ast))
            method Expression.simplify():Expression -> Expression(simplify(this.ast))
            method Expression.differentiate(x:String):Expression -> Expression(differentiate(this.ast, x))
            method Expression.eval(x:Decimal):Decimal -> eval(this.ast, x)

            0
            """;
}
