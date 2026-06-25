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

    public static IrModule expand(IrModule module) throws CompileException {
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
            IrStmt.TraitImpl ti, Map<String, IrSort.Trait> traits) throws CompileException {
        IrSort.Trait trait = traits.get(ti.traitName());
        if (trait == null) trait = traits.get(baseName(ti.traitName()));
        if (trait == null) return ti;  // unknown trait — leave for SortChecker to report

        List<IrSort.Trait> chain = traitChain(trait, traits);
        Map<String, IrStmt.FunctionDecl> defaults = flatten(chain, IrSort.Trait::methodDefaults);
        Map<String, IrExpr.Lambda> shells = flatten(chain, IrSort.Trait::returnShells);
        Map<String, Map<Integer, IrExpr.Lambda>> argShells = flatten(chain, IrSort.Trait::argShells);
        if (defaults.isEmpty() && shells.isEmpty() && argShells.isEmpty()) return ti;

        // Short names the impl already provides (overrides) — never synthesize those.
        String prefix = ti.typeName() + ".";
        Set<String> provided = new HashSet<>();
        for (IrStmt.FunctionDecl m : ti.methods()) provided.add(shortName(m, prefix));

        Map<String, IrSort> subst = buildSubst(ti, trait);
        IrSort selfSort = selfSort(ti);

        // 1. Synthesize the defaults the impl omits.
        List<IrStmt.FunctionDecl> methods = new ArrayList<>(ti.methods());
        for (Map.Entry<String, IrStmt.FunctionDecl> e : defaults.entrySet()) {
            if (provided.contains(e.getKey())) continue;  // overridden by the impl
            methods.add(specialize(e.getKey(), e.getValue(), ti, selfSort, subst));
        }

        // 2. Wrap each method's kernel with the trait's shells — behaviour the impl can't
        //    change, applied to impl-provided AND synthesized-default kernels alike
        //    (docs/sort-transforms.md). Argument shells adapt the INPUTS first (registered
        //    param = domain A, kernel sees codomain B); the return shell then shapes the
        //    OUTPUT (kernel produces C, callers see terminus D) — args inner, return outer.
        if (!shells.isEmpty() || !argShells.isEmpty()) {
            List<IrStmt.FunctionDecl> wrapped = new ArrayList<>(methods.size());
            for (IrStmt.FunctionDecl m : methods) {
                String sn = shortName(m, prefix);
                IrStmt.FunctionDecl k = m;
                Map<Integer, IrExpr.Lambda> as = argShells.get(sn);
                if (as != null) k = applyArgShells(ti, k, as, subst);
                IrExpr.Lambda rs = shells.get(sn);
                if (rs != null) k = applyReturnShell(ti, k, rs, subst);
                wrapped.add(k);
            }
            methods = wrapped;
        }

        if (methods.equals(ti.methods())) return ti;  // nothing synthesized or wrapped
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

    /**
     * Wrap a kernel with its trait's return shell `[C -> D]`: the method's result
     * becomes {@code Apply(shell, [kernel-result])} and its declared return becomes the
     * terminus {@code D}. The kernel's own declared return must be the shell's domain
     * {@code C} (option (a) — the obligation is checked here, where contract and impl
     * are both in hand, before the body is rewritten and the kernel sort is lost).
     */
    private static IrStmt.FunctionDecl applyReturnShell(
            IrStmt.TraitImpl ti, IrStmt.FunctionDecl kernel,
            IrExpr.Lambda shell, Map<String, IrSort> subst) throws CompileException {
        IrSort domainC = SortChecker.substituteTypeVars(shell.params().get(0).sort(), subst);
        String want = baseName(domainC);
        String got = baseName(kernel.returnSort());
        if (want != null && got != null && !want.equals(got)) {
            throw new CompileException(
                    "Trait impl '" + ti.typeName() + " : " + ti.traitName() + "' method '"
                            + kernel.name() + "' returns " + got + " but the trait's return "
                            + "shell expects the kernel to produce " + want
                            + " (the shell then transforms it to the contract return).",
                    kernel.origin());
        }
        IrSort terminusD = SortChecker.substituteTypeVars(shell.returnSort(), subst);
        IrExpr wrappedBody = new IrExpr.Apply(shell, List.of(kernel.body()), kernel.origin());
        return new IrStmt.FunctionDecl(
                kernel.name(), kernel.params(), terminusD, wrappedBody, kernel.origin());
    }

    /**
     * Wrap a kernel's INPUTS with the trait's argument shells `[A -> B]` (keyed by user-param
     * position). For each shelled param the registered param is rewritten to the domain
     * {@code A} (what callers/dispatch see) and the body is prefixed with
     * {@code let p = shell(p)} (the IR form of {@code wrapParamConversions}), so the kernel
     * sees the codomain {@code B}. The kernel's declared param must be {@code B} (option (a),
     * mirroring the return-shell's {@code C} check). User-param position {@code i} is kernel
     * param {@code i+1} (after the injected {@code this}).
     */
    private static IrStmt.FunctionDecl applyArgShells(
            IrStmt.TraitImpl ti, IrStmt.FunctionDecl kernel,
            Map<Integer, IrExpr.Lambda> shellsByPos, Map<String, IrSort> subst) throws CompileException {
        List<IrParam> params = new ArrayList<>(kernel.params());
        IrExpr body = kernel.body();
        for (Map.Entry<Integer, IrExpr.Lambda> e : shellsByPos.entrySet()) {
            int kernelIdx = e.getKey() + 1;  // +1 for the injected `this`
            if (kernelIdx >= params.size()) continue;  // arity mismatch — SortChecker reports
            IrParam kp = params.get(kernelIdx);
            IrExpr.Lambda shell = e.getValue();
            IrSort domainA = SortChecker.substituteTypeVars(shell.params().get(0).sort(), subst);
            IrSort codomainB = SortChecker.substituteTypeVars(shell.returnSort(), subst);
            String want = baseName(codomainB);
            String got = baseName(kp.sort());
            if (want != null && got != null && !want.equals(got)) {
                throw new CompileException(
                        "Trait impl '" + ti.typeName() + " : " + ti.traitName() + "' method '"
                                + kernel.name() + "' parameter '" + kp.name() + "' is " + got
                                + " but the trait's argument shell delivers " + want
                                + " to the kernel (the caller passes " + baseName(domainA)
                                + "; the shell converts it).",
                        kernel.origin());
            }
            // Registered param takes the domain A (for dispatch); the body rebinds it to B.
            params.set(kernelIdx, new IrParam(kp.name(), domainA));
            body = new IrExpr.LetIn(kp.name(), codomainB,
                    new IrExpr.Apply(shell, List.of(new IrExpr.Var(kp.name(), kernel.origin())),
                            kernel.origin()),
                    body, kernel.origin());
        }
        return new IrStmt.FunctionDecl(
                kernel.name(), params, kernel.returnSort(), body, kernel.origin());
    }

    private static String shortName(IrStmt.FunctionDecl m, String prefix) {
        return m.name().startsWith(prefix) ? m.name().substring(prefix.length()) : m.name();
    }

    /** The base/head type name of a sort, or null when it has no nominal head. */
    private static String baseName(IrSort sort) {
        return switch (sort) {
            case IrSort.Named n -> n.name();
            case IrSort.Refined r -> r.name();
            case IrSort.Structural s -> s.name();
            default -> null;
        };
    }

    /** The trait + every transitive base, root-first (a derived member overrides a base one). */
    private static List<IrSort.Trait> traitChain(
            IrSort.Trait trait, Map<String, IrSort.Trait> traits) {
        List<IrSort.Trait> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        IrSort.Trait cur = trait;
        while (cur != null && seen.add(cur.name())) {
            chain.add(cur);
            String base = cur.baseTrait();
            cur = base == null ? null : traits.getOrDefault(base, traits.get(baseName(base)));
        }
        return chain;
    }

    /** Merge a per-trait map across the chain, root-first so a derived entry wins. */
    private static <V> Map<String, V> flatten(
            List<IrSort.Trait> chain, java.util.function.Function<IrSort.Trait, Map<String, V>> sel) {
        Map<String, V> out = new LinkedHashMap<>();
        for (int i = chain.size() - 1; i >= 0; i--) out.putAll(sel.apply(chain.get(i)));
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
