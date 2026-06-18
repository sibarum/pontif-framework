package sibarum.pontif.ir;

import java.util.Map;
import java.util.Set;

/**
 * The coherence / orphan rule (Rust-style), enforced across a linked project. Two
 * applications of one principle — <b>you may extend a shared, dispatched name only
 * on types you own</b> — closing the type-piracy holes that Pontif's cross-module
 * sharing would otherwise open:
 *
 * <ol>
 *   <li><b>Trait impls.</b> {@code impl Trait for Type} may be declared only in the
 *       module owning {@code Trait} or {@code Type} — never a third module owning
 *       neither (which could silently change trait dispatch everywhere via the global
 *       {@code TraitRegistry}).
 *   <li><b>Operator overloads (mechanism 1).</b> An operator overload
 *       ({@code +}, {@code *}, …) may be declared only in a module owning at least one
 *       of its <em>operand</em> types (its "home set"). A user module cannot redefine
 *       {@code +(Int, Int)} (owns neither operand — the prelude owns {@code Int}); it
 *       <em>can</em> define {@code +(Int, Custom)} or {@code *(k:Int, v:Vec)} in
 *       {@code Custom}/{@code Vec}'s own module (owns one operand). This is what keeps
 *       operators module-scoped + coherent under import-by-association
 *       (docs/cross-module-dispatch.md): an overload surfaces by importing a type in
 *       its signature, and the orphan rule guarantees that type's module is where it
 *       legitimately lives. See `project_import_by_association` (the namespace-safety
 *       invariant).
 * </ol>
 *
 * <p><b>Scope (Phase 1):</b> operators only — a closed, shared name set where piracy is
 * unambiguous. The same rule generalizes to free functions and methods (the
 * orphan-method fork, {@code inference__20}), but those need the "owns the name OR owns
 * an operand type" refinement to avoid rejecting an ordinary primitive-param function
 * (`double(x:Int)`); deferred to later war phases.
 *
 * <p>Runs in the linker over the loaded (pre-FQN-rewrite) modules + the
 * {@link ModuleSymbolTable}; a single-module compile never reaches it, and a
 * lone module trivially owns everything it declares.
 */
public final class CoherenceCheck {

    private CoherenceCheck() {}

    /** The overloadable operator symbols — a mechanism-1 overload of one of these is governed. */
    private static final Set<String> OPERATORS = Set.of(
            "+", "-", "*", "/", "%", "^", "<", "<=", ">", ">=", "==", "!=");

    public static void check(Map<String, IrModule> modules, ModuleSymbolTable table)
            throws CompileException {
        for (Map.Entry<String, IrModule> e : modules.entrySet()) {
            String module = e.getKey();
            for (IrStmt stmt : e.getValue().statements()) {
                if (stmt instanceof IrStmt.TraitImpl ti) {
                    checkTraitImpl(ti, module, table);
                } else if (stmt instanceof IrStmt.FunctionDecl fd) {
                    checkOperatorOverload(fd, module, table);
                }
            }
        }
    }

    private static void checkTraitImpl(IrStmt.TraitImpl ti, String module, ModuleSymbolTable table)
            throws CompileException {
        boolean ownsTrait = table.typeOwners(ti.traitName()).contains(module);
        boolean ownsType = table.typeOwners(ti.typeName()).contains(module);
        if (!ownsTrait && !ownsType) {
            throw new CompileException(
                    "Coherence violation: 'impl " + ti.traitName() + " for "
                            + ti.typeName() + "' in module '" + module
                            + "' — an impl may only be declared in the module owning the"
                            + " trait ('" + ti.traitName() + "') or the type ('"
                            + ti.typeName() + "'). This is the orphan rule that prevents"
                            + " type piracy across modules.",
                    ti.origin());
        }
    }

    /**
     * Orphan rule for an operator overload: the declaring module must own at least one
     * operand type. Skips non-operators (free functions are a later phase) and nullary
     * declarations (no operand to anchor — accessed by explicit name import, not by
     * association). Operand types owned by no module (the prelude's primitives) never
     * put the declaring module in the home set — which is exactly why a user
     * {@code +(Int, Int)} is rejected.
     */
    private static void checkOperatorOverload(
            IrStmt.FunctionDecl fd, String module, ModuleSymbolTable table)
            throws CompileException {
        if (!OPERATORS.contains(fd.name()) || fd.params().isEmpty()) return;
        boolean ownsAnOperand = false;
        for (IrParam p : fd.params()) {
            String base = baseTypeName(p.sort());
            if (base != null && table.typeOwners(base).contains(module)) {
                ownsAnOperand = true;
                break;
            }
        }
        if (!ownsAnOperand) {
            throw new CompileException(
                    "Coherence violation: operator '" + fd.name() + "' overloaded in module '"
                            + module + "' on operand types it does not own — an operator overload"
                            + " may be declared only in a module owning at least one of its operand"
                            + " types (the orphan rule, preventing type piracy). Define it in the"
                            + " module that owns one of the operands.",
                    fd.origin());
        }
    }

    /** The nominal base type name of a sort (struct/trait/alias name), or null for none. */
    private static String baseTypeName(IrSort sort) {
        return switch (sort) {
            case IrSort.Named n -> n.name();
            case IrSort.Refined r -> r.name();
            case IrSort.Structural s -> s.name();
            default -> null;
        };
    }
}
