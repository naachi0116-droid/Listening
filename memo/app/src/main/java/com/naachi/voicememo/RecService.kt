package com.naachi.voicememo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 録音の本体。画面を消すか、アプリを閉じると止めて保存する */
class RecService : Service() {
    companion object {
        @Volatile var recording = false
        @Volatile var startedAt = 0L
        var listeners = mutableListOf<() -> Unit>()
        fun changed() { listeners.toList().forEach { it() } }
        const val DIR = "Recordings/ボイスメモ/"
    }

    private var rec: MediaRecorder? = null
    private var uri: Uri? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopRec(); return START_NOT_STICKY }
        if (!recording) startRec()
        return START_NOT_STICKY
    }

    private fun notif(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("rec", "録音中", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, RecService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "rec")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("🔴 録音中")
            .setContentText("タップで画面を開く")
            .setUsesChronometer(true).setWhen(System.currentTimeMillis())
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "■ 停止して保存", stop).build())
            .setOngoing(true).build()
    }

    private fun startRec() {
        startForeground(9, notif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        val name = "メモ_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.JAPAN).format(Date()) + ".m4a"
        val cv = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, DIR)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        try {
            val u = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cv) ?: throw RuntimeException("保存先を作れません")
            uri = u
            val pfd = contentResolver.openFileDescriptor(u, "w") ?: throw RuntimeException("保存先を開けません")
            val r = MediaRecorder(this)
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64000)
            r.setAudioSamplingRate(44100)
            r.setAudioChannels(1)
            r.setOutputFile(pfd.fileDescriptor)
            r.prepare(); r.start()
            rec = r
            recording = true; startedAt = SystemClock.elapsedRealtime()
            wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "memo:rec").apply { acquire(4 * 60 * 60 * 1000L) }
            pfd.close()
        } catch (e: Exception) {
            uri?.let { try { contentResolver.delete(it, null, null) } catch (_: Exception) {} }
            recording = false
            MainActivity.lastError = e.message ?: e.toString()
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
        changed()
    }

    private fun stopRec() {
        try { rec?.stop() } catch (_: Exception) {}
        rec?.release(); rec = null
        uri?.let {
            try { contentResolver.update(it, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null) } catch (_: Exception) {}
        }
        uri = null
        recording = false
        try { wake?.release() } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        changed()
    }

    // 画面が消えたら止めて保存（アプリの画面が出ていないときの念のため）
    private val screenOff = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: Intent?) { if (recording) stopRec() }
    }
    override fun onCreate() { super.onCreate(); registerReceiver(screenOff, android.content.IntentFilter(Intent.ACTION_SCREEN_OFF)) }
    override fun onDestroy() { try { unregisterReceiver(screenOff) } catch (_: Exception) {}; if (recording) stopRec(); super.onDestroy() }
}
