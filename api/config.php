<?php
/**
 * SNT Sing - Configuração do Banco de Dados
 * 
 * Arquivo de conexão PDO com MySQL.
 * Configure as constantes abaixo com os dados do seu banco.
 * 
 * ⚠️ NÃO versionar este arquivo com senhas reais!
 *    Crie um config.local.php separado para produção.
 */

// === Configurações do Banco ===
define('DB_HOST', 'localhost');          // ou IP do servidor MySQL
define('DB_NAME', 'snt_sing');
define('DB_USER', 'root');              // usuário do MySQL (crie um específico!)
define('DB_PASS', '');                  // senha do MySQL
define('DB_CHARSET', 'utf8mb4');

// === Configurações da API ===
define('API_BASE', '/api');             // base path da API
define('ITEMS_PER_PAGE', 20);           // itens por página na paginação

// -----------------------------------------------------------
// Conexão PDO (Singleton)
// -----------------------------------------------------------
function getDB(): PDO
{
    static $pdo = null;

    if ($pdo === null) {
        $dsn = sprintf(
            'mysql:host=%s;dbname=%s;charset=%s',
            DB_HOST,
            DB_NAME,
            DB_CHARSET
        );

        $options = [
            PDO::ATTR_ERRMODE            => PDO::ERRMODE_EXCEPTION,
            PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
            PDO::ATTR_EMULATE_PREPARES   => false,        // consultas preparadas reais
        ];

        try {
            $pdo = new PDO($dsn, DB_USER, DB_PASS, $options);
        } catch (PDOException $e) {
            http_response_code(500);
            echo json_encode([
                'success' => false,
                'message' => 'Erro de conexão com o banco de dados.'
            ]);
            exit;
        }
    }

    return $pdo;
}

// -----------------------------------------------------------
// Configuração de CORS
// -----------------------------------------------------------
//
// 🔒 CORS (Cross-Origin Resource Sharing)
//
// O aplicativo Android fará requisições para esta API.
// Sem os headers abaixo, o navegador (WebView) ou o cliente HTTP
// do app pode bloquear a resposta por questões de segurança.
//
// ✅ Configuração recomendada para desenvolvimento:
//
// header('Access-Control-Allow-Origin: *');
// header('Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS');
// header('Access-Control-Allow-Headers: Content-Type, Authorization');
// header('Access-Control-Max-Age: 86400');
//
// ✅ Para produção, troque o * pelo domínio específico:
// header('Access-Control-Allow-Origin: https://sntcam.studiosnt.com.br');
//
// A chamada desses headers deve ser feita no início de cada endpoint.
// Veja o arquivo songs.php como exemplo.
//
function setupCORS(): void
{
    // Permitir qualquer origem (desenvolvimento)
    header('Access-Control-Allow-Origin: *');
    
    // Métodos HTTP permitidos
    header('Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS');
    
    // Headers personalizados permitidos
    header('Access-Control-Allow-Headers: Content-Type, Authorization');
    
    // Cache do preflight (em segundos)
    header('Access-Control-Max-Age: 86400');

    // Se for requisição OPTIONS (preflight), responder 204 e sair
    if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
        http_response_code(204);
        exit;
    }
}

// -----------------------------------------------------------
// Resposta JSON padronizada
// -----------------------------------------------------------
function jsonResponse(mixed $data, int $statusCode = 200): void
{
    http_response_code($statusCode);
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

function jsonError(string $message, int $statusCode = 400): void
{
    jsonResponse([
        'success' => false,
        'message' => $message
    ], $statusCode);
}
