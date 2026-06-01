package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.parser.AltParser;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end multi-file module linking: parse each module's source, link via
 * {@code compileProject} (FQN-keyed, coherence-checked), run the entry module's
 * main. Exercises the loader-invoked pipeline; single-file compiles are covered
 * elsewhere and stay on the bare-key path.
 */
class ModuleSystemTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private Map<String, IrModule> modules(Map<String, String> sources) {
        Map<String, IrModule> mods = new LinkedHashMap<>();
        sources.forEach((name, src) -> {
            try {
                mods.put(name, AltParser.parseModule(src, name + ".ptf"));
            } catch (Exception e) {
                throw new RuntimeException("parse " + name + ": " + e.getMessage(), e);
            }
        });
        return mods;
    }

    private RunResult runProject(Map<String, String> sources, String entry) {
        CompileResult r = compiler.compileProject(modules(sources), entry);
        assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "expected link+compile success; got: " + ((CompileResult.Failed) r).error().text());
        return runner.run(r, Engine.INTERPRETER);
    }

    private String assertLinkRejected(Map<String, String> sources, String entry) {
        CompileResult r = compiler.compileProject(modules(sources), entry);
        assertInstanceOf(CompileResult.Failed.class, r, "expected link/compile failure");
        return ((CompileResult.Failed) r).error().text();
    }

    @Test
    void crossModuleCall_resolvesViaRequires_andRuns() {
        // app imports `f` from lib and calls it. The call resolves to lib's FQN
        // declaration across the merge.
        Map<String, String> src = new LinkedHashMap<>();
        src.put("lib", """
                module lib
                exports @.{inc}
                function inc(x:Int):Int -> x + 1
                """);
        src.put("app", """
                module app
                requires lib.{inc}
                inc(41)
                """);
        RunResult r = runProject(src, "app");
        assertTrue(!r.isError(), () -> "expected success; got: " + r.text());
        assertEquals("42", r.text());
    }

    @Test
    void qualifiedCall_moduleDotName_resolves() {
        // The fully-qualified call form `lib.inc(...)` resolves too.
        Map<String, String> src = new LinkedHashMap<>();
        src.put("lib", """
                module lib
                exports @.{inc}
                function inc(x:Int):Int -> x + 1
                """);
        src.put("app", """
                module app
                requires lib.{inc}
                lib.inc(41)
                """);
        assertEquals("42", runProject(src, "app").text());
    }

    @Test
    void sameLocalName_inTwoModules_doesNotCollide() {
        // Both modules declare `helper`; FQN keys (lib/helper, app/helper) keep
        // them distinct. app's own helper is the one its main calls.
        Map<String, String> src = new LinkedHashMap<>();
        src.put("lib", """
                module lib
                exports @.{helper}
                function helper(x:Int):Int -> x + 100
                """);
        src.put("app", """
                module app
                function helper(x:Int):Int -> x * 2
                helper(10)
                """);
        assertEquals("20", runProject(src, "app").text());  // app/helper, not lib/helper
    }

    @Test
    void sameTypeName_inTwoModules_doesNotCollide_andCrossModuleStructTypeFlows() {
        // geo and viz BOTH declare a struct `Point` (different shapes). Per-module
        // type FQNs (geo/Point vs viz/Point) keep them distinct in the combined
        // module — no duplicate-alias collision. app constructs geo's Point via
        // geo's `make` constructor (struct literals for *imported* structs are a
        // separate parser enhancement) and reads it back via geo's originX —
        // proving the geo/Point type flows across modules in signatures + fields.
        Map<String, String> src = new LinkedHashMap<>();
        src.put("geo", """
                module geo
                exports @.{make, originX}
                struct Point(x:Int, y:Int)
                function make(x:Int, y:Int):Point -> Point(x, y)
                function originX(p:Point):Int -> p.x
                """);
        src.put("viz", """
                module viz
                struct Point(r:Int, g:Int, b:Int)
                function red(p:Point):Int -> p.r
                """);
        src.put("app", """
                module app
                requires geo.{make, originX}
                originX(make(7, 9))
                """);
        assertEquals("7", runProject(src, "app").text());
    }

    @Test
    void unknownEntryModule_isHardError() {
        Map<String, String> src = new LinkedHashMap<>();
        src.put("lib", "module lib\nfunction f(x:Int):Int -> x\n0");
        String err = assertLinkRejected(src, "nope");
        assertTrue(err.contains("Unknown entry module 'nope'"), () -> err);
    }
}
