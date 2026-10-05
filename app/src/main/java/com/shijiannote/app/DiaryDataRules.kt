package com.shijiannote.app

import com.shijiannote.app.data.NoteNode
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** createdAt is draft identity time; only an explicit send fixes sentAt. */
data class DiaryMoment(
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val title: String = "",
    val text: String = "",
    val document: String = "",
    val occurredAt: Long? = null,
    val sentAt: Long? = null
) {
    val sendTime: Long get() = sentAt ?: createdAt
    val occurrenceTime: Long get() = occurredAt ?: sentAt ?: createdAt
}

data class DiaryInboxItem(
    val id: String = UUID.randomUUID().toString(),
    val status: String = "draft",
    val originalStatus: String = "draft",
    val moment: DiaryMoment? = null,
    val road: String = "",
    val roadTheme: String = "forest",
    val roadBackground: String = "",
    val roadLayout: String = "alternate",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null,
    val expiresAt: Long? = null
)

fun decodeDiaryMoment(j: JSONObject): DiaryMoment {
    val created = j.getLong("createdAt")
    return DiaryMoment(j.getString("id"), created, j.optString("title"), j.optString("text"), j.optString("document"),
        if (j.has("occurredAt")) j.nullLong("occurredAt") else created,
        if (j.has("sentAt")) j.nullLong("sentAt") else created)
}
fun encodeDiaryMoments(moments: List<DiaryMoment>): String = if (moments.isEmpty()) "" else
    JSONArray().apply { moments.forEach { put(jsonObject(it)) } }.toString()
fun decodeDiaryMoments(road: String): List<DiaryMoment> = if (road.isBlank()) emptyList() else JSONArray(road).let { array ->
    (0 until array.length()).map { decodeDiaryMoment(array.getJSONObject(it)) }
}
fun sortedDiaryMoments(moments: List<DiaryMoment>, order: String = "occurred"): List<DiaryMoment> = moments.sortedWith(
    if (order == "sent") compareBy<DiaryMoment> { it.sendTime }.thenBy { it.id }
    else compareBy<DiaryMoment> { it.occurrenceTime }.thenBy { it.sendTime }.thenBy { it.id }
)
fun NoteNode.diaryMoments(): List<DiaryMoment> = sortedDiaryMoments(decodeDiaryMoments(diaryRoad))
fun encodeDiaryInbox(items: List<DiaryInboxItem>): String = if (items.isEmpty()) "" else JSONArray().apply {
    items.forEach { item -> put(JSONObject().put("id", item.id).put("status", item.status).put("originalStatus", item.originalStatus)
        .put("moment", item.moment?.let(::jsonObject) ?: JSONObject.NULL).put("road", item.road).put("roadTheme", item.roadTheme)
        .put("roadBackground", item.roadBackground).put("roadLayout", item.roadLayout).put("createdAt", item.createdAt)
        .put("updatedAt", item.updatedAt).put("deletedAt", item.deletedAt ?: JSONObject.NULL).put("expiresAt", item.expiresAt ?: JSONObject.NULL)) }
}.toString()
fun NoteNode.diaryInboxItems(): List<DiaryInboxItem> = if (diaryInbox.isBlank()) emptyList() else JSONArray(diaryInbox).let { array ->
    (0 until array.length()).map { index -> array.getJSONObject(index).let { j ->
        DiaryInboxItem(j.getString("id"), j.optString("status", "draft"), j.optString("originalStatus", "draft"),
            j.optJSONObject("moment")?.let(::decodeDiaryMoment), j.optString("road"), j.optString("roadTheme", "forest"),
            j.optString("roadBackground"), j.optString("roadLayout", "alternate"), j.getLong("createdAt"), j.getLong("updatedAt"),
            j.nullLong("deletedAt"), j.nullLong("expiresAt"))
    } }.sortedWith(compareByDescending<DiaryInboxItem> { it.updatedAt }.thenBy { it.id })
}
fun DiaryMoment.asNote(parent: NoteNode): NoteNode = NoteNode(
    id = "moment-$id", kind = "diary_moment", parentId = parent.id, day = parent.day,
    title = title, text = text, document = document, createdAt = createdAt, diaryOccurredAt = occurredAt,
    imageDisplay = parent.imageDisplay, imageStorage = parent.imageStorage
)
fun DiaryInboxItem.asNote(parent: NoteNode): NoteNode? = moment?.asNote(parent)
fun DiaryInboxItem.roadNote(parent: NoteNode): NoteNode = parent.copy(diaryRoad = road, diaryRoadTheme = roadTheme,
    diaryRoadBackground = roadBackground, diaryRoadLayout = roadLayout, diaryRoadEnabled = true, diaryInbox = "")
fun NoteNode.asDiaryMoment(): DiaryMoment = DiaryMoment(id.removePrefix("moment-"), createdAt, title, text, document, diaryOccurredAt)
fun DiaryMoment.hasContent(): Boolean = title.isNotBlank() || decodeBlocks(document, text).any { it.text.isNotBlank() || it.uri.isNotBlank() || it.target.isNotBlank() }
fun NoteNode.hasNoteContent(): Boolean = title.isNotBlank() || tags.isNotBlank() || blocks().any { it.text.isNotBlank() || it.uri.isNotBlank() || it.target.isNotBlank() }
fun NoteNode.hasDiaryContent(): Boolean = hasNoteContent() || diaryMoments().any { it.hasContent() }
fun NoteNode.withDiaryMoment(note: NoteNode): NoteNode {
    val momentId = note.id.removePrefix("moment-")
    val current = diaryMoments()
    val previous = current.firstOrNull { it.id == momentId }
    val changed = note.asDiaryMoment().copy(createdAt = previous?.createdAt ?: note.createdAt,
        sentAt = previous?.sentAt, occurredAt = note.diaryOccurredAt ?: previous?.sentAt)
    return copy(diaryRoad = encodeDiaryMoments(sortedDiaryMoments(current.filterNot { it.id == momentId } + if (note.hasNoteContent() || previous != null) listOf(changed) else emptyList())))
}
private fun backgroundBlock(uri: String): List<NoteBlock> = if (uri.isBlank()) emptyList() else
    listOf(NoteBlock(id = "diary-background", type = "image", text = "小路背景", uri = uri, owned = uri.startsWith("file:")))
fun NoteNode.materialBlocks(): List<NoteBlock> = blocks() + diaryMoments().flatMap { decodeBlocks(it.document, it.text) } + backgroundBlock(diaryRoadBackground) +
    diaryInboxItems().flatMap { item -> (item.moment?.let { decodeBlocks(it.document, it.text) } ?: emptyList()) +
        decodeDiaryMoments(item.road).flatMap { decodeBlocks(it.document, it.text) } + backgroundBlock(item.roadBackground) }
fun NoteNode.exportBlocks(): List<NoteBlock> {
    val moments = diaryMoments()
    if (moments.isEmpty()) return blocks()
    return listOf(NoteBlock(type = "heading1", text = "小路片段")) + moments.flatMap { moment ->
        listOf(NoteBlock(type = "heading2", text = "发生：${dateText(moment.occurrenceTime, true)} · 发送：${dateText(moment.sendTime, true)}" + if (moment.title.isBlank()) "" else " · ${moment.title}")) + decodeBlocks(moment.document, moment.text)
    } + if (hasNoteContent()) listOf(NoteBlock(type = "heading1", text = "当日结语")) + blocks() else emptyList()
}
fun NoteNode.remapMaterials(restored: Map<String, String>): NoteNode {
    fun remap(blocks: List<NoteBlock>) = blocks.map { b -> restored[b.uri]?.let { b.copy(uri = it, owned = true) } ?: b }
    fun remapMoment(moment: DiaryMoment): DiaryMoment { val next = remap(decodeBlocks(moment.document, moment.text)); return moment.copy(document = encodeBlocks(next), text = blockPlainText(next)) }
    val summary = remap(blocks())
    val inbox = diaryInboxItems().map { item -> item.copy(moment = item.moment?.let(::remapMoment),
        road = encodeDiaryMoments(decodeDiaryMoments(item.road).map(::remapMoment)), roadBackground = restored[item.roadBackground] ?: item.roadBackground) }
    return copy(document = encodeBlocks(summary), text = blockPlainText(summary), diaryRoad = encodeDiaryMoments(diaryMoments().map(::remapMoment)),
        diaryRoadBackground = restored[diaryRoadBackground] ?: diaryRoadBackground, diaryInbox = encodeDiaryInbox(inbox))
}
fun NoteNode.flattenDiaryRoad(): NoteNode {
    if (diaryRoad.isBlank() && diaryRoadBackground.isBlank()) return this
    val flattened = exportBlocks() + backgroundBlock(diaryRoadBackground)
    return copy(document = encodeBlocks(flattened), text = blockPlainText(flattened), diaryRoad = "", diaryRoadBackground = "", diaryRoadTheme = "forest", diaryRoadLayout = "alternate", diaryRoadEnabled = false)
}
/** Attach a surviving service recording at its original location, including withdrawn or archived moments. */
fun NoteNode.withDiaryRecording(momentId: String, audio: List<NoteBlock>): NoteNode {
    fun attach(moment: DiaryMoment): DiaryMoment {
        if (moment.id != momentId) return moment
        val blocks = decodeBlocks(moment.document, moment.text).toMutableList()
        audio.filter { new -> blocks.none { it.id == new.id } }.forEach { blocks += it }
        return moment.copy(document = encodeBlocks(blocks), text = blockPlainText(blocks))
    }
    val inbox = diaryInboxItems().map { item -> item.copy(moment = item.moment?.let(::attach),
        road = encodeDiaryMoments(decodeDiaryMoments(item.road).map(::attach))) }
    return copy(diaryRoad = encodeDiaryMoments(diaryMoments().map(::attach)), diaryInbox = encodeDiaryInbox(inbox))
}
/** Imported recovery records can share identities with newer drafts; retain differing payloads independently. */
fun mergeDiaryInboxPreservingConflicts(current: List<DiaryInboxItem>, incoming: List<DiaryInboxItem>): List<DiaryInboxItem> {
    val result = current.toMutableList()
    incoming.forEach { item ->
        val collision = result.firstOrNull { it.id == item.id || it.moment != null && it.moment.id == item.moment?.id }
        if (collision == null) result += item
        else {
            val same = item.copy(id = collision.id, createdAt = collision.createdAt, updatedAt = collision.updatedAt,
                deletedAt = collision.deletedAt, expiresAt = collision.expiresAt) == collision
            if (!same) {
                val newId = UUID.randomUUID().toString()
                result += item.copy(id = newId, moment = item.moment?.copy(id = newId),
                    road = encodeDiaryMoments(decodeDiaryMoments(item.road).map { it.copy(id = UUID.randomUUID().toString()) }))
            }
        }
    }
    return result
}
