package symtable.parser_lexer;

import org.antlr.v4.runtime.ParserRuleContext;
import symtable.scope.FunctionScope;
import symtable.scope.GlobalScope;
import symtable.scope.LocalScope;
import symtable.scope.Scope;
import symtable.symbol.*;
import symtable.type.ArrayType;
import symtable.type.BaseType;
import symtable.type.FunctionType;
import symtable.type.Type;

import java.util.*;

/**
 * @author WFS
 * @date 2022/12/17 12:48
 */
public class TypeCheckListener extends SysYParserBaseListener{
    enum ErrorType {
        UNDEFINED_VAR, UNDEFINED_FUNC,
        REDEFINED_VAR, REDEFINED_FUNC,
        ASSIGN_TYPE_MISMATCH, OPERATION_TYPE_MISMATCH, RETURN_TYPE_MISMATCH, FUNC_PARAM_TYPE_MISMATCH,
        NOT_ARRAY, NOT_FUNC, NOT_LEFT_VALUE
    }

    public static Map<ErrorType, Integer> errorTypeMap = new HashMap<>();
    public static Map<ErrorType, String> errorTypeBaseMsg = new HashMap<>();

    static {
        /* initialize  errorTypeMap*/
        errorTypeMap.put(ErrorType.UNDEFINED_VAR, 1);
        errorTypeMap.put(ErrorType.UNDEFINED_FUNC, 2);
        errorTypeMap.put(ErrorType.REDEFINED_VAR, 3);
        errorTypeMap.put(ErrorType.REDEFINED_FUNC, 4);
        errorTypeMap.put(ErrorType.ASSIGN_TYPE_MISMATCH, 5);
        errorTypeMap.put(ErrorType.OPERATION_TYPE_MISMATCH, 6);
        errorTypeMap.put(ErrorType.RETURN_TYPE_MISMATCH, 7);
        errorTypeMap.put(ErrorType.FUNC_PARAM_TYPE_MISMATCH, 8);
        errorTypeMap.put(ErrorType.NOT_ARRAY, 9);
        errorTypeMap.put(ErrorType.NOT_FUNC, 10);
        errorTypeMap.put(ErrorType.NOT_LEFT_VALUE, 11);

        /* initialize  errorTypeBaseMsg*/
        errorTypeBaseMsg.put(ErrorType.UNDEFINED_VAR, "Undefined variable: ");
        errorTypeBaseMsg.put(ErrorType.UNDEFINED_FUNC, "Undefined function: ");
        errorTypeBaseMsg.put(ErrorType.REDEFINED_VAR, "Redefined variable: ");
        errorTypeBaseMsg.put(ErrorType.REDEFINED_FUNC, "Redefined function: ");
        errorTypeBaseMsg.put(ErrorType.ASSIGN_TYPE_MISMATCH, "Assign type mismatch: ");
        errorTypeBaseMsg.put(ErrorType.OPERATION_TYPE_MISMATCH, "Operation type mismatch: ");
        errorTypeBaseMsg.put(ErrorType.RETURN_TYPE_MISMATCH, "Return type mismatch.");
        errorTypeBaseMsg.put(ErrorType.FUNC_PARAM_TYPE_MISMATCH, "Function parameter type mismatch.");
        errorTypeBaseMsg.put(ErrorType.NOT_ARRAY, "Not a array: ");
        errorTypeBaseMsg.put(ErrorType.NOT_FUNC, "Not a function: ");
        errorTypeBaseMsg.put(ErrorType.NOT_LEFT_VALUE, "Not left value: ");
    }

    private GlobalScope globalScope = null;

    private Scope currentScope = null;

    private List<LocalScope> localScopeList = new LinkedList<>();

    private int localScopeCounter = 0;

    public boolean hasError = false;

    public boolean skipFuncScope = false;


    /* enter scope */
    @Override
    public void enterProgram(SysYParser.ProgramContext ctx) {
        globalScope = new GlobalScope(null);
        currentScope = globalScope;
    }

    @Override
    public void enterFuncDef(SysYParser.FuncDefContext ctx) {
        if (!skipFuncScope) {
            String returnTypeName = ctx.funcType().getText();
            Symbol returnSymbol = globalScope.resolve(returnTypeName);

            String funcName =ctx.IDENT().getText();
            Symbol resolveSymbol = globalScope.resolve(funcName);

            FunctionScope functionScope = new FunctionScope(funcName, currentScope);
            FunctionType functionType = new FunctionType(functionScope, returnSymbol.getType());
            FunctionSymbol functionSymbol = new FunctionSymbol(functionType);
            if (resolveSymbol == null) {
                currentScope.define(functionSymbol);
                currentScope = functionScope;
                /* define param symbol */
                List<SysYParser.FuncFParamContext> funcFParamContexts = new LinkedList<>();
                if (hasParams(ctx)) funcFParamContexts.addAll(ctx.funcFParams().funcFParam());
                for (SysYParser.FuncFParamContext funcFParamContext : funcFParamContexts) {
                    String paramName = funcFParamContext.IDENT().getText();
                    Symbol paramSymbol = currentScope.resolveInConflictScope(paramName);
                    if (paramSymbol == null) {
                        defineParam(funcFParamContext, functionType);
                    } else {
                        outputErrorMsg(ErrorType.REDEFINED_VAR, getLine(funcFParamContext), paramName);
                    }
                }
            } else {
                skipFuncScope = true;
                outputErrorMsg(ErrorType.REDEFINED_FUNC, getLine(ctx), funcName);
            }
        }
    }

    @Override
    public void enterBlock(SysYParser.BlockContext ctx) {
        if (!skipFuncScope) {
            LocalScope localScope = new LocalScope(currentScope);
            String localScopeName = localScope.getName() + localScopeCounter;
            localScope.setName(localScopeName);

            localScopeCounter++;

            currentScope = localScope;
            localScopeList.add(localScope);
        }
    }


    /* exit scope */
    @Override
    public void exitProgram(SysYParser.ProgramContext ctx) {
        currentScope = currentScope.getEnclosingScope();
    }

    @Override
    public void exitFuncDef(SysYParser.FuncDefContext ctx) {
        if (skipFuncScope) {
            skipFuncScope = false;
        } else {
            currentScope = currentScope.getEnclosingScope();
        }
    }

    @Override
    public void exitBlock(SysYParser.BlockContext ctx) {
        if (!skipFuncScope) {
            currentScope = currentScope.getEnclosingScope();
        }
    }


    /* define symbol */
    @Override
    public void enterConstDecl(SysYParser.ConstDeclContext ctx) {
        if (!skipFuncScope) {
            String typeName = ctx.bType().getText();
            Symbol typeSymbol = globalScope.resolve(typeName);

            for (SysYParser.ConstDefContext constDef : ctx.constDef()) {
                String constName = constDef.IDENT().getText();
                // label冲突域中其他的变量
                Symbol labelSymbol = currentScope.resolveInConflictScope(constName);
                // 常量声明左边
                List<Object> constExps = new LinkedList<>();
                if (isArray(constDef)) constExps.addAll(constDef.constExp());
                VariableSymbol constSymbol = new VariableSymbol(constName, generateArray(constExps, (BaseType) typeSymbol.getType()));
                // 常量声明右边
                Type constInitValType = null;
                if (hasConstInitVal(constDef)) {
                    constInitValType = resolveConstInitValType(constDef.constInitVal(), (BaseType) typeSymbol.getType());
                }
                // 检查是否合法
                if (labelSymbol == null) {
                    if (constInitValType != null && !constInitValType.equals(constSymbol.getType())) {
                        outputErrorMsg(ErrorType.ASSIGN_TYPE_MISMATCH, getLine(constDef), "");
                    }
                    currentScope.define(constSymbol);
                } else {
                    outputErrorMsg(ErrorType.REDEFINED_VAR, getLine(ctx), constName);
                }
            }
        }
    }

    @Override
    public void enterVarDecl(SysYParser.VarDeclContext ctx) {
        if (!skipFuncScope) {
            String typeName = ctx.bType().getText();
            Symbol typeSymbol = globalScope.resolve(typeName);

            for (SysYParser.VarDefContext varDef : ctx.varDef()) {
                String varName = varDef.IDENT().getText();
                //  label冲突域中其他的变量
                Symbol labelSymbol = currentScope.resolveInConflictScope(varName);
                // 变量声明左边
                List<Object> constExps = new LinkedList<>();
                if (isArray(varDef)) constExps.addAll(varDef.constExp());
                VariableSymbol variableSymbol = new VariableSymbol(varName, generateArray(constExps, (BaseType) typeSymbol.getType()));
                // 变量声明右边
                Type initValType = null;
                if (hasInitVal(varDef)) {
                    initValType = resolveInitVal(varDef.initVal(), (BaseType) typeSymbol.getType());
                }
                // 检查是否合法
                if (labelSymbol == null) {
                    if (initValType != null && !initValType.equals(variableSymbol.getType())) {
                        outputErrorMsg(ErrorType.ASSIGN_TYPE_MISMATCH, getLine(varDef), "");
                    }
                    currentScope.define(variableSymbol);
                } else {
                    outputErrorMsg(ErrorType.REDEFINED_VAR, getLine(ctx), varName);
                }
            }
        }
    }

    /* resolve symbol */

    /* check assign type is matched */

    @Override
    public void enterAssignStmt(SysYParser.AssignStmtContext ctx) {
        if (!skipFuncScope) {
            Type lValType = resolveLValType(ctx.lVal());
            Type rValType = resolveExpType(ctx.exp());
            if (lValType != null) {
                if (lValType instanceof FunctionType) {
                    String funcName = ((FunctionType) lValType).getFunctionScope().getName();
                    outputErrorMsg(ErrorType.NOT_LEFT_VALUE, getLine(ctx), funcName);
                } else {
                    if (rValType != null && !lValType.equals(rValType)) {
                        outputErrorMsg(ErrorType.ASSIGN_TYPE_MISMATCH, getLine(ctx), "");
                    }
                }
            }
        }
    }

    @Override
    public void enterReturnStmt(SysYParser.ReturnStmtContext ctx) {
        if (!skipFuncScope) {
            Type expReturnType = resolveExpType(ctx.exp());
            FunctionType functionType = getNearestFunctionType();
            Type funcReturnType = functionType.getReturnType();
            if (funcReturnType.equals(BaseType.getTypeInt())) {
                funcReturnType = new ArrayType(0, funcReturnType);
            }
            if (expReturnType != null && !(expReturnType.equals(funcReturnType))) {
                outputErrorMsg(ErrorType.RETURN_TYPE_MISMATCH, getLine(ctx), "");
            }
        }
    }

    @Override
    public void enterExpStmt(SysYParser.ExpStmtContext ctx) {
        if (!skipFuncScope) {
            resolveExpType(ctx.exp());
        }
    }

    @Override
    public void enterIfStmt(SysYParser.IfStmtContext ctx) {
        if (!skipFuncScope) {
            // 这里还得限制返回类型为int
            resolveCondType(ctx.cond());
        }
    }

    @Override
    public void enterWhileStmt(SysYParser.WhileStmtContext ctx) {
        if (!skipFuncScope) {
            // 这里还得限制返回类型为int
            resolveCondType(ctx.cond());
        }
    }

    /* below is used for rename visitor */

    public GlobalScope getGlobalScope() {
        return this.globalScope;
    }

    public List<LocalScope> getLocalScopeList() {
        return this.localScopeList;
    }

    /* below are private methods */
    private boolean hasParams(SysYParser.FuncDefContext ctx) {
        return ctx.funcFParams() != null;
    }

    private boolean isArray(SysYParser.ConstDefContext ctx) {
        return !ctx.constExp().isEmpty();
    }

    private boolean isArray(SysYParser.VarDefContext ctx) {
        return !ctx.constExp().isEmpty();
    }

    /**
     * 进入这个函数的参数在函数定义部分已经检查过了，保证不会有命名冲突问题
     * @param ctx
     * @param functionType
     */
    private void defineParam(SysYParser.FuncFParamContext ctx, FunctionType functionType) {
        String typeName = ctx.bType().getText();
        Symbol typeSymbol = globalScope.resolve(typeName);

        String paraName = ctx.IDENT().getText();
        VariableSymbol variableSymbol = new VariableSymbol(paraName, getParaType(ctx, (BaseType) typeSymbol.getType()));
        currentScope.define(variableSymbol);
        // add param type to function type's field
        functionType.addParamType(variableSymbol.getType());
    }

    /**
     * 根据一系列常量表达式生成对应长度的数组类型，数组元素的count字段是Object，用于兼容constExp和exp
     * @param indexList
     * @param baseType
     * @return
     */
    private ArrayType generateArray(List<Object> indexList, BaseType baseType) {
        // int type
        if (indexList.isEmpty()) return new ArrayType(0, baseType);
        // array type
        else {
            Object index = indexList.get(0);
            indexList.remove(0);
            return new ArrayType(index, generateArray(indexList, baseType));
        }
    }

    private Type resolveConstInitValType(SysYParser.ConstInitValContext ctx, BaseType baseType) {
        if (ctx instanceof SysYParser.ConstExpConstInitValContext) {
            return resolveExpType(((SysYParser.ConstExpConstInitValContext) ctx).constExp().exp());
        } else {
            SysYParser.ArrayConstInitValContext arrayConstInitValContext = (SysYParser.ArrayConstInitValContext) ctx;
            if (!(arrayConstInitValContext.constInitVal().isEmpty())) {
                int count = arrayConstInitValContext.constInitVal().size();
                Type subType = resolveConstInitValType(arrayConstInitValContext.constInitVal(0), baseType);
                if (subType != null) {
                    // 这里可以扩展，可以检测每个元素是否同一个类型
                    return new ArrayType(count, subType);
                }
            } else {
                return new ArrayType(-1, new ArrayType(0, baseType));
            }
        }
        return null;
    }

    private Type resolveInitVal(SysYParser.InitValContext ctx, BaseType baseType) {
        if (ctx instanceof SysYParser.ExpInitValContext) {
            return resolveExpType(((SysYParser.ExpInitValContext) ctx).exp());
        } else {
            SysYParser.ArrayInitValContext arrayInitValContext = (SysYParser.ArrayInitValContext) ctx;
            if (!arrayInitValContext.initVal().isEmpty()) {
                int count = arrayInitValContext.initVal().size();
                Type subType = resolveInitVal(arrayInitValContext.initVal(0), baseType);
                if (subType != null) {
                    // 这里可以扩展，可以检测每个元素是否同一个类型
                    return new ArrayType(count, subType);
                }
            } else {
                return new ArrayType(-1, new ArrayType(0, baseType));
            }
        }
        return null;
    }

    private ArrayType getParaType(SysYParser.FuncFParamContext ctx, BaseType type) {
        List<Object> indexList = new LinkedList<>();
        // 一维数组的index省略不写，要加回去
        if (!ctx.L_BRACKT().isEmpty()) indexList.add(-1);
        indexList.addAll(ctx.exp());
        return generateArray(indexList, type);
    }

    private Type resolveExpType(SysYParser.ExpContext expContext) {
        if (expContext instanceof SysYParser.LValExpContext) {
            /* lVal */
            return resolveLValType(((SysYParser.LValExpContext) expContext).lVal());
        } else if (expContext instanceof SysYParser.ParenExpContext) {
            /* (exp) */
            SysYParser.ParenExpContext parenExpContext = (SysYParser.ParenExpContext) expContext;
            return resolveExpType(parenExpContext.exp());
        } else if (expContext instanceof SysYParser.NumberExpContext) {
            /* number */
            return new ArrayType(0, BaseType.getTypeInt());
        } else if (expContext instanceof SysYParser.CallExpContext) {
            /* f(xxx) */
            SysYParser.CallExpContext callExpContext = (SysYParser.CallExpContext) expContext;
            return resolveCallExp(callExpContext);
        } else if (expContext instanceof SysYParser.UnaryExpContext) {
            /* +\- exp */
            SysYParser.UnaryExpContext unaryExpContext = (SysYParser.UnaryExpContext) expContext;
            Type unaryExpType = resolveExpType(unaryExpContext.exp());
            return resolveOneIntOPType(unaryExpType, unaryExpContext);
        } else if (expContext instanceof SysYParser.MulDivModExpContext) {
            /* exp *\/\% exp */
            SysYParser.MulDivModExpContext mulDivModExpContext = (SysYParser.MulDivModExpContext) expContext;
            // 此处递归调用了解析表达式类型，会对未定义的label标记，故之后不需要再outputErrorMsg
            Type lhsType = resolveExpType(mulDivModExpContext.lhs);
            Type rhsType = resolveExpType(mulDivModExpContext.rhs);
            return resolveTwoIntOPType(lhsType, rhsType, mulDivModExpContext);
        } else if (expContext instanceof SysYParser.PlusMinusExpContext) {
            /* exp +\- exp */
            SysYParser.PlusMinusExpContext plusMinusExpContext = (SysYParser.PlusMinusExpContext) expContext;
            // 此处递归调用了解析表达式类型，会对未定义的label标记，故之后不需要再outputErrorMsg
            Type lhsType = resolveExpType(plusMinusExpContext.lhs);
            Type rhsType = resolveExpType(plusMinusExpContext.rhs);
            return resolveTwoIntOPType(lhsType, rhsType, plusMinusExpContext);
        } else {
            // return;会到这里
            return BaseType.getTypeVoid();
        }
    }

    private Type resolveLValType(SysYParser.LValContext lValContext) {
        String lValName = lValContext.IDENT().getText();
        Symbol lValSymbol = currentScope.resolve(lValName);
        if (lValSymbol == null) {
            outputErrorMsg(ErrorType.UNDEFINED_VAR, getLine(lValContext), lValName);
        } else {
            Type labelType = lValSymbol.getType();
            // 只有变量要特殊处理，其他（函数）不需要特殊处理
            if (lValSymbol instanceof VariableSymbol && lValContext.getChildCount() > 1) {
                int indexSize = lValContext.L_BRACKT().size();
                for (int i = 0; i < indexSize; i++) {
                    // 由于 int 类型我也封装成了 ArrayType，所以我需要连续两个判断，判断是否都是 ArrayType
                    if (labelType instanceof ArrayType && ((ArrayType) labelType).getSubType() instanceof ArrayType) {
                        labelType = ((ArrayType) labelType).getSubType();
                    } else {
                        outputErrorMsg(ErrorType.NOT_ARRAY, getLine(lValContext), lValName);
                        return null;
                    }
                }
            }
            return labelType;
        }
        return null;
    }

    private Type resolveCallExp(SysYParser.CallExpContext callExpContext) {
        String funcName = callExpContext.IDENT().getText();
        /* resolve function name */
        Symbol funcSymbol = currentScope.resolve(funcName);
        if (funcSymbol == null) {
            outputErrorMsg(ErrorType.UNDEFINED_FUNC, getLine(callExpContext), funcName);
        } else if (!(funcSymbol instanceof FunctionSymbol)) {
            outputErrorMsg(ErrorType.NOT_FUNC, getLine(callExpContext), funcName);
        } else {
            FunctionType functionType = (FunctionType) funcSymbol.getType();
            if (checkFuncRParams(callExpContext, functionType)) {
                return resolveReturnType(functionType);
            } else {
                outputErrorMsg(ErrorType.FUNC_PARAM_TYPE_MISMATCH, getLine(callExpContext), "");
            }
        }
        return null;
    }

    private boolean checkFuncRParams(SysYParser.CallExpContext callExpContext, FunctionType functionType) {
        int rParamSize = 0;
        int fParamSize = functionType.getParamSize();
        List<SysYParser.ParamContext> paramContexts = new LinkedList<>();

        if (callExpContext.funcRParams() != null) {
            paramContexts.addAll(callExpContext.funcRParams().param());// NullPointerException, 之前没有看到funcRParams之后有个问号
            rParamSize = paramContexts.size();
        }

        int i = 0;
        int j = 0;
        for (; i < rParamSize && j < fParamSize; j++) {
            SysYParser.ParamContext paramContext = paramContexts.get(i);
            Type rParamType = resolveExpType(paramContext.exp());
            // 形参在函数定义的时候检查过了，不会有问题
            Type fParamType = functionType.getParamTypes(j);
            if (rParamType != null) {
                if (!fParamType.equals(rParamType)) {
                    return false;
                }
                i++;
            }
        }
        return i == rParamSize && j == fParamSize;
    }

    private Type resolveReturnType(FunctionType functionType) {
        Type returnType = functionType.getReturnType();
        if (returnType.equals(BaseType.getTypeInt())) {
            return new ArrayType(0, returnType);
        } else {
            return returnType;
        }
    }

    /**
     * 这个函数判断表达式是否合法，返回条件表达式的类型
     * @param ctx
     */
    private Type resolveCondType(SysYParser.CondContext ctx) {
        if (ctx instanceof SysYParser.ExpCondContext) {
            return resolveExpType(((SysYParser.ExpCondContext) ctx).exp());
        } else if (ctx instanceof SysYParser.GLCondContext) {
            Type lCondType = resolveCondType(((SysYParser.GLCondContext) ctx).cond(0));
            Type rCondType = resolveCondType(((SysYParser.GLCondContext) ctx).cond(1));
            return resolveTwoIntOPType(lCondType, rCondType, ctx);
        } else if (ctx instanceof SysYParser.EQCondContext){
            Type lCondType = resolveCondType(((SysYParser.EQCondContext) ctx).cond(0));
            Type rCondType = resolveCondType(((SysYParser.EQCondContext) ctx).cond(1));
            return resolveTwoIntOPType(lCondType, rCondType, ctx);
        } else if (ctx instanceof SysYParser.AndCondContext) {
            Type lCondType = resolveCondType(((SysYParser.AndCondContext) ctx).cond(0));
            Type rCondType = resolveCondType(((SysYParser.AndCondContext) ctx).cond(1));
            return resolveTwoIntOPType(lCondType, rCondType, ctx);
        } else if (ctx instanceof SysYParser.OrCondContext) {
            Type lCondType = resolveCondType(((SysYParser.OrCondContext) ctx).cond(0));
            Type rCondType = resolveCondType(((SysYParser.OrCondContext) ctx).cond(1));
            return resolveTwoIntOPType(lCondType, rCondType, ctx);
        }
        return null;
    }

    private Type resolveOneIntOPType(Type operandType, ParserRuleContext ctx) {
        if (operandType != null && !isIntType(operandType)) {
            outputErrorMsg(ErrorType.OPERATION_TYPE_MISMATCH, getLine(ctx), "");
        }
        return operandType;
    }

    private Type resolveTwoIntOPType(Type leftOperandType, Type rightOperandType, ParserRuleContext ctx) {
        if (leftOperandType != null && rightOperandType != null) {
            if (leftOperandType.equals(rightOperandType) && isIntType(leftOperandType)) {
                return leftOperandType;
            } else {
                outputErrorMsg(ErrorType.OPERATION_TYPE_MISMATCH, getLine(ctx), "");
            }
        }
        return null;
    }

    private boolean hasConstInitVal(SysYParser.ConstDefContext ctx) {
        return ctx.constInitVal() != null;
    }

    private boolean hasInitVal(SysYParser.VarDefContext ctx) {
        return ctx.initVal() != null;
    }

    private boolean isIntType(Type t) {
        return t instanceof ArrayType && ((ArrayType) t).getSubType().equals(BaseType.getTypeInt());
    }

    private FunctionType getNearestFunctionType() {
        Scope scopePointer = currentScope;
        while (!(scopePointer instanceof FunctionScope)) {
            scopePointer = scopePointer.getEnclosingScope(); // NullPointerException -> currentScope 忘记先修改了，导致少了一层
        }
        String funcName = scopePointer.getName(); // 这里需要额外的措施保证这个 funcName 对应的一定是 FuncSymbol，即防止在最外层block中return
        return (FunctionType) scopePointer.getEnclosingScope().resolve(funcName).getType();
    }

    private int getLine(ParserRuleContext ctx) {
        return ctx.getStart().getLine();
    }

    private void outputErrorMsg(ErrorType type, int lineNumber, String msg) {
        System.err.println("Error type " + errorTypeMap.get(type) + " at Line " + lineNumber + ": " +
                    errorTypeBaseMsg.get(type) + msg);
        hasError = true;
    }
}
