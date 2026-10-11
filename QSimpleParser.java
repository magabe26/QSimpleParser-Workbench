/**
 * Copyright 2020 - MagabeLab (Tanzania). All Rights Reserved.
 * Author Edwin Magabe    edyma50@yahoo.com
 *
 * Java port of QSimpleParser (https://github.com/magabe26/QSimpleParser),
 * condensed into a single source file. Requires Java 8 or newer.
 *
 * Add your own "package ...;" line at the top if you need one.
 *
 * Example:
 *
 *   QSimpleParser.chaR('a')
 *       .seq(QSimpleParser.chaR('b'))
 *       .seq(QSimpleParser.chaR('c'))
 *       .allStringMatches("abcdABCD");            // [abc]
 *
 * With "import static your.package.QSimpleParser.*;" the factories can be used bare:
 *
 *   chaR('a').seq(chaR('b')).seq(chaR('c')).allStringMatches("abcdABCD");
 *
 * Notes:
 *  - Parsers are immutable except for the (shared) skip list created by skip().
 *  - Instances are not meant to be shared between threads while being built.
 */

// package your.package.here;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

public final class QSimpleParser {

    private QSimpleParser() {
    }

    private static String getSubstring(String input, int start, int end) {
        return input.substring(start, end != -1 ? end : input.length());
    }

    // =====================================================================
    // Errors
    // =====================================================================

    /** Base class of the errors thrown while parsing. */
    public static class ParserError extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ParserError(String message) {
            super(message);
        }
    }

    /** Thrown when a position/range is outside the input. */
    public static class RangeError extends ParserError {
        private static final long serialVersionUID = 1L;

        private final int start;
        private final int end;
        private final int invalidValue;
        private final String name;

        public RangeError(int invalidValue, int minValue, int maxValue, String name, String message) {
            super(describe(minValue, maxValue, message));
            this.invalidValue = invalidValue;
            this.start = minValue;
            this.end = maxValue;
            this.name = name == null ? "" : name;
        }

        public RangeError(int invalidValue, int minValue, int maxValue) {
            this(invalidValue, minValue, maxValue, "", "");
        }

        private static String describe(int start, int end, String message) {
            if (message != null && !message.isEmpty()) {
                return message;
            }
            if (end > start) {
                return ": Not in range " + start + ".." + end + ", inclusive";
            } else if (end < start) {
                return ": Valid value range is empty";
            } else {
                return ": Only valid value is " + start;
            }
        }

        /** The minimum value that the invalid value is allowed to assume. */
        public int start() {
            return start;
        }

        /** The maximum value that the invalid value is allowed to assume. */
        public int end() {
            return end;
        }

        public int invalidValue() {
            return invalidValue;
        }

        /** Name of the invalid argument, if available. */
        public String name() {
            return name;
        }

        public String message() {
            return getMessage();
        }

        public static int checkValidRange(int start, int end, int length) {
            return checkValidRange(start, end, length, "", "", "");
        }

        public static int checkValidRange(int start, int end, int length,
                                          String startName, String endName, String message) {
            if (0 > start || start > length) {
                if (startName == null || startName.isEmpty()) {
                    startName = "start";
                }
                throw new RangeError(start, 0, length, startName, message);
            }
            if (start > end || end > length) {
                if (endName == null || endName.isEmpty()) {
                    endName = "end";
                }
                throw new RangeError(end, start, length, endName, message);
            }
            return end;
        }
    }

    // =====================================================================
    // Context / Result
    // =====================================================================

    /** The input buffer together with the position parsing starts at. */
    public static final class Context {
        private final String buffer;
        private final int position;

        public Context(String buffer, int position) {
            this.buffer = buffer;
            this.position = position;
        }

        public String buffer() {
            return buffer;
        }

        public int position() {
            return position;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Context)) return false;
            Context c = (Context) o;
            return position == c.position && buffer.equals(c.buffer);
        }

        @Override
        public int hashCode() {
            return 31 * buffer.hashCode() + position;
        }
    }

    /** A successful parse: the matched range [start, end) and its text. */
    public static final class Success {
        private final int start;
        private final int end;
        private final String value;

        public Success(int start, int end) {
            this(start, end, "");
        }

        public Success(int start, int end, String value) {
            this.start = start;
            this.end = end;
            this.value = value == null ? "" : value;
        }

        public int start() {
            return start;
        }

        public int end() {
            return end;
        }

        public String value() {
            return value;
        }

        /** Returns a copy carrying {@code value}, unless this one already has a non-empty value. */
        public Success withValue(String value) {
            return this.value.isEmpty() ? new Success(start, end, value) : this;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Success)) return false;
            Success s = (Success) o;
            return start == s.start && end == s.end && value.equals(s.value);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * start + end) + value.hashCode();
        }

        @Override
        public String toString() {
            return ">>> Success:  " + value + " (start: " + start + " - end: " + end + ") <<<";
        }
    }

    /** A failed parse: where it failed and why (the message may be empty). */
    public static final class Failure {
        private final int position;
        private final String message;

        public Failure(int position) {
            this(position, "");
        }

        public Failure(int position, String message) {
            this.position = position;
            this.message = message == null ? "" : message;
        }

        public int position() {
            return position;
        }

        public String message() {
            return message;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Failure)) return false;
            Failure f = (Failure) o;
            return position == f.position && message.equals(f.message);
        }

        @Override
        public int hashCode() {
            return 31 * position + message.hashCode();
        }

        @Override
        public String toString() {
            return ">>> Failure:  (position: " + position + ")\n" + message + " <<<\n";
        }
    }

    /** Either a {@link Success} or a {@link Failure}. */
    public static final class Result {
        private final Success success;
        private final Failure failure;

        public Result(Success success) {
            this.success = success;
            this.failure = null;
        }

        public Result(Failure failure) {
            this.success = null;
            this.failure = failure;
        }

        public boolean isSuccess() {
            return success != null;
        }

        public boolean isFailure() {
            return failure != null;
        }

        /** @throws IllegalStateException if this result is a failure */
        public Success success() {
            if (success == null) {
                throw new IllegalStateException("Result is a failure: " + failure);
            }
            return success;
        }

        /** @throws IllegalStateException if this result is a success */
        public Failure failure() {
            if (failure == null) {
                throw new IllegalStateException("Result is a success: " + success);
            }
            return failure;
        }

        @Override
        public String toString() {
            return isFailure() ? failure.toString() : success.toString();
        }
    }

    // =====================================================================
    // Parser (base class + combinators)
    // =====================================================================

    /**
     * Base class of all parsers. Subclasses implement {@link #parseOn(Context)};
     * everything else (combinators, searching, replacing) is provided here.
     */
    public abstract static class Parser {
        private final String name;
        /** Shared with parsers derived through the combinators; see skip(). */
        private List<Parser> skippers = new ArrayList<Parser>();

        protected Parser(String name) {
            this.name = name == null ? "" : name;
        }

        public final String name() {
            return name;
        }

        /**
         * Parses at {@code context.position()}. Return {@link #succeed} / {@link #fail}.
         * Throwing a {@link ParserError} is treated like a failure by the combinators.
         */
        protected abstract Result parseOn(Context context);

        protected static Result succeed(int start, int end) {
            return new Result(new Success(start, end));
        }

        protected static Result fail(int position) {
            return new Result(new Failure(position));
        }

        protected static Result fail(int position, String message) {
            return new Result(new Failure(position, message));
        }

        // ----- combinators ---------------------------------------------

        private Parser derive(Parser parser) {
            parser.skippers = this.skippers;
            return parser;
        }

        /** This parser followed by {@code other}. */
        public Parser seq(Parser other) {
            return derive(new SeqParser(this, other));
        }

        /** Same as {@link #seq(Parser)} (the C++ {@code operator &}). */
        public Parser and(Parser other) {
            return seq(other);
        }

        /** This parser, or {@code other} if this one fails. */
        public Parser oR(Parser other) {
            return derive(new OrParser(this, other));
        }

        /** Same as {@link #oR(Parser)} (the C++ {@code operator |}). */
        public Parser or(Parser other) {
            return oR(other);
        }

        /** Zero or one match. Never fails. */
        public Parser optional() {
            return derive(new OptionalParser(this));
        }

        /** Zero or more matches. Never fails. */
        public Parser star() {
            return derive(new StarParser(this));
        }

        /** One or more matches. */
        public Parser plus() {
            return derive(new PlusParser(this));
        }

        /** Exactly {@code min} matches. */
        public Parser repeat(int min) {
            return repeat(min, -1);
        }

        /**
         * At least {@code min} and at most {@code max} matches. If {@code max} is -1 or not
         * greater than {@code min}, exactly {@code min} matches are consumed.
         */
        public Parser repeat(int min, int max) {
            return derive(new RepeatParser(this, min, max));
        }

        /**
         * Consumes this parser's match if there is one at the start of the search (and in a
         * sequence, when it is the first element) without making it part of the result.
         * Example: {@code chaR('a').skip().seq(remainingChars()).firstStringMatch("abc")} is "bc".
         */
        public Parser skip() {
            Parser parser = new SkipParser(this);
            parser.skippers = new ArrayList<Parser>();
            parser.skippers.add(new SkipParser(this));
            return parser;
        }

        private boolean isSkipParser() {
            return this instanceof SkipParser;
        }

        // ----- running a parser ----------------------------------------

        /** Parses at the beginning of {@code input}. */
        public Result parse(String input) {
            return parse(input, 0);
        }

        /**
         * Parses exactly at {@code start}.
         *
         * @throws RangeError if {@code start} is outside the input
         */
        public Result parse(String input, int start) {
            return parseImpl(input, getQualifiedPosition(input, start));
        }

        public Result firstMatch(String input) {
            return firstMatch(input, 0);
        }

        /** The first match found at or after {@code start}. */
        public Result firstMatch(String input, int start) {
            return firstMatchImpl(input, getQualifiedPosition(input, start));
        }

        public String firstStringMatch(String input) {
            return firstStringMatch(input, 0);
        }

        /** Text of the first match at or after {@code start}, or "" if there is none. */
        public String firstStringMatch(String input, int start) {
            Result result = firstMatch(input, start);
            return result.isSuccess() ? result.success().value() : "";
        }

        public Result lastMatch(String input) {
            return lastMatch(input, 0);
        }

        /** The last match at or after {@code start}. */
        public Result lastMatch(String input, int start) {
            List<Success> matches = allMatches(input, start);
            return matches.isEmpty()
                    ? new Result(new Failure(start))
                    : new Result(matches.get(matches.size() - 1));
        }

        public String lastStringMatch(String input) {
            return lastStringMatch(input, 0);
        }

        public String lastStringMatch(String input, int start) {
            Result match = lastMatch(input, start);
            return match.isSuccess() ? match.success().value() : "";
        }

        public List<String> allStringMatches(String input) {
            return allStringMatches(input, 0);
        }

        /** Texts of all non-overlapping, non-empty matches at or after {@code start}. */
        public List<String> allStringMatches(String input, int start) {
            List<String> list = new ArrayList<String>();
            for (Success match : allMatches(input, start)) {
                list.add(match.value());
            }
            return list;
        }

        public List<Success> allMatches(String input) {
            return allMatches(input, 0);
        }

        /** All non-overlapping, non-empty matches at or after {@code start}. */
        public List<Success> allMatches(String input, int start) {
            List<Success> list = new ArrayList<Success>();
            int position = getQualifiedPosition(input, start);

            while (position >= 0 && position < input.length()) {
                Result result = parseImpl(input, position);
                if (result.isSuccess()) {
                    Success success = result.success();
                    if (!success.value().isEmpty()) {
                        list.add(success);
                    }
                    if (position == success.end()) {
                        position++;
                    } else {
                        position = success.end();
                    }
                } else {
                    position++;
                }
            }
            return list;
        }

        public boolean hasMatch(String input) {
            return hasMatch(input, 0);
        }

        public boolean hasMatch(String input, int start) {
            return firstMatch(input, start).isSuccess();
        }

        public boolean isNull() {
            return false;
        }

        // ----- removing / replacing ------------------------------------

        public String removeFrom(String input) {
            return removeFrom(input, 0, -1);
        }

        public String removeFrom(String input, int start) {
            return removeFrom(input, start, -1);
        }

        /** Removes matches; {@code count == -1} removes all of them. */
        public String removeFrom(String input, int start, int count) {
            return replaceIn(input, "", start, count);
        }

        public String replaceIn(String input, String replacement) {
            return replaceIn(input, replacement, 0, -1);
        }

        public String replaceIn(String input, String replacement, int start) {
            return replaceIn(input, replacement, start, -1);
        }

        public String replaceIn(String input, final String replacement, int start, int count) {
            return replaceInMapped(input, new Function<String, String>() {
                @Override
                public String apply(String match) {
                    return replacement;
                }
            }, start, count);
        }

        public String replaceInMapped(String input, Function<String, String> replace) {
            return replaceInMapped(input, replace, 0, -1);
        }

        public String replaceInMapped(String input, Function<String, String> replace, int start) {
            return replaceInMapped(input, replace, start, -1);
        }

        /**
         * Replaces matches with {@code replace.apply(matchedText)}.
         *
         * @param count how many matches to replace; -1 replaces all of them
         */
        public String replaceInMapped(String input, Function<String, String> replace, int start, int count) {
            if (start < 0 || start >= input.length()) {
                return input;
            }
            List<Success> matches = allMatches(input, start);
            if (matches.isEmpty()) {
                return input;
            }
            StringBuilder output = new StringBuilder();
            int last = 0;
            int replaced = 0;
            for (Success match : matches) {
                output.append(input, last, match.start());
                output.append(replace.apply(match.value()));
                last = match.end();
                replaced++;
                if (count != -1 && replaced == count) {
                    break;
                }
            }
            output.append(input, last, input.length());
            return output.toString();
        }

        @Override
        public String toString() {
            return name;
        }

        // ----- internals -----------------------------------------------

        /** Parse without applying skippers; used by combinators to run their children. */
        private Result parseImpl(String input, int position) {
            RangeError.checkValidRange(position, input.length(), input.length());
            Result result = parseOn(new Context(input, position));
            if (result.isSuccess()) {
                Success success = result.success();
                RangeError.checkValidRange(success.start(), success.end(), input.length());
                return new Result(success.withValue(getSubstring(input, success.start(), success.end())));
            }
            return result;
        }

        private Result firstMatchImpl(String input, int position) {
            String message = "";
            while (position >= 0 && position < input.length()) {
                Result result = parseImpl(input, position);
                if (result.isSuccess()) {
                    return result;
                }
                message = result.failure().message();
                position++;
            }
            return new Result(new Failure(position, message));
        }

        private int getQualifiedPosition(String input, int start) {
            for (Parser skipper : skippers) {
                try {
                    Result result = skipper.parseImpl(input, start);
                    if (result.isSuccess() && result.success().end() > start) {
                        start = result.success().end();
                    }
                } catch (RuntimeException ignored) {
                    // a skipper that cannot run simply skips nothing
                }
            }
            return start;
        }
    }

    // =====================================================================
    // Combinator parsers
    // =====================================================================

    private static final class SeqParser extends Parser {
        private final Parser first;
        private final Parser second;

        SeqParser(Parser first, Parser second) {
            super("SeqParser");
            this.first = first;
            this.second = second;
        }

        @Override
        protected Result parseOn(Context context) {
            String buffer = context.buffer();
            int start = context.position();
            int position = start;

            if (first.isSkipParser()) {
                try {
                    return second.parseImpl(buffer, position);
                } catch (ParserError e) {
                    return fail(position, e.getMessage());
                }
            }

            try {
                Result result = first.parseImpl(buffer, position);
                if (!result.isSuccess()) {
                    return result;
                }
                position = result.success().end();
                try {
                    result = second.parseImpl(buffer, position);
                    if (!result.isSuccess()) {
                        return result;
                    }
                    position = result.success().end();
                } catch (ParserError e) {
                    return fail(position, e.getMessage());
                }
            } catch (ParserError e) {
                return fail(position, e.getMessage());
            }
            return succeed(start, position);
        }
    }

    private static final class OrParser extends Parser {
        private final Parser first;
        private final Parser second;

        OrParser(Parser first, Parser second) {
            super("OrParser");
            this.first = first;
            this.second = second;
        }

        @Override
        protected Result parseOn(Context context) {
            String buffer = context.buffer();
            int position = context.position();
            try {
                Result result = first.parseImpl(buffer, position);
                if (result.isFailure()) {
                    try {
                        result = second.parseImpl(buffer, position);
                    } catch (ParserError e) {
                        result = fail(position, e.getMessage());
                    }
                }
                return result;
            } catch (ParserError e) {
                try {
                    return second.parseImpl(buffer, position);
                } catch (ParserError e2) {
                    return fail(position, e2.getMessage());
                }
            }
        }
    }

    private static final class StarParser extends Parser {
        private final Parser child;

        StarParser(Parser child) {
            super("StarParser");
            this.child = child;
        }

        @Override
        protected Result parseOn(Context context) {
            int start = context.position();
            int end = start;
            try {
                Result result = child.parseImpl(context.buffer(), end);
                while (result.isSuccess()) {
                    int newEnd = result.success().end();
                    boolean progressed = newEnd > end;
                    end = newEnd;
                    if (!progressed) {
                        break; // zero-width match: repeating would never finish
                    }
                    try {
                        result = child.parseImpl(context.buffer(), end);
                    } catch (ParserError e) {
                        break;
                    }
                }
                return succeed(start, end);
            } catch (ParserError e) {
                return succeed(start, end);
            }
        }
    }

    private static final class PlusParser extends Parser {
        private final Parser child;

        PlusParser(Parser child) {
            super("PlusParser");
            this.child = child;
        }

        @Override
        protected Result parseOn(Context context) {
            int start = context.position();
            int end = start;
            boolean success = false;
            String message = "";
            try {
                Result result = child.parseImpl(context.buffer(), end);
                while (result.isSuccess()) {
                    int newEnd = result.success().end();
                    boolean progressed = newEnd > end;
                    end = newEnd;
                    success = true;
                    if (!progressed) {
                        break; // zero-width match: repeating would never finish
                    }
                    try {
                        result = child.parseImpl(context.buffer(), end);
                    } catch (ParserError e) {
                        message = e.getMessage();
                        break;
                    }
                }
                return success ? succeed(start, end) : fail(end, message);
            } catch (ParserError e) {
                return fail(context.position(), e.getMessage());
            }
        }
    }

    private static final class OptionalParser extends Parser {
        private final Parser child;

        OptionalParser(Parser child) {
            super("OptionalParser");
            this.child = child;
        }

        @Override
        protected Result parseOn(Context context) {
            int start = context.position();
            try {
                Result result = child.parseImpl(context.buffer(), start);
                return result.isSuccess() ? succeed(start, result.success().end()) : succeed(start, start);
            } catch (ParserError e) {
                return succeed(start, start);
            }
        }
    }

    private static final class RepeatParser extends Parser {
        private final Parser child;
        private final int min;
        private final int max;

        RepeatParser(Parser child, int min, int max) {
            super("RepeatParser");
            this.child = child;
            this.min = min;
            this.max = max;
        }

        @Override
        protected Result parseOn(Context context) {
            String buffer = context.buffer();
            int start = context.position();
            int end = start;

            for (int i = 0; i < min; i++) {
                try {
                    Result result = child.parseImpl(buffer, end);
                    if (result.isSuccess()) {
                        end = result.success().end();
                    } else {
                        return fail(start, result.failure().message());
                    }
                } catch (ParserError e) {
                    return fail(start, e.getMessage());
                }
            }

            if (max == -1 || max <= min) {
                return succeed(start, end);
            }

            for (int i = 0; i < max - min; i++) {
                try {
                    Result result = child.parseImpl(buffer, end);
                    if (!result.isSuccess() || result.success().end() == end) {
                        break;
                    }
                    end = result.success().end();
                } catch (ParserError e) {
                    break;
                }
            }
            return succeed(start, end);
        }
    }

    private static final class SkipParser extends Parser {
        private final Parser child;

        SkipParser(Parser child) {
            super("SkipParser");
            this.child = child;
        }

        @Override
        protected Result parseOn(Context context) {
            int position = context.position();
            try {
                Result result = child.parseImpl(context.buffer(), position);
                if (result.isSuccess()) {
                    int end = result.success().end();
                    return succeed(end, end);
                }
                return succeed(position, position);
            } catch (ParserError e) {
                return succeed(position, position);
            }
        }
    }

    // =====================================================================
    // Basic parsers
    // =====================================================================

    /** Always fails. */
    public static final class NullParser extends Parser {
        public NullParser() {
            super("NullParser");
        }

        @Override
        protected Result parseOn(Context context) {
            return fail(context.position(), "NullDelegate");
        }

        @Override
        public boolean isNull() {
            return true;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof NullParser;
        }

        @Override
        public int hashCode() {
            return NullParser.class.hashCode();
        }
    }

    /** Prints the input and position to stderr, matches nothing, never fails. */
    public static final class DebugParser extends Parser {
        public DebugParser() {
            super("DebugParser");
        }

        @Override
        protected Result parseOn(Context context) {
            System.err.println(">>> DebugParser:\n input = " + context.buffer()
                    + "\n\nposition = " + context.position() + " <<<");
            return succeed(context.position(), context.position());
        }
    }

    /** Delegates to another parser. */
    public static final class WrapParser extends Parser {
        private final Parser child;

        public WrapParser(Parser child) {
            super("WrapParser");
            this.child = child;
        }

        @Override
        protected Result parseOn(Context context) {
            try {
                return child.parseImpl(context.buffer(), context.position());
            } catch (ParserError e) {
                return fail(context.position(), e.getMessage());
            }
        }
    }

    /** Matches one specific character. */
    public static final class CharParser extends Parser {
        private final char character;

        public CharParser(char character) {
            super("CharParser");
            this.character = character;
        }

        @Override
        protected Result parseOn(Context context) {
            String buffer = context.buffer();
            int position = context.position();
            if (position < buffer.length()) {
                return buffer.charAt(position) == character
                        ? succeed(position, position + 1)
                        : fail(position);
            }
            return fail(buffer.length());
        }
    }

    /** Matches only at the last character of the input (matching is zero-based: length - 1). */
    private static final class LastCharDelegateParser extends Parser {
        LastCharDelegateParser() {
            super("LastCharParserDelegate");
        }

        @Override
        protected Result parseOn(Context context) {
            String buffer = context.buffer();
            int position = context.position();
            if (position == buffer.length() - 1) {
                return succeed(position, buffer.length());
            }
            return fail(position, "Not the end of input");
        }
    }

    /** Matches the last character of the input. */
    public static final class LastCharParser extends Parser {
        public LastCharParser() {
            super("LastCharParser");
        }

        @Override
        protected Result parseOn(Context context) {
            try {
                return new LastCharDelegateParser().plus().parse(context.buffer(), context.position());
            } catch (ParserError e) {
                return fail(context.position(), e.getMessage());
            }
        }
    }

    /** Matches the first character of the input. */
    public static final class FirstCharParser extends Parser {
        public FirstCharParser() {
            super("FirstCharParser");
        }

        @Override
        protected Result parseOn(Context context) {
            int position = context.position();
            if (position == 0) {
                return succeed(position, 1);
            }
            return fail(position, "Not the start of input");
        }
    }

    /**
     * Matches one character, as long as {@code end} can still be found later in the input.
     * Characters that belong to the optional {@code except} set are refused.
     */
    public static final class AnyCharacterParser extends Parser {
        private final Parser end;
        private final Pattern notExcepted; // null when there is no except set

        /** @param except body of a regex character class, e.g. "o" or "a-z" */
        public AnyCharacterParser(Parser end, String except) {
            super("AnyCharacterParser");
            this.end = end;
            this.notExcepted = (except == null || except.isEmpty())
                    ? null
                    : Pattern.compile("[^" + except + "]");
        }

        @Override
        protected Result parseOn(Context context) {
            String buffer = context.buffer();
            int position = context.position();

            Result match = end.firstMatchImpl(buffer, position);
            if (!match.isSuccess()) {
                return fail(position, "AnyCharacterParser: end not found");
            }
            int start = position;
            int endStart = match.success().start();
            if (start >= endStart) {
                return fail(start, "AnyCharacterParser: character not found");
            }
            if (notExcepted == null
                    || notExcepted.matcher(String.valueOf(buffer.charAt(start))).matches()) {
                return succeed(start, start + 1);
            }
            return fail(start, "AnyCharacterParser: unwanted character found");
        }
    }

    /** Matches a literal string. */
    public static final class StringParser extends Parser {
        private final String string;
        private final boolean caseSensitive;

        public StringParser(String string, boolean caseSensitive) {
            super("StringParser");
            this.string = string;
            this.caseSensitive = caseSensitive;
        }

        @Override
        protected Result parseOn(Context context) {
            String buffer = context.buffer();
            int start = context.position();
            if (start < buffer.length()) {
                int end = start;
                for (int i = 0; i < string.length(); i++, end++) {
                    boolean match = false;
                    if (end < buffer.length()) {
                        char expected = string.charAt(i);
                        char actual = buffer.charAt(end);
                        match = caseSensitive
                                ? expected == actual
                                : (Character.toLowerCase(expected) == actual
                                || Character.toUpperCase(expected) == actual);
                    }
                    if (!match) {
                        return fail(end, "StringParser:: Failed , No match found");
                    }
                }
                return succeed(start, end);
            }
            return fail(buffer.length(), "StringParser:: Failed , No match found");
        }
    }

    /** Matches one character that belongs to a regex character class. */
    public static final class PatternParser extends Parser {
        private final Pattern regex;

        /**
         * @param pattern body of a regex character class: "a-zA-Z" matches letters.
         * @throws java.util.regex.PatternSyntaxException if the class is not valid
         */
        public PatternParser(String pattern) {
            super("PatternParser");
            this.regex = Pattern.compile("[" + pattern + "]");
        }

        @Override
        protected Result parseOn(Context context) {
            String buffer = context.buffer();
            int position = context.position();
            if (position < buffer.length()) {
                return regex.matcher(String.valueOf(buffer.charAt(position))).matches()
                        ? succeed(position, position + 1)
                        : fail(position);
            }
            return fail(buffer.length());
        }
    }

    // =====================================================================
    // Factory functions
    // =====================================================================

    public static CharParser chaR(char c) {
        return new CharParser(c);
    }

    public static FirstCharParser firstChar() {
        return new FirstCharParser();
    }

    public static LastCharParser lastChar() {
        return new LastCharParser();
    }

    /** Everything up to and including the last character. */
    public static Parser remainingChars() {
        return any(lastChar()).star().seq(lastChar());
    }

    /** Every character up to (not including) the first match of {@code parser}. */
    public static Parser charsBefore(Parser parser) {
        return any(parser).star();
    }

    public static Parser newLine() {
        return chaR('\n');
    }

    public static AnyCharacterParser any(Parser end) {
        return new AnyCharacterParser(end, null);
    }

    public static AnyCharacterParser any(Parser end, String except) {
        return new AnyCharacterParser(end, except);
    }

    public static StringParser string(String string) {
        return new StringParser(string, true);
    }

    public static StringParser string(String string, boolean caseSensitive) {
        return new StringParser(string, caseSensitive);
    }

    public static PatternParser pattern(String pattern) {
        return new PatternParser(pattern);
    }

    public static PatternParser letter() {
        return pattern("a-zA-Z");
    }

    public static PatternParser digit() {
        return pattern("0-9");
    }

    public static Parser word() {
        return letter().or(digit());
    }

    private static final Parser WHITESPACE_CHAR = chaR('\t')
            .or(chaR('\n'))
            .or(chaR((char) 0x0B))
            .or(chaR((char) 0x0C))
            .or(chaR('\r'))
            .or(chaR(' '))
            .or(chaR((char) 0x85))
            .or(chaR((char) 0xA0));

    /** Zero or more whitespace characters. */
    public static Parser spaceOptional() {
        return WHITESPACE_CHAR.star();
    }

    /** One or more whitespace characters. */
    public static Parser space() {
        return WHITESPACE_CHAR.plus();
    }

    /** Parses the entire input: {@code allInput().firstStringMatch("I am Awesome!")} is "I am Awesome!". */
    public static Parser allInput() {
        return firstChar().seq(any(lastChar()).star().seq(lastChar()));
    }

    public static DebugParser debug() {
        return new DebugParser();
    }

    public static WrapParser wrap(Parser another) {
        return new WrapParser(another);
    }
}
