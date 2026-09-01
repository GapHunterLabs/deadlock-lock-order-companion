package dev.gaphunter.deadlocklockordercompanion.detect

import dev.gaphunter.deadlocklockordercompanion.model.LockNestingEdge

/**
 * Given every [LockNestingEdge] found in a class (outer lock -> inner
 * lock, "outer is held while inner is acquired"), builds the directed
 * lock acquisition-order graph and reports every simple cycle -- the
 * textbook signal for a potential deadlock: if one code path acquires
 * A then B, and another acquires B then A, two threads can each hold
 * one and wait forever for the other.
 *
 * Classic algorithm (MIT/ECOOP 2005; Peahen FSE'22 refines it for
 * scale, not applicable at this project's size): DFS from every node,
 * tracking the current recursion stack -- a back-edge to a node
 * already on the stack closes a cycle.
 *
 * **v0.1 scope, stated honestly:** reports the first cycle found
 * through each starting node once (not every distinct simple cycle in
 * a densely-connected graph, which can be exponential) -- enough to
 * flag the real problem sites without runaway cost on a large class.
 */
object LockCycleFinder {

    data class Cycle(val edges: List<LockNestingEdge>)

    fun findCycles(edges: List<LockNestingEdge>): List<Cycle> {
        if (edges.isEmpty()) return emptyList()

        val outgoing: Map<String, List<LockNestingEdge>> = edges.groupBy { it.outerLock }
        val allNodes = (edges.map { it.outerLock } + edges.map { it.innerLock }).distinct()

        val cycles = mutableListOf<Cycle>()
        val globallyReportedNodes = mutableSetOf<String>()

        for (start in allNodes) {
            if (start in globallyReportedNodes) continue
            val stack = ArrayDeque<LockNestingEdge>()
            val onStack = linkedSetOf<String>() // insertion order preserved, used to slice the cycle back to its start
            val found = dfs(start, outgoing, stack, onStack, globallyReportedNodes)
            if (found != null) {
                cycles += found
                found.edges.forEach { globallyReportedNodes += it.outerLock }
            }
        }

        return cycles
    }

    private fun dfs(
        node: String,
        outgoing: Map<String, List<LockNestingEdge>>,
        stack: ArrayDeque<LockNestingEdge>,
        onStack: LinkedHashSet<String>,
        globallyReportedNodes: Set<String>,
    ): Cycle? {
        if (node in globallyReportedNodes) return null
        onStack += node
        for (edge in outgoing[node].orEmpty()) {
            if (edge.innerLock in onStack) {
                // Closed a cycle: slice the stack back to where innerLock first appeared.
                val cycleStartIndex = stack.indexOfFirst { it.outerLock == edge.innerLock }
                val cycleEdges = if (cycleStartIndex >= 0) {
                    stack.toList().subList(cycleStartIndex, stack.size) + edge
                } else {
                    listOf(edge) // direct self-adjacent edge (node -> node's own onStack entry from this call)
                }
                return Cycle(cycleEdges)
            }
            if (edge.innerLock !in onStack) {
                stack.addLast(edge)
                val result = dfs(edge.innerLock, outgoing, stack, onStack, globallyReportedNodes)
                stack.removeLast()
                if (result != null) return result
            }
        }
        onStack -= node
        return null
    }
}
