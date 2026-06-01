package sibarum.pontif.ir;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Validates the {@code requires} declarations of a project against the
 * {@link ModuleSymbolTable}, turning what used to resolve silently (or fail
 * later as a generic "unknown sort"/"unknown function") into precise,
 * origin-anchored import diagnostics.
 *
 * <p>Runs at link time on the pre-FQN-rewrite modules (bare names match the
 * table), alongside {@link CoherenceCheck}. Three checks:
 * <ol>
 *   <li><b>requires-unknown-module</b> — {@code requires S.{…}} where {@code S}
 *       is not a module in the project.</li>
 *   <li><b>unexported-name</b> — importing a name the source module doesn't
 *       export. Visibility is <em>private-by-default</em> (Rust-style): a name
 *       is importable only if the source module's {@code exports} clause lists
 *       it. A name not declared at all in the source is reported distinctly from
 *       one that exists but is private.</li>
 *   <li><b>ambiguous-import</b> — the same local name imported from two
 *       different source modules in one module, so a bare reference to it has no
 *       single provider.</li>
 * </ol>
 *
 * <p>Like {@link CoherenceCheck}, invoked <b>only by the module linker</b>; a
 * single-file compile has no {@code requires} to check.
 */
public final class ModuleImportCheck {

    private ModuleImportCheck() {}

    public static void check(Map<String, IrModule> modules, ModuleSymbolTable table)
            throws CompileException {
        for (Map.Entry<String, IrModule> e : modules.entrySet()) {
            String module = e.getKey();
            // local imported name → source module already seen (for ambiguity).
            Map<String, String> seen = new LinkedHashMap<>();
            for (IrStmt stmt : e.getValue().statements()) {
                if (!(stmt instanceof IrStmt.Requires r)) continue;
                String source = r.targetModule();
                if (!modules.containsKey(source)) {
                    throw new CompileException(
                            "module '" + module + "' requires unknown module '" + source + "'",
                            r.origin());
                }
                for (String name : r.names()) {
                    boolean declared = table.functionOwners(name).contains(source)
                            || table.typeOwners(name).contains(source);
                    if (!declared) {
                        throw new CompileException(
                                "module '" + source + "' declares no name '" + name
                                        + "' to import (required by '" + module + "')",
                                r.origin());
                    }
                    if (!table.isExported(source, name)) {
                        throw new CompileException(
                                "module '" + source + "' does not export '" + name
                                        + "' (no `exports` clause lists it; required by '"
                                        + module + "')",
                                r.origin());
                    }
                    String prior = seen.put(name, source);
                    if (prior != null && !prior.equals(source)) {
                        throw new CompileException(
                                "module '" + module + "' imports '" + name
                                        + "' from both '" + prior + "' and '" + source
                                        + "' — ambiguous; qualify the call or drop one import",
                                r.origin());
                    }
                }
            }
        }
    }
}
