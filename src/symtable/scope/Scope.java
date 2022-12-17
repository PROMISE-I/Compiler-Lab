package symtable.scope;

import symtable.symbol.Symbol;

import java.util.Map;

/**
 * @author WFS
 * @date 2022/12/17 11:18
 */
public interface Scope {
    public String getName();

    public void setName(String name);

    public Scope getEnclosingScope();

    public Map<String, Symbol> getSymbols();

    public void define(Symbol symbol);

    public Symbol resolve(String name);
}
