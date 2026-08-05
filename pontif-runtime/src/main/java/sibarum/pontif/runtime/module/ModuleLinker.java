package sibarum.pontif.runtime.module;

import sibarum.pontif.core.Origin;
import sibarum.pontif.ir.CoherenceCheck;
import sibarum.pontif.ir.CompileException;
import sibarum.pontif.ir.DestructureResolver;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrStmt;
import sibarum.pontif.ir.MethodOperatorResolver;
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
     * Links a single parsed module <b>iff</b> it declares any {@code requires}
     * (so builtin modules are injected and names FQN-resolved); otherwise
     * returns it unchanged — the bare single-file path. This is the one shared
     * "was this file linked?" rule, used by both the compiler ({@code compileAlt})
     * and the receipt-graph report, so Run and the Receipts view can never
     * disagree about whether a file went through linking.
     */
    public static IrModule combineSingle(IrModule parsed) throws CompileException {
        return needsLinking(parsed)
                ? combine(Map.of(parsed.name(), parsed), parsed.name())
                : parsed;
    }

    /**
     * Whether a module must go through the link pipeline rather than the bare single-file
     * pass-through. This is the <b>single source of truth</b> for that decision — every gate
     * ({@link #combineSingle} here, {@code ModuleResolver.resolveAndCombine}) calls it, so they
     * can never disagree about whether a program is linked (the drift that let a {@code spawn}-only
     * program skip seating). Backed by {@link #triggersLink}, an exhaustive switch over the sealed
     * {@link IrStmt}: adding a new statement kind will not compile until it is classified, so a
     * future construct that needs linking can never silently be forgotten here.
     */
    public static boolean needsLinking(IrModule module) {
        return module.statements().stream().anyMatch(ModuleLinker::triggersLink);
    }

    /**
     * Does this statement force the link pipeline? Exhaustive by design (no {@code default}) — the
     * compiler forces every {@link IrStmt} kind to be classified. Two kinds trigger linking today:
     * {@code requires} (pulls in another module) and {@code spawn} (seating injects the conductor's
     * reactions and validates it exists — docs/orchestration.md, §Seating). A bare
     * {@link IrStmt.ConductorDecl} does NOT: a conductor is inert until an entry-point {@code spawn}
     * seats it, so merely declaring one keeps the bare path.
     */
    private static boolean triggersLink(IrStmt s) {
        return switch (s) {
            case IrStmt.Requires r -> true;
            case IrStmt.Spawn sp -> true;
            case IrStmt.FunctionDecl f -> false;
            case IrStmt.TypeAlias t -> false;
            case IrStmt.TraitImpl ti -> false;
            case IrStmt.Coercion c -> false;
            case IrStmt.Proof p -> false;
            case IrStmt.ReturnProof rp -> false;
            case IrStmt.Exports e -> false;
            case IrStmt.ConductorDecl cd -> false;
            case IrStmt.NoOp n -> false;
        };
    }

    /**
     * Links parsed modules into one combined, FQN-keyed, fully-resolved module the
     * ordinary compiler can consume. The symbol table built here is consumed
     * <em>within</em> the link — coherence/import/coercion checks and per-module
     * operator/method resolution (the cross-module visibility gate) — and is not
     * exposed: nothing downstream re-threads it (WAR(link-provenance)).
     *
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
        // Seed compiler-provided modules that some user module `requires` — and
        // only those, so a program that imports none is unaffected (no shadowing
        // or ambiguity from unused builtins).
        Map<String, IrModule> all = withRequiredBuiltins(modules);
        // Seating (docs/orchestration.md, §Seating): a `spawn C` in the ENTRY module activates
        // conductor C — its body-bearing handler reactions are injected into the entry module's
        // statements here, BEFORE resolution, so they flow through NameResolver like any other
        // reaction. A spawn in a required module is inert (this only reads the entry module's
        // spawns) — libraries define conductors, the entry point activates them.
        all = seatConductors(all, entryModule);

        ModuleSymbolTable table = ModuleSymbolTable.build(all);
        // Coherence + import validation run on the pre-FQN-rewrite modules
        // (bare names match the table).
        CoherenceCheck.check(all, table);
        ModuleImportCheck.check(all, table);
        // Coercion orphan rule: a `cast` must be declared in a module owning its
        // source or target type (bare names here match the table). prim↔prim +
        // coherence are checked per-module by CoercionCheck.validate (SortChecker phase).
        sibarum.pontif.ir.CoercionCheck.validateOrphans(all, table);

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
        IrModule withStructs = StructLiteralRewriter.rewrite(
                new IrModule(entryModule, statements, main));
        // Positional/match/nested/param destructure patterns over IMPORTED structs
        // were left deferred by the parser (field order/sorts unknown pre-link).
        // Now the combined struct registry is available — resolve every deferred
        // pattern's slots to declared field names, enforce the arity-total rule,
        // and generate the field bindings (cluster 2). Runs after StructLiteralRewriter
        // so its struct-literal Records are in place first.
        IrModule shaped = DestructureResolver.rewrite(withStructs);
        // Per-module operator/method resolution (WAR(link-provenance) Slice 2,
        // Option A): the link is where the symbol table is consumed. Each decl is
        // gated in its OWN module's scope here — the sole visibility gate — so the
        // combined module is emitted already resolved and nothing downstream needs
        // to re-thread the table. Runs last, after struct literals are Records and
        // destructures are resolved, so operand sorts are known for routing.
        return MethodOperatorResolver.resolvePerModule(shaped, table);
    }

    /**
     * Seating (docs/orchestration.md, §Seating). Activates the conductors named by {@code spawn}
     * statements in the ENTRY module: their concrete handler reactions (the {@code #action#}-keyed
     * {@link IrStmt.FunctionDecl}s the conductor carries) are appended to the entry module's
     * statements, so downstream resolution + compilation register them as live reactions. A
     * conductor may be <em>declared</em> in any module (a library), but only an entry-module
     * {@code spawn} brings it to life — so {@code requires}-ing a library never stands up its
     * conductors. Returns {@code all} unchanged when the entry module seats nothing.
     *
     * @throws CompileException if a {@code spawn} names a conductor no module declares
     */
    private static Map<String, IrModule> seatConductors(
            Map<String, IrModule> all, String entryModule) throws CompileException {
        IrModule entry = all.get(entryModule);
        boolean seatsAnything = entry.statements().stream().anyMatch(s -> s instanceof IrStmt.Spawn);
        if (!seatsAnything) return all;

        // Registry of every declared conductor across all modules (libraries may define them).
        Map<String, IrStmt.ConductorDecl> conductors = new LinkedHashMap<>();
        for (IrModule m : all.values()) {
            for (IrStmt s : m.statements()) {
                if (s instanceof IrStmt.ConductorDecl cd) conductors.put(cd.name(), cd);
            }
        }
        List<IrStmt> injected = new ArrayList<>();
        for (IrStmt s : entry.statements()) {
            if (s instanceof IrStmt.Spawn sp) {
                IrStmt.ConductorDecl cd = conductors.get(sp.conductorName());
                if (cd == null) {
                    throw new CompileException(
                            "`spawn " + sp.conductorName() + "`: no conductor named '"
                                    + sp.conductorName() + "' is declared", sp.origin());
                }
                injected.addAll(cd.reactions());
            }
        }
        if (injected.isEmpty()) return all;   // seated conductors, but none had a concrete handler
        List<IrStmt> newStmts = new ArrayList<>(entry.statements());
        newStmts.addAll(injected);
        Map<String, IrModule> out = new LinkedHashMap<>(all);
        out.put(entryModule, new IrModule(entry.name(), newStmts, entry.main()));
        return out;
    }

    /**
     * Returns {@code modules} augmented with any builtin module that a user
     * module {@code requires} (and that the user hasn't itself defined). When no
     * builtin is required, returns {@code modules} unchanged — so non-importing
     * programs link exactly as before.
     */
    private static Map<String, IrModule> withRequiredBuiltins(Map<String, IrModule> modules) {
        Map<String, IrModule> builtins = BuiltinModules.all();
        // TRANSITIVE collection: a required builtin's own requires pull in
        // further builtins (std.proof re-exports std.common's Leaf, so
        // requiring std.proof must inject std.common too).
        Set<String> needed = new HashSet<>();
        java.util.ArrayDeque<IrModule> scan = new java.util.ArrayDeque<>(modules.values());
        while (!scan.isEmpty()) {
            IrModule m = scan.pop();
            for (IrStmt s : m.statements()) {
                if (s instanceof IrStmt.Requires r
                        && builtins.containsKey(r.targetModule())
                        && !modules.containsKey(r.targetModule())
                        && needed.add(r.targetModule())) {
                    scan.push(builtins.get(r.targetModule()));
                }
            }
        }
        if (needed.isEmpty()) return modules;

        Map<String, IrModule> all = new LinkedHashMap<>();
        for (Map.Entry<String, IrModule> e : builtins.entrySet()) {
            if (needed.contains(e.getKey())) {
                all.put(e.getKey(), e.getValue());
            }
        }
        all.putAll(modules);
        return all;
    }
}
