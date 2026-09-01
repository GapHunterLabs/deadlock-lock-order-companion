package dev.gaphunter.deadlocklockordercompanion.detect

import com.intellij.psi.PsiElement

/**
 * What a single method acquires, transitively, through calls to other
 * methods of the SAME class -- computed once per method by
 * [TransitiveLockResolver] and memoized, since the same callee can be
 * reached from many call sites.
 *
 * [locksAcquired] is every lock (by expression text) this method ends
 * up holding at some point during its execution, whether directly
 * (its own `synchronized` blocks/modifier) or via a call to another
 * method of the class that itself acquires a lock -- in acquisition
 * order is NOT tracked here (this summary answers "does calling this
 * method risk taking lock X", not "in what order"); ordering is
 * reconstructed by [JavaLockNestingFinder] at each real call site
 * using [firstLockAnchor].
 *
 * [firstLockAnchor] anchors each lock name to the PSI element where
 * IT is acquired -- if this method's own `synchronized` blocks anchor
 * it, that element; if only reached via a further call, the anchor of
 * that call expression (so the line marker points at code the user
 * can actually act on, not at a callee buried elsewhere).
 */
data class MethodLockSummary(
    val locksAcquired: Set<String>,
    val firstLockAnchor: Map<String, PsiElement>,
)
