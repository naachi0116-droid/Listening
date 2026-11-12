package com.naachi.eigodrive

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var tNo: TextView
    private lateinit var tJa: TextView
    private lateinit var tEn: TextView
    private lateinit var tHeard: TextView
    private lateinit var tVerdict: TextView
    private lateinit var tStatus: TextView
    private lateinit var tCount: TextView
    private lateinit var level: ProgressBar
    private lateinit var from: EditText
    private lateinit var key: EditText
    private lateinit var startBtn: Button
    private lateinit var rtBtn: Button
    private val ui = Handler(Looper.getMainLooper())
    private val refresh = { render() }
    private val tick = object : Runnable { override fun run() { level.progress = (Hub.level / 40).toInt().coerceIn(0, 100); ui.postDelayed(this, 150) } }

    private fun tv(size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD); setPadding(0, 12, 0, 12)
    }
    private fun btn(text: String, on: () -> Unit) = Button(this).apply { this.text = text; textSize = 18f; setOnClickListener { on() } }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Hub.load(this)
        val prefs = getSharedPreferences("s", MODE_PRIVATE)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 60, 40, 60); setBackgroundColor(Color.rgb(17, 20, 24)) }
        col.addView(tv(24f, Color.rgb(255, 160, 80), true).apply { text = "英作文ドライブ（声で練習）" })
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        col.addView(tv(13f, Color.GRAY).apply { text = "バージョン $ver" })
        tCount = tv(15f, Color.LTGRAY); col.addView(tCount)
        col.addView(btn("📄 例文ファイルを読み込む") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/*"), 5)
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(tv(18f).apply { text = "何番から：" })
        from = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(prefs.getInt("from", 1).toString()); textSize = 20f; setTextColor(Color.WHITE); minEms = 4 }
        row.addView(from)
        col.addView(row)
        rtBtn = btn("🤖 AIと会話して練習（速い・おすすめ）") { toggle("rt") }.apply { textSize = 21f }
        col.addView(rtBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        startBtn = btn("▶ ふつうの練習（1問ずつ判定）") { toggle("classic") }.apply { textSize = 18f }
        col.addView(startBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        level = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        col.addView(level)
        tStatus = tv(17f, Color.rgb(255, 200, 80)); col.addView(tStatus)
        tNo = tv(20f, Color.rgb(80, 180, 255), true); col.addView(tNo)
        tJa = tv(26f, Color.WHITE, true); col.addView(tJa)
        tEn = tv(22f, Color.rgb(90, 210, 140)); col.addView(tEn)
        tVerdict = tv(19f); col.addView(tVerdict)
        tHeard = tv(16f, Color.LTGRAY); col.addView(tHeard)
        col.addView(tv(14f, Color.GRAY).apply {
            text = "声での操作：「次」「もう一回」「わからない」「問題」「前の問題」「終わり」\n" +
                "車のBluetoothにつながっていれば、電話と同じように車のスピーカーとマイクを使います（Wazeの案内も割り込めます）。"
        })
        col.addView(tv(15f, Color.LTGRAY).apply { text = "\nOpenAIのAPIキー" })
        key = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("key", "")); setTextColor(Color.WHITE); hint = "sk-..."
        }
        col.addView(key)
        col.addView(btn("キーを保存") {
            prefs.edit().putString("key", key.text.toString().trim()).apply()
            Toast.makeText(this, "保存しました", Toast.LENGTH_SHORT).show()
        })
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.rgb(17, 20, 24)); addView(col) })

        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) need.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
        render()
    }

    private fun toggle(mode: String) {
        if (Hub.running) { startService(Intent(this, DrillService::class.java).setAction("stop")); return }
        val k = getSharedPreferences("s", MODE_PRIVATE).getString("key", "") ?: ""
        if (k.isBlank()) { Toast.makeText(this, "先にAPIキーを入れて保存してください", Toast.LENGTH_LONG).show(); return }
        if (Hub.items.isEmpty()) { Toast.makeText(this, "先に例文ファイルを読み込んでください", Toast.LENGTH_LONG).show(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return
        }
        val n = from.text.toString().toIntOrNull() ?: 1
        getSharedPreferences("s", MODE_PRIVATE).edit().putInt("from", n).apply()
        Hub.status = "準備中…"; render()
        startForegroundService(Intent(this, DrillService::class.java).putExtra("from", n).putExtra("mode", mode))
    }

    private fun render() {
        tCount.text = if (Hub.items.isEmpty()) "例文がまだありません。英作文アプリの「例文」タブ →「📄 例文ファイルを保存」で作ったファイルを読み込んでください"
                      else "例文 ${Hub.items.size}文（${Hub.items.first().no}〜${Hub.items.last().no}番）"
        startBtn.text = if (Hub.running) "■ 練習を終わる" else "▶ ふつうの練習（1問ずつ判定）"
        rtBtn.text = if (Hub.running) "■ 練習を終わる" else "🤖 AIと会話して練習（速い・おすすめ）"
        tStatus.text = Hub.status + if (Hub.route.isNotEmpty() && Hub.running) "　［${Hub.route}］" else ""
        tNo.text = Hub.no; tJa.text = Hub.ja
        tEn.text = if (Hub.showEn) Hub.en else ""
        tVerdict.text = Hub.verdict.let { if (it.isNotEmpty() && Hub.running && !it.startsWith("✅") && !it.startsWith("🔶") && !it.startsWith("❌")) "AI：$it" else it }
        tHeard.text = if (Hub.heard.isEmpty()) "" else "あなた：" + Hub.heard
        if (Hub.running) {
            val n = Hub.no.removeSuffix("番").toIntOrNull()
            if (n != null) getSharedPreferences("s", MODE_PRIVATE).edit().putInt("from", n).apply()
        }
    }

    override fun onResume() { super.onResume(); Hub.listen(refresh); ui.post(tick); render() }
    override fun onPause() { Hub.unlisten(refresh); ui.removeCallbacks(tick); super.onPause() }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != 5 || res != RESULT_OK) return
        val uri = data?.data ?: return
        val text = try { contentResolver.openInputStream(uri)!!.bufferedReader().readText() } catch (e: Exception) { "" }
        val n = Hub.import(this, text)
        Toast.makeText(this, if (n > 0) "${n}文を読み込みました" else "例文が見つかりませんでした", Toast.LENGTH_LONG).show()
        render()
    }
}
