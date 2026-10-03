package com.shijiannote.app

import com.shijiannote.app.data.NoteNode
import org.junit.Assert.*
import org.junit.Test
import com.shijiannote.app.data.TodoItem
import java.time.LocalDate
import java.time.ZoneId

class NoteRulesTest {
    @Test fun insertingListRowsPreservesUnchangedTaskIdentity() {
        val a = TodoItem(id = 1, boardId = 1, text = "买牛奶", completed = true, position = 0)
        val b = TodoItem(id = 2, boardId = 1, text = "读书", important = true, position = 1)
        assertEquals(listOf(null, a, b), matchTaskEdits(listOf(a, b), listOf("新事项", "买牛奶", "读书")))
        assertEquals(listOf(b, a), matchTaskEdits(listOf(a, b), listOf("读书", "买牛奶")))
    }
    @Test fun repeatUsesCalendarDaysAcrossDaylightSaving() {
        val zone = ZoneId.of("America/New_York")
        val date = LocalDate.of(2026, 3, 7)
        val morning = date.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val task = TodoItem(boardId = 1, text = "每天", plannedDay = date.atStartOfDay(zone).toInstant().toEpochMilli(), reminderAt = morning, repeatDays = 1)
        val next = nextRepeatedTask(task, date, zone)
        assertEquals(date.plusDays(1).atTime(9, 0).atZone(zone).toInstant().toEpochMilli(), next.reminderAt)
        assertEquals(23 * 3_600_000L, next.reminderAt!! - morning)
        assertFalse(next.repeatSpawned)
    }
    @Test fun selectedFormattingTracksInsertDeleteAndPartialRemoval() {
        val bold = NoteBlock(text = "abcdef").toggleMark(1, 5, "b")
        assertEquals(listOf(TextMark(1, 5, "b")), bold.textMarks())
        assertEquals(listOf(TextMark(2, 6, "b")), bold.editText("Xabcdef").textMarks())
        assertEquals(listOf(TextMark(1, 3, "b")), bold.editText("abef").textMarks())
        assertEquals(listOf(TextMark(1, 2, "b"), TextMark(4, 5, "b")), bold.toggleMark(2, 4, "b").textMarks())
    }
    private fun folder(id: String, parent: String? = null) = NoteNode(id = id, kind = "folder", parentId = parent, title = id)
    @Test fun deepTreesAreIterativeAndMoveCannotIntroduceCycles() {
        val chain = (0..1200).map { folder("f$it", if (it == 0) null else "f${it - 1}") }
        val memory = NoteNode(id = "leaf", parentId = "f1200", text = "正文")
        val all = chain + memory
        assertEquals(1202, TreeRules.descendants("f0", all).size)
        assertEquals(1201, TreeRules.ancestors(memory, all).size)
        assertFalse(TreeRules.canMove(chain[0], chain[1000], all))
        assertFalse(TreeRules.canMove(chain[10], chain[10], all))
        assertTrue(TreeRules.canMove(chain[100], chain[0], all))
        val rows = treeRows(all, null, chain.map { it.id }.toSet(), true, "manual")
        assertEquals(1202, rows.size)
        assertEquals(1201, rows.last().depth)
    }
    @Test fun mixedSiblingsKeepUserOrderAndPinnedNodesLead() {
        val a = folder("folder").copy(position = 1)
        val b = NoteNode(id = "memory", text = "无标题正文", position = 0)
        val c = NoteNode(id = "pinned", text = "置顶", pinned = true, position = 10)
        assertEquals(listOf("pinned", "memory", "folder"), treeRows(listOf(a, b, c), null, emptySet(), false, "manual").map { it.node.id })
        assertEquals("无标题正文", b.displayTitle())
    }
    @Test fun collapsedHeadingsStopAtSameOrHigherHeadingAndSearchReveals() {
        val blocks = listOf(NoteBlock(id = "h1", type = "heading1", collapsed = true), NoteBlock(id = "t1", text = "藏起来"), NoteBlock(id = "h2", type = "heading2"), NoteBlock(id = "t2"), NoteBlock(id = "next", type = "heading1"), NoteBlock(id = "t3"))
        assertEquals(setOf("h1", "next", "t3"), visibleBlockIds(blocks, false))
        assertEquals(blocks.map { it.id }.toSet(), visibleBlockIds(blocks, true))
    }
    @Test fun nearestImageSettingOverridesAndReferenceChoiceIsSeparate() {
        val root = folder("root").copy(imageDisplay = "card", imageStorage = "reference")
        val child = folder("child", "root").copy(imageDisplay = "preview")
        val note = NoteNode(id = "note", parentId = "child")
        assertEquals("preview", TreeRules.imagePreference(note, listOf(root, child, note), false, "card"))
        assertEquals("reference", TreeRules.imagePreference(note, listOf(root, child, note), true, "copy"))
        assertEquals("copy", TreeRules.imagePreference(note.copy(imageStorage = "copy"), listOf(root, child), true, "reference"))
    }
    @Test fun damagedCyclesDoNotHang() {
        val all = listOf(folder("a", "b"), folder("b", "a"))
        assertEquals(setOf("a", "b"), TreeRules.descendants("a", all))
        assertEquals(1, TreeRules.ancestors(all.first(), all).size)
    }
    @Test fun exportScopeIncludesOnlySelectedSubtreeAndHandlesLongNames() {
        val root = folder("root")
        val child = folder("child", "root")
        val leaf = NoteNode(id = "leaf", parentId = "child", title = "同名")
        val outside = NoteNode(id = "outside", title = "同名")
        val selected = ExportEngine.scope(setOf("child"), listOf(root, child, leaf, outside))
        assertEquals(setOf("child", "leaf"), selected.map { it.id }.toSet())
        assertEquals(2, ExportEngine.paths(selected).values.distinct().size)
        assertFalse(ExportEngine.clean("../非法/名称:").contains('/'))
        val deep = (0..20).map { folder("n$it", if (it == 0) null else "n${it - 1}").copy(title = "很长的分类名称".repeat(5)) }
        assertTrue(ExportEngine.paths(deep).values.all { it.length < 250 })
    }
}
