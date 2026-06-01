package sibarum.pontif.ir;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Cross-module ownership index, built from the loaded modules of a project.
 * Answers the questions the {@link NameResolver} (which provider does a name
 * resolve to?) and the coherence check (which module owns this name/type?) ask.
 *
 * <p>Records, per module: its declared function/method/operator keys, its
 * declared type names (structs, traits, aliases), its {@code requires} imports
 * (local name → source module), and its {@code exports} set. A name declared in
 * more than one module is tracked as multiple owners so ambiguity can be a hard
 * error rather than a silent pick.
 *
 * <p>FQN key convention: {@code module + "/" + localKey}. The local key keeps
 * its existing dot-grammar ({@code Point.magnitude}, {@code +}), so {@code "/"}
 * is the sole module↔local boundary and existing {@code .}-splitting routines
 * keep working on the local part.
 */
public final class ModuleSymbolTable {

    /** function/method/operator local key → modules declaring it. */
    private final Map<String, Set<String>> functionOwners;
    /** type name (struct / trait / alias) → modules declaring it. */
    private final Map<String, Set<String>> typeOwners;
    /** module → (imported local name → source module), from {@code requires}. */
    private final Map<String, Map<String, String>> imports;
    /** module → exported local names, from {@code exports @.{…}}. */
    private final Map<String, Set<String>> exports;

    private ModuleSymbolTable(
            Map<String, Set<String>> functionOwners,
            Map<String, Set<String>> typeOwners,
            Map<String, Map<String, String>> imports,
            Map<String, Set<String>> exports) {
        this.functionOwners = functionOwners;
        this.typeOwners = typeOwners;
        this.imports = imports;
        this.exports = exports;
    }

    /** FQN key for a local key in a module. */
    public static String fqn(String module, String localKey) {
        return module + "/" + localKey;
    }

    /**
     * Builds the table from {@code moduleName → IrModule} (post-parse,
     * pre-resolve). Reads function/type declarations and requires/exports.
     */
    public static ModuleSymbolTable build(Map<String, IrModule> modules) {
        Map<String, Set<String>> fns = new LinkedHashMap<>();
        Map<String, Set<String>> types = new LinkedHashMap<>();
        Map<String, Map<String, String>> imp = new LinkedHashMap<>();
        Map<String, Set<String>> exp = new LinkedHashMap<>();

        for (Map.Entry<String, IrModule> e : modules.entrySet()) {
            String module = e.getKey();
            imp.put(module, new LinkedHashMap<>());
            exp.put(module, new LinkedHashSet<>());
            for (IrStmt stmt : e.getValue().statements()) {
                switch (stmt) {
                    case IrStmt.FunctionDecl fd ->
                            fns.computeIfAbsent(fd.name(), k -> new LinkedHashSet<>()).add(module);
                    case IrStmt.TraitImpl ti -> {
                        for (IrStmt.FunctionDecl m : ti.methods()) {
                            fns.computeIfAbsent(m.name(), k -> new LinkedHashSet<>()).add(module);
                        }
                    }
                    case IrStmt.TypeAlias ta -> {
                        types.computeIfAbsent(ta.name(), k -> new LinkedHashSet<>()).add(module);
                        if (ta.sort() instanceof IrSort.Structural s) {
                            types.computeIfAbsent(s.name(), k -> new LinkedHashSet<>()).add(module);
                        } else if (ta.sort() instanceof IrSort.Trait t) {
                            types.computeIfAbsent(t.name(), k -> new LinkedHashSet<>()).add(module);
                        }
                    }
                    case IrStmt.Requires r -> {
                        for (String name : r.names()) {
                            imp.get(module).put(name, r.targetModule());
                        }
                    }
                    case IrStmt.Exports ex -> {
                        if (ex.self()) exp.get(module).addAll(ex.names());
                    }
                    case IrStmt.Proof p -> { }
                    case IrStmt.NoOp n -> { }
                }
            }
        }
        return new ModuleSymbolTable(fns, types, imp, exp);
    }

    /** Modules declaring a function/method/operator local key (may be empty). */
    public Set<String> functionOwners(String localKey) {
        return functionOwners.getOrDefault(localKey, Set.of());
    }

    /** Modules declaring a type name (may be empty). */
    public Set<String> typeOwners(String typeName) {
        return typeOwners.getOrDefault(typeName, Set.of());
    }

    /** The single module owning {@code typeName}, or {@code null} if none/ambiguous. */
    public String soleTypeOwner(String typeName) {
        Set<String> owners = typeOwners.getOrDefault(typeName, Set.of());
        return owners.size() == 1 ? owners.iterator().next() : null;
    }

    /** Does {@code module} declare {@code localKey} as a function/method/operator? */
    public boolean moduleDeclaresFunction(String module, String localKey) {
        return functionOwners.getOrDefault(localKey, Set.of()).contains(module);
    }

    /** Source module a {@code requires}-imported name in {@code module} came from, or null. */
    public String importSource(String module, String localKey) {
        return imports.getOrDefault(module, Map.of()).get(localKey);
    }

    /** Modules {@code module} pulls in via {@code requires} (the import sources). */
    public Set<String> requiredModules(String module) {
        return Set.copyOf(imports.getOrDefault(module, Map.of()).values());
    }

    /** Is {@code localKey} listed in {@code module}'s {@code exports}? */
    public boolean isExported(String module, String localKey) {
        return exports.getOrDefault(module, Set.of()).contains(localKey);
    }
}
