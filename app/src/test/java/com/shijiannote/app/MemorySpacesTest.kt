package com.shijiannote.app

import com.shijiannote.app.data.NoteNode
import org.junit.Assert.*
import org.junit.Test

class MemorySpacesTest {
    @Test fun oldBranchesAndDeletedBranchesGoToWorkWithoutChangingContent() {
        val folder = NoteNode(id = "old", kind = "folder", title = "旧分类", forceChildren = true)
        val leaf = NoteNode(id = "leaf", parentId = folder.id, text = "原文", document = "原文档", imageStorage = "reference")
        val deleted = NoteNode(id = "deleted", kind = "folder", deletedAt = 123)
        val deletedLeaf = leaf.copy(id = "deleted-leaf", parentId = deleted.id, deletedAt = 123)
        val diary = NoteNode(id = "diary", kind = "diary", title = "", text = "日记正文")
        val next = MemorySpaces.normalize(listOf(folder, leaf, deleted, deletedLeaf, diary))
        fun get(id: String) = next.single { it.id == id }
        assertEquals(MemorySpaces.WORK_ID, get(folder.id).parentId)
        assertEquals(leaf, get(leaf.id))
        assertEquals(MemorySpaces.WORK_ID, get(deleted.id).parentId)
        assertEquals(deleted.id, get(deletedLeaf.id).parentId)
        assertEquals(get(deleted.id).deleteGroup, get(deletedLeaf.id).deleteGroup)
        assertEquals(123L, get(deletedLeaf.id).deletedAt)
        assertEquals(diary, get(diary.id))
        assertEquals("未命名", diary.displayTitle())
        assertEquals(next, MemorySpaces.normalize(next))
    }

    @Test fun existingLifeAndItsDeletedChildrenStayInLife() {
        val root = NoteNode(id = MemorySpaces.LIFE_ID, kind = "folder", title = "生活", imageDisplay = "card")
        val folder = NoteNode(id = "life", parentId = root.id, kind = "folder")
        val leaf = NoteNode(id = "life-trash", parentId = folder.id, deletedAt = 1, deleteGroup = "group")
        val next = MemorySpaces.normalize(listOf(root, folder, leaf))
        assertEquals(root.id, MemorySpaces.rootId(leaf, next))
        assertEquals("card", next.single { it.id == root.id }.imageDisplay)
        assertEquals(leaf, next.single { it.id == leaf.id })
    }

    @Test fun orphanAndCyclicImportsRepairOnlyBrokenEdges() {
        val orphan = NoteNode(id = "orphan", parentId = "missing", text = "保留")
        val a = NoteNode(id = "a", kind = "folder", parentId = "b")
        val b = NoteNode(id = "b", kind = "folder", parentId = "a")
        val next = MemorySpaces.normalize(listOf(orphan, a, b))
        next.filterNot { it.kind == "diary" || MemorySpaces.isRoot(it.id) }.forEach {
            assertEquals(MemorySpaces.WORK_ID, MemorySpaces.rootId(it, next))
            assertFalse(it.parentId in TreeRules.descendants(it.id, next))
        }
        assertEquals("保留", next.single { it.id == orphan.id }.text)
    }

    @Test fun fixedEntrancesAreExcludedFromExportButTheirContentsRemainSelectable() {
        val note = NoteNode(id = "note", title = "记忆")
        val all = MemorySpaces.normalize(listOf(note))
        assertEquals(listOf(note.id), ExportEngine.scope(setOf(MemorySpaces.WORK_ID), all).map { it.id })
        assertTrue(TreeRules.exportStructures(ExportEngine.scope(setOf(note.id), all)).isEmpty())
    }
}
