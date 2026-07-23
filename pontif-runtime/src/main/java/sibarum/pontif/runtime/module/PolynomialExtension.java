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
}
