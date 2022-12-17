package parser_and_lexer;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

/**
 * @author WFS
 * @date 2022/11/15 22:43
 */
public class MyErrorListener extends BaseErrorListener {
    public boolean hasError = false;

    @Override
    public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine, String msg, RecognitionException e) {
        System.err.println("Error type A at Line " + line + ":" + msg);
        hasError = true;
    }
}
