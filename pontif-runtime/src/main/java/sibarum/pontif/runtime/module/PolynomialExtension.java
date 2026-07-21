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
 *   <li>{@code simplify(e, name)} — combine like terms. <b>Univariate</b> (v1): groups the
 *       expanded terms of the single variable {@code name} by degree and sums their coefficients.
 *       Eval-preserving.</li>
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
            requires pontif.algebra.{AlgExpr, Const, Param, Add, Sub, Mul, Div, Pow}
            exports @.{substitute, expand, simplify}

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

            # simplify: combine like terms (univariate — group expanded terms by degree).
            function maxOf(a:Decimal, b:Decimal):Decimal -> match (a >= b) { [Bool:true] -> a  [Bool:false] -> b }

            # degree of a single product term (sum of variable exponents).
            function deg(e:AlgExpr):Decimal -> match e {
              [Param(_)]  -> 1.0
              [Const(_)]  -> 0.0
              [Mul(l, r)] -> deg(l) + deg(r)
              [Pow(b, x)] -> match x { [Const(k)] -> k * deg(b)  [_] -> 0.0 }
              [_] -> 0.0
            }

            # numeric coefficient of a single product term (product of its Const factors).
            function coef(e:AlgExpr):Decimal -> match e {
              [Param(_)]  -> 1.0
              [Const(v)]  -> v
              [Mul(l, r)] -> coef(l) * coef(r)
              [Pow(b, x)] -> coef(b)
              [_] -> 1.0
            }

            # the monomial name^d as a canonical product (name^0 = Const(1)).
            function monomial(name:String, d:Decimal):AlgExpr -> match (d <= 0.0) {
              [Bool:true]  -> Const(1.0)
              [Bool:false] -> Mul(Param(name), monomial(name, d - 1.0))
            }

            function term(name:String, c:Decimal, d:Decimal):AlgExpr -> Mul(Const(c), monomial(name, d))

            # prepend a term, treating a Const(0) accumulator as the empty sum (no leading +0).
            function addTerm(acc:AlgExpr, t:AlgExpr):AlgExpr -> match acc {
              [Const(z)] -> match (z == 0.0) { [Bool:true] -> t  [Bool:false] -> Add(t, acc) }
              [_]        -> Add(t, acc)
            }

            # sum of the coefficients of every term whose degree == d.
            function coefAtDeg(e:AlgExpr, d:Decimal):Decimal -> match e {
              [Add(l, r)] -> coefAtDeg(l, d) + coefAtDeg(r, d)
              [_] -> match (deg(e) == d) { [Bool:true] -> coef(e)  [Bool:false] -> 0.0 }
            }

            function maxDeg(e:AlgExpr):Decimal -> match e {
              [Add(l, r)] -> maxOf(maxDeg(l), maxDeg(r))
              [_] -> deg(e)
            }

            # assemble degrees d..0 (descending) into `acc`, dropping zero-coefficient terms.
            function assemble(e:AlgExpr, name:String, d:Decimal, acc:AlgExpr):AlgExpr -> match (d < 0.0) {
              [Bool:true]  -> acc
              [Bool:false] -> (
                let c:Decimal = coefAtDeg(e, d)
                match (c == 0.0) {
                  [Bool:true]  -> assemble(e, name, d - 1.0, acc)
                  [Bool:false] -> assemble(e, name, d - 1.0, addTerm(acc, term(name, c, d)))
                }
              )
            }

            function simplify(e:AlgExpr, name:String):AlgExpr -> (
              let ex:AlgExpr = expand(e)
              assemble(ex, name, maxDeg(ex), Const(0.0))
            )

            0
            """;
}
