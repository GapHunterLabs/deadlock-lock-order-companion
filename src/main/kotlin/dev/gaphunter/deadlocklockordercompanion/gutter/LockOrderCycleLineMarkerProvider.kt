package dev.gaphunter.deadlocklockordercompanion.gutter

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import dev.gaphunter.deadlocklockordercompanion.detect.JavaLockNestingFinder
import dev.gaphunter.deadlocklockordercompanion.detect.LockCycleFinder
import dev.gaphunter.deadlocklockordercompanion.model.LockNestingEdge
import dev.gaphunter.deadlocklockordercompanion.review.ReviewPrompt

/**
 * Marks every code site that participates in a lock acquisition-order
 * cycle within its own class (see [JavaLockNestingFinder] and
 * [LockCycleFinder] for the real mechanism -- a directed graph of
 * "this lock is acquired while that one is held", cycle = potential
 * deadlock). One gutter icon per edge in the cycle, on the acquisition
 * site of the OUTER lock of that edge -- that is the statement whose
 * ordering, if swapped to match the other code path, would break the
 * cycle.
 */
class LockOrderCycleLineMarkerProvider : LineMarkerProviderDescriptor(), DumbAware {

    override fun getName(): String = "Lock acquisition-order cycle (potential deadlock)"

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(elements: MutableList<out PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
        val file = elements.firstOrNull()?.containingFile ?: return
        if (file.language.id != "JAVA") return

        // Every top-level and nested class analyzed independently -- v0.1 is
        // bounded to a single class, never resolves locks shared across classes.
        val classes = mutableListOf<PsiClass>()
        file.accept(object : com.intellij.psi.JavaRecursiveElementWalkingVisitor() {
            override fun visitClass(aClass: PsiClass) {
                super.visitClass(aClass)
                classes += aClass
            }
        })
        if (classes.isEmpty()) return

        val anchorsInCycles = mutableMapOf<PsiElement, MutableList<LockNestingEdge>>()
        for (psiClass in classes) {
            val edges = JavaLockNestingFinder.findAll(psiClass)
            val cycles = LockCycleFinder.findCycles(edges)
            for (cycle in cycles) {
                for (edge in cycle.edges) {
                    anchorsInCycles.getOrPut(edge.outerAnchor) { mutableListOf() } += edge
                }
            }
        }
        if (anchorsInCycles.isEmpty()) return

        for (element in elements) {
            val edgesHere = anchorsInCycles[element] ?: continue
            result.add(buildMarker(element, edgesHere))

            val path = file.virtualFile?.path ?: continue
            val lineNumber = file.viewProvider.document?.getLineNumber(element.textRange.startOffset) ?: -1
            ReviewPrompt.recordHit(file.project, "$path:$lineNumber")
        }
    }

    private fun buildMarker(element: PsiElement, edges: List<LockNestingEdge>): LineMarkerInfo<PsiElement> {
        val description = edges.joinToString(" | ") { edge ->
            "acquires \"${edge.outerLock}\" then \"${edge.innerLock}\" in ${edge.containingMethodName}()"
        }
        val tooltip = "Lock acquisition-order cycle in this class: $description -- another method in the same " +
            "class acquires the same locks in the opposite order. If two threads hit these methods at the " +
            "same time, each can hold one lock while waiting for the other: a classic deadlock."
        return LineMarkerInfo(
            element,
            element.textRange,
            LockOrderIcons.CYCLE,
            { _: PsiElement -> tooltip },
            null,
            GutterIconRenderer.Alignment.RIGHT,
            { tooltip },
        )
    }
}
