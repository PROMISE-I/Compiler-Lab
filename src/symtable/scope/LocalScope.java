package symtable.scope;

/**
 * @author WFS
 * @date 2022/12/17 11:39
 */
public class LocalScope extends BaseScope {
    public LocalScope(Scope enclosingScope) {
        super("LocalScope", enclosingScope);
    }
}
