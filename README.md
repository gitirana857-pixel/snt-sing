# 🎤 SNT Sing — App de Karaokê Social

Cante suas músicas favoritas com playback instrumental, grave vídeo + áudio,
aplique efeitos DSP em tempo real e compartilhe com amigos!

## 📱 Stack

| Camada | Tecnologia |
|--------|-----------|
| **App Android** | Kotlin + CameraX + ExoPlayer + FFmpegKit |
| **Backend API** | PHP 8.x + MySQL (PDO) |
| **Servidor** | NGINX + aaPanel |

## 🎯 Funcionalidades

- ✅ Câmera frontal com preview em tempo real (CameraX)
- ✅ Player de vídeo instrumental (ExoPlayer / Media3)
- ✅ Gravação simultânea: vídeo (sem áudio) + áudio da voz (AAC)
- ✅ Efeitos DSP: Reverb + Echo ajustáveis (EnvironmentalReverb)
- ✅ Mixagem FFmpeg: ajuste de volume voz/música + merge final MP4
- ✅ API RESTful com paginação (PHP + PDO)
- ✅ Permissões em tempo de execução (Android 13+)

## 🚀 Como buildar

```bash
# Clonar
git clone https://github.com/gitirana857-pixel/snt-sing.git
cd snt-sing/android-app

# Build debug APK
./gradlew assembleDebug

# APK gerado em:
# app/build/outputs/apk/debug/app-debug.apk
```

Ou faça o download direto das [Actions](https://github.com/gitirana857-pixel/snt-sing/actions).

## 🗄️ Backend (PHP)

1. Crie o banco MySQL com `database/schema.sql`
2. Configure `api/config.php` com suas credenciais
3. Aponte o NGINX para a pasta `api/`
4. Teste: `GET /api/songs.php`

## 📋 Próximos passos

- [ ] Upload do vídeo final para o backend
- [ ] Tela de login/cadastro
- [ ] Feed de gravações de outros usuários
- [ ] Compartilhamento nas redes sociais
- [ ] Publicação na Google Play Store
