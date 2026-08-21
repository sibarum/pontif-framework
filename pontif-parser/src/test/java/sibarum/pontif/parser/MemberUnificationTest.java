package sibarum.pontif.parser;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ir.CallKinds;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrSort;
import sibarum.pontif.ir.IrStmt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice 1 of the orchestration authoring model (docs/orchestration.md, "Ratified
 * authoring model → Member unification"): {@code Action} and {@code Conduit} join
 * {@code Method}/{@code Dispatch} as recognized member <em>type-constructors</em>, so an
 * effectful member can be declared inside a member block by its <em>sort</em> — the
 * sort-carried sibling of the top-level {@code action}/{@code conduit} keyword forms.
 *
 * <p>These tests pin the classification contract this slice adds:
 * <ul>
 *   <li>an {@code Action}/{@code Conduit}-sorted member is classified as a <b>callable</b>
 *       member (kept in {@code methods}), NOT silently misread as a data attribute — the
 *       load-bearing fail-closed guard;</li>
 *   <li>its call-kind is exactly {@code Kind.ACTION}/{@code Kind.CONDUIT}, distinct from a
 *       {@code Method}'s {@code Kind.FUNCTION}, so downstream can route by kind;</li>
 *   <li>{@code method}/{@code [Method(…)]} equivalence still holds (regression anchor).</li>
 * </ul>
 * The effectful members' write-only/value terminus is expressed in the type for this slice
 * ({@code [Action(e:E):_]} — {@code _} is "no value out"; {@code [Conduit(e:E,s:S):S]}); the
 * prettier transform-chain surface ({@code onKey:[E -> …]}) is a later slice.
 */
class MemberUnificationTest {

    private static IrModule parse(String src) throws ParseException {
        return PontifParser.parseModule(src, "t");
    }

    /** A sort's head name, for structural comparison that ignores source origin. */
    private static String sortName(IrSort s) {
        return switch (s) {
            case IrSort.Named n -> n.name();
            case IrSort.Refined r -> r.name();
            default -> s.toString();
        };
    }

    private static IrSort.Trait traitOf(IrModule m) {
        IrStmt.TypeAlias ta = assertInstanceOf(IrStmt.TypeAlias.class, m.statements().get(0));
        return assertInstanceOf(IrSort.Trait.class, ta.sort());
    }

    // --- Action as a member type-constructor -----------------------------

    @Test
    void actionMember_classifiedAsCallableWithActionKind_notAttribute() throws Exception {
        IrSort.Trait trait = traitOf(parse("trait Keyed{onKey:[Action(e:KeyPress):_]}"));
        // Caught as a callable member — the fail-closed guard against the attribute trap.
        assertTrue(trait.methods().containsKey("onKey"),
                "Action member must land in methods, not be misread as data");
        assertFalse(trait.attributes().containsKey("onKey"),
                "Action member must NOT fall through to attributes");
        IrSort.CallSig sig = trait.methods().get("onKey");
        assertEquals(IrSort.CallSig.ACTION, sig.typeName());
        assertEquals(CallKinds.Kind.ACTION, CallKinds.builtin(sig.typeName()));
    }

    @Test
    void conduitMember_classifiedAsCallableWithConduitKind() throws Exception {
        IrSort.Trait trait = traitOf(parse("trait Counter{tick:[Conduit(e:Tick,s:Int):Int]}"));
        assertTrue(trait.methods().containsKey("tick"));
        assertFalse(trait.attributes().containsKey("tick"));
        IrSort.CallSig sig = trait.methods().get("tick");
        assertEquals(IrSort.CallSig.CONDUIT, sig.typeName());
        assertEquals(CallKinds.Kind.CONDUIT, CallKinds.builtin(sig.typeName()));
    }

    // --- the kinds stay distinct -----------------------------------------

    @Test
    void method_action_conduit_carryDistinctKinds_inOneBlock() throws Exception {
        IrSort.Trait trait = traitOf(parse("""
                trait Mixed{
                  compute:[Method(Int):Int],
                  onKey:[Action(e:KeyPress):_],
                  tick:[Conduit(e:Tick,s:Int):Int]
                }
                """));
        assertEquals(3, trait.methods().size());
        assertEquals(CallKinds.Kind.FUNCTION,
                CallKinds.builtin(trait.methods().get("compute").typeName()));
        assertEquals(CallKinds.Kind.ACTION,
                CallKinds.builtin(trait.methods().get("onKey").typeName()));
        assertEquals(CallKinds.Kind.CONDUIT,
                CallKinds.builtin(trait.methods().get("tick").typeName()));
    }

    // --- a genuine data attribute is still an attribute ------------------

    @Test
    void dataAttribute_stillClassifiedAsAttribute() throws Exception {
        IrSort.Trait trait = traitOf(parse("trait Boxed{area:[Int:@>0]}"));
        assertTrue(trait.attributes().containsKey("area"));
        assertFalse(trait.methods().containsKey("area"));
    }

    // --- method / [Method(…)] equivalence (regression anchor) ------------

    @Test
    void methodSugar_andMemberForm_produceEquivalentContract() throws Exception {
        // Impl-method sugar form (name(params):Ret) and the explicit member-sort form
        // ([Method(params):Ret]) must yield the same contract CallSig.
        IrSort.CallSig sugar = traitOf(parse("trait A{bar(x:Int):Int}")).methods().get("bar");
        IrSort.CallSig explicit = traitOf(parse("trait B{bar:[Method(x:Int):Int]}")).methods().get("bar");
        assertEquals(explicit.typeName(), sugar.typeName());
        // Structurally identical modulo source origin (the two forms sit at different columns).
        assertEquals(sortName(explicit.returnSort()), sortName(sugar.returnSort()));
        assertEquals(explicit.paramSorts().size(), sugar.paramSorts().size());
        for (int i = 0; i < explicit.paramSorts().size(); i++) {
            assertEquals(sortName(explicit.paramSorts().get(i)), sortName(sugar.paramSorts().get(i)));
        }
        assertEquals(CallKinds.Kind.FUNCTION, CallKinds.builtin(sugar.typeName()));
    }
}
