package com.shijiannote.app

import com.shijiannote.app.data.NoteNode
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class NoteBlock(
    val id: String = UUID.randomUUID().toString(),
    val type: String = "text", // text, heading1, heading2, bullet, check, quote, code, image, audio, file, link
    val text: String = "",
    val bold: Boolean = false,
    val italic: Boolean = false,
    val checked: Boolean = false,
    val collapsed: Boolean = false,
    val uri: String = "",
    val owned: Boolean = false,
    val mime: String = "",
    val bytes: Long = 0,
    val duration: Long = 0,
    val target: String = "",
    val display: String = "inherit",
    val marks: String = ""
)

fun jsonObject(value: Any): JSONObject = JSONObject().apply {
    value.javaClass.declaredFields.filter { !it.isSynthetic && !java.lang.reflect.Modifier.isStatic(it.modifiers) }.forEach {
        it.isAccessible = true
        put(it.name, it.get(value) ?: JSONObject.NULL)
    }
}
fun JSONObject.nullString(key: String): String? = if (isNull(key) || !has(key)) null else getString(key)
fun JSONObject.nullLong(key: String): Long? = if (isNull(key) || !has(key)) null else getLong(key)
fun encodeBlocks(blocks: List<NoteBlock>): String = JSONArray().apply { blocks.forEach { put(jsonObject(it)) } }.toString()
fun decodeBlocks(document: String, fallback: String = ""): List<NoteBlock> {
    if (document.isBlank()) return listOf(NoteBlock(text = fallback))
    val array = JSONArray(document)
    return (0 until array.length()).map { i -> array.getJSONObject(i).let { j ->
        NoteBlock(j.getString("id"), j.optString("type", "text"), j.optString("text"), j.optBoolean("bold"), j.optBoolean("italic"), j.optBoolean("checked"), j.optBoolean("collapsed"), j.optString("uri"), j.optBoolean("owned"), j.optString("mime"), j.optLong("bytes"), j.optLong("duration"), j.optString("target"), j.optString("display", "inherit"), j.optString("marks"))
    } }.ifEmpty { listOf(NoteBlock()) }
}
fun decodeNode(j: JSONObject): NoteNode = NoteNode(
    id = j.getString("id"), kind = j.getString("kind"), parentId = j.nullString("parentId"),
    title = j.optString("title"), text = j.optString("text"), document = j.optString("document"), day = j.nullLong("day"),
    diaryRoad = j.optString("diaryRoad"), diaryRoadTheme = j.optString("diaryRoadTheme", "forest"), diaryRoadBackground = j.optString("diaryRoadBackground"),
    diaryRoadEnabled = if (j.has("diaryRoadEnabled")) j.optBoolean("diaryRoadEnabled") else j.optString("diaryRoad").let { runCatching { JSONArray(it).length() > 0 }.getOrDefault(false) },
    diaryRoadLayout = j.optString("diaryRoadLayout", "alternate"), diaryInbox = j.optString("diaryInbox"), diaryOccurredAt = j.nullLong("diaryOccurredAt"), diaryTrashExpiresAt = j.nullLong("diaryTrashExpiresAt"),
    position = j.optInt("position"), pinned = j.optBoolean("pinned"), favorite = j.optBoolean("favorite"), tags = j.optString("tags"), mood = j.optString("mood"),
    imageDisplay = j.optString("imageDisplay", "inherit"), imageStorage = j.optString("imageStorage", "inherit"),
    forceChildren = j.optBoolean("forceChildren"),
    deletedAt = j.nullLong("deletedAt"), deleteGroup = j.nullString("deleteGroup"), createdAt = j.getLong("createdAt"), updatedAt = j.getLong("updatedAt")
)
fun NoteNode.blocks(): List<NoteBlock> = decodeBlocks(document, text)

fun NoteNode.displayTitle(): String = title.takeIf { it.isNotBlank() } ?: if (kind == "diary") "未命名" else
    text.lineSequence().firstOrNull { it.isNotBlank() }?.take(80) ?: if (kind == "folder") "未命名分类" else "未命名记忆"
fun blockPlainText(blocks: List<NoteBlock>): String = blocks.joinToString("\n") { it.text }
fun visibleBlockIds(blocks: List<NoteBlock>, reveal: Boolean): Set<String> {
    val result = mutableSetOf<String>()
    var hidingLevel = 0
    blocks.forEach { block ->
        val level = when (block.type) { "heading1" -> 1; "heading2" -> 2; else -> 0 }
        if (level != 0 && hidingLevel != 0 && level <= hidingLevel) hidingLevel = 0
        if (hidingLevel == 0 || reveal) result += block.id
        if (!reveal && hidingLevel == 0 && level != 0 && block.collapsed) hidingLevel = level
    }
    return result
}
