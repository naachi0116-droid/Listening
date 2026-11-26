package com.naachi.voicememo

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** アイコンを押したら、すぐ録音が始まる。もう一度開くと録音画面（止める・一覧）が出る */
class MainActivity : Activity() {
    companion object { var lastError = "" }

    private lateinit var timer: TextView
    private lateinit var bigBtn: Button
    private lateinit var list: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var playing: Long = -1
    private val refresh = { ui.post { render() } ; Unit }
    private val tick = object : Runnable {
        override fun run() {
            if (RecService.recording) {
                val s = (SystemClock.elapsedRealtime() - RecService.startedAt) / 1000
                timer.text = "🔴 " + fmt(s)
            }
            ui.postDelayed(this, 500)
        }
    }

    private fun fmt(s: Long) = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    private fun tv(size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val bg = Color.rgb(17, 20, 24)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 70, 40, 60); setBackgroundColor(bg) }
        col.addView(tv(22f, Color.rgb(255, 120, 120), true).apply { text = "ボイスメモ" })
        timer = tv(56f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setPadding(0, 40, 0, 20) }
        col.addView(timer, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        bigBtn = Button(this).apply { textSize = 24f; setOnClickListener { toggle() } }
        col.addView(bigBtn, LinearLayout.LayoutParams(MATCH_PARENT, 220))
        col.addView(tv(14f, Color.GRAY).apply {
            text = "アイコンを押すと、すぐ録音が始まります。画面を消しても、ほかのアプリを使っても録音は続きます（通知の「■ 停止して保存」でも止められます）。\n保存先：スマホの「Recordings/ボイスメモ」フォルダ"
            setPadding(0, 20, 0, 30)
        })
        col.addView(tv(18f, Color.LTGRAY, true).apply { text = "保存したメモ" })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        setContentView(ScrollView(this).apply { setBackgroundColor(bg); addView(col) })
        if (b == null) autoStart()
    }

    // ホーム画面のアイコンからもう一度開いたときも、録音していなければすぐ始める
    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); if (intent?.action == Intent.ACTION_MAIN) autoStart() }

    /** 起動したら、録音していなければすぐ始める */
    private fun autoStart() {
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) { requestPermissions(missing.toTypedArray(), 1); return }
        if (!RecService.recording) start()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED && !RecService.recording) start()
        else render()
    }

    private fun start() {
        stopPlay()
        startForegroundService(Intent(this, RecService::class.java))
        ui.postDelayed({ if (lastError.isNotEmpty()) { Toast.makeText(this, "録音できません：$lastError", Toast.LENGTH_LONG).show(); lastError = "" }; render() }, 600)
    }

    private fun toggle() {
        if (RecService.recording) startService(Intent(this, RecService::class.java).setAction("stop"))
        else {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { autoStart(); return }
            start()
        }
    }

    data class Memo(val id: Long, val name: String, val date: Long, val dur: Long, val uri: Uri)

    private fun memos(): List<Memo> {
        val out = mutableListOf<Memo>()
        val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.DURATION)
        contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj,
            "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?", arrayOf(RecService.DIR + "%"),
            "${MediaStore.Audio.Media.DATE_ADDED} DESC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out.add(Memo(id, c.getString(1), c.getLong(2) * 1000, c.getLong(3), ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)))
            }
        }
        return out
    }

    private fun render() {
        if (RecService.recording) {
            bigBtn.text = "■ 停止して保存"; bigBtn.setBackgroundColor(Color.rgb(200, 50, 60)); bigBtn.setTextColor(Color.WHITE)
        } else {
            timer.text = "待機中"
            bigBtn.text = "● 録音する"; bigBtn.setBackgroundColor(Color.rgb(45, 55, 65)); bigBtn.setTextColor(Color.WHITE)
        }
        list.removeAllViews()
        val ms = try { memos() } catch (_: Exception) { emptyList() }
        if (ms.isEmpty()) { list.addView(tv(14f, Color.GRAY).apply { text = "まだありません" }); return }
        val df = SimpleDateFormat("M/d(E) HH:mm", Locale.JAPAN)
        for (m in ms) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 14, 0, 14) }
            val info = tv(17f).apply { text = df.format(Date(m.date)) + (if (m.dur > 0) "　" + fmt(m.dur / 1000) else "") }
            row.addView(info, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(Button(this).apply { text = if (playing == m.id) "■" else "▶"; setOnClickListener { play(m) } })
            row.addView(Button(this).apply { text = "共有"; setOnClickListener { share(m) } })
            row.addView(Button(this).apply { text = "🗑"; setOnClickListener { del(m) } })
            list.addView(row)
        }
    }

    private fun play(m: Memo) {
        if (playing == m.id) { stopPlay(); render(); return }
        stopPlay()
        player = MediaPlayer().apply {
            setDataSource(this@MainActivity, m.uri); prepare(); start()
            setOnCompletionListener { stopPlay(); render() }
        }
        playing = m.id; render()
    }
    private fun stopPlay() { try { player?.stop() } catch (_: Exception) {}; player?.release(); player = null; playing = -1 }

    private fun share(m: Memo) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("audio/mp4").putExtra(Intent.EXTRA_STREAM, m.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "メモを送る"))
    }

    private fun del(m: Memo) {
        AlertDialog.Builder(this).setMessage("このメモを削除しますか？").setPositiveButton("削除") { _, _ ->
            if (playing == m.id) stopPlay()
            try { contentResolver.delete(m.uri, null, null) } catch (e: Exception) { Toast.makeText(this, "削除できませんでした", Toast.LENGTH_SHORT).show() }
            render()
        }.setNegativeButton("やめる", null).show()
    }

    override fun onResume() { super.onResume(); RecService.listeners.add(refresh); ui.post(tick); render() }
    override fun onPause() { RecService.listeners.remove(refresh); ui.removeCallbacks(tick); super.onPause() }
    override fun onDestroy() { stopPlay(); super.onDestroy() }
}
