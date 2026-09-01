package dev.gaphunter.deadlocklockordercompanion.detect

import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiSynchronizedStatement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch

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
 * **v0.3 extends this across class boundaries** through an injected
 * collaborator: `methodA` calling `synchronized(lockA) { collaborator.helper(); }`
 * where `collaborator` is a field/parameter of `owningClass` is
 * followed into the collaborator's OWN lock graph exactly the same
 * way -- the most common real-production shape (a service calling
 * into another injected service), not just self-recursion. Resolving
 * a collaborator's declared type to the class whose methods to
 * actually analyze requires a real answer to "which concrete class is
 * this": if the declared type is already a concrete class, that's it;
 * if it's an interface/abstract type, the SAME MODULE is searched for
 * implementations, and only a SINGLE unambiguous one is followed --
 * two or more real implementations in the same module is genuine
 * ambiguity v0.3 does not attempt to resolve (see
 * [resolveUniqueConcreteClassInModule]).
 *
 * **Still bounded to a fixed depth** ([MAX_COLLABORATOR_DEPTH]) -- a
 * dense collaboration graph (service A calls B calls C calls D...)
 * would otherwise explode in cost with no natural stopping point the
 * way a single class's own method count already bounds v0.1/v0.2.
 * Only crossing INTO a collaborator's class counts against this depth
 * -- calls within the same class remain bounded by
 * [MAX_METHODS_PER_CLASS] and the existing cycle protection, exactly
 * as in v0.2.
 *
 * **Memoized with cycle protection, now keyed globally by [PsiMethod]
 * (not per-class)** -- the same collaborator method can be reached
 * from many call sites across several classes, and a real call cycle
 * across class boundaries (A calls B calls back into A) must be
 * broken exactly like the same-class case already was in v0.2.
 */
object TransitiveLockResolver {

    /** Recursion/method-count safety valve -- a class with more methods than this is skipped for tracing, never crashes. */
    private const val MAX_METHODS_PER_CLASS = 200

    /**
     * How many collaborator classes deep a lock-order chain is followed.
     * Only incremented when a call crosses INTO a different class's
     * method (never for same-class calls, which are already bounded by
     * [MAX_METHODS_PER_CLASS] and cycle protection) -- this is the
     * explicit, fixed cost bound v0.3's README documents for what would
     * otherwise be an unbounded collaboration-graph walk.
     */
    private const val MAX_COLLABORATOR_DEPTH = 3

    fun resolveAll(psiClass: PsiClass): Map<PsiMethod, MethodLockSummary> {
        val memo = mutableMapOf<PsiMethod, MethodLockSummary>()
        if (psiClass.methods.size > MAX_METHODS_PER_CLASS) return memo
        for (method in psiClass.methods) {
            resolve(method, psiClass, memo, linkedSetOf(), depth = 0)
        }
        return memo
    }

    private fun resolve(
        method: PsiMethod,
        owningClass: PsiClass,
        memo: MutableMap<PsiMethod, MethodLockSummary>,
        inProgress: LinkedHashSet<PsiMethod>,
        depth: Int,
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
                        val target = resolveCallTarget(element, owningClass, depth)
                        if (target != null) {
                            val (targetClass, targetMethod, targetDepth) = target
                            val calleeSummary = resolve(targetMethod, targetClass, memo, inProgress, targetDepth)
                            for (lockText in calleeSummary.locksAcquired) record(lockText, element)
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

    /**
     * The single source of truth for "what real, concrete method does
     * this call site actually reach" -- tries a same-class method
     * first, then an injected-collaborator method (only when [depth]
     * hasn't hit [MAX_COLLABORATOR_DEPTH]). Returns the target's class,
     * its CONCRETE method (never an interface/abstract declaration --
     * see [resolveCollaboratorMethod]), and the depth to resolve it at.
     *
     * Exposed (not private) so [JavaLockNestingFinder] can look up the
     * exact same [MethodLockSummary] key this resolver populated --
     * `PsiMethodCallExpression.resolveMethod()` alone resolves a
     * collaborator call to its DECLARED (possibly interface/abstract)
     * method, which is never the key [resolve] actually memoizes under
     * for a collaborator call (the concrete implementation) -- looking
     * those two up independently would silently never match.
     */
    fun resolveCallTarget(call: PsiMethodCallExpression, owningClass: PsiClass, depth: Int): Triple<PsiClass, PsiMethod, Int>? {
        val sameClassCallee = resolveSameClassMethod(call, owningClass)
        if (sameClassCallee != null) return Triple(owningClass, sameClassCallee, depth)
        if (depth >= MAX_COLLABORATOR_DEPTH) return null
        val (collabClass, collabMethod) = resolveCollaboratorMethod(call, owningClass) ?: return null
        return Triple(collabClass, collabMethod, depth + 1)
    }

    /** Only follows a call whose target is a method declared in [owningClass] itself -- never a library/external call. */
    private fun resolveSameClassMethod(call: PsiMethodCallExpression, owningClass: PsiClass): PsiMethod? {
        val resolved = call.resolveMethod() ?: return null
        if (resolved.containingClass != owningClass) return null
        return resolved
    }

    /**
     * Resolves `collaborator.method()` to the concrete class/method to
     * actually analyze, when `collaborator` is a field or parameter of
     * [owningClass] whose declared type resolves to a SINGLE concrete
     * class -- v0.3's real, bounded extension past a single class's own
     * methods. Returns null (never a guess) for every ambiguous or
     * unresolvable shape: no qualifier (already handled as a same-class
     * call), a qualifier that isn't a field/parameter, a declared type
     * with zero or more than one real implementation in the same
     * module, or a resolved method with no source body (a compiled
     * library method -- nothing to analyze).
     */
    private fun resolveCollaboratorMethod(call: PsiMethodCallExpression, owningClass: PsiClass): Pair<PsiClass, PsiMethod>? {
        val qualifier = call.methodExpression.qualifierExpression as? PsiReferenceExpression ?: return null
        val resolvedVariable = qualifier.resolve() ?: return null
        val declaredType = when (resolvedVariable) {
            is PsiField -> if (resolvedVariable.containingClass == owningClass) resolvedVariable.type else return null
            is PsiParameter -> resolvedVariable.type
            else -> return null
        }
        val declaredClass = (declaredType as? PsiClassType)?.resolve() ?: return null
        val concreteClass = resolveUniqueConcreteClassInModule(declaredClass, owningClass) ?: return null

        val calledMethod = call.resolveMethod() ?: return null
        val concreteMethod = concreteClass.findMethodBySignature(calledMethod, true) ?: return null
        if (concreteMethod.body == null) return null // compiled/library method -- no source to analyze
        return concreteClass to concreteMethod
    }

    /**
     * [declaredClass] itself when it's already concrete (not an
     * interface, not abstract); when it's an interface/abstract type,
     * the SINGLE concrete class implementing it within [owningClass]'s
     * own module -- an interface with zero or more than one real
     * implementation in that module is genuine ambiguity, never
     * resolved by guessing (returns null, same as any other
     * unresolvable shape here).
     */
    private fun resolveUniqueConcreteClassInModule(declaredClass: PsiClass, owningClass: PsiClass): PsiClass? {
        if (!declaredClass.isInterface && !declaredClass.hasModifierProperty(PsiModifier.ABSTRACT)) return declaredClass

        val module = ModuleUtilCore.findModuleForPsiElement(owningClass) ?: return null
        val scope = GlobalSearchScope.moduleScope(module)
        val implementations = ClassInheritorsSearch.search(declaredClass, scope, true).findAll()
            .filter { !it.isInterface && !it.hasModifierProperty(PsiModifier.ABSTRACT) }
        return implementations.singleOrNull()
    }
}
