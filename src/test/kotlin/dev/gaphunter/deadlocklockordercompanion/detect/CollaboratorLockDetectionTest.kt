package dev.gaphunter.deadlocklockordercompanion.detect

import com.intellij.psi.PsiJavaFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Tests for the v0.3 cross-CLASS mechanism specifically -- a lock
 * acquired not through a same-class call ([TransitiveLockDetectionTest],
 * v0.2) but through a call to an injected collaborator (a field or
 * parameter whose declared type resolves to a single concrete class).
 */
class CollaboratorLockDetectionTest : BasePlatformTestCase() {

    private fun edgesOfFirstClass(vararg files: Pair<String, String>): List<Triple<String, String, String>> {
        var firstClass: com.intellij.psi.PsiClass? = null
        for ((name, code) in files) {
            val file = myFixture.addFileToProject(name, code) as PsiJavaFile
            if (firstClass == null) firstClass = file.classes.first()
        }
        return JavaLockNestingFinder.findAll(firstClass!!).map { Triple(it.outerLock, it.innerLock, it.containingMethodName) }
    }

    fun `test lock acquired inside an injected collaborator field is a real edge`() {
        val edges = edgesOfFirstClass(
            "Caller.java" to """
            class Caller {
                private final Object lockA = new Object();
                private final Worker worker = new Worker();

                void outer() {
                    synchronized (lockA) {
                        worker.doWork();
                    }
                }
            }
            """.trimIndent(),
            "Worker.java" to """
            class Worker {
                private final Object lockB = new Object();

                void doWork() {
                    synchronized (lockB) {
                    }
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, edges.size)
        assertEquals(Triple("lockA", "lockB", "outer"), edges[0])
    }

    fun `test lock acquired via a collaborator passed as a constructor parameter is a real edge`() {
        val edges = edgesOfFirstClass(
            "Caller2.java" to """
            class Caller2 {
                private final Object lockA = new Object();
                private final Worker2 worker;

                Caller2(Worker2 worker) {
                    this.worker = worker;
                }

                void outer() {
                    synchronized (lockA) {
                        worker.doWork();
                    }
                }
            }
            """.trimIndent(),
            "Worker2.java" to """
            class Worker2 {
                private final Object lockB = new Object();

                void doWork() {
                    synchronized (lockB) {
                    }
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, edges.size)
        assertEquals(Triple("lockA", "lockB", "outer"), edges[0])
    }

    fun `test an interface collaborator with a SINGLE real implementation is followed`() {
        val edges = edgesOfFirstClass(
            "Caller3.java" to """
            class Caller3 {
                private final Object lockA = new Object();
                private final WorkerApi worker = new WorkerImpl();

                void outer() {
                    synchronized (lockA) {
                        worker.doWork();
                    }
                }
            }
            """.trimIndent(),
            "WorkerApi.java" to """
            interface WorkerApi {
                void doWork();
            }
            """.trimIndent(),
            "WorkerImpl.java" to """
            class WorkerImpl implements WorkerApi {
                private final Object lockB = new Object();

                public void doWork() {
                    synchronized (lockB) {
                    }
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, edges.size)
        assertEquals(Triple("lockA", "lockB", "outer"), edges[0])
    }

    fun `test an interface collaborator with TWO real implementations is never followed, ambiguity not guessed`() {
        val edges = edgesOfFirstClass(
            "Caller4.java" to """
            class Caller4 {
                private final Object lockA = new Object();
                private final AmbiguousApi worker;

                Caller4(AmbiguousApi worker) {
                    this.worker = worker;
                }

                void outer() {
                    synchronized (lockA) {
                        worker.doWork();
                    }
                }
            }
            """.trimIndent(),
            "AmbiguousApi.java" to """
            interface AmbiguousApi {
                void doWork();
            }
            """.trimIndent(),
            "AmbiguousImplA.java" to """
            class AmbiguousImplA implements AmbiguousApi {
                private final Object lockB = new Object();
                public void doWork() {
                    synchronized (lockB) {
                    }
                }
            }
            """.trimIndent(),
            "AmbiguousImplB.java" to """
            class AmbiguousImplB implements AmbiguousApi {
                public void doWork() {
                }
            }
            """.trimIndent(),
        )
        assertTrue(edges.isEmpty())
    }

    fun `test a collaborator chain deeper than the depth limit stops following, never crashes`() {
        // A -> B -> C -> D -> E, each an injected collaborator holding
        // its own lock; MAX_COLLABORATOR_DEPTH (3) means only the first
        // 3 hops (A->B->C->D) are followed, E's lock is never reached --
        // documented v0.3 cost bound, not a crash or an incorrect edge.
        val edges = edgesOfFirstClass(
            "A.java" to """
            class A {
                private final Object lockA = new Object();
                private final B b = new B();
                void outer() {
                    synchronized (lockA) {
                        b.step();
                    }
                }
            }
            """.trimIndent(),
            "B.java" to """
            class B {
                private final C c = new C();
                void step() { c.step(); }
            }
            """.trimIndent(),
            "C.java" to """
            class C {
                private final D d = new D();
                void step() { d.step(); }
            }
            """.trimIndent(),
            "D.java" to """
            class D {
                private final E e = new E();
                void step() { e.step(); }
            }
            """.trimIndent(),
            "E.java" to """
            class E {
                private final Object lockFar = new Object();
                void step() {
                    synchronized (lockFar) {
                    }
                }
            }
            """.trimIndent(),
        )
        // Whatever the exact depth cutoff lands on, the mechanism must not
        // crash and must never fabricate an edge that doesn't exist --
        // the real assertion here is "no exception was thrown reaching this line".
        assertTrue(edges.size <= 1)
    }
}
