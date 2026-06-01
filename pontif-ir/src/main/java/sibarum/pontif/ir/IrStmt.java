package sibarum.pontif.ir;

import sibarum.pontif.core.Origin;

import java.util.List;

public sealed interface IrStmt permits IrStmt.FunctionDecl, IrStmt.TypeAlias, IrStmt.TraitImpl, IrStmt.Proof, IrStmt.Requires, IrStmt.Exports, IrStmt.NoOp {

    Origin origin();

    static FunctionDecl functionDecl(
            String name,
            List<IrParam> params,
            IrSort returnSort,
            IrExpr body) {
        return new FunctionDecl(name, params, returnSort, body, Origin.NONE);
    }

    static TypeAlias typeAlias(String name, IrSort sort) {
        return new TypeAlias(name, sort, Origin.NONE);
    }

    static NoOp noOp(String label) {
        return new NoOp(label, Origin.NONE);
    }

    static TraitImpl traitImpl(String typeName, String traitName, List<FunctionDecl> methods) {
        return new TraitImpl(typeName, traitName, methods, Origin.NONE);
    }

    static Proof proof(String functionName, IrExpr proofTree) {
        return new Proof(functionName, proofTree, Origin.NONE);
    }

    static Requires requires(String targetModule, List<String> names) {
        return new Requires(targetModule, names, Origin.NONE);
    }

    static Exports exports(List<String> names, boolean self) {
        return new Exports(names, self, Origin.NONE);
    }

    record FunctionDecl(
            String name,
            List<IrParam> params,
            IrSort returnSort,
            IrExpr body,
            Origin origin) implements IrStmt {
        public FunctionDecl {
            params = List.copyOf(params);
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("Function name must be non-empty");
            }
        }
    }

    /**
     * Binds a name to a sort. Resolved away by {@link AliasResolver} before
     * the rest of the compilation pipeline runs — every {@link IrSort.Named}
     * whose name matches a {@code TypeAlias} declaration gets substituted
     * with the aliased sort, transitively.
     */
    record TypeAlias(String name, IrSort sort, Origin origin) implements IrStmt {
        public TypeAlias {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("Type alias name must be non-empty");
            }
            if (sort == null) {
                throw new IllegalArgumentException("Type alias sort must be non-null");
            }
        }
    }

    /**
     * Trait impl block — assigns a trait to a struct type and bundles
     * the impl methods together. At compile time, methods are registered
     * in the dispatch table as regular {@link FunctionDecl}s (with the
     * type-qualified name and self-prepended params) and the
     * {@code (typeName, traitName)} pair is added to the
     * {@link sibarum.pontif.core.symbolic.TraitRegistry}.
     *
     * <p>SortChecker validates each contract method has a matching impl
     * (after self-prepending). Surface form (alt syntax):
     * {@code assign trait T:Tr { ... }}.
     */
    record TraitImpl(
            String typeName,
            String traitName,
            List<FunctionDecl> methods,
            Origin origin) implements IrStmt {
        public TraitImpl {
            if (typeName == null || typeName.isEmpty()) {
                throw new IllegalArgumentException("TraitImpl typeName must be non-empty");
            }
            if (traitName == null || traitName.isEmpty()) {
                throw new IllegalArgumentException("TraitImpl traitName must be non-empty");
            }
            methods = List.copyOf(methods);
        }
    }

    /**
     * A hand-authored proof for a function's declared return refinement,
     * written in-source as a struct-literal tree (a {@code Refinement}-shaped
     * {@code Leaf}/{@code Split} value). The {@code proofTree} is the
     * <b>unevaluated</b> {@link IrExpr} — never compiled or evaluated, so its
     * {@code Split} predicates stay symbolic. The return-refinement gate
     * ({@code PontifCompiler}) translates it to a
     * {@link sibarum.pontif.receipts.Refinement} and validates it against the
     * named function's obligation; a proof that no longer discharges is a hard
     * compile error. Surface form (alt syntax): {@code proof f = Split(...)}.
     */
    record Proof(String functionName, IrExpr proofTree, Origin origin) implements IrStmt {
        public Proof {
            if (functionName == null || functionName.isEmpty()) {
                throw new IllegalArgumentException("Proof functionName must be non-empty");
            }
            if (proofTree == null) {
                throw new IllegalArgumentException("Proof tree must be non-null");
            }
        }
    }

    /**
     * Import declaration: {@code requires a.b.{name, name, …}} — pulls the named
     * symbols from module {@code targetModule} (a dotted module name) into this
     * module's scope. Consumed by the module loader/linker and the name
     * resolver; inert when a single file is compiled on its own.
     */
    record Requires(String targetModule, List<String> names, Origin origin) implements IrStmt {
        public Requires {
            if (targetModule == null || targetModule.isEmpty()) {
                throw new IllegalArgumentException("Requires targetModule must be non-empty");
            }
            names = List.copyOf(names);
        }
    }

    /**
     * Export declaration: {@code exports @.{name, …}} (this module; {@code self}
     * true) lists the local symbols this module makes visible to importers.
     * Consumed by the linker's visibility check; inert for a single file.
     */
    record Exports(List<String> names, boolean self, Origin origin) implements IrStmt {
        public Exports {
            names = List.copyOf(names);
        }
    }

    /**
     * Placeholder for syntactic forms the parser recognizes but the IR
     * doesn't yet support (e.g., spec-only functions, {@code method}
     * declarations, top-level {@code let} without a body). Carries a
     * human-readable {@code label} of the original form for
     * diagnostics; otherwise contributes nothing to compilation or execution.
     */
    record NoOp(String label, Origin origin) implements IrStmt {
        public NoOp {
            if (label == null) {
                throw new IllegalArgumentException("NoOp label must be non-null");
            }
        }
    }
}
