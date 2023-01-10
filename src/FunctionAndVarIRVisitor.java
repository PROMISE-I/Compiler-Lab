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
import symtable.type.FunctionType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.bytedeco.llvm.global.LLVM.*;

/**
 * @author WFS
 * @date 2023/1/7 22:09
 */
public class FunctionAndVarIRVisitor extends SysYParserBaseVisitor<LLVMValueRef>{
    // fields related to symbol table
    private GlobalScope globalScope;

    private Scope currentScope;

    private List<LocalScope> localScopeList;

    private int localScopeCounter;

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

    public static final BytePointer error = new BytePointer();

    public FunctionAndVarIRVisitor(GlobalScope globalScope, List<LocalScope> localScopeList, String destPath) {
        this.globalScope = globalScope;
        this.localScopeList = localScopeList;
        this.currentScope = globalScope;
        this.localScopeCounter = 0;

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
        // change scope
        String funcName =ctx.IDENT().getText();
        FunctionSymbol functionSymbol = (FunctionSymbol) globalScope.resolve(funcName, FunctionSymbol.class);
        FunctionType functionType = (FunctionType) functionSymbol.getType();
        currentScope = functionType.getFunctionScope();

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
            argumentTypes.put(i, i32Type);
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
                LLVMValueRef fParamRef = LLVMBuildAlloca(builder, i32Type, fParamName);
                LLVMBuildStore(builder, rParamRef, fParamRef);

                fParamSymbol.setValueRef(fParamRef);
            }
        }

        // continue traversal
        super.visitFuncDef(ctx);

        currentScope = currentScope.getEnclosingScope();

        // 为没有返回语句的函数增加默认返回语句
        int retStmtIdx = ctx.block().children.size() - 2;
        ParseTree targetContext = ctx.block().children.get(retStmtIdx).getChild(0);
        if (!(targetContext instanceof SysYParser.ReturnStmtContext) && returnType.equals(voidType)) {
            LLVMBuildRetVoid(builder);
        }

        return null;
    }

    @Override
    public LLVMValueRef visitAssignStmt(SysYParser.AssignStmtContext ctx) {
        LLVMValueRef lValRef = visit(ctx.lVal());
        LLVMValueRef expVal = visit(ctx.exp());
        LLVMBuildStore(builder, expVal, lValRef);

        return null;
    }

    @Override
    public LLVMValueRef visitIfStmt(SysYParser.IfStmtContext ctx) {
//        /* append basic block */
//        LLVMValueRef functionRef = getContainerFunctionRef();
//        LLVMBasicBlockRef ifTrueBlock = LLVMAppendBasicBlock(functionRef, "if_true");
//        LLVMBasicBlockRef ifFalseBlock = LLVMAppendBasicBlock(functionRef, "if_false");
//        LLVMBasicBlockRef ifExitBlock = LLVMAppendBasicBlock(functionRef, "if_exit");
//        generateCondBrInstr(ctx, ifTrueBlock, ifFalseBlock);
//
//        // true
//        LLVMPositionBuilderAtEnd(builder, ifTrueBlock);
//        visit(ctx.if_stmt);
//        LLVMBuildBr(builder, ifExitBlock);
//
//        // false
//        LLVMPositionBuilderAtEnd(builder, ifFalseBlock);
//        if (ctx.else_stmt != null) visit(ctx.else_stmt);
//        LLVMBuildBr(builder, ifExitBlock);
//
//        // if-stmt exit
//        LLVMPositionBuilderAtEnd(builder, ifExitBlock);
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

    private void generateCondBrInstr(SysYParser.IfStmtContext ctx, LLVMBasicBlockRef ifTrueBlock, LLVMBasicBlockRef ifFalseBlock) {
        LLVMValueRef condVal = visit(ctx.cond());
        LLVMValueRef condition = LLVMBuildICmp(builder, LLVMIntNE, condVal, zero, "");
        LLVMBuildCondBr(builder, condition, ifTrueBlock, ifFalseBlock);
    }

    @Override
    public LLVMValueRef visitReturnStmt(SysYParser.ReturnStmtContext ctx) {
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
        LLVMValueRef lengthRef = visit(ctx.constExp(0));
        int length = (int) LLVMConstIntGetZExtValue(lengthRef);
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
    public LLVMValueRef visitVarDef(SysYParser.VarDefContext ctx) {
        String varName = ctx.IDENT().getText();
        Symbol varSymbol = currentScope.resolve(varName);
        LLVMValueRef varPointer;

        if (ctx.constExp().isEmpty()) {
            /* variable case */
            varPointer = handleVar(ctx, varName);
            varSymbol.setValueRef(varPointer);
        } else {
            /* array case */
            varPointer = handleArray(ctx, varName);
            varSymbol.setValueRef(varPointer);
        }

//        varSymbol.setValueRef(varPointer);
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
        LLVMValueRef lengthRef = visit(ctx.constExp(0));
        int length = (int) LLVMConstIntGetZExtValue(lengthRef);
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
        LLVMValueRef lVal = LLVMBuildLoad(builder, lValRef, "");
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
                // 这里函数参数只为整型
                LLVMValueRef argVal = visit(ctx.funcRParams().param(i).exp());
                indices.put(i, argVal);
            }
        }
        LLVMValueRef retVal = LLVMBuildCall(builder, funcRef, indices, paramSize, "");
        return retVal;
    }

    @Override
    public LLVMValueRef visitUnaryExp(SysYParser.UnaryExpContext ctx) {
        LLVMValueRef expVal = visit(ctx.exp());
        String unaryOP = ctx.unaryOp().getText();
        if (unaryOP.equals("+")) {
            return expVal;
        } else if (unaryOP.equals("-")){
            return LLVMBuildSub(builder, zero, expVal, "negVal");
        } else {
            LLVMValueRef cmpResVal = LLVMBuildICmp(builder, LLVMIntNE, expVal, zero, "");
            LLVMValueRef xorResVal = LLVMBuildXor(builder, cmpResVal, trueRef, "");
            LLVMValueRef zExtResVal = LLVMBuildZExt(builder, xorResVal, i32Type, "");
            return zExtResVal;
        }
    }

    @Override
    public LLVMValueRef visitMulDivModExp(SysYParser.MulDivModExpContext ctx) {
        LLVMValueRef lExpVal = visit(ctx.lhs);
        LLVMValueRef rExpVal = visit(ctx.rhs);

        String op = ctx.op.getText();
        if (op.equals("*")) {
            return LLVMBuildMul(builder, lExpVal, rExpVal, "");
        } else if (op.equals("/")) {
            return LLVMBuildSDiv(builder, lExpVal, rExpVal, "");
        } else {
            return LLVMBuildSRem(builder, lExpVal, rExpVal, "");
        }
    }

    @Override
    public LLVMValueRef visitPlusMinusExp(SysYParser.PlusMinusExpContext ctx) {
        LLVMValueRef lExpVal = visit(ctx.lhs);
        LLVMValueRef rExpVal = visit(ctx.rhs);

        String op = ctx.op.getText();
        if (op.equals("+")) {
            return LLVMBuildAdd(builder, lExpVal, rExpVal, "");
        } else {
            return LLVMBuildSub(builder, lExpVal, rExpVal, "");
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
            PointerPointer<Pointer> indices = new PointerPointer<>(new LLVMValueRef[]{zero, idxExpRef});
            lValRef = LLVMBuildGEP(builder, lValRef, indices, 2, "");
        }
        return lValRef;
    }

    @Override
    public LLVMValueRef visitGLCond(SysYParser.GLCondContext ctx) {
        LLVMValueRef lCondVal = visit(ctx.l_cond);
        LLVMValueRef rCondVal = visit(ctx.r_cond);

        LLVMValueRef condVal = LLVMBuildICmp(builder, opMap.get(ctx.op.getText()), lCondVal, rCondVal, "");
        return LLVMBuildZExt(builder, condVal, i32Type, "");
    }

    @Override
    public LLVMValueRef visitOrCond(SysYParser.OrCondContext ctx) {
        LLVMValueRef lCondVal = visit(ctx.l_cond);
        LLVMValueRef rCondVal = visit(ctx.r_cond);

        LLVMValueRef orCondVal = LLVMBuildOr(builder, lCondVal, rCondVal, "");
        return LLVMBuildZExt(builder, orCondVal, i32Type, "");
    }

    @Override
    public LLVMValueRef visitExpCond(SysYParser.ExpCondContext ctx) {
        return visit(ctx.exp());
    }

    @Override
    public LLVMValueRef visitAndCond(SysYParser.AndCondContext ctx) {
        LLVMValueRef lCondVal = visit(ctx.l_cond);
        LLVMValueRef rCondVal = visit(ctx.r_cond);

        LLVMValueRef andCondVal = LLVMBuildAnd(builder, lCondVal, rCondVal, "");
        return LLVMBuildZExt(builder, andCondVal, i32Type, "");
    }

    @Override
    public LLVMValueRef visitEQCond(SysYParser.EQCondContext ctx) {
        LLVMValueRef lCondVal = visit(ctx.l_cond);
        LLVMValueRef rCondVal = visit(ctx.r_cond);

        LLVMValueRef condVal = LLVMBuildICmp(builder, opMap.get(ctx.op.getText()), lCondVal, rCondVal, "");
        return LLVMBuildZExt(builder, condVal, i32Type, "");
    }

    @Override
    public LLVMValueRef visitBlock(SysYParser.BlockContext ctx) {
        currentScope = localScopeList.get(localScopeCounter);
        localScopeCounter++;

        super.visitBlock(ctx);

        currentScope = currentScope.getEnclosingScope();

        return null;
    }

    @Override
    public LLVMValueRef visitProgram(SysYParser.ProgramContext ctx) {
        super.visitProgram(ctx);
        LLVMPrintModuleToFile(module, destPath, error);
        return null;
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
