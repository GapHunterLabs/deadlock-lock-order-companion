package dev.gaphunter.deadlocklockordercompanion.detect

import com.intellij.psi.JavaRecursiveElementWalkingVisitor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiSynchronizedStatement
import dev.gaphunter.deadlocklockordercompanion.model.LockNestingEdge

/**
 * Finds every real lock acquisition-order edge inside a single Java
 * class: a `synchronized (outer) { ... synchronized (inner) { ... } }`
 * nesting, where `inner` is lexically inside `outer`'s block -- the
 * exact shape a lock-order graph is built from (MIT/ECOOP 2005 "Static
 * Deadlock Detection for Java Libraries"; the underlying principle,
 * a cycle in the lock acquisition-order graph signals a potential
 * deadlock, is textbook and requires no external citation).
 *
 * A `synchronized` **method** counts as an implicit acquisition of
 * `this` (instance methods) or the class's own monitor (static
 * methods, `ClassName.class`) around its entire body -- same rule the
 * JLS itself uses (17.1, "synchronized methods automatically perform a
 * synchronized action").
 *
 * **v0.1 scope, stated honestly:**
 * - Bounded to ONE class at a time (see README) -- no cross-class/
 *   cross-file resolution. A nesting edge only exists between two
 *   `synchronized` sites textually inside the same class body.
 * - Lock identity is by **exact expression text** (`this`, `lockA`,
 *   `this.cacheLock`) -- two different-looking expressions that
 *   happen to reference the same object at runtime are never merged;
 *   two identical-looking expressions on different instances are
 *   never told apart. Same acknowledged limitation as every other
 *   catalog plugin that matches by text/PSI rather than full type/
 *   points-to resolution.
 * - Only the direct/first inner `synchronized` found inside each
 *   outer block is recorded per outer site -- multiple sibling inner
 *   blocks each produce their own edge (all real), but a
 *   triple-nested case still produces edges transitively (A->B, B->C)
 *   which is exactly what the cycle search over the whole graph needs.
 */
object JavaLockNestingFinder {

    fun findAll(psiClass: PsiClass): List<LockNestingEdge> {
        val edges = mutableListOf<LockNestingEdge>()

        for (method in psiClass.methods) {
            val body = method.body ?: continue
            val implicitLock = implicitMethodLock(method)
            if (implicitLock != null) {
                // The whole method body is itself inside an implicit
                // synchronized region -- any synchronized block/method
                // reached from here nests one level deeper.
                collectNestedSynchronized(body, method).forEach { inner ->
                    edges += LockNestingEdge(
                        outerLock = implicitLock,
                        innerLock = inner.first,
                        outerAnchor = method.nameIdentifier ?: method,
                        innerAnchor = inner.second,
                        containingMethodName = method.name,
                    )
                }
            }

            body.accept(object : JavaRecursiveElementWalkingVisitor() {
                override fun visitSynchronizedStatement(statement: PsiSynchronizedStatement) {
                    super.visitSynchronizedStatement(statement)
                    val outerLockText = statement.lockExpression?.text ?: return
                    val outerBody = statement.body ?: return
                    collectNestedSynchronized(outerBody, method).forEach { inner ->
                        edges += LockNestingEdge(
                            outerLock = outerLockText,
                            innerLock = inner.first,
                            outerAnchor = statement.lockExpression ?: statement,
                            innerAnchor = inner.second,
                            containingMethodName = method.name,
                        )
                    }
                }
            })
        }

        return edges
    }

    /** `this` for a synchronized instance method, the class name for a synchronized static method, null otherwise. */
    private fun implicitMethodLock(method: PsiMethod): String? {
        if (!method.hasModifierProperty(com.intellij.psi.PsiModifier.SYNCHRONIZED)) return null
        return if (method.hasModifierProperty(com.intellij.psi.PsiModifier.STATIC)) {
            (method.containingClass?.name ?: "<class>") + ".class"
        } else {
            "this"
        }
    }

    /**
     * The NEAREST `synchronized` statement(s) lexically inside [scope]
     * along each branch -- i.e. a manual bounded-depth search that
     * stops descending a branch the moment it finds one, so a
     * triple-nested `synchronized(a){synchronized(b){synchronized(c){}}}`
     * yields only the immediate a->b pair here (b->c is produced
     * separately when `findAll`'s own top-level visitor reaches
     * statement `b` and calls this function again with `b`'s body as
     * scope) -- deliberately manual recursion instead of
     * [JavaRecursiveElementWalkingVisitor] so correctness does not
     * depend on that framework's stop-descending semantics, only on
     * this function's own explicit `children` walk. Only searches
     * within the same containing method [outerMethod] belongs to -- a
     * call to another method is not followed here (that would require
     * real call-graph resolution across the whole class; left for a
     * future version, see README).
     */
    private fun collectNestedSynchronized(
        scope: PsiElement,
        outerMethod: PsiMethod,
    ): List<Pair<String, PsiElement>> {
        val found = mutableListOf<Pair<String, PsiElement>>()
        fun walk(element: PsiElement) {
            if (element is PsiSynchronizedStatement) {
                val lockText = element.lockExpression?.text
                if (lockText != null) {
                    found += lockText to (element.lockExpression ?: element)
                    return // stop descending this branch -- deeper nesting is its own edge, added when findAll reaches this statement
                }
            }
            for (child in element.children) walk(child)
        }
        for (child in scope.children) walk(child)
        return found
    }
}
