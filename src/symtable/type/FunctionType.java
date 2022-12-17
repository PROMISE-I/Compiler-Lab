package symtable.type;

import symtable.scope.FunctionScope;

import java.util.LinkedList;
import java.util.List;

/**
 * @author WFS
 * @date 2022/12/17 11:55
 */
public class FunctionType implements Type{
    FunctionScope functionScope;

    Type returnType;

    List<Type> paramTypes = new LinkedList<>();

    public FunctionType(FunctionScope functionScope, Type returnType) {
        this.functionScope = functionScope;
        this.returnType = returnType;
    }

    public Type getReturnType() {
        return returnType;
    }

    public Type getParamTypes(int i) {
        if (i < paramTypes.size()) return paramTypes.get(i);
        else return null;
    }

    public int getParamSize() {
        return this.paramTypes.size();
    }

    public void addParamType(Type paramType) {
        this.paramTypes.add(paramType);
    }

    public FunctionScope getFunctionScope() {
        return this.functionScope;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof FunctionType) {
            return this.returnType.equals(((FunctionType) o).getReturnType()) &&
                    this.functionScope.equals(((FunctionType) o).getFunctionScope());
        }
        return false;
    }
}
