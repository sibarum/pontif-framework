package sibarum.pontif.runtime.module;

import sibarum.pontif.core.Origin;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrSort;
import sibarum.pontif.ir.IrStmt;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiler-provided modules, constructed in Java rather than parsed from a
 * shipped {@code .ptf} source. They are seeded into the linker's module
 * universe so user code can {@code requires} them like any other module —
 * exercising the real import path (imports/exports), which is the point: a
 * standard library you import is the proof that the module system is complete,
 * not just demo-complete.
 *
 * <p>Visibility is <b>explicit</b>: a builtin module is injected by
 * {@link ModuleLinker} only when some user module actually {@code requires} it,
 * so a program that never imports it is unaffected (in particular, a user's own
 * {@code Leaf}/{@code Split} type is never shadowed nor made ambiguous).
 *
 * <p>The first builtin is {@code std.proof}: the proof-authoring vocabulary
 * ({@code Leaf}, {@code Split}, {@code Singletons}) that proofs previously had
 * to hand-redeclare. {@code RefinementProof} recognizes these by local name and
 * translates a proof tree built from them into a {@code Refinement}.
 */
public final class BuiltinModules {

    /** The shared-shapes builtin module's name — structs with reuse value live here. */
    public static final String STD_COMMON = "std.common";

    /** The proof-authoring builtin module's name. */
    public static final String STD_PROOF = "std.proof";

    /** The conservation-property builtin module's name. */
    public static final String STD_CONSERVATION = "std.conservation";

    /** The sequence-substrate builtin module's name (docs/streams.md). */
    public static final String STD_STREAM = "std.stream";

    private BuiltinModules() {}

    /** All builtin modules, by name. */
    public static Map<String, IrModule> all() {
        Map<String, IrModule> mods = new LinkedHashMap<>();
        mods.put(STD_COMMON, stdCommon());
        mods.put(STD_PROOF, stdProof());
        mods.put(STD_CONSERVATION, stdConservation());
        mods.put(STD_STREAM, stdStream());
        return mods;
    }

    /**
     * The Queue and its combinators — the sequence substrate's inductive view
     * ({@code docs/streams.md}, ratified): {@code Element(head,
     * rest:[Element|Leaf])} with terminal {@code Leaf()} re-exported from
     * {@code std.common}, plus the basis written <b>in Pontif source</b> —
     * the first builtin that is itself Pontif, parsed at registration. The
     * combinators are recursive match functions; function-valued parameters
     * are metareferences invoked by application.
     *
     * <p>Interim leniencies, both flagged in the doc: {@code head} and the
     * function params are loose ({@code "_"}) until the {@code [Stream(T)]}
     * sort form and Dispatch-key subsumption land (a Dispatch param sort
     * requires EXACT key match today, which would pin each combinator to one
     * key sort). Combinator bodies call through bindings, which the
     * conservation drafter treats as residual — combinator pipelines are
     * fail-closed in the ledger until the drafter learns to resolve
     * metareference arguments (a named follow-up).
     */
    private static final String STD_STREAM_SOURCE = """
            requires std.common.{Leaf}
            exports @.{Element, Leaf, singleton, concat, append, map, exchange, partition}

            struct Element(head:_, rest:[Element|Leaf])

            function singleton(x:_):Element -> Element(x, Leaf())

            function concat(a:[Element|Leaf], b:[Element|Leaf]):[Element|Leaf] -> match a {
              [Element] -> Element(a.head, concat(a.rest, b))
              [Leaf]    -> b
            }

            function append(q:[Element|Leaf], x:_):[Element|Leaf] -> concat(q, singleton(x))

            function map(f:_, q:[Element|Leaf]):[Element|Leaf] -> match q {
              [Element] -> Element(f(q.head), map(f, q.rest))
              [Leaf]    -> Leaf()
            }

            function exchange(p:_, f:_, q:[Element|Leaf]):[Element|Leaf] -> match q {
              [Element] -> match p(q.head) {
                [Bool:@==true] -> Element(f(q.head), exchange(p, f, q.rest))
                _              -> Element(q.head, exchange(p, f, q.rest))
              }
              [Leaf] -> Leaf()
            }

            function partition(p:_, q:[Element|Leaf]):[([Element|Leaf], [Element|Leaf])] -> match q {
              [Element] -> match partition(p, q.rest) {
                [(yes, no)] -> match p(q.head) {
                  [Bool:@==true] -> (Element(q.head, yes), no)
                  _              -> (yes, Element(q.head, no))
                }
              }
              [Leaf] -> (Leaf(), Leaf())
            }

            0
            """;

    private static IrModule stdStream() {
        try {
            IrModule parsed = sibarum.pontif.parser.AltParser.parseModule(
                    STD_STREAM_SOURCE, STD_STREAM);
            // Force the registry name regardless of header conventions.
            return new IrModule(STD_STREAM, parsed.statements(), parsed.main());
        } catch (sibarum.pontif.parser.ParseException pe) {
            throw new IllegalStateException(
                    "std.stream's builtin source failed to parse: " + pe.getMessage(), pe);
        }
    }

    /**
     * Structs with cross-domain reuse value (ruled 2026-06-06). Founding
     * resident: {@code Leaf()} — the canonical terminal, referenced by
     * {@code [Leaf|Split]} (proof trees) and {@code [Element(T)|Leaf]}
     * (streams, when they land). One freestanding nominal, borrowed by
     * whichever unions need it — the un-Haskell union design's poster child.
     * Domain modules RE-EXPORT it so their import surfaces stay whole.
     */
    private static IrModule stdCommon() {
        IrStmt leaf = IrStmt.typeAlias("Leaf", IrSort.structural("Leaf", new LinkedHashMap<>()));
        IrStmt exports = IrStmt.exports(List.of("Leaf"), true);
        return new IrModule(STD_COMMON, List.of(exports, leaf), IrExpr.lit(0));
    }

    private static IrModule stdProof() {
        IrSort leafOrSplit = IrSort.union(List.of(IrSort.named("Leaf"), IrSort.named("Split")));

        // Leaf lives in std.common; std.proof re-exports it (requires +
        // exports of the imported name), so `requires std.proof.{Leaf, …}`
        // keeps working and resolves to the SAME nominal: std.common/Leaf.
        IrStmt requiresCommon = new IrStmt.Requires(STD_COMMON,
                List.of(new IrStmt.RequireEntry("Leaf", "Leaf")), Origin.NONE);

        // struct Split(p:Bool, whenTrue:[Leaf|Split], whenFalse:[Leaf|Split])
        Map<String, IrSort> splitFields = new LinkedHashMap<>();
        splitFields.put("p", IrSort.named("Bool"));
        splitFields.put("whenTrue", leafOrSplit);
        splitFields.put("whenFalse", leafOrSplit);
        IrStmt split = IrStmt.typeAlias("Split", IrSort.structural("Split", splitFields));

        // struct Singletons(subject:Int, lo:Int, hi:Int) — the generative
        // "recursion to singletons" directive; RefinementProof unfolds it into a
        // splitToSingletons ladder rather than reading it as a literal value.
        Map<String, IrSort> singletonsFields = new LinkedHashMap<>();
        singletonsFields.put("subject", IrSort.named("Int"));
        singletonsFields.put("lo", IrSort.named("Int"));
        singletonsFields.put("hi", IrSort.named("Int"));
        IrStmt singletons =
                IrStmt.typeAlias("Singletons", IrSort.structural("Singletons", singletonsFields));

        // Leaf appears in the exports although it is imported, not declared —
        // that combination IS a re-export.
        IrStmt exports = IrStmt.exports(List.of("Leaf", "Split", "Singletons"), true);

        return new IrModule(STD_PROOF,
                List.of(requiresCommon, exports, split, singletons), IrExpr.lit(0));
    }

    /**
     * The conservation-property vocabulary (names provisional): assertions
     * over the conservation ledger, attached with the same {@code proof f = …}
     * statement as algebraic proofs — the tree's head picks the ledger.
     * {@code ConservationProofs} (pontif-conservation) recognizes these by
     * local name; the trees are never evaluated.
     */
    private static IrModule stdConservation() {
        // struct DataConservative() / Reversible() / NoDuplication() — 0-arg
        // properties. ("Lossless" is RESERVED for the cross-ledger property —
        // see docs/conservation-algebra.md.)
        IrStmt dataConservative = IrStmt.typeAlias(
                "DataConservative", IrSort.structural("DataConservative", new LinkedHashMap<>()));
        IrStmt reversible = IrStmt.typeAlias(
                "Reversible", IrSort.structural("Reversible", new LinkedHashMap<>()));
        IrStmt noDuplication = IrStmt.typeAlias(
                "NoDuplication", IrSort.structural("NoDuplication", new LinkedHashMap<>()));

        // struct DataConservativeExcept(dropped:_) — intentional erasure; the
        // argument is an unevaluated attribute expression over the target's
        // params (e.g. s.email), so its declared sort is the "_" placeholder.
        Map<String, IrSort> exceptFields = new LinkedHashMap<>();
        exceptFields.put("dropped", IrSort.named("_"));
        IrStmt dataConservativeExcept = IrStmt.typeAlias(
                "DataConservativeExcept",
                IrSort.structural("DataConservativeExcept", exceptFields));

        IrStmt exports = IrStmt.exports(
                List.of("DataConservative", "Reversible", "NoDuplication",
                        "DataConservativeExcept"), true);

        return new IrModule(STD_CONSERVATION,
                List.of(exports, dataConservative, reversible, noDuplication,
                        dataConservativeExcept),
                IrExpr.lit(0));
    }
}
