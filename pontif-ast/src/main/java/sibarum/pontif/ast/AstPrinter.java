package sibarum.pontif.ast;

import sibarum.pontif.ast.bind.Var;
import sibarum.pontif.ast.literal.Bool;
import sibarum.pontif.ast.literal.CharLiteral;
import sibarum.pontif.ast.literal.DecimalLiteral;
import sibarum.pontif.ast.literal.IntLiteral;
import sibarum.pontif.ast.literal.StringLiteral;
import sibarum.pontif.ast.record.FieldAccessNode;
import sibarum.pontif.core.Origin;
import sibarum.pontif.core.PontifNode;

/**
 * Readable, indented dump of a Truffle execution-AST subtree — the node tree
 * produced by lowering. Each node is one line tagged with its kind (and, for
 * the value-carrying leaves, its value) followed by its source origin; the
 * {@code children()} of every node indent two spaces beneath it. Structural,
 * never throws. Used by the Playground's IR/AST inspector tab.
 */
public final class AstPrinter {

    private static final String INDENT = "  ";

    private AstPrinter() {}

    public static String print(PontifNode node) {
        StringBuilder sb = new StringBuilder();
        print(sb, node, 0);
        return sb.toString();
    }

    private static void print(StringBuilder sb, PontifNode node, int depth) {
        sb.append(INDENT.repeat(depth)).append(label(node)).append(at(node.origin())).append('\n');
        for (PontifNode child : node.children()) {
            if (child != null) print(sb, child, depth + 1);
        }
    }

    /**
     * One-line label. The value-carrying leaves print their value; every other
     * node prints its class's simple name (Add, Mul, Cmp, CallNode, MatchNode,
     * …), which already reads as the node kind. {@code PontifNode} is not a
     * sealed type, so this is an instanceof chain rather than a switch.
     */
    private static String label(PontifNode n) {
        if (n instanceof IntLiteral l)      return "Int " + l.value();
        if (n instanceof DecimalLiteral d)  return "Decimal " + d.value().toPlainString();
        if (n instanceof CharLiteral c)     return "Char " + c.value();
        if (n instanceof StringLiteral s)   return "String " + s.value();
        if (n instanceof Bool b)            return "Bool " + b.value();
        if (n instanceof Var v)             return "Var " + v.name();
        if (n instanceof FieldAccessNode f) return "FieldAccess ." + f.fieldName();
        return n.getClass().getSimpleName();
    }

    private static String at(Origin o) {
        return (o != null && o.isPresent()) ? " @" + o.span().start() : "";
    }
}
