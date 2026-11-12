package com.naachi.eigodrive

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Item(val no: Int, val ja: String, val en: String)

/** 画面と練習（サービス）で共有する状態 */
object Hub {
    var items: List<Item> = emptyList()
    @Volatile var running = false
    var no = ""; var ja = ""; var en = ""; var showEn = false
    var heard = ""; var verdict = ""; var status = "スタートを押してください"; var route = ""
    @Volatile var level = 0f
    private val listeners = mutableListOf<() -> Unit>()

    fun listen(f: () -> Unit) { listeners.add(f) }
    fun unlisten(f: () -> Unit) { listeners.remove(f) }
    fun changed() { listeners.toList().forEach { it() } }

    private fun file(ctx: Context) = File(ctx.filesDir, "items.json")

    fun load(ctx: Context) {
        val f = file(ctx)
        if (!f.exists()) return
        val a = JSONArray(f.readText())
        items = (0 until a.length()).map { a.getJSONObject(it) }
            .map { Item(it.getInt("no"), it.getString("ja"), it.getString("en")) }
    }

    /** 「番号. 日本語 ／ 英語」の行を読み込む（英作文アプリの「例文ファイルを保存」で作ったファイル） */
    fun import(ctx: Context, text: String): Int {
        val re = Regex("""^\s*(\d+)\s*[.．、]\s*(.+?)\s*[／/]\s*(.+?)\s*$""")
        val list = text.lines().mapNotNull { re.find(it) }
            .map { Item(it.groupValues[1].toInt(), it.groupValues[2], it.groupValues[3]) }
            .sortedBy { it.no }
        if (list.isEmpty()) return 0
        items = list
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("no", it.no).put("ja", it.ja).put("en", it.en)) }
        file(ctx).writeText(a.toString())
        return list.size
    }
}
