import org.antlr.v4.runtime.tree.RuleNode;
import org.antlr.v4.runtime.tree.TerminalNode;

/**
 * @author WFS
 * @date 2022/11/24 11:21
 */
public class HighLightVisitor extends SysYParserBaseVisitor<Void>{
    @Override
    public Void visitChildren(RuleNode node) {
        System.err.println();
        return super.visitChildren(node);
    }

    @Override
    public Void visitTerminal(TerminalNode node) {
        return super.visitTerminal(node);
    }
}
