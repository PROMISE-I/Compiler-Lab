import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;

import java.io.IOException;
import java.util.List;

public class Main
{
    public static void main(String[] args) throws Exception {
        funcAndVarIRGenerate(args);
    }

    public static void funcAndVarIRGenerate(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("input path is required");
        }
        String source = args[0];
        CharStream input = CharStreams.fromFileName(source);

        SysYLexer sysYLexer = new SysYLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(sysYLexer);

        SysYParser sysYParser = new SysYParser(tokens);
        ParseTree tree = sysYParser.program();

        // 类型检查 & 构建符号表 & 构建 IR
        FunctionAndVarIRVisitor visitor = new FunctionAndVarIRVisitor(args[1]);
        visitor.visit(tree);
    }

    public static void irGenerate(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("input path is required");
        }
        String source = args[0];
        CharStream input = CharStreams.fromFileName(source);

        SysYLexer sysYLexer = new SysYLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(sysYLexer);

        SysYParser sysYParser = new SysYParser(tokens);
        ParseTree tree = sysYParser.program();

        IRVisitor visitor = new IRVisitor(args[1]);
        visitor.visit(tree);
    }

    public static void typeCheckAndRename(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("input path is required");
        }
        String source = args[0];
        CharStream input = CharStreams.fromFileName(source);

        SysYLexer sysYLexer = new SysYLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(sysYLexer);

        SysYParser sysYParser = new SysYParser(tokens);
        ParseTree tree = sysYParser.program();

        ParseTreeWalker walker = new ParseTreeWalker();
        TypeCheckListener listener = new TypeCheckListener();
        walker.walk(listener, tree);

        if (!listener.hasError) {
            if (args.length < 4) {
                System.err.println("input line number/row number/replacing identity is required");
            }
            int lineNumber = Integer.parseInt(args[1]);
            int rowNumber = Integer.parseInt(args[2]);
            String replacingIdentity = args[3];
            FindTargetSymbolListener findTargetSymbolListener = new FindTargetSymbolListener(lineNumber, rowNumber,
                    listener.getGlobalScope(), listener.getLocalScopeList());
            walker.walk(findTargetSymbolListener, tree);

            RenameListener renameListener = new RenameListener(findTargetSymbolListener.getTargetSymbol(), replacingIdentity,
                    listener.getGlobalScope(), listener.getLocalScopeList());
            walker.walk(renameListener, tree);
        }
    }

    public static void checkParser(String[] args) throws IOException {
        ParserErrorListener parserErrorListener = new ParserErrorListener();

        if (args.length < 1) {
            System.err.println("input path is required");
        }
        String source = args[0];
        CharStream input = CharStreams.fromFileName(source);

        SysYLexer sysYLexer = new SysYLexer(input);
        // List<? extends Token> _tokens = sysYLexer.getAllTokens();
        CommonTokenStream tokens = new CommonTokenStream(sysYLexer);
        SysYParser sysYParser = new SysYParser(tokens);

        sysYParser.removeErrorListeners();
        sysYParser.addErrorListener(parserErrorListener);

        ParseTree tree = sysYParser.program();
        if (!parserErrorListener.hasError) {
            HighLightVisitor visitor = new HighLightVisitor();
            visitor.visit(tree);
        }
    }

    public static void checkLexer(String[] args) throws IOException {
        MyErrorListener myErrorListener = new MyErrorListener();

        if (args.length < 1) {
            System.err.println("input path is required");
        }
        String source = args[0];
        CharStream input = CharStreams.fromFileName(source);
        SysYLexer sysYLexer = new SysYLexer(input);

        sysYLexer.removeErrorListeners();
        sysYLexer.addErrorListener(myErrorListener);

        List<? extends Token> tokens = sysYLexer.getAllTokens();
        if (!myErrorListener.hasError) {
            String[] ruleNames = sysYLexer.getRuleNames();
            for (Token t : tokens) {
                String ruleName = ruleNames[t.getType() - 1];
                if (!(ruleName.equals("WS") || ruleName.equals("LINE_COMMENT") || ruleName.equals("MULTILINE_COMMENT"))) {
                    String text = t.getText();
                    if (ruleName.equals("INTEGR_CONST")) {
                        text = HighLightVisitor.getDecimal(text);
                    }
                    System.err.println(ruleName + " " + text + " at Line " + t.getLine() + "");
                }
            }
        }
    }
}