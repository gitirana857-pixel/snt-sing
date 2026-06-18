package com.sntsing.app

import android.content.Context
import android.media.MediaPlayer
import android.media.audiofx.EnvironmentalReverb
import android.media.audiofx.PresetReverb
import android.util.Log
import java.io.File

/**
 * Processador de efeitos de áudio em tempo real.
 *
 * Conecta-se a um MediaPlayer já em execução e aplica
 * efeitos via android.media.audiofx:
 *
 * ─── Reverb (PresetReverb + EnvironmentalReverb) ───
 *   Simula ambientes (sala, palco, corredor) na voz.
 *   Usa PresetReverb como base (mais leve) e pode alternar
 *   para EnvironmentalReverb para controle fino.
 *
 * ─── Echo / Delay ───
 *   O Android AudioFX não possui Echo nativo.
 *   Duas abordagens:
 *   a) **Ambiental**: usar EnvironmentalReverb com decay
 *      alto e diffusion baixo para simular eco
 *   b) **Externo**: processamento offline com FFmpeg
 *      (recomendado para produção — mixa áudio + delay)
 *   Esta classe implementa a opção (a) por ser 100% nativa.
 *
 * Uso:
 *   val fx = AudioEffectsProcessor(context, audioFile)
 *   fx.setReverbLevel(0.5f)   // 0.0 a 1.0
 *   fx.setEchoLevel(0.3f)     // 0.0 a 1.0
 *   fx.startPlayback()
 *   ...
 *   fx.release()
 */
class AudioEffectsProcessor(
    private val context: Context,
    private val audioFile: File
) {
    // ─── MediaPlayer ─────────────────────────────────────
    private var mediaPlayer: MediaPlayer? = null

    // ─── AudioFX ─────────────────────────────────────────
    private var presetReverb: PresetReverb? = null
    private var envReverb: EnvironmentalReverb? = null

    // ─── Estado ──────────────────────────────────────────
    private var audioSessionId = 0
    private var currentReverbLevel = 0f  // 0..1
    private var currentEchoLevel = 0f    // 0..1
    private var isPlaying = false

    // ─── Callback ────────────────────────────────────────
    var onCompletionListener: (() -> Unit)? = null

    /**
     * Inicia a reprodução do áudio gravado com os efeitos atuais.
     */
    fun startPlayback(): Boolean {
        return try {
            release()

            mediaPlayer = MediaPlayer().apply {
                setDataSource(audioFile.absolutePath)
                prepare()
                audioSessionId = audioSessionId
                    .takeIf { it != 0 }
                    .also { session ->
                        if (session == null) audioSessionId = audioSessionId
                    }

                // Conecta efeitos à sessão do MediaPlayer
                setupEffects(audioSessionId)

                setOnCompletionListener {
                    isPlaying = false
                    onCompletionListener?.invoke()
                }

                start()
            }

            isPlaying = true
            Log.d(TAG, "▶️ Playback iniciado: ${audioFile.name}")
            true

        } catch (e: Exception) {
            Log.e(TAG, "❌ Erro ao iniciar playback: ${e.message}")
            false
        }
    }

    /**
     * Pausa / resume a reprodução.
     */
    fun togglePlayback() {
        mediaPlayer?.let { mp ->
            if (mp.isPlaying) {
                mp.pause()
                isPlaying = false
            } else {
                mp.start()
                isPlaying = true
            }
        }
    }

    /**
     * Para e volta ao início.
     */
    fun stopPlayback() {
        mediaPlayer?.apply {
            stop()
            seekTo(0)
            isPlaying = false
        }
    }

    /**
     * Retorna a posição atual em milissegundos.
     */
    fun getCurrentPosition(): Int = mediaPlayer?.currentPosition ?: 0

    /**
     * Retorna a duração total em milissegundos.
     */
    fun getDuration(): Int = mediaPlayer?.duration ?: 0

    // ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ──
    // SLIDERS — chamados pela UI
    // ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ──

    /**
     * Ajusta o nível de Reverb (0.0 = seco, 1.0 = máximo).
     *
     * Mapeia para EnvironmentalReverb:
     * - Decay Time: 0.5s → 7.0s
     * - Diffusion: 100 → 1000
     * - Density: 100 → 1000
     */
    fun setReverbLevel(level: Float) {
        currentReverbLevel = level.coerceIn(0f, 1f)
        applyReverb()
    }

    /**
     * Ajusta o nível de Echo / Delay (0.0 = seco, 1.0 = máximo).
     *
     * Simula eco usando EnvironmentalReverb:
     * - Decay HFRatio: controla quanto as frequências altas
     *   se dissipam a cada repetição (eco mais suave)
     * - ReflectionsDelay + ReverbDelay: simula o delay do eco
     * - Para eco real (delay+repetições), é necessário
     *   processamento offline com FFmpeg ou biblioteca DSP
     */
    fun setEchoLevel(level: Float) {
        currentEchoLevel = level.coerceIn(0f, 1f)
        applyReverb()
    }

    // ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ──
    // CONFIGURAÇÃO DOS EFEITOS
    // ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ──

    /**
     * Configura os efeitos na sessão de áudio do MediaPlayer.
     *
     * Usamos EnvironmentalReverb (controle fino) com fallback
     * para PresetReverb (mais compatível).
     */
    private fun setupEffects(sessionId: Int) {
        try {
            // Tenta EnvironmentalReverb primeiro
            envReverb = EnvironmentalReverb(0, sessionId).apply {
                enabled = true
            }
            Log.d(TAG, "✅ EnvironmentalReverb criado")
            applyReverb()
            return
        } catch (e: Exception) {
            Log.w(TAG, "EnvironmentalReverb indisponível: ${e.message}")
        }

        try {
            // Fallback: PresetReverb (mais simples)
            presetReverb = PresetReverb(0, sessionId).apply {
                enabled = true
            }
            Log.d(TAG, "✅ PresetReverb criado (fallback)")
            applyPresetReverb()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Nenhum reverb disponível: ${e.message}")
        }
    }

    /**
     * Aplica as configurações ao EnvironmentalReverb.
     *
     * Mapeamento Reverb (0..1):
     *   level 0.0 → decay 500ms, diffusion/density mínimos (seco)
     *   level 0.5 → decay 2000ms, diffusion 500 (sala média)
     *   level 1.0 → decay 7000ms, diffusion 1000 (catedral)
     *
     * Mapeamento Echo (0..1):
     *   level 0.0 → reflectionsDelay 0ms, reverbDelay 0ms
     *   level 0.5 → reflectionsDelay 20ms, reverbDelay 40ms
     *   level 1.0 → reflectionsDelay 40ms, reverbDelay 80ms
     */
    private fun applyReverb() {
        envReverb?.let { reverb ->
            // ─── Reverb ──────────────────────────────────
            val decayMs = (500 + (currentReverbLevel * 6500)).toInt()      // 500..7000ms
            val diffusion = (100 + (currentReverbLevel * 900)).toInt()      // 100..1000
            val density = (100 + (currentReverbLevel * 900)).toInt()         // 100..1000

            reverb.decayTime = decayMs
            reverb.diffusion = diffusion.toShort()
            reverb.density = density.toShort()

            // ─── Echo (simulado) ─────────────────────────
            val refDelayMs = (currentEchoLevel * 40).toInt()               // 0..40ms
            val revDelayMs = (currentEchoLevel * 80).toInt()               // 0..80ms
            val decayHfRatio = 500 + ((1f - currentEchoLevel) * 500).toInt() // eco agudo ↓

            reverb.reflectionsDelay = refDelayMs
            reverb.reverbDelay = revDelayMs
            reverb.decayHFRatio = decayHfRatio.toShort()

            Log.d(TAG, """
                |🎛️ Reverb aplicado:
                |   decay=${decayMs}ms diffusion=$diffusion density=$density
                |   refDelay=${refDelayMs}ms revDelay=${revDelayMs}ms
            """.trimMargin())
        }
    }

    /**
     * Fallback: PresetReverb (menos parâmetros, mais compatível).
     * Mapeia level para presets.
     */
    private fun applyPresetReverb() {
        presetReverb?.let { pr ->
            val preset = when {
                currentReverbLevel < 0.2f -> PresetReverb.PRESET_NONE
                currentReverbLevel < 0.4f -> PresetReverb.PRESET_SMALLROOM
                currentReverbLevel < 0.6f -> PresetReverb.PRESET_MEDIUMROOM
                currentReverbLevel < 0.8f -> PresetReverb.PRESET_LARGEROOM
                else                       -> PresetReverb.PRESET_PLATE
            }
            pr.preset = preset
            Log.d(TAG, "🎛️ PresetReverb: preset=$preset (level=${currentReverbLevel})")
        }
    }

    // ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ──
    // LIMPEZA
    // ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ──

    fun release() {
        try {
            mediaPlayer?.apply { if (isPlaying) stop(); release() }
            envReverb?.release()
            presetReverb?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao liberar: ${e.message}")
        }
        mediaPlayer = null
        envReverb = null
        presetReverb = null
        isPlaying = false
        Log.d(TAG, "🧹 AudioEffectsProcessor liberado")
    }

    companion object {
        private const val TAG = "AudioEffects"
    }
}
