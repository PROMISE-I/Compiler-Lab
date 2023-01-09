package symtable.symbol;

import org.bytedeco.llvm.LLVM.LLVMValueRef;
import symtable.type.Type;

/**
 * @author WFS
 * @date 2022/12/17 11:18
 */
public interface Symbol {
    public String getName();

    public Type getType();

    public String toString();

    public LLVMValueRef getValueRef();

    public void setValueRef(LLVMValueRef valueRef);
}
