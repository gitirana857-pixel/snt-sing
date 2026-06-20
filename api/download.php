<?php
/**
 * SNT Sing - API: Download de Instrumental do YouTube
 * 
 * POST /api/download.php
 * 
 * Baixa o vídeo/áudio do YouTube e extrai o instrumental (AAC/MP3).
 * Usa yt-dlp + FFmpeg para extrair o áudio.
 * 
 * ─── PARÂMETROS ───
 * 
 *   song_id (int, obrigatório) - ID da música no catálogo
 *   force   (bool, opcional)   - true = redownload mesmo se já existir
 * 
 * ─── FLUXO ───
 * 
 * 1. Busca a URL do instrumental no banco (campo instrumental_url)
 * 2. Baixa o áudio com yt-dlp (formato AAC/MP3, 192kbps)
 * 3. Salva em /storage/instrumentals/{song_id}/
 * 4. Atualiza instrumental_url no banco para URL local
 * 5. Retorna a URL do arquivo baixado
 * 
 * ─── RESPOSTA ───
 * 
 *   200: { "success": true, "url": "/storage/instrumentals/1/instrumental.m4a" }
 *   400: { "success": false, "message": "..." }
 */

require_once __DIR__ . '/config.php';

setupCORS();

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    jsonError('Método não permitido. Use POST.', 405);
}

$songId = (int) ($_POST['song_id'] ?? $_GET['song_id'] ?? 0);
$force  = ($_POST['force'] ?? 'false') === 'true';

if ($songId <= 0) {
    jsonError('Parâmetro song_id é obrigatório.', 400);
}

$pdo = getDB();

// ─── Busca música ────────────────────────────────────────
$stmt = $pdo->prepare("
    SELECT s.id, s.title, s.instrumental_url, s.duration_secs, a.name AS artist_name
    FROM songs s
    INNER JOIN artists a ON a.id = s.artist_id
    WHERE s.id = :id AND s.is_active = 1
");
$stmt->execute([':id' => $songId]);
$song = $stmt->fetch();

if (!$song) {
    jsonError('Música não encontrada.', 404);
}

$youtubeUrl = $song['instrumental_url'];

// ─── Verifica se já baixou ───────────────────────────────
$storageDir = __DIR__ . '/../storage/instrumentals/' . $songId;
$localFile  = $storageDir . '/instrumental.m4a';

if (!$force && file_exists($localFile) && filesize($localFile) > 0) {
    $publicUrl = "/storage/instrumentals/{$songId}/instrumental.m4a";
    
    // Se o banco ainda aponta pro YouTube, atualiza pra URL local
    $stmt = $pdo->prepare("UPDATE songs SET instrumental_url = :url WHERE id = :id");
    $stmt->execute([':url' => $publicUrl, ':id' => $songId]);
    
    jsonResponse([
        'success' => true,
        'url'     => $publicUrl,
        'cached'  => true,
        'message' => 'Instrumental já existe em cache.',
    ]);
}

// ─── Extrai ID do YouTube ─────────────────────────────────
$videoId = extractYoutubeId($youtubeUrl);
if (!$videoId) {
    jsonError('URL do YouTube inválida na base de dados.', 400);
}

// ─── Cria diretório ──────────────────────────────────────
if (!is_dir($storageDir)) {
    mkdir($storageDir, 0755, true);
}

// Remove arquivo antigo se forçar redownload
if ($force && file_exists($localFile)) {
    @unlink($localFile);
}

// ─── Executa yt-dlp ──────────────────────────────────────
$outputTemplate = $storageDir . '/instrumental.%(ext)s';

// Comando: extrai melhor áudio, converte pra AAC/M4A
$cmd = sprintf(
    'yt-dlp -x --audio-format m4a --audio-quality 192k ' .
    '-o %s %s 2>&1',
    escapeshellarg($outputTemplate),
    escapeshellarg($youtubeUrl)
);

exec($cmd, $outputLines, $exitCode);

$output = implode("\n", $outputLines);

if ($exitCode !== 0) {
    jsonError("Falha ao baixar áudio. YouTube pode ter bloqueado. Log:\n" . substr($output, 0, 500), 500);
}

// ─── Procura o arquivo gerado ────────────────────────────
$foundFile = null;
$files = glob($storageDir . '/instrumental.*');
foreach ($files as $f) {
    if (filesize($f) > 0) {
        $foundFile = $f;
        break;
    }
}

if (!$foundFile) {
    jsonError('Arquivo de áudio não encontrado após download.', 500);
}

// Renomeia para .m4a se necessário
$finalFile = $storageDir . '/instrumental.m4a';
if ($foundFile !== $finalFile) {
    rename($foundFile, $finalFile);
}

$publicUrl = "/storage/instrumentals/{$songId}/instrumental.m4a";

// ─── Atualiza instrumental_url no banco ──────────────────
$stmt = $pdo->prepare("UPDATE songs SET instrumental_url = :url WHERE id = :id");
$stmt->execute([':url' => $publicUrl, ':id' => $songId]);

// ─── Tenta extrair duração com ffprobe ───────────────────
$duration = (int) $song['duration_secs'];
if ($duration <= 0) {
    $probeCmd = sprintf(
        'ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 %s 2>/dev/null',
        escapeshellarg($finalFile)
    );
    $probeOutput = trim(shell_exec($probeCmd));
    if (is_numeric($probeOutput)) {
        $duration = (int) round((float) $probeOutput);
        $stmt = $pdo->prepare("UPDATE songs SET duration_secs = :dur WHERE id = :id");
        $stmt->execute([':dur' => $duration, ':id' => $songId]);
    }
}

jsonResponse([
    'success'       => true,
    'url'           => $publicUrl,
    'cached'        => false,
    'duration_secs' => $duration,
    'size_bytes'    => filesize($finalFile),
    'message'       => 'Instrumental baixado com sucesso!',
]);

// ═══════════════════════════════════════════════════════════
// FUNÇÕES AUXILIARES
// ═══════════════════════════════════════════════════════════

/**
 * Extrai o ID do vídeo do YouTube de vários formatos de URL.
 */
function extractYoutubeId(string $url): ?string
{
    // youtube.com/watch?v=ID
    if (preg_match('/[?&]v=([a-zA-Z0-9_-]{11})/', $url, $m)) {
        return $m[1];
    }
    // youtu.be/ID
    if (preg_match('/youtu\.be\/([a-zA-Z0-9_-]{11})/', $url, $m)) {
        return $m[1];
    }
    // youtube.com/embed/ID
    if (preg_match('/\/embed\/([a-zA-Z0-9_-]{11})/', $url, $m)) {
        return $m[1];
    }
    return null;
}
