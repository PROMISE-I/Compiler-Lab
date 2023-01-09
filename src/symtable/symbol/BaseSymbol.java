package symtable.symbol;

import org.bytedeco.llvm.LLVM.LLVMValueRef;
import symtable.type.Type;

/**
 * @author WFS
 * @date 2022/12/17 11:19
 */
public class BaseSymbol implements Symbol {
    final String name;
    final Type type;

    LLVMValueRef valueRef;

    public BaseSymbol(String name, Type type) {
        this.name = name;
        this.type = type;
        this.valueRef = null;
    }

    public String getName() {
        return name;
    }

    public Type getType() {
        return type;
    }

    @Override
    public LLVMValueRef getValueRef() {
        return valueRef;
    }

    @Override
    public void setValueRef(LLVMValueRef valueRef) {
        this.valueRef = valueRef;
    }

    @Override
    public String toString() {
        return "name" + name + "type" + type;
    }
}
