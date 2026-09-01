package dev.gaphunter.deadlocklockordercompanion.model

import com.intellij.psi.PsiElement

/**
 * One real acquisition-order edge found in the source: within a single
 * method body, the [outerLock] is acquired and, before it is released,
 * the [innerLock] is acquired while [outerLock] is still held.
 *
 * [outerLock]/[innerLock] are the *textual* lock expressions (e.g.
 * "this", "lockA", "this.cacheLock") -- v0.1 does not resolve whether
 * two differently-spelled expressions denote the same runtime object
 * (see README "Stated honestly"), so edges are keyed by exact text.
 *
 * [outerAnchor] is the PSI element of the outer lock acquisition
 * (`synchronized (outerLock) {` or `outerLock.lock()`), used to place
 * the line marker when this edge turns out to close a cycle.
 */
data class LockNestingEdge(
    val outerLock: String,
    val innerLock: String,
    val outerAnchor: PsiElement,
    val innerAnchor: PsiElement,
    val containingMethodName: String,
)
