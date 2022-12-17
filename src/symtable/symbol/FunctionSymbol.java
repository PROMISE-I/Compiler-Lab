package symtable.symbol;

import symtable.type.FunctionType;

/**
 * @author WFS
 * @date 2022/12/17 11:31
 */
public class FunctionSymbol extends BaseSymbol{
    public FunctionSymbol(FunctionType functionType) {super(functionType.getFunctionScope().getName(), functionType);}
}
