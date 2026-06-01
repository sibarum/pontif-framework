package sibarum.pontif.ir;

import java.util.Map;

/**
 * The coherence / orphan rule (Rust-style), enforced across a linked project:
 * a trait implementation {@code impl Trait for Type} may be declared only in
 * the module that owns {@code Trait} or the module that owns {@code Type} —
 * never a third module that owns neither. This closes the type-piracy hole that
 * Pontif's <b>global</b> {@code TraitRegistry} otherwise opens: without it, any
 * module could register {@code (someoneElsesTrait, someoneElsesType)} and
 * silently change trait dispatch everywhere.
 *
 * <p>Free-function overloads need no such rule here: per-module FQN dispatch
 * keys ({@code module/f}) already isolate each module's generic, so one module
 * cannot inject an overload into another's dispatch. (Cross-module overload
 * coherence becomes relevant only if shared generics are introduced later.)
 *
 * <p>Runs in the linker over the loaded (pre-FQN-rewrite) modules + the
 * {@link ModuleSymbolTable}; a single-module compile never reaches it, and a
 * lone module trivially owns everything it declares.
 */
public final class CoherenceCheck {

    private CoherenceCheck() {}

    public static void check(Map<String, IrModule> modules, ModuleSymbolTable table)
            throws CompileException {
        for (Map.Entry<String, IrModule> e : modules.entrySet()) {
            String module = e.getKey();
            for (IrStmt stmt : e.getValue().statements()) {
                if (stmt instanceof IrStmt.TraitImpl ti) {
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
            }
        }
    }
}
