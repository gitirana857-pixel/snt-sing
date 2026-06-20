#!/bin/bash
# ──────────────────────────────────────────────────────────────
# SNT Sing - Download de Todos os Instrumentais
# ──────────────────────────────────────────────────────────────
#
# Uso: ./scripts/download_all.sh [base_url]
#
# Percorre todas as músicas ativas no banco e baixa os
# instrumentais do YouTube para storage local.
#
# Parâmetros:
#   base_url - URL base da API (padrão: http://localhost/api)
#
# Exemplo:
#   ./scripts/download_all.sh https://sntcam.studiosnt.com.br/api
# ──────────────────────────────────────────────────────────────

BASE_URL="${1:-http://localhost/api}"

echo "🎵 SNT Sing - Download em Lote de Instrumentais"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "URL base: $BASE_URL"
echo ""

# Busca lista de músicas da API
echo "📋 Buscando músicas..."
SONGS=$(curl -s "$BASE_URL/songs.php?per_page=50" | python3 -c "
import json, sys
data = json.load(sys.stdin)
for s in data.get('data', []):
    url = s.get('instrumental_url', '')
    if 'youtube' in url.lower() or 'youtu.be' in url.lower():
        print(f\"{s['id']}|{s['title']}|{s['artist']['name']}\")
")

if [ -z "$SONGS" ]; then
    echo "❌ Nenhuma música com URL do YouTube encontrada."
    echo "   Verifique se a API está acessível e há músicas cadastradas."
    exit 1
fi

TOTAL=$(echo "$SONGS" | wc -l)
echo "🎯 Encontradas $TOTAL música(s) com YouTube"
echo ""

COUNT=0
SUCCESS=0
FAIL=0

while IFS='|' read -r ID TITLE ARTIST; do
    COUNT=$((COUNT + 1))
    echo "[$COUNT/$TOTAL] 🎬 Baixando: $TITLE - $ARTIST (ID: $ID)..."
    
    RESULT=$(curl -s -X POST "$BASE_URL/download.php" \
        -d "song_id=$ID" \
        -d "force=false")
    
    if echo "$RESULT" | python3 -c "import json,sys; d=json.load(sys.stdin); sys.exit(0 if d.get('success') else 1)" 2>/dev/null; then
        URL=$(echo "$RESULT" | python3 -c "import json,sys; print(json.load(sys.stdin).get('url','?'))")
        CACHED=$(echo "$RESULT" | python3 -c "import json,sys; print('(cache)' if json.load(sys.stdin).get('cached') else '(download)')")
        echo "   ✅ $URL $CACHED"
        SUCCESS=$((SUCCESS + 1))
    else
        MSG=$(echo "$RESULT" | python3 -c "import json,sys; print(json.load(sys.stdin).get('message','erro'))")
        echo "   ❌ $MSG"
        FAIL=$((FAIL + 1))
    fi
    
    # Pequena pausa entre downloads para evitar rate limit do YouTube
    sleep 2
    
done <<< "$SONGS"

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "✅ $SUCCESS baixados | ❌ $FAIL falhas | 📊 $TOTAL total"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
