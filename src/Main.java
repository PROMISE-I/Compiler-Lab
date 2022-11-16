import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;

import java.io.IOException;
import java.util.List;

public class Main
{
    public static void main(String[] args) throws IOException {
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
                        if (text.startsWith("0x") || text.startsWith("0X")) {
                            text = String.valueOf(Integer.parseInt(text.substring(2), 16));
                        } else if (text.startsWith("0") && text.length() > 1) {
                            text = String.valueOf(Integer.parseInt(text, 8));
                        } else {
                            text = String.valueOf(Integer.parseInt(text, 10));
                        }
                    }
                    System.err.println(ruleName + " " + text + " at Line " + t.getLine() + ".");
                }
            }
        }
    }
}