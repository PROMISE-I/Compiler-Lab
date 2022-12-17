package symtable.symbol;

import symtable.type.Type;

/**
 * @author WFS
 * @date 2022/12/17 11:19
 */
public class BaseSymbol implements Symbol {
    final String name;
    final Type type;

    public BaseSymbol(String name, Type type) {
        this.name = name;
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public Type getType() {
        return type;
    }

    @Override
    public String toString() {
        return "name" + name + "type" + type;
    }
}
