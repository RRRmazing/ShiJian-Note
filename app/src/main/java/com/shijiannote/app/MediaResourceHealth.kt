package com.shijiannote.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import com.shijiannote.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.*
import java.io.File
import java.util.concurrent.TimeUnit

data class MediaLocation(val nodeId: String, val momentId: String?, val inboxId: String?, val blockId: String, val ordinal: Int, val path: String)
data class MediaReference(val block: NoteBlock, val locations: List<MediaLocation>)
data class MediaIssue(val uri: String, val type: String, val name: String, val reason: String, val firstFound: Long, val lastChecked: Long, val locations: List<MediaLocation>)

/** The index includes recoverable diary drafts, but never counts history/trash as current problems. */
fun mediaReferenceIndex(nodes: List<NoteNode>): List<MediaReference> {
    val references = linkedMapOf<String, Pair<NoteBlock, MutableList<MediaLocation>>>()
    nodes.filter { it.deletedAt == null && it.kind != "folder" }.forEach { node ->
        val path = if (node.kind == "diary") "日记 / ${dateText(node.day ?: node.createdAt)}" else "记忆 / ${TreeRules.path(node, nodes)}"
        fun add(blocks: List<NoteBlock>, moment: String? = null, inbox: String? = null, suffix: String = "正文") {
            blocks.forEachIndexed { i, b -> if (b.type in setOf("image", "video", "audio", "file") && b.uri.isNotBlank()) {
                val item = references.getOrPut(b.uri) { b to mutableListOf() }
                val location = MediaLocation(node.id, moment, inbox, b.id, i + 1, "$path / $suffix / 第${i + 1}块")
                if (location !in item.second) item.second.add(location)
            } }
        }
        add(node.blocks())
        node.diaryMoments().forEachIndexed { i, m -> add(m.blocks(), m.id, suffix = "小路 · 第${i + 1}片段（${diaryTimestamp(m.occurrenceTime)}）") }
        node.diaryInboxItems().forEach { item ->
            val label = "收纳箱 · " + when (item.status) { "draft" -> "草稿"; "retracted" -> "撤回"; "deleted" -> "已删除片段"; else -> "整条小路" }
            item.moment?.let { add(it.blocks(), it.id, item.id, label) }
            if (item.road.isNotBlank()) node.copy(diaryRoad = item.road).diaryMoments().forEachIndexed { i, m -> add(m.blocks(), m.id, item.id, "$label · 第${i + 1}片段") }
        }
    }
    return references.values.map { MediaReference(it.first, it.second.toList()) }
}

object MediaResourceHealth {
    const val ACTION_OPEN = "com.shijiannote.app.MEDIA_PROBLEMS"
    private const val WORK = "media_resource_health"
    val issues = MutableStateFlow<List<MediaIssue>>(emptyList())
    val scanning = MutableStateFlow(false)
    private val mutex = Mutex()
    private var loadedPackage: String? = null
    fun initialize(context: Context) {
        if (loadedPackage != context.packageName) {
            issues.value = readIssues(context); loadedPackage = context.packageName
            schedule(context)
        }
    }
    fun interval(context: Context) = appPreferences(context).getInt("mediaCheckDays", 1)
    fun setInterval(context: Context, days: Int) { require(days in listOf(0, 1, 3, 7)); appPreferences(context).edit().putInt("mediaCheckDays", days).apply(); schedule(context) }
    fun workspaceRestored(context: Context) {
        context.getSharedPreferences("media_health", Context.MODE_PRIVATE).edit().remove("pending").remove("lastScan").apply()
        schedule(context)
        WorkManager.getInstance(context).enqueueUniqueWork("media_resource_refresh", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<MediaHealthWorker>().setInputData(workDataOf("force" to true)).build())
    }
    private fun schedule(context: Context) {
        val manager = WorkManager.getInstance(context)
        val days = interval(context)
        if (days == 0) manager.cancelUniqueWork(WORK)
        else manager.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<MediaHealthWorker>(days.toLong(), TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()).build())
    }
    suspend fun scan(context: Context, model: WorkspaceModel? = null, force: Boolean = false) = mutex.withLock {
        initialize(context)
        val prefs = context.getSharedPreferences("media_health", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && (interval(context) == 0 || now - prefs.getLong("lastScan", 0) < interval(context) * 86_400_000L)) return@withLock
        scanning.value = true
        try {
            model?.flushAll()
            val nodes = withContext(Dispatchers.IO) {
                val saved = AppDatabase.get(context).noteDao().nodes().associateBy { it.id }.toMutableMap()
                // Atomic snapshots can be newer than Room after a process restart. Never clear issues on corrupt drafts.
                File(context.filesDir, "drafts").listFiles().orEmpty().filter { it.name.endsWith(".json") || it.name.endsWith(".json.bak") }
                    .map { File(it.path.removeSuffix(".bak")) }.distinct().forEach { f ->
                        if (f.exists() || File(f.path + ".bak").exists()) {
                            val n = decodeNode(JSONObject(android.util.AtomicFile(f).openRead().bufferedReader().use { it.readText() }))
                            if (n.updatedAt >= (saved[n.id]?.updatedAt ?: 0)) saved[n.id] = n
                        }
                    }; saved.values.toList()
            }
            val old = issues.value.associateBy { it.uri }
            val pending = JSONObject(prefs.getString("pending", "{}")!!)
            val nextPending = JSONObject()
            val next = mutableListOf<MediaIssue>()
            for (reference in mediaReferenceIndex(nodes)) {
                currentCoroutineContext().ensureActive()
                val block = reference.block
                val reason = withContext(Dispatchers.IO) { probe(context, block.uri) }
                if (reason == null) continue
                val previous = old[block.uri]
                val transient = reason == "暂时无法访问"
                val since = pending.optLong(block.uri, now)
                if (transient && previous == null && (now - since < 86_400_000L)) { nextPending.put(block.uri, since); continue }
                next.add(MediaIssue(block.uri, block.type, block.text.ifBlank { mediaKindLabel(block.type) },
                    if (transient && previous != null) previous.reason else reason, previous?.firstFound ?: now, now, reference.locations))
            }
            val json = JSONArray().apply { next.forEach { put(issueJson(it)) } }.toString()
            check(prefs.edit().putString("issues", json).putString("pending", nextPending.toString()).putLong("lastScan", now).commit()) { "问题日志保存失败" }
            issues.value = next
            if (next.isEmpty()) context.getSystemService(NotificationManager::class.java).cancel(5401)
            val newIssues = next.filter { it.uri !in old }
            if (newIssues.isNotEmpty()) notify(context, newIssues.size, next.size)
        } finally { scanning.value = false }
    }
    private fun probe(context: Context, value: String): String? {
        val uri = Uri.parse(value)
        if (uri.scheme == "file") return if (uri.path?.let { File(it).isFile && File(it).canRead() && File(it).length() > 0 } == true) null else "原文件无法访问"
        val signal = android.os.CancellationSignal()
        val timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        val timeout = timer.schedule({ signal.cancel() }, 10, TimeUnit.SECONDS)
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r", signal)?.use { descriptor ->
                if (descriptor.declaredLength == 0L) "原文件为空" else null
            } ?: "原文件无法访问"
        } catch (_: SecurityException) { "访问权限失效" }
        catch (_: java.io.FileNotFoundException) {
            // Local media/document providers report a definitive missing resource. Remote providers can be offline.
            if (uri.authority in setOf("media", context.packageName + ".files", "com.android.providers.media.documents", "com.android.externalstorage.documents", "com.android.providers.downloads.documents")) "原文件无法访问" else "暂时无法访问"
        } catch (_: Exception) { "暂时无法访问" }
        finally { timeout.cancel(false); timer.shutdownNow() }
    }
    private fun notify(context: Context, added: Int, total: Int) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("media_health", "素材问题提醒", NotificationManager.IMPORTANCE_DEFAULT))
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val intent = PendingIntent.getActivity(context, 5401, Intent(context, MainActivity::class.java).setAction(ACTION_OPEN)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching { NotificationManagerCompat.from(context).notify(5401, NotificationCompat.Builder(context, "media_health")
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("发现${added}项新的素材问题")
            .setContentText("共有${total}项素材无法访问，点此查看问题日志。近期删除的原件可尝试从回收站恢复。")
            .setContentIntent(intent).setAutoCancel(true).build()) }
    }
    private fun readIssues(context: Context): List<MediaIssue> = runCatching {
        val a = JSONArray(context.getSharedPreferences("media_health", Context.MODE_PRIVATE).getString("issues", "[]"))
        (0 until a.length()).map { i -> val j = a.getJSONObject(i); val locations = j.getJSONArray("locations")
            MediaIssue(j.getString("uri"), j.getString("type"), j.getString("name"), j.getString("reason"), j.getLong("first"), j.getLong("last"),
                (0 until locations.length()).map { n -> val l = locations.getJSONObject(n)
                    MediaLocation(l.getString("node"), l.optString("moment").ifBlank { null }, l.optString("inbox").ifBlank { null }, l.getString("block"), l.getInt("ordinal"), l.getString("path")) })
        }
    }.getOrDefault(emptyList())
    private fun issueJson(issue: MediaIssue) = JSONObject().put("uri", issue.uri).put("type", issue.type).put("name", issue.name)
        .put("reason", issue.reason).put("first", issue.firstFound).put("last", issue.lastChecked).put("locations", JSONArray().apply {
            issue.locations.forEach { l -> put(JSONObject().put("node", l.nodeId).put("moment", l.momentId.orEmpty()).put("inbox", l.inboxId.orEmpty())
                .put("block", l.blockId).put("ordinal", l.ordinal).put("path", l.path)) }
        })
}
class MediaHealthWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try { MediaResourceHealth.scan(applicationContext, force = inputData.getBoolean("force", false)); Result.success() }
        catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { Result.retry() }
}

/** Replace only the selected stable block, including recoverable inbox contents; unrelated revisions stay intact. */
suspend fun rebindMedia(context: Context, model: WorkspaceModel, location: MediaLocation, uri: Uri) {
    model.flushAll()
    val node = model.notes.node(location.nodeId) ?: error("原记录已经不存在")
    val reference = mediaReferenceIndex(listOf(node)).firstOrNull { ref -> ref.locations.any {
        it.nodeId == location.nodeId && it.momentId == location.momentId && it.inboxId == location.inboxId && it.blockId == location.blockId
    } } ?: error("原素材位置已经变化，请重新检查")
    val info = withContext(Dispatchers.IO) { mediaInfo(context, uri) }
    retainMediaAccess(context, uri)
    val block = mediaBlockAtLocation(node, location) ?: error("原素材位置已经变化")
    require(when (block.type) { "image" -> info.mime.startsWith("image/"); "video" -> info.mime.startsWith("video/"); "audio" -> info.mime.startsWith("audio/"); else -> true }) { "新文件类型与原素材不一致" }
    val replacement = block.copy(uri = uri.toString(), owned = false, mime = info.mime, bytes = info.size,
        duration = if (block.type in setOf("video", "audio")) withContext(Dispatchers.IO) { videoMetadata(context, uri).duration } else block.duration)
    model.rebindResource(node, location, block.uri, replacement); model.flush(node.id)
    MediaResourceHealth.scan(context, model, true)
}

internal fun replaceResourceAtLocation(node: NoteNode, location: MediaLocation, replacement: NoteBlock): NoteNode {
    fun replace(blocks: List<NoteBlock>) = blocks.map { b -> if (b.id != location.blockId) b else b.copy(uri = replacement.uri, owned = replacement.owned, mime = replacement.mime, bytes = replacement.bytes, duration = replacement.duration) }
    fun moment(m: DiaryMoment): DiaryMoment { val blocks = replace(m.blocks()); return m.copy(document = encodeBlocks(blocks), text = blockPlainText(blocks)) }
    return when {
        location.inboxId != null -> node.copy(diaryInbox = encodeDiaryInbox(node.diaryInboxItems().map { item ->
            if (item.id != location.inboxId) item else if (item.moment != null) item.copy(moment = moment(item.moment), updatedAt = System.currentTimeMillis())
            else item.copy(road = encodeDiaryMoments(node.copy(diaryRoad = item.road).diaryMoments().map { if (it.id == location.momentId) moment(it) else it }), updatedAt = System.currentTimeMillis())
        }))
        location.momentId != null -> node.copy(diaryRoad = encodeDiaryMoments(node.diaryMoments().map { if (it.id == location.momentId) moment(it) else it }))
        else -> { val blocks = replace(node.blocks()); node.copy(document = encodeBlocks(blocks), text = blockPlainText(blocks)) }
    }
}

internal fun mediaBlockAtLocation(node: NoteNode, location: MediaLocation): NoteBlock? {
    val content = if (location.inboxId != null) {
        val item = node.diaryInboxItems().firstOrNull { it.id == location.inboxId } ?: return null
        item.moment?.blocks() ?: node.copy(diaryRoad = item.road).diaryMoments().firstOrNull { it.id == location.momentId }?.blocks()
    } else if (location.momentId != null) node.diaryMoments().firstOrNull { it.id == location.momentId }?.blocks() else node.blocks()
    return content?.firstOrNull { it.id == location.blockId }
}
