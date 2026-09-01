package dev.gaphunter.deadlocklockordercompanion.detect

import com.intellij.psi.PsiJavaFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Tests for the v0.2 cross-method mechanism specifically -- a lock
 * acquired not by direct textual nesting but through a call to
 * another method of the same class. [JavaLockNestingFinderTest]
 * covers the v0.1 direct-nesting mechanism, still exercised here
 * unchanged (this is additive, not a replacement).
 */
class TransitiveLockDetectionTest : BasePlatformTestCase() {

    private fun edgesOf(code: String): List<Triple<String, String, String>> {
        val file = myFixture.configureByText("Account.java", code) as PsiJavaFile
        val psiClass = file.classes.first()
        return JavaLockNestingFinder.findAll(psiClass).map { Triple(it.outerLock, it.innerLock, it.containingMethodName) }
    }

    fun `test lock acquired inside a called method is a real edge from the caller`() {
        val edges = edgesOf(
            """
            class Account {
                private final Object lockA = new Object();
                private final Object lockB = new Object();

                void outer() {
                    synchronized (lockA) {
                        helper();
                    }
                }

                void helper() {
                    synchronized (lockB) {
                    }
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, edges.size)
        assertEquals(Triple("lockA", "lockB", "outer"), edges[0])
    }

    fun `test lock acquired two calls deep is still found transitively`() {
        val edges = edgesOf(
            """
            class Account {
                private final Object lockA = new Object();
                private final Object lockC = new Object();

                void outer() {
                    synchronized (lockA) {
                        middle();
                    }
                }

                void middle() {
                    inner();
                }

                void inner() {
                    synchronized (lockC) {
                    }
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, edges.size)
        assertEquals(Triple("lockA", "lockC", "outer"), edges[0])
    }

    fun `test a call that acquires no lock produces no edge`() {
        val edges = edgesOf(
            """
            class Account {
                private final Object lockA = new Object();

                void outer() {
                    synchronized (lockA) {
                        helper();
                    }
                }

                void helper() {
                    // no locking at all
                }
            }
            """.trimIndent(),
        )
        assertTrue(edges.isEmpty())
    }

    fun `test a real call cycle between methods never crashes and still resolves the reachable lock`() {
        val edges = edgesOf(
            """
            class Account {
                private final Object lockA = new Object();
                private final Object lockB = new Object();

                void outer() {
                    synchronized (lockA) {
                        pingPongA();
                    }
                }

                void pingPongA() {
                    pingPongB();
                }

                void pingPongB() {
                    synchronized (lockB) {
                        pingPongA(); // real call cycle -- must not stack overflow / infinite loop
                    }
                }
            }
            """.trimIndent(),
        )
        // outer -> pingPongA -> pingPongB acquires lockB -> the edge from outer's own
        // synchronized(lockA) block is what matters; the internal cycle must not crash.
        assertEquals(1, edges.size)
        assertEquals(Triple("lockA", "lockB", "outer"), edges[0])
    }

    fun `test cross-method deadlock cycle -- two methods each hold one lock while calling into the other's lock via a helper`() {
        val edges = edgesOf(
            """
            class Account {
                private final Object lockA = new Object();
                private final Object lockB = new Object();

                void methodOne() {
                    synchronized (lockA) {
                        takeB();
                    }
                }

                void takeB() {
                    synchronized (lockB) {
                    }
                }

                void methodTwo() {
                    synchronized (lockB) {
                        takeA();
                    }
                }

                void takeA() {
                    synchronized (lockA) {
                    }
                }
            }
            """.trimIndent(),
        )
        assertEquals(2, edges.size)
        assertTrue(edges.contains(Triple("lockA", "lockB", "methodOne")))
        assertTrue(edges.contains(Triple("lockB", "lockA", "methodTwo")))

        // And the cycle finder confirms the end-to-end v0.2 mechanism catches it.
        val file = myFixture.configureByText(
            "Account2.java",
            """
            class Account {
                private final Object lockA = new Object();
                private final Object lockB = new Object();

                void methodOne() {
                    synchronized (lockA) {
                        takeB();
                    }
                }

                void takeB() {
                    synchronized (lockB) {
                    }
                }

                void methodTwo() {
                    synchronized (lockB) {
                        takeA();
                    }
                }

                void takeA() {
                    synchronized (lockA) {
                    }
                }
            }
            """.trimIndent(),
        ).let { (it as PsiJavaFile).classes.first() }
        val cycles = LockCycleFinder.findCycles(JavaLockNestingFinder.findAll(file))
        assertEquals(1, cycles.size)
    }

    fun `test reentrant call to a method taking the SAME lock is not reported as a new edge`() {
        val edges = edgesOf(
            """
            class Account {
                private final Object lockA = new Object();

                void outer() {
                    synchronized (lockA) {
                        reenter();
                    }
                }

                void reenter() {
                    synchronized (lockA) {
                        // same lock -- reentrant, not a distinct edge
                    }
                }
            }
            """.trimIndent(),
        )
        assertTrue(edges.isEmpty())
    }
}
