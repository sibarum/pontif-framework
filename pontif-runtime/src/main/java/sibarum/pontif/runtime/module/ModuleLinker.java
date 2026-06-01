package sibarum.pontif.runtime.module;

import sibarum.pontif.core.Origin;
import sibarum.pontif.ir.CoherenceCheck;
import sibarum.pontif.ir.CompileException;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrStmt;
import sibarum.pontif.ir.ModuleImportCheck;
import sibarum.pontif.ir.ModuleSymbolTable;
import sibarum.pontif.ir.NameResolver;
import sibarum.pontif.ir.StructLiteralRewriter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Links the parsed modules of a project into a single combined {@link IrModule}
 * the ordinary compiler can consume. The strategy is deliberately simple: build
 * the cross-module ownership table, enforce the coherence/orphan rule, rewrite
 * each module's names to FQNs ({@code module/localKey}) via {@link NameResolver},
 * then <b>concatenate</b> every module's statements into one module with the
 * entry module's {@code main}.
 *
 * <p>Because FQN keys are disjoint per module, concatenation can't collide on
 * function names, and a cross-module call (already rewritten to the callee's
 * FQN) resolves against the combined declaration set — so {@code SortChecker},
 * overload-overlap, and the return gate all run unchanged over the combined
 * module. A single-file compile never goes through here, so its (bare) keys are
 * untouched.
 *
 * <p>Type names are per-module too: {@code NameResolver} FQN-rewrites struct/
 * trait/alias names (and every reference), so two modules can reuse a type name
 * (`a/Point` vs `b/Point`) without colliding in the combined module. A
 * duplicate-type-alias error now only fires on a genuine same-module
 * redeclaration.
 */
public final class ModuleLinker {

    private ModuleLinker() {}

    /**
     * @param modules     {@code moduleName → parsed IrModule}
     * @param entryModule the module whose {@code main} runs
     * @throws CompileException on an unknown entry module or a coherence violation
     */
    public static IrModule combine(Map<String, IrModule> modules, String entryModule)
            throws CompileException {
        if (!modules.containsKey(entryModule)) {
            throw new CompileException(
                    "Unknown entry module '" + entryModule + "'", Origin.NONE);
        }
        ModuleSymbolTable table = ModuleSymbolTable.build(modules);
        // Coherence + import validation run on the pre-FQN-rewrite modules
        // (bare names match the table).
        CoherenceCheck.check(modules, table);
        ModuleImportCheck.check(modules, table);

        List<IrStmt> statements = new ArrayList<>();
        IrExpr main = IrExpr.lit(0);
        for (Map.Entry<String, IrModule> e : modules.entrySet()) {
            IrModule resolved = NameResolver.resolve(e.getValue(), table);
            statements.addAll(resolved.statements());
            if (e.getKey().equals(entryModule)) {
                main = resolved.main();
            }
        }
        // Constructor-shaped calls to *imported* structs parsed as Calls (the
        // parser only sees local structs); now that every struct definition is
        // FQN'd and visible in the combined module, rewrite them to Records.
        return StructLiteralRewriter.rewrite(new IrModule(entryModule, statements, main));
    }
}
