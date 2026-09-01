<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Deadlock Lock-Order Companion Changelog

## [Unreleased]

## [0.1.0]

### Added

- Real lock acquisition-order graph built from `synchronized`
  blocks/methods inside a single Java class, with a directed-graph
  cycle search flagging every code site involved in a potential
  static deadlock (inconsistent lock ordering across two or more
  methods of the same class).
- Gutter warning icon with a tooltip naming both locks and both
  methods on each cycle-participating acquisition site.
- Review/star CTA: after 10 distinct real findings, a one-time
  notification asks whether to rate the plugin on Marketplace, with a
  permanent "Don't ask again" option. Standard mechanism used
  catalog-wide since 2026-08-24.

[Unreleased]: https://github.com/GapHunterLabs/deadlock-lock-order-companion/compare/0.1.0...HEAD
[0.1.0]: https://github.com/GapHunterLabs/deadlock-lock-order-companion/commits/0.1.0
