package dev.gaphunter.deadlocklockordercompanion.detect

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiSynchronizedStatement

/**
 * v0.2 mechanism: computes, for a method, every lock it can end up
 * holding -- not just from its own `synchronized` sites (v0.1's
 * scope, see [JavaLockNestingFinder]) but transitively through calls
 * to OTHER methods of the same class that themselves acquire a lock.
 *
 * This is what makes the cross-method case real: `methodA` calling
 * `synchronized(lockA) { helper(); }` where `helper()` itself does
 * `synchronized(lockB) { ... }` is exactly as much a lock-order edge
 * (lockA -> lockB) as a direct textual nesting is -- v0.1 could not
 * see this at all, since it never followed a call.
 *
 * **Still bounded to one class** (see README) -- a call to a method
 * outside the class (an unresolved receiver, a call into another
 * class/library) is never followed; only calls whose receiver is
 * implicit (`this`/unqualified) or explicit `this.xxx()` to a method
 * declared in the SAME [PsiClass] are.
 *
 * **Memoized with cycle protection.** The same callee is often
 * reached from several call sites, so each method's summary is
 * computed once ([memo]) and reused. A real call cycle (`a()` calls
 * `b()` calls `a()`) is broken by [inProgress] -- a method already
 * being resolved on the current recursion path contributes an empty
 * summary rather than recursing forever; whatever locks IT acquires
 * are already being accounted for by the caller higher up the stack.
 */
object TransitiveLockResolver {

    /** Recursion/method-count safety valve -- a class with more methods than this is skipped for v0.2 tracing, never crashes. */
    private const val MAX_METHODS_PER_CLASS = 200

    fun resolveAll(psiClass: PsiClass): Map<PsiMethod, MethodLockSummary> {
        if (psiClass.methods.size > MAX_METHODS_PER_CLASS) return emptyMap()
        val memo = mutableMapOf<PsiMethod, MethodLockSummary>()
        for (method in psiClass.methods) {
            resolve(method, psiClass, memo, linkedSetOf())
        }
        return memo
    }

    private fun resolve(
        method: PsiMethod,
        owningClass: PsiClass,
        memo: MutableMap<PsiMethod, MethodLockSummary>,
        inProgress: LinkedHashSet<PsiMethod>,
    ): MethodLockSummary {
        memo[method]?.let { return it }
        if (method in inProgress) return MethodLockSummary(emptySet(), emptyMap()) // real call cycle -- break it here

        inProgress += method
        val locks = mutableSetOf<String>()
        val anchors = mutableMapOf<String, PsiElement>()

        fun record(lockText: String, anchor: PsiElement) {
            if (locks.add(lockText)) anchors[lockText] = anchor
        }

        if (method.hasModifierProperty(PsiModifier.SYNCHRONIZED)) {
            val implicit = if (method.hasModifierProperty(PsiModifier.STATIC)) {
                (method.containingClass?.name ?: "<class>") + ".class"
            } else {
                "this"
            }
            record(implicit, method.nameIdentifier ?: method)
        }

        val body = method.body
        if (body != null) {
            fun walk(element: PsiElement) {
                when (element) {
                    is PsiSynchronizedStatement -> {
                        val lockText = element.lockExpression?.text
                        if (lockText != null) record(lockText, element.lockExpression ?: element)
                        // Still descend into the synchronized body -- further locks/calls inside also count.
                        for (child in element.children) walk(child)
                    }
                    is PsiMethodCallExpression -> {
                        val callee = resolveSameClassMethod(element, owningClass)
                        if (callee != null) {
                            val calleeSummary = resolve(callee, owningClass, memo, inProgress)
                            for (lockText in calleeSummary.locksAcquired) {
                                // Anchored at THIS call site, not inside the callee -- actionable for the user editing this method.
                                record(lockText, element)
                            }
                        }
                        for (child in element.children) walk(child)
                    }
                    else -> for (child in element.children) walk(child)
                }
            }
            walk(body)
        }

        inProgress -= method
        val summary = MethodLockSummary(locks, anchors)
        memo[method] = summary
        return summary
    }

    /** Only follows a call whose target is a method declared in [owningClass] itself -- never a library/external call. */
    private fun resolveSameClassMethod(call: PsiMethodCallExpression, owningClass: PsiClass): PsiMethod? {
        val resolved = call.resolveMethod() ?: return null
        if (resolved.containingClass != owningClass) return null
        return resolved
    }
}
