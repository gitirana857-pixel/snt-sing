package com.sntsing.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.VideoCapture
import androidx.camera.core.VideoCapture.Recorder
import androidx.camera.core.VideoCapture.Quality
import androidx.camera.core.VideoCapture.QualitySelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.sntsing.app.databinding.ActivityRecordingBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Tela de Gravação (Karaokê) — versão completa com gravação.
 *
 * ─── FLUXO DE GRAVAÇÃO ───
 *
 * 1. Usuário toca em "Gravar"
 * 2. ExoPlayer INICIA (toca o instrumental no fone)
 * 3. CameraX VideoCapture INICIA (grava vídeo, SEM áudio)
 * 4. MediaRecorder INICIA (grava áudio do microfone em .aac)
 * 5. Tudo roda simultaneamente
 * 6. Usuário toca em "Parar"
 * 7. Tudo para ao mesmo tempo
 * 8. Arquivos salvos em: {cacheDir}/recordings/{sessionId}/
 *
 * ─── SINCRONIZAÇÃO ───
 *
 * A sincronização entre vídeo + áudio + playback é feita por:
 * - Timestamp UNIX capturado no início (sessionStartMs)
 * - Os 3 processos disparam no mesmo frame (onClick do botão)
 * - O Player/MediaRecorder/VideoCapture começam em milissegundos
 *   diferentes (~5-50ms de delay) que são compensados no backend
 *   usando o sessionStartMs como referência
 * - Pós-processamento no servidor: FFmpeg concatena vídeo + áudio
 *   usando o timestamp como guia de alinhamento
 */
class RecordingActivity : AppCompatActivity() {

    // ─── Binding ─────────────────────────────────────────
    private lateinit var binding: ActivityRecordingBinding

    // ─── CameraX ─────────────────────────────────────────
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null

    // ─── ExoPlayer ───────────────────────────────────────
    private var exoPlayer: ExoPlayer? = null

    // ─── Audio Effects ────────────────────────────────────
    private var audioFx: AudioEffectsProcessor? = null

    // ─── MediaRecorder (áudio) ───────────────────────────
    private var audioRecorder: MediaRecorder? = null

    // ─── Estado da gravação ──────────────────────────────
    private var isRecording = false
    private var sessionStartMs = 0L
    private var currentSessionDir: File? = null
    private var videoFile: File? = null
    private var audioFile: File? = null

    // ─── Permissões ──────────────────────────────────────
    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    // ─── URL de teste ────────────────────────────────────
    private val TEST_VIDEO_URL =
        "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4"

    // ─── URL da API ────────────────────────────────────
    private val API_BASE = "https://sntcam.studiosnt.com.br/api"
    private var currentSongId: Int = 0                     // setado via intent

    // ─── Mixer ────────────────────────────────────────────
    private var mixer: FFmpegMixer? = null

    // ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ──
    // LIFECYCLE
    // ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ──

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvLoading.visibility = View.VISIBLE
        binding.btnRecord.setOnClickListener { onRecordClick() }
        binding.btnSave.setOnClickListener { onSaveClick() }
        binding.btnPreviewPlay.setOnClickListener { onPreviewPlayClick() }

        // ─── Sliders de efeitos ────────────────────────────
        binding.sbReverb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val level = progress / 100f
                binding.tvReverbValue.text = "${progress}%"
                audioFx?.setReverbLevel(level)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.sbEcho.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val level = progress / 100f
                binding.tvEchoValue.text = "${progress}%"
                audioFx?.setEchoLevel(level)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // ─── Sliders de volume final (mixagem) ────────────
        binding.sbVoiceVol.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val pct = ((progress / 200f) * 100).toInt()
                binding.tvVoiceVolValue.text = "${pct}%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.sbMusicVol.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val pct = ((progress / 200f) * 100).toInt()
                binding.tvMusicVolValue.text = "${pct}%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        checkAndRequestPermissions()
    }

    override fun onDestroy() {
        stopRecording()
        audioFx?.release()
        mixer?.cancel()
        releasePlayer()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        if (!isRecording) exoPlayer?.pause()
    }

    override fun onResume() {
        super.onResume()
        if (!isRecording) exoPlayer?.play()
    }

    // ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ──
    // PERMISSÕES
    // ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ──

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grantResults ->
            val allGranted = grantResults.values.all { it }
            if (allGranted) {
                startCamera()
                initPlayer()
            } else {
                showPermissionDeniedDialog()
            }
        }

    private fun checkAndRequestPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startCamera()
            initPlayer()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permissões necessárias")
            .setMessage("O app precisa da câmera e microfone para gravar.")
            .setPositiveButton("OK") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    // ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ──
    // CAMERAX — Preview + VideoCapture (sem áudio!)
    // ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ──

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()

            // Preview (o que o usuário vê na tela)
            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

            // VideoCapture (gravação sem áudio) — CameraX 1.4 API
            // Nova API: VideoCapture<Recorder> com Recorder.Builder()
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    this, cameraSelector, preview, videoCapture
                )
                Log.d(TAG, "✅ Câmera + VideoCapture<Recorder> prontos")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Erro câmera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ──
    // EXOPLAYER
    // ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ──

    private fun initPlayer() {
        exoPlayer = ExoPlayer.Builder(this).build().also { player ->
            binding.playerView.player = player
            val mediaItem = MediaItem.fromUri(TEST_VIDEO_URL)
            player.setMediaItem(mediaItem)
            player.prepare()

            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_READY -> binding.tvLoading.visibility = View.GONE
                        Player.STATE_BUFFERING -> binding.tvLoading.visibility = View.VISIBLE
                        Player.STATE_ENDED -> { player.seekTo(0); player.play() }
                        else -> {}
                    }
                }
            })

            player.playWhenReady = true
            Log.d(TAG, "✅ ExoPlayer pronto")
        }
    }

    // ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ──
    // GRAVAÇÃO
    // ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ──

    private fun onRecordClick() {
        if (isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    /**
     * Inicia a gravação simultânea de:
     *   ☑ ExoPlayer (playback do instrumental)
     *   ☑ CameraX VideoCapture (vídeo da camera frontal, sem áudio)
     *   ☑ MediaRecorder (áudio do microfone, .aac)
     *
     * ─── LÓGICA DE SINCRONIZAÇÃO ───
     *
     * Todos os 3 são disparados no mesmo frame de UI (este método).
     * O timestamp sessionStartMs (System.currentTimeMillis()) é
     * capturado ANTES de qualquer start(), servindo como referência
     * única para alinhamento no pós-processamento.
     *
     * No backend, o FFmpeg usará este timestamp para sincronizar
     * o vídeo (sem áudio) com o áudio (sem vídeo), já que ambos
     * foram iniciados no mesmo milissegundo de referência.
     */
    private fun startRecording() {
        // ─── 1. Prepara diretório da sessão ──────────────
        val sessionId = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val sessionDir = File(cacheDir, "recordings/$sessionId")
        sessionDir.mkdirs()
        currentSessionDir = sessionDir

        // ─── 2. Define arquivos de saída ─────────────────
        videoFile = File(sessionDir, "video.mp4")
        audioFile = File(sessionDir, "audio.aac")

        // ─── 3. Marca o timestamp de início (REFERÊNCIA) ─
        sessionStartMs = System.currentTimeMillis()
        Log.d(TAG, "🎬 Iniciando gravação — sessionStartMs=$sessionStartMs")

        try {
            // ─── 4. Inicia MediaRecorder (áudio) ─────────
            startAudioRecording()

            // ─── 5. Inicia VideoCapture (vídeo) ──────────
            startVideoRecording()

            // ─── 6. Inicia / Reinicia o ExoPlayer ────────
            exoPlayer?.seekTo(0)
            exoPlayer?.play()

            // ─── 7. Atualiza UI ──────────────────────────
            isRecording = true
            binding.btnRecord.text = "⏹"
            binding.btnRecord.setBackgroundResource(android.R.color.holo_red_dark)
            Log.d(TAG, "✅ Gravação INICIADA")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Falha ao iniciar gravação: ${e.message}")
            Toast.makeText(this, "Erro ao iniciar gravação", Toast.LENGTH_SHORT).show()
            // Limpa arquivos parciais
            cleanupSession()
        }
    }

    /**
     * Inicia o MediaRecorder para capturar áudio do microfone.
     *
     * Formato: AAC (alta qualidade, tamanho compacto).
     * Saída: {cacheDir}/recordings/{sessionId}/audio.aac
     */
    private fun startAudioRecording() {
        audioRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44100)
            setAudioBitRate(128000)       // 128 kbps — qualidade boa para canto
            setAudioChannels(1)           // mono (voz)
            setOutputFile(audioFile!!.absolutePath)
            prepare()
            start()
            Log.d(TAG, "🎤 MediaRecorder iniciado: ${audioFile!!.absolutePath}")
        }
    }

    /**
     * Inicia o VideoCapture do CameraX para gravar vídeo.
     *
     * O vídeo NÃO tem áudio (configurado com AUDIO_NONE).
     * Saída: {cacheDir}/recordings/{sessionId}/video.mp4
     */
    private fun startVideoRecording() {
        val outputOptions = VideoCapture.OutputFileOptions.Builder(videoFile!!).build()

        videoCapture?.startRecording(
            outputOptions,
            cameraExecutor,
            object : VideoCapture.OnVideoSavedCallback {
                override fun onVideoSaved(outputFileResults: VideoCapture.OutputFileResults) {
                    Log.d(TAG, "🎥 Vídeo salvo: ${videoFile?.absolutePath}")
                }

                override fun onError(
                    videoCaptureError: Int,
                    message: String,
                    cause: Throwable?
                ) {
                    Log.e(TAG, "❌ Erro VideoCapture: $message", cause)
                }
            }
        )
        Log.d(TAG, "🎥 VideoCapture<Recorder> iniciado: ${videoFile!!.absolutePath}")
    }

    /**
     * Para a gravação e libera os recursos.
     *
     * Ordem: 1) MediaRecorder 2) VideoCapture 3) ExoPlayer
     * (inverso da inicialização para evitar deadlocks)
     */
    private fun stopRecording() {
        if (!isRecording) return

        Log.d(TAG, "⏹ Parando gravação...")

        // ─── 1. Para o MediaRecorder ─────────────────────
        try {
            audioRecorder?.apply {
                stop()
                release()
            }
            audioRecorder = null
            Log.d(TAG, "⏹ Áudio salvo: ${audioFile?.length() ?: 0} bytes")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao parar áudio: ${e.message}")
        }

        // ─── 2. Para o VideoCapture ──────────────────────
        try {
            videoCapture?.stopRecording()
            Log.d(TAG, "⏹ Vídeo salvo: ${videoFile?.length() ?: 0} bytes")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao parar vídeo: ${e.message}")
        }

        // ─── 3. Pausa o ExoPlayer ───────────────────────
        exoPlayer?.pause()

        // ─── 4. Atualiza UI ──────────────────────────────
        isRecording = false
        binding.btnRecord.text = "Gravar"
        binding.btnRecord.setBackgroundResource(android.R.color.holo_blue_light)
        binding.tvLoading.visibility = View.VISIBLE
        binding.tvLoading.text = "Gravação salva!"

        // ─── 5. Mostra painel de efeitos ──────────────────
        showEffectsPanel()

        // ─── 5. Log do resultado ─────────────────────────
        val durationMs = System.currentTimeMillis() - sessionStartMs
        Log.d(TAG, """
            |✅ Gravação FINALIZADA
            |   Duração: ${durationMs}ms
            |   Vídeo: ${videoFile?.absolutePath}
            |   Áudio: ${audioFile?.absolutePath}
            |   Tamanhos: vídeo=${videoFile?.length() ?: 0}B / áudio=${audioFile?.length() ?: 0}B
        """.trimMargin())

        Toast.makeText(
            this,
            "Gravação salva! (${durationMs / 1000}s)",
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * Remove arquivos da sessão em caso de erro.
     */
    private fun cleanupSession() {
        currentSessionDir?.deleteRecursively()
        videoFile = null
        audioFile = null
        currentSessionDir = null
    }

    private fun releasePlayer() {
        exoPlayer?.run { stop(); release() }
        exoPlayer = null
    }

    // ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ──
    // PAINEL DE EFEITOS (pós-gravação)
    // ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ──

    /**
     * Exibe o painel de efeitos e inicializa o AudioEffectsProcessor.
     */
    private fun showEffectsPanel() {
        audioFile?.let { file ->
            audioFx = AudioEffectsProcessor(this, file)
            binding.effectsPanel.visibility = View.VISIBLE
            binding.btnSave.visibility = View.VISIBLE
            Log.d(TAG, "🎛️ Painel de efeitos aberto: ${file.name}")
        }
    }

    /**
     * Toca / pausa a prévia do áudio com os efeitos atuais.
     */
    private fun onPreviewPlayClick() {
        audioFx?.let { fx ->
            if (fx.getDuration() == 0) {
                fx.startPlayback()
                binding.btnPreviewPlay.text = "⏸"
            } else if (fx.getCurrentPosition() < fx.getDuration()) {
                fx.togglePlayback()
                binding.btnPreviewPlay.text =
                    if (fx.getCurrentPosition() > 0) "⏸" else "▶️"
            } else {
                fx.startPlayback()
                binding.btnPreviewPlay.text = "⏸"
            }
        }
    }

    /**
     * Salva e renderiza o vídeo final com FFmpeg.
     *
     * 1. Pausa a prévia de áudio
     * 2. Cria FFmpegMixer com os arquivos da sessão
     * 3. Aplica volumes dos sliders
     * 4. Executa mixagem em background
     * 5. Exibe progresso na UI
     */
    private fun onSaveClick() {
        val vidFile = videoFile ?: return
        val audFile = audioFile ?: return

        // Para a prévia de áudio
        audioFx?.stopPlayback()
        binding.btnPreviewPlay.text = "▶️"

        // Mostra status
        binding.tvMixStatus.visibility = View.VISIBLE
        binding.tvMixStatus.text = "🔄 Preparando mixagem..."
        binding.btnSave.isEnabled = false

        // Cria o mixer com suporte a karaoke
        mixer = FFmpegMixer(
            context = this,
            videoFile = vidFile,
            voiceFile = audFile,
            instrumentalUrl = TEST_VIDEO_URL  // ← usar instrumental_url do backend
        ).apply {
            // Volume dos sliders (0..200 → 0..2.0 linear)
            voiceVolume = binding.sbVoiceVol.progress / 100f
            musicVolume = binding.sbMusicVol.progress / 100f

            // Se tiver letras, adiciona karaoke ASS
            // (em produção: buscar lyrics da API /api/lyrics.php?song_id=X
            //  e converter para List<KaraokeLine>)
            // Exemplo com letras estáticas para teste:
            karaokeLyrics = listOf(
                KaraokeLine(0.0, 4.0, "Twinkle twinkle little star",
                    listOf(
                        KaraokeWord("Twinkle", 1.0),
                        KaraokeWord("twinkle", 1.0),
                        KaraokeWord("little", 1.0),
                        KaraokeWord("star", 1.0)
                    ), isChorus = false
                ),
                KaraokeLine(4.0, 8.0, "How I wonder what you are",
                    listOf(
                        KaraokeWord("How", 0.8),
                        KaraokeWord("I", 0.4),
                        KaraokeWord("wonder", 1.2),
                        KaraokeWord("what", 0.8),
                        KaraokeWord("you", 0.4),
                        KaraokeWord("are", 1.2)
                    ), isChorus = false
                ),
                KaraokeLine(8.0, 12.0, "Up above the world so high",
                    listOf(
                        KaraokeWord("Up", 0.5),
                        KaraokeWord("above", 1.0),
                        KaraokeWord("the", 0.5),
                        KaraokeWord("world", 1.0),
                        KaraokeWord("so", 0.5),
                        KaraokeWord("high", 1.0)
                    ), isChorus = false
                ),
                KaraokeLine(12.0, 16.0, "Like a diamond in the sky",
                    listOf(
                        KaraokeWord("Like", 0.5),
                        KaraokeWord("a", 0.3),
                        KaraokeWord("diamond", 1.2),
                        KaraokeWord("in", 0.5),
                        KaraokeWord("the", 0.3),
                        KaraokeWord("sky", 1.2)
                    ), isChorus = false
                ),
            )

            // Callbacks
            onProgress = { percent ->
                runOnUiThread {
                    binding.tvMixStatus.text = when {
                        percent < 20 -> "⬇️ Baixando música base..."
                        percent < 30 -> "🎬 Iniciando FFmpeg..."
                        percent < 100 -> "🎛️ Mixando áudio e vídeo... $percent%"
                        else -> "✅ Renderizando..."
                    }
                }
            }

            onComplete = { outputFile ->
                runOnUiThread {
                    binding.tvMixStatus.text = "✅ Vídeo final pronto!"
                    binding.btnSave.text = "✅ Salvo"
                    Toast.makeText(
                        this@RecordingActivity,
                        "🎉 Vídeo salvo em: ${outputFile?.name}",
                        Toast.LENGTH_LONG
                    ).show()
                    Log.d(TAG, "✅ Mixagem concluída: ${outputFile?.absolutePath}")
                }
            }

            onError = { message ->
                runOnUiThread {
                    binding.tvMixStatus.text = "❌ $message"
                    binding.btnSave.isEnabled = true
                    binding.btnSave.text = "💾 Tentar novamente"
                }
            }

            start()
        }
    }

    companion object {
        private const val TAG = "RecordingActivity"
    }
}
