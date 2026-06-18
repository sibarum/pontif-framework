package sibarum.pontif.ir;

import sibarum.pontif.core.QualifiedName;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Validates user-defined coercions ({@link IrStmt.Coercion}) against the ratified
 * rules (docs/cross-module-dispatch.md, docs/dispatch-unification.md §Coercion):
 *
 * <ul>
 *   <li><b>No primitive↔primitive.</b> Both source and target primitive is rejected —
 *       the closed {@code Int→Decimal} tower is built-in and unshadowable.</li>
 *   <li><b>Coherence.</b> At most one coercion per {@code (sourceBase, targetBase)}
 *       pair.</li>
 *   <li><b>Orphan rule.</b> The declaring module must own the source-base or the
 *       target-base type (checked at link time, where ownership is known). A LOCAL
 *       version per docs/cross-module-dispatch.md §6 phase 1; later subsumed by the
 *       generalized {@code CoherenceCheck}.</li>
 * </ul>
 *
 * <p>{@link #validate} runs wherever {@code SortChecker} runs (single-file and the
 * combined module) and covers prim↔prim + coherence. {@link #validateOrphans} runs
 * in the linker (needs {@link ModuleSymbolTable} ownership). Bases are compared by
 * their member (unqualified) name, so the check reads the same pre- and post-FQN.
 */
public final class CoercionCheck {

    /** A base is primitive when it is one of the closed built-in scalar sorts. */
    private static final Set<String> PRIMITIVES = Set.of("Int", "Bool", "Decimal", "Char", "String");

    private CoercionCheck() {}

    /** prim↔prim rejection + (sourceBase, targetBase) coherence over one module's coercions. */
    public static void validate(IrModule module) throws CompileException {
        Map<String, IrStmt.Coercion> seenPairs = new LinkedHashMap<>();
        for (IrStmt stmt : module.statements()) {
            if (!(stmt instanceof IrStmt.Coercion c)) {
                continue;
            }
            String src = memberBase(c.sourceSort());
            String tgt = memberBase(c.targetSort());
            if (PRIMITIVES.contains(src) && PRIMITIVES.contains(tgt)) {
                throw new CompileException(
                        "coercion '" + src + " → " + tgt + "' is not allowed — both are primitive; "
                                + "the closed primitive tower (e.g. Int→Decimal) is built-in and "
                                + "cannot be redefined", c.origin());
            }
            String pair = src + " → " + tgt;
            IrStmt.Coercion prior = seenPairs.put(pair, c);
            if (prior != null) {
                throw new CompileException(
                        "duplicate coercion '" + pair + "' — at most one coercion per "
                                + "(source, target) pair (first at " + prior.origin() + ")",
                        c.origin());
            }
        }
    }

    /**
     * Orphan rule: each coercion's declaring module must own the source-base or the
     * target-base type. Primitives have no owner, so a primitive base contributes no
     * ownership — `cast Decimal:(f:Frac)` is legal in Frac's module (owns the source),
     * `cast Foreign1:(x:Foreign2)` in a module owning neither is rejected.
     */
    public static void validateOrphans(Map<String, IrModule> modules, ModuleSymbolTable table)
            throws CompileException {
        for (Map.Entry<String, IrModule> e : modules.entrySet()) {
            String module = e.getKey();
            for (IrStmt stmt : e.getValue().statements()) {
                if (!(stmt instanceof IrStmt.Coercion c)) {
                    continue;
                }
                String src = memberBase(c.sourceSort());
                String tgt = memberBase(c.targetSort());
                boolean ownsSource = src != null && table.typeOwners(src).contains(module);
                boolean ownsTarget = tgt != null && table.typeOwners(tgt).contains(module);
                if (!ownsSource && !ownsTarget) {
                    throw new CompileException(
                            "orphan coercion '" + src + " → " + tgt + "' in module '" + module
                                    + "' — a coercion may be declared only in a module that owns "
                                    + "its source or target type", c.origin());
                }
            }
        }
    }

    /** The member (unqualified) base name of a sort — same pre- and post-FQN. */
    private static String memberBase(IrSort sort) {
        String base = Coercions.baseName(sort);
        return base == null ? null : QualifiedName.memberOf(base);
    }
}
