package sibarum.pontif.ir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Expands DEFAULT trait methods into per-impl {@link IrStmt.FunctionDecl}s.
 *
 * <p>A trait may give a method a body directly in its body
 * ({@code quack():Int -> this.x}); the parser stores that body in
 * {@link IrSort.Trait#methodDefaults()}. For each {@code assign trait Type:Trait}
 * block that <b>omits</b> a defaulted contract method, this pass clones the
 * default body into a {@code Type.method(this:Type, …)} FunctionDecl and appends
 * it to the impl's methods. An impl that <b>provides</b> the method overrides the
 * default (no synthesis for it).
 *
 * <p>Runs as the FIRST {@link IrCompiler} pass — before AliasResolver,
 * MethodOperatorResolver and SortChecker — so the synthesized methods flow
 * through every downstream pass exactly like hand-written impl methods: their
 * {@code this.sibling()} calls get resolved, their sorts checked, and they
 * register in the dispatch table under {@code Type.method}. Because the impl is
 * complete after expansion, SortChecker's missing-method check needs no change.
 *
 * <p>Slice-1 limitation: a default body's expression is cloned verbatim. Type
 * variables in the SIGNATURE (associated types, the {@code this.type} self-type,
 * the trait's {@code [type E]} parameters) are substituted per impl, but a body
 * that references an imported type by name in a linked program is not re-run
 * through NameResolver (the body lived inside the sort, not the statement tree).
 * Default bodies that use {@code this}, fields, sibling methods, and primitives —
 * the common case — are fully supported.
 */
public final class TraitDefaultExpansion {

    private TraitDefaultExpansion() {}

    public static IrModule expand(IrModule module) {
        // Index trait declarations by name — and by base name (after the last '/')
        // so a linked impl whose trait reference is FQN still matches a trait whose
        // declaration carries a different qualification.
        Map<String, IrSort.Trait> traits = new HashMap<>();
        for (IrStmt s : module.statements()) {
            if (s instanceof IrStmt.TypeAlias ta && ta.sort() instanceof IrSort.Trait t) {
                traits.put(t.name(), t);
                traits.putIfAbsent(baseName(t.name()), t);
            }
        }
        if (traits.isEmpty()) return module;

        boolean changed = false;
        List<IrStmt> out = new ArrayList<>(module.statements().size());
        for (IrStmt s : module.statements()) {
            if (s instanceof IrStmt.TraitImpl ti) {
                IrStmt.TraitImpl expanded = expandImpl(ti, traits);
                out.add(expanded);
                if (expanded != ti) changed = true;
            } else {
                out.add(s);
            }
        }
        return changed ? new IrModule(module.name(), out, module.main()) : module;
    }

    private static IrStmt.TraitImpl expandImpl(
            IrStmt.TraitImpl ti, Map<String, IrSort.Trait> traits) {
        IrSort.Trait trait = traits.get(ti.traitName());
        if (trait == null) trait = traits.get(baseName(ti.traitName()));
        if (trait == null) return ti;  // unknown trait — leave for SortChecker to report

        Map<String, IrStmt.FunctionDecl> defaults = flattenDefaults(trait, traits);
        if (defaults.isEmpty()) return ti;

        // Short names the impl already provides (overrides) — never synthesize those.
        Set<String> provided = new HashSet<>();
        String prefix = ti.typeName() + ".";
        for (IrStmt.FunctionDecl m : ti.methods()) {
            provided.add(m.name().startsWith(prefix)
                    ? m.name().substring(prefix.length()) : m.name());
        }

        Map<String, IrSort> subst = buildSubst(ti, trait);
        IrSort selfSort = selfSort(ti);

        List<IrStmt.FunctionDecl> synthesized = new ArrayList<>();
        for (Map.Entry<String, IrStmt.FunctionDecl> e : defaults.entrySet()) {
            if (provided.contains(e.getKey())) continue;  // overridden by the impl
            synthesized.add(specialize(e.getKey(), e.getValue(), ti, selfSort, subst));
        }
        if (synthesized.isEmpty()) return ti;

        List<IrStmt.FunctionDecl> methods = new ArrayList<>(ti.methods());
        methods.addAll(synthesized);
        return new IrStmt.TraitImpl(
                ti.typeName(), ti.traitName(), methods, ti.attributeProducers(),
                ti.typeBindings(), ti.typeParams(), ti.traitTypeArgs(), ti.origin());
    }

    /** A defaulted method specialized for one impl: {@code Type.m(this:Type, …):RetSubst -> body}. */
    private static IrStmt.FunctionDecl specialize(
            String shortName, IrStmt.FunctionDecl def, IrStmt.TraitImpl ti,
            IrSort selfSort, Map<String, IrSort> subst) {
        List<IrParam> params = new ArrayList<>(def.params().size());
        boolean first = true;
        for (IrParam p : def.params()) {
            if (first) {  // the injected `this` — re-type to the concrete subject
                params.add(new IrParam(p.name(), selfSort));
                first = false;
            } else {
                params.add(new IrParam(p.name(), SortChecker.substituteTypeVars(p.sort(), subst)));
            }
        }
        IrSort returnSort = SortChecker.substituteTypeVars(def.returnSort(), subst);
        return new IrStmt.FunctionDecl(
                ti.typeName() + "." + shortName, params, returnSort, def.body(), def.origin());
    }

    /** Trait's own defaults, plus every base trait's, root-first (a derived default wins). */
    private static Map<String, IrStmt.FunctionDecl> flattenDefaults(
            IrSort.Trait trait, Map<String, IrSort.Trait> traits) {
        if (trait.baseTrait() == null) return trait.methodDefaults();
        List<IrSort.Trait> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        IrSort.Trait cur = trait;
        while (cur != null && seen.add(cur.name())) {
            chain.add(cur);
            String base = cur.baseTrait();
            cur = base == null ? null : traits.getOrDefault(base, traits.get(baseName(base)));
        }
        Map<String, IrStmt.FunctionDecl> out = new LinkedHashMap<>();
        for (int i = chain.size() - 1; i >= 0; i--) out.putAll(chain.get(i).methodDefaults());
        return out;
    }

    /**
     * The substitution every impl applies to a defaulted method's signature: the
     * associated-type bindings ({@code type X = […]}), the trait's {@code [type E]}
     * parameters (↦ the impl's applied arguments), and the implicit self-type
     * {@code this.type} ↦ the concrete subject. Mirrors {@code SortChecker.validateTraitImpl}.
     */
    private static Map<String, IrSort> buildSubst(IrStmt.TraitImpl ti, IrSort.Trait trait) {
        Map<String, IrSort> subst = new HashMap<>(ti.typeBindings());
        subst.put(IrSort.SELF_TYPE, IrSort.named(ti.typeName()));
        List<String> params = new ArrayList<>(trait.typeParams().keySet());
        for (int i = 0; i < params.size() && i < ti.traitTypeArgs().size(); i++) {
            subst.put(params.get(i), ti.traitTypeArgs().get(i));
        }
        return subst;
    }

    /** The {@code this} sort — parametric ({@code Element[T]}) when the impl binds {@code [type T]}. */
    private static IrSort selfSort(IrStmt.TraitImpl ti) {
        if (ti.typeParams().isEmpty()) return IrSort.named(ti.typeName());
        List<IrSort> selfArgs = new ArrayList<>(ti.typeParams().size());
        for (String tp : ti.typeParams().keySet()) selfArgs.add(IrSort.named(tp));
        return new IrSort.Named(ti.typeName(), selfArgs, ti.origin());
    }

    private static String baseName(String name) {
        int slash = name.lastIndexOf('/');
        return slash >= 0 ? name.substring(slash + 1) : name;
    }
}
