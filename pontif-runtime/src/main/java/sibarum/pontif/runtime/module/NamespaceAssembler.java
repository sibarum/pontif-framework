package sibarum.pontif.runtime.module;

import sibarum.pontif.core.Origin;
import sibarum.pontif.ir.CompileException;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrStmt;

import java.util.ArrayList;
import java.util.List;

/**
 * Folds the several source files that declare one namespace into a single
 * {@link IrModule}. This is the ONE place the "a namespace spans every file whose
 * {@code module a.b} header matches" policy is applied — both the eager
 * whole-project loader ({@link ModuleLoader}) and the demand-driven resolver
 * ({@link ModuleResolver}) route through here, so the strategy can be changed or
 * enhanced (e.g. folder-scoped packages, richer merge rules) without touching
 * either loader. Pure: no I/O — callers parse the files and hand over the modules.
 *
 * <p>The merge is intentionally minimal for now: statements are concatenated in
 * the caller-supplied order and at most one file may carry the entry {@code main}
 * expression. Duplicate-definition and duplicate-{@code requires} reconciliation
 * is left to the existing link/coherence checks; tighten here if that proves
 * necessary.
 */
public final class NamespaceAssembler {

    private NamespaceAssembler() {}

    /**
     * Merge {@code parts} — all of which must declare namespace {@code name}, in
     * the order given — into one module. A single part is returned unchanged (the
     * common single-file case stays byte-for-byte as before).
     *
     * @throws CompileException if more than one part carries a non-trivial
     *                          {@code main} (only one file per namespace may hold
     *                          the entry expression)
     */
    public static IrModule merge(String name, List<IrModule> parts) throws CompileException {
        if (parts.size() == 1) {
            return parts.get(0);
        }
        List<IrStmt> statements = new ArrayList<>();
        IrExpr main = null;
        for (IrModule part : parts) {
            statements.addAll(part.statements());
            if (!isTrivialMain(part.main())) {
                if (main != null) {
                    throw new CompileException(
                            "namespace '" + name + "' carries a `main` entry expression in more "
                                    + "than one file — only one file per namespace may hold it",
                            part.main().origin());
                }
                main = part.main();
            }
        }
        return new IrModule(name, statements, main == null ? new IrExpr.Lit(0, Origin.NONE) : main);
    }

    /** The parser's "no entry expression" sentinel is {@code Lit(0)} (see AltParser.parseModule). */
    private static boolean isTrivialMain(IrExpr main) {
        return main instanceof IrExpr.Lit lit && lit.value() == 0;
    }
}
