package sibarum.pontif.parser;

import sibarum.pontif.core.Origin;
import sibarum.pontif.ir.InferenceContext;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrSort;
import sibarum.pontif.types.Assignability;
import sibarum.pontif.types.AssignabilityContext;
import sibarum.pontif.types.TypeCatalog;
import sibarum.pontif.types.TypeSystem;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static sibarum.pontif.parser.IrQueries.baseSortName;
import static sibarum.pontif.parser.IrQueries.describeSort;

/**
 * Parse-time sort inference and nominal let-binding, isolated from the parser.
 *
 * This is the one place the recursive-descent parser reasons with the type
 * system: it runs the core inference floor over the parser's live scope and
 * composes it with the nominal Assignability engine to decide a binding's
 * sort. Holding it here keeps PontifParser free of the TypeSystem and
 * Assignability engines - the parser owns the scope maps and the shared type
 * catalog, and this collaborator reads them live to answer inference queries.
 */
final class ParseTimeInference {

    private final TypeCatalog types;
    private final Map<String, IrSort> currentScope;
    private final Map<String, IrSort> declaredTopLevelLets;
    private final Map<String, IrSort> declaredFunctionReturns;

    ParseTimeInference(
            TypeCatalog types,
            Map<String, IrSort> currentScope,
            Map<String, IrSort> declaredTopLevelLets,
            Map<String, IrSort> declaredFunctionReturns) {
        this.types = types;
        this.currentScope = currentScope;
        this.declaredTopLevelLets = declaredTopLevelLets;
        this.declaredFunctionReturns = declaredFunctionReturns;
    }

    /**
     * Computes the maximally-specific sort for an expression, giving bindings
     * the tightest narrowing the parser can derive at parse time. Best-effort:
     * falls back to coarser shapes when tighter inference would require
     * machinery that does not exist yet (notably per-call dispatch return
     * narrowing).
     *
     * A record keeps its structural representation - the parser's canonical
     * aggregate shape (member name to sort), interchangeable with the
     * field-conjunct refinement the core's infer produces for the same value.
     * A named PARAMETRIC-struct record routes through the core engine instead so
     * its narrowing carries the derived type-args (Box(5) to [Box[Int]:@.value==5]),
     * which the type-arg-aware Assignability needs to decide let b:Box[Int] = Box(5).
     * Every other form types through the one core engine, so there is no
     * divergent reasoner - only a shape choice. Parse-time weakness falls out
     * only from an emptier context (no imports yet gives "_"), never a divergent
     * strategy. See docs/inference-unification.md.
     */
    IrSort maximalSort(IrExpr expr) {
        if (expr instanceof IrExpr.Record r) {
            boolean parametricNamed = r.typeName() != null
                    && types.shapeOf(r.typeName()).map(s -> !s.typeParams().isEmpty()).orElse(false);
            if (!parametricNamed) {
                Map<String, IrSort> members = new LinkedHashMap<>();
                for (Map.Entry<String, IrExpr> e : r.members().entrySet()) {
                    members.put(e.getKey(), maximalSort(e.getValue()));
                }
                return new IrSort.Structural(
                        r.typeName() != null ? r.typeName() : "_record", members, r.origin());
            }
        }
        IrSort inferred = TypeSystem.standard().inferFloor(expr, inferenceContext());
        // The parser's floor for "no narrowing" is the unknown sort "_", not null.
        return inferred != null ? inferred : IrSort.named("_");
    }

    /**
     * Builds the inference context from the parser's live scope maps. A Var
     * resolves in currentScope over declaredTopLevelLets; a 0-arg Call resolves
     * in declaredFunctionReturns over declaredTopLevelLets (a top-level let
     * lowers to a 0-arg call). Method/operator returns live in
     * declaredFunctionReturns keyed Type.method / the operator symbol.
     * Null-valued entries are stripped (InferenceContext's canonical
     * constructor rejects nulls).
     */
    private InferenceContext inferenceContext() {
        Map<String, IrSort> typeEnv = new LinkedHashMap<>();
        typeEnv.putAll(declaredTopLevelLets);
        typeEnv.putAll(currentScope);  // local scope shadows top-level
        Map<String, IrSort> functionReturns = new LinkedHashMap<>();
        functionReturns.putAll(declaredTopLevelLets);
        functionReturns.putAll(declaredFunctionReturns);  // declared returns win
        typeEnv.values().removeIf(Objects::isNull);
        functionReturns.values().removeIf(Objects::isNull);
        return new InferenceContext(typeEnv, functionReturns, types.structShapes(), Map.of(), Map.of(),
                Map.of(), Set.of(), Set.of(), Map.of());
    }

    /**
     * The binding sort for a let name:declared = value, decided by the single
     * nominal engine (Assignability) composed with inference (which produced
     * inferred, the tighter sort). Covers all trait-free nominal pairs -
     * struct/struct, primitives, and primitive/struct, including the Int to
     * Decimal embedding. A trait-free legality question needs no trait closure,
     * so the parser decides it here and throws on a provable mismatch
     * (ILLEGAL/NEEDS_CAST). Only trait-dependent legality is deferred post-link
     * (satisfaction is a fact the parser lacks): returns null when either side
     * is a trait, an anonymous-aggregate sentinel (_record/_tuple), or otherwise
     * not a decidable nominal pair (a type parameter, an alias, a parametric
     * application, an unknown floor); the caller then binds at the declared sort
     * and lets the post-link gate rule.
     *
     * Binding-sort rule: COERCE (a value that promotes at IR time, e.g. Int to
     * Decimal) binds at the bare declared base - a refinement there would become
     * a 0-arg-return obligation the integer kernel cannot prove, so the claim
     * rides the LetIn instead. A same-base agreement keeps the tighter inferred;
     * a widen/demote to a different base binds at the declared sort.
     */
    IrSort nominalBinding(IrSort inferred, IrSort declared, String name, Origin origin)
            throws ParseException {
        if (declared == null) return inferred;  // no claim - the plain agreement; keep the narrowing
        if (!(declared instanceof IrSort.Named)) return null;  // refined/union/etc. - gate judges it
        String declaredBase = baseSortName(declared);
        String inferredBase = baseSortName(inferred);
        if (declaredBase == null || inferredBase == null) return null;   // unknown floor - defer
        if (types.isTrait(declaredBase) || types.isTrait(inferredBase)) return null;  // satisfaction is post-link
        // Only a decidable nominal pair (struct or primitive on both sides) is judged here; anonymous
        // aggregates (_record/_tuple), aliases, and type parameters fall through to the caller.
        boolean lhsNominal = types.isStruct(declaredBase) || types.isPrimitive(declaredBase);
        boolean rhsNominal = types.isStruct(inferredBase) || types.isPrimitive(inferredBase);
        if (!lhsNominal || !rhsNominal) return null;
        Assignability.Assignment verdict =
                Assignability.assign(inferred, declared, AssignabilityContext.of(types));
        if (verdict == Assignability.Assignment.NEEDS_CAST
                || verdict == Assignability.Assignment.ILLEGAL) {
            throw new ParseException(
                    "let '" + name + "' is declared " + describeSort(declared)
                            + " but its value is " + describeSort(inferred)
                            + " - these are different types.",
                    origin);
        }
        if (verdict == Assignability.Assignment.COERCE) {
            return new IrSort.Named(declaredBase, declared.origin());  // bare - the value promotes at IR time
        }
        return declaredBase.equals(inferredBase) ? inferred : declared;
    }
}
