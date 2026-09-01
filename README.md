# Deadlock Lock-Order Companion

IntelliJ-family plugin. Flags a real lock acquisition-order cycle
inside a single Java class -- the classic static-deadlock shape --
before it ever happens at runtime.

## Why it exists

Static deadlock detection via a lock-order graph and cycle search is
well-established research (MIT/ECOOP 2005 "Static Deadlock Detection
for Java Libraries"; refined for scale by Peahen, FSE 2022), but no
dedicated JetBrains Marketplace plugin was found doing this ahead of
time, inline, as you write code. IntelliJ IDEA's own tooling only
shows a deadlock *after* it happens, via thread dumps of a running or
hung process (Help > Diagnostic Tools > Analyze/Capture Thread Dump).
There is no built-in or third-party static check that a lock-order
cycle exists in your own code before you ever run it.

This is an original tool (no third-party paid competitor with real
complaints motivated it) -- a deliberate bet on solving a real,
well-known correctness problem with a real static-analysis mechanism,
not a search-pattern/text-linting plugin.

## Why built this way

- **A real directed graph, not a lexer.** `JavaLockNestingFinder` walks
  the actual PSI tree of a class to find genuine lexical nesting of
  `synchronized` blocks/methods (one `synchronized` textually inside
  another's body = "outer lock held while inner lock acquired").
  `LockCycleFinder` builds the directed graph from those edges and
  runs a real depth-first search for cycles -- a back-edge to a node
  already on the current DFS stack is a closed cycle, the textbook
  signal for a potential deadlock.
- **v0.2: cross-method edges, still bounded to one class.**
  `TransitiveLockResolver` computes, for every method of the class,
  every lock it can end up holding -- not just its own `synchronized`
  sites, but transitively through calls to OTHER methods of the SAME
  class. `synchronized(a) { helper(); }` where `helper()` acquires
  lock `b` is exactly as real an a->b edge as direct textual nesting.
  Memoized per class (the same callee is often reached from several
  call sites) with cycle protection: a genuine call cycle between
  methods (`a()` calls `b()` calls `a()`) is broken by tracking the
  current resolution path, never hangs or crashes. A call to another
  CLASS (an unresolved receiver, a library call) is never followed --
  that would need real cross-class/cross-file resolution across the
  whole project, out of scope for a plugin built with a lexer/PSI/
  Annotator, no backend, no external analysis engine.
- **Lock identity is by exact expression text.** `lockA` and
  `this.lockA` are never merged as "the same lock" even if they refer
  to the same object at runtime, and two different fields that happen
  to share a name are never told apart either. Same acknowledged
  limitation as every other text/PSI-based plugin in this catalog
  (never full type/points-to resolution).
- **Java only.** `synchronized` is a real Java keyword/PSI
  node (`PsiSynchronizedStatement`); Kotlin has no equivalent keyword,
  only an inline stdlib function `synchronized(lock) { ... }` --
  structurally different PSI, deferred to a future version.
  `ReentrantLock`/`java.util.concurrent.locks` are also deferred (a
  `.lock()`/`.unlock()` pair does not nest as cleanly in the PSI tree
  as a `synchronized` block's braces do).
- Off-EDT-safe (`collectSlowLineMarkers`), no network calls, no
  telemetry.

## Usage

Open any Java file with two or more `synchronized` sites. If any of
them form a lock acquisition-order cycle within the same class, a
gutter warning icon appears on each site that participates in the
cycle, with a tooltip naming the two locks and the two methods
involved.

## Enterprise / Team Licensing

Need enterprise features, custom rules, or team licensing? Contact us at
**gaphunterlabs@gmail.com**.

## Development

```
./gradlew test           # unit tests
./gradlew buildPlugin    # generates build/distributions/*.zip
./gradlew verifyPlugin   # checks compatibility against real IDEs
```

## License

Apache-2.0. See `LICENSE`.
