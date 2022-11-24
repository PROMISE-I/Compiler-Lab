import org.antlr.v4.runtime.RuleContext;
import org.antlr.v4.runtime.tree.RuleNode;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.HashMap;
import java.util.Map;

/**
 * @author WFS
 * @date 2022/11/24 11:21
 */
public class HighLightVisitor extends SysYParserBaseVisitor<Void>{
    private static final Map<String, String> terminalNode = new HashMap<>();

    static {
        //保留字
        terminalNode.put("CONST", "orange");
        terminalNode.put("INT", "orange");
        terminalNode.put("VOID", "orange");
        terminalNode.put("IF", "orange");
        terminalNode.put("ELSE", "orange");
        terminalNode.put("WHILE", "orange");
        terminalNode.put("BREAK", "orange");
        terminalNode.put("CONTINUE", "orange");
        terminalNode.put("RETURN", "orange");
        //运算符
        terminalNode.put("PLUS", "blue");
        terminalNode.put("MINUS", "blue");
        terminalNode.put("MUL", "blue");
        terminalNode.put("DIV", "blue");
        terminalNode.put("MOD", "blue");
        terminalNode.put("ASSIGN", "blue");
        terminalNode.put("EQ", "blue");
        terminalNode.put("NEQ", "blue");
        terminalNode.put("LT", "blue");
        terminalNode.put("GT", "blue");
        terminalNode.put("LE", "blue");
        terminalNode.put("GE", "blue");
        terminalNode.put("NOT", "blue");
        terminalNode.put("AND", "blue");
        terminalNode.put("OR", "blue");
        //标识符
        terminalNode.put("IDENT", "red");
        //数字与字符串
        terminalNode.put("INTEGR_CONST", "green");
    }

    @Override
    public Void visitChildren(RuleNode node) {
        RuleContext ruleContext = node.getRuleContext();
        int depth = ruleContext.depth();
        String ruleName = SysYParser.ruleNames[ruleContext.getRuleIndex()];
        for (int i = 1; i < depth; i++) System.err.print("  ");
        System.err.println(ruleName.substring(0, 1).toUpperCase() + ruleName.substring(1));
        return super.visitChildren(node);
    }

    @Override
    public Void visitTerminal(TerminalNode node) {
        RuleNode parent = (RuleNode) node.getParent();
        int depth = parent.getRuleContext().depth() + 1;

        String text = node.getSymbol().getText();
        int typeIndex = node.getSymbol().getType();
        if (typeIndex > 0) {
            String type = SysYLexer.ruleNames[typeIndex - 1];
            if (terminalNode.containsKey(type)) {
                if (type.equals("INTEGR_CONST"))    text = getDecimal(text);
                for (int i = 1; i < depth; i++) {
                    System.err.print("  ");
                }
                System.err.println(text + " " + type + "[" + terminalNode.get(type) + "]");
            }
        }
        return super.visitTerminal(node);
    }

    static String getDecimal(String text) {
        if (text.startsWith("0x") || text.startsWith("0X")) {
            text = String.valueOf(Integer.parseInt(text.substring(2), 16));
        } else if (text.startsWith("0") && text.length() > 1) {
            text = String.valueOf(Integer.parseInt(text, 8));
        } else {
            text = String.valueOf(Integer.parseInt(text, 10));
        }
        return text;
    }
}
