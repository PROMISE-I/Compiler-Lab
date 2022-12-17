package symtable.symbol;

import symtable.type.BaseType;

/**
 * @author WFS
 * @date 2022/12/17 11:42
 */
public class BasicTypeSymbol extends BaseSymbol {
    public BasicTypeSymbol(BaseType baseType) {
        super(baseType.getName(), baseType);
    }

    @Override
    public String toString() {
        return name;
    }
}
