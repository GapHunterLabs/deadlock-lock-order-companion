package dev.gaphunter.deadlocklockordercompanion.detect

import com.intellij.psi.PsiElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.gaphunter.deadlocklockordercompanion.model.LockNestingEdge

/**
 * Pure graph-algorithm tests -- [LockCycleFinder] only ever reads
 * [LockNestingEdge.outerLock]/[LockNestingEdge.innerLock] (plain
 * strings), never the PSI anchors' real content, but a real
 * [PsiElement] is still the simplest safe way to satisfy the type
 * (same [BasePlatformTestCase] fixture used catalog-wide, e.g.
 * `JavaClientBuildFinderTest`) rather than hand-rolling a fake
 * implementation of the large platform interface. [JavaLockNestingFinderTest]
 * covers the real PSI extraction half end to end.
 */
class LockCycleFinderTest : BasePlatformTestCase() {

    /** Any real, valid PsiElement -- its identity/content is irrelevant to the graph algorithm under test. */
    private fun anyAnchor(): PsiElement =
        myFixture.configureByText("Anchor.java", "class Anchor {}").let { it }

    private fun edge(outer: String, inner: String, method: String = "m"): LockNestingEdge {
        val anchor = anyAnchor()
        return LockNestingEdge(outer, inner, anchor, anchor, method)
    }

    fun `test no edges means no cycles`() {
        assertTrue(LockCycleFinder.findCycles(emptyList()).isEmpty())
    }

    fun `test simple nesting with no reverse order is not a cycle`() {
        val edges = listOf(edge("a", "b"), edge("b", "c"))
        assertTrue(LockCycleFinder.findCycles(edges).isEmpty())
    }

    fun `test direct two-lock cycle is detected`() {
        // methodOne: synchronized(a) { synchronized(b) { } }
        // methodTwo: synchronized(b) { synchronized(a) { } }
        val edges = listOf(edge("a", "b", "methodOne"), edge("b", "a", "methodTwo"))
        val cycles = LockCycleFinder.findCycles(edges)
        assertEquals(1, cycles.size)
        assertEquals(2, cycles[0].edges.size)
    }

    fun `test transitive three-lock cycle is detected`() {
        // a->b in one method, b->c in another, c->a in a third: a full cycle across 3 sites.
        val edges = listOf(edge("a", "b", "m1"), edge("b", "c", "m2"), edge("c", "a", "m3"))
        val cycles = LockCycleFinder.findCycles(edges)
        assertEquals(1, cycles.size)
        assertEquals(3, cycles[0].edges.size)
    }

    fun `test same lock text used consistently in the same order across methods is never a cycle`() {
        // Every method acquires a then b -- consistent order, the safe pattern.
        val edges = listOf(edge("a", "b", "m1"), edge("a", "b", "m2"), edge("a", "b", "m3"))
        assertTrue(LockCycleFinder.findCycles(edges).isEmpty())
    }

    fun `test unrelated lock pairs alongside a real cycle do not suppress it`() {
        val edges = listOf(
            edge("x", "y", "unrelated1"),
            edge("y", "z", "unrelated2"),
            edge("a", "b", "methodOne"),
            edge("b", "a", "methodTwo"),
        )
        val cycles = LockCycleFinder.findCycles(edges)
        assertEquals(1, cycles.size)
    }

    fun `test four lock diamond without a cycle is not flagged`() {
        // a->b, a->c, b->d, c->d -- multiple paths converge on d, but no back-edge -- not a cycle.
        val edges = listOf(edge("a", "b"), edge("a", "c"), edge("b", "d"), edge("c", "d"))
        assertTrue(LockCycleFinder.findCycles(edges).isEmpty())
    }
}
