package sibarum.pontif.ir;

import sibarum.pontif.core.Origin;

import java.util.List;
import java.util.Map;

public sealed interface IrSort permits IrSort.Named, IrSort.Refined, IrSort.Structural, IrSort.CallSig, IrSort.Trait, IrSort.Union, IrSort.Intersection {

    /**
     * Reserved sentinel sort name for the {@code this.type} self-type (the
     * type-preserving return — docs/associated-types.md §7.3). Carried as an
     * {@link Named} with this name; the embedded dot makes it un-spellable as a
     * user struct/trait name, so it never collides. In a trait contract it is in
     * scope like an associated-type variable; per impl it substitutes to the
     * implementor's own concrete type; at the bare-trait boundary it
     * existentializes to the owning trait.
     */
    String SELF_TYPE = "this.type";

    Origin origin();

    /**
     * The nominal base name — the head type name this sort dispatches, refines,
     * or is-a's on. A {@link CallSig} receiver's base is its head type name (a
     * metareference dispatches its traits' methods like any nominal). The
     * anonymous composites ({@link Union} / {@link Intersection}) have no single
     * base and return {@code null}. The single home for the {@code baseName(sort)}
     * switch that was open-coded across the dispatch, cast, and check passes.
     */
    default String baseName() {
        return switch (this) {
            case Named n -> n.name();
            case Refined r -> r.name();
            case Structural s -> s.name();
            case Trait t -> t.name();
            case CallSig c -> c.typeName();
            case Union ignored -> null;
            case Intersection ignored -> null;
        };
    }

    static Named named(String name) {
        return new Named(name, Origin.NONE);
    }

    static Refined refined(String name, IrExpr predicate) {
        return new Refined(name, predicate, Origin.NONE);
    }

    static Structural structural(String name, Map<String, IrSort> members) {
        return new Structural(name, members, Origin.NONE);
    }

    static CallSig method(List<IrSort> paramSorts, IrSort returnSort) {
        return new CallSig(CallSig.METHOD, paramSorts, List.of(), returnSort, Origin.NONE);
    }

    static CallSig dispatch(List<IrSort> keySorts, IrSort returnSort) {
        return new CallSig(CallSig.DISPATCH, keySorts, List.of(), returnSort, Origin.NONE);
    }

    static CallSig action(List<IrSort> paramSorts) {
        return new CallSig(CallSig.ACTION, paramSorts, List.of(), named("_"), Origin.NONE);
    }

    static CallSig conduit(List<IrSort> paramSorts, IrSort returnSort) {
        return new CallSig(CallSig.CONDUIT, paramSorts, List.of(), returnSort, Origin.NONE);
    }

    static Trait trait(String name, Map<String, IrSort.CallSig> methods) {
        return new Trait(name, methods, Map.of(), Origin.NONE);
    }

    static Union union(List<IrSort> branches) {
        return new Union(branches, Origin.NONE);
    }

    static Intersection intersection(List<IrSort> branches) {
        return new Intersection(branches, Origin.NONE);
    }

    /**
     * A named type reference, optionally applied to type arguments:
     * {@code Int} is {@code Named("Int", [])}, a type variable {@code T} is
     * {@code Named("T", [])}, and a parametric application {@code Element[Int]}
     * is {@code Named("Element", [Named("Int", [])])}
     * (docs/type-parameters.md §2.3). A bare reference and an applied one share
     * this one variant — the head name plus its (possibly empty) type arguments —
     * so every existing {@code case IrSort.Named} keeps matching; only code that
     * cares about the arguments reads {@link #typeArgs()}.
     */
    record Named(String name, List<IrSort> typeArgs, Origin origin) implements IrSort {
        public Named {
            typeArgs = List.copyOf(typeArgs);
        }

        /** Back-compat: a bare named reference with no type arguments. */
        public Named(String name, Origin origin) {
            this(name, List.of(), origin);
        }
    }

    /**
     * A refined sort {@code [Base:pred]}. {@code typeArgs} carries a parametric
     * base's type arguments — {@code [Literal[Int]:@.value==value]} is
     * {@code Refined("Literal", [Named("Int")], …)} (docs/type-parameters.md §2.3,
     * the is-a-base reading). Empty for a non-parametric base; every existing
     * {@code case IrSort.Refined} keeps matching, only the is-a-base check reads
     * {@link #typeArgs()}.
     */
    record Refined(String name, List<IrSort> typeArgs, IrExpr predicate, Origin origin)
            implements IrSort {
        public Refined {
            typeArgs = List.copyOf(typeArgs);
        }

        /** Back-compat: a non-parametric refined base (no type arguments). */
        public Refined(String name, IrExpr predicate, Origin origin) {
            this(name, List.of(), predicate, origin);
        }
    }

    /**
     * Struct sort. {@code baseSort} (nullable) carries the is-a relationship
     * declared by {@code struct Name:[Base:rel](fields)} — the parsed
     * {@code [Base:rel]} sort, whose base names the supertype and whose
     * refinement predicate (if any) is the <em>demotion morphism</em> relating
     * the base's fields to this struct's constructor params (e.g.
     * {@code @.x==x & @.y==y} on {@code Point3D:[Point:…](x,y,z)}). Null for a
     * plain struct with no declared base. Parse + registration + totality
     * validation only at this slice; the demotion coercion it licenses is a
     * later slice.
     */
    record Structural(String name, Map<String, IrSort> members, IrSort baseSort,
                      Map<String, IrSort> typeParams, Map<String, IrExpr> extensions,
                      Origin origin) implements IrSort {
        public Structural {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("Structural sort name must be non-empty");
            }
            if (members == null) {
                throw new IllegalArgumentException("Structural sort members must be non-null");
            }
            if (typeParams == null) {
                throw new IllegalArgumentException("Structural sort typeParams must be non-null");
            }
            // LinkedHashMap preserves field declaration order — critical for
            // destructure desugaring, which walks fields in declared order so
            // that `let x = p.x in let y = p.y in body` reads top-to-bottom in
            // the same order the user wrote the struct decl. Map.copyOf does
            // NOT preserve order.
            members = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(members));
            // Type parameters (`struct Box[type T](…)`), name → bound; a null
            // value is an unbounded `type T`. Declaration order preserved, and
            // null values permitted (so LinkedHashMap, not Map.copyOf), exactly
            // like {@link Trait#associatedTypes}.
            typeParams = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(typeParams));
            // Constructor-extension fields (`let name:Sort = expr` in the member
            // block): name → initializer. Each name is ALSO a key of `members`
            // (appended after the constructor fields, carrying its declared
            // sort); the default constructor never accepts it — ConstructionGate
            // materializes the value into every constructed record.
            if (extensions == null) {
                throw new IllegalArgumentException("Structural sort extensions must be non-null");
            }
            extensions = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(extensions));
        }

        /** Back-compat: the pre-extension canonical shape (no extension fields). */
        public Structural(String name, Map<String, IrSort> members, IrSort baseSort,
                          Map<String, IrSort> typeParams, Origin origin) {
            this(name, members, baseSort, typeParams, java.util.Map.of(), origin);
        }

        /** Back-compat: a struct with a declared base-sort but no type parameters. */
        public Structural(String name, Map<String, IrSort> members, IrSort baseSort, Origin origin) {
            this(name, members, baseSort, java.util.Map.of(), origin);
        }

        /**
         * The constructor-facing field view: {@link #members()} minus the
         * extension fields. Positional arity, by-name required-field checks,
         * destructure slot mapping, and base-field determination all read this —
         * an extension field exists on every VALUE but is never a constructor
         * parameter.
         */
        public Map<String, IrSort> constructorMembers() {
            if (extensions.isEmpty()) return members;
            java.util.LinkedHashMap<String, IrSort> m = new java.util.LinkedHashMap<>(members);
            m.keySet().removeAll(extensions.keySet());
            return java.util.Collections.unmodifiableMap(m);
        }

        /** Back-compat: a plain struct with no declared base-sort (is-a) and no type parameters. */
        public Structural(String name, Map<String, IrSort> members, Origin origin) {
            this(name, members, null, java.util.Map.of(), origin);
        }
    }

    /**
     * A <b>call signature</b> sort {@code Type(Params):Return} — the one generic
     * node that replaces the old hardcoded {@code Method} and {@code Dispatch}
     * kinds (docs/dispatch-method-elimination.md). {@code typeName} is <em>data</em>
     * — {@code "Method"}, {@code "Dispatch"}, or any future callable type — never a
     * keyword branched on: subtyping and value-satisfaction are driven by which
     * <em>call-kind capability</em> ({@code function-style} / {@code dispatch-style})
     * the head type is-a, looked up as registry data ({@link CallKinds}).
     *
     * <p>{@code paramSorts} is the variadic parameter list (the one sanctioned
     * variadic type-argument list — carried structurally here rather than as trait
     * type-args). {@code paramNames} is either empty (a positional sort,
     * {@code [Method(Int,Int):R]}) or one name per parameter (a named sort,
     * {@code [Method(i:Int,j:Int):R]}) — never partially named; names are the
     * binders a dependent return/param sort may reference (WAR(dependent-sorts)).
     * For a dispatch-style sort the params are the dispatch <em>key</em> sorts.
     */
    record CallSig(String typeName, List<IrSort> paramSorts, List<String> paramNames,
                   IrSort returnSort, Origin origin) implements IrSort {

        /** The builtin function-style head type ({@code Method}) — a lambda's contract. */
        public static final String METHOD = "Method";
        /** The builtin dispatch-style head type ({@code Dispatch}) — a metareference's contract. */
        public static final String DISPATCH = "Dispatch";
        /**
         * The builtin effect-reaction head type ({@code Action}) — an event handler whose
         * terminus is write-only ({@code emit}), no returned value. A member's sort being an
         * {@code Action(...)} is what declares it an effect reaction inside a member block,
         * the sort-carried sibling of the top-level {@code action} keyword.
         */
        public static final String ACTION = "Action";
        /**
         * The builtin conduit head type ({@code Conduit}) — an event handler with a value
         * terminus (its state) alongside its effects. The sort-carried sibling of the
         * top-level {@code conduit} keyword.
         */
        public static final String CONDUIT = "Conduit";

        public CallSig {
            if (typeName == null || typeName.isEmpty()) {
                throw new IllegalArgumentException("CallSig typeName must be non-empty");
            }
            if (paramSorts == null) {
                throw new IllegalArgumentException("CallSig paramSorts must be non-null");
            }
            if (returnSort == null) {
                throw new IllegalArgumentException("CallSig returnSort must be non-null");
            }
            paramSorts = List.copyOf(paramSorts);
            paramNames = paramNames == null ? List.of() : List.copyOf(paramNames);
            if (!paramNames.isEmpty() && paramNames.size() != paramSorts.size()) {
                throw new IllegalArgumentException(
                        "CallSig paramNames, when present, must be one per parameter "
                                + "(got " + paramNames.size() + " names for "
                                + paramSorts.size() + " params)");
            }
        }

        /** Back-compat: a positional call signature — no parameter names. */
        public CallSig(String typeName, List<IrSort> paramSorts, IrSort returnSort, Origin origin) {
            this(typeName, paramSorts, List.of(), returnSort, origin);
        }
    }

    /**
     * Trait sort: a named contract over members — methods AND typed data
     * {@code attributes} (the {@code @{…}} member cell; see
     * docs/univocal-arrows.md). Values of this sort are concrete struct types
     * that satisfy the contract — registered explicitly via
     * {@link IrStmt.TraitImpl} blocks. The contract method signatures here
     * exclude the implicit {@code this} parameter; SortChecker prepends it.
     *
     * <p>An {@code attribute} {@code name->sort} is a required data member: it
     * is a value sort ({@code Int}, a refinement {@code [Int:@>0]}, a named
     * struct), NOT a {@link CallSig} sort. A satisfier supplies it either with a
     * matching struct field or with a computed producer in its impl block — a
     * trait attribute is a computed projection of the underlying value, which is
     * what makes trait coercion free in both directions.
     */
    record Trait(String name, Map<String, IrSort.CallSig> methods,
                 Map<String, IrSort> attributes, Map<String, IrSort> associatedTypes,
                 Map<String, IrSort> typeParams, Map<String, IrSort.CallSig> operators,
                 String baseTrait, List<IrSort> typeArgs,
                 Map<String, IrStmt.FunctionDecl> methodDefaults,
                 Map<String, IrExpr.Lambda> returnShells,
                 Map<String, Map<Integer, IrExpr.Lambda>> argShells, Origin origin)
            implements IrSort {
        public Trait {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("Trait name must be non-empty");
            }
            if (methods == null) {
                throw new IllegalArgumentException("Trait methods must be non-null");
            }
            if (attributes == null) {
                throw new IllegalArgumentException("Trait attributes must be non-null");
            }
            if (associatedTypes == null) {
                throw new IllegalArgumentException("Trait associatedTypes must be non-null");
            }
            if (typeParams == null) {
                throw new IllegalArgumentException("Trait typeParams must be non-null");
            }
            if (operators == null) {
                throw new IllegalArgumentException("Trait operators must be non-null");
            }
            methods = java.util.Collections.unmodifiableMap(
                    new java.util.LinkedHashMap<>(methods));
            attributes = java.util.Collections.unmodifiableMap(
                    new java.util.LinkedHashMap<>(attributes));
            // Values may be null (an unbounded associated type `type X`); a
            // non-null value is the bound (`type X:R`). LinkedHashMap permits
            // null values — Map.copyOf would not.
            associatedTypes = java.util.Collections.unmodifiableMap(
                    new java.util.LinkedHashMap<>(associatedTypes));
            // `[type T]` slot parameters on the trait (`trait Expr[type T]{…}`,
            // docs/type-parameters.md §2.1) — distinct from associatedTypes:
            // parameters are chosen from OUTSIDE (the user writes `Expr[Int]`),
            // associated types are fixed by the implementor. Null = unbounded.
            typeParams = java.util.Collections.unmodifiableMap(
                    new java.util.LinkedHashMap<>(typeParams));
            // Operator contract members — `+:[Dispatch(this.type, this.type):this.type]`
            // (dispatch-unification B1, docs/traits.md "Operator contract members").
            // Each is a mechanism-1 BOUND: the satisfier must have a coherent
            // operator overload of this shape; the trait does not host it. Keyed by
            // the operator symbol ("+", "*", …). Verified at `assign trait`, not
            // implemented in the block — a later slice; the parser only collects them.
            operators = java.util.Collections.unmodifiableMap(
                    new java.util.LinkedHashMap<>(operators));
            // baseTrait: the extended trait's name (`trait B : A` → "A"), or null for a
            // root trait. WAR(stream): trait-extends-trait — an impl of B must satisfy
            // A's contract too, and a B is-a A for dispatch (SortChecker/TraitRegistry).
            if (baseTrait != null && baseTrait.isEmpty()) {
                baseTrait = null;
            }
            // typeArgs: the APPLIED type arguments (`Stream[Int]` → [Int]), distinct
            // from typeParams (the declaration slots). Empty for a bare/declaration-site
            // trait; populated by AliasResolver.applyTypeArgs when a parametric trait is
            // instantiated. WAR(stream) §8.6: carrying these closes the no-lie hole where
            // the element type of a computed Stream was dropped at resolution.
            typeArgs = typeArgs == null ? List.of() : List.copyOf(typeArgs);
            // methodDefaults: method name → a body-bearing FunctionDecl, present only
            // for a DEFAULTED contract method (`quack():Int -> this.x` in the trait
            // body). A defaulted method also appears in `methods` (its signature), so
            // every existing contract/dispatch path still sees it; the body here is
            // cloned per-impl by TraitDefaultExpansion when the impl omits the method.
            // The stored FunctionDecl's `this` param is typed to SELF_TYPE — the
            // expansion substitutes it to the satisfier's concrete type.
            methodDefaults = methodDefaults == null
                    ? java.util.Map.of()
                    : java.util.Collections.unmodifiableMap(
                            new java.util.LinkedHashMap<>(methodDefaults));
            // returnShells: method name → its trait-owned RETURN shell `[C->D]`
            // (docs/sort-transforms.md). Callers/dispatch see the terminus D (the
            // contract method's returnSort); the impl's kernel returns the domain C;
            // TraitDefaultExpansion wraps the kernel with this transform. Forward-only
            // (slice 1) — arg-shells are a later slice.
            returnShells = returnShells == null
                    ? java.util.Map.of()
                    : java.util.Collections.unmodifiableMap(
                            new java.util.LinkedHashMap<>(returnShells));
            // argShells: method name → user-param POSITION → its trait-owned ARGUMENT
            // shell `[A->B]` (docs/sort-transforms.md, slice 2). The caller passes the
            // domain A (the contract param sort, what dispatch keys on); the impl's
            // kernel sees the codomain B; TraitDefaultExpansion rewrites the registered
            // param to A and rebinds `let p = shell(p)` before the body. Position-keyed
            // because the impl may rename the parameter.
            argShells = argShells == null
                    ? java.util.Map.of()
                    : java.util.Collections.unmodifiableMap(
                            new java.util.LinkedHashMap<>(argShells));
        }

        /** Back-compat: the pre-argShells canonical — return shells present, no arg shells. */
        public Trait(String name, Map<String, IrSort.CallSig> methods,
                     Map<String, IrSort> attributes, Map<String, IrSort> associatedTypes,
                     Map<String, IrSort> typeParams, Map<String, IrSort.CallSig> operators,
                     String baseTrait, List<IrSort> typeArgs,
                     Map<String, IrStmt.FunctionDecl> methodDefaults,
                     Map<String, IrExpr.Lambda> returnShells, Origin origin) {
            this(name, methods, attributes, associatedTypes, typeParams, operators,
                    baseTrait, typeArgs, methodDefaults, returnShells, java.util.Map.of(), origin);
        }

        /** Back-compat: the pre-returnShells canonical — defaults present, no return shells. */
        public Trait(String name, Map<String, IrSort.CallSig> methods,
                     Map<String, IrSort> attributes, Map<String, IrSort> associatedTypes,
                     Map<String, IrSort> typeParams, Map<String, IrSort.CallSig> operators,
                     String baseTrait, List<IrSort> typeArgs,
                     Map<String, IrStmt.FunctionDecl> methodDefaults, Origin origin) {
            this(name, methods, attributes, associatedTypes, typeParams, operators,
                    baseTrait, typeArgs, methodDefaults, java.util.Map.of(), java.util.Map.of(), origin);
        }

        /** Back-compat: the pre-methodDefaults canonical signature — no default method bodies. */
        public Trait(String name, Map<String, IrSort.CallSig> methods,
                     Map<String, IrSort> attributes, Map<String, IrSort> associatedTypes,
                     Map<String, IrSort> typeParams, Map<String, IrSort.CallSig> operators,
                     String baseTrait, List<IrSort> typeArgs, Origin origin) {
            this(name, methods, attributes, associatedTypes, typeParams, operators,
                    baseTrait, typeArgs, java.util.Map.of(), java.util.Map.of(), origin);
        }

        /** Back-compat: the pre-typeArgs canonical signature — no applied type arguments. */
        public Trait(String name, Map<String, IrSort.CallSig> methods,
                     Map<String, IrSort> attributes, Map<String, IrSort> associatedTypes,
                     Map<String, IrSort> typeParams, Map<String, IrSort.CallSig> operators,
                     String baseTrait, Origin origin) {
            this(name, methods, attributes, associatedTypes, typeParams, operators,
                    baseTrait, List.of(), java.util.Map.of(), origin);
        }

        /** Back-compat: a trait with no base trait (a root trait). */
        public Trait(String name, Map<String, IrSort.CallSig> methods,
                     Map<String, IrSort> attributes, Map<String, IrSort> associatedTypes,
                     Map<String, IrSort> typeParams, Map<String, IrSort.CallSig> operators,
                     Origin origin) {
            this(name, methods, attributes, associatedTypes, typeParams, operators,
                    null, List.of(), origin);
        }

        /** Back-compat: a trait with no operator contract members. */
        public Trait(String name, Map<String, IrSort.CallSig> methods,
                     Map<String, IrSort> attributes, Map<String, IrSort> associatedTypes,
                     Map<String, IrSort> typeParams, Origin origin) {
            this(name, methods, attributes, associatedTypes, typeParams, Map.of(), null, origin);
        }

        /** Back-compat: a trait with no type parameters. */
        public Trait(String name, Map<String, IrSort.CallSig> methods,
                     Map<String, IrSort> attributes, Map<String, IrSort> associatedTypes,
                     Origin origin) {
            this(name, methods, attributes, associatedTypes, Map.of(), Map.of(), origin);
        }

        /** Back-compat: a trait with no associated types or type parameters. */
        public Trait(String name, Map<String, IrSort.CallSig> methods,
                     Map<String, IrSort> attributes, Origin origin) {
            this(name, methods, attributes, Map.of(), Map.of(), Map.of(), origin);
        }

        /** Back-compat: a methods-only trait (no data attributes). */
        public Trait(String name, Map<String, IrSort.CallSig> methods, Origin origin) {
            this(name, methods, Map.of(), Map.of(), Map.of(), Map.of(), origin);
        }
    }

    /**
     * Union of cross-base sorts. Same-base unions (e.g.,
     * {@code [Int:0]|[Int:1]}) are normalized at parse time into a single
     * {@link Refined} sort with an {@code OR}-joined predicate; this variant
     * is reserved for unions whose branches don't share a common base
     * (e.g., {@code Int|Float}).
     *
     * <p>Branches are stored in source order. The dispatcher accepts a
     * value against a Union sort iff the value satisfies at least one
     * branch.
     */
    record Union(List<IrSort> branches, Origin origin) implements IrSort {
        public Union {
            if (branches == null) {
                throw new IllegalArgumentException("Union branches must be non-null");
            }
            branches = List.copyOf(branches);
            if (branches.size() < 2) {
                throw new IllegalArgumentException(
                        "Union must have at least two branches; got " + branches.size());
            }
        }
    }

    /**
     * Intersection of cross-base sorts. Same-base intersections (e.g.,
     * {@code [Int:@>0]&[Int:@<10]}) are normalized at parse time into a
     * single {@link Refined} sort with an {@code AND}-joined predicate;
     * this variant is reserved for the rare cross-base case.
     *
     * <p>The dispatcher accepts a value against an Intersection sort iff
     * the value satisfies every branch.
     */
    record Intersection(List<IrSort> branches, Origin origin) implements IrSort {
        public Intersection {
            if (branches == null) {
                throw new IllegalArgumentException("Intersection branches must be non-null");
            }
            branches = List.copyOf(branches);
            if (branches.size() < 2) {
                throw new IllegalArgumentException(
                        "Intersection must have at least two branches; got " + branches.size());
            }
        }
    }
}
