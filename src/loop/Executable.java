package loop;

import loop.ast.Node;
import loop.ast.script.FunctionDecl;
import loop.ast.script.Unit;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * @author dhanji@gmail.com (Dhanji R. Prasanna)
 */
public class Executable {
  private volatile String source;      // Raw source code, discarded after compile.
  private final List<String> lines;    // Loop source code lines (for error tracing).


  private Unit unit;             // Loop compilation unit.
  private Node node;             // If a fragment and not a whole unit (mutually exclusive with unit)


  private String compiled;  // Compiled MVEL script.
  private List<ParseError> parseErrors;

  public Executable(Reader source) {
    List<String> lines = new ArrayList<String>();
    StringBuilder builder;
    try {
      BufferedReader br = new BufferedReader(source);

      builder = new StringBuilder();
      while (br.ready()) {
        String line = br.readLine();
        builder.append(line);
        builder.append('\n');

        lines.add(line);
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    this.source = builder.toString();
    this.lines = lines;
  }

  public String getCompiled() {
    return compiled;
  }

  private Unit parse(String input) {
    Parser parser = new Parser(new Tokenizer(input).tokenize());
    Unit unit = null;
    try {
      unit = parser.script();
      unit.reduceAll();
    } catch (LoopSyntaxException e) {
      // Ignored.
    }
    if (!parser.getErrors().isEmpty())
      this.parseErrors = parser.getErrors();

    return unit;
  }

  public void printErrors() {
    for (int i = 0, errorsSize = parseErrors.size(); i < errorsSize; i++) {
      ParseError error = parseErrors.get(i);
      System.out.println((i + 1) + ") " + error.getMessage());
      System.out.println();

      // Show some context around this file.
      int lineNumber = error.line() + 1;
      int column = error.column();

      // Unwrap to the previous line if the highlighted line is empty.
      if (lineNumber > 0 && (lines.get(lineNumber - 1).trim().isEmpty() || column == 0)) {
        lineNumber -= 1;
        column = lines.get(lineNumber - 1).length();
      }

      if (error.line() > 0)
        System.out.println("  " + error.line() + ": " + lines.get(lineNumber - 2));

      System.out.println("  " + lineNumber + ": " + lines.get(lineNumber - 1));
      int spaces = column + Integer.toString(lineNumber).length() + 1;
      System.out.println("  " + whitespace(spaces) + "^\n");
    }
  }

  public boolean hasErrors() {
    return parseErrors != null;
  }

  public void compile() {
    this.unit = parse(source);
    if (hasErrors())
      return;

    this.compiled = new CodeWriter().write(unit);
    this.source = null;
  }

  public void compileExpression() {
    Parser parser = new Parser(new Tokenizer(source).tokenize());
    Node line = parser.line();
    if (hasErrors())
      return;

    this.node = new Reducer(line).reduce();
    this.compiled = new CodeWriter().write(node);
    this.source = null;
  }

  public void compileFunction() {
    Parser parser = new Parser(new Tokenizer(source).tokenize());
    FunctionDecl functionDecl = parser.functionDecl();
    if (hasErrors())
      return;

    this.node = new Reducer(functionDecl).reduce();
    this.compiled = new CodeWriter().write(node);
    this.source = null;
  }

  private static String whitespace(int amount) {
    StringBuilder builder = new StringBuilder(amount);
    for (int i = 0; i < amount; i++) {
      builder.append(' ');
    }
    return builder.toString();
  }

  public void runMain(boolean runMain) {
    if (runMain)
      compiled += "; main();";
  }

  public List<ParseError> getParseErrors() {
    return parseErrors;
  }
}
