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

    /** The language-core builtin module's name (docs/stream-war.md): the {@code Stream} trait. */
    public static final String PONTIF_CORE = "pontif.core";

    /** The event-substrate builtin module's name (docs/events.md): IO + concurrency. */
    public static final String PONTIF_EVENTS = "pontif.events";

    /**
     * Modules contributed by installed {@link Extension}s (docs/extensions.md), by name —
     * the side-effecting builtins ({@code pontif.events} via {@link IoExtension}, the GUI, …).
     * Distinct from the pure builtins below, which have no Java backing and stay hardcoded.
     */
    private static final Map<String, IrModule> EXTENSION_MODULES = new LinkedHashMap<>();

    static {
        // The builtin IO extension is always present (no external dependency), so every path —
        // CLI included — keeps StdOut/StdErr/stdin. External extensions (the GUI) are installed
        // by their launcher before compile.
        Extensions.install(IoExtension.INSTANCE);
    }

    private BuiltinModules() {}

    /** Records an installed extension's module so {@link #all()} offers it to the linker. */
    static void registerExtensionModule(String name, IrModule module) {
        EXTENSION_MODULES.put(name, module);
    }

    /** All builtin modules, by name — the pure builtins plus every installed extension module. */
    public static Map<String, IrModule> all() {
        Map<String, IrModule> mods = new LinkedHashMap<>();
        mods.put(STD_COMMON, stdCommon());
        mods.put(STD_PROOF, stdProof());
        mods.put(STD_CONSERVATION, stdConservation());
        mods.put(PONTIF_CORE, pontifCore());
        mods.putAll(EXTENSION_MODULES);
        return mods;
    }

    /**
     * The language-core module (docs/stream-war.md): home of the {@code Stream}
     * trait — the pure provable membrane over stateful sources. Base {@code Stream}
     * is a <b>marker capability</b> (no callable contract member yet — internal
     * iteration drives a source via the {@code Iterate} construct, so there is no
     * external {@code next()} to expose; the Source-obligation shape is deferred
     * until builtin streams are exercised). Sub-traits ({@code IndexedStream}) and a
     * real backing arrive in later slices; for now a tuple literal autoboxes into
     * {@code Stream[E]}. Also home to {@code Nothing} (slice 2b) — the universal
     * omission value ({@code let null:Nothing = Nothing()}); a fragment that returns
     * {@code Nothing} at a stream channel drops that element (the lossy filter shape,
     * docs/stream-war.md §3). Written in Pontif source, like {@code std.stream}.
     */
    private static IrModule pontifCore() {
        String source = """
                exports @.{Stream, Nothing}

                trait Stream[type E]{}

                struct Nothing()

                0
                """;
        try {
            IrModule parsed = sibarum.pontif.parser.AltParser.parseModule(source, PONTIF_CORE);
            return new IrModule(PONTIF_CORE, parsed.statements(), parsed.main());
        } catch (sibarum.pontif.parser.ParseException pe) {
            throw new IllegalStateException(
                    "pontif.core's builtin source failed to parse: " + pe.getMessage(), pe);
        }
    }

    // pontif.events (the event substrate's IO: Event/EventConduit/EventStream + the
    // StdOut/StdErr sinks + stdin) is no longer hardcoded here — it is the builtin
    // IoExtension (docs/extensions.md), installed by default into EXTENSION_MODULES and
    // surfaced by all(). Its Pontif-side interface + the by-name binding to the Java
    // effects/calls live in IoExtension.

    // The Queue and its cons-cell combinators (the retired `std.stream`:
    // `Element(head, rest:[Element|Leaf])` + singleton/concat/append/map/
    // exchange/partition) were demolished in the stream-trait war (§7 step 5,
    // docs/stream-war.md): the `&s:[…]` synthesis-fragment primitive subsumes
    // the whole basis, so the cons-cell carried no remaining weight. `Leaf`
    // still lives in `std.common` (shared with the proof system).

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
