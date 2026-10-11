/**
 * QSimpleParser for JavaScript
 * Port of the Qt/C++ library by Edwin Magabe (MagabeLab, Tanzania):
 * https://github.com/magabe26/QSimpleParser
 */
var QSP = (function () {
  'use strict';

  // ---------------------------------------------------------------- errors

  class ParserError extends Error {
    constructor(message) {
      super(message);
      this.name = 'ParserError';
    }
  }

  function describeRange(name, invalidValue, start, end, message) {
    if (message) return message;
    const label = (name || 'value') + ' ' + invalidValue;
    if (end > start) return label + ' is not in range ' + start + '..' + end + ' (inclusive)';
    if (end < start) return label + ': the valid range is empty';
    return label + ': the only valid value is ' + start;
  }

  class ParserRangeError extends ParserError {
    constructor(invalidValue, minValue, maxValue, name, message) {
      super(describeRange(name, invalidValue, minValue, maxValue, message));
      this.name = 'ParserRangeError';
      this.invalidValue = invalidValue;
      this.start = minValue;
      this.end = maxValue;
      this.argName = name || '';
    }

    static checkValidRange(start, end, length, startName, endName, message) {
      if (0 > start || start > length) {
        throw new ParserRangeError(start, 0, length, startName || 'start', message);
      }
      if (start > end || end > length) {
        throw new ParserRangeError(end, start, length, endName || 'end', message);
      }
      return end;
    }
  }

  // ------------------------------------------------- context and results

  class Context {
    constructor(buffer, position) {
      this._buffer = buffer;
      this._position = position;
    }
    buffer() { return this._buffer; }
    position() { return this._position; }
  }

  class Success {
    constructor(start, end, value) {
      this._start = start;
      this._end = end;
      this._value = value || '';
    }
    start() { return this._start; }
    end() { return this._end; }
    value() { return this._value; }
    /** Copy carrying `value`, unless this one already has a non-empty value. */
    withValue(value) {
      return this._value === '' ? new Success(this._start, this._end, value) : this;
    }
    toString() {
      return '>>> Success:  ' + this._value + ' (start: ' + this._start + ' - end: ' + this._end + ') <<<';
    }
  }

  class Failure {
    constructor(position, message) {
      this._position = position;
      this._message = message || '';
    }
    position() { return this._position; }
    message() { return this._message; }
    toString() {
      return '>>> Failure:  (position: ' + this._position + ')\n' + this._message + ' <<<\n';
    }
  }

  class Result {
    constructor(successOrFailure) {
      if (successOrFailure instanceof Success) {
        this._success = successOrFailure;
        this._failure = null;
      } else {
        this._success = null;
        this._failure = successOrFailure;
      }
    }
    isSuccess() { return this._success !== null; }
    isFailure() { return this._failure !== null; }
    success() {
      if (this._success === null) throw new Error('Result is a failure: ' + this._failure);
      return this._success;
    }
    failure() {
      if (this._failure === null) throw new Error('Result is a success: ' + this._success);
      return this._failure;
    }
    toString() {
      return this.isFailure() ? this._failure.toString() : this._success.toString();
    }
  }

  function succeed(start, end) { return new Result(new Success(start, end)); }
  function fail(position, message) { return new Result(new Failure(position, message)); }

  function getSubstring(input, start, end) {
    return input.substring(start, end !== -1 ? end : input.length);
  }

  function isParserError(e) { return e instanceof ParserError; }

  // ---------------------------------------------------------------- Parser

  class Parser {
    constructor(name) {
      this._name = name || '';
      this._skippers = [];
    }

    name() { return this._name; }

    /** Override in subclasses. Return succeed(start, end) or fail(position, message). */
    parseOn(context) {
      throw new Error('parseOn() is not implemented in ' + this._name);
    }

    // ----- combinators

    _derive(parser) {
      parser._skippers = this._skippers;
      return parser;
    }

    seq(other) { return this._derive(new SeqParser(this, other)); }
    and(other) { return this.seq(other); }
    oR(other) { return this._derive(new OrParser(this, other)); }
    or(other) { return this.oR(other); }
    optional() { return this._derive(new OptionalParser(this)); }
    star() { return this._derive(new StarParser(this)); }
    plus() { return this._derive(new PlusParser(this)); }

    /**
     * At least `min` and at most `max` matches. If `max` is -1 (the default) or not
     * greater than `min`, exactly `min` matches are consumed.
     */
    repeat(min, max) {
      return this._derive(new RepeatParser(this, min, max === undefined ? -1 : max));
    }

    /** Consumes this parser's match at the start of a search (or first in a sequence) without keeping it. */
    skip() {
      const parser = new SkipParser(this);
      parser._skippers = [new SkipParser(this)];
      return parser;
    }

    isNull() { return false; }

    // ----- running

    parse(input, start) {
      start = start === undefined ? 0 : start;
      return this._parseImpl(input, this._qualifiedPosition(input, start));
    }

    firstMatch(input, start) {
      start = start === undefined ? 0 : start;
      return this._firstMatchImpl(input, this._qualifiedPosition(input, start));
    }

    firstStringMatch(input, start) {
      const result = this.firstMatch(input, start);
      return result.isSuccess() ? result.success().value() : '';
    }

    lastMatch(input, start) {
      start = start === undefined ? 0 : start;
      const matches = this.allMatches(input, start);
      return matches.length ? new Result(matches[matches.length - 1]) : new Result(new Failure(start));
    }

    lastStringMatch(input, start) {
      const result = this.lastMatch(input, start);
      return result.isSuccess() ? result.success().value() : '';
    }

    allMatches(input, start) {
      start = start === undefined ? 0 : start;
      const list = [];
      let position = this._qualifiedPosition(input, start);
      while (position >= 0 && position < input.length) {
        const result = this._parseImpl(input, position);
        if (result.isSuccess()) {
          const success = result.success();
          if (success.value() !== '') list.push(success);
          position = position === success.end() ? position + 1 : success.end();
        } else {
          position++;
        }
      }
      return list;
    }

    allStringMatches(input, start) {
      return this.allMatches(input, start).map(function (m) { return m.value(); });
    }

    hasMatch(input, start) {
      return this.firstMatch(input, start).isSuccess();
    }

    // ----- removing / replacing

    removeFrom(input, start, count) {
      return this.replaceIn(input, '', start, count);
    }

    replaceIn(input, replacement, start, count) {
      return this.replaceInMapped(input, function () { return replacement; }, start, count);
    }

    /** `count` of -1 (the default) replaces every match. */
    replaceInMapped(input, replace, start, count) {
      start = start === undefined ? 0 : start;
      count = count === undefined ? -1 : count;
      if (start < 0 || start >= input.length) return input;
      const matches = this.allMatches(input, start);
      if (!matches.length) return input;
      let output = '';
      let last = 0;
      let replaced = 0;
      for (const match of matches) {
        output += input.substring(last, match.start()) + replace(match.value());
        last = match.end();
        replaced++;
        if (count !== -1 && replaced === count) break;
      }
      return output + input.substring(last);
    }

    toString() { return this._name; }

    // ----- internals

    _parseImpl(input, position) {
      ParserRangeError.checkValidRange(position, input.length, input.length);
      const result = this.parseOn(new Context(input, position));
      if (result.isSuccess()) {
        const success = result.success();
        ParserRangeError.checkValidRange(success.start(), success.end(), input.length);
        return new Result(success.withValue(getSubstring(input, success.start(), success.end())));
      }
      return result;
    }

    _firstMatchImpl(input, position) {
      let message = '';
      while (position >= 0 && position < input.length) {
        const result = this._parseImpl(input, position);
        if (result.isSuccess()) return result;
        message = result.failure().message();
        position++;
      }
      return new Result(new Failure(position, message));
    }

    _qualifiedPosition(input, start) {
      for (const skipper of this._skippers) {
        try {
          const result = skipper._parseImpl(input, start);
          if (result.isSuccess() && result.success().end() > start) start = result.success().end();
        } catch (e) {
          // a skipper that cannot run simply skips nothing
        }
      }
      return start;
    }
  }

  // ------------------------------------------------------------ combinators

  class SeqParser extends Parser {
    constructor(first, second) {
      super('SeqParser');
      this._first = first;
      this._second = second;
    }
    parseOn(context) {
      const buffer = context.buffer();
      const start = context.position();
      let position = start;

      if (this._first instanceof SkipParser) {
        try {
          return this._second._parseImpl(buffer, position);
        } catch (e) {
          if (!isParserError(e)) throw e;
          return fail(position, e.message);
        }
      }

      try {
        let result = this._first._parseImpl(buffer, position);
        if (!result.isSuccess()) return result;
        position = result.success().end();
        try {
          result = this._second._parseImpl(buffer, position);
          if (!result.isSuccess()) return result;
          position = result.success().end();
        } catch (e) {
          if (!isParserError(e)) throw e;
          return fail(position, e.message);
        }
      } catch (e) {
        if (!isParserError(e)) throw e;
        return fail(position, e.message);
      }
      return succeed(start, position);
    }
  }

  class OrParser extends Parser {
    constructor(first, second) {
      super('OrParser');
      this._first = first;
      this._second = second;
    }
    parseOn(context) {
      const buffer = context.buffer();
      const position = context.position();
      try {
        let result = this._first._parseImpl(buffer, position);
        if (result.isFailure()) {
          try {
            result = this._second._parseImpl(buffer, position);
          } catch (e) {
            if (!isParserError(e)) throw e;
            result = fail(position, e.message);
          }
        }
        return result;
      } catch (e) {
        if (!isParserError(e)) throw e;
        try {
          return this._second._parseImpl(buffer, position);
        } catch (e2) {
          if (!isParserError(e2)) throw e2;
          return fail(position, e2.message);
        }
      }
    }
  }

  class StarParser extends Parser {
    constructor(child) {
      super('StarParser');
      this._child = child;
    }
    parseOn(context) {
      const buffer = context.buffer();
      const start = context.position();
      let end = start;
      try {
        let result = this._child._parseImpl(buffer, end);
        while (result.isSuccess()) {
          const newEnd = result.success().end();
          const progressed = newEnd > end;
          end = newEnd;
          if (!progressed) break; // zero-width match: repeating would never finish
          try {
            result = this._child._parseImpl(buffer, end);
          } catch (e) {
            if (!isParserError(e)) throw e;
            break;
          }
        }
        return succeed(start, end);
      } catch (e) {
        if (!isParserError(e)) throw e;
        return succeed(start, end);
      }
    }
  }

  class PlusParser extends Parser {
    constructor(child) {
      super('PlusParser');
      this._child = child;
    }
    parseOn(context) {
      const buffer = context.buffer();
      const start = context.position();
      let end = start;
      let success = false;
      let message = '';
      try {
        let result = this._child._parseImpl(buffer, end);
        while (result.isSuccess()) {
          const newEnd = result.success().end();
          const progressed = newEnd > end;
          end = newEnd;
          success = true;
          if (!progressed) break; // zero-width match: repeating would never finish
          try {
            result = this._child._parseImpl(buffer, end);
          } catch (e) {
            if (!isParserError(e)) throw e;
            message = e.message;
            break;
          }
        }
        return success ? succeed(start, end) : fail(end, message);
      } catch (e) {
        if (!isParserError(e)) throw e;
        return fail(context.position(), e.message);
      }
    }
  }

  class OptionalParser extends Parser {
    constructor(child) {
      super('OptionalParser');
      this._child = child;
    }
    parseOn(context) {
      const start = context.position();
      try {
        const result = this._child._parseImpl(context.buffer(), start);
        return result.isSuccess() ? succeed(start, result.success().end()) : succeed(start, start);
      } catch (e) {
        if (!isParserError(e)) throw e;
        return succeed(start, start);
      }
    }
  }

  class RepeatParser extends Parser {
    constructor(child, min, max) {
      super('RepeatParser');
      this._child = child;
      this._min = min;
      this._max = max;
    }
    parseOn(context) {
      const buffer = context.buffer();
      const start = context.position();
      let end = start;

      for (let i = 0; i < this._min; i++) {
        try {
          const result = this._child._parseImpl(buffer, end);
          if (result.isSuccess()) {
            end = result.success().end();
          } else {
            return fail(start, result.failure().message());
          }
        } catch (e) {
          if (!isParserError(e)) throw e;
          return fail(start, e.message);
        }
      }

      if (this._max === -1 || this._max <= this._min) return succeed(start, end);

      for (let i = 0; i < this._max - this._min; i++) {
        try {
          const result = this._child._parseImpl(buffer, end);
          if (!result.isSuccess() || result.success().end() === end) break;
          end = result.success().end();
        } catch (e) {
          if (!isParserError(e)) throw e;
          break;
        }
      }
      return succeed(start, end);
    }
  }

  class SkipParser extends Parser {
    constructor(child) {
      super('SkipParser');
      this._child = child;
    }
    parseOn(context) {
      const position = context.position();
      try {
        const result = this._child._parseImpl(context.buffer(), position);
        if (result.isSuccess()) {
          const end = result.success().end();
          return succeed(end, end);
        }
        return succeed(position, position);
      } catch (e) {
        if (!isParserError(e)) throw e;
        return succeed(position, position);
      }
    }
  }

  // ----------------------------------------------------------- basic parsers

  class NullParser extends Parser {
    constructor() { super('NullParser'); }
    parseOn(context) { return fail(context.position(), 'NullDelegate'); }
    isNull() { return true; }
  }

  class DebugParser extends Parser {
    constructor() { super('DebugParser'); }
    parseOn(context) {
      console.log('>>> DebugParser:\n input = ' + context.buffer() + '\n\nposition = ' + context.position() + ' <<<');
      return succeed(context.position(), context.position());
    }
  }

  class WrapParser extends Parser {
    constructor(child) {
      super('WrapParser');
      this._child = child;
    }
    parseOn(context) {
      try {
        return this._child._parseImpl(context.buffer(), context.position());
      } catch (e) {
        if (!isParserError(e)) throw e;
        return fail(context.position(), e.message);
      }
    }
  }

  class CharParser extends Parser {
    constructor(character) {
      super('CharParser');
      this._char = character;
    }
    parseOn(context) {
      const buffer = context.buffer();
      const position = context.position();
      if (position < buffer.length) {
        return buffer.charAt(position) === this._char ? succeed(position, position + 1) : fail(position);
      }
      return fail(buffer.length);
    }
  }

  class LastCharDelegateParser extends Parser {
    constructor() { super('LastCharParserDelegate'); }
    parseOn(context) {
      const buffer = context.buffer();
      const position = context.position();
      if (position === buffer.length - 1) return succeed(position, buffer.length);
      return fail(position, 'Not the end of input');
    }
  }

  class LastCharParser extends Parser {
    constructor() { super('LastCharParser'); }
    parseOn(context) {
      try {
        return new LastCharDelegateParser().plus().parse(context.buffer(), context.position());
      } catch (e) {
        if (!isParserError(e)) throw e;
        return fail(context.position(), e.message);
      }
    }
  }

  class FirstCharParser extends Parser {
    constructor() { super('FirstCharParser'); }
    parseOn(context) {
      const position = context.position();
      if (position === 0) return succeed(position, 1);
      return fail(position, 'Not the start of input');
    }
  }

  class AnyCharacterParser extends Parser {
    constructor(end, except) {
      super('AnyCharacterParser');
      this._end = end;
      this._notExcepted = except ? new RegExp('^[^' + except + ']$') : null;
    }
    parseOn(context) {
      const buffer = context.buffer();
      const position = context.position();

      const match = this._end._firstMatchImpl(buffer, position);
      if (!match.isSuccess()) return fail(position, 'AnyCharacterParser: end not found');

      const start = position;
      const endStart = match.success().start();
      if (start >= endStart) return fail(start, 'AnyCharacterParser: character not found');
      if (this._notExcepted === null || this._notExcepted.test(buffer.charAt(start))) {
        return succeed(start, start + 1);
      }
      return fail(start, 'AnyCharacterParser: unwanted character found');
    }
  }

  class StringParser extends Parser {
    constructor(string, caseSensitive) {
      super('StringParser');
      this._string = string;
      this._caseSensitive = caseSensitive;
    }
    parseOn(context) {
      const buffer = context.buffer();
      const start = context.position();
      if (start < buffer.length) {
        let end = start;
        for (let i = 0; i < this._string.length; i++, end++) {
          let match = false;
          if (end < buffer.length) {
            const expected = this._string.charAt(i);
            const actual = buffer.charAt(end);
            match = this._caseSensitive
              ? expected === actual
              : expected.toLowerCase() === actual || expected.toUpperCase() === actual;
          }
          if (!match) return fail(end, 'StringParser:: Failed , No match found');
        }
        return succeed(start, end);
      }
      return fail(buffer.length, 'StringParser:: Failed , No match found');
    }
  }

  class PatternParser extends Parser {
    constructor(pattern) {
      super('PatternParser');
      this._regex = new RegExp('^[' + pattern + ']$');
    }
    parseOn(context) {
      const buffer = context.buffer();
      const position = context.position();
      if (position < buffer.length) {
        return this._regex.test(buffer.charAt(position)) ? succeed(position, position + 1) : fail(position);
      }
      return fail(buffer.length);
    }
  }

  // -------------------------------------------------------------- factories

  /** `c` is a one-character string or a character code. */
  function chaR(c) {
    return new CharParser(typeof c === 'number' ? String.fromCharCode(c) : String(c).charAt(0));
  }
  function firstChar() { return new FirstCharParser(); }
  function lastChar() { return new LastCharParser(); }
  function any(end, except) { return new AnyCharacterParser(end, except); }
  function remainingChars() { return any(lastChar()).star().seq(lastChar()); }
  function charsBefore(parser) { return any(parser).star(); }
  function newLine() { return chaR('\n'); }
  function string(s, caseSensitive) { return new StringParser(s, caseSensitive === undefined ? true : caseSensitive); }
  /** `pattern` is the inside of a regex character class, e.g. 'a-zA-Z'. */
  function pattern(p) { return new PatternParser(p); }
  function letter() { return pattern('a-zA-Z'); }
  function digit() { return pattern('0-9'); }
  function word() { return letter().or(digit()); }

  const WHITESPACE_CHAR = chaR('\t').or(chaR('\n')).or(chaR(0x0B)).or(chaR(0x0C))
    .or(chaR('\r')).or(chaR(' ')).or(chaR(0x85)).or(chaR(0xA0));

  function spaceOptional() { return WHITESPACE_CHAR.star(); }
  function space() { return WHITESPACE_CHAR.plus(); }
  function allInput() { return firstChar().seq(any(lastChar()).star().seq(lastChar())); }
  function debug() { return new DebugParser(); }
  function wrap(another) { return new WrapParser(another); }

  return {
    // parsers
    chaR: chaR, string: string, pattern: pattern, letter: letter, digit: digit, word: word,
    space: space, spaceOptional: spaceOptional, newLine: newLine,
    firstChar: firstChar, lastChar: lastChar, remainingChars: remainingChars, allInput: allInput,
    any: any, charsBefore: charsBefore, wrap: wrap, debug: debug,
    // building blocks for custom parsers
    Parser: Parser, NullParser: NullParser, Context: Context, Result: Result,
    Success: Success, Failure: Failure, ParserError: ParserError, ParserRangeError: ParserRangeError,
    succeed: succeed, fail: fail
  };
})();

if (typeof module !== 'undefined' && module.exports) module.exports = QSP;
