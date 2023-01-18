import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.llvm.LLVM.*;
import symtable.scope.FunctionScope;
import symtable.scope.GlobalScope;
import symtable.scope.LocalScope;
import symtable.scope.Scope;
import symtable.symbol.FunctionSymbol;
import symtable.symbol.Symbol;
import symtable.symbol.VariableSymbol;
import symtable.type.ArrayType;
import symtable.type.BaseType;
import symtable.type.FunctionType;
import symtable.type.Type;

import java.util.*;

import static org.bytedeco.llvm.global.LLVM.*;

/**
 * @author WFS
 * @date 2023/1/7 22:09
 * 结论：lab6 pure global var 共 700 分
 * 但是不处理 ifStmt 且 global var 正确可以得到 1700 分（最后 3 个 hardtest 执行 ir 的结果错误）
 * 因为可能只有 if 且条件表达式为正确，所以执行结果正确
 */
public class FunctionAndVarIRVisitor extends SysYParserBaseVisitor<LLVMValueRef>{
    // fields related to type checking and symbol table generating
    enum ErrorType {
        UNDEFINED_VAR, UNDEFINED_FUNC,
        REDEFINED_VAR, REDEFINED_FUNC,
        ASSIGN_TYPE_MISMATCH, OPERATION_TYPE_MISMATCH, RETURN_TYPE_MISMATCH, FUNC_PARAM_TYPE_MISMATCH,
        NOT_ARRAY, NOT_FUNC, NOT_LEFT_VALUE
    }

    public static Map<TypeCheckListener.ErrorType, Integer> errorTypeMap = new HashMap<>();
    public static Map<TypeCheckListener.ErrorType, String> errorTypeBaseMsg = new HashMap<>();

    static {
        /* initialize  errorTypeMap*/
        errorTypeMap.put(TypeCheckListener.ErrorType.UNDEFINED_VAR, 1);
        errorTypeMap.put(TypeCheckListener.ErrorType.UNDEFINED_FUNC, 2);
        errorTypeMap.put(TypeCheckListener.ErrorType.REDEFINED_VAR, 3);
        errorTypeMap.put(TypeCheckListener.ErrorType.REDEFINED_FUNC, 4);
        errorTypeMap.put(TypeCheckListener.ErrorType.ASSIGN_TYPE_MISMATCH, 5);
        errorTypeMap.put(TypeCheckListener.ErrorType.OPERATION_TYPE_MISMATCH, 6);
        errorTypeMap.put(TypeCheckListener.ErrorType.RETURN_TYPE_MISMATCH, 7);
        errorTypeMap.put(TypeCheckListener.ErrorType.FUNC_PARAM_TYPE_MISMATCH, 8);
        errorTypeMap.put(TypeCheckListener.ErrorType.NOT_ARRAY, 9);
        errorTypeMap.put(TypeCheckListener.ErrorType.NOT_FUNC, 10);
        errorTypeMap.put(TypeCheckListener.ErrorType.NOT_LEFT_VALUE, 11);

        /* initialize  errorTypeBaseMsg*/
        errorTypeBaseMsg.put(TypeCheckListener.ErrorType.UNDEFINED_VAR, "Undefined variable: ");
        errorTypeBaseMsg.put(TypeCheckListener.ErrorType.UNDEFINED_FUNC, "Undefined function: ");
        errorTypeBaseMsg.put(TypeCheckListener.ErrorType.REDEFINED_VAR, "Redefined variable: ");
        errorTypeBaseMsg.put(TypeCheckListener.ErrorType.REDEFINED_FUNC, "Redefined function: ");
        errorTypeBaseMsg.put(TypeCheckListener.ErrorType.ASSIGN_TYPE_MISMATCH, "Assign type mismatch: ");
        errorTypeBaseMsg.put(TypeCheckListener.ErrorType.OPERATION_TYPE_MISMATCH, "Operation type mismatch: ");
        errorTypeBaseMsg.put(TypeCheckListener.ErrorType.RETURN_TYPE_MISMATCH, "Return type mismatch.");
        errorTypeBaseMsg.put(TypeCheckListener.ErrorType.FUNC_PARAM_TYPE_MISMATCH, "Function parameter type mismatch.");
        errorTypeBaseMsg.put(TypeCheckListener.ErrorType.NOT_ARRAY, "Not a array: ");
        errorTypeBaseMsg.put(TypeCheckListener.ErrorType.NOT_FUNC, "Not a function: ");
        errorTypeBaseMsg.put(TypeCheckListener.ErrorType.NOT_LEFT_VALUE, "Not left value: ");
    }

    private GlobalScope globalScope = null;

    private Scope currentScope = null;

    private int localScopeCounter = 0;

    public boolean hasError = false;

    public boolean skipFuncScope = false;

    // fields related to IR generator
    static final LLVMModuleRef module = LLVMModuleCreateWithName("module");
    static final LLVMBuilderRef builder = LLVMCreateBuilder();
    static final LLVMTypeRef voidType = LLVMVoidType();
    static final LLVMTypeRef i32Type = LLVMInt32Type();

    static final LLVMValueRef zero = LLVMConstInt(i32Type, 0, 0);

    static final LLVMValueRef trueRef = LLVMConstInt(LLVMInt1Type(), 1, 0);

    static Map<String, Integer> opMap = new HashMap<>();

    static {
        opMap.put(">", LLVMIntSGT);
        opMap.put("<", LLVMIntSLT);
        opMap.put(">=", LLVMIntSGE);
        opMap.put("<=", LLVMIntSLE);
        opMap.put("==", LLVMIntEQ);
        opMap.put("!=", LLVMIntNE);
    }

    String destPath;

    static final BytePointer error = new BytePointer();

    static final String namePrefix = "*";

    static Stack<LLVMBasicBlockRef> whileCondBlocks = new Stack<>();

    static Stack<LLVMBasicBlockRef> whileExitBlocks = new Stack<>();

    public FunctionAndVarIRVisitor(String destPath) {
        //初始化LLVM
        LLVMInitializeCore(LLVMGetGlobalPassRegistry());
        LLVMLinkInMCJIT();
        LLVMInitializeNativeAsmPrinter();
        LLVMInitializeNativeAsmParser();
        LLVMInitializeNativeTarget();

        this.destPath = destPath;
    }

    @Override
    public LLVMValueRef visitFuncDef(SysYParser.FuncDefContext ctx) {
        FunctionSymbol functionSymbol = null;

        // related to type checking and symbol table generating
        if (!skipFuncScope) {
            String returnTypeName = ctx.funcType().getText();
            Symbol returnSymbol = globalScope.resolve(returnTypeName);

            String funcName =ctx.IDENT().getText();
            Symbol resolveSymbol = globalScope.resolve(funcName);

            FunctionScope functionScope = new FunctionScope(funcName, currentScope);
            FunctionType functionType = new FunctionType(functionScope, returnSymbol.getType());
            functionSymbol = new FunctionSymbol(functionType);
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
                        outputErrorMsg(TypeCheckListener.ErrorType.REDEFINED_VAR, getLine(funcFParamContext), paramName);
                    }
                }
            } else {
                skipFuncScope = true;
                outputErrorMsg(TypeCheckListener.ErrorType.REDEFINED_FUNC, getLine(ctx), funcName);
            }
        }

        // function ir
        LLVMTypeRef returnType = i32Type;
        if (ctx.funcType().getText().equals("void")) {
            returnType = voidType;
        }

        int paraSize;
        if (ctx.funcFParams() == null) paraSize = 0;
        else paraSize = ctx.funcFParams().funcFParam().size();

        PointerPointer<Pointer> argumentTypes = new PointerPointer<>(paraSize);
        for (int i = 0; i < paraSize; i++) {
            LLVMTypeRef paramType = resolveParamType(ctx.funcFParams().funcFParam(i));
            argumentTypes.put(i, paramType);
        }

        LLVMTypeRef ft = LLVMFunctionType(returnType, argumentTypes, paraSize, 0);

        LLVMValueRef function = LLVMAddFunction(module, ctx.IDENT().getText(), ft);
        functionSymbol.setValueRef(function);

        // 添加基本快
        LLVMBasicBlockRef block = LLVMAppendBasicBlock(function, ctx.IDENT().getText() + "Entry");

        LLVMPositionBuilderAtEnd(builder, block);

        // 将形参传递给实参（这里的形参指的是定义的function自动生成的没有名字的参数）
        if (ctx.funcFParams() != null) {
            List<SysYParser.FuncFParamContext> funcRParamContexts = ctx.funcFParams().funcFParam();
            for (int i = 0; i < funcRParamContexts.size(); i++) {
                SysYParser.FuncFParamContext funcFParamContext = funcRParamContexts.get(i);
                // resolve symbol
                String fParamName = funcFParamContext.IDENT().getText();
                Symbol fParamSymbol = currentScope.resolve(fParamName);
                // generate mapping relation
                LLVMValueRef rParamRef = LLVMGetParam(function, i);
                LLVMTypeRef paramType = LLVMTypeOf(rParamRef);
                LLVMValueRef fParamRef = LLVMBuildAlloca(builder, paramType, fParamName);
                LLVMBuildStore(builder, rParamRef, fParamRef);

                fParamSymbol.setValueRef(fParamRef);
            }
        }

        // continue traversal
        super.visitFuncDef(ctx);

        // 为没有返回语句的函数增加默认返回语句
        int retStmtIdx = ctx.block().children.size() - 2;
        ParseTree targetContext = ctx.block().children.get(retStmtIdx).getChild(0);
        if (!(targetContext instanceof SysYParser.ReturnStmtContext) && returnType.equals(voidType)) {
            LLVMBuildRetVoid(builder);
        }

        // related to type checking and symbol table generating
        if (skipFuncScope) {
            skipFuncScope = false;
        } else {
            currentScope = currentScope.getEnclosingScope();
        }

        return null;
    }

    private LLVMTypeRef resolveParamType(SysYParser.FuncFParamContext ctx) {
        LLVMTypeRef paramType = i32Type;
        if (!ctx.L_BRACKT().isEmpty()) {
            paramType = LLVMPointerType(paramType, 0);
        }
        return paramType;
    }

    @Override
    public LLVMValueRef visitAssignStmt(SysYParser.AssignStmtContext ctx) {
        // related to type checking symbol table
        if (!skipFuncScope) {
            Type lValType = resolveLValType(ctx.lVal());
            Type rValType = resolveExpType(ctx.exp());
            if (lValType != null) {
                if (lValType instanceof FunctionType) {
                    String funcName = ((FunctionType) lValType).getFunctionScope().getName();
                    outputErrorMsg(TypeCheckListener.ErrorType.NOT_LEFT_VALUE, getLine(ctx), funcName);
                } else {
                    if (rValType != null && !lValType.equals(rValType)) {
                        outputErrorMsg(TypeCheckListener.ErrorType.ASSIGN_TYPE_MISMATCH, getLine(ctx), "");
                    }
                }
            }
        }

        LLVMValueRef lValRef = visit(ctx.lVal());
        LLVMValueRef expVal = visit(ctx.exp());
        LLVMBuildStore(builder, expVal, lValRef);

        return null;
    }

    @Override
    public LLVMValueRef visitExpStmt(SysYParser.ExpStmtContext ctx) {
        // related to type checking and symbol table generating
        if (!skipFuncScope) {
            resolveExpType(ctx.exp());
        }

        super.visitExpStmt(ctx);
        return null;
    }

    @Override
    public LLVMValueRef visitIfStmt(SysYParser.IfStmtContext ctx) {
        // related to type checking and symbol table generating
        if (!skipFuncScope) {
            // 这里还得限制返回类型为int
            resolveCondType(ctx.cond());
        }

        /* append basic block */
        LLVMValueRef functionRef = getContainerFunctionRef();
        LLVMBasicBlockRef ifTrueBlock = LLVMAppendBasicBlock(functionRef, "if_true");
        LLVMBasicBlockRef ifFalseBlock = LLVMAppendBasicBlock(functionRef, "if_false");
        LLVMBasicBlockRef ifExitBlock = LLVMAppendBasicBlock(functionRef, "if_exit");
        generateIfCondBrInstr(ctx, ifTrueBlock, ifFalseBlock);

        // true
        LLVMPositionBuilderAtEnd(builder, ifTrueBlock);
        visit(ctx.if_stmt);
        LLVMBuildBr(builder, ifExitBlock);

        // false
        LLVMPositionBuilderAtEnd(builder, ifFalseBlock);
        if (ctx.else_stmt != null) visit(ctx.else_stmt);
        LLVMBuildBr(builder, ifExitBlock);

        // if-stmt exit
        LLVMPositionBuilderAtEnd(builder, ifExitBlock);
        return null;
    }

    private LLVMValueRef getContainerFunctionRef() {
        Scope scopePointer = currentScope;
        while (!(scopePointer instanceof FunctionScope)) {
            scopePointer = scopePointer.getEnclosingScope(); // NullPointerException -> currentScope 忘记先修改了，导致少了一层
        }
        String funcName = scopePointer.getName(); // 这里需要额外的措施保证这个 funcName 对应的一定是 FuncSymbol，即防止在最外层block中return
        return scopePointer.getEnclosingScope().resolve(funcName).getValueRef();
    }

    private void generateIfCondBrInstr(SysYParser.IfStmtContext ctx, LLVMBasicBlockRef ifTrueBlock, LLVMBasicBlockRef ifFalseBlock) {
        LLVMValueRef condVal = visit(ctx.cond());
        LLVMValueRef condition = LLVMBuildICmp(builder, LLVMIntNE, condVal, zero, namePrefix);
        LLVMBuildCondBr(builder, condition, ifTrueBlock, ifFalseBlock);
    }

    @Override
    public LLVMValueRef visitWhileStmt(SysYParser.WhileStmtContext ctx) {
        // related to type checking and symbol table generating
        if (!skipFuncScope) {
            // 这里还得限制返回类型为int
            resolveCondType(ctx.cond());
        }

        /* append basic block */
        LLVMValueRef functionRef = getContainerFunctionRef();
        LLVMBasicBlockRef whileCondBlock = LLVMAppendBasicBlock(functionRef, "while_cond");
        LLVMBasicBlockRef whileTrueBlock = LLVMAppendBasicBlock(functionRef, "while_true");
        LLVMBasicBlockRef whileExitBlock = LLVMAppendBasicBlock(functionRef, "while_exit");

        /* 基本块入栈 */
        whileCondBlocks.push(whileCondBlock);
        whileExitBlocks.push(whileExitBlock);

        LLVMBuildBr(builder, whileCondBlock);
        /* 切换到条件判断基本块 */
        LLVMPositionBuilderAtEnd(builder, whileCondBlock);
        generateWhileCondBrInstr(ctx, whileTrueBlock, whileExitBlock);

        /* 填充 while true 的指令 */
        LLVMPositionBuilderAtEnd(builder, whileTrueBlock);
        visit(ctx.stmt());
        LLVMBuildBr(builder, whileCondBlock);

        /* 填充 while exit 的指令 */
        LLVMPositionBuilderAtEnd(builder, whileExitBlock);

        /* 基本块出栈 */
        whileExitBlocks.pop();
        whileCondBlocks.pop();

        return null;
    }



    private void generateWhileCondBrInstr(SysYParser.WhileStmtContext ctx, LLVMBasicBlockRef whileTrueBlock, LLVMBasicBlockRef whileExitBlock) {
        LLVMValueRef condVal = visit(ctx.cond());
        LLVMValueRef condition = LLVMBuildICmp(builder, LLVMIntNE, condVal, zero, namePrefix);
        LLVMBuildCondBr(builder, condition, whileTrueBlock, whileExitBlock);
    }

    @Override
    public LLVMValueRef visitBreakStmt(SysYParser.BreakStmtContext ctx) {
        LLVMBasicBlockRef whileExitBlock = whileExitBlocks.peek();
        LLVMBuildBr(builder, whileExitBlock);
        return null;
    }

    @Override
    public LLVMValueRef visitContinueStmt(SysYParser.ContinueStmtContext ctx) {
        LLVMBasicBlockRef whileCondBlock = whileCondBlocks.peek();
        LLVMBuildBr(builder, whileCondBlock);
        return null;
    }

    @Override
    public LLVMValueRef visitReturnStmt(SysYParser.ReturnStmtContext ctx) {
        // related to type checking and symbol table generating
        if (!skipFuncScope) {
            Type expReturnType = resolveExpType(ctx.exp());
            FunctionType functionType = getNearestFunctionType();
            Type funcReturnType = functionType.getReturnType();
            if (funcReturnType.equals(BaseType.getTypeInt())) {
                funcReturnType = new ArrayType(0, funcReturnType);
            }
            if (expReturnType != null && !(expReturnType.equals(funcReturnType))) {
                outputErrorMsg(TypeCheckListener.ErrorType.RETURN_TYPE_MISMATCH, getLine(ctx), "");
            }
        }

        LLVMValueRef retVal;
        if (ctx.exp() != null) {
            retVal = visit(ctx.exp());
            LLVMBuildRet(builder, retVal);
        }
        else {
            LLVMBuildRetVoid(builder);
        }
        return null;
    }

    @Override
    public LLVMValueRef visitConstDecl(SysYParser.ConstDeclContext ctx) {
        // related to type checking and symbol table generating
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
                        outputErrorMsg(TypeCheckListener.ErrorType.ASSIGN_TYPE_MISMATCH, getLine(constDef), "");
                    }
                    currentScope.define(constSymbol);
                } else {
                    outputErrorMsg(TypeCheckListener.ErrorType.REDEFINED_VAR, getLine(ctx), constName);
                }
            }
        }

        super.visitConstDecl(ctx);
        return null;
    }

    @Override
    public LLVMValueRef visitConstDef(SysYParser.ConstDefContext ctx) {
        String varName = ctx.IDENT().getText();
        Symbol varSymbol = currentScope.resolve(varName);
        LLVMValueRef varPointer;

        if (ctx.constExp().isEmpty()) {
            /* const variable case */
            varPointer = handleConstVar(ctx, varName);
        } else {
            /* const array case */
            varPointer = handleConstArray(ctx, varName);
        }

        varSymbol.setValueRef(varPointer);
        return null;
    }

    private LLVMValueRef handleConstVar(SysYParser.ConstDefContext ctx, String varName) {
        /* allocate & init global/local const var */
        LLVMValueRef varPointer;
        LLVMValueRef constInitVal = visit(ctx.constInitVal());
        if (currentScope.equals(globalScope)) {
            /* global const var */
            varPointer = LLVMAddGlobal(module, i32Type, varName);
            LLVMSetInitializer(varPointer, constInitVal);
        } else {
            /* local const var */
            varPointer = LLVMBuildAlloca(builder, i32Type, varName);
            LLVMBuildStore(builder, constInitVal, varPointer);
        }

        return varPointer;
    }

    private LLVMValueRef handleConstArray(SysYParser.ConstDefContext ctx, String arrayName) {
        /* allocate global/local const array */
        // 获得数组长度, 只处理一维数组
        LLVMValueRef arrayPointer;
        LLVMValueRef lengthVal = visit(ctx.constExp(0).exp());
        int length = (int) LLVMConstIntGetZExtValue(lengthVal);
        // 创建数组类型、分配空间、初始化
        LLVMTypeRef arrayType = LLVMVectorType(i32Type, length);
        if (currentScope.equals(globalScope)) {
            /* global const array */
            arrayPointer = LLVMAddGlobal(module, arrayType, arrayName);
            globalConstArrayInit(ctx, length, arrayPointer);
        } else {
            /* local const array */
            arrayPointer = LLVMBuildAlloca(builder, arrayType, arrayName);
            localConstArrayInit(ctx, length, arrayPointer);
        }

        return arrayPointer;
    }

    private void globalConstArrayInit(SysYParser.ConstDefContext ctx, int length, LLVMValueRef arrayPointer) {
        PointerPointer<Pointer> constInitVals = new PointerPointer<>(length);
        for (int i = 0; i < length; i++) {
            // 获得下标对应的初值
            LLVMValueRef constInitVal = zero;
            SysYParser.ArrayConstInitValContext arrayConstInitValContext = (SysYParser.ArrayConstInitValContext) ctx.constInitVal();
            if (i < arrayConstInitValContext.constInitVal().size()) {
                constInitVal = visit(arrayConstInitValContext.constInitVal(i));
            }
            // 存入初始值
            constInitVals.put(i, constInitVal);
        }

        LLVMSetInitializer(arrayPointer, LLVMConstVector(constInitVals, length));
    }

    private void localConstArrayInit(SysYParser.ConstDefContext ctx, int length, LLVMValueRef arrayPointer) {
        for (int i = 0; i < length; i++) {
            // 通过GEP指令获得下标对应的元素指针
            LLVMValueRef[] idxesRef = new LLVMValueRef[]{zero, LLVMConstInt(i32Type, i, 0)}; // Complete 这边长度设置为2是不是因为arrayPointer是指向数组的指针？ 是
            PointerPointer<Pointer> idxesPointer = new PointerPointer<>(idxesRef);
            LLVMValueRef elePointer = LLVMBuildGEP(builder, arrayPointer, idxesPointer, 2, "pointer");
            // 获得下标对应的初值
            LLVMValueRef constInitVal = zero;
            SysYParser.ArrayConstInitValContext arrayInitValContext = (SysYParser.ArrayConstInitValContext) ctx.constInitVal();
            if (i < arrayInitValContext.constInitVal().size()) {
                constInitVal = visit(arrayInitValContext.constInitVal(i));
            }
            // 存入初始值
            LLVMBuildStore(builder, constInitVal, elePointer);
        }
    }

    @Override
    public LLVMValueRef visitConstExpConstInitVal(SysYParser.ConstExpConstInitValContext ctx) {
        return visit(ctx.constExp().exp());
    }

    @Override
    public LLVMValueRef visitVarDecl(SysYParser.VarDeclContext ctx) {
        // related to type checking and symbol table generating
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
                        outputErrorMsg(TypeCheckListener.ErrorType.ASSIGN_TYPE_MISMATCH, getLine(varDef), "");
                    }
                    currentScope.define(variableSymbol);
                } else {
                    outputErrorMsg(TypeCheckListener.ErrorType.REDEFINED_VAR, getLine(ctx), varName);
                }
            }
        }

        super.visitVarDecl(ctx);
        return null;
    }

    @Override
    public LLVMValueRef visitVarDef(SysYParser.VarDefContext ctx) {
        String varName = ctx.IDENT().getText();
        Symbol varSymbol = currentScope.resolve(varName);
        LLVMValueRef varPointer;

        if (ctx.constExp().isEmpty()) {
            /* variable case */
            varPointer = handleVar(ctx, varName);
        } else {
            /* array case */
            varPointer = handleArray(ctx, varName);
        }

        varSymbol.setValueRef(varPointer);
        return null;
    }

    private LLVMValueRef handleVar(SysYParser.VarDefContext ctx, String varName) {
        /* allocate & init global/local var */
        LLVMValueRef varPointer;
        LLVMValueRef initVal = zero;
        if (ctx.initVal() != null) {
            initVal = visit(ctx.initVal());
        }
        if (currentScope.equals(globalScope)) {
            /* global var */
            varPointer = LLVMAddGlobal(module, i32Type, varName);
            LLVMSetInitializer(varPointer, initVal);
        } else {
            /* local var */
            varPointer = LLVMBuildAlloca(builder, i32Type, varName);
            if (ctx.initVal() != null) {
                LLVMBuildStore(builder, initVal, varPointer);
            }
        }

        return varPointer;
    }

    private LLVMValueRef handleArray(SysYParser.VarDefContext ctx, String arrayName) {
        /* allocate global/local array */
        // 获得数组长度, 只处理一维数组
        LLVMValueRef arrayPointer;
        LLVMValueRef lengthVal = visit(ctx.constExp(0).exp());
        int length = (int) LLVMConstIntGetZExtValue(lengthVal);
        // 创建数组类型并分配空间
        LLVMTypeRef arrayType = LLVMVectorType(i32Type, length);
        if (currentScope.equals(globalScope)) {
            /* global array */
            arrayPointer = LLVMAddGlobal(module, arrayType, arrayName);
            globalArrayInit(ctx, length, arrayPointer);
        } else {
            /* local array */
            arrayPointer = LLVMBuildAlloca(builder, arrayType, arrayName);
            localArrayInit(ctx, length, arrayPointer);
        }

        return arrayPointer;
    }

    private void globalArrayInit(SysYParser.VarDefContext ctx, int length, LLVMValueRef arrayPointer) {
        PointerPointer<Pointer> initVals = new PointerPointer<>(length);
        for (int i = 0; i < length; i++) {
            // 获得下标对应的初值
            LLVMValueRef initVal = zero;
            SysYParser.ArrayInitValContext arrayInitValContext = (SysYParser.ArrayInitValContext) ctx.initVal();
            if (arrayInitValContext != null && i < arrayInitValContext.initVal().size()) {
                initVal = visit(arrayInitValContext.initVal(i));
            }
            // 存入初始值
            initVals.put(i, initVal);
        }
        LLVMSetInitializer(arrayPointer, LLVMConstVector(initVals, length));
    }

    private void localArrayInit(SysYParser.VarDefContext ctx, int length, LLVMValueRef arrayPointer) {
        for (int i = 0; i < length; i++) {
            if (ctx.initVal() != null) {
                // 通过GEP指令获得下标对应的元素指针
                LLVMValueRef[] idxesRef = new LLVMValueRef[]{zero, LLVMConstInt(i32Type, i, 0)}; // Complete 这边长度设置为2是不是因为arrayPointer是指向数组的指针？ 是
                PointerPointer<Pointer> idxesPointer = new PointerPointer<>(idxesRef);
                LLVMValueRef elePointer = LLVMBuildGEP(builder, arrayPointer, idxesPointer, 2, "pointer");
                // 获得下标对应的初值
                LLVMValueRef initVal = zero;
                SysYParser.ArrayInitValContext arrayInitValContext = (SysYParser.ArrayInitValContext) ctx.initVal();
                if (i < arrayInitValContext.initVal().size()) {
                    initVal = visit(arrayInitValContext.initVal(i));
                }
                // 存入初始值
                LLVMBuildStore(builder, initVal, elePointer);
            }
        }
    }

    @Override
    public LLVMValueRef visitExpInitVal(SysYParser.ExpInitValContext ctx) {
        return visit(ctx.exp());
    }

    @Override
    public LLVMValueRef visitParenExp(SysYParser.ParenExpContext ctx) {
        return visit(ctx.exp());
    }

    @Override
    public LLVMValueRef visitLValExp(SysYParser.LValExpContext ctx) {
        LLVMValueRef lValRef = visit(ctx.lVal());
        LLVMValueRef lVal = LLVMBuildLoad(builder, lValRef, namePrefix);
        return lVal;
    }

    @Override
    public LLVMValueRef visitNumberExp(SysYParser.NumberExpContext ctx) {
        return LLVMConstInt(i32Type, getDecimal(ctx.number().getText()), 0);
    }

    @Override
    public LLVMValueRef visitCallExp(SysYParser.CallExpContext ctx) {
        String funcName = ctx.IDENT().getText();
        LLVMValueRef funcRef = currentScope.resolve(funcName).getValueRef();
        int paramSize = 0;
        PointerPointer<Pointer> indices = null;
        if (ctx.funcRParams() != null) {
            paramSize = ctx.funcRParams().param().size();
            indices = new PointerPointer<>(paramSize);
            for (int i = 0; i < paramSize; i++) {
                // 这里函数参数只为整型和一维数组
                LLVMValueRef argVal = visit(ctx.funcRParams().param(i).exp());
                if (LLVMGetTypeKind(LLVMTypeOf(argVal)) == LLVMVectorTypeKind) {
                    String arrayName = ctx.funcRParams().param(i).exp().getText();
                    LLVMValueRef arrayRef = currentScope.resolve(arrayName).getValueRef();
                    PointerPointer<Pointer> p = new PointerPointer<>(new LLVMValueRef[]{zero, zero});
                    argVal = LLVMBuildGEP(builder, arrayRef, p, 2, namePrefix);
                }

                indices.put(i, argVal);
            }
        }

        String retValName = namePrefix;
        FunctionType functionType = (FunctionType) currentScope.resolve(funcName).getType();
        if (functionType.getReturnType().equals(BaseType.getTypeVoid())) retValName = "";
        LLVMValueRef retVal = LLVMBuildCall(builder, funcRef, indices, paramSize, retValName);
        return retVal;
    }

    @Override
    public LLVMValueRef visitUnaryExp(SysYParser.UnaryExpContext ctx) {
        LLVMValueRef expVal = visit(ctx.exp());
        String unaryOP = ctx.unaryOp().getText();
        if (unaryOP.equals("+")) {
            return expVal;
        } else if (unaryOP.equals("-")){
            return LLVMBuildSub(builder, zero, expVal, namePrefix);
        } else {
            LLVMValueRef cmpResVal = LLVMBuildICmp(builder, LLVMIntNE, expVal, zero, namePrefix);
            LLVMValueRef xorResVal = LLVMBuildXor(builder, cmpResVal, trueRef, namePrefix);
            LLVMValueRef zExtResVal = LLVMBuildZExt(builder, xorResVal, i32Type, namePrefix);
            return zExtResVal;
        }
    }

    @Override
    public LLVMValueRef visitMulDivModExp(SysYParser.MulDivModExpContext ctx) {
        LLVMValueRef lExpVal = visit(ctx.lhs);
        LLVMValueRef rExpVal = visit(ctx.rhs);

        String op = ctx.op.getText();
        if (op.equals("*")) {
            return LLVMBuildMul(builder, lExpVal, rExpVal, namePrefix);
        } else if (op.equals("/")) {
            return LLVMBuildSDiv(builder, lExpVal, rExpVal, namePrefix);
        } else {
            return LLVMBuildSRem(builder, lExpVal, rExpVal, namePrefix);
        }
    }

    @Override
    public LLVMValueRef visitPlusMinusExp(SysYParser.PlusMinusExpContext ctx) {
        LLVMValueRef lExpVal = visit(ctx.lhs);
        LLVMValueRef rExpVal = visit(ctx.rhs);

        String op = ctx.op.getText();
        if (op.equals("+")) {
            return LLVMBuildAdd(builder, lExpVal, rExpVal, namePrefix);
        } else {
            return LLVMBuildSub(builder, lExpVal, rExpVal, namePrefix);
        }
    }


    /**
     * 返回 lVal 对应在内存中的指针
     * @param ctx
     * @return
     */
    @Override
    public LLVMValueRef visitLVal(SysYParser.LValContext ctx) {
        String varName = ctx.IDENT().getText();
        Symbol varSymbol = currentScope.resolve(varName);
        LLVMValueRef lValRef = varSymbol.getValueRef();
        if (ctx.exp(0) != null) {
            LLVMValueRef idxExpRef = visit(ctx.exp(0));
            // 判断是指针还是向量
            int lValTypeKind = LLVMGetTypeKind(LLVMGetAllocatedType(lValRef));
            if (lValTypeKind == LLVMPointerTypeKind) {
                // 指针
                LLVMValueRef lVal = LLVMBuildLoad(builder, lValRef, namePrefix);
                PointerPointer<Pointer> indices = new PointerPointer<>(new LLVMValueRef[]{idxExpRef});
                lValRef = LLVMBuildGEP(builder, lVal, indices, 1, namePrefix);
            } else {
                // 向量
                PointerPointer<Pointer> indices = new PointerPointer<>(new LLVMValueRef[]{zero, idxExpRef});
                lValRef = LLVMBuildGEP(builder, lValRef, indices, 2, namePrefix);
            }
        }
        return lValRef;
    }

    @Override
    public LLVMValueRef visitExpCond(SysYParser.ExpCondContext ctx) {
        return visit(ctx.exp());
    }

    @Override
    public LLVMValueRef visitGLCond(SysYParser.GLCondContext ctx) {
        LLVMValueRef lCondVal = visit(ctx.l_cond);
        LLVMValueRef rCondVal = visit(ctx.r_cond);

        LLVMValueRef condVal = LLVMBuildICmp(builder, opMap.get(ctx.op.getText()), lCondVal, rCondVal, namePrefix);
        return LLVMBuildZExt(builder, condVal, i32Type, namePrefix);
    }

    @Override
    public LLVMValueRef visitEQCond(SysYParser.EQCondContext ctx) {
        LLVMValueRef lCondVal = visit(ctx.l_cond);
        LLVMValueRef rCondVal = visit(ctx.r_cond);

        LLVMValueRef condVal = LLVMBuildICmp(builder, opMap.get(ctx.op.getText()), lCondVal, rCondVal, namePrefix);
        return LLVMBuildZExt(builder, condVal, i32Type, namePrefix);
    }

    @Override
    public LLVMValueRef visitAndCond(SysYParser.AndCondContext ctx) {
        LLVMValueRef lCondVal = LLVMBuildICmp(builder, LLVMIntNE, visit(ctx.l_cond), zero, namePrefix);
        LLVMValueRef rCondVal = LLVMBuildICmp(builder, LLVMIntNE, visit(ctx.r_cond), zero, namePrefix);

        LLVMValueRef andCondVal = LLVMBuildAnd(builder, lCondVal, rCondVal, namePrefix);
        return LLVMBuildZExt(builder, andCondVal, i32Type, namePrefix);
    }

    @Override
    public LLVMValueRef visitOrCond(SysYParser.OrCondContext ctx) {
        LLVMValueRef lCondVal = LLVMBuildICmp(builder, LLVMIntNE, visit(ctx.l_cond), zero, namePrefix);
        LLVMValueRef rCondVal = LLVMBuildICmp(builder, LLVMIntNE, visit(ctx.r_cond), zero, namePrefix);

        LLVMValueRef orCondVal = LLVMBuildOr(builder, lCondVal, rCondVal, namePrefix);
        return LLVMBuildZExt(builder, orCondVal, i32Type, namePrefix);
    }

    @Override
    public LLVMValueRef visitBlock(SysYParser.BlockContext ctx) {
        // related to type checking and symbol table generating
        if (!skipFuncScope) {
            LocalScope localScope = new LocalScope(currentScope);
            String localScopeName = localScope.getName() + localScopeCounter;
            localScope.setName(localScopeName);

            localScopeCounter++;

            currentScope = localScope;
        }

        super.visitBlock(ctx);

        // related to type checking and symbol table generating
        if (!skipFuncScope) {
            currentScope = currentScope.getEnclosingScope();
        }

        return null;
    }

    @Override
    public LLVMValueRef visitProgram(SysYParser.ProgramContext ctx) {
        // related to type checking and symbol table generating
        globalScope = new GlobalScope(null);
        currentScope = globalScope;

        super.visitProgram(ctx);
        LLVMPrintModuleToFile(module, destPath, error);

        // related to type checking and symbol table generating
        currentScope = currentScope.getEnclosingScope();
        return null;
    }

    /* below are private methods related to type checking and symbol table generating*/
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
            outputErrorMsg(TypeCheckListener.ErrorType.UNDEFINED_VAR, getLine(lValContext), lValName);
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
                        outputErrorMsg(TypeCheckListener.ErrorType.NOT_ARRAY, getLine(lValContext), lValName);
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
            outputErrorMsg(TypeCheckListener.ErrorType.UNDEFINED_FUNC, getLine(callExpContext), funcName);
        } else if (!(funcSymbol instanceof FunctionSymbol)) {
            outputErrorMsg(TypeCheckListener.ErrorType.NOT_FUNC, getLine(callExpContext), funcName);
        } else {
            FunctionType functionType = (FunctionType) funcSymbol.getType();
            if (checkFuncRParams(callExpContext, functionType)) {
                return resolveReturnType(functionType);
            } else {
                outputErrorMsg(TypeCheckListener.ErrorType.FUNC_PARAM_TYPE_MISMATCH, getLine(callExpContext), "");
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
            outputErrorMsg(TypeCheckListener.ErrorType.OPERATION_TYPE_MISMATCH, getLine(ctx), "");
        }
        return operandType;
    }

    private Type resolveTwoIntOPType(Type leftOperandType, Type rightOperandType, ParserRuleContext ctx) {
        if (leftOperandType != null && rightOperandType != null) {
            if (leftOperandType.equals(rightOperandType) && isIntType(leftOperandType)) {
                return leftOperandType;
            } else {
                outputErrorMsg(TypeCheckListener.ErrorType.OPERATION_TYPE_MISMATCH, getLine(ctx), "");
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

    private void outputErrorMsg(TypeCheckListener.ErrorType type, int lineNumber, String msg) {
        System.err.println("Error type " + errorTypeMap.get(type) + " at Line " + lineNumber + ": " +
                errorTypeBaseMsg.get(type) + msg);
        hasError = true;
    }

    private static int getDecimal(String text) {
        if (text.startsWith("0x") || text.startsWith("0X")) {
            return Integer.parseInt(text.substring(2), 16);
        } else if (text.startsWith("0") && text.length() > 1) {
            return Integer.parseInt(text, 8);
        } else {
            return Integer.parseInt(text, 10);
        }
    }
}


/**
 * lab6 后记：
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * ①wired first thing: 注释掉ifStmt的方法体后报nullPointerException
 * 原因：scope切换错误，FunctionAndVarIRVisitor跳过了if语句，但是typeCheckListener有处理if语句
 * 导致分析if语句近邻的block的时候scope切换成if语句的scope，导致符号解析得到null
 * example:
 *
 * int main() {
 *     if (1) {
 *     }
 *
 *     {
 *         int d = 15;
 *         return d;
 *     }
 * }
 * 解决方案：处理 ifStmt 就好了，注释掉整个 ifStmt而不是单独注释方法体
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * ②wired second thing: "error: instruction expected to be numbered"
 * 原因：if 语句中有 return 语句，导致 LLVM 编译器会分配编号给默认生成的 basic block，导致后续的预期编号不一致
 * example:
 *
 * int main() {
 *     return 0;
 *     return 1;
 * }
 * 解决方案：
 * (1) 所有的build命名都不用空字符串
 * (2) 修改 visitIfStmt，通过判断 if 语句中是否有 return 语句来判断是否要跳转到 exit block
 * 但是要在函数定义时重置 isReturn 变量为 false，避免上一个函数的影响，导致 error: expected instruction opcode
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 *
 * lab7 后记
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * ①core dump
 * 原因：不能先遍历符号表再遍历生成 IR，这样会导致符号解析获得 LLVMValueRef 为 null，再 Load 则出现 core dump
 * example:
 *
 * int a = 2;
 * int func() {
 *     int b = a;
 *     int a = 1;
 *     return 0;
 * }
 * 解决方案：
 * (1) 修改框架，使得边生成符号表，边生成 IR
 * PS: 这里引申出 C 和 python 的区别，
 * python 如果作用域定义了局部变量则在定义之前的变量即使有全局变量也当作未初始化的局部变量
 * C 没有声明之前可以是全局变量
 */
