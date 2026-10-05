package com.shijiannote.app

import com.shijiannote.app.data.NoteNode
import org.junit.Assert.*
import org.junit.Test
import com.shijiannote.app.data.TodoItem
import com.shijiannote.app.data.TodoBoard
import java.time.LocalDate
import java.time.ZoneId

class NoteRulesTest {
    @Test fun forcedSettingsOverrideNestedRulesAndRestoreOriginalConfigurationWhenReleased() {
        val root = folder("root").copy(forceChildren = true, imageStorage = "copy", imageDisplay = "card")
        val child = folder("child", root.id).copy(forceChildren = true, imageStorage = "reference", imageDisplay = "preview")
        val note = NoteNode(id = "note", parentId = child.id, imageDisplay = "preview", imageStorage = "reference")
        val all = listOf(root, child, note)
        assertEquals(root, TreeRules.forcedBy(child, all))
        assertEquals(root, TreeRules.forcedBy(note, all))
        assertEquals("copy", TreeRules.imagePreference(note, all, true, "reference"))
        assertEquals("card", TreeRules.imageDisplay(NoteBlock(type = "image", display = "preview"), note, all, "preview"))
        val released = listOf(root.copy(forceChildren = false), child, note)
        assertEquals(child, TreeRules.forcedBy(note, released))
        assertEquals("reference", TreeRules.imagePreference(note, released, true, "copy"))
        assertEquals("preview", TreeRules.imagePreference(note, released, false, "card"))
    }

    @Test fun deselectingAChildCannotReincludeItThroughExportAncestorExpansion() {
        val root = folder("root")
        val branch = folder("branch", root.id)
        val leaf = NoteNode(id = "leaf", parentId = branch.id)
        val sibling = NoteNode(id = "sibling", parentId = root.id)
        val all = listOf(root, branch, leaf, sibling)
        val selected = TreeRules.toggleSelection(emptySet(), root, all)
        assertEquals(all.map { it.id }.toSet(), selected)
        val reduced = TreeRules.toggleSelection(selected, leaf, all)
        assertEquals(setOf("sibling"), ExportEngine.scope(reduced, all).map { it.id }.toSet())
        assertEquals(listOf(root), TreeRules.topLevel(selected, all))
    }

    @Test fun structureTreesRespectExportRootsAndUnicodeCharacterLimit() {
        val parent = folder("outside")
        val a = folder("a", parent.id).copy(title = "你好啊哈哈哈还有")
        val b = folder("b", parent.id)
        val leaf = NoteNode(id = "leaf", parentId = a.id, title = "😀😀😀😀😀😀第七")
        val standalone = NoteNode(id = "standalone", title = "独立")
        val selected = listOf(a, b, leaf, standalone)
        val trees = TreeRules.exportStructures(selected)
        assertEquals(setOf("a", "b"), trees.keys)
        assertTrue(trees.getValue("a").startsWith("《你好啊哈哈哈...》"))
        assertTrue(trees.getValue("a").contains("└── 😀😀😀😀😀😀..."))
        assertFalse(trees.values.any { it.contains("outside") || it.contains("独立") })
        assertEquals(emptyMap<String, String>(), TreeRules.exportStructures(listOf(standalone)))
    }

    @Test fun structureBranchesKeepAncestorLinesUntilTheLastSibling() {
        val root = folder("root")
        val branch = folder("branch", root.id)
        val leaf = NoteNode(id = "leaf", parentId = branch.id)
        val last = folder("last", root.id).copy(position = 1)
        val lastLeaf = NoteNode(id = "last-leaf", parentId = last.id)
        val prefixes = TreeRules.structure(listOf(root, branch, leaf, last, lastLeaf)).associate { it.node.id to it.prefix }
        assertEquals("", prefixes[root.id])
        assertEquals("├── ", prefixes[branch.id])
        assertEquals("│   └── ", prefixes[leaf.id])
        assertEquals("└── ", prefixes[last.id])
        assertEquals("    └── ", prefixes[lastLeaf.id])
    }

    @Test fun dailyTasksBecomeTodayAtMidnightAndOverdueAtTheirDeadline() {
        val zone = ZoneId.of("Asia/Shanghai")
        val date = LocalDate.of(2026, 10, 4)
        fun at(day: LocalDate, hour: Int, minute: Int = 0) = day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
        val planned = at(date.plusDays(1), 0)
        val item = TodoItem(boardId = 1, text = "明天", plannedDay = planned, dueAt = at(date.plusDays(1), 16))
        val board = TodoBoard(summary = "计划", timeMode = "INDEPENDENT")
        assertTrue(taskInView(item, board, "tomorrow", at(date, 12), zone))
        assertFalse(taskInView(item, board, "today", at(date, 12), zone))
        assertTrue(taskInView(item, board, "today", at(date.plusDays(1), 8), zone))
        assertFalse(taskInView(item, board, "today", at(date.plusDays(1), 17), zone))
        assertTrue(taskInView(item, board, "overdue", at(date.plusDays(1), 17), zone))
        assertFalse(taskInView(item.copy(completed = true), board, "today", at(date.plusDays(1), 8), zone))
        assertTrue(taskInView(item.copy(completed = true), board, "today", at(date.plusDays(1), 8), zone, includeCompleted = true))
    }

    @Test fun dailyDeadlineUsesLocalMidnightEvenAcrossDaylightSaving() {
        val zone = ZoneId.of("America/New_York")
        val start = LocalDate.of(2026, 3, 8).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(23 * 3_600_000L - 1, planDeadline(start, zone) - start)
    }

    @Test fun reminderDisplayIsTheActualSingleTimeOrRepeatBase() {
        val due = 200_000_000L
        assertEquals(123L, reminderDisplayTime(BoardReminderDraft(repeatRule = "DAILY", baseAt = 123), due))
        assertEquals(123L, reminderDisplayTime(BoardReminderDraft(singleAt = 123), due))
    }
    @Test fun insertingListRowsPreservesUnchangedTaskIdentity() {
        val a = TodoItem(id = 1, boardId = 1, text = "买牛奶", completed = true, position = 0)
        val b = TodoItem(id = 2, boardId = 1, text = "读书", important = true, position = 1)
        assertEquals(listOf(null, a, b), matchTaskEdits(listOf(a, b), listOf("新事项", "买牛奶", "读书")))
        assertEquals(listOf(b, a), matchTaskEdits(listOf(a, b), listOf("读书", "买牛奶")))
    }
    @Test fun selectedFormattingTracksInsertDeleteAndPartialRemoval() {
        val bold = NoteBlock(text = "abcdef").toggleMark(1, 5, "b")
        assertEquals(listOf(TextMark(1, 5, "b")), bold.textMarks())
        assertEquals(listOf(TextMark(2, 6, "b")), bold.editText("Xabcdef").textMarks())
        assertEquals(listOf(TextMark(1, 3, "b")), bold.editText("abef").textMarks())
        assertEquals(listOf(TextMark(1, 2, "b"), TextMark(4, 5, "b")), bold.toggleMark(2, 4, "b").textMarks())
    }
    @Test fun typingStylesAndHighlightDoNotChangeSurroundingText() {
        val original = NoteBlock(text = "abcd", marks = "0:4:b")
        val edited = original.editText("abXYcd", setOf("h", "i"))
        assertEquals(setOf(TextMark(0, 2, "b"), TextMark(4, 6, "b"), TextMark(2, 4, "h"), TextMark(2, 4, "i")), edited.textMarks().toSet())
        assertTrue(edited.markdownText { it }.contains("<mark>"))
        val highlighted = NoteBlock(text = "高亮文字").toggleMark(1, 3, "h")
        assertEquals(listOf(TextMark(1, 3, "h")), highlighted.textMarks())
        assertFalse(highlighted.italic)
        assertEquals("highlight", NoteBlock().toggleMark(0, 0, "h").display)
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
