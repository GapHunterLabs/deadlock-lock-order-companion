# Demo data for screenshots

`AccountService.java` — `transfer()` and `transferReverse()` acquire
`lockA`/`lockB` in opposite order (a real lock-order cycle, flagged
with a gutter icon on each method's outer `synchronized` statement);
`audit()` uses the same order as `transfer()`, so it's not part of any
cycle (not flagged).

## How to get the screenshot

1. `./gradlew runIde` from `deadlock-lock-order-companion`, open this
   `demo/` folder as the project.
2. Full Screen, open `AccountService.java` — a gutter icon (right-hand
   margin) should appear on the `synchronized (lockA)` line inside
   `transfer()` and on the `synchronized (lockB)` line inside
   `transferReverse()`, but not on `audit()`'s lines. Hover the icon
   for the tooltip explaining the cycle.
3. Screenshot with all three methods visible, save into
   `deadlock-lock-order-companion/docs/screenshots/`. Close the
   sandbox.
