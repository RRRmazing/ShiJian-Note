package com.shijiannote.app

import com.shijiannote.app.data.NoteNode

/** Iterative walks remain bounded even with deep imported trees or damaged cyclic input. */
object TreeRules {
    fun descendants(id: String, nodes: List<NoteNode>): Set<String> {
        val children = nodes.groupBy { it.parentId }
        val seen = mutableSetOf<String>()
        val queue = java.util.ArrayDeque<String>().apply { add(id) }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!seen.add(current)) continue
            children[current].orEmpty().forEach { queue.add(it.id) }
        }
        return seen
    }
    fun ancestors(node: NoteNode, nodes: List<NoteNode>): List<NoteNode> {
        val map = nodes.associateBy { it.id }
        val seen = mutableSetOf(node.id)
        val result = mutableListOf<NoteNode>()
        var parent = node.parentId
        while (parent != null && seen.add(parent)) {
            val n = map[parent] ?: break
            result += n
            parent = n.parentId
        }
        return result.asReversed()
    }
    fun canMove(node: NoteNode, parent: NoteNode?, nodes: List<NoteNode>): Boolean =
        parent == null || (parent.kind == "folder" && parent.deletedAt == null && parent.id !in descendants(node.id, nodes))
    fun path(node: NoteNode, nodes: List<NoteNode>): String = (ancestors(node, nodes) + node).joinToString(" / ") { it.displayTitle() }
    fun imagePreference(node: NoteNode, nodes: List<NoteNode>, storage: Boolean, fallback: String): String =
        (listOf(node) + ancestors(node, nodes).asReversed()).firstNotNullOfOrNull {
            (if (storage) it.imageStorage else it.imageDisplay).takeUnless { value -> value == "inherit" }
        } ?: fallback
}
