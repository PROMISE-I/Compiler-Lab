package symtable.symbol;

import symtable.type.Type;

/**
 * @author WFS
 * @date 2022/12/17 11:47
 */
public class VariableSymbol extends BaseSymbol {
    public VariableSymbol(String name, Type type) {
        super(name, type);
    }
}
