-- ============================================================
-- SNT Sing - Seed: Músicas Populares Brasileiras para Karaokê
-- ============================================================
-- 
-- Para usar:
--   mysql -u root snt_sing < database/seed_songs.sql
--
-- As URLs do YouTube são exemplares. Substitua pelos links reais.
-- Depois rode: ./scripts/download_all.sh https://sntcam.studiosnt.com.br/api
-- ============================================================

USE snt_sing;

-- -----------------------------------------------------------
-- Artistas
-- -----------------------------------------------------------
INSERT INTO artists (id, name, bio, image_url) VALUES
(2, 'Anitta',            'Cantora e compositora carioca, um dos maiores nomes do pop brasileiro', NULL),
(3, 'Gusttavo Lima',     'Cantor sertanejo, fenômeno do agronejo brasileiro', NULL),
(4, 'Marília Mendonça',  'Cantora e compositora, rainha da sofrência e do feminejo', NULL),
(5, 'Jorge & Mateus',    'Dupla sertaneja de Itumbiara/GO, sucesso nacional', NULL),
(6, 'Ludmilla',          'Cantora e compositora carioca, referência no funk e pop', NULL),
(7, 'João Gomes',        'Cantor pernambucano, expoente do piseiro/forró', NULL),
(8, 'Luan Santana',      'Cantor sertanejo, ídolo do agronejo romântico', NULL),
(9, 'Ivete Sangalo',     'Cantora baiana, rainha do axé e carnaval brasileiro', NULL),
(10, 'Legião Urbana',    'Banda de rock de Brasília, ícone do rock nacional', NULL),
(11, 'Caetano Veloso',   'Cantor, compositor e escritor baiano, ícone da MPB', NULL),
(12, 'Pabllo Vittar',    'Cantor e drag queen maranhense, fenômeno do pop LGBTQIA+', NULL),
(13, 'MC Kevinho',       'Cantor e compositor paulista, expoente do funk ousadia', NULL),
(14, 'Alok',             'DJ e produtor goiano, um dos maiores DJs do mundo', NULL);

-- -----------------------------------------------------------
-- Músicas
-- -----------------------------------------------------------
INSERT INTO songs (id, artist_id, title, genre, duration_secs, instrumental_url, cover_url, lyrics) VALUES

-- Anitta
(2, 2, 'Envolver', 'Funk/Pop', 200,
 'https://www.youtube.com/watch?v=EXAMPLE_ANITTA_ENVOLVER',
 'https://i.imgur.com/placeholder.jpg',
 '[00:01.00]Me envolve, me beija, me abraça, me ama
[00:05.00]Eu quero você, eu quero você
[Chorus]
[00:09.00]Me envolve, me beija, me abraça, me ama
[00:13.00]Você sabe bem o que eu quero de você'),

(3, 2, 'Show das Poderosas', 'Funk/Pop', 200,
 'https://www.youtube.com/watch?v=EXAMPLE_SHOW_PODEROSAS',
 'https://i.imgur.com/placeholder.jpg',
 '[Chorus]
[00:01.00]Prepara, que agora é a hora do show das poderosas
[00:05.00]Que desce e desce e desce e desce e desce'),

-- Gusttavo Lima
(4, 3, 'Zé da Recaída', 'Sertanejo', 210,
 'https://www.youtube.com/watch?v=EXAMPLE_ZE_RECAIDA',
 'https://i.imgur.com/placeholder.jpg',
 '[00:01.00]Eu já tentei te esquecer
[00:05.00]Já joguei foto sua fora
[00:09.00]Já bebi pra não lembrar
[00:13.00]Já apaguei seu número agora
[Chorus]
[00:17.00]Mas você sempre aparece
[00:21.00]Com seu jeito de menina
[00:25.00]E eu caio na sua lábia
[00:29.00]Sou o Zé da Recaída'),

(5, 3, 'Apelido Carinhoso', 'Sertanejo', 200,
 'https://www.youtube.com/watch?v=EXAMPLE_APELIDO',
 'https://i.imgur.com/placeholder.jpg',
 '[Chorus]
[00:01.00]Eu quero ser seu apelido carinhoso
[00:05.00]Seu xodó, seu bem, seu tudo, seu amante
[00:09.00]Eu quero ser seu namorado,
[00:13.00]Seu melhor amigo também'),

-- Marília Mendonça
(6, 4, 'Infiel', 'Sertanejo', 220,
 'https://www.youtube.com/watch?v=EXAMPLE_INFIEL',
 'https://i.imgur.com/placeholder.jpg',
 '[00:01.00]Você mentiu pra mim
[00:05.00]Fingiu que me amava
[00:09.00]Enquanto nos seus braços
[00:13.00]Outra pessoa ocupava o meu lugar
[Chorus]
[00:17.00]Infiel, você é infiel
[00:21.00]Machucou meu coração'),

(7, 4, 'Todo Mundo Vai Sofrer', 'Sertanejo', 200,
 'https://www.youtube.com/watch?v=EXAMPLE_TODO_MUNDO',
 'https://i.imgur.com/placeholder.jpg',
 '[Chorus]
[00:01.00]Todo mundo vai sofrer
[00:05.00]Na vida amorosa uma hora a conta chega
[00:09.00]É melhor se preparar
[00:13.00]Que o amor não tem pena'),

-- Jorge & Mateus
(8, 5, 'Propaganda', 'Sertanejo', 190,
 'https://www.youtube.com/watch?v=EXAMPLE_PROPAGANDA',
 'https://i.imgur.com/placeholder.jpg',
 '[00:01.00]Vai, faz propaganda de mim
[00:05.00]Fala mal pra geral
[00:09.00]Que eu não te fiz feliz
[00:13.00]E que eu não presto pra ninguém'),

-- Ludmilla
(9, 6, 'Socadona', 'Funk/Pop', 180,
 'https://www.youtube.com/watch?v=EXAMPLE_SOCADONA',
 'https://i.imgur.com/placeholder.jpg',
 '[Chorus]
[00:01.00]Ela soca, soca, soca, socadona
[00:05.00]No chão, rebola, desce e quica
[00:09.00]Ela é uma foguenta
[00:13.00]Ela é uma bandida'),

-- João Gomes
(10, 7, 'Meu Pedaço de Pecado', 'Piseiro/Forró', 200,
 'https://www.youtube.com/watch?v=EXAMPLE_PEDACO',
 'https://i.imgur.com/placeholder.jpg',
 '[00:01.00]Você é meu pedaço de pecado
[00:05.00]Meu atalho pro paraíso
[00:09.00]Te quero do meu lado
[00:13.00]Pra vida inteira, juízo
[Chorus]
[00:17.00]Vem, me beija, me abraça, me ama
[00:21.00]Que o mundo é nosso'),

-- Luan Santana
(11, 8, 'Morena', 'Sertanejo', 210,
 'https://www.youtube.com/watch?v=EXAMPLE_MORENA',
 'https://i.imgur.com/placeholder.jpg',
 '[00:01.00]Morena, eu vim de longe
[00:05.00]Só pra te ver
[00:09.00]Eu andei, eu corri
[00:13.00]Não parei de querer
[Chorus]
[00:17.00]Seu olhar me completa
[00:21.00]Seu sorriso me aquece
[00:25.00]Morena, o meu mundo
[00:29.00]Só você conhece'),

-- Ivete Sangalo
(12, 9, 'Sorte Grande', 'Axé', 210,
 'https://www.youtube.com/watch?v=EXAMPLE_SORTE_GRANDE',
 'https://i.imgur.com/placeholder.jpg',
 '[Chorus]
[00:01.00]Que sorte grande, que sorte grande
[00:05.00]Foi me apaixonar por você
[00:09.00]Você me deu a chave do seu coração
[00:13.00]E eu guardei com toda fé'),

-- Legião Urbana
(13, 10, 'Tempo Perdido', 'Rock', 250,
 'https://www.youtube.com/watch?v=EXAMPLE_TEMPO_PERDIDO',
 'https://i.imgur.com/placeholder.jpg',
 '[00:01.00]Todos os dias quando acordo
[00:05.00]Não tenho mais o tempo que passou
[00:09.00]Mas tenho muito tempo
[00:13.00]Temos todo o tempo do mundo
[Chorus]
[00:17.00]Somos tão jovens, tão jovens, tão jovens'),

(14, 10, 'Eduardo e Mônica', 'Rock', 270,
 'https://www.youtube.com/watch?v=EXAMPLE_EDUARDO_MONICA',
 'https://i.imgur.com/placeholder.jpg',
 '[00:01.00]Quem um dia irá dizer que existe razão
[00:05.00]Nas coisas feitas pelo coração?
[00:09.00]E quem irá dizer que não existe razão?
[00:13.00]Eduardo abriu os olhos mas não quis se levantar'),

-- Caetano Veloso
(15, 11, 'Sozinho', 'MPB', 220,
 'https://www.youtube.com/watch?v=EXAMPLE_SOZINHO',
 'https://i.imgur.com/placeholder.jpg',
 '[00:01.00]Às vezes no silêncio da noite
[00:05.00]Eu fico imaginando nós dois
[00:09.00]Eu fico ali sonhando acordado
[00:13.00]Juntando o antes, o agora e o depois'),

-- Pabllo Vittar
(16, 12, 'Amor de Que', 'Pop', 190,
 'https://www.youtube.com/watch?v=EXAMPLE_AMOR_QUE',
 'https://i.imgur.com/placeholder.jpg',
 '[Chorus]
[00:01.00]Amor de que? Amor de quê?
[00:05.00]Seu tipo de amor não serve pra mim
[00:09.00]Você não me ama, só quer me usar
[00:13.00]Vai procurar outra pessoa pra enganar'),

-- Alok
(17, 14, 'Hear Me Now', 'Eletrônica', 210,
 'https://www.youtube.com/watch?v=EXAMPLE_HEAR_ME_NOW',
 'https://i.imgur.com/placeholder.jpg',
 '[00:01.00]Hear me now, please hear me now
[00:05.00]I''ve been trying to find the words
[00:09.00]But I don''t know how
[00:13.00]To say I''m sorry'),

(18, 14, 'Never Let Me Go', 'Eletrônica', 200,
 'https://www.youtube.com/watch?v=EXAMPLE_NEVER_LET',
 'https://i.imgur.com/placeholder.jpg',
 '[Chorus]
[00:01.00]Never let me go, never let me go
[00:05.00]I''ve been waiting for this moment all my life
[00:09.00]Never let me go, never let me go
[00:13.00]Take my hand and we can reach the sky');
