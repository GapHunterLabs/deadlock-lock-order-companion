package dev.gaphunter.deadlocklockordercompanion.detect

import com.intellij.psi.PsiJavaFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class JavaLockNestingFinderTest : BasePlatformTestCase() {

    private fun findEdges(code: String): List<LockNestingEdgeSummary> {
        val file = myFixture.configureByText("Account.java", code) as PsiJavaFile
        val psiClass = file.classes.first()
        return JavaLockNestingFinder.findAll(psiClass).map { LockNestingEdgeSummary(it.outerLock, it.innerLock, it.containingMethodName) }
    }

    data class LockNestingEdgeSummary(val outer: String, val inner: String, val method: String)

    fun `test nested synchronized blocks produce one edge`() {
        val edges = findEdges(
            """
            class Account {
                private final Object lockA = new Object();
                private final Object lockB = new Object();

                void transfer() {
                    synchronized (lockA) {
                        synchronized (lockB) {
                            // move funds
                        }
                    }
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, edges.size)
        assertEquals(LockNestingEdgeSummary("lockA", "lockB", "transfer"), edges[0])
    }

    fun `test single synchronized block with no nesting produces no edges`() {
        val edges = findEdges(
            """
            class Account {
                private final Object lockA = new Object();

                void deposit() {
                    synchronized (lockA) {
                        // credit balance
                    }
                }
            }
            """.trimIndent(),
        )
        assertTrue(edges.isEmpty())
    }

    fun `test synchronized instance method nesting a synchronized block uses implicit this`() {
        val edges = findEdges(
            """
            class Account {
                private final Object lockB = new Object();

                synchronized void withdraw() {
                    synchronized (lockB) {
                        // debit balance
                    }
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, edges.size)
        assertEquals(LockNestingEdgeSummary("this", "lockB", "withdraw"), edges[0])
    }

    fun `test synchronized static method uses the class monitor as implicit lock`() {
        val edges = findEdges(
            """
            class Account {
                private static final Object registry = new Object();

                static synchronized void register() {
                    synchronized (registry) {
                        // add to registry
                    }
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, edges.size)
        assertEquals(LockNestingEdgeSummary("Account.class", "registry", "register"), edges[0])
    }

    fun `test triple nesting produces two transitive edges`() {
        val edges = findEdges(
            """
            class Account {
                private final Object a = new Object();
                private final Object b = new Object();
                private final Object c = new Object();

                void complexOp() {
                    synchronized (a) {
                        synchronized (b) {
                            synchronized (c) {
                                // deepest operation
                            }
                        }
                    }
                }
            }
            """.trimIndent(),
        )
        assertEquals(2, edges.size)
        assertTrue(edges.contains(LockNestingEdgeSummary("a", "b", "complexOp")))
        assertTrue(edges.contains(LockNestingEdgeSummary("b", "c", "complexOp")))
    }

    fun `test sibling synchronized blocks not nested inside each other produce no edge between them`() {
        val edges = findEdges(
            """
            class Account {
                private final Object a = new Object();
                private final Object b = new Object();

                void twoSeparateSteps() {
                    synchronized (a) {
                        // step 1
                    }
                    synchronized (b) {
                        // step 2
                    }
                }
            }
            """.trimIndent(),
        )
        assertTrue(edges.isEmpty())
    }

    fun `test two methods with opposite nesting order both produce their own edge -- the real deadlock shape`() {
        val edges = findEdges(
            """
            class Account {
                private final Object lockA = new Object();
                private final Object lockB = new Object();

                void methodOne() {
                    synchronized (lockA) {
                        synchronized (lockB) {
                            // ...
                        }
                    }
                }

                void methodTwo() {
                    synchronized (lockB) {
                        synchronized (lockA) {
                            // ...
                        }
                    }
                }
            }
            """.trimIndent(),
        )
        assertEquals(2, edges.size)
        assertTrue(edges.contains(LockNestingEdgeSummary("lockA", "lockB", "methodOne")))
        assertTrue(edges.contains(LockNestingEdgeSummary("lockB", "lockA", "methodTwo")))

        // And feeding those edges to the cycle finder confirms the end-to-end mechanism.
        val realEdges = myFixture.configureByText(
            "Account2.java",
            """
            class Account {
                private final Object lockA = new Object();
                private final Object lockB = new Object();

                void methodOne() {
                    synchronized (lockA) {
                        synchronized (lockB) {
                        }
                    }
                }

                void methodTwo() {
                    synchronized (lockB) {
                        synchronized (lockA) {
                        }
                    }
                }
            }
            """.trimIndent(),
        ).let { (it as PsiJavaFile).classes.first() }
        val cycles = LockCycleFinder.findCycles(JavaLockNestingFinder.findAll(realEdges))
        assertEquals(1, cycles.size)
    }
}
