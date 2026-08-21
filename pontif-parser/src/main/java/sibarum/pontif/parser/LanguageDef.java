package sibarum.pontif.parser;

import sibarum.pontif.ir.IrExpr;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Surface-syntax configuration for {@link SexprParser}. Every keyword and operator
 * symbol the parser recognizes lives here. Construct the standard set via
 * {@link #defaults()} and override individual fields with the {@code with*}
 * helpers to spell-the-language differently without touching the parser itself.
 */
public record LanguageDef(
        String moduleKeyword,
        String functionDeclKeyword,
        String typeAliasKeyword,
        String letKeyword,
        String callKeyword,
        String matchKeyword,
        String lambdaKeyword,
        String recordKeyword,
        String fieldKeyword,
        String refinedSortKeyword,
        String functionSortKeyword,
        String structSortKeyword,
        String interfaceKeyword,
        String implKeyword,
        String trueLiteral,
        String falseLiteral,
        String selfReference,
        Map<String, IrExpr.Op> binaryOps
) {

    public LanguageDef {
        if (binaryOps == null) {
            throw new IllegalArgumentException("binaryOps must be non-null");
        }
        binaryOps = Map.copyOf(binaryOps);
    }

    public static LanguageDef defaults() {
        Map<String, IrExpr.Op> ops = new LinkedHashMap<>();
        ops.put("+", IrExpr.Op.ADD);
        ops.put("-", IrExpr.Op.SUB);
        ops.put("*", IrExpr.Op.MUL);
        ops.put("<", IrExpr.Op.LT);
        ops.put("<=", IrExpr.Op.LE);
        ops.put(">", IrExpr.Op.GT);
        ops.put(">=", IrExpr.Op.GE);
        ops.put("==", IrExpr.Op.EQ);
        ops.put("!=", IrExpr.Op.NE);
        ops.put("&&", IrExpr.Op.AND);
        ops.put("||", IrExpr.Op.OR);
        return new LanguageDef(
                "module", "defn", "deftype",
                "let", "call", "match", "lambda", "record", "field",
                "refined", "function", "struct",
                "interface", "impl",
                "true", "false", "self",
                ops);
    }

    // --- Lookup helpers ---

    public boolean isBinaryOp(String text) {
        return binaryOps.containsKey(text);
    }

    public Optional<IrExpr.Op> binaryOpFor(String text) {
        return Optional.ofNullable(binaryOps.get(text));
    }

    /**
     * A symbol is reserved if it would otherwise be interpreted as a form keyword,
     * a literal, or a binary operator. Reserved words may not be used as variable
     * names.
     */
    public boolean isReserved(String text) {
        return text.equals(moduleKeyword)
                || text.equals(functionDeclKeyword)
                || text.equals(typeAliasKeyword)
                || text.equals(letKeyword)
                || text.equals(callKeyword)
                || text.equals(matchKeyword)
                || text.equals(lambdaKeyword)
                || text.equals(recordKeyword)
                || text.equals(fieldKeyword)
                || text.equals(refinedSortKeyword)
                || text.equals(functionSortKeyword)
                || text.equals(structSortKeyword)
                || text.equals(interfaceKeyword)
                || text.equals(implKeyword)
                || text.equals(trueLiteral)
                || text.equals(falseLiteral)
                || text.equals(selfReference)
                || isBinaryOp(text);
    }

    // --- Single-field overrides ---
    // Verbose by design: each override is one line, making it easy to spot exactly
    // which knob a customization turns. Constructor argument order is fixed; the
    // canonical record fields keep their order; only the named field varies.

    public LanguageDef withModuleKeyword(String s) {
        return new LanguageDef(s, functionDeclKeyword, typeAliasKeyword, letKeyword, callKeyword, matchKeyword, lambdaKeyword, recordKeyword, fieldKeyword, refinedSortKeyword, functionSortKeyword, structSortKeyword, interfaceKeyword, implKeyword, trueLiteral, falseLiteral, selfReference, binaryOps);
    }

    public LanguageDef withFunctionDeclKeyword(String s) {
        return new LanguageDef(moduleKeyword, s, typeAliasKeyword, letKeyword, callKeyword, matchKeyword, lambdaKeyword, recordKeyword, fieldKeyword, refinedSortKeyword, functionSortKeyword, structSortKeyword, interfaceKeyword, implKeyword, trueLiteral, falseLiteral, selfReference, binaryOps);
    }

    public LanguageDef withTypeAliasKeyword(String s) {
        return new LanguageDef(moduleKeyword, functionDeclKeyword, s, letKeyword, callKeyword, matchKeyword, lambdaKeyword, recordKeyword, fieldKeyword, refinedSortKeyword, functionSortKeyword, structSortKeyword, interfaceKeyword, implKeyword, trueLiteral, falseLiteral, selfReference, binaryOps);
    }

    public LanguageDef withLetKeyword(String s) {
        return new LanguageDef(moduleKeyword, functionDeclKeyword, typeAliasKeyword, s, callKeyword, matchKeyword, lambdaKeyword, recordKeyword, fieldKeyword, refinedSortKeyword, functionSortKeyword, structSortKeyword, interfaceKeyword, implKeyword, trueLiteral, falseLiteral, selfReference, binaryOps);
    }

    public LanguageDef withCallKeyword(String s) {
        return new LanguageDef(moduleKeyword, functionDeclKeyword, typeAliasKeyword, letKeyword, s, matchKeyword, lambdaKeyword, recordKeyword, fieldKeyword, refinedSortKeyword, functionSortKeyword, structSortKeyword, interfaceKeyword, implKeyword, trueLiteral, falseLiteral, selfReference, binaryOps);
    }

    public LanguageDef withMatchKeyword(String s) {
        return new LanguageDef(moduleKeyword, functionDeclKeyword, typeAliasKeyword, letKeyword, callKeyword, s, lambdaKeyword, recordKeyword, fieldKeyword, refinedSortKeyword, functionSortKeyword, structSortKeyword, interfaceKeyword, implKeyword, trueLiteral, falseLiteral, selfReference, binaryOps);
    }

    public LanguageDef withLambdaKeyword(String s) {
        return new LanguageDef(moduleKeyword, functionDeclKeyword, typeAliasKeyword, letKeyword, callKeyword, matchKeyword, s, recordKeyword, fieldKeyword, refinedSortKeyword, functionSortKeyword, structSortKeyword, interfaceKeyword, implKeyword, trueLiteral, falseLiteral, selfReference, binaryOps);
    }

    public LanguageDef withRecordKeyword(String s) {
        return new LanguageDef(moduleKeyword, functionDeclKeyword, typeAliasKeyword, letKeyword, callKeyword, matchKeyword, lambdaKeyword, s, fieldKeyword, refinedSortKeyword, functionSortKeyword, structSortKeyword, interfaceKeyword, implKeyword, trueLiteral, falseLiteral, selfReference, binaryOps);
    }

    public LanguageDef withFieldKeyword(String s) {
        return new LanguageDef(moduleKeyword, functionDeclKeyword, typeAliasKeyword, letKeyword, callKeyword, matchKeyword, lambdaKeyword, recordKeyword, s, refinedSortKeyword, functionSortKeyword, structSortKeyword, interfaceKeyword, implKeyword, trueLiteral, falseLiteral, selfReference, binaryOps);
    }

    public LanguageDef withRefinedSortKeyword(String s) {
        return new LanguageDef(moduleKeyword, functionDeclKeyword, typeAliasKeyword, letKeyword, callKeyword, matchKeyword, lambdaKeyword, recordKeyword, fieldKeyword, s, functionSortKeyword, structSortKeyword, interfaceKeyword, implKeyword, trueLiteral, falseLiteral, selfReference, binaryOps);
    }

    public LanguageDef withFunctionSortKeyword(String s) {
        return new LanguageDef(moduleKeyword, functionDeclKeyword, typeAliasKeyword, letKeyword, callKeyword, matchKeyword, lambdaKeyword, recordKeyword, fieldKeyword, refinedSortKeyword, s, structSortKeyword, interfaceKeyword, implKeyword, trueLiteral, falseLiteral, selfReference, binaryOps);
    }

    public LanguageDef withStructSortKeyword(String s) {
        return new LanguageDef(moduleKeyword, functionDeclKeyword, typeAliasKeyword, letKeyword, callKeyword, matchKeyword, lambdaKeyword, recordKeyword, fieldKeyword, refinedSortKeyword, functionSortKeyword, s, interfaceKeyword, implKeyword, trueLiteral, falseLiteral, selfReference, binaryOps);
    }

    public LanguageDef withInterfaceKeyword(String s) {
        return new LanguageDef(moduleKeyword, functionDeclKeyword, typeAliasKeyword, letKeyword, callKeyword, matchKeyword, lambdaKeyword, recordKeyword, fieldKeyword, refinedSortKeyword, functionSortKeyword, structSortKeyword, s, implKeyword, trueLiteral, falseLiteral, selfReference, binaryOps);
    }

    public LanguageDef withImplKeyword(String s) {
        return new LanguageDef(moduleKeyword, functionDeclKeyword, typeAliasKeyword, letKeyword, callKeyword, matchKeyword, lambdaKeyword, recordKeyword, fieldKeyword, refinedSortKeyword, functionSortKeyword, structSortKeyword, interfaceKeyword, s, trueLiteral, falseLiteral, selfReference, binaryOps);
    }

    public LanguageDef withTrueLiteral(String s) {
        return new LanguageDef(moduleKeyword, functionDeclKeyword, typeAliasKeyword, letKeyword, callKeyword, matchKeyword, lambdaKeyword, recordKeyword, fieldKeyword, refinedSortKeyword, functionSortKeyword, structSortKeyword, interfaceKeyword, implKeyword, s, falseLiteral, selfReference, binaryOps);
    }

    public LanguageDef withFalseLiteral(String s) {
        return new LanguageDef(moduleKeyword, functionDeclKeyword, typeAliasKeyword, letKeyword, callKeyword, matchKeyword, lambdaKeyword, recordKeyword, fieldKeyword, refinedSortKeyword, functionSortKeyword, structSortKeyword, interfaceKeyword, implKeyword, trueLiteral, s, selfReference, binaryOps);
    }

    public LanguageDef withSelfReference(String s) {
        return new LanguageDef(moduleKeyword, functionDeclKeyword, typeAliasKeyword, letKeyword, callKeyword, matchKeyword, lambdaKeyword, recordKeyword, fieldKeyword, refinedSortKeyword, functionSortKeyword, structSortKeyword, interfaceKeyword, implKeyword, trueLiteral, falseLiteral, s, binaryOps);
    }

    public LanguageDef withBinaryOps(Map<String, IrExpr.Op> ops) {
        return new LanguageDef(moduleKeyword, functionDeclKeyword, typeAliasKeyword, letKeyword, callKeyword, matchKeyword, lambdaKeyword, recordKeyword, fieldKeyword, refinedSortKeyword, functionSortKeyword, structSortKeyword, interfaceKeyword, implKeyword, trueLiteral, falseLiteral, selfReference, ops);
    }

    /**
     * Replace a single binary operator's spelling. {@code from} need not be a
     * default operator; if absent, the result simply adds the new spelling.
     */
    public LanguageDef withRenamedBinaryOp(String from, String to) {
        Map<String, IrExpr.Op> updated = new LinkedHashMap<>(binaryOps);
        IrExpr.Op kind = updated.remove(from);
        if (kind == null) {
            throw new IllegalArgumentException(
                    "Cannot rename binary operator '" + from + "': not defined in this LanguageDef");
        }
        updated.put(to, kind);
        return withBinaryOps(updated);
    }
}
