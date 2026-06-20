<?php
/**
 * SNT Sing - API: Letras com Timing Karaoke
 * 
 * GET /api/lyrics.php?song_id=1
 * 
 * Retorna a letra da música no formato karaoke, com cada linha
 * e palavra parseadas para timing ASS.
 * 
 * ─── FORMATO DA LETRA NO BANCO ───
 * 
 * O campo `lyrics` na tabela `songs` pode conter:
 * 
 * 1. Texto SIMPLES (sem timing):
 *    Twinkle twinkle little star
 *    How I wonder what you are
 *    Up above the world so high
 * 
 * 2. Formato com TIMING (LRC-like, opcional):
 *    [00:01.00]Twinkle twinkle little star
 *    [00:05.00]How I wonder what you are
 *    [00:09.00]Up above the world so high
 * 
 * 3. Se não houver timing, o servidor distribui
 *    o texto uniformemente pela duração da música.
 * 
 * ─── REPETIÇÃO DE REFRÃO ───
 * 
 * Marque o refrão com [Chorus] antes da linha:
 *    [Chorus]
 *    [00:10.00]Never gonna give you up
 *    [00:13.00]Never gonna let you down
 * 
 * ─── RESPOSTA (JSON) ───
 * 
 * {
 *     "success": true,
 *     "song_id": 1,
 *     "title": "Música Exemplo",
 *     "artist": "Artista",
 *     "duration_secs": 240,
 *     "lines": [
 *         {
 *             "start": 1.0,
 *             "end": 5.0,
 *             "text": "Twinkle twinkle little star",
 *             "is_chorus": false,
 *             "words": [
 *                 {"text": "Twinkle", "start": 1.0, "duration": 1.0},
 *                 {"text": "twinkle", "start": 2.0, "duration": 1.0},
 *                 {"text": "little",  "start": 3.0, "duration": 1.0},
 *                 {"text": "star",    "start": 4.0, "duration": 1.0}
 *             ]
 *         }
 *     ]
 * }
 */

require_once __DIR__ . '/config.php';

setupCORS();

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    jsonError('Método não permitido. Use GET.', 405);
}

$songId = (int) ($_GET['song_id'] ?? 0);
if ($songId <= 0) {
    jsonError('Parâmetro song_id é obrigatório.', 400);
}

$pdo = getDB();

// ─── Busca música ────────────────────────────────────────
$stmt = $pdo->prepare("
    SELECT
        s.id, s.title, s.duration_secs, s.lyrics,
        a.name AS artist_name
    FROM songs s
    INNER JOIN artists a ON a.id = s.artist_id
    WHERE s.id = :id AND s.is_active = 1
");
$stmt->execute([':id' => $songId]);
$song = $stmt->fetch();

if (!$song) {
    jsonError('Música não encontrada.', 404);
}

// ─── Parse da letra ──────────────────────────────────────
$lines = parseLyrics(
    $song['lyrics'] ?? '',
    (int) $song['duration_secs']
);

// ─── Resposta ────────────────────────────────────────────
jsonResponse([
    'success'      => true,
    'song_id'      => (int) $song['id'],
    'title'        => $song['title'],
    'artist'       => $song['artist_name'],
    'duration_secs' => (int) $song['duration_secs'],
    'lines'        => $lines,
]);

// ═══════════════════════════════════════════════════════════
// FUNÇÕES AUXILIARES
// ═══════════════════════════════════════════════════════════

/**
 * Parseia a letra bruta em linhas com timing karaoke.
 *
 * @param string $rawLyrics     Texto bruto da letra
 * @param int    $totalDuration Duração total em segundos
 * @return array Linhas parseadas
 */
function parseLyrics(string $rawLyrics, int $totalDuration): array
{
    $rawLyrics = trim($rawLyrics);
    if ($rawLyrics === '') {
        return [];
    }

    $lines = explode("\n", $rawLyrics);
    $result = [];
    $isChorus = false;
    $hasTiming = false;
    $parsedLines = [];

    // ─── Primeira passada: detecta se há timings ─────────
    foreach ($lines as $line) {
        $line = trim($line);
        if ($line === '') continue;

        if (preg_match('/^\[(\d{1,3}):(\d{2})\.(\d{2,3})\]/', $line)) {
            $hasTiming = true;
            break;
        }
    }

    if ($hasTiming) {
        // ─── Formato com timing (LRC-like) ───────────────
        return parseTimedLyrics($lines, $totalDuration);
    } else {
        // ─── Formato simples (sem timing) ────────────────
        return distributeEvenly($lines, $totalDuration);
    }
}

/**
 * Parseia letras no formato LRC (com timestamps).
 */
function parseTimedLyrics(array $lines, int $totalDuration): array
{
    $result = [];
    $timedLines = [];
    $isChorus = false;

    foreach ($lines as $line) {
        $line = trim($line);
        if ($line === '') continue;

        // Marcação de refrão
        if (preg_match('/^\[chorus\]/i', $line)) {
            $isChorus = true;
            continue;
        }
        // Outras marcações (opcional)
        if (preg_match('/^\[(verse|bridge|intro|outro)\]/i', $line)) {
            $isChorus = str_starts_with(strtolower($line), '[chorus]');
            continue;
        }

        // Linha com timestamp: [mm:ss.xx]texto
        if (preg_match('/^\[(\d{1,3}):(\d{2})\.(\d{2,3})\]\s*(.*)$/', $line, $m)) {
            $minutes = (int) $m[1];
            $seconds = (int) $m[2];
            $centis  = (int) $m[3];
            $text    = trim($m[4]);

            // Centisegundos ou milissegundos?
            $startTime = $minutes * 60 + $seconds + ($centis > 99 ? $centis / 1000 : $centis / 100);

            $timedLines[] = [
                'start'     => $startTime,
                'text'      => $text,
                'is_chorus' => $isChorus,
            ];
        }
    }

    // Se não conseguiu parsear nada, cai no distribuição uniforme
    if (empty($timedLines)) {
        return distributeEvenly($lines, $totalDuration);
    }

    // Calcula end de cada linha baseado no start da próxima
    for ($i = 0; $i < count($timedLines); $i++) {
        $current = $timedLines[$i];
        $nextStart = $i + 1 < count($timedLines)
            ? $timedLines[$i + 1]['start']
            : $totalDuration;

        $words = parseWords(
            $current['text'],
            $current['start'],
            $nextStart
        );

        $result[] = [
            'start'     => $current['start'],
            'end'       => $nextStart,
            'text'      => $current['text'],
            'is_chorus' => $current['is_chorus'],
            'words'     => $words,
        ];
    }

    return $result;
}

/**
 * Distribui linhas uniformemente pela duração (fallback).
 */
function distributeEvenly(array $rawLines, int $totalDuration): array
{
    $result = [];
    $textLines = [];
    $isChorus = false;

    foreach ($rawLines as $line) {
        $line = trim($line);
        if ($line === '') continue;

        if (preg_match('/^\[chorus\]/i', $line)) {
            $isChorus = true;
            continue;
        }
        $textLines[] = [
            'text'      => $line,
            'is_chorus' => $isChorus,
        ];
    }

    $count = count($textLines);
    if ($count === 0) return [];

    $interval = $totalDuration / $count;
    $halfInterval = $interval / 2;

    for ($i = 0; $i < $count; $i++) {
        $start = $i * $interval;
        $end   = ($i + 1) * $interval;

        $words = parseWords(
            $textLines[$i]['text'],
            $start,
            $end
        );

        $result[] = [
            'start'     => $start,
            'end'       => $end,
            'text'      => $textLines[$i]['text'],
            'is_chorus' => $textLines[$i]['is_chorus'],
            'words'     => $words,
        ];
    }

    return $result;
}

/**
 * Divide uma linha em palavras com timing individual.
 */
function parseWords(string $text, float $lineStart, float $lineEnd): array
{
    $words = preg_split('/\s+/', trim($text));
    if (empty($words) || $words === ['']) {
        return [];
    }

    $count = count($words);
    $duration = $lineEnd - $lineStart;
    $wordDuration = $duration / $count;

    $result = [];
    for ($i = 0; $i < $count; $i++) {
        $result[] = [
            'text'     => $words[$i],
            'start'    => $lineStart + ($i * $wordDuration),
            'duration' => $wordDuration,
        ];
    }

    return $result;
}
