import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.llvm.LLVM.*;
import static org.bytedeco.llvm.global.LLVM.*;


/**
 * @author WFS
 * @date 2022/12/29 23:23
 */
public class IRVisitor extends SysYParserBaseVisitor<Integer>{
    LLVMModuleRef module;
    LLVMBuilderRef builder;
    LLVMTypeRef i32Type;

    String destPath;

    public static final BytePointer error = new BytePointer();

    public IRVisitor(String destPath) {
        //初始化LLVM
        LLVMInitializeCore(LLVMGetGlobalPassRegistry());
        LLVMLinkInMCJIT();
        LLVMInitializeNativeAsmPrinter();
        LLVMInitializeNativeAsmParser();
        LLVMInitializeNativeTarget();

        module = LLVMModuleCreateWithName("module");
        builder = LLVMCreateBuilder();
        i32Type = LLVMInt32Type();
        this.destPath = destPath;
    }

    @Override
    public Integer visitFuncDef(SysYParser.FuncDefContext ctx) {
        LLVMTypeRef returnType = i32Type;

        int paraSize;
        if (ctx.funcFParams() == null) paraSize = 0;
        else paraSize = ctx.funcFParams().funcFParam().size();

        PointerPointer<Pointer> argumentTypes = new PointerPointer<>(paraSize);
        for (int i = 0; i < paraSize; i++) {
            argumentTypes.put(i, i32Type);
        }

        LLVMTypeRef ft = LLVMFunctionType(returnType, argumentTypes, paraSize, 0);

        LLVMValueRef function = LLVMAddFunction(module, ctx.IDENT().getText(), ft);

        // 添加基本快
        LLVMBasicBlockRef block = LLVMAppendBasicBlock(function, "mainEntry");

        LLVMPositionBuilderAtEnd(builder, block);

        return super.visitFuncDef(ctx);
    }

    @Override
    public Integer visitReturnStmt(SysYParser.ReturnStmtContext ctx) {
        int retVal = visit(ctx.exp());
        LLVMValueRef retValRef = LLVMConstInt(i32Type, retVal, 0);

        LLVMBuildRet(builder, retValRef);

        return super.visitReturnStmt(ctx);
    }

    @Override
    public Integer visitProgram(SysYParser.ProgramContext ctx) {
        visit(ctx.compUnit());
        LLVMPrintModuleToFile(module, destPath, error);
        return super.visitProgram(ctx);
    }

    @Override
    public Integer visitParenExp(SysYParser.ParenExpContext ctx) {
        return visit(ctx.exp());
    }

    @Override
    public Integer visitNumberExp(SysYParser.NumberExpContext ctx) {
        return getDecimal(ctx.number().getText());
    }

    @Override
    public Integer visitUnaryExp(SysYParser.UnaryExpContext ctx) {
        int opd = visit(ctx.exp());
        switch (ctx.unaryOp().getText()) {
            case "-":
                return -opd;
            case "!":
                if (opd == 0) return 1;
                else return 0;
            default:
                return opd;
        }
    }

    @Override
    public Integer visitMulDivModExp(SysYParser.MulDivModExpContext ctx) {
        int lOpd = visit(ctx.lhs);
        int rOpd = visit(ctx.rhs);
        switch (ctx.op.getText()) {
            case "*":
                return lOpd * rOpd;
            case "/":
                return lOpd / rOpd;
            default:
                return lOpd % rOpd;
        }
    }

    @Override
    public Integer visitPlusMinusExp(SysYParser.PlusMinusExpContext ctx) {
        int lOpd = visit(ctx.lhs);
        int rOpd = visit(ctx.rhs);
        if ("+".equals(ctx.op.getText())) {
            return lOpd + rOpd;
        }
        return lOpd - rOpd;
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
