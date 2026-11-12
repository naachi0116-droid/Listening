package com.naachi.eigodrive

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 「電話」と同じ扱いで音を出し・聞く。
 * 車のBluetoothにつながっていれば、車のスピーカーとマイクを通話として使う（ChatGPTの音声会話と同じしくみ）。
 */
class Audio(ctx: Context) {
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    @Volatile var stopFlag = false
    var route = "スマホ"

    fun begin() {
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        route = "スマホ"
        if (Build.VERSION.SDK_INT >= 31) {
            val bt = am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            if (bt != null && am.setCommunicationDevice(bt)) route = "Bluetooth（" + (bt.productName ?: "車") + "）"
            else am.isSpeakerphoneOn = true
        } else {
            @Suppress("DEPRECATION")
            if (am.isBluetoothScoAvailableOffCall) {
                am.startBluetoothSco(); am.isBluetoothScoOn = true; route = "Bluetooth"
            } else am.isSpeakerphoneOn = true
        }
    }

    fun end() {
        try {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
            else { @Suppress("DEPRECATION") run { am.stopBluetoothSco(); am.isBluetoothScoOn = false } }
            am.isSpeakerphoneOn = false
            am.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {}
    }

    /** 24kHz 16bit モノラルのPCMを通話の音として流す。最後まで流れたら戻る */
    fun play(pcm: ByteArray, speed: Float = 1f) {
        if (pcm.isEmpty() || stopFlag) return
        val rate = (24000 * speed).toInt()
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(max(minBuf * 4, 48000))
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        try {
            t.play()
            // 頭に少し無音（Bluetoothの通話は鳴り始めが切れやすいので）
            t.write(ByteArray(rate / 5 * 2), 0, rate / 5 * 2)
            var off = 0
            while (off < pcm.size && !stopFlag) {
                val n = minOf(4096, pcm.size - off)
                t.write(pcm, off, n); off += n
            }
            val total = pcm.size / 2 + rate / 5
            while (!stopFlag && t.playbackHeadPosition < total - 200) Thread.sleep(30)
            Thread.sleep(150)
        } finally {
            try { t.stop() } catch (_: Exception) {}
            t.release()
        }
    }

    /**
     * 声を待って録る。話し始めを待ち（waitMs）、話し終わり（silMs の無音）で止める。
     * 何も話さなければ null。戻り値は 16kHz WAV
     */
    fun listen(waitMs: Int, silMs: Int = 1100, maxMs: Int = 15000, level: (Float) -> Unit = {}): ByteArray? {
        val rate = 16000
        val frame = 320 // 20ms
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        @Suppress("MissingPermission")
        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, frame * 2 * 10))
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); throw RuntimeException("マイクを開けません") }
        val buf = ShortArray(frame)
        val pre = ArrayDeque<ShortArray>()
        val rec = ByteArrayOutputStream()
        var floor = 0f; var nFloor = 0
        var started = false; var onMs = 0; var silence = 0; var waited = 0; var total = 0
        try {
            r.startRecording()
            while (!stopFlag) {
                val n = r.read(buf, 0, frame)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) sum += buf[i] * buf[i].toDouble()
                val rms = sqrt(sum / n).toFloat()
                level(rms)
                val th = max(400f, floor * 3f)
                if (!started) {
                    waited += 20
                    if (nFloor < 15) { floor = (floor * nFloor + rms) / (nFloor + 1); nFloor++ }
                    else if (rms < floor * 1.5f) floor = floor * 0.95f + rms * 0.05f
                    pre.addLast(buf.copyOf(n)); if (pre.size > 15) pre.removeFirst()
                    if (rms > th) onMs += 20 else onMs = 0
                    if (onMs >= 140) {
                        started = true
                        for (p in pre) rec.write(toBytes(p))
                    } else if (waited >= waitMs) return null
                } else {
                    rec.write(toBytes(buf.copyOf(n)))
                    total += 20
                    if (rms > th * 0.8f) silence = 0 else silence += 20
                    if (silence >= silMs || total >= maxMs) break
                }
            }
        } finally {
            try { r.stop() } catch (_: Exception) {}
            r.release()
        }
        if (!started || stopFlag) return null
        return wav(rec.toByteArray(), rate)
    }

    private fun toBytes(s: ShortArray): ByteArray {
        val b = ByteBuffer.allocate(s.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (x in s) b.putShort(x)
        return b.array()
    }

    private fun wav(pcm: ByteArray, rate: Int): ByteArray {
        val b = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + pcm.size); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1)
        b.putInt(rate); b.putInt(rate * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(pcm.size); b.put(pcm)
        return b.array()
    }
}
