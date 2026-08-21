package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage for structural sorts + records + field access at the
 * source level. Goes through SexprParser → PontifCompiler → PontifRunner; checks
 * both engines (interpreter and Truffle) where a parity check makes sense.
 */
class StructuralIntegrationTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private RunResult run(String source) {
        return runner.run(compiler.compileSexpr(source, "t.ptf"), Engine.INTERPRETER);
    }

    private RunResult runTruffle(String source) {
        return runner.run(compiler.compileSexpr(source, "t.ptf"), Engine.TRUFFLE);
    }

    // --- Record construction + field access ---

    @Test
    void recordConstructionAndFieldRead() throws Exception {
        // Build a point, read its x field.
        String src = """
                (module m
                  ()
                  (let p (struct Point (x Int) (y Int)) (record (x 3) (y 4))
                    (field p x)))
                """;
        assertEquals("3", run(src).text());
        assertEquals("3", runTruffle(src).text());
    }

    @Test
    void recordWithComputedFields_evaluatesEagerly() throws Exception {
        String src = """
                (module m
                  ()
                  (let p (struct Cell (sum Int)) (record (sum (+ 1 (* 2 3))))
                    (field p sum)))
                """;
        assertEquals("7", run(src).text());
    }

    @Test
    void nestedRecord_accessedThroughChainedFieldReads() throws Exception {
        String src = """
                (module m
                  ()
                  (let o
                       (struct Outer (inner (struct Inner (n Int))))
                       (record (inner (record (n 42))))
                    (field (field o inner) n)))
                """;
        assertEquals("42", run(src).text());
        assertEquals("42", runTruffle(src).text());
    }

    @Test
    void missingField_isACompileError_caughtBySortPropagation() throws Exception {
        // The base's sort is statically known via the let-binding's declared
        // sort, so SortChecker rejects this before runtime.
        String src = """
                (module m
                  ()
                  (let p (struct P (x Int)) (record (x 1))
                    (field p missing)))
                """;
        RunResult r = run(src);
        assertTrue(r.isError(), "expected error");
        assertTrue(r.text().toLowerCase().contains("compile"),
                "should be a compile-time error; got: " + r.text());
        assertTrue(r.text().contains("missing"),
                "should name the field; got: " + r.text());
        assertTrue(r.text().contains("'P'") || r.text().contains("P "),
                "should name the sort; got: " + r.text());
    }

    @Test
    void missingField_onUntypedBase_stillCaughtAtRuntime() throws Exception {
        // When the base's sort isn't statically inferable (here: a call result
        // with no declared sort recorded in scope), the runtime catches it.
        String src = """
                (module m
                  ((defn mkRec () (struct P (x Int))
                     (record (x 1))))
                  (field (call mkRec) missing))
                """;
        RunResult r = run(src);
        assertTrue(r.isError(), "expected error");
        assertTrue(r.text().contains("missing"),
                "should name the field; got: " + r.text());
    }

    @Test
    void missingField_inDestructuringBranch_caughtByCompiler() throws Exception {
        // Inside the structural-pattern branch, p's sort is narrowed to the
        // pattern. SortChecker validates that (field p z) — z not in pattern —
        // is a compile error.
        String src = """
                (module m
                  ()
                  (let p (struct P (x Int) (y Int)) (record (x 1) (y 2))
                    (match p
                      ((struct P (x Int) (y Int)) (field p z)))))
                """;
        RunResult r = run(src);
        assertTrue(r.isError(), "expected error");
        assertTrue(r.text().toLowerCase().contains("compile"),
                "should be a compile-time error; got: " + r.text());
        assertTrue(r.text().contains("'z'"),
                "should name the missing field; got: " + r.text());
    }

    @Test
    void nestedFieldAccess_chainedThroughKnownStructuralSorts_compiles() throws Exception {
        // (field (field o inner) n) where both layers' sorts are known —
        // SortChecker recurses through the chain successfully.
        String src = """
                (module m
                  ()
                  (let o
                       (struct Outer (inner (struct Inner (n Int))))
                       (record (inner (record (n 7))))
                    (field (field o inner) n)))
                """;
        assertEquals("7", run(src).text());
    }

    @Test
    void nestedFieldAccess_invalidInnerField_caughtAtCompile() throws Exception {
        // The inner field name is wrong; SortChecker walks through the chain
        // and fails on the inner FieldAccess.
        String src = """
                (module m
                  ()
                  (let o
                       (struct Outer (inner (struct Inner (n Int))))
                       (record (inner (record (n 7))))
                    (field (field o inner) bogus)))
                """;
        RunResult r = run(src);
        assertTrue(r.isError(), "expected error");
        assertTrue(r.text().toLowerCase().contains("compile"));
        assertTrue(r.text().contains("bogus"));
    }

    @Test
    void functionParamWithStructuralSort_fieldAccessValidatedInBody() throws Exception {
        // The function param's structural sort flows into the body's type
        // environment; field accesses against it validate at compile time.
        String src = """
                (module m
                  ((defn manhattan ((p (struct P (x Int) (y Int)))) Int
                     (+ (field p x) (field p oops))))
                  (call manhattan (record (x 3) (y 4))))
                """;
        RunResult r = run(src);
        assertTrue(r.isError(), "expected compile error");
        assertTrue(r.text().toLowerCase().contains("compile"));
        assertTrue(r.text().contains("oops"));
    }

    // --- Structural sorts as function parameter types: dispatch on records ---

    @Test
    void functionDeclaredWithStructuralParam_dispatchesOnRecord() throws Exception {
        // The dispatcher now lifts RecordValue → SymExpr.Record so structural
        // refinement matching against an actual record value works at runtime.
        String src = """
                (module m
                  ((defn manhattan ((p (struct P (x Int) (y Int)))) Int
                     (+ (field p x) (field p y))))
                  (call manhattan (record (x 3) (y 4))))
                """;
        assertEquals("7", run(src).text());
        assertEquals("7", runTruffle(src).text());
    }

    // --- Match on a structural sort ---

    @Test
    void destructuring_structuralPatternBindsFieldNames() throws Exception {
        // x and y are bound to p's fields automatically inside the branch.
        String src = """
                (module m
                  ()
                  (let p (struct Point (x Int) (y Int)) (record (x 3) (y 4))
                    (match p
                      ((struct Point (x Int) (y Int)) (+ x y)))))
                """;
        assertEquals("7", run(src).text());
        assertEquals("7", runTruffle(src).text());
    }

    @Test
    void destructuring_nonVarScrutinee_evaluatedOnce() throws Exception {
        // A compound scrutinee is wrapped in a synthetic outer let so it
        // doesn't re-evaluate per field-access. (Result should still be 12.)
        String src = """
                (module m
                  ((defn mkPair ((a Int) (b Int)) (struct Pair (x Int) (y Int))
                     (record (x a) (y b))))
                  (match (call mkPair 5 7)
                    ((struct Pair (x Int) (y Int)) (+ x y))))
                """;
        assertEquals("12", run(src).text());
        assertEquals("12", runTruffle(src).text());
    }

    @Test
    void destructuring_renaming_doesNotConflictWithOuterScope() throws Exception {
        // Outer let binds x to 100; inner match's structural pattern shadows
        // it with the field value (10). Result is the inner x.
        String src = """
                (module m
                  ()
                  (let x Int 100
                    (let p (struct P (x Int)) (record (x 10))
                      (match p
                        ((struct P (x Int)) (+ x 1))))))
                """;
        assertEquals("11", run(src).text());
    }

    @Test
    void destructuring_nestedRecord_innerFieldsBoundOneLevel() throws Exception {
        // Destructuring binds only the top-level field names. To get nested
        // field values, the branch uses (field inner ...) explicitly.
        String src = """
                (module m
                  ()
                  (let o
                       (struct Outer (inner (struct I (n Int))))
                       (record (inner (record (n 5))))
                    (match o
                      ((struct Outer (inner (struct I (n Int))))
                        (field inner n)))))
                """;
        assertEquals("5", run(src).text());
    }

    @Test
    void matchOnRecord_picksBranchByStructuralSort() throws Exception {
        // Two-branch match where the first sort accepts records with field
        // `kind` and the alternate fallback catches all Ints. Only one branch
        // shape applies to the record value.
        String src = """
                (module m
                  ()
                  (let p (struct Tagged (kind Int)) (record (kind 1))
                    (match p
                      ((struct Tagged (kind Int)) (field p kind)))))
                """;
        RunResult r = run(src);
        assertFalse(r.isError(), "expected success; got: " + r.text());
        assertEquals("1", r.text());
    }

    // --- Empty struct sort + empty record ---

    @Test
    void emptyStructSort_canBeDeclared_andEmptyRecordConstructed() throws Exception {
        String src = """
                (module m
                  ()
                  (let u (struct Unit) (record)
                    42))
                """;
        // The let value is the empty record, the body returns 42.
        assertEquals("42", run(src).text());
    }
}
