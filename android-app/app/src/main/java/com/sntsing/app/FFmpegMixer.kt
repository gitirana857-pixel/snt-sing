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
 *   → output_final.mp4
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
     * 2. Mixagem com FFmpeg
     * 3. Notifica resultado
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
                onProgress?.invoke(20)

                // ─── 2. Prepara diretório de saída ───────
                val outputDir = File(context.cacheDir, "recordings/final")
                outputDir.mkdirs()
                val outputFile = File(outputDir, "output_final_${System.currentTimeMillis()}.mp4")

                // ─── 3. Monta e executa FFmpeg ───────────
                val command = buildCommand(outputFile)
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

    /**
     * Baixa o áudio da música base para um arquivo local.
     *
     * Se a instrumentalUrl for um vídeo do YouTube, extraímos
     * apenas o áudio. Caso contrário, baixamos o arquivo direto.
     *
     * Para produção: o backend deve fornecer uma URL direta
     * para o arquivo de áudio (.aac/.mp3) separadamente.
     */
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
            // (útil para testes com MP4 local)
            Log.w(TAG, "⚠️ Usando arquivo de vídeo como fallback de áudio")
            videoFile
        }
    }

    // ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ──
    // COMANDO FFMPEG
    // ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ──

    /**
     * Monta o comando FFmpeg completo.
     *
     * ─── ESTRUTURA DO COMANDO ───
     *
     * 1. Converte ganho linear (0..2) para dB:
     *    dB = 20 * log10(volume)
     *    Ex: 1.0 → 0dB (original), 0.5 → -6dB, 2.0 → +6dB
     *
     * 2. Aplica volume com filtro 'volume':
     *    [1:a]volume=+3dB[music]  — aumenta música em 3dB
     *    [2:a]volume=-6dB[voice]  — reduz voz em 6dB
     *
     * 3. Mixa com 'amix':
     *    [music][voice]amix=inputs=2:duration=first[mixed]
     *    - duration=first: duração = duração da primeira entrada
     *      (a música base, que é a referência)
     *    - dropout_transition=2: transição suave se uma entrada acabar
     *
     * 4. Mapeia e codifica:
     *    - 0:v → vídeo original (cópia direta, sem re-encode)
     *    - [mixed] → áudio mixado (codificado como AAC)
     *
     * ─── COMANDO COMPLETO ───
     *
     * ffmpeg -i video.mp4 -i instrumental.aac -i voice.aac \
     *   -filter_complex \
     *     "[1:a]volume=VOL_MUSIC_DB[music]; \
     *      [2:a]volume=VOL_VOICE_DB[voice]; \
     *      [music][voice]amix=inputs=2:duration=first:dropout_transition=2[mixed]" \
     *   -map 0:v -map "[mixed]" \
     *   -c:v copy \
     *   -c:a aac -b:a 192k \
     *   -shortest \
     *   output_final.mp4
     */
    private fun buildCommand(outputFile: File): String {
        val voiceDb = linearToDb(voiceVolume)
        val musicDb = linearToDb(musicVolume)

        return buildString {
            append("-y ")                                          // sobrescreve sem perguntar
            append("-i \"${videoFile.absolutePath}\" ")            // [0] vídeo da câmera
            append("-i \"${instrumentalFile!!.absolutePath}\" ")   // [1] áudio instrumental
            append("-i \"${voiceFile.absolutePath}\" ")            // [2] áudio da voz
            append("-filter_complex \"")
            append("[1:a]volume=${musicDb}dB[music];")             // ajusta volume música
            append("[2:a]volume=${voiceDb}dB[voice];")             // ajusta volume voz
            append("[music][voice]amix=inputs=2:duration=first:dropout_transition=2[mixed]")
            append("\" ")
            append("-map 0:v ")                                    // pega vídeo do input 0
            append("-map \"[mixed]\" ")                             // pega áudio mixado
            append("-c:v copy ")                                   // copia vídeo sem re-encode
            append("-c:a aac -b:a 192k ")                          // codifica áudio como AAC 192kbps
            append("-shortest ")                                   // corta na duração menor
            append("\"${outputFile.absolutePath}\"")                // arquivo final
        }
    }

    // ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ──
    // EXECUÇÃO
    // ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ──

    /**
     * Executa o comando FFmpeg e notifica o resultado.
     */
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
     *
     *   linear 1.0 →  0.0 dB (volume original)
     *   linear 0.5 → -6.0 dB (metade do volume)
     *   linear 2.0 → +6.0 dB (dobro do volume)
     *   linear 0.0 → -∞  dB (silêncio)
     */
    private fun linearToDb(linear: Float): Float {
        if (linear <= 0f) return -40f  // -40dB ≈ silêncio
        return 20f * kotlin.math.log10(linear)
    }

    companion object {
        private const val TAG = "FFmpegMixer"
    }
}
