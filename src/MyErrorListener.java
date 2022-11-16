import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

/**
 * @author WFS
 * @date 2022/11/15 22:43
 */
public class MyErrorListener extends BaseErrorListener {
    boolean hasError = false;

    @Override
    public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine, String msg, RecognitionException e) {
        System.err.println("Error type A at line " + line + ":" + msg);
        hasError = true;
    }
}
