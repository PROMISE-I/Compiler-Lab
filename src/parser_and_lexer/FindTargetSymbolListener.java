package parser_and_lexer;

import org.antlr.v4.runtime.tree.TerminalNode;
import symtable.scope.GlobalScope;
import symtable.scope.LocalScope;
import symtable.scope.Scope;
import symtable.symbol.FunctionSymbol;
import symtable.symbol.Symbol;
import symtable.type.FunctionType;

import java.util.List;


/**
 * @author WFS
 * @date 2022/12/17 22:48
 */
public class FindTargetSymbolListener extends SysYParserBaseListener{
    int lineNumber;

    int rowNumber;

    private GlobalScope globalScope = null;

    private Scope currentScope = null;

    private List<LocalScope> localScopeList = null;

    private int localScopeCounter;

    Symbol targetSymbol;

    public FindTargetSymbolListener(int lineNumber, int rowNumber, GlobalScope globalScope, List<LocalScope> localScopeList) {
        this.lineNumber = lineNumber;
        this.rowNumber = rowNumber;
        this.globalScope = globalScope;
        this.localScopeList = localScopeList;
        this.currentScope = globalScope;
        this.localScopeCounter = 0;
    }

    @Override
    public void visitTerminal(TerminalNode node) {
        int lineNumber = node.getSymbol().getLine();
        int rowNumber = node.getSymbol().getCharPositionInLine();

        if (lineNumber == this.lineNumber && rowNumber == this.rowNumber) {
            String text = node.getSymbol().getText();
            this.targetSymbol = currentScope.resolve(text);
        }
    }

    /* enter scope */
    @Override
    public void enterFuncDef(SysYParser.FuncDefContext ctx) {
        String funcName =ctx.IDENT().getText();
        FunctionSymbol functionSymbol = (FunctionSymbol) globalScope.resolve(funcName);
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

    public Symbol getTargetSymbol() {
        return targetSymbol;
    }
}
