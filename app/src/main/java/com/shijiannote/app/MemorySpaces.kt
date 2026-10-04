package com.shijiannote.app

import com.shijiannote.app.data.NoteNode

/** Stable folders keep old documents, versions and image inheritance intact. */
object MemorySpaces {
    const val WORK_ID = "system-memory-work"
    const val LIFE_ID = "system-memory-life"
    fun isRoot(id: String?): Boolean = id == WORK_ID || id == LIFE_ID

    fun rootId(node: NoteNode, nodes: List<NoteNode>): String {
        val byId = nodes.associateBy { it.id }
        val seen = mutableSetOf<String>()
        var current: NoteNode? = node
        while (current != null && seen.add(current.id)) {
            if (isRoot(current.id)) return current.id
            current = current.parentId?.let(byId::get)
        }
        return WORK_ID
    }

    /** Idempotent upgrade also repairs old backups and deleted/orphaned branches. */
    fun normalize(nodes: List<NoteNode>): List<NoteNode> {
        val result = nodes.associateBy { it.id }.toMutableMap()
        listOf(WORK_ID to "工作", LIFE_ID to "生活").forEachIndexed { index, (id, title) ->
            val old = result[id]
            result[id] = (old ?: NoteNode(id = id, createdAt = 0, updatedAt = 0)).copy(
                kind = "folder", title = title, parentId = null, deletedAt = null, deleteGroup = null,
                pinned = false, position = index
            )
        }
        // Walk each branch rather than flattening it. A broken edge goes to Work.
        for (node in nodes.filter { it.kind != "diary" && !isRoot(it.id) }) {
            val seen = mutableSetOf<String>()
            var current = result.getValue(node.id)
            while (!isRoot(current.id)) {
                seen.add(current.id)
                val parent = current.parentId?.let(result::get)
                if (parent == null || parent.kind != "folder" || parent.id in seen) {
                    result[current.id] = current.copy(parentId = WORK_ID)
                    break
                }
                current = parent
            }
        }
        return result.values.map { node ->
            if (node.deletedAt != null && node.deleteGroup == null) {
                var root = node
                val seen = mutableSetOf(node.id)
                while (true) {
                    val parent = root.parentId?.let(result::get) ?: break
                    if (parent.deletedAt == null || parent.deleteGroup != null || !seen.add(parent.id) ||
                        (parent.kind == "diary") != (node.kind == "diary")) break
                    root = parent
                }
                node.copy(deleteGroup = "legacy-${root.id}")
            } else node
        }
    }
}
