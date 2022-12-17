package symtable.scope;

import symtable.symbol.BasicTypeSymbol;
import symtable.type.BaseType;

/**
 * @author WFS
 * @date 2022/12/17 11:37
 */
public class GlobalScope extends BaseScope {

    public GlobalScope(Scope enclosingScope) {
        super("GlobalScope", enclosingScope);
        define(new BasicTypeSymbol(BaseType.getTypeInt()));
        define(new BasicTypeSymbol(BaseType.getTypeVoid()));
    }
}
