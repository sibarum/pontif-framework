package sibarum.pontif.ir;

import sibarum.pontif.core.QualifiedName;

/**
 * The visibility view of ONE module during resolution: the question "can the code
 * I am resolving route to this declaration?" answered from that module's own
 * ownership plus its import-by-association imports — nothing global.
 *
 * <p>WAR(link-provenance), Slice 1 (docs/link-provenance.md): this is the seam that
 * replaces {@link MethodOperatorResolver}'s {@code currentModule} mutable field and
 * its direct {@link ModuleSymbolTable} reconstruction. Today resolution still runs
 * post-link over the flat module, so the resolver picks a scope per declaration (by
 * the decl's FQN module); Slice 2 moves resolution per-module before concatenation,
 * at which point a pass carries a single fixed scope. The logic here is unchanged
 * from the old {@code ownsOrImports} — this slice only relocates it behind one type.
 *
 * <p>The {@link #unrestricted()} scope gates nothing — the single-file / unlinked
 * case (no symbol table, every name local), exactly the old {@code table == null ||
 * currentModule.isEmpty()} short-circuit.
 */
public final class ModuleScope {

    /** Owning module FQN, or "" for the unrestricted (single-file/unlinked) scope. */
    private final String module;
    /** Cross-module index, or null for the unrestricted scope. */
    private final ModuleSymbolTable table;

    private ModuleScope(String module, ModuleSymbolTable table) {
        this.module = module;
        this.table = table;
    }

    /** The all-visible scope: nothing is gated (single-file / unlinked compile). */
    public static ModuleScope unrestricted() {
        return new ModuleScope("", null);
    }

    /**
     * The scope of {@code module} against {@code table}. Falls back to
     * {@link #unrestricted()} when there is no table or the module is unknown
     * (bare/unlinked decl), preserving the old short-circuit.
     */
    public static ModuleScope forModule(String module, ModuleSymbolTable table) {
        if (table == null || module == null || module.isEmpty()) return unrestricted();
        return new ModuleScope(module, table);
    }

    /** The owning module FQN ("" when unrestricted). */
    public String module() {
        return module;
    }

    /** Whether this scope actually gates visibility (false for the unrestricted scope). */
    public boolean restricts() {
        return table != null && !module.isEmpty();
    }

    /**
     * Whether this module owns or imports-by-association the (FQN) type. A bare
     * primitive type (no module qualifier) never grants visibility — you don't
     * import {@code Int}. Always true for the unrestricted scope's callers (which
     * short-circuit on {@link #restricts()} before asking).
     */
    public boolean ownsOrImports(String typeFqn) {
        if (!restricts() || typeFqn == null) return false;
        QualifiedName qn = QualifiedName.parse(typeFqn);
        String owner = qn.module();
        if (owner.isEmpty()) return false;            // primitive / bare
        if (owner.equals(module)) return true;        // the caller owns it
        ModuleSymbolTable.ImportedName imp = table.importedName(module, qn.member());
        return imp != null && imp.sourceModule().equals(owner);  // imported by association
    }
}
