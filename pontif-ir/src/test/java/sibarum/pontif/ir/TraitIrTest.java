package sibarum.pontif.ir;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ast.record.RecordValue;
import sibarum.pontif.core.Origin;
import sibarum.pontif.core.symbolic.Simplifier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for slice 2: IR shapes for traits ({@link IrSort.Trait},
 * {@link IrStmt.TraitImpl}) and their integration with the compile
 * pipeline ({@link AliasResolver}, {@link SortChecker},
 * {@link IrCompiler}).
 *
 * <p>End-to-end behavior is exercised via hand-built IR: a trait
 * declaration (TypeAlias holding a Trait sort), a struct, a
 * {@code TraitImpl} block whose methods become real {@code FunctionDecl}s
 * in the dispatch table, and a {@code Call("Trait.method", ...)} that
 * routes through the slice-1 fallback to the concrete-type impl at
 * runtime.
 */
class TraitIrTest {

    private static final Simplifier SIMPLIFIER = new Simplifier(List.of());

    /** Trait: Duck { quack():Int } — using Int as the return for simple verification. */
    private static IrSort.Trait duckTrait() {
        Map<String, IrSort.Function> methods = new LinkedHashMap<>();
        methods.put("quack", new IrSort.Function(List.of(), IrSort.named("Int"), Origin.NONE));
        return new IrSort.Trait("Duck", methods, Origin.NONE);
    }

    /** Struct: Donald(name:Int) — Int instead of String since String isn't a primitive yet. */
    private static IrSort.Structural donaldStruct() {
        Map<String, IrSort> members = new LinkedHashMap<>();
        members.put("name", IrSort.named("Int"));
        return new IrSort.Structural("Donald", members, Origin.NONE);
    }

    /** Impl method: function Donald.quack(self:Donald):Int -> 42 */
    private static IrStmt.FunctionDecl donaldQuackImpl() {
        return new IrStmt.FunctionDecl(
                "Donald.quack",
                List.of(new IrParam("self", donaldStruct())),
                IrSort.named("Int"),
                IrExpr.lit(42),
                Origin.NONE);
    }

    private static CompiledModule compile(IrModule module) throws CompileException {
        return new IrCompiler(SIMPLIFIER).compile(module);
    }

    // --- IR shape sanity ----------------------------------------------------

    @Test
    void traitSort_canBeConstructed() {
        IrSort.Trait t = duckTrait();
        assertEquals("Duck", t.name());
        assertEquals(1, t.methods().size());
        assertTrue(t.methods().containsKey("quack"));
    }

    @Test
    void traitImpl_canBeConstructed() {
        IrStmt.TraitImpl ti = new IrStmt.TraitImpl(
                "Donald", "Duck", List.of(donaldQuackImpl()), Origin.NONE);
        assertEquals("Donald", ti.typeName());
        assertEquals("Duck", ti.traitName());
        assertEquals(1, ti.methods().size());
    }

    // --- Compile pipeline ---------------------------------------------------

    @Test
    void module_withTraitDeclAndImpl_compilesAndRegistersSatisfierPair() throws Exception {
        IrModule module = new IrModule(
                "m",
                List.of(
                        new IrStmt.TypeAlias("Duck", duckTrait(), Origin.NONE),
                        new IrStmt.TypeAlias("Donald", donaldStruct(), Origin.NONE),
                        new IrStmt.TraitImpl(
                                "Donald", "Duck",
                                List.of(donaldQuackImpl()),
                                Origin.NONE)),
                IrExpr.lit(0));

        CompiledModule compiled = compile(module);

        // Donald.quack is registered in dispatch.
        assertEquals(1, compiled.dispatch().declarationsFor("Donald.quack").size());
        // The (Donald, Duck) pair is in the trait registry.
        assertTrue(compiled.dispatch().traitRegistry().satisfies("Duck", "Donald"));
    }

    @Test
    void traitImpl_referencingUnknownTrait_failsWithClearMessage() {
        IrModule module = new IrModule(
                "m",
                List.of(
                        new IrStmt.TypeAlias("Donald", donaldStruct(), Origin.NONE),
                        new IrStmt.TraitImpl(
                                "Donald", "NonexistentTrait",
                                List.of(donaldQuackImpl()),
                                Origin.NONE)),
                IrExpr.lit(0));

        CompileException ex = assertThrows(CompileException.class, () -> compile(module));
        assertTrue(ex.getMessage().contains("NonexistentTrait"));
        assertTrue(ex.getMessage().toLowerCase().contains("unknown trait"));
    }

    @Test
    void traitImpl_missingRequiredMethod_failsWithClearMessage() {
        // Duck contract requires `quack` but the impl provides nothing.
        IrModule module = new IrModule(
                "m",
                List.of(
                        new IrStmt.TypeAlias("Duck", duckTrait(), Origin.NONE),
                        new IrStmt.TypeAlias("Donald", donaldStruct(), Origin.NONE),
                        new IrStmt.TraitImpl(
                                "Donald", "Duck", List.of(), Origin.NONE)),
                IrExpr.lit(0));

        CompileException ex = assertThrows(CompileException.class, () -> compile(module));
        assertTrue(ex.getMessage().contains("quack"));
        assertTrue(ex.getMessage().toLowerCase().contains("missing"));
    }

    @Test
    void traitImpl_methodWithWrongArity_failsWithClearMessage() {
        // Contract says quack():Int — no params (besides implicit self).
        // Impl declares quack(self:Donald, extra:Int):Int — one too many.
        IrStmt.FunctionDecl badImpl = new IrStmt.FunctionDecl(
                "Donald.quack",
                List.of(
                        new IrParam("self", donaldStruct()),
                        new IrParam("extra", IrSort.named("Int"))),
                IrSort.named("Int"),
                IrExpr.lit(0),
                Origin.NONE);

        IrModule module = new IrModule(
                "m",
                List.of(
                        new IrStmt.TypeAlias("Duck", duckTrait(), Origin.NONE),
                        new IrStmt.TypeAlias("Donald", donaldStruct(), Origin.NONE),
                        new IrStmt.TraitImpl(
                                "Donald", "Duck", List.of(badImpl), Origin.NONE)),
                IrExpr.lit(0));

        CompileException ex = assertThrows(CompileException.class, () -> compile(module));
        assertTrue(ex.getMessage().toLowerCase().contains("param"));
        assertTrue(ex.getMessage().contains("contract"));
    }

    // --- End-to-end runtime dispatch ----------------------------------------

    @Test
    void traitMethodCall_routesThroughFallback_toConcreteImpl() throws Exception {
        // Main: Call("Duck.quack", [donaldRecord])
        // Should resolve via slice-1 fallback to Donald.quack, which returns 42.
        Map<String, IrExpr> donaldMembers = new LinkedHashMap<>();
        donaldMembers.put("name", IrExpr.lit(1));
        IrExpr donaldRecord = new IrExpr.Record("Donald", donaldMembers, Origin.NONE);
        IrExpr mainCall = new IrExpr.Call("Duck.quack", List.of(donaldRecord), Origin.NONE);

        IrModule module = new IrModule(
                "m",
                List.of(
                        new IrStmt.TypeAlias("Duck", duckTrait(), Origin.NONE),
                        new IrStmt.TypeAlias("Donald", donaldStruct(), Origin.NONE),
                        new IrStmt.TraitImpl(
                                "Donald", "Duck",
                                List.of(donaldQuackImpl()),
                                Origin.NONE)),
                mainCall);

        CompiledModule compiled = compile(module);
        Object result = new IrInterpreter(SIMPLIFIER).eval(compiled);
        assertEquals(42L, result);
    }

    @Test
    void function_takingTraitTypedParam_dispatchesOnSatisfyingArg() throws Exception {
        // function describe(d:Duck):Int -> 7
        // describe(donald) where Donald satisfies Duck → resolves to describe
        IrSort duckSort = duckTrait();
        IrStmt.FunctionDecl describe = new IrStmt.FunctionDecl(
                "describe",
                List.of(new IrParam("d", IrSort.named("Duck"))),
                IrSort.named("Int"),
                IrExpr.lit(7),
                Origin.NONE);

        Map<String, IrExpr> donaldMembers = new LinkedHashMap<>();
        donaldMembers.put("name", IrExpr.lit(1));
        IrExpr donaldRecord = new IrExpr.Record("Donald", donaldMembers, Origin.NONE);
        IrExpr mainCall = new IrExpr.Call("describe", List.of(donaldRecord), Origin.NONE);

        IrModule module = new IrModule(
                "m",
                List.of(
                        new IrStmt.TypeAlias("Duck", duckSort, Origin.NONE),
                        new IrStmt.TypeAlias("Donald", donaldStruct(), Origin.NONE),
                        new IrStmt.TraitImpl(
                                "Donald", "Duck",
                                List.of(donaldQuackImpl()),
                                Origin.NONE),
                        describe),
                mainCall);

        CompiledModule compiled = compile(module);
        Object result = new IrInterpreter(SIMPLIFIER).eval(compiled);
        assertEquals(7L, result);
    }

    @Test
    void function_takingTraitTypedParam_rejectsNonSatisfyingArg() throws Exception {
        // function describe(d:Duck):Int -> 7
        // Duck is declared but no impls; Point passed in → must NOT dispatch
        IrStmt.FunctionDecl describe = new IrStmt.FunctionDecl(
                "describe",
                List.of(new IrParam("d", IrSort.named("Duck"))),
                IrSort.named("Int"),
                IrExpr.lit(7),
                Origin.NONE);

        // Anonymous "Point" record (a struct that isn't a Duck satisfier)
        Map<String, IrExpr> pointMembers = new LinkedHashMap<>();
        pointMembers.put("x", IrExpr.lit(1));
        pointMembers.put("y", IrExpr.lit(2));
        IrExpr pointRec = new IrExpr.Record("Point", pointMembers, Origin.NONE);
        IrExpr mainCall = new IrExpr.Call("describe", List.of(pointRec), Origin.NONE);

        IrModule module = new IrModule(
                "m",
                List.of(
                        new IrStmt.TypeAlias("Duck", duckTrait(), Origin.NONE),
                        describe),
                mainCall);

        CompiledModule compiled = compile(module);
        // No matching dispatch at runtime
        assertThrows(Exception.class, () -> new IrInterpreter(SIMPLIFIER).eval(compiled));
    }

    @Test
    void recordValue_carriesTypeName_throughEvalAndToSymExpr() throws Exception {
        // Sanity check: when we build IrExpr.Record with a typeName,
        // the runtime RecordValue carries the same name.
        Map<String, IrExpr> members = new LinkedHashMap<>();
        members.put("name", IrExpr.lit(1));
        IrExpr record = new IrExpr.Record("Donald", members, Origin.NONE);
        IrModule module = new IrModule("m", List.of(), record);

        CompiledModule compiled = compile(module);
        Object result = new IrInterpreter(SIMPLIFIER).eval(compiled);
        RecordValue rv = assertInstanceOf(RecordValue.class, result);
        assertEquals("Donald", rv.typeName());
    }
}
