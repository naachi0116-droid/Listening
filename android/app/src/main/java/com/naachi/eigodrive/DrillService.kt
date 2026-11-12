package com.naachi.eigodrive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * 練習の本体。画面を消しても続くように「マイクを使うサービス」として動く。
 * 流れ：日本語の問題 → 英語で答える → AIが判定・一言の直し → お手本 → 「次」で次の問題
 */
class DrillService : Service() {
    private var thread: Thread? = null
    private var audio: Audio? = null
    private val ui = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null
    private var rt: Realtime? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopDrill(); return START_NOT_STICKY }
        val start = intent?.getIntExtra("from", 1) ?: 1
        val key = getSharedPreferences("s", MODE_PRIVATE).getString("key", "") ?: ""
        startFg()
        if (thread == null) {
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "eigo:drill").apply { acquire(3 * 60 * 60 * 1000L) }
            val mode = intent?.getStringExtra("mode") ?: "classic"
            thread = Thread { if (mode == "rt") runRealtime(key, start) else run(key, start) }.apply { start() }
        }
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("drill", "英作文の練習", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stopPi = PendingIntent.getService(this, 1, Intent(this, DrillService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "drill")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("英作文ドライブ練習中")
            .setContentText("「次」「もう一回」「わからない」「終わり」で操作")
            .setContentIntent(pi)
            .addAction(Notification.Action.Builder(null, "終わる", stopPi).build())
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(7, n)
    }

    private fun set(f: Hub.() -> Unit) { ui.post { Hub.f(); Hub.changed() } }

    private fun stopDrill() {
        Hub.running = false
        audio?.stopFlag = true
        rt?.stop()
        thread?.interrupt()
        set { status = "終了しました" }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        Hub.running = false
        audio?.stopFlag = true
        try { wake?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    private enum class Cmd { NEXT, AGAIN, ANSWER, QUESTION, STOP, PREV }

    private fun command(t: String): Cmd? {
        val s = t.lowercase().replace(Regex("""[\s、。.,!?！？「」'"’]"""), "")
        if (s.length > 14) return null
        return when {
            Regex("終わり|おわり|終了|ストップ|stop|やめ").containsMatchIn(s) -> Cmd.STOP
            Regex("前の|まえの|戻って|もどって|previous|back").containsMatchIn(s) -> Cmd.PREV
            Regex("^(次|つぎ|next|ネクスト|スキップ|skip|次へ|つぎへ|次の問題)").containsMatchIn(s) -> Cmd.NEXT
            Regex("わからない|分からない|わかんない|答え|こたえ|idontknow|pass|パス").containsMatchIn(s) -> Cmd.ANSWER
            Regex("もう一回|もう1回|もういっかい|もう一度|もういちど|again|repeat|リピート").containsMatchIn(s) -> Cmd.AGAIN
            Regex("問題|もんだい|question").containsMatchIn(s) -> Cmd.QUESTION
            else -> null
        }
    }

    private val HINT = "英語の答え、または「次」「もう一回」「わからない」「120番」などの短い日本語の指示です。"

    private fun kanjiNum(s: String): Int? {
        val d = mapOf('一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        var total = 0; var cur = 0
        for (c in s) {
            when {
                d.containsKey(c) -> cur = d[c]!!
                c == '百' -> { total += (if (cur == 0) 1 else cur) * 100; cur = 0 }
                c == '十' -> { total += (if (cur == 0) 1 else cur) * 10; cur = 0 }
                c == '〇' || c == '零' -> cur = 0
                else -> return null
            }
        }
        return (total + cur).takeIf { it > 0 }
    }

    private fun jumpNo(t: String): Int? {
        val z = t.replace(Regex("[０-９]")) { m -> ((m.value[0] - '０') + '0'.code).toChar().toString() }
        Regex("""(\d+)\s*(番|ばん|ban|号)""").find(z)?.let { return it.groupValues[1].toInt() }
        // 「120」だけ、のように数字だけを言ったときも番号とみなす
        Regex("""^\D{0,6}(\d{1,3})\D{0,8}$""").find(z.trim())?.let { m -> if (!Regex("[a-zA-Z]{3,}").containsMatchIn(z)) return m.groupValues[1].toInt() }
        Regex("""([一二三四五六七八九十百〇零]+)\s*番""").find(z)?.let { return kanjiNum(it.groupValues[1]) }
        Regex("""(?i)number\s*(\d+)""").find(z)?.let { return it.groupValues[1].toInt() }
        return null
    }

    /** AIとリアルタイム会話で練習（出題はアプリが固定で渡す） */
    private fun runRealtime(key: String, from: Int) {
        val a = Audio(this); audio = a
        Hub.running = true
        val done = java.util.concurrent.CountDownLatch(1)
        try {
            a.begin()
            set { route = a.route }
            val items = Hub.items
            val start = items.indexOfFirst { it.no >= from }.coerceAtLeast(0)
            val r = Realtime(this, key, items, start, { f -> set(f) }, { done.countDown() })
            rt = r
            r.start()
            done.await()
        } catch (e: Exception) {
            set { status = "⚠ " + (e.message ?: e.toString()) }
        } finally {
            rt = null
            a.end()
            Hub.running = false
            thread = null
            set { if (!status.startsWith("⚠")) status = "終了しました" }
            ui.post { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
    }

    private fun run(key: String, from: Int) {
        val a = Audio(this); audio = a
        val ai = Ai(key, filesDir)
        Hub.running = true
        try {
            a.begin()
            set { route = a.route; status = "音の出口：${a.route}" }
            val items = Hub.items
            var i = items.indexOfFirst { it.no >= from }.coerceAtLeast(0)
            // 次の問題の声を先に作っておく（待ち時間を短く）
            fun prefetch(k: Int) { items.getOrNull(k)?.let { it2 -> Thread { try { ai.tts(it2.ja, true); ai.tts(it2.en, false) } catch (_: Exception) {} }.start() } }
            fun say(t: String, ja: Boolean) { a.play(ai.tts(t, ja)) }
            while (Hub.running && i < items.size) {
                val it = items[i]
                prefetch(i + 1)
                set { no = "${it.no}番"; this.ja = it.ja; this.en = it.en; showEn = false; heard = ""; verdict = ""; status = "🔊 問題" }
                say(it.ja, true)
                var answered = false
                var quiet = 0
                var go = 0 // +1 次へ、-1 前へ
                while (Hub.running && go == 0) {
                    set { status = if (answered) "🎤 「次」で次へ（もう一度英語で言ってもOK）" else "🎤 英語で答えてください" }
                    val wav = a.listen(if (answered) 30000 else 25000) { lv -> Hub.level = lv }
                    if (!Hub.running) break
                    if (wav == null) {
                        quiet++
                        if (!answered && quiet == 1) { set { status = "🔊 問題をもう一度" }; say(it.ja, true) }
                        continue
                    }
                    set { status = "⏳ 聞き取り中…" }
                    val said = try { ai.transcribe(wav, HINT) } catch (e: Exception) { set { status = "⚠ " + e.message }; continue }
                    if (said.isBlank()) continue
                    set { heard = said }
                    // 「100番」「100番読んで」「number 100」で、その番号へ飛ぶ
                    val jump = jumpNo(said)
                    if (jump != null) {
                        val k = items.indexOfFirst { x -> x.no >= jump }
                        if (k >= 0) { i = k; go = 2; continue }
                    }
                    when (command(said)) {
                        Cmd.STOP -> { Hub.running = false; break }
                        Cmd.NEXT -> { go = 1; continue }
                        Cmd.PREV -> { go = -1; continue }
                        Cmd.QUESTION -> { say(it.ja, true); continue }
                        Cmd.ANSWER -> { set { showEn = true; status = "🔊 お手本" }; say(it.en, false); answered = true; continue }
                        Cmd.AGAIN -> { if (answered) { set { status = "🔊 お手本" }; say(it.en, false) } else say(it.ja, true); continue }
                        null -> {}
                    }
                    val latin = said.count { ch -> ch in 'a'..'z' || ch in 'A'..'Z' }
                    val jaChars = said.count { ch -> ch.code in 0x3040..0x30ff || ch.code in 0x4e00..0x9fff }
                    if (latin < 4 || jaChars > latin) { set { status = "🤔 「$said」は指示として分かりませんでした" }; continue }
                    set { status = "⏳ 判定中…" }
                    val v = try { ai.judge(it.ja, it.en, said) } catch (e: Exception) { set { status = "⚠ " + e.message }; null }
                    val head = when (v?.verdict) { "correct" -> "正解です。"; "close" -> "おしい。"; else -> "もう少し。" }
                    set { verdict = when (v?.verdict) { "correct" -> "✅ 正解"; "close" -> "🔶 おしい"; else -> "❌ 不正解" } + (if (v?.point.isNullOrBlank()) "" else "　" + v!!.point); showEn = true; status = "🔊 判定とお手本" }
                    say(head + (v?.point ?: ""), true)
                    say(it.en, false)
                    answered = true
                    if (v?.verdict == "correct") go = 1 // 正解なら次へ
                }
                i = when { go == 2 -> i; go < 0 -> (i - 1).coerceAtLeast(0); else -> i + 1 }
            }
            if (i >= items.size) { set { status = "最後の問題まで終わりました" }; say("お疲れさまでした。", true) }
        } catch (e: Exception) {
            set { status = "⚠ " + (e.message ?: e.toString()) }
        } finally {
            a.end()
            Hub.running = false
            thread = null
            set { if (!status.startsWith("⚠")) status = "終了しました" }
            ui.post { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
    }
}
