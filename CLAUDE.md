# Sementeira (app Android) — contexto do projeto

Dono: Erick (Brasil, fala português; responda sempre em português). Repo público: `erickrosso/sementeira-app`.
O site (GitHub Pages) fica em outro repo, `erickrosso/Sementeira`, e NÃO deve ser alterado.

## O que é
Jogo de cuidar de plantas em um único `index.html` (HTML/JS/SVG, estado em `localStorage` chave `sementeira-v1`).
Foto da planta -> Pl@ntNet identifica -> semente parecida -> plantar, regar (tempos de espera), solos, clima, jardineiros,
herbário, cruzamento, missões diárias/semanais, drops/moedas, loja, som ambiente, modo noite (22h–6h a água dura o dobro),
"Como jogar" (botão ❓), avisos (notificações locais).
O mesmo `index.html` serve web e app. Recursos só do app (Bluetooth, avisos) ficam ocultos na web.

## Estrutura
- `index.html`: o jogo inteiro (fonte da verdade). `npm run sync` copia para `www/` e roda `cap sync android`.
- Capacitor 8 (Android), appId `com.erickrosso.sementeira`, nome "Sementeira", versionName em `android/app/build.gradle`,
  versionCode = `GITHUB_RUN_NUMBER`. minSdk 24, targetSdk/compileSdk 36. AGP 8.13, Gradle 8.14, JDK 21.
- `android/app/src/main/java/com/erickrosso/sementeira/NearbyPlugin.java`: envio de sementes por Bluetooth (Google Nearby
  Connections). Copia, quem envia mantém a semente. Pede Localização (aproximada+precisa) + Bluetooth/Wi-Fi próximo.
- Plugin `@capacitor/local-notifications` para avisos. Todas as notificações usam `isExactNotification:false`
  (se for exato, o Android abre a tela "Alarmes e lembretes"); SCHEDULE_EXACT_ALARM é removido no manifesto.
- `.github/workflows/android.yml`: build no GitHub Actions. Sempre gera APK debug (`sementeira-apk-debug`);
  com os 4 secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) gera também
  `sementeira-apk-release` e `sementeira-aab` assinados.

## Pontos técnicos importantes
- No app, a ponte do Capacitor é "simples" (sem `registerPlugin`): plugins são chamados com
  `Capacitor.nativePromise('Plugin','metodo',opts)` e eventos com `Capacitor.addListener`.
- Reagendar avisos ao ir para segundo plano precisa ser numa tacada só (cancel + schedule sem esperar respostas),
  senão o WebView pausa no meio e perde todos os avisos.
- Pl@ntNet: chave no código (restrita por domínio); no app a origem é `https://localhost` (autorizar em my.plantnet.org).
- Não dá para compilar Android no ambiente da sessão (sem SDK/Maven); o build é só pelo GitHub Actions.
  Testes locais: Playwright com Capacitor falso.
- Assinatura: o keystore NÃO está no repo (e nunca deve ir). Foi gerado em 29/10/2026 e entregue ao Erick.
  A Play usa "Assinatura pelo Google Play", então a chave de upload pode ser trocada pelo suporte se perdida.

## Estado (29/10/2026)
- Teste interno na Play Store em andamento (AAB enviado pelo Erick). Bluetooth testado e funcionando em 2 celulares.
- Notificações: plantas prontas para regar, com sede (1h antes de murchar) e missões do dia às 10h; não notifica 22h–6h.
  Celulares Xiaomi podem bloquear (bateria/início automático). Há botão de teste no "Como jogar".
- Pendente: política de privacidade (URL), capturas de tela e banner 1024x500 para a ficha da loja; autorizar `https://localhost` no Pl@ntNet.
- Ideias não pedidas: limite de fotos por dia, salvar na nuvem, recompensa por regar à noite.
