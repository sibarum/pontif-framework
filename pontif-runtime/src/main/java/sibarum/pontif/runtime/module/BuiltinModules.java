package sibarum.pontif.runtime.module;

import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrSort;
import sibarum.pontif.ir.IrStmt;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiler-provided modules, constructed in Java rather than parsed from a
 * shipped {@code .ptf} source. They are seeded into the linker's module
 * universe so user code can {@code requires} them like any other module —
 * exercising the real import path (imports/exports), which is the point: a
 * standard library you import is the proof that the module system is complete,
 * not just demo-complete.
 *
 * <p>Visibility is <b>explicit</b>: a builtin module is injected by
 * {@link ModuleLinker} only when some user module actually {@code requires} it,
 * so a program that never imports it is unaffected (in particular, a user's own
 * {@code Leaf}/{@code Split} type is never shadowed nor made ambiguous).
 *
 * <p>The first builtin is {@code std.proof}: the proof-authoring vocabulary
 * ({@code Leaf}, {@code Split}, {@code Singletons}) that proofs previously had
 * to hand-redeclare. {@code RefinementProof} recognizes these by local name and
 * translates a proof tree built from them into a {@code Refinement}.
 */
public final class BuiltinModules {

    /** The proof-authoring builtin module's name. */
    public static final String STD_PROOF = "std.proof";

    private BuiltinModules() {}

    /** All builtin modules, by name. */
    public static Map<String, IrModule> all() {
        Map<String, IrModule> mods = new LinkedHashMap<>();
        mods.put(STD_PROOF, stdProof());
        return mods;
    }

    private static IrModule stdProof() {
        IrSort leafOrSplit = IrSort.union(List.of(IrSort.named("Leaf"), IrSort.named("Split")));

        // struct Leaf()
        IrStmt leaf = IrStmt.typeAlias("Leaf", IrSort.structural("Leaf", new LinkedHashMap<>()));

        // struct Split(p:Bool, whenTrue:[Leaf|Split], whenFalse:[Leaf|Split])
        Map<String, IrSort> splitFields = new LinkedHashMap<>();
        splitFields.put("p", IrSort.named("Bool"));
        splitFields.put("whenTrue", leafOrSplit);
        splitFields.put("whenFalse", leafOrSplit);
        IrStmt split = IrStmt.typeAlias("Split", IrSort.structural("Split", splitFields));

        // struct Singletons(subject:Int, lo:Int, hi:Int) — the generative
        // "recursion to singletons" directive; RefinementProof unfolds it into a
        // splitToSingletons ladder rather than reading it as a literal value.
        Map<String, IrSort> singletonsFields = new LinkedHashMap<>();
        singletonsFields.put("subject", IrSort.named("Int"));
        singletonsFields.put("lo", IrSort.named("Int"));
        singletonsFields.put("hi", IrSort.named("Int"));
        IrStmt singletons =
                IrStmt.typeAlias("Singletons", IrSort.structural("Singletons", singletonsFields));

        IrStmt exports = IrStmt.exports(List.of("Leaf", "Split", "Singletons"), true);

        return new IrModule(STD_PROOF,
                List.of(exports, leaf, split, singletons), IrExpr.lit(0));
    }
}
