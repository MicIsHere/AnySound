package io.anysound.audio

import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.*
import kotlin.concurrent.thread
import kotlin.math.sqrt

interface AudioSource {
    fun start(onAudio: (ByteArray) -> Unit, onLevel: (Float) -> Unit, onError: (String) -> Unit)
    fun stop()
}

class Microphone(private val deviceName: String) : AudioSource {
    private val stopped = AtomicBoolean(false)
    @Volatile private var line: TargetDataLine? = null
    @Volatile private var stream: AudioInputStream? = null

    override fun start(onAudio: (ByteArray) -> Unit, onLevel: (Float) -> Unit, onError: (String) -> Unit) {
        check(!stopped.get()) { "录音已取消" }
        val device = if (deviceName.isBlank()) null else AudioSystem.getMixerInfo().firstOrNull { id(it) == deviceName }
            ?: error("所选麦克风已断开，请重新选择")
        val mixer = device?.let(AudioSystem::getMixer)
        val formats = listOf(targetFormat) + listOf(48000f, 44100f).flatMap { rate ->
            listOf(1, 2).map { channels -> AudioFormat(rate, 16, channels, true, false) }
        }
        var opened: TargetDataLine? = null
        for (format in formats) {
            if (stopped.get()) return
            val info = DataLine.Info(TargetDataLine::class.java, format)
            if (!(mixer?.isLineSupported(info) ?: AudioSystem.isLineSupported(info))) continue
            if (!AudioSystem.isConversionSupported(targetFormat, format)) continue
            var candidate: TargetDataLine? = null
            try {
                candidate = (mixer?.getLine(info) ?: AudioSystem.getLine(info)) as TargetDataLine
                candidate.open(format, (format.frameRate * format.frameSize / 4).toInt())
                opened = candidate
                break
            } catch (_: Exception) { candidate?.close() }
        }
        val input = opened ?: error("无法打开麦克风，请检查设备和系统麦克风权限")
        line = input
        if (stopped.get()) { input.close(); return }
        val converted = try { AudioSystem.getAudioInputStream(targetFormat, AudioInputStream(input)) }
            catch (e: Exception) { input.close(); throw e }
        stream = converted
        input.start()
        thread(name = "anysound-microphone", isDaemon = true) {
            try {
                val buffer = ByteArray(640)
                var lastAudio = System.nanoTime()
                while (!stopped.get()) {
                    val size = converted.read(buffer)
                    if (stopped.get()) break
                    if (size < 0) error("麦克风已断开")
                    if (size == 0) {
                        check(System.nanoTime() - lastAudio < 2_000_000_000L) { "麦克风停止响应" }
                        Thread.sleep(10)
                        continue
                    }
                    lastAudio = System.nanoTime()
                    val pcm = buffer.copyOf(size)
                    onLevel(rms(pcm))
                    onAudio(pcm)
                }
            } catch (_: Exception) {
                if (!stopped.get()) onError("麦克风读取失败或设备已断开")
            } finally { stop() }
        }
    }

    override fun stop() {
        stopped.set(true)
        // Closing the line unblocks a pending read, including the rate converter.
        runCatching { line?.stop() }
        runCatching { line?.close() }
        runCatching { stream?.close() }
    }

    companion object {
        val targetFormat = AudioFormat(16000f, 16, 1, true, false)
        private fun id(info: Mixer.Info) = "${info.name} | ${info.vendor}"
        fun devices(): List<Pair<String, String>> = AudioSystem.getMixerInfo().filter { info ->
            runCatching { AudioSystem.getMixer(info).targetLineInfo.any { TargetDataLine::class.java.isAssignableFrom(it.lineClass) } }.getOrDefault(false)
        }.map { id(it) to it.name }
        fun rms(pcm: ByteArray): Float {
            if (pcm.size < 2) return 0f
            var energy = 0.0
            for (i in 0 until pcm.size - 1 step 2) {
                val sample = ((pcm[i].toInt() and 255) or (pcm[i + 1].toInt() shl 8)).toShort() / 32768.0
                energy += sample * sample
            }
            return sqrt(energy / (pcm.size / 2)).toFloat()
        }
    }
}
