import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.llvm.LLVM.*;
import symtable.scope.GlobalScope;
import symtable.scope.LocalScope;
import symtable.scope.Scope;
import symtable.symbol.FunctionSymbol;
import symtable.symbol.Symbol;
import symtable.type.FunctionType;

import java.util.List;

import static org.bytedeco.llvm.global.LLVM.*;

/**
 * @author WFS
 * @date 2023/1/7 22:09
 */
public class FunctionAndVarIRVisitor extends SysYParserBaseVisitor<LLVMValueRef>{
    // fields related to symbol table
    private GlobalScope globalScope = null;

    private Scope currentScope = null;

    private List<LocalScope> localScopeList = null;

    private int localScopeCounter;

    // fields related to IR generator
    static final LLVMModuleRef module = LLVMModuleCreateWithName("module");
    static final LLVMBuilderRef builder = LLVMCreateBuilder();
    static final LLVMTypeRef voidType = LLVMVoidType();
    static final LLVMTypeRef i32Type = LLVMInt32Type();

    static final LLVMValueRef zero = LLVMConstInt(i32Type, 0, 0);

    static final LLVMValueRef trueRef = LLVMConstInt(LLVMInt1Type(), 1, 0);

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
        LLVMValueRef ret = super.visitFuncDef(ctx);

        currentScope = currentScope.getEnclosingScope();

        return ret;
    }

    @Override
    public LLVMValueRef visitAssignStmt(SysYParser.AssignStmtContext ctx) {
        LLVMValueRef lValRef = visit(ctx.lVal());
        LLVMValueRef expVal = visit(ctx.exp());
        LLVMBuildStore(builder, expVal, lValRef);

        return LLVMBuildLoad(builder, lValRef, "");
    }

    @Override
    public LLVMValueRef visitReturnStmt(SysYParser.ReturnStmtContext ctx) {
        LLVMValueRef retRef = visit(ctx.exp());
        LLVMBuildRet(builder, retRef);

        return null;
    }

    @Override
    public LLVMValueRef visitConstDef(SysYParser.ConstDefContext ctx) {
        if (ctx.constExp().isEmpty()) {
            String varName = ctx.IDENT().getText();
            Symbol varSymbol = currentScope.resolve(varName);
            /* allocate var */
            LLVMValueRef varPointer = LLVMBuildAlloca(builder, i32Type, varName);
            LLVMValueRef constInitVal = visit(ctx.constInitVal());
            LLVMBuildStore(builder, constInitVal, varPointer);

            varSymbol.setValueRef(varPointer);
        } else {
            String arrayName = ctx.IDENT().getText();
            Symbol arraySymbol = currentScope.resolve(arrayName);
            /* allocate array var */
            // 获得数组长度, 只处理一维数组
            LLVMValueRef lengthRef = visit(ctx.constExp(0));
            int length = (int) LLVMConstIntGetZExtValue(lengthRef);
            // 创建数组类型并分配空间
            LLVMTypeRef arrayType = LLVMVectorType(i32Type, length);
            LLVMValueRef arrayPointer = LLVMBuildAlloca(builder, arrayType, arrayName);

            for (int i = 0; i < length; i++) {
                // 通过GEP指令获得下标对应的元素指针
                LLVMValueRef idxesRef[] = new LLVMValueRef[]{zero, LLVMConstInt(i32Type, i, 0)}; // TODO 这边长度设置为2是不是因为arrayPointer是指向数组的指针？
                PointerPointer idxesPointer = new PointerPointer(idxesRef);
                LLVMValueRef elePointer = LLVMBuildGEP(builder, arrayPointer, idxesPointer, 2, "pointer");
                // 获得下标对应的初值
                LLVMValueRef constInitValRef = zero;
                SysYParser.ArrayConstInitValContext arrayInitValContext = (SysYParser.ArrayConstInitValContext) ctx.constInitVal();
                if (i < arrayInitValContext.constInitVal().size()) {
                    constInitValRef = visit(arrayInitValContext.constInitVal(i));
                }
                // 存入初始值
                LLVMBuildStore(builder, constInitValRef, elePointer);
            }

            arraySymbol.setValueRef(arrayPointer);
        }
        return super.visitConstDef(ctx);
    }

    @Override
    public LLVMValueRef visitConstExpConstInitVal(SysYParser.ConstExpConstInitValContext ctx) {
        return visit(ctx.constExp().exp());
    }

    @Override
    public LLVMValueRef visitVarDef(SysYParser.VarDefContext ctx) {
        if (ctx.constExp().isEmpty()) {
            String varName = ctx.IDENT().getText();
            Symbol varSymbol = currentScope.resolve(varName);
            /* allocate var */
            LLVMValueRef varPointer = LLVMBuildAlloca(builder, i32Type, varName);
            LLVMValueRef initVal = zero;
            if (ctx.initVal() != null) initVal = visit(ctx.initVal());
            LLVMBuildStore(builder, initVal, varPointer);

            varSymbol.setValueRef(varPointer);
        } else {
            String arrayName = ctx.IDENT().getText();
            Symbol arraySymbol = currentScope.resolve(arrayName);
            /* allocate array var */
            // 获得数组长度, 只处理一维数组
            LLVMValueRef lengthRef = visit(ctx.constExp(0));
            int length = (int) LLVMConstIntGetZExtValue(lengthRef);
            // 创建数组类型并分配空间
            LLVMTypeRef arrayType = LLVMVectorType(i32Type, length);
            LLVMValueRef arrayPointer = LLVMBuildAlloca(builder, arrayType, arrayName);

            for (int i = 0; i < length; i++) {
                if (ctx.initVal() != null) {
                    // 通过GEP指令获得下标对应的元素指针
                    LLVMValueRef idxesRef[] = new LLVMValueRef[]{zero, LLVMConstInt(i32Type, i, 0)}; // TODO 这边长度设置为2是不是因为arrayPointer是指向数组的指针？
                    PointerPointer idxesPointer = new PointerPointer(idxesRef);
                    LLVMValueRef elePointer = LLVMBuildGEP(builder, arrayPointer, idxesPointer, 2, "pointer");
                    // 获得下标对应的初值
                    LLVMValueRef initValRef = zero;
                    SysYParser.ArrayInitValContext arrayInitValContext = (SysYParser.ArrayInitValContext) ctx.initVal();
                    if (i < arrayInitValContext.initVal().size()) {
                        initValRef = visit(arrayInitValContext.initVal(i));
                    }
                    // 存入初始值
                    LLVMBuildStore(builder, initValRef, elePointer);
                }
            }

            arraySymbol.setValueRef(arrayPointer);
        }
        return super.visitVarDef(ctx);
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
        PointerPointer indices = null;
        if (ctx.funcRParams() != null) {
            paramSize = ctx.funcRParams().param().size();
            indices = new PointerPointer(paramSize);
            for (int i = 0; i < paramSize; i++) {
                // 这里函数参数只为整型
                LLVMValueRef argVal = visit(ctx.funcRParams().param(i).exp());
                indices.put(argVal);
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
            return LLVMBuildSub(builder, zero, expVal, "");
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
            PointerPointer indices = new PointerPointer(new LLVMValueRef[]{zero, idxExpRef});
            lValRef = LLVMBuildGEP(builder, lValRef, indices, 2, "");
        }
        return lValRef;
    }

    @Override
    public LLVMValueRef visitBlock(SysYParser.BlockContext ctx) {
        currentScope = localScopeList.get(localScopeCounter);
        localScopeCounter++;

        LLVMValueRef ret = super.visitBlock(ctx);

        currentScope = currentScope.getEnclosingScope();

        return ret;
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