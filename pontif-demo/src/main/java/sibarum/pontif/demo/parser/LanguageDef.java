package sibarum.pontif.demo.parser;

import sibarum.pontif.ir.IrExpr;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Surface-syntax configuration for {@link Parser}. Every keyword and operator
 * symbol the parser recognizes lives here. Construct the standard set via
 * {@link #defaults()} and override individual fields with the {@code with*}
 * helpers to spell-the-language differently without touching the parser itself.
 */
public record LanguageDef(
        String moduleKeyword,
        String functionDeclKeyword,
        String letKeyword,
        String callKeyword,
        String matchKeyword,
        String refinedSortKeyword,
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
        return new LanguageDef(
                "module", "defn", "let", "call", "match", "refined",
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
                || text.equals(letKeyword)
                || text.equals(callKeyword)
                || text.equals(matchKeyword)
                || text.equals(refinedSortKeyword)
                || text.equals(trueLiteral)
                || text.equals(falseLiteral)
                || text.equals(selfReference)
                || isBinaryOp(text);
    }

    // --- Single-field overrides ---
    // Verbose by design: each override is one line, making it easy to spot exactly
    // which knob a customization turns.

    public LanguageDef withModuleKeyword(String s) {
        return new LanguageDef(s, functionDeclKeyword, letKeyword, callKeyword, matchKeyword, refinedSortKeyword, trueLiteral, falseLiteral, selfReference, binaryOps);
    }

    public LanguageDef withFunctionDeclKeyword(String s) {
        return new LanguageDef(moduleKeyword, s, letKeyword, callKeyword, matchKeyword, refinedSortKeyword, trueLiteral, falseLiteral, selfReference, binaryOps);
    }

    public LanguageDef withLetKeyword(String s) {
        return new LanguageDef(moduleKeyword, functionDeclKeyword, s, callKeyword, matchKeyword, refinedSortKeyword, trueLiteral, falseLiteral, selfReference, binaryOps);
    }

    public LanguageDef withCallKeyword(String s) {
        return new LanguageDef(moduleKeyword, functionDeclKeyword, letKeyword, s, matchKeyword, refinedSortKeyword, trueLiteral, falseLiteral, selfReference, binaryOps);
    }

    public LanguageDef withMatchKeyword(String s) {
        return new LanguageDef(moduleKeyword, functionDeclKeyword, letKeyword, callKeyword, s, refinedSortKeyword, trueLiteral, falseLiteral, selfReference, binaryOps);
    }

    public LanguageDef withRefinedSortKeyword(String s) {
        return new LanguageDef(moduleKeyword, functionDeclKeyword, letKeyword, callKeyword, matchKeyword, s, trueLiteral, falseLiteral, selfReference, binaryOps);
    }

    public LanguageDef withTrueLiteral(String s) {
        return new LanguageDef(moduleKeyword, functionDeclKeyword, letKeyword, callKeyword, matchKeyword, refinedSortKeyword, s, falseLiteral, selfReference, binaryOps);
    }

    public LanguageDef withFalseLiteral(String s) {
        return new LanguageDef(moduleKeyword, functionDeclKeyword, letKeyword, callKeyword, matchKeyword, refinedSortKeyword, trueLiteral, s, selfReference, binaryOps);
    }

    public LanguageDef withSelfReference(String s) {
        return new LanguageDef(moduleKeyword, functionDeclKeyword, letKeyword, callKeyword, matchKeyword, refinedSortKeyword, trueLiteral, falseLiteral, s, binaryOps);
    }

    public LanguageDef withBinaryOps(Map<String, IrExpr.Op> ops) {
        return new LanguageDef(moduleKeyword, functionDeclKeyword, letKeyword, callKeyword, matchKeyword, refinedSortKeyword, trueLiteral, falseLiteral, selfReference, ops);
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
