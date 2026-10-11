# QSimpleParser

A small text-parsing library for **Java** and **JavaScript**, with no dependencies. You build a parser from characters, strings and patterns, combine parsers with `seq`, `or`, `star`, `plus` and friends, and then use the result to find matches in a text, replace them, or see where parsing stops.

It is a port of [QSimpleParser](https://github.com/magabe26/QSimpleParser), the Qt/C++ library by Edwin Magabe (MagabeLab, Tanzania). The parser names are the same as in the original, so an expression reads the same in all three languages:

```js
chaR('a').seq(chaR('b')).seq(chaR('c')).allStringMatches('abcdABCD')   // [ 'abc' ]
```

## What is in this repository

| File | What it is |
| --- | --- |
| `QSimpleParser.java` | The Java library. One file, Java 8 or newer. |
| `qsimpleparser.js` | The JavaScript library. One file, works in browsers and in Node.js. |
| `qsimpleparser-workbench.html` | A page for trying parsers out: write code, paste some text, press Run. |

## Quick start

### JavaScript

In Node.js:

```js
const { chaR, digit } = require('./qsimpleparser.js');

const abc = chaR('a').seq(chaR('b')).seq(chaR('c'));

console.log(abc.allStringMatches('abcdABCD abc'));   // [ 'abc', 'abc' ]
console.log(abc.parse('abd').isFailure());           // true

const r = digit().plus().parse('123abc');
console.log(r.success().value());                    // 123
```

In a browser, load the file with a script tag. Everything is available under the global `QSP`:

```html
<script src="qsimpleparser.js"></script>
<script>
  const { chaR } = QSP;
  console.log(chaR('a').allStringMatches('banana'));  // [ 'a', 'a', 'a' ]
</script>
```

### Java

Copy `QSimpleParser.java` into your project and add a `package` line at the top if you use packages. Everything lives inside the `QSimpleParser` class.

```java
public class Demo {
    public static void main(String[] args) {
        QSimpleParser.Parser abc = QSimpleParser.chaR('a')
                .seq(QSimpleParser.chaR('b'))
                .seq(QSimpleParser.chaR('c'));

        System.out.println(abc.allStringMatches("abcdABCD abc"));   // [abc, abc]

        QSimpleParser.Result r = abc.parse("abd");
        System.out.println(r.isFailure());                          // true
        System.out.println(r.failure().position());                 // 2

        String out = QSimpleParser.digit().plus()
                .replaceInMapped("a1b22", m -> "<" + m + ">");
        System.out.println(out);                                    // a<1>b<22>
    }
}
```

When the class is in a package, `import static your.package.QSimpleParser.*;` lets you write `chaR('a')` without the prefix.

## How matching works

A parser is a rule for one position in a text. Three methods run it:

- `parse(input, start)` applies the rule exactly at `start`.
- `firstMatch(input, start)` tries each position from `start` onward and returns the first one that matches.
- `allMatches(input, start)` returns every match from `start` onward, left to right, without overlaps. Empty matches are left out.

The `...StringMatch` variants return the matched text instead of a result object.

A result is either a **Success** or a **Failure**. Check with `isSuccess()` or `isFailure()`, then read the details:

```js
const { digit } = require('./qsimpleparser.js');

const r = digit().plus().parse('123abc');
if (r.isSuccess()) {
  const s = r.success();
  console.log(s.value(), s.start(), s.end());   // 123 0 3
} else {
  console.log(r.failure().position(), r.failure().message());
}
```

Calling `success()` on a failure (or `failure()` on a success) throws, so check first.

## Examples

These use the JavaScript names. The Java calls are identical.

Numbers with an optional decimal part:

```js
const { digit, chaR } = require('./qsimpleparser.js');

const number = digit().plus().seq(chaR('.').seq(digit().plus()).optional());
console.log(number.allStringMatches('12 apples, 3.5 kg, 40 cups'));   // [ '12', '3.5', '40' ]
```

Text between quotes. `any(end)` takes any character as long as `end` can still be found further on:

```js
const { chaR, any } = require('./qsimpleparser.js');

const quote = chaR('"');
const quoted = quote.seq(any(quote).star()).seq(quote);
console.log(quoted.allStringMatches('say "hello" and "good bye"'));   // [ '"hello"', '"good bye"' ]
```

Replacing and removing:

```js
const { digit } = require('./qsimpleparser.js');

console.log(digit().plus().replaceIn('a1b22', '#'));                      // a#b#
console.log(digit().plus().replaceInMapped('a1b22', m => `<${m}>`));      // a<1>b<22>
console.log(digit().plus().removeFrom('a1b22'));                          // ab
```

Dropping a prefix with `skip()`, which consumes a match at the start without keeping it:

```js
const { string, remainingChars } = require('./qsimpleparser.js');

const message = string('LOG:').skip().seq(remainingChars());
console.log(message.firstStringMatch('LOG: started'));   // " started"
```

Case-insensitive words:

```js
const { string } = require('./qsimpleparser.js');

console.log(string('tea', false).allStringMatches('Tea, TEA and tea'));   // [ 'Tea', 'TEA', 'tea' ]
```

### Writing your own parser

Extend `Parser`, implement `parseOn`, and return `succeed(start, end)` or `fail(position, message)`.

JavaScript:

```js
const { Parser, succeed, fail } = require('./qsimpleparser.js');

class Hex extends Parser {
  constructor() { super('Hex'); }
  parseOn(ctx) {
    const s = ctx.buffer(), i = ctx.position();
    if (s.slice(i, i + 2) !== '0x') return fail(i, 'expected 0x');
    let j = i + 2;
    while (j < s.length && /[0-9a-f]/i.test(s[j])) j++;
    return j > i + 2 ? succeed(i, j) : fail(j, 'no digits after 0x');
  }
}

console.log(new Hex().allStringMatches('ids: 0x1F, 0xdeadbeef, 0x, 42'));   // [ '0x1F', '0xdeadbeef' ]
```

Java:

```java
class Hex extends QSimpleParser.Parser {
    Hex() { super("Hex"); }

    @Override
    protected QSimpleParser.Result parseOn(QSimpleParser.Context ctx) {
        String s = ctx.buffer();
        int i = ctx.position();
        if (!s.startsWith("0x", i)) return fail(i, "expected 0x");
        int j = i + 2;
        while (j < s.length() && Character.digit(s.charAt(j), 16) >= 0) j++;
        return j > i + 2 ? succeed(i, j) : fail(j, "no digits after 0x");
    }
}
```

A custom parser can be combined with the built-in ones like any other, for example `new Hex().seq(chaR(','))`.

## Reference

Every parser has the combinator and run methods listed below.

### Make a parser

| Call | Matches |
| --- | --- |
| `chaR(c)` | One character. In JavaScript `c` can also be a character code. |
| `string(s)`, `string(s, caseSensitive)` | A literal string. Case-sensitive unless you pass `false`. |
| `pattern(body)` | One character from a regex character class, for example `pattern("a-zA-Z_")`. |
| `letter()`, `digit()`, `word()` | A letter, a digit, a letter or digit. |
| `space()`, `spaceOptional()` | One or more, or zero or more, whitespace characters. |
| `newLine()` | A line feed. |
| `firstChar()`, `lastChar()` | The first or the last character of the input. |
| `remainingChars()` | Everything from the current position through the last character. |
| `allInput()` | The whole input. |
| `any(end)`, `any(end, except)` | Any character while `end` still occurs later. `except` is a character-class body, such as `"a-z"`, of characters to refuse. |
| `charsBefore(p)` | Every character up to the first match of `p`. |
| `wrap(p)` | Delegates to `p`. |
| `debug()` | Matches nothing and prints the input and position (stderr in Java, the console in JavaScript). |

### Combine parsers

| Call | Meaning |
| --- | --- |
| `p.seq(q)`, `p.and(q)` | `p` followed by `q`. |
| `p.or(q)`, `p.oR(q)` | `p`, or `q` if `p` fails. |
| `p.optional()` | Zero or one match. Never fails. |
| `p.star()` | Zero or more matches. Never fails. |
| `p.plus()` | One or more matches. |
| `p.repeat(min)` | Exactly `min` matches. |
| `p.repeat(min, max)` | At least `min` and at most `max` matches. If `max` is `-1` or not greater than `min`, exactly `min` are used. |
| `p.skip()` | Consumes a match at the start of a search, or at the start of a sequence, and leaves it out of the result. |

### Run a parser

| Call | Returns |
| --- | --- |
| `parse(input)`, `parse(input, start)` | A result for a match exactly at `start` (default 0). Throws a range error if `start` is outside the input. |
| `firstMatch(input, start)` | The first result at or after `start`. |
| `firstStringMatch(input, start)` | Its text, or an empty string when nothing matches. |
| `lastMatch(input, start)`, `lastStringMatch(input, start)` | The last match. |
| `allMatches(input, start)` | A list of `Success` objects. |
| `allStringMatches(input, start)` | A list of strings. |
| `hasMatch(input, start)` | `true` or `false`. |
| `replaceIn(input, text, start, count)` | The input with matches replaced. `count` of `-1` replaces all of them. |
| `replaceInMapped(input, fn, start, count)` | The same, with each match replaced by `fn(matchText)`. |
| `removeFrom(input, start, count)` | The input with matches deleted. |

`start` defaults to 0 and `count` to `-1` everywhere.

### Results

| Call | Meaning |
| --- | --- |
| `r.isSuccess()`, `r.isFailure()` | Which kind of result it is. |
| `r.success()` | A `Success` with `value()`, `start()` and `end()`. |
| `r.failure()` | A `Failure` with `position()` and `message()` (the message can be empty). |

## Differences between the Java and JavaScript versions

- **Optional arguments.** Java uses overloads (`parse(input)` and `parse(input, start)`), JavaScript uses default values. The behavior is the same.
- **Operators.** C++ had `&` and `|`. Here they are the methods `and(...)` and `or(...)`.
- **Regex flavor.** `pattern(...)` and the `except` argument of `any(...)` take the inside of a regex character class in each language's own syntax. An invalid class throws when the parser is created.
- **Errors.** Both versions throw `ParserError`. The error for a position outside the input is `RangeError` in Java and `ParserRangeError` in JavaScript, so it does not clash with the built-in JavaScript `RangeError`.
- **Replacement function.** `replaceInMapped` takes a `java.util.function.Function<String, String>` in Java and a plain function in JavaScript.
- **Collections.** Match lists are `java.util.List` in Java and arrays in JavaScript.

## Where this differs from the Qt/C++ original

A few things behave differently on purpose, because the original misbehaved in these cases:

- `string("ab")` finds `ab` inside `aab`. The C++ parser remembered where it last failed and skipped that match.
- `space()` no longer counts the letter `U` as whitespace. The original listed character 85 where 0x85 was meant.
- `repeat(0, n)` succeeds. In the original it always failed.
- `star()` and `plus()` stop when their inner parser matches nothing, instead of looping forever.
- `skip()` works on every call. In the original it only took effect the first time a parser ran.
- `replaceIn` and `replaceInMapped` work from match positions, so replacement text that contains the matched text is not replaced again.

Things to keep in mind:

- `any(end)` looks ahead for `end` at every character, so on very long inputs it gets slow.
- A built parser keeps no state while running, so it can be reused and shared between threads.
- `repeat(min)` without a `max` means exactly `min`, not "min or more". Use `plus()` or `star()` for open-ended repetition.

## Workbench

`qsimpleparser-workbench.html` is a single page for trying parsers out. Open it in a browser; no server or install is needed.

- Write JavaScript in the code pane. All the functions above are already in scope, and `input` holds the text from the input pane.
- Press **Run** (or Ctrl/Cmd + Enter). The value of your last expression appears under Result. If it is a parser, every match is highlighted in the input.
- `print(...)` and `console.log(...)` write to the Console section.
- The **Load an example** menu has seven starting points, including a custom parser.
- Code runs in a background thread and is stopped after 3 seconds, so a parser that loops forever cannot freeze the page.
- Your code and input are kept in the browser between visits.

## Credits

The original library, QSimpleParser, is by Edwin Magabe (MagabeLab, Tanzania), copyright 2020: <https://github.com/magabe26/QSimpleParser>. This repository ports it to Java and JavaScript.
