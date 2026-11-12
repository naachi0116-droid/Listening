package com.naachi.eigodrive

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * AIとリアルタイムの音声会話で練習する（ChatGPTの音声会話と同じしくみ）。
 * 出題する文は必ずアプリの例文リストから渡す（AIに問題を作らせない）。
 * AIは「出題・判定・直し・お手本」を声で行い、番号の移動は道具（関数）でアプリに頼む。
 */
class Realtime(
    private val ctx: Context,
    private val key: String,
    private val items: List<Item>,
    startIndex: Int,
    private val ui: (Hub.() -> Unit) -> Unit,
    private val onEnd: () -> Unit,
) {
    private var idx = startIndex.coerceIn(0, max(0, items.size - 1))
    private var ws: WebSocket? = null
    @Volatile private var alive = false
    private var rec: AudioRecord? = null
    private var track: AudioTrack? = null
    private var micThread: Thread? = null
    @Volatile private var aiSpeaking = false

    private fun itemJson(i: Int): String {
        val it = items[i]
        return JSONObject().put("number", it.no).put("japanese", it.ja).put("model_english", it.en).toString()
    }

    private fun show(i: Int) {
        val it = items[i]
        ui { no = "${it.no}番"; ja = it.ja; en = it.en; showEn = false; verdict = ""; heard = "" }
    }

    private val rules = """
あなたは日本人ビジネスパーソン（水産物の貿易担当）の英会話コーチです。音声だけでやり取りします。瞬間英作文の練習をします。
大事なルール：
- 出題する文は、必ず道具 get_item / move で受け取った文だけ。自分で問題を作ったり、文を変えたりしない。
- 出題では、受け取った japanese を日本語で読み上げるだけ。英語は言わない。番号も最初に短く言う（例「120番。…」）。
- 学習者が英語で答えたら：
  1) 判定を一言（「正解」「おしい」「もう少し」）。言いよどみや言い直しは気にしない。意味が同じで自然なら、お手本と違う言い方でも正解。
  2) 直す点があれば、日本語で一言だけ（長い説明はしない）。
  3) model_english をそのまま1回、はっきり自然な速さで読む。
  4) お手本を読んだら、そこで止まって待つ。正解でも自分から次へ進まない。
- move は、学習者がはっきり「次」「前」「○番」と言ったときだけ呼ぶ。それ以外（正解したとき、聞き取れなかったとき、雑音など）では絶対に呼ばない。
- 学習者の指示：
  「次」→ move(action="next")／「前」「前の問題」→ move(action="prev")／「120番」「120番読んで」→ move(action="jump", number=120)
  「もう一回」→ 直前に読んだもの（問題かお手本）をもう一度／「わからない」「答え」→ model_english を読む／「問題」→ japanese をもう一度
  「ゆっくり」→ お手本をゆっくり読む／「終わり」→ 一言あいさつして end_session を呼ぶ
- 運転中のこともあるので、1回の発言は短く（2〜3文まで）。雑談や前置きはしない。
- 文法や表現について質問されたら、短く日本語で答えてから、同じ問題に戻る。
""".trimIndent()

    fun start() {
        alive = true
        show(idx)
        ui { status = "🔌 AIにつないでいます…" }
        val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()
        val req = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?model=gpt-realtime-mini")
            .header("Authorization", "Bearer $key")
            .build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(sessionUpdate().toString())
                // 最初の問題を出してもらう
                webSocket.send(JSONObject().put("type", "conversation.item.create").put("item", JSONObject()
                    .put("type", "message").put("role", "user")
                    .put("content", JSONArray().put(JSONObject().put("type", "input_text")
                        .put("text", "練習を始めます。get_item で今の問題を受け取って出題してください。")))).toString())
                webSocket.send(JSONObject().put("type", "response.create").toString())
                startAudio()
                ui { status = "🎤 AIと会話中（「次」「120番」「わからない」「終わり」など）" }
            }
            override fun onMessage(webSocket: WebSocket, text: String) { handle(JSONObject(text)) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val body = try { response?.body?.string() } catch (_: Exception) { null }
                ui { status = "⚠ AIとの接続が切れました：" + (t.message ?: "") + (if (body.isNullOrBlank()) "" else " " + body.take(150)) }
                stop()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { stop() }
        })
    }

    private fun sessionUpdate(): JSONObject {
        val tools = JSONArray()
            .put(JSONObject().put("type", "function").put("name", "get_item")
                .put("description", "今の問題（番号・日本語・お手本の英語）を受け取る")
                .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject())))
            .put(JSONObject().put("type", "function").put("name", "move")
                .put("description", "問題を移動して、その問題を受け取る。next=次、prev=前、jump=番号を指定")
                .put("parameters", JSONObject().put("type", "object")
                    .put("properties", JSONObject()
                        .put("action", JSONObject().put("type", "string").put("enum", JSONArray().put("next").put("prev").put("jump")))
                        .put("number", JSONObject().put("type", "integer")))
                    .put("required", JSONArray().put("action"))))
            .put(JSONObject().put("type", "function").put("name", "end_session")
                .put("description", "練習を終える").put("parameters", JSONObject().put("type", "object").put("properties", JSONObject())))
        val audio = JSONObject()
            .put("input", JSONObject()
                .put("format", JSONObject().put("type", "audio/pcm").put("rate", 24000))
                .put("transcription", JSONObject().put("model", "gpt-4o-mini-transcribe"))
                .put("turn_detection", JSONObject().put("type", "server_vad").put("silence_duration_ms", 900).put("prefix_padding_ms", 300)))
            .put("output", JSONObject()
                .put("format", JSONObject().put("type", "audio/pcm").put("rate", 24000))
                .put("voice", "marin"))
        return JSONObject().put("type", "session.update").put("session", JSONObject()
            .put("type", "realtime")
            .put("instructions", rules)
            .put("audio", audio)
            .put("tools", tools)
            .put("tool_choice", "auto"))
    }

    private fun handle(e: JSONObject) {
        when (e.optString("type")) {
            "response.output_audio.delta", "response.audio.delta" -> {
                val pcm = Base64.decode(e.optString("delta"), Base64.DEFAULT)
                aiSpeaking = true
                track?.write(pcm, 0, pcm.size)
            }
            "response.done" -> aiSpeaking = false
            "input_audio_buffer.speech_started" -> {
                // こちらが話し始めたら、AIの声を止める（割り込み）
                try { track?.pause(); track?.flush(); track?.play() } catch (_: Exception) {}
                ui { status = "🎤 聞いています…" }
            }
            "conversation.item.input_audio_transcription.completed" -> {
                val t = e.optString("transcript").trim()
                ui { heard = t; status = "🤖 AIが考えています…" }
            }
            "response.output_audio_transcript.done", "response.audio_transcript.done" -> {
                val t = e.optString("transcript").trim()
                if (t.isNotEmpty()) ui { verdict = t; status = "🎤 どうぞ（「次」「120番」「わからない」「終わり」）" }
            }
            "response.function_call_arguments.done" -> tool(e.optString("call_id"), e.optString("name"), e.optString("arguments"))
            "error" -> {
                val m = e.optJSONObject("error")?.optString("message") ?: e.toString()
                ui { status = "⚠ " + m.take(160) }
            }
        }
    }

    private fun tool(callId: String, name: String, args: String) {
        val a = try { JSONObject(args) } catch (_: Exception) { JSONObject() }
        val out: String = when (name) {
            "get_item" -> { show(idx); itemJson(idx) }
            "move" -> {
                when (a.optString("action")) {
                    "next" -> idx = (idx + 1).coerceAtMost(items.size - 1)
                    "prev" -> idx = (idx - 1).coerceAtLeast(0)
                    "jump" -> { val n = a.optInt("number", -1); val k = items.indexOfFirst { it.no >= n }; if (k >= 0) idx = k }
                }
                show(idx)
                ctx.getSharedPreferences("s", Context.MODE_PRIVATE).edit().putInt("from", items[idx].no).apply()
                itemJson(idx)
            }
            "end_session" -> { Thread { Thread.sleep(2500); stop() }.start(); "{\"ok\":true}" }
            else -> "{\"error\":\"unknown\"}"
        }
        ws?.send(JSONObject().put("type", "conversation.item.create").put("item", JSONObject()
            .put("type", "function_call_output").put("call_id", callId).put("output", out)).toString())
        if (name != "end_session") ws?.send(JSONObject().put("type", "response.create").toString())
    }

    private fun startAudio() {
        val rate = 24000
        val outBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(max(outBuf * 8, rate * 2 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM).build().apply { play() }
        val inBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        @Suppress("MissingPermission")
        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(inBuf, 9600))
        rec = r
        try { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(r.audioSessionId)?.enabled = true } catch (_: Exception) {}
        try { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(r.audioSessionId)?.enabled = true } catch (_: Exception) {}
        r.startRecording()
        micThread = Thread {
            val buf = ByteArray(4800) // 100ms
            while (alive) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) {
                    var sum = 0.0
                    for (i in 0 until n / 2) { val v = (buf[2 * i].toInt() and 0xff) or (buf[2 * i + 1].toInt() shl 8); sum += v.toShort() * v.toShort().toDouble() }
                    Hub.level = kotlin.math.sqrt(sum / (n / 2)).toFloat()
                    ws?.send(JSONObject().put("type", "input_audio_buffer.append")
                        .put("audio", Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)).toString())
                }
            }
        }.apply { start() }
    }

    fun stop() {
        if (!alive) return
        alive = false
        try { ws?.close(1000, null) } catch (_: Exception) {}
        try { rec?.stop() } catch (_: Exception) {}
        rec?.release(); rec = null
        try { track?.stop() } catch (_: Exception) {}
        track?.release(); track = null
        onEnd()
    }
}
