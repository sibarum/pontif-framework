package sibarum.pontif.ir;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import sibarum.pontif.core.QualifiedName;

/**
 * Splits a struct's intersection is-a base into its single struct super and its
 * trait obligations (docs/struct-methods.md). A struct member block may name its
 * is-a base as an intersection — {@code struct Sub:[Super & T1 & T2](…){ … }} —
 * carrying at most one struct supertype plus zero or more traits the struct must
 * satisfy. That intersection is stored whole on {@link IrSort.Structural#baseSort()}
 * by the parser; this pass takes it apart:
 *
 * <ul>
 *   <li>the (≤1) <b>struct super</b> branch stays the struct's {@code baseSort} — so
 *       every downstream is-a consumer (the ancestry walk, the demotion/totality
 *       check, the construction gate) sees an ordinary single-base struct, exactly
 *       as {@code struct Sub:Super(…)} always has;</li>
 *   <li>each <b>trait</b> branch becomes an empty {@code assign trait Sub:T} impl —
 *       so trait registration, default expansion, and satisfaction verification all
 *       ride the existing {@link IrStmt.TraitImpl} pipeline. The struct's block
 *       methods (standalone {@code Sub.m} decls emitted by the parser) are the one
 *       method set each such trait is checked against — SortChecker's some-method
 *       pool rule.</li>
 * </ul>
 *
 * <p>Runs AFTER {@link AliasResolver}: a base branch is then a resolved sort, so a
 * {@link IrSort.Trait} branch is unambiguously a trait obligation and a nominal
 * ({@link IrSort.Named}/{@link IrSort.Refined}) branch naming a declared struct is
 * the super. Idempotent — once a struct's base is a single (non-intersection) sort
 * there is nothing to split, so the pipeline's second run (AliasResolver and this
 * pass are re-run inside {@link IrCompiler}) is a no-op.
 */
public final class StructTraitLowering {

    private StructTraitLowering() {}

    public static IrModule lower(IrModule module) throws CompileException {
        // Names declared in this module, to classify a nominal base branch. A branch
        // is a trait obligation if it is an IrSort.Trait (the resolved form) or names
        // a declared trait; otherwise it must name a declared struct — the super.
        Set<String> structNames = new HashSet<>();
        Set<String> traitNames = new HashSet<>();
        for (IrStmt s : module.statements()) {
            if (s instanceof IrStmt.TypeAlias ta) {
                if (ta.sort() instanceof IrSort.Structural) structNames.add(ta.name());
                else if (ta.sort() instanceof IrSort.Trait) traitNames.add(ta.name());
            }
        }

        // A struct's is-a base carries a trait obligation when it is an intersection
        // with a trait branch, or is itself a (single) trait. A base that is purely a
        // struct super — `struct Sub:Super(…)` / `Sub:[Super:rel](…)` — is left alone,
        // which also makes the pipeline's second run a no-op (docs/struct-methods.md).
        boolean any = false;
        for (IrStmt s : module.statements()) {
            if (s instanceof IrStmt.TypeAlias ta
                    && ta.sort() instanceof IrSort.Structural st
                    && st.baseSort() != null
                    && needsSplit(st.baseSort(), traitNames)) {
                any = true;
                break;
            }
        }
        if (!any) return module;

        List<IrStmt> out = new ArrayList<>(module.statements().size());
        for (IrStmt s : module.statements()) {
            if (!(s instanceof IrStmt.TypeAlias ta
                    && ta.sort() instanceof IrSort.Structural st
                    && st.baseSort() != null
                    && needsSplit(st.baseSort(), traitNames))) {
                out.add(s);
                continue;
            }

            // Normalize the base to its branches — an intersection splits, any other
            // base is a single branch. Partition into the (≤1) struct super and the
            // trait obligations.
            List<IrSort> branches = st.baseSort() instanceof IrSort.Intersection inter
                    ? inter.branches()
                    : List.of(st.baseSort());
            IrSort superBranch = null;
            List<String> traitBranches = new ArrayList<>();
            for (IrSort branch : branches) {
                String traitName = traitObligation(branch, traitNames);
                if (traitName != null) {
                    traitBranches.add(traitName);
                    continue;
                }
                // Not a trait — it must be the struct super.
                if (superBranch != null) {
                    throw new CompileException(
                            "Struct '" + st.name() + "' declares more than one struct supertype ("
                                    + describe(superBranch) + " and " + describe(branch)
                                    + ") in its is-a base — a struct may have at most one struct "
                                    + "supertype (plus any number of traits).",
                            st.origin());
                }
                if (!isDeclaredStruct(branch, structNames)) {
                    throw new CompileException(
                            "Struct '" + st.name() + "' is-a base names '" + describe(branch)
                                    + "', which is neither a declared struct nor a trait.",
                            st.origin());
                }
                superBranch = branch;
            }

            // The struct keeps its single struct super (or none) as an ordinary base.
            out.add(new IrStmt.TypeAlias(
                    ta.name(),
                    new IrSort.Structural(st.name(), st.members(), superBranch,
                            st.typeParams(), st.extensions(), st.origin()),
                    ta.origin()));
            // Each trait becomes an empty impl — verified against the struct's block
            // methods by SortChecker, defaults filled by TraitDefaultExpansion.
            for (String traitName : traitBranches) {
                out.add(new IrStmt.TraitImpl(
                        st.name(), traitName, List.of(), List.of(), st.origin()));
            }
        }
        return new IrModule(module.name(), out, module.main());
    }

    /**
     * Whether a base needs splitting: any intersection (so an all-struct `[A & B]`
     * still reaches the one-supertype check), or a base naming a trait. A base that
     * is purely a single struct super is left untouched.
     */
    private static boolean needsSplit(IrSort base, Set<String> traitNames) {
        return base instanceof IrSort.Intersection || namesAnyTrait(base, traitNames);
    }

    /** Whether a struct base is, or (as an intersection) contains, a trait branch. */
    private static boolean namesAnyTrait(IrSort base, Set<String> traitNames) {
        List<IrSort> branches = base instanceof IrSort.Intersection inter
                ? inter.branches()
                : List.of(base);
        for (IrSort branch : branches) {
            if (traitObligation(branch, traitNames) != null) return true;
        }
        return false;
    }

    /**
     * The trait name a base branch obligates, or null if the branch is not a trait.
     * A resolved {@link IrSort.Trait} carries its own name; a bare nominal that names
     * a declared trait (a form that can survive when the trait alias was not inlined)
     * is also an obligation.
     */
    private static String traitObligation(IrSort branch, Set<String> traitNames) {
        if (branch instanceof IrSort.Trait t) return t.name();
        String name = branch.baseName();
        return name != null && traitNames.contains(name) ? name : null;
    }

    private static boolean isDeclaredStruct(IrSort branch, Set<String> structNames) {
        String name = branch.baseName();
        return name != null
                && (structNames.contains(name) || structNames.contains(QualifiedName.memberOf(name)));
    }

    private static String describe(IrSort branch) {
        String name = branch.baseName();
        return name != null ? name : branch.toString();
    }
}
