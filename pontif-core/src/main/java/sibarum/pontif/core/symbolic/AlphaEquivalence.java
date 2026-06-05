package sibarum.pontif.core.symbolic;

import sibarum.pontif.core.types.Sort;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class AlphaEquivalence {

    private AlphaEquivalence() {}

    public static boolean equivalent(SymExpr a, SymExpr b) {
        return walk(a, b, List.of(), List.of());
    }

    private static boolean walk(SymExpr a, SymExpr b, List<String> aStack, List<String> bStack) {
        return switch (a) {
            case SymExpr.Var av -> {
                if (!(b instanceof SymExpr.Var bv)) yield false;
                int aIdx = aStack.indexOf(av.name());
                int bIdx = bStack.indexOf(bv.name());
                if (aIdx == -1 && bIdx == -1) {
                    yield av.name().equals(bv.name());
                }
                yield aIdx == bIdx;
            }
            case SymExpr.Lit al -> b instanceof SymExpr.Lit bl && al.value() == bl.value();
            case SymExpr.Frac af -> b instanceof SymExpr.Frac bf
                    && af.num() == bf.num() && af.denom() == bf.denom();
            case SymExpr.Dec ad -> b instanceof SymExpr.Dec bd
                    && ad.value().compareTo(bd.value()) == 0;
            case SymExpr.Chr ac -> b instanceof SymExpr.Chr bc
                    && ac.codePoint() == bc.codePoint();
            case SymExpr.DispatchRef ad -> b instanceof SymExpr.DispatchRef bd
                    && ad.equals(bd);
            case SymExpr.Bool ab -> b instanceof SymExpr.Bool bb && ab.value() == bb.value();
            case SymExpr.Self as -> b instanceof SymExpr.Self;
            case SymExpr.Add(SymExpr al, SymExpr ar) -> {
                if (!(b instanceof SymExpr.Add(SymExpr bl, SymExpr br))) yield false;
                yield walk(al, bl, aStack, bStack) && walk(ar, br, aStack, bStack);
            }
            case SymExpr.Mul(SymExpr al, SymExpr ar) -> {
                if (!(b instanceof SymExpr.Mul(SymExpr bl, SymExpr br))) yield false;
                yield walk(al, bl, aStack, bStack) && walk(ar, br, aStack, bStack);
            }
            case SymExpr.Pow(SymExpr ab2, SymExpr ae) -> {
                if (!(b instanceof SymExpr.Pow(SymExpr bb, SymExpr be))) yield false;
                yield walk(ab2, bb, aStack, bStack) && walk(ae, be, aStack, bStack);
            }
            case SymExpr.Cmp(SymExpr al, SymExpr.CmpOp aop, SymExpr ar) -> {
                if (!(b instanceof SymExpr.Cmp(SymExpr bl, SymExpr.CmpOp bop, SymExpr br))) yield false;
                yield aop == bop && walk(al, bl, aStack, bStack) && walk(ar, br, aStack, bStack);
            }
            case SymExpr.And(SymExpr al, SymExpr ar) -> {
                if (!(b instanceof SymExpr.And(SymExpr bl, SymExpr br))) yield false;
                yield walk(al, bl, aStack, bStack) && walk(ar, br, aStack, bStack);
            }
            case SymExpr.Or(SymExpr al, SymExpr ar) -> {
                if (!(b instanceof SymExpr.Or(SymExpr bl, SymExpr br))) yield false;
                yield walk(al, bl, aStack, bStack) && walk(ar, br, aStack, bStack);
            }
            case SymExpr.Lam(String ap, Sort apt, SymExpr abody) -> {
                if (!(b instanceof SymExpr.Lam(String bp, Sort bpt, SymExpr bbody))) yield false;
                List<String> newA = new ArrayList<>(aStack.size() + 1);
                newA.add(ap);
                newA.addAll(aStack);
                List<String> newB = new ArrayList<>(bStack.size() + 1);
                newB.add(bp);
                newB.addAll(bStack);
                yield walk(abody, bbody, newA, newB);
            }
            case SymExpr.App(SymExpr afn, SymExpr aarg) -> {
                if (!(b instanceof SymExpr.App(SymExpr bfn, SymExpr barg))) yield false;
                yield walk(afn, bfn, aStack, bStack) && walk(aarg, barg, aStack, bStack);
            }
            case SymExpr.Case(SymExpr aScrut, List<SymExpr.CaseBranch> aBr) -> {
                if (!(b instanceof SymExpr.Case(SymExpr bScrut, List<SymExpr.CaseBranch> bBr))) yield false;
                if (aBr.size() != bBr.size()) yield false;
                if (!walk(aScrut, bScrut, aStack, bStack)) yield false;
                boolean allMatch = true;
                for (int i = 0; i < aBr.size(); i++) {
                    SymExpr.CaseBranch ab2 = aBr.get(i);
                    SymExpr.CaseBranch bb = bBr.get(i);
                    if (!ab2.pattern().equals(bb.pattern())) { allMatch = false; break; }
                    if (!walk(ab2.result(), bb.result(), aStack, bStack)) { allMatch = false; break; }
                }
                yield allMatch;
            }
            case SymExpr.Record(Map<String, SymExpr> aMembers, String aTypeName) -> {
                if (!(b instanceof SymExpr.Record(Map<String, SymExpr> bMembers, String bTypeName))) yield false;
                if (!aMembers.keySet().equals(bMembers.keySet())) yield false;
                // Alpha-equivalence is structural — typeName is dispatch
                // metadata, not part of structural equivalence. Two records
                // with the same members but different type names are still
                // alpha-equivalent at this level.
                boolean allMatch = true;
                for (Map.Entry<String, SymExpr> e : aMembers.entrySet()) {
                    if (!walk(e.getValue(), bMembers.get(e.getKey()), aStack, bStack)) {
                        allMatch = false;
                        break;
                    }
                }
                yield allMatch;
            }
            case SymExpr.FieldAccess(SymExpr aBase, String aName) -> {
                if (!(b instanceof SymExpr.FieldAccess(SymExpr bBase, String bName))) yield false;
                yield aName.equals(bName) && walk(aBase, bBase, aStack, bStack);
            }
        };
    }
}
