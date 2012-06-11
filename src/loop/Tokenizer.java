package loop;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Stack;

import loop.Token.Kind;

/**
 * @author Dhanji R. Prasanna
 */
public class Tokenizer {
  private final String input;

  public Tokenizer(String input) {
    try {
      // Clean input of leading whitespace on empty lines.
      StringBuilder cleaned = new StringBuilder();
      @SuppressWarnings("unchecked")
      List<String> lines = Util.toLines(new StringReader(input));
      for (int i = 0, linesSize = lines.size(); i < linesSize; i++) {
        String line = lines.get(i);
        if (!line.trim().isEmpty())
          cleaned.append(line);

        // Append newlines for all but the last line, because we don't want to
        // introduce an
        // unnecessary newline at the eof.
        if (i < linesSize - 1)
          cleaned.append('\n');
      }

      // Unless it explicitly has one.
      if (input.endsWith("\n") || input.endsWith("\r"))
        cleaned.append('\n');

      this.input = cleaned.toString();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static final int NON = 0; // MUST be zero
  private static final int SINGLE_TOKEN = 1;
  private static final int SEQUENCE_TOKEN = 2;

  private static final int[] DELIMITERS = new int[256];
  private static final boolean[] STRING_TERMINATORS = new boolean[256];

  static {
    DELIMITERS['-'] = SEQUENCE_TOKEN;
    DELIMITERS['='] = SEQUENCE_TOKEN;
    DELIMITERS['+'] = SEQUENCE_TOKEN;
    DELIMITERS['/'] = SEQUENCE_TOKEN;
    DELIMITERS['*'] = SEQUENCE_TOKEN;
    DELIMITERS['>'] = SEQUENCE_TOKEN;
    DELIMITERS['<'] = SEQUENCE_TOKEN;

    // SINGLE token delimiters are one char in length in any context
    DELIMITERS['\n'] = SINGLE_TOKEN;
    DELIMITERS['.'] = SINGLE_TOKEN;
    DELIMITERS[','] = SINGLE_TOKEN;
    DELIMITERS[':'] = SINGLE_TOKEN;
    DELIMITERS['('] = SINGLE_TOKEN;
    DELIMITERS[')'] = SINGLE_TOKEN;
    DELIMITERS['['] = SINGLE_TOKEN;
    DELIMITERS[']'] = SINGLE_TOKEN;
    DELIMITERS['{'] = SINGLE_TOKEN;
    DELIMITERS['}'] = SINGLE_TOKEN;

    STRING_TERMINATORS['"'] = true;
    STRING_TERMINATORS['\''] = true;
    STRING_TERMINATORS['`'] = true;
  }

  public List<Token> tokenize() {
    List<Token> tokens = new ArrayList<Token>();
    char[] input = this.input.toCharArray();

    int line = 0, column = 0;

    int i = 0, start = 0;
    boolean inWhitespace = false, inDelimiter = false, inComment = false, leading = true;
    char inStringSequence = 0;
    for (; i < input.length; i++) {
      char c = input[i];
      column++;

      if (c == '\n') {
        line++;
        column = 0;
      }

      // strings and sequences
      if (STRING_TERMINATORS[c] && !inComment) {

        if (inStringSequence > 0) {

          // end of the current string sequence. bake.
          if (inStringSequence == c) {
            // +1 to include the terminating token.
            bakeToken(tokens, input, i + 1, start, line, column);
            start = i + 1;

            inStringSequence = 0; // reset to normal language
            leading = false;
            continue;
          }
          // it's a string terminator but it's ok, it's part of the string,
          // ignore...

        } else {
          // Also bake if there is any leading tokenage.
          if (i > start) {
            bakeToken(tokens, input, i, start, line, column);
            start = i;
          }

          inStringSequence = c; // start string
        }
      }

      // skip everything if we're in a string...
      if (inStringSequence > 0)
        continue;

      if (c == '\n') {
        leading = true;
      }

      // Comments beginning with #
      if (c == '#') {
        inComment = true;
      }

      // We run the comment until the end of the line
      if (inComment) {
        if (c == '\n')
          inComment = false;

        start = i;
        continue;
      }

      // whitespace is ignored unless it is leading...
      if (isWhitespace(c)) {
        inDelimiter = false;

        if (!inWhitespace) {
          // bake token
          bakeToken(tokens, input, i, start, line, column);
          inWhitespace = true;
        }

        // leading whitespace is a special token...
        if (leading) {
          tokens.add(new Token(" ", Token.Kind.INDENT, line, column));
        }

        // skip whitespace
        start = i + 1;
        continue;
      }

      // any non-whitespace character encountered
      inWhitespace = false;
      if (c != '\n')
        leading = false;

      // For delimiters that are 1-char long in all contexts,
      // break early.
      if (isSingleTokenDelimiter(c)) {

        bakeToken(tokens, input, i, start, line, column);
        start = i;

        // Also add the delimiter.
        bakeToken(tokens, input, i + 1, start, line, column);
        start = i + 1;
        continue;
      }

      // is delimiter
      if (isDelimiter(c)) {

        if (!inDelimiter) {
          bakeToken(tokens, input, i, start, line, column);
          inDelimiter = true;
          start = i;
        }

        continue;
      }

      // if coming out of a delimiter, we still need to bake
      if (inDelimiter) {
        bakeToken(tokens, input, i, start, line, column);
        start = i;
        inDelimiter = false;
      }
    }

    // collect residual token
    if (i > start && !inComment) {
      // we don't want trailing whitespace
      bakeToken(tokens, input, i, start, line, column);
    }

    return cleanTokens(tokens);
  }

  private List<Token> cleanTokens(List<Token> tokens) {
    ListIterator<Token> iterator = tokens.listIterator();
    Stack<Integer> annonymousFunctions = new Stack<Integer>();
    int lastIndents = -1;
    int indents = 0;
    int parens = 0;
    Token previous = null;
    int braces = 0;
    boolean previousIsGuarded = false;
    boolean currentIsFunction = false;
    boolean currentIsAnnonymous = false;
    boolean currentAnnonymousPassedArgs = false;
    while (iterator.hasNext()) {
      Token token = iterator.next();
      switch (token.kind) {
        case INDENT:
          if (parens > 0) {
            iterator.remove();
          } else {
            indents++;
          }
          break;
        case EOL:
          if (parens > 0) {
            iterator.remove();
          } else if (previous != null) {
            switch (previous.kind) {
              case EOL:
              case INDENT:
                while (braces > 0 && indents < braces) {
                  iterator.previous();
                  iterator.add(new Token("}", Token.Kind.RBRACE, previous.line, previous.column));
                  iterator.add(new Token("\n", Token.Kind.EOL, previous.line, previous.column));
                  for (int j = 0; j < indents; j++) {
                    iterator.add(new Token(" ", Token.Kind.INDENT, previous.line, previous.column));
                  }
                  iterator.next();
                  braces--;
                }
                break;
              default:
                int nextIndex = iterator.nextIndex();
                while (nextIndex < tokens.size() && tokens.get(nextIndex).kind == Kind.INDENT) {
                  nextIndex++;
                }
                if (nextIndex < tokens.size() && tokens.get(nextIndex).kind == Kind.IDENT
                    && nextIndex - iterator.nextIndex() == indents && currentIsFunction) {
                  iterator.previous();
                  iterator.add(new Token("}", Token.Kind.RBRACE, previous.line, previous.column));
                  iterator.next();
                  braces--;
                }
                break;
            }
          }
          lastIndents = indents;
          indents = 0;
          currentIsFunction = false;
          break;
        case LPAREN:
          if (currentIsAnnonymous) {
            annonymousFunctions.set(annonymousFunctions.size() - 1, annonymousFunctions
                .lastElement() + 1);
          }
          parens++;
          break;
        case RPAREN:
          if (currentIsAnnonymous && currentAnnonymousPassedArgs) {
            annonymousFunctions.set(annonymousFunctions.size() - 1, annonymousFunctions
                .lastElement() - 1);
            int annonymousFunctionsSize = annonymousFunctions.size();
            int nextIndex = iterator.nextIndex();
            if (token.kind != Kind.LBRACE && tokens.size() > nextIndex && tokens.get(nextIndex).kind != Kind.RBRACE
                && (annonymousFunctionsSize == 0 || annonymousFunctions.lastElement() == 0)) {
              iterator.previous();
              iterator.add(new Token("}", Token.Kind.RBRACE, previous.line, previous.column));
              iterator.next();
              braces--;
              if (annonymousFunctionsSize > 0) {
                annonymousFunctions.pop();
              }
              currentIsAnnonymous = false;
            }
          }
          parens--;
          break;
        case ANONYMOUS_TOKEN:
          currentAnnonymousPassedArgs = false;
          annonymousFunctions.push(0);
          currentIsAnnonymous = true;
          break;
        case RBRACE:
          if (currentIsAnnonymous) {
            currentIsAnnonymous = false;
            annonymousFunctions.pop();
          }
          break;
        case ARROW:
        case HASHROCKET:
          if (currentIsAnnonymous) {
            currentAnnonymousPassedArgs = true;
          }
          if (tokens.get(iterator.nextIndex()).kind != Kind.LBRACE) {
            currentIsFunction = true;
            iterator.add(new Token("{", Token.Kind.LBRACE, previous.line, previous.column));
            braces++;
          }
          break;
        case WHERE:
          break;
        case PIPE:
          if (previous != null && previous.kind == Token.Kind.INDENT) {
            previousIsGuarded = true;
          }
          break;
        default:
          if (parens == 0 && previous != null && previous.kind == Token.Kind.INDENT
              && lastIndents > indents) {
            if (previousIsGuarded) {
              previousIsGuarded = false;
              break;
            }
            iterator.previous();
            iterator.add(new Token("}", Token.Kind.RBRACE, previous.line, previous.column));
            iterator.add(new Token("\n", Token.Kind.EOL, previous.line, previous.column));
            for (int j = 0; j < indents; j++) {
              iterator.add(new Token(" ", Token.Kind.INDENT, previous.line, previous.column));
            }
            iterator.next();
            braces--;
          }
      }
      previous = token;
    }
    for (int i = 0; i < braces; i++) {
      int insertIndents = braces - i - 1;
      for (int j = 0; j < insertIndents; j++) {
        tokens.add(new Token(" ", Token.Kind.INDENT, 0, 0));
      }
      tokens.add(new Token("}", Token.Kind.RBRACE, 0, 0));
      tokens.add(new Token("\n", Token.Kind.EOL, 0, 0));
    }
    prettyPrintTokens(tokens);
    return tokens;
  }

  /**
   * Utility function to prettyPrint a list of tokens(for debugging).
   */
  void prettyPrintTokens(List<Token> tokens) {
    for (Token token : tokens) {
      System.out.print(token.value);
    }
  }

  private static boolean isWhitespace(char c) {
    return '\n' != c && Character.isWhitespace(c);
  }

  static boolean isSingleTokenDelimiter(char c) {
    return DELIMITERS[c] == SINGLE_TOKEN;
  }

  public static String detokenize(List<Token> tokens) {
    StringBuilder builder = new StringBuilder();

    for (Token token : tokens) {
      if (Token.Kind.INDENT == token.kind)
        builder.append("~");
      else
        builder.append(token.value);
      builder.append(' ');
    }

    return builder.toString().trim();
  }

  private static boolean isDelimiter(char c) {
    return DELIMITERS[c] != NON;
  }

  private static void bakeToken(List<Token> tokens, char[] input, int i, int start, int line,
      int column) {
    if (i > start) {
      String value = new String(input, start, i - start);

      // remove this disgusting hack when you can fix the lexer.
      tokens.add(new Token(value, Token.Kind.determine(value), line, column));
    }
  }
}
