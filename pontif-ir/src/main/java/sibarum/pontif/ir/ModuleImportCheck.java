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
            // local imported name → provider FQN (source/remote) already seen
            // (for ambiguity).
            Map<String, String> seen = new LinkedHashMap<>();
            for (IrStmt stmt : e.getValue().statements()) {
                if (!(stmt instanceof IrStmt.Requires r)) continue;
                String source = r.targetModule();
                if (!modules.containsKey(source)) {
                    throw new CompileException(
                            "module '" + module + "' requires unknown module '" + source + "'",
                            r.origin());
                }
                for (IrStmt.RequireEntry entry : r.entries()) {
                    // Declaration + export checks run against the REMOTE name —
                    // the name as the source module knows it. A rename changes
                    // only what the importer calls it, never what is visible.
                    String remote = entry.remoteName();
                    boolean declared = table.functionOwners(remote).contains(source)
                            || table.typeOwners(remote).contains(source);
                    // RE-EXPORT: the source may export a name it imports
                    // rather than declares — valid iff the chase reaches a
                    // true declarer (each hop's own requires is validated by
                    // this same loop, so per-hop visibility is covered).
                    if (!declared && table.originOf(source, remote) == null) {
                        throw new CompileException(
                                "module '" + source + "' declares no name '" + remote
                                        + "' to import (required by '" + module + "')",
                                r.origin());
                    }
                    if (!table.isExported(source, remote)) {
                        throw new CompileException(
                                "module '" + source + "' does not export '" + remote
                                        + "' (no `exports` clause lists it; required by '"
                                        + module + "')",
                                r.origin());
                    }
                    // The ambiguity check stays keyed on the LOCAL name — two
                    // imports may bring the same remote name as long as their
                    // local names differ; that's exactly what rename is for. A
                    // local name bound twice to different providers (different
                    // module OR different remote name) is ambiguous. Providers
                    // compare by DECLARING ORIGIN: importing the same nominal
                    // through two re-export doors is not ambiguous — it's one
                    // name arriving twice.
                    ModuleSymbolTable.ImportedName origin = table.originOf(source, remote);
                    String provider = origin != null
                            ? ModuleSymbolTable.fqn(origin.sourceModule(), origin.remoteName())
                            : ModuleSymbolTable.fqn(source, remote);
                    String prior = seen.put(entry.localName(), provider);
                    if (prior != null && !prior.equals(provider)) {
                        throw new CompileException(
                                "module '" + module + "' imports '" + entry.localName()
                                        + "' as both '" + prior + "' and '" + provider
                                        + "' — ambiguous; rename one (`name -> alias`) or qualify the call",
                                r.origin());
                    }
                }
            }
        }
    }
}
