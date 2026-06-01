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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    /**
     * Links a single parsed module <b>iff</b> it declares any {@code requires}
     * (so builtin modules are injected and names FQN-resolved); otherwise
     * returns it unchanged — the bare single-file path. This is the one shared
     * "was this file linked?" rule, used by both the compiler ({@code compileAlt})
     * and the receipt-graph report, so Run and the Receipts view can never
     * disagree about whether a file went through linking.
     */
    public static IrModule combineSingle(IrModule parsed) throws CompileException {
        boolean hasRequires = parsed.statements().stream()
                .anyMatch(s -> s instanceof IrStmt.Requires);
        return hasRequires
                ? combine(Map.of(parsed.name(), parsed), parsed.name())
                : parsed;
    }

    public static IrModule combine(Map<String, IrModule> modules, String entryModule)
            throws CompileException {
        if (!modules.containsKey(entryModule)) {
            throw new CompileException(
                    "Unknown entry module '" + entryModule + "'", Origin.NONE);
        }
        // Seed compiler-provided modules that some user module `requires` — and
        // only those, so a program that imports none is unaffected (no shadowing
        // or ambiguity from unused builtins).
        Map<String, IrModule> all = withRequiredBuiltins(modules);

        ModuleSymbolTable table = ModuleSymbolTable.build(all);
        // Coherence + import validation run on the pre-FQN-rewrite modules
        // (bare names match the table).
        CoherenceCheck.check(all, table);
        ModuleImportCheck.check(all, table);

        List<IrStmt> statements = new ArrayList<>();
        IrExpr main = IrExpr.lit(0);
        for (Map.Entry<String, IrModule> e : all.entrySet()) {
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

    /**
     * Returns {@code modules} augmented with any builtin module that a user
     * module {@code requires} (and that the user hasn't itself defined). When no
     * builtin is required, returns {@code modules} unchanged — so non-importing
     * programs link exactly as before.
     */
    private static Map<String, IrModule> withRequiredBuiltins(Map<String, IrModule> modules) {
        Set<String> required = new HashSet<>();
        for (IrModule m : modules.values()) {
            for (IrStmt s : m.statements()) {
                if (s instanceof IrStmt.Requires r) required.add(r.targetModule());
            }
        }
        Map<String, IrModule> builtins = BuiltinModules.all();
        boolean any = false;
        for (String name : builtins.keySet()) {
            if (required.contains(name) && !modules.containsKey(name)) {
                any = true;
                break;
            }
        }
        if (!any) return modules;

        Map<String, IrModule> all = new LinkedHashMap<>();
        for (Map.Entry<String, IrModule> e : builtins.entrySet()) {
            if (required.contains(e.getKey()) && !modules.containsKey(e.getKey())) {
                all.put(e.getKey(), e.getValue());
            }
        }
        all.putAll(modules);
        return all;
    }
}
