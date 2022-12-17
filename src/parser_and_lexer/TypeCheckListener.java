package parser_and_lexer;

import symtable.scope.FunctionScope;
import symtable.scope.GlobalScope;
import symtable.scope.LocalScope;
import symtable.scope.Scope;
import symtable.symbol.BasicTypeSymbol;
import symtable.symbol.FunctionSymbol;
import symtable.symbol.Symbol;
import symtable.symbol.VariableSymbol;
import symtable.type.ArrayType;
import symtable.type.BaseType;
import symtable.type.FunctionType;
import symtable.type.Type;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static parser_and_lexer.TypeCheckListener.ErrorType.*;

/**
 * @author WFS
 * @date 2022/12/17 12:48
 */
public class TypeCheckListener extends SysYParserBaseListener{
    enum ErrorType {
        UNKNOWN_BASIC_TYPE,
        UNDEFINED_VAR, UNDEFINED_FUNC,
        REDEFINED_VAR, REDEFINED_FUNC,
        ASSIGN_TYPE_MISMATCH, OPERATION_TYPE_MISMATCH, RETURN_TYPE_MISMATCH, FUNC_PARAM_TYPE_MISMATCH,
        NOT_ARRAY, NOT_FUNC, NOT_LEFT_VALUE
    }

    public static Map<ErrorType, Integer> errorTypeMap = new HashMap<>();
    public static Map<ErrorType, String> errorTypeBaseMsg = new HashMap<>();

    static {
        /* initialize  errorTypeMap*/
        errorTypeMap.put(UNKNOWN_BASIC_TYPE, 0);
        errorTypeMap.put(UNDEFINED_VAR, 1);
        errorTypeMap.put(UNDEFINED_FUNC, 2);
        errorTypeMap.put(REDEFINED_VAR, 3);
        errorTypeMap.put(REDEFINED_FUNC, 4);
        errorTypeMap.put(ASSIGN_TYPE_MISMATCH, 5);
        errorTypeMap.put(OPERATION_TYPE_MISMATCH, 6);
        errorTypeMap.put(RETURN_TYPE_MISMATCH, 7);
        errorTypeMap.put(FUNC_PARAM_TYPE_MISMATCH, 8);
        errorTypeMap.put(NOT_ARRAY, 9);
        errorTypeMap.put(NOT_FUNC, 10);
        errorTypeMap.put(NOT_LEFT_VALUE, 11);

        /* initialize  errorTypeBaseMsg*/
        errorTypeBaseMsg.put(UNKNOWN_BASIC_TYPE, "unknown basic type: ");
        errorTypeBaseMsg.put(UNDEFINED_VAR, "undefined variable: ");
        errorTypeBaseMsg.put(UNDEFINED_FUNC, "undefined function: ");
        errorTypeBaseMsg.put(REDEFINED_VAR, "redefined variable: ");
        errorTypeBaseMsg.put(REDEFINED_FUNC, "redefined function: ");
        errorTypeBaseMsg.put(ASSIGN_TYPE_MISMATCH, "assign type mismatch: ");
        errorTypeBaseMsg.put(OPERATION_TYPE_MISMATCH, "operation type mismatch: ");
        errorTypeBaseMsg.put(RETURN_TYPE_MISMATCH, "return type mismatch: ");
        errorTypeBaseMsg.put(FUNC_PARAM_TYPE_MISMATCH, "function parameter type mismatch: ");
        errorTypeBaseMsg.put(NOT_ARRAY, "not a array: ");
        errorTypeBaseMsg.put(NOT_FUNC, "not a function: ");
        errorTypeBaseMsg.put(NOT_LEFT_VALUE, "not left value: ");
    }

    private GlobalScope globalScope = null;

    private Scope currentScope = null;

    private int localScopeCounter = 0;

    public boolean hasError = false;

    /* enter scope */
    @Override
    public void enterProgram(SysYParser.ProgramContext ctx) {
        globalScope = new GlobalScope(null);
        currentScope = globalScope;
    }

    @Override
    public void enterFuncDef(SysYParser.FuncDefContext ctx) {
        String returnTypeName =ctx.funcType().getText();
        Symbol returnSymbol = globalScope.resolve(returnTypeName, BasicTypeSymbol.class);
        if (returnSymbol != null) {
            String funcName =ctx.IDENT().getText();
            Symbol resolveSymbol = globalScope.resolve(funcName, FunctionSymbol.class);

            FunctionScope functionScope = new FunctionScope(funcName, currentScope);
            FunctionType functionType = new FunctionType(functionScope, returnSymbol.getType());
            FunctionSymbol functionSymbol = new FunctionSymbol(functionType);
            if (resolveSymbol == null || !(resolveSymbol.getType() instanceof FunctionType)) {

                currentScope.define(functionSymbol);

                /* define param symbol */
                List<SysYParser.FuncFParamContext> funcFParamContexts = new LinkedList<>();
                if (hasParams(ctx)) funcFParamContexts.addAll(ctx.funcFParams().funcFParam());
                for (SysYParser.FuncFParamContext funcFParamContext : funcFParamContexts) {
                    defineParam(funcFParamContext);
                }
            } else {
                outputErrorMsg(REDEFINED_FUNC, ctx.getStart().getLine(), funcName);
            }
            currentScope = functionScope;
        } else {
            outputErrorMsg(UNKNOWN_BASIC_TYPE, ctx.getStart().getLine(), returnTypeName);
        }
    }

    @Override
    public void enterBlock(SysYParser.BlockContext ctx) {
        LocalScope localScope = new LocalScope(currentScope);
        String localScopeName = localScope.getName() +localScopeCounter;
        localScope.setName(localScopeName);

        localScopeCounter++;

        currentScope = localScope;
    }

    /* exit scope */
    @Override
    public void exitProgram(SysYParser.ProgramContext ctx) {
        currentScope = currentScope.getEnclosingScope();
    }

    @Override
    public void exitFuncDef(SysYParser.FuncDefContext ctx) {
        currentScope = currentScope.getEnclosingScope();
    }

    @Override
    public void exitBlock(SysYParser.BlockContext ctx) {
        currentScope = currentScope.getEnclosingScope();
    }

    /* define symbol */

    @Override
    public void enterConstDecl(SysYParser.ConstDeclContext ctx) {
        String typeName = ctx.bType().getText();
        Symbol typeSymbol = globalScope.resolve(typeName, BasicTypeSymbol.class);
        if (typeSymbol instanceof BasicTypeSymbol) {
            for (SysYParser.ConstDefContext constDef : ctx.constDef()) {
                String constName = constDef.IDENT().getText();
                // 此处如果 resolve 可以找到 FuncSymbol，还是需要定义变量的，所以只要 VariableSymbol
                Symbol resolveSymbol = currentScope.resolve(constName, VariableSymbol.class);
                if (resolveSymbol == null || !(resolveSymbol.getType() instanceof ArrayType)) {
                    List<SysYParser.ConstExpContext> constExps = new LinkedList<>();
                    if (hasBracket(constDef)) constExps.addAll(constDef.constExp());

                    VariableSymbol constSymbol = new VariableSymbol(constName, generateArray(constExps, (BaseType) typeSymbol.getType()));
                    currentScope.define(constSymbol);
                } else {
                    outputErrorMsg(REDEFINED_VAR, ctx.getStart().getLine(), constName);
                }
            }
        } else {
            outputErrorMsg(UNKNOWN_BASIC_TYPE, ctx.getStart().getLine(), typeName);
        }
    }

    @Override
    public void enterVarDecl(SysYParser.VarDeclContext ctx) {
        String typeName = ctx.bType().getText();
        Symbol typeSymbol = globalScope.resolve(typeName, BasicTypeSymbol.class);
        if (typeSymbol instanceof BasicTypeSymbol) {
            for (SysYParser.VarDefContext varDef : ctx.varDef()) {
                String varName = varDef.IDENT().getText();
                // 此处如果 resolve 可以找到 FuncSymbol，还是需要定义变量的，所以只要 VariableSymbol
                Symbol resolveSymbol = currentScope.resolve(varName, VariableSymbol.class);
                if (resolveSymbol == null || !(resolveSymbol.getType() instanceof ArrayType)) {
                    List<SysYParser.ConstExpContext> constExps = new LinkedList<>();
                    if (hasBracket(varDef)) constExps.addAll(varDef.constExp());

                    VariableSymbol variableSymbol = new VariableSymbol(varName, generateArray(constExps, (BaseType) typeSymbol.getType()));
                    currentScope.define(variableSymbol);
                } else {
                    outputErrorMsg(REDEFINED_VAR, ctx.getStart().getLine(), varName);
                }
            }
        } else {
            outputErrorMsg(UNKNOWN_BASIC_TYPE, ctx.getStart().getLine(), typeName);
        }
    }

    /* resolve symbol */

    /* check assign type is matched */

    @Override
    public void enterAssignStmt(SysYParser.AssignStmtContext ctx) {
        Type lValType = resolveLValTypeInAssignStmt(ctx.lVal());
        Type rValType = resolveExpType(ctx.exp());
        if (lValType != null && rValType != null && !lValType.equals(rValType)) {
            outputErrorMsg(ASSIGN_TYPE_MISMATCH, ctx.getStart().getLine(), "");
        }
    }

    @Override
    public void enterReturnStmt(SysYParser.ReturnStmtContext ctx) {
        Type expReturnType = resolveExpType(ctx.exp());
        FunctionType functionType = getNearestFunctionType();
        Type funcReturnType = functionType.getReturnType();
        if (funcReturnType.equals(BaseType.getTypeInt())) {
            funcReturnType = new ArrayType(0, funcReturnType);
        }
        if (expReturnType != null && !(expReturnType.equals(funcReturnType))) {
            //TODO 可能会出现在重复定义的函数中再报一次错，不知道算不算“最本质错误”？
            outputErrorMsg(RETURN_TYPE_MISMATCH, ctx.getStart().getLine(), "");
        }
    }

    @Override
    public void enterExpStmt(SysYParser.ExpStmtContext ctx) {
        resolveExpType(ctx.exp());
    }

    private boolean hasParams(SysYParser.FuncDefContext ctx) {
        return ctx.getChildCount() > 5;
    }

    private boolean hasBracket(SysYParser.ConstDefContext constDefContext) {
        return constDefContext.getChildCount() > 3;
    }

    private boolean hasBracket(SysYParser.VarDefContext varDefContext) {
        return varDefContext.getChildCount() > 1 && varDefContext.getChildCount() != 3;
    }

    private void defineParam(SysYParser.FuncFParamContext ctx) {
        String typeName = ctx.bType().getText();
        Symbol typeSymbol = globalScope.resolve(typeName, BasicTypeSymbol.class);
        if (typeSymbol instanceof BasicTypeSymbol) {
            String paraName = ctx.IDENT().getText();
            VariableSymbol variableSymbol = new VariableSymbol(paraName, resolveParaType(ctx, (BaseType) typeSymbol.getType()));
            currentScope.define(variableSymbol);
            // add param type to function type's field
            FunctionType nearestFunctionType = getNearestFunctionType();
            nearestFunctionType.addParamType(variableSymbol.getType());
        } else {
            // TODO will never reach here, need to modify
            outputErrorMsg(UNKNOWN_BASIC_TYPE, ctx.getStart().getLine(), typeName);
        }
    }

    private ArrayType generateArray(List<SysYParser.ConstExpContext> constExpContexts, BaseType type) {
        if (constExpContexts.isEmpty()) return new ArrayType(0, type);
        else {
            SysYParser.ConstExpContext constExpContext = constExpContexts.get(0);
            constExpContexts.remove(0);
            return new ArrayType(constExpContext, generateArray(constExpContexts, type)); // 这里需要修改 arrayType 的第一个参数？
        }
    }

    private ArrayType resolveParaType(SysYParser.FuncFParamContext ctx, BaseType type) {
        int indexSize = ctx.L_BRACKT().size();
        List<SysYParser.ExpContext> indexes = ctx.exp();
        if (indexSize == 0) return new ArrayType(0, type);
        else {
            ArrayType paraType = new ArrayType(indexes.get(indexSize - 1), type);
            for (int i = 1; i < indexSize - 1; i++) {
                paraType = new ArrayType(indexes.get(indexSize - 1 - i), paraType);
            }
            paraType = new ArrayType(-1, paraType);
            return paraType;
        }
    }

    private Type resolveExpType(SysYParser.ExpContext expContext) {
        if (expContext instanceof SysYParser.LValExpContext) {
            /* lVal */
            return resolveLValType(((SysYParser.LValExpContext) expContext).lVal());
        } else if (expContext instanceof SysYParser.ParenExpContext) {
            /* L_PAREN exp R_PAREN */
            SysYParser.ParenExpContext parenExpContext = (SysYParser.ParenExpContext) expContext;
            return resolveExpType(parenExpContext.exp());
        } else if (expContext instanceof SysYParser.NumberExpContext) {
            /* number */
            return new ArrayType(0, BaseType.getTypeInt());
        } else if (expContext instanceof SysYParser.CallExpContext) {
            /* IDENT L_PAREN funcRParams? R_PAREN */
            SysYParser.CallExpContext callExpContext = (SysYParser.CallExpContext) expContext;
            return resolveCallExp(callExpContext);
        } else if (expContext instanceof SysYParser.UnaryExpContext) {
            /* unaryOp exp */
            SysYParser.UnaryExpContext unaryExpContext = (SysYParser.UnaryExpContext) expContext;
            return resolveExpType(unaryExpContext.exp());
        } else if (expContext instanceof SysYParser.MulDivModExpContext) {
            /* lhs = exp (MUL | DIV | MOD) rhs = exp */
            SysYParser.MulDivModExpContext mulDivModExpContext = (SysYParser.MulDivModExpContext) expContext;
            // 此处递归调用了解析表达式类型，会对未定义的label标记，故之后不需要再outputErrorMsg
            Type lhsType = resolveExpType(mulDivModExpContext.lhs);
            Type rhsType = resolveExpType(mulDivModExpContext.rhs);
            if (lhsType != null && lhsType.equals(rhsType)) {
                return lhsType;
            } else {
                outputErrorMsg(OPERATION_TYPE_MISMATCH, mulDivModExpContext.getStart().getLine(), "");
            }
        } else {
            /* lhs = exp (PLUS | MINUS) rhs = exp */
            SysYParser.PlusMinusExpContext plusMinusExpContext = (SysYParser.PlusMinusExpContext) expContext;
            // 此处递归调用了解析表达式类型，会对未定义的label标记，故之后不需要再outputErrorMsg
            Type lhsType = resolveExpType(plusMinusExpContext.lhs);
            Type rhsType = resolveExpType(plusMinusExpContext.rhs);
            if (lhsType != null && lhsType.equals(rhsType)) {
                return lhsType;
            } else {
                outputErrorMsg(OPERATION_TYPE_MISMATCH, plusMinusExpContext.getStart().getLine(), "");
            }
        }
        return null;
    }

    /**
     * 由于 AssignStmt 等号左边的 lVal 跟其他的 lVal 不太一样，需要考虑是否有同名函数，所以单独处理，但逻辑基本一样
     * @param lValContext
     * @return
     */
    private Type resolveLValTypeInAssignStmt(SysYParser.LValContext lValContext) {
        String lValName = lValContext.IDENT().getText();
        Symbol lValSymbol = currentScope.resolve(lValName, VariableSymbol.class);
        if (lValSymbol == null) {
            lValSymbol = currentScope.resolve(lValName, FunctionSymbol.class);
            if (lValSymbol != null) {
                outputErrorMsg(NOT_LEFT_VALUE, lValContext.getStart().getLine(), lValName);
            } else {
                outputErrorMsg(UNDEFINED_VAR, lValContext.getStart().getLine(), lValName);
            }
        }  else {
            Type labelType = lValSymbol.getType();
            if (lValContext.getChildCount() > 1) {
                int indexSize = lValContext.L_BRACKT().size();
                for (int i = 0; i < indexSize; i++) {
                    // 由于 int 类型我也封装成了 ArrayType，所以我需要连续两个判断，判断是否都是 ArrayType
                    if (labelType instanceof ArrayType && ((ArrayType) labelType).getSubType() instanceof ArrayType) {
                        labelType = ((ArrayType) labelType).getSubType();
                    } else {
                        outputErrorMsg(NOT_ARRAY, lValContext.getStart().getLine(), lValName);
                        break;
                    }
                }
            }
            return labelType;
        }
        return null;
    }

    private Type resolveLValType(SysYParser.LValContext lValContext) {
        String lValName = lValContext.IDENT().getText();
        Symbol lValSymbol = currentScope.resolve(lValName, VariableSymbol.class);
        if (lValSymbol == null) {
            outputErrorMsg(UNDEFINED_VAR, lValContext.getStart().getLine(), lValName);
        }  else {
            Type labelType = lValSymbol.getType();
            if (lValContext.getChildCount() > 1) {
                int indexSize = lValContext.L_BRACKT().size();
                for (int i = 0; i < indexSize; i++) {
                    // 由于 int 类型我也封装成了 ArrayType，所以我需要连续两个判断，判断是否都是 ArrayType
                    if (labelType instanceof ArrayType && ((ArrayType) labelType).getSubType() instanceof ArrayType) {
                        labelType = ((ArrayType) labelType).getSubType();
                    } else {
                        outputErrorMsg(NOT_ARRAY, lValContext.getStart().getLine(), lValName);
                        break;
                    }
                }
            }
            return labelType;
        }
        return null;
    }

    private Type resolveCallExp(SysYParser.CallExpContext callExpContext) {
        String funcName = callExpContext.IDENT().getText();
        Symbol funcSymbol = currentScope.resolve(funcName, FunctionSymbol.class);
        /* resolve function name */
        if (funcSymbol == null) {
            funcSymbol = currentScope.resolve(funcName, VariableSymbol.class);
            if (funcSymbol != null) {
                outputErrorMsg(NOT_FUNC, callExpContext.getStart().getLine(), funcName);
            } else {
                outputErrorMsg(UNDEFINED_FUNC, callExpContext.getStart().getLine(), funcName);
            }
        } else {
            if (resolveFuncRParams(callExpContext.funcRParams())) {
                FunctionType functionType = (FunctionType) funcSymbol.getType();
                Type returnType = functionType.getReturnType();
                if (returnType.equals(BaseType.getTypeInt())) {
                    return new ArrayType(0, returnType);
                } else {
                    return returnType;
                }
            } else {
                outputErrorMsg(FUNC_PARAM_TYPE_MISMATCH, callExpContext.getStart().getLine(), "");
            }
        }
        return null;
    }

    private boolean resolveFuncRParams(SysYParser.FuncRParamsContext funcRParamsContext) {
        boolean isAllMatched = true;
        FunctionType nearestFuncType = getNearestFunctionType();

        List<SysYParser.ParamContext> paramContexts = funcRParamsContext.param();
        int rParamSize = paramContexts.size();
        int fParamSize = nearestFuncType.getParamSize();
        if (rParamSize == fParamSize) {
            for (int i = 0; i < rParamSize; i++) {
                SysYParser.ParamContext paramContext = paramContexts.get(i);
                Type rParamType = resolveExpType(paramContext.exp());
                Type fParamType = nearestFuncType.getParamTypes(i);
                if (!fParamType.equals(rParamType)) {
                    isAllMatched = false;
                    break;
                }
            }
        } else {
            isAllMatched = false;
        }

        return isAllMatched;
    }

    private FunctionType getNearestFunctionType() {
        Scope scopePointer = currentScope;
        while (!(scopePointer instanceof FunctionScope)) {
            scopePointer = scopePointer.getEnclosingScope();
        }
        String funcName = scopePointer.getName();
        return (FunctionType) scopePointer.getEnclosingScope().resolve(funcName, FunctionSymbol.class).getType();
    }

    private void outputErrorMsg(ErrorType type, int lineNumber, String msg) {
        System.err.println("Error type " + errorTypeMap.get(type) + " at Line " + lineNumber + ": " +
                errorTypeBaseMsg.get(type) + msg);
    }
}