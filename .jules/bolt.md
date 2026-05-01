## 2024-05-13 - [Optimize formatOctetAsHex]
**Learning:** `String.format("%02X", value)` is heavily used in `AbstractMessage.formatOctetAsHex` but parsing format strings is slow. A custom char array lookup is nearly ~50x faster.
**Action:** Replace `String.format("%02X", value)` in `AbstractMessage.formatOctetAsHex` with a fast char array lookup implementation.

## 2024-05-13 - [Optimize getIntAsHex]
**Learning:** `getIntAsHex` creates strings via `Integer.toHexString`, pads them via string concatenation inside a `while` loop, and calls `.toUpperCase()`. This is slow due to creating multiple string objects and parsing strings. Using a custom char array padding technique like in `formatOctetAsHex` but supporting variable padding lengths improves speed by ~4x.
**Action:** Replace `Integer.toHexString(value).toUpperCase()` and manual padding in `getIntAsHex` with a fast char array lookup and initialization implementation.

## 2024-05-13 - [Optimize getHex and BinaryMessage]
**Learning:** `BinaryMessage` heavily utilizes `String.format` and `Integer.toHexString` to format hex strings, which are surprisingly slow due to regex parsing and object instantiation inside inner loops in hot paths. Profiling shows `char[]` lookup techniques are significantly faster.
**Action:** Replace `String.format` and `Integer.toHexString` in `BinaryMessage` with manual bit-shifting and `char[]` mapping against a static `HEX_ARRAY`. Use `Math.max` for correctly sizing buffer arrays.
