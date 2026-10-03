package com.shijiannote.app

import android.app.*
import android.content.*
import android.media.MediaRecorder
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.media.AudioAttributes
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class RecordingState(val owner: String = "", val running: Boolean = false, val paused: Boolean = false, val elapsed: Long = 0, val error: String = "")
class RecordingService : Service() {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var owner = ""
    private var start = 0L
    private var accumulated = 0L
    private var paused = false
    private val audioFocus by lazy { AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener { change -> if (change < 0 && recorder != null && !paused) pause() }.build() }
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (recorder == null) return
            state.value = RecordingState(owner, true, paused, elapsed())
            getSystemService(NotificationManager::class.java).notify(707, notification())
            handler.postDelayed(this, 1000)
        }
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "start" -> if (recorder == null) start(intent.getStringExtra("owner") ?: "")
            "pause" -> pause()
            "stop" -> finish(true)
            "cancel" -> finish(false)
        }
        return START_NOT_STICKY
    }
    private fun elapsed(): Long = accumulated + if (paused) 0 else SystemClock.elapsedRealtime() - start
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun action(name: String, code: Int) = PendingIntent.getService(this, code, Intent(this, RecordingService::class.java).setAction(name), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "recording")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle(if (paused) "录音已暂停" else "时笺正在录音")
            .setContentText(audioTime(if (recorder == null) 0 else elapsed())).setContentIntent(open).setOngoing(true).setSilent(true)
            .addAction(0, if (paused) "继续" else "暂停", action("pause", 1)).addAction(0, "结束并保存", action("stop", 2)).build()
    }
    private fun start(id: String) {
        owner = id
        accumulated = 0L; paused = false; start = SystemClock.elapsedRealtime()
        state.value = RecordingState(owner, true, false, 0)
        val channel = NotificationChannel("recording", "语音录制", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        file = File(File(filesDir, "assets").apply { mkdirs() }, "${UUID.randomUUID()}.m4a")
        try {
            startForeground(707, notification())
            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            recorder = r
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(file!!.absolutePath)
            r.setOnErrorListener { _, _, _ -> finish(true) }
            r.prepare(); r.start()
            getSystemService(AudioManager::class.java).requestAudioFocus(audioFocus)
            start = SystemClock.elapsedRealtime()
            saveInbox(file!!, false, 0)
            handler.post(tick)
        } catch (_: Exception) {
            state.value = RecordingState(error = "录音无法开始，请检查麦克风权限或是否被其他应用占用")
            recorder?.release(); recorder = null; file?.delete(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
    }
    private fun pause() {
        try {
            if (paused) { recorder?.resume(); start = SystemClock.elapsedRealtime(); paused = false }
            else { accumulated = elapsed(); recorder?.pause(); paused = true }
            state.value = RecordingState(owner, true, paused, elapsed())
        } catch (_: Exception) { finish(true) }
    }
    private fun finish(keep: Boolean) {
        val r = recorder ?: run { stopSelf(); return }
        val duration = elapsed()
        recorder = null; handler.removeCallbacks(tick)
        val valid = runCatching { r.stop() }.isSuccess
        r.release()
        getSystemService(AudioManager::class.java).abandonAudioFocusRequest(audioFocus)
        val f = file
        if (keep && valid && f != null) saveInbox(f, true, duration)
        else { f?.delete(); removeInbox(this, f?.absolutePath.orEmpty()) }
        state.value = RecordingState()
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    private fun saveInbox(f: File, finished: Boolean, duration: Long) {
        val prefs = getSharedPreferences("recording_inbox", MODE_PRIVATE)
        val all = JSONArray(prefs.getString("items", "[]"))
        val next = JSONArray()
        for (i in 0 until all.length()) if (all.getJSONObject(i).optString("path") != f.absolutePath) next.put(all.getJSONObject(i))
        next.put(JSONObject().put("owner", owner).put("path", f.absolutePath).put("finished", finished).put("duration", duration))
        prefs.edit().putString("items", next.toString()).commit()
    }
    override fun onDestroy() { if (recorder != null) finish(true); super.onDestroy() }
    companion object {
        val state = MutableStateFlow(RecordingState())
        fun command(context: Context, action: String, owner: String = "") {
            val intent = Intent(context, RecordingService::class.java).setAction(action).putExtra("owner", owner)
            if (action == "start") androidx.core.content.ContextCompat.startForegroundService(context, intent) else context.startService(intent)
        }
        fun inbox(context: Context, owner: String): List<NoteBlock> {
            val all = JSONArray(context.getSharedPreferences("recording_inbox", MODE_PRIVATE).getString("items", "[]"))
            return (0 until all.length()).map { all.getJSONObject(it) }.filter { it.optString("owner") == owner && (it.optBoolean("finished") || !state.value.running) }.mapNotNull {
                val f = File(it.getString("path"))
                if (!f.isFile) return@mapNotNull null
                val duration = if (it.optBoolean("finished")) it.optLong("duration") else runCatching {
                    val reader = android.media.MediaMetadataRetriever()
                    try { reader.setDataSource(f.absolutePath); reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L } finally { reader.release() }
                }.getOrDefault(0L)
                NoteBlock(id = f.nameWithoutExtension, type = "audio", text = if (it.optBoolean("finished")) "语音记录" else "中断的录音（可尝试播放）", uri = android.net.Uri.fromFile(f).toString(), owned = true, mime = "audio/mp4", bytes = f.length(), duration = duration)
            }
        }
        fun removeInbox(context: Context, path: String) {
            val prefs = context.getSharedPreferences("recording_inbox", MODE_PRIVATE)
            val all = JSONArray(prefs.getString("items", "[]"))
            val next = JSONArray()
            for (i in 0 until all.length()) if (all.getJSONObject(i).optString("path") != path) next.put(all.getJSONObject(i))
            prefs.edit().putString("items", next.toString()).commit()
        }
    }
}
