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
    /** The outermost enforcing ancestor controls its entire branch, including nested rules. */
    fun forcedBy(node: NoteNode, nodes: List<NoteNode>): NoteNode? =
        ancestors(node, nodes).firstOrNull { it.kind == "folder" && it.forceChildren && it.deletedAt == null }

    fun imagePreference(node: NoteNode, nodes: List<NoteNode>, storage: Boolean, fallback: String): String {
        val source = forcedBy(node, nodes) ?: node
        return (listOf(source) + ancestors(source, nodes).asReversed()).firstNotNullOfOrNull {
            (if (storage) it.imageStorage else it.imageDisplay).takeUnless { value -> value == "inherit" }
        } ?: fallback
    }

    fun imageDisplay(block: NoteBlock, node: NoteNode, nodes: List<NoteNode>, fallback: String): String =
        if (forcedBy(node, nodes) != null) imagePreference(node, nodes, false, fallback)
        else block.display.takeUnless { it == "inherit" } ?: imagePreference(node, nodes, false, fallback)

    /** Remove redundant descendant selections; never move a selected child out of its parent. */
    fun topLevel(ids: Set<String>, nodes: List<NoteNode>): List<NoteNode> =
        nodes.filter { it.id in ids && it.deletedAt == null && ancestors(it, nodes).none { ancestor -> ancestor.id in ids } }

    fun toggleSelection(ids: Set<String>, node: NoteNode, nodes: List<NoteNode>): Set<String> =
        if (node.id !in ids) ids + descendants(node.id, nodes)
        else ids - descendants(node.id, nodes) - ancestors(node, nodes).map { it.id }.toSet()

    fun shortTitle(node: NoteNode): String {
        val value = node.displayTitle().replace(Regex("[\\r\\n\\t]"), " ")
        val points = value.codePoints().toArray()
        val title = String(points, 0, minOf(6, points.size)) + if (points.size > 6) "..." else ""
        return if (node.kind == "folder") "《$title》" else title
    }

    data class StructureLine(val node: NoteNode, val prefix: String) {
        val text: String get() = prefix + shortTitle(node)
    }
    fun structure(nodes: List<NoteNode>, root: NoteNode? = null): List<StructureLine> {
        val grouped = nodes.filter { it.deletedAt == null && it.kind != "diary" }.groupBy { it.parentId }
            .mapValues { (_, children) -> children.sortedWith(compareByDescending<NoteNode> { it.pinned }.thenBy { it.position }.thenBy { it.id }) }
        data class Visit(val node: NoteNode, val indent: String, val last: Boolean, val isRoot: Boolean)
        val stack = java.util.ArrayDeque<Visit>()
        val roots = if (root != null) listOf(root) else nodes.filter { it.deletedAt == null && it.kind != "diary" && nodes.none { parent -> parent.id == it.parentId && parent.deletedAt == null } }
            .sortedWith(compareByDescending<NoteNode> { it.pinned }.thenBy { it.position }.thenBy { it.id })
        roots.asReversed().forEach { stack.addFirst(Visit(it, "", true, true)) }
        val seen = mutableSetOf<String>()
        val result = mutableListOf<StructureLine>()
        while (stack.isNotEmpty()) {
            val visit = stack.removeFirst()
            if (!seen.add(visit.node.id)) continue
            result += StructureLine(visit.node, visit.indent + if (visit.isRoot) "" else if (visit.last) "└── " else "├── ")
            val children = grouped[visit.node.id].orEmpty()
            val indent = visit.indent + if (visit.isRoot) "" else if (visit.last) "    " else "│   "
            children.indices.reversed().forEach { index -> stack.addFirst(Visit(children[index], indent, index == children.lastIndex, false)) }
        }
        return result
    }

    fun exportStructures(selected: List<NoteNode>): Map<String, String> =
        topLevel(selected.map { it.id }.toSet(), selected).filter { it.kind == "folder" }
            .associate { it.id to structure(selected, it).joinToString("\n") { line -> line.text } }
}
