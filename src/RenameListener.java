import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.RuleNode;
import org.antlr.v4.runtime.tree.TerminalNode;
import symtable.scope.GlobalScope;
import symtable.scope.LocalScope;
import symtable.scope.Scope;
import symtable.symbol.FunctionSymbol;
import symtable.symbol.Symbol;
import symtable.symbol.VariableSymbol;
import symtable.type.FunctionType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author WFS
 * @date 2022/12/17 23:15
 */
public class RenameListener extends SysYParserBaseListener {
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

    Symbol targetSymbol;

    String replacingIdentity;

    private GlobalScope globalScope = null;

    private Scope currentScope = null;

    private List<LocalScope> localScopeList = null;

    private int localScopeCounter;

    public RenameListener(Symbol targetSymbol, String replacingIdentity, GlobalScope globalScope, List<LocalScope> localScopeList) {
        this.targetSymbol = targetSymbol;
        this.replacingIdentity = replacingIdentity;
        this.globalScope = globalScope;
        this.localScopeList = localScopeList;
        this.currentScope = globalScope;
        this.localScopeCounter = 0;
    }

    @Override
    public void enterEveryRule(ParserRuleContext ctx) {
        int depth = ctx.depth();
        String ruleName = SysYParser.ruleNames[ctx.getRuleIndex()];
        for (int i = 1; i < depth; i++) System.err.print("  ");
        System.err.println(ruleName.substring(0, 1).toUpperCase() + ruleName.substring(1));
    }

    @Override
    public void visitTerminal(TerminalNode node) {
        RuleNode parent = (RuleNode) node.getParent();
        int depth = parent.getRuleContext().depth() + 1;

        String text = node.getSymbol().getText();
        if (targetSymbol != null){
            Symbol symbol;
            if (parent instanceof SysYParser.FuncDefContext ||
                parent instanceof SysYParser.CallExpContext) {
                symbol = currentScope.resolve(text, FunctionSymbol.class);
            } else {
                symbol = currentScope.resolve(text, VariableSymbol.class);
            }
            symbol = currentScope.resolve(text);
            if (targetSymbol.equals(symbol)) {
                text = replacingIdentity;
            }
        }

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
    }


    /* enter scope */
    @Override
    public void enterFuncDef(SysYParser.FuncDefContext ctx) {
        String funcName =ctx.IDENT().getText();
        FunctionSymbol functionSymbol = (FunctionSymbol) globalScope.resolve(funcName, FunctionSymbol.class);
        FunctionType functionType = (FunctionType) functionSymbol.getType();
        currentScope = functionType.getFunctionScope();
    }

    @Override
    public void enterBlock(SysYParser.BlockContext ctx) {
        currentScope = localScopeList.get(localScopeCounter);
        localScopeCounter++;
    }

    @Override
    public void exitFuncDef(SysYParser.FuncDefContext ctx) {
        currentScope = currentScope.getEnclosingScope();
    }

    @Override
    public void exitBlock(SysYParser.BlockContext ctx) {
        currentScope = currentScope.getEnclosingScope();
    }

    public static String getDecimal(String text) {
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
