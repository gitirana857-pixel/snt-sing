package com.sntsing.app

import android.content.Context
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.net.URL

/**
 * Mixer de mídia usando FFmpegKit.
 *
 * Responsável por:
 * 1. Baixar o áudio da música base (instrumental_url do backend)
 * 2. Aplicar ganho (volume) na voz e na música
 * 3. Mixar (amix) as duas trilhas de áudio
 * 4. Muxar o áudio mixado ao vídeo da câmera
 * 5. Queimar legendas karaoke (ASS) no vídeo final
 *
 * ─── FLUXO FFMPEG ───
 *
 * Inputs:
 *   [0] video.mp4        — vídeo da câmera frontal (SEM áudio)
 *   [1] instrumental.aac — áudio da música base (baixado da URL)
 *   [2] voice.aac        — áudio da voz gravada (com efeitos DSP)
 *
 * Filtros:
 *   [1:a] volume=MUSIC_VOL[dB]  → music_adjusted
 *   [2:a] volume=VOICE_VOL[dB]  → voice_adjusted
 *   [music][voice] amix=inputs=2:duration=first → mixed
 *
 * Output:
 *   map 0:v (vídeo original) + map [mixed] (áudio mixado)
 *   + subtitles overlay (se karaokeLyrics fornecido)
 *
 * ─── GERAÇÃO DE KARAOKE ───
 *
 * Se `karaokeLyrics` for fornecido, o mixer gera um arquivo ASS
 * com karaoke timing e queima no vídeo durante a mixagem.
 */
class FFmpegMixer(
    private val context: Context,
    private val videoFile: File,
    private val voiceFile: File,
    private val instrumentalUrl: String
) {

    // ─── Configuração de volume ──────────────────────────
    var voiceVolume: Float = 1.0f    // 0.0 .. 2.0 (multiplicador linear)
    var musicVolume: Float = 0.8f    // 0.0 .. 2.0

    // ─── Configuração de karaoke ─────────────────────────
    var karaokeLyrics: List<KaraokeLine>? = null  // opcional: letras sincronizadas

    // ─── Callbacks ───────────────────────────────────────
    var onProgress: ((percent: Int) -> Unit)? = null
    var onComplete: ((outputFile: File?) -> Unit)? = null
    var onError: ((message: String) -> Unit)? = null

    // ─── Estado ──────────────────────────────────────────
    private var currentSession: FFmpegSession? = null
    private var instrumentalFile: File? = null

    /**
     * Inicia o processo completo:
     * 1. Download do instrumental
     * 2. Geração de ASS karaoke (se letras fornecidas)
     * 3. Mixagem com FFmpeg (com ou sem karaoke)
     * 4. Notifica resultado
     */
    fun start() {
        Thread {
            try {
                // ─── 1. Download do instrumental ──────────
                onProgress?.invoke(5)
                instrumentalFile = downloadInstrumental()

                if (instrumentalFile == null) {
                    onError?.invoke("Falha ao baixar áudio da música base")
                    return@Thread
                }
                onProgress?.invoke(15)

                // ─── 2. Gera ASS karaoke se tiver letras ──
                var assFile: File? = null
                if (!karaokeLyrics.isNullOrEmpty()) {
                    onProgress?.invoke(20)
                    assFile = generateKaraokeAss()
                    Log.d(TAG, "📝 ASS karaoke gerado: ${assFile?.absolutePath}")
                }
                onProgress?.invoke(25)

                // ─── 3. Prepara diretório de saída ───────
                val outputDir = File(context.cacheDir, "recordings/final")
                outputDir.mkdirs()
                val outputFile = File(outputDir, "output_final_${System.currentTimeMillis()}.mp4")

                // ─── 4. Monta e executa FFmpeg ───────────
                val command = buildCommand(outputFile, assFile)
                Log.d(TAG, "🎬 FFmpeg command:\n$command")

                executeFFmpeg(command, outputFile)

            } catch (e: Exception) {
                Log.e(TAG, "❌ Erro na mixagem: ${e.message}", e)
                onError?.invoke("Erro: ${e.message}")
            }
        }.start()
    }

    /**
     * Interrompe o processo.
     */
    fun cancel() {
        currentSession?.cancel()
    }

    // ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ──
    // DOWNLOAD DO INSTRUMENTAL
    // ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ──

    private fun downloadInstrumental(): File? {
        return try {
            val dest = File(context.cacheDir, "recordings/instrumental.aac")
            if (dest.exists()) dest.delete()

            Log.d(TAG, "⬇️ Baixando instrumental: $instrumentalUrl")
            URL(instrumentalUrl).openStream().use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            if (dest.exists() && dest.length() > 0) {
                Log.d(TAG, "✅ Instrumental baixado: ${dest.length()} bytes")
                dest
            } else {
                Log.e(TAG, "❌ Arquivo de instrumental vazio ou não criado")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Falha ao baixar instrumental: ${e.message}")

            // Fallback: usar o próprio arquivo de vídeo como fonte de áudio
            Log.w(TAG, "⚠️ Usando arquivo de vídeo como fallback de áudio")
            videoFile
        }
    }

    // ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ──
    // GERAÇÃO DE ASS KARAOKE
    // ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ──

    /**
     * Gera um arquivo ASS (Advanced SubStation Alpha) com timing karaoke.
     *
     * Formato:
     *   {\k<centisegs>}word — destaca palavra por palavra
     *   {\kf<centisegs>}word — destaque suave (fade progressivo)
     *
     * Timing: cada linha tem start e end em segundos
     */
    private fun generateKaraokeAss(): File {
        val assFile = File(context.cacheDir, "recordings/karaoke.ass")

        assFile.bufferedWriter().use { writer ->
            // ─── Cabeçalho ───────────────────────────────
            writer.writeLine("[Script Info]")
            writer.writeLine("Title: SNT Sing Karaoke")
            writer.writeLine("ScriptType: v4.00+")
            writer.writeLine("PlayResX: 1920")
            writer.writeLine("PlayResY: 1080")
            writer.writeLine("ScaledBorderAndShadow: yes")
            writer.newLine()

            // ─── Styles ──────────────────────────────────
            writer.writeLine("[V4+ Styles]")
            writer.writeLine(
                "Format: Name, Fontname, Fontsize, PrimaryColour, " +
                "SecondaryColour, OutlineColour, BackColour, Bold, Italic, " +
                "Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, " +
                "BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, " +
                "MarginV, Encoding"
            )
            writer.writeLine(
                "Style: Karaoke,Arial,72,&H00FFFFFF,&H0000FF00,&H00000000," +
                "&H80000000,1,0,0,0,100,100,0,0,1,3,2,2,10,10,50,1"
            )
            writer.writeLine(
                "Style: KaraokeSmall,Arial,48,&H00FFFFFF,&H0000FF00,&H00000000," +
                "&H80000000,0,0,0,0,100,100,0,0,1,2,1,2,10,10,30,1"
            )
            writer.newLine()

            // ─── Events ──────────────────────────────────
            writer.writeLine("[Events]")
            writer.writeLine(
                "Format: Layer, Start, End, Style, Name, " +
                "MarginL, MarginR, MarginV, Effect, Text"
            )

            for (line in karaokeLyrics!!) {
                val startTime = formatAssTime(line.startSec)
                val endTime = formatAssTime(line.endSec)
                val style = if (line.isChorus) "Karaoke" else "KaraokeSmall"
                val karaokeText = buildKaraokeText(line.words)
                writer.writeLine(
                    "Dialogue: 0,$startTime,$endTime,$style,,0,0,0,,$karaokeText"
                )
            }
        }

        return assFile
    }

    /**
     * Converte segundos para formato ASS (h:mm:ss.cc).
     * cc = centésimos de segundo (centisegundos × 100 / 1000)
     */
    private fun formatAssTime(seconds: Double): String {
        val totalCs = (seconds * 100).toInt()  // centisegundos totais
        val h = totalCs / 360000
        val m = (totalCs % 360000) / 6000
        val s = (totalCs % 6000) / 100
        val cs = totalCs % 100
        return String.format("%d:%02d:%02d.%02d", h, m, s, cs)
    }

    /**
     * Constrói o texto karaoke com tags {\k<centisegs>} para cada palavra.
     */
    private fun buildKaraokeText(words: List<KaraokeWord>): String {
        val sb = StringBuilder()
        for (word in words) {
            val centisecs = (word.durationSec * 100).toInt()
            sb.append("{\\k$centisecs}")
            // Se tiver cor personalizada, adiciona \c
            if (word.color != null) {
                sb.append("{\\c&H${word.color}&}")
            }
            sb.append(word.text)
            sb.append(" ")
        }
        return sb.toString().trimEnd()
    }

    // ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ──
    // COMANDO FFMPEG
    // ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ──

    /**
     * Monta o comando FFmpeg completo.
     *
     * Se assFile for fornecido, queima as legendas no vídeo.
     */
    private fun buildCommand(outputFile: File, assFile: File?): String {
        val voiceDb = linearToDb(voiceVolume)
        val musicDb = linearToDb(musicVolume)

        val sb = StringBuilder()

        sb.append("-y ")                                          // sobrescreve sem perguntar
        sb.append("-i \"${videoFile.absolutePath}\" ")            // [0] vídeo da câmera
        sb.append("-i \"${instrumentalFile!!.absolutePath}\" ")   // [1] áudio instrumental
        sb.append("-i \"${voiceFile.absolutePath}\" ")            // [2] áudio da voz

        // Filtro complexo: áudio
        sb.append("-filter_complex \"")
        sb.append("[1:a]volume=${musicDb}dB[music];")             // ajusta volume música
        sb.append("[2:a]volume=${voiceDb}dB[voice];")             // ajusta volume voz
        sb.append("[music][voice]amix=inputs=2:duration=first:dropout_transition=2[mixed]")
        sb.append("\" ")

        // Legendas (ASS karaoke) — se fornecido
        if (assFile != null && assFile.exists()) {
            sb.append("-vf \"ass='${assFile.absolutePath}'\" ")
        }

        sb.append("-map 0:v ")                                    // pega vídeo do input 0
        sb.append("-map \"[mixed]\" ")                             // pega áudio mixado
        sb.append("-c:v libx264 -crf 18 ")                        // re-encode vídeo com legendas
        sb.append("-preset ultrafast ")                            // encoding rápido
        sb.append("-c:a aac -b:a 192k ")                          // codifica áudio como AAC 192kbps
        sb.append("-shortest ")                                   // corta na duração menor
        sb.append("\"${outputFile.absolutePath}\"")                // arquivo final

        return sb.toString()
    }

    // ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ──
    // EXECUÇÃO
    // ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ──

    private fun executeFFmpeg(command: String, outputFile: File) {
        onProgress?.invoke(30)

        currentSession = FFmpegKit.executeAsync(command) { session ->
            val returnCode = session.returnCode

            when {
                ReturnCode.isSuccess(returnCode) -> {
                    Log.d(TAG, "✅ FFmpeg concluído: ${outputFile.absolutePath}")
                    Log.d(TAG, "   Tamanho: ${outputFile.length()} bytes")
                    onProgress?.invoke(100)
                    onComplete?.invoke(outputFile)
                }
                ReturnCode.isCancel(returnCode) -> {
                    Log.d(TAG, "⏹️ FFmpeg cancelado")
                    onError?.invoke("Processo cancelado")
                }
                else -> {
                    val logs = session.allLogs.joinToString("\n") { it.message }
                    Log.e(TAG, "❌ FFmpeg falhou:\n$logs")
                    onError?.invoke("Erro na mixagem. Verifique os logs.")
                }
            }
        }
    }

    // ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ──
    // UTILITÁRIOS
    // ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ──

    /**
     * Converte ganho linear (multiplicador) para dB.
     */
    private fun linearToDb(linear: Float): Float {
        if (linear <= 0f) return -40f
        return 20f * kotlin.math.log10(linear)
    }

    companion object {
        private const val TAG = "FFmpegMixer"
    }
}

/**
 * Uma linha de letra de karaoke (geralmente uma frase).
 * @property startSec tempo de início em segundos
 * @property endSec tempo de fim em segundos
 * @property text texto completo da linha
 * @property words lista de palavras com timing individual
 * @property isChorus se é refrão (usar fonte maior)
 */
data class KaraokeLine(
    val startSec: Double,
    val endSec: Double,
    val text: String,
    val words: List<KaraokeWord> = emptyList(),
    val isChorus: Boolean = false
)

/**
 * Uma palavra dentro de uma linha de karaoke.
 * @property text a palavra
 * @property durationSec duração em segundos
 * @property color cor ASS opcional (AABBGGRR, sem &H)
 */
data class KaraokeWord(
    val text: String,
    val durationSec: Double,
    val color: String? = null
)
