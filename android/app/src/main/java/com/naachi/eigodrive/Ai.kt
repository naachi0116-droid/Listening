package com.naachi.eigodrive

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** OpenAI とのやり取り（読み上げ・文字起こし・判定） */
class Ai(private val key: String, private val cacheDir: File) {

    private fun conn(path: String): HttpURLConnection {
        val c = URL("https://api.openai.com/v1/$path").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer $key")
        return c
    }

    private fun readAll(c: HttpURLConnection): ByteArray {
        val code = c.responseCode
        val s = if (code in 200..299) c.inputStream else c.errorStream
        val b = s?.readBytes() ?: ByteArray(0)
        if (code !in 200..299) throw RuntimeException("OpenAI $code: " + String(b).take(200))
        return b
    }

    /** 読み上げ（24kHz 16bit モノラルのPCM）。一度作った声は端末に保存して使い回す */
    fun tts(text: String, japanese: Boolean): ByteArray {
        val voice = "coral"
        val instr = if (japanese) "Read this Japanese sentence clearly and naturally, at a normal pace."
                    else "Speak clear, natural American English at a normal conversational pace."
        val h = MessageDigest.getInstance("MD5").digest("$voice|$instr|$text".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val dir = File(cacheDir, "tts").apply { mkdirs() }
        val f = File(dir, "$h.pcm")
        if (f.exists() && f.length() > 0) return f.readBytes()
        val c = conn("audio/speech")
        c.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject()
            .put("model", "gpt-4o-mini-tts").put("voice", voice).put("input", text)
            .put("instructions", instr).put("response_format", "pcm")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val pcm = readAll(c)
        f.writeBytes(pcm)
        return pcm
    }

    /** 文字起こし（16kHz WAV） */
    fun transcribe(wav: ByteArray, hint: String): String {
        val boundary = "----eigo" + System.nanoTime()
        val c = conn("audio/transcriptions")
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        val out = ByteArrayOutputStream()
        fun field(name: String, value: String) {
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        }
        field("model", "gpt-4o-mini-transcribe")
        if (hint.isNotBlank()) field("prompt", hint)
        out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
        out.write(wav)
        out.write("\r\n--$boundary--\r\n".toByteArray())
        c.outputStream.use { it.write(out.toByteArray()) }
        return JSONObject(String(readAll(c))).optString("text").trim()
    }

    data class Verdict(val verdict: String, val point: String)

    /** 判定：お手本と同じ意味で自然な英語なら正解 */
    fun judge(ja: String, model: String, said: String): Verdict {
        val c = conn("chat/completions")
        c.setRequestProperty("Content-Type", "application/json")
        val sys = "You grade a Japanese learner's spoken English translation. " +
            "Ignore fillers, hesitations and self-corrections; judge the final intended sentence. " +
            "verdict: \"correct\" if it conveys the same meaning naturally (paraphrases are fine), " +
            "\"close\" if understandable with small errors, \"wrong\" otherwise. " +
            "point: ONE short Japanese sentence (max 40 chars) on the most important fix; empty if correct. " +
            "Reply JSON {\"verdict\":...,\"point\":...}."
        val user = "Japanese: $ja\nModel answer: $model\nLearner said: $said"
        val body = JSONObject().put("model", "gpt-4.1-mini")
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("temperature", 0)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", sys))
                .put(JSONObject().put("role", "user").put("content", user)))
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val j = JSONObject(String(readAll(c)))
        val txt = j.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        val r = JSONObject(txt)
        return Verdict(r.optString("verdict", "wrong"), r.optString("point", ""))
    }
}
