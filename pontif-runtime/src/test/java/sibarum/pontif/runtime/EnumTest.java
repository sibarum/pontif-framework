package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code enum} declarator (docs/enums.md) — sugar for a sealed struct plus one
 * pinned case struct per case, with the case names as type-level members.
 *
 * <p>What is being pinned here is that enum adds no type-system machinery: each test
 * asserts a behaviour that falls out of an existing rule — the discriminant pin, the
 * construction gate, match totality — now reachable from a declaration that says what
 * it means. The genuinely new facts are three: the <b>seal</b> (a sealed base cannot
 * be constructed, and a match over it is total on its cases alone), the <b>type-level
 * member</b> ({@code E.Case} names a singleton sort and its one inhabitant at once),
 * and the <b>lookup</b> ({@code E(literal…)} selects a case rather than building one).
 */
class EnumTest {

    private static final String RESOURCE = """
            enum ResourceType(driver:String) {
              DatabaseTable("postgres")
              LocalFilesystem("NTFS")
              RemoteHttp("tcp/ip")
            }
            """;

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private RunResult run(String src, Engine engine) {
        return runner.run(compiler.compile(src, "enum.ptf"), engine);
    }

    private String value(String src) {
        RunResult interp = run(src, Engine.INTERPRETER);
        assertFalse(interp.isError(), () -> "interpreter: " + interp.text());
        RunResult truffle = run(src, Engine.TRUFFLE);
        assertFalse(truffle.isError(), () -> "truffle: " + truffle.text());
        assertEquals(interp.text(), truffle.text(), "engines disagree");
        return interp.text();
    }

    private String error(String src) {
        CompileResult result = compiler.compile(src, "enum.ptf");
        CompileResult.Failed failed = assertInstanceOf(CompileResult.Failed.class, result,
                () -> "expected a compile error, but the program compiled");
        return failed.error().text();
    }

    // --- the type-level member ---------------------------------------------

    @Test
    void caseName_isAValue_andCarriesItsPinnedFields() {
        assertEquals("\"postgres\"", value(RESOURCE + """
                main ( ResourceType.DatabaseTable.driver )"""));
    }

    @Test
    void caseName_isASort_soItCanBeABindingsDeclaredType() {
        assertEquals("\"NTFS\"", value(RESOURCE + """
                main (
                  let f:ResourceType.LocalFilesystem = ResourceType.LocalFilesystem
                  f.driver
                )"""));
    }

    @Test
    void caseValue_demotesToTheEnum_keepingItsFields() {
        assertEquals("\"tcp/ip\"", value(RESOURCE + """
                main (
                  let r:ResourceType = ResourceType.RemoteHttp
                  r.driver
                )"""));
    }

    @Test
    void ordinal_ordersTheCasesInDeclarationOrder() {
        assertEquals("12", value(RESOURCE + """
                main (
                  ResourceType.DatabaseTable._ordinal * 100
                    + ResourceType.LocalFilesystem._ordinal * 10
                    + ResourceType.RemoteHttp._ordinal
                )"""));
    }

    // --- the lookup ---------------------------------------------------------

    @Test
    void lookup_selectsTheCaseCarryingTheLiteralRow() {
        assertEquals("0", value(RESOURCE + """
                main ( ResourceType("postgres")._ordinal )"""));
    }

    @Test
    void lookup_rejectsAValueNoCaseCarries() {
        String err = error(RESOURCE + """
                main ( ResourceType("mysql").driver )""");
        assertTrue(err.contains("No case of enum 'ResourceType' carries that value"),
                () -> "unexpected error: " + err);
        assertTrue(err.contains("ResourceType.DatabaseTable"),
                () -> "the error should list the cases that DO exist: " + err);
    }

    @Test
    void lookup_rejectsANonLiteralArgument() {
        String err = error(RESOURCE + """
                function pick(s:String):String -> ResourceType(s).driver
                main ( pick("NTFS") )""");
        assertTrue(err.contains("literal"), () -> "unexpected error: " + err);
    }

    // --- the seal -----------------------------------------------------------

    @Test
    void sealedBase_cannotBeConstructedDirectly() {
        String err = error(RESOURCE + """
                main ( ResourceType{driver="mysql"}.driver )""");
        assertTrue(err.contains("sealed enum") || err.contains("_ordinal"),
                () -> "unexpected error: " + err);
    }

    @Test
    void match_isTotalOverTheCasesAlone_withNoDefaultArm() {
        assertEquals("\"remote\"", value(RESOURCE + """
                function describe(r:ResourceType):String -> match r {
                  [ResourceType.DatabaseTable] -> "db"
                  [ResourceType.LocalFilesystem] -> "local"
                  [ResourceType.RemoteHttp] -> "remote"
                }
                main ( describe(ResourceType.RemoteHttp) )"""));
    }

    @Test
    void match_missingACase_namesTheCaseNoArmCovers() {
        String err = error(RESOURCE + """
                function describe(r:ResourceType):String -> match r {
                  [ResourceType.DatabaseTable] -> "db"
                  [ResourceType.LocalFilesystem] -> "local"
                }
                main ( describe(ResourceType.RemoteHttp) )""");
        assertTrue(err.contains("not exhaustive"), () -> "unexpected error: " + err);
        assertTrue(err.contains("ResourceType.RemoteHttp"),
                () -> "the error should name the missing case: " + err);
    }

    @Test
    void match_literalRowArm_coversTheCaseCarryingIt() {
        assertEquals("\"local\"", value(RESOURCE + """
                function describe(r:ResourceType):String -> match r {
                  [ResourceType("NTFS")] -> "local"
                  [ResourceType.DatabaseTable] -> "db"
                  [ResourceType] -> "other"
                }
                main ( describe(ResourceType.LocalFilesystem) )"""));
    }

    @Test
    void match_literalRowArmsAlone_areTotal() {
        assertEquals("\"db\"", value(RESOURCE + """
                function describe(r:ResourceType):String -> match r {
                  [ResourceType("postgres")] -> "db"
                  [ResourceType("NTFS")] -> "local"
                  [ResourceType("tcp/ip")] -> "remote"
                }
                main ( describe(ResourceType.DatabaseTable) )"""));
    }

    @Test
    void match_refinementArm_coversWhateverTheCoverSays() {
        assertEquals("\"short\"", value(RESOURCE + """
                function describe(r:ResourceType):String -> match r {
                  [ResourceType:@.driver == "NTFS"] -> "short"
                  [ResourceType] -> "other"
                }
                main ( describe(ResourceType.LocalFilesystem) )"""));
    }

    // --- payload-free enums -------------------------------------------------

    @Test
    void fieldlessEnum_casesAreDistinctAndMatchable() {
        assertEquals("\"green\"", value("""
                enum Colour { Red; Green; Blue }
                function name(c:Colour):String -> match c {
                  [Colour.Red] -> "red"
                  [Colour.Green] -> "green"
                  [Colour.Blue] -> "blue"
                }
                main ( name(Colour.Green) )"""));
    }

    @Test
    void duplicatePayloads_stayDistinctCases() {
        assertEquals("1", value("""
                enum Flag(on:Bool) { Draft(false); Hidden(false); Live(true) }
                main ( Flag.Hidden._ordinal )"""));
    }

    // --- declaration-time checks -------------------------------------------

    @Test
    void case_mustDetermineEveryField() {
        String err = error("""
                enum Pair(a:Int, b:Int) { Both(1, 2); Half(3) }
                main ( 0 )""");
        assertTrue(err.contains("supplies 1 value(s)"), () -> "unexpected error: " + err);
    }

    @Test
    void case_valuesMustBeLiterals() {
        String err = error("""
                enum Bad(n:Int) { One(1); Two(1 + 1) }
                main ( 0 )""");
        assertTrue(err.contains("literal"), () -> "unexpected error: " + err);
    }

    @Test
    void case_namesMustBeDistinct() {
        String err = error("""
                enum Dup(n:Int) { A(1); A(2) }
                main ( 0 )""");
        assertTrue(err.contains("twice"), () -> "unexpected error: " + err);
    }

    @Test
    void enum_mustDeclareAtLeastOneCase() {
        String err = error("""
                enum Empty(n:Int) { }
                main ( 0 )""");
        assertTrue(err.contains("no cases"), () -> "unexpected error: " + err);
    }

    // --- methods share the member block ------------------------------------

    /** The enum with a method added to its member block, sharing the same braces. */
    private static final String WITH_METHOD = RESOURCE.replace("}\n", """
              isRemote():Int -> match this {
                [ResourceType.RemoteHttp] -> 1
                [ResourceType] -> 0
              }
            }
            """);

    @Test
    void enum_carriesMethods_whichMayNameSiblingCases() {
        assertEquals("1", value(WITH_METHOD + """
                main ( ResourceType.RemoteHttp.isRemote() )"""));
        assertEquals("0", value(WITH_METHOD + """
                main ( ResourceType.DatabaseTable.isRemote() )"""));
    }

    /**
     * An enum takes trait obligations on its declaration exactly as a struct does, and
     * its block methods satisfy them. Passing a case where the TRAIT is expected is a
     * separate, pre-existing gap (docs/enums.md §6): a pinned subtype does not inherit
     * its base's trait impl at the call gate, which a hand-written
     * {@code struct Sub:[Base:@.n==1]()} fails in exactly the same way.
     */
    @Test
    void enum_declaresTraitObligations_andItsBlockMethodsSatisfyThem() {
        assertEquals("500", value("""
                trait Budgeted { budget:[Method():Int] }
                enum Tier:[Budgeted](name:String) {
                  Cheap("basic")
                  Costly("premium")

                  budget():Int -> match this {
                    [Tier.Costly] -> 500
                    [Tier] -> 5
                  }
                }
                main ( Tier.Costly.budget() )"""));
    }

    /** A case is usable wherever the enum is expected — the demotion is total. */
    @Test
    void caseValue_passesWhereTheEnumIsExpected() {
        assertEquals("\"NTFS\"", value(RESOURCE + """
                function driverOf(r:ResourceType):String -> r.driver
                main ( driverOf(ResourceType.LocalFilesystem) )"""));
    }
}
