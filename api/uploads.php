<?php
/**
 * SNT Sing - API: Upload de Gravações
 * 
 * POST /api/uploads.php
 * Content-Type: multipart/form-data
 * 
 * Recebe os arquivos de gravação e metadados do app Android.
 * Processa a mixagem final no servidor (futuro) ou apenas armazena.
 * 
 * ─── PARÂMETROS (multipart/form-data) ───
 * 
 *   video       (file, obrigatório)  - video.mp4 (câmera frontal, sem áudio)
 *   audio       (file, obrigatório)  - audio.aac (voz gravada do microfone)
 *   song_id     (int,  obrigatório)  - ID da música do catálogo
 *   user_id     (int,  opcional)     - ID do usuário (se logado)
 *   session_ts  (int,  obrigatório)  - timestamp UNIX do início da gravação
 *   voice_vol   (float, opcional)    - volume da voz aplicado (0.0 - 2.0)
 *   music_vol   (float, opcional)    - volume da música aplicado (0.0 - 2.0)
 *   reverb      (float, opcional)    - nível de reverb (0.0 - 1.0)
 *   echo        (float, opcional)    - nível de echo (0.0 - 1.0)
 * 
 * ─── RESPOSTA (JSON) ───
 * 
 *   201 Created:
 *     { "success": true, "recording_id": 42, "url": "/recordings/42.mp4" }
 * 
 *   400/500:
 *     { "success": false, "message": "..." }
 */

require_once __DIR__ . '/config.php';

setupCORS();

// ─── Valida método HTTP ───────────────────────────────────
if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    jsonError('Método não permitido. Use POST.', 405);
}

// ─── Valida arquivos enviados ────────────────────────────
$videoFile = $_FILES['video'] ?? null;
$audioFile = $_FILES['audio'] ?? null;

if (!$videoFile || $videoFile['error'] !== UPLOAD_ERR_OK) {
    jsonError('Arquivo de vídeo é obrigatório e deve ser enviado corretamente.', 400);
}
if (!$audioFile || $audioFile['error'] !== UPLOAD_ERR_OK) {
    jsonError('Arquivo de áudio é obrigatório e deve ser enviado corretamente.', 400);
}

// ─── Valida campos ───────────────────────────────────────
$songId    = (int) ($_POST['song_id'] ?? 0);
$sessionTs = (int) ($_POST['session_ts'] ?? 0);

if ($songId <= 0) {
    jsonError('song_id inválido ou não informado.', 400);
}
if ($sessionTs <= 0) {
    jsonError('session_ts inválido ou não informado.', 400);
}

$userId   = (int) ($_POST['user_id'] ?? 0);
$voiceVol = (float) ($_POST['voice_vol'] ?? 1.0);
$musicVol = (float) ($_POST['music_vol'] ?? 0.8);
$reverb   = (float) ($_POST['reverb'] ?? 0.0);
$echo     = (float) ($_POST['echo'] ?? 0.0);

// ─── Valida extensões ────────────────────────────────────
$allowedVideoExts = ['mp4', 'webm', '3gp'];
$allowedAudioExts = ['aac', 'mp3', 'm4a', 'ogg', 'wav'];

$videoExt = strtolower(pathinfo($videoFile['name'], PATHINFO_EXTENSION));
$audioExt = strtolower(pathinfo($audioFile['name'], PATHINFO_EXTENSION));

if (!in_array($videoExt, $allowedVideoExts)) {
    jsonError("Formato de vídeo não suportado: .$videoExt", 400);
}
if (!in_array($audioExt, $allowedAudioExts)) {
    jsonError("Formato de áudio não suportado: .$audioExt", 400);
}

// ─── Tamanho máximo: 500MB vídeo, 50MB áudio ────────────
$maxVideoSize = 500 * 1024 * 1024;  // 500 MB
$maxAudioSize = 50 * 1024 * 1024;   // 50 MB

if ($videoFile['size'] > $maxVideoSize) {
    jsonError('Vídeo muito grande. Máximo: 500MB.', 413);
}
if ($audioFile['size'] > $maxAudioSize) {
    jsonError('Áudio muito grande. Máximo: 50MB.', 413);
}

// ─── Conecta ao banco ────────────────────────────────────
$pdo = getDB();

// ─── Verifica se a música existe ─────────────────────────
$stmt = $pdo->prepare("SELECT id, title FROM songs WHERE id = :id AND is_active = 1");
$stmt->execute([':id' => $songId]);
$song = $stmt->fetch();

if (!$song) {
    jsonError('Música não encontrada ou indisponível.', 404);
}

// ─── Cria diretório de armazenamento ─────────────────────
$recDir = __DIR__ . '/../storage/recordings/' . date('Y/m/d');
if (!is_dir($recDir)) {
    mkdir($recDir, 0755, true);
}

// ─── Gera nome único para a gravação ─────────────────────
$recUid = bin2hex(random_bytes(8));  // 16 caracteres hex
$finalName = "{$recUid}_{$sessionTs}";

// ─── Move os arquivos para o storage ─────────────────────
$videoDest = "{$recDir}/{$finalName}.{$videoExt}";
$audioDest = "{$recDir}/{$finalName}.{$audioExt}";

if (!move_uploaded_file($videoFile['tmp_name'], $videoDest)) {
    jsonError('Falha ao salvar arquivo de vídeo.', 500);
}
if (!move_uploaded_file($audioFile['tmp_name'], $audioDest)) {
    // Remove vídeo que já foi salvo
    @unlink($videoDest);
    jsonError('Falha ao salvar arquivo de áudio.', 500);
}

// ─── Insere registro no banco ────────────────────────────
$stmt = $pdo->prepare("
    INSERT INTO recordings (
        user_id, song_id, session_start_ts,
        video_path, audio_path,
        voice_volume, music_volume, reverb_level, echo_level,
        video_size, audio_size, duration_secs,
        created_at
    ) VALUES (
        :user_id, :song_id, :session_ts,
        :video_path, :audio_path,
        :voice_vol, :music_vol, :reverb, :echo,
        :video_size, :audio_size, 0,
        NOW()
    )
");

$stmt->execute([
    ':user_id'     => $userId > 0 ? $userId : null,
    ':song_id'     => $songId,
    ':session_ts'  => $sessionTs,
    ':video_path'  => $videoDest,
    ':audio_path'  => $audioDest,
    ':voice_vol'   => $voiceVol,
    ':music_vol'   => $musicVol,
    ':reverb'      => $reverb,
    ':echo'        => $echo,
    ':video_size'  => $videoFile['size'],
    ':audio_size'  => $audioFile['size'],
]);

$recordingId = (int) $pdo->lastInsertId();

// ─── Resposta de sucesso ─────────────────────────────────
$publicUrl = "/storage/recordings/" . date('Y/m/d') . "/{$finalName}.{$videoExt}";

jsonResponse([
    'success'      => true,
    'recording_id' => $recordingId,
    'url'          => $publicUrl,
    'message'      => 'Gravação enviada com sucesso!',
], 201);
