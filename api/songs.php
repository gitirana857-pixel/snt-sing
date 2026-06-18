<?php
/**
 * SNT Sing - API: Listar Músicas
 * 
 * GET /api/songs.php
 * 
 * Retorna a lista de músicas disponíveis com paginação.
 * 
 * Parâmetros (query string):
 *   page     (int, opcional)  - Número da página (padrão: 1)
 *   per_page (int, opcional)  - Itens por página (padrão: 20, máx: 50)
 *   search   (string, opcional) - Busca por título da música
 *   genre    (string, opcional) - Filtro por gênero
 * 
 * Exemplo:
 *   GET /api/songs.php?page=1&per_page=10
 *   GET /api/songs.php?search=amor&genre=Pop
 * 
 * Resposta (JSON):
 * {
 *     "success": true,
 *     "data": [
 *         {
 *             "id": 1,
 *             "title": "Música Exemplo",
 *             "artist": "Artista Exemplo",
 *             "genre": "Pop",
 *             "duration_secs": 240,
 *             "instrumental_url": "https://...",
 *             "cover_url": "https://...",
 *             "lyrics": "Letra..."
 *         }
 *     ],
 *     "pagination": {
 *         "current_page": 1,
 *         "per_page": 20,
 *         "total": 50,
 *         "total_pages": 3,
 *         "has_next": true,
 *         "has_prev": false
 *     }
 * }
 */

// ─── 1. Configuração inicial ─────────────────────────────────
require_once __DIR__ . '/config.php';

// Aplica headers CORS (chamado ANTES de qualquer output)
setupCORS();

// ─── 2. Valida parâmetros da query ───────────────────────────
$page     = max(1, (int) ($_GET['page'] ?? 1));
$perPage  = min(50, max(1, (int) ($_GET['per_page'] ?? ITEMS_PER_PAGE)));
$search   = trim($_GET['search'] ?? '');
$genre    = trim($_GET['genre'] ?? '');

$offset = ($page - 1) * $perPage;

// ─── 3. Monta a consulta SQL com filtros opcionais ──────────
$pdo = getDB();

$where  = ['s.is_active = 1'];
$params = [];

if ($search !== '') {
    $where[]      = 's.title LIKE :search';
    $params[':search'] = '%' . $search . '%';
}

if ($genre !== '') {
    $where[]      = 's.genre = :genre';
    $params[':genre'] = $genre;
}

$whereClause = implode(' AND ', $where);

// ─── 4. Conta total de registros (para paginação) ───────────
$countSql = "
    SELECT COUNT(*) as total
    FROM songs s
    WHERE {$whereClause}
";
$countStmt = $pdo->prepare($countSql);
$countStmt->execute($params);
$total = (int) $countStmt->fetchColumn();

$totalPages = max(1, (int) ceil($total / $perPage));

// ─── 5. Busca os dados da página atual ──────────────────────
$sql = "
    SELECT
        s.id,
        s.title,
        s.genre,
        s.duration_secs,
        s.instrumental_url,
        s.cover_url,
        s.lyrics,
        a.id   AS artist_id,
        a.name AS artist_name,
        a.image_url AS artist_image
    FROM songs s
    INNER JOIN artists a ON a.id = s.artist_id
    WHERE {$whereClause}
    ORDER BY s.title ASC
    LIMIT :limit OFFSET :offset
";

$stmt = $pdo->prepare($sql);

// Bind dos parâmetros de filtro
foreach ($params as $key => $value) {
    $stmt->bindValue($key, $value);
}

$stmt->bindValue(':limit',  $perPage,  PDO::PARAM_INT);
$stmt->bindValue(':offset', $offset,   PDO::PARAM_INT);
$stmt->execute();

$songs = $stmt->fetchAll();

// ─── 6. Formata a resposta ──────────────────────────────────
$formatted = array_map(function ($row) {
    return [
        'id'               => (int) $row['id'],
        'title'            => $row['title'],
        'genre'            => $row['genre'],
        'duration_secs'    => (int) $row['duration_secs'],
        'instrumental_url' => $row['instrumental_url'],
        'cover_url'        => $row['cover_url'],
        'lyrics'           => $row['lyrics'],
        'artist'           => [
            'id'   => (int) $row['artist_id'],
            'name' => $row['artist_name'],
            'image' => $row['artist_image'],
        ],
    ];
}, $songs);

jsonResponse([
    'success'    => true,
    'data'       => $formatted,
    'pagination' => [
        'current_page' => $page,
        'per_page'     => $perPage,
        'total'        => $total,
        'total_pages'  => $totalPages,
        'has_next'     => $page < $totalPages,
        'has_prev'     => $page > 1,
    ],
]);
