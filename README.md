# Observador de Sensi

App Android (Kotlin) no mesmo visual do Vision Paint Macro. Ele **transmite a tela do Free Fire**
(com a bolinha e a notificação como segurança), **observa o seu combate quadro a quadro** e, no
final de cada sessão, entrega um relatório com a **sensibilidade recomendada por mira**, o **DPI**,
a **resolução** (se precisar), a sua **% de capa** e os valores medidos para o Gerador.
Jogue quantas partidas quiser: cada sessão aproxima a recomendação da sensi "full capa".

## Como compilar

1. Suba esta pasta inteira para um repositório no GitHub (branch `main`).
2. Aba **Actions** > **Build APK** (roda sozinho a cada push; ou "Run workflow").
3. Baixe o artefato **SensiObserver-apk**, extraia e instale o `app-debug.apk`.

A chave de assinatura é fixa (`signing/debug.keystore.b64`): uma versão nova instala por cima da
anterior sem apagar suas sessões e calibrações.

## Primeiro uso

1. Abra o app. Permita a **sobreposição na tela** (cartão 2).
2. No cartão 3, coloque a sensibilidade que **está no Free Fire agora** (0 a 200) e quantos dedos
   você usa. A recomendação parte desses números.
3. Toque em **INICIAR**, autorize o compartilhamento de tela e abra o Free Fire **na horizontal**.
4. Toque na **bolinha > Marcar e calibrar**:
   - **1 Mira**: toque no centro da mira do jogo (o quadrado verde é onde o X de acerto aparece).
   - **2 Tiro**: toque no botão de tiro (o círculo vermelho deve ficar dentro dele).
   - **3 Giro**: escolha um trecho de cenário sem botões nem placar (quadrado azul).
   - **4 Dedo**: onde seu dedo gira a câmera (só para a calibração de Resposta).
   - `− tamanho` e `+ tamanho` ajustam o tamanho do marcador atual; **OK** termina.
5. Ainda no painel, calibre num **boneco de treino** (cada uma leva alguns segundos e guia você):
   - **Tiro**: mede a cor do botão solto e apertado.
   - **Capa** e **Corpo**: você atira na cabeça/corpo do alvo e o app aprende o marcador de acerto.
   - **Resposta** (opcional, exige o serviço de acessibilidade): faz 6 arrastos de teste de tamanho
     conhecido e mede quanto a câmera gira por pixel de dedo. Com isso o relatório mostra a
     distância real do seu dedo em mm. Refaça se mudar a sensi ou o DPI.
6. Jogue treino e partidas. Ao trocar de mira (ponto vermelho, 2x, 4x, AWM), troque também
   **Mira em uso** no menu da bolinha. Os "números ao vivo" ao lado da bolinha mostram FPS, se o
   app está lendo a câmera, se vê o tiro e os acertos, e o quanto de vermelho/branco vê na mira.
7. **Finalizar** (bolinha ou notificação) gera o relatório. Coloque os valores no jogo, toque em
   **Usei estes valores no jogo** (atualiza o cartão 3) e jogue de novo.

A bolinha tem **Pausar**, **Finalizar** e **Descartar**. A notificação tem **Pausar** e **Finalizar**.
Se você tocar em "parar de compartilhar" do Android, o que já foi observado é guardado.

## O que é medido

Tudo vem das imagens da tela, com o tempo de cada quadro (8 a 16 ms em 60 a 120 Hz):

- **Arrasto**: quanto a câmera girou, em que velocidade, em quanto tempo, e o **erro** (passou do
  alvo ou ficou curto) e as **correções** antes de atirar.
- **Grude**: do fim do arrasto até o primeiro tiro. **Estabiliza**: tempo até a mira parar.
- **Tiro e capa**: rajadas, acertos, % na cabeça, tempo até o 1º acerto.
- **Puxada**: quanto a câmera sobe nos primeiros 250 ms de tiro, e o **tremor** ao atirar.
- **DPI e resolução**: o app sugere testar um DPI por vez (mudar sensi e DPI juntos atrapalha a
  comparação) e só recomenda baixar a resolução se o FPS ficar instável.

A sensi sugerida por mira usa o erro dos seus arrastos (passou do alvo = sensi alta; ficou curto =
sensi baixa), suaviza o passo e usa o histórico das sessões com a mesma tela e DPI. Com menos de
10 arrastos medidos numa mira ela diz "sem dados" em vez de chutar. Quando a recomendação para de
mudar, aparece "✔ estabilizada".

## Limites (leia)

- **O Android não deixa um app ler os toques de outro app.** O app não vê o seu dedo: ele vê a
  **câmera do jogo** girando e deduz o arrasto. A distância real do dedo só aparece depois da
  calibração de Resposta.
- **Visual do Free Fire**: a detecção de acerto assume um X **branco** (corpo) e **vermelho**
  (cabeça) em volta da mira, e o botão de tiro mudando de cor ao apertar. Se o seu jogo estiver
  diferente, use as calibrações de Tiro, Capa e Corpo e confira os "números ao vivo".
  Em rajadas o app **conta menos acertos do que aconteceram** (um X que fica aceso conta uma vez);
  a proporção cabeça/corpo é mais confiável do que o total.
- Deixe a **bolinha longe** da mira, do botão de tiro e da área de giro: ela aparece na captura
  (o app a esconde da leitura, mas ela cobre o que está embaixo).
- Só analisa com a tela **na horizontal**. Menus e lobby também são lidos; para a medição ficar
  limpa, finalize ou pause fora das partidas e do treino.
- **FPS baixo** (abaixo de uns 45) perde arrastos rápidos; o relatório avisa quando isso acontece.
- Os testes de precisão foram feitos com um **simulador** (câmera, botão de tiro e acertos
  sintéticos): o motor acertou arrasto, erro e rajadas dentro de poucos %. Com o jogo de verdade o
  resultado depende das calibrações acima.
- O serviço de acessibilidade é **opcional** e só faz toques de teste quando você aperta
  **Resposta**, no treino. Nada é enviado para a internet: sessões e calibrações ficam no aparelho.
  Use por sua conta, de acordo com as regras do jogo.

## Estrutura

```
app/src/main/java/com/sensi/observer/
  MainActivity.kt, ReportActivity.kt     telas (cartões numerados, mesmo visual do ScreenMacro)
  CaptureService.kt                       captura de tela, notificação, calibrações guiadas, finalizar
  FloatingControls.kt, Markers.kt         bolinha, menu/painel e marcadores sobre o jogo
  ObserverAccessibilityService.kt         arrastos de teste (opcional)
  ObsSettings.kt, Ui.kt                   configurações e peças de interface
  engine/                                 motor de análise (Kotlin puro, sem Android)
    PhaseCorrelator  movimento da câmera por correlação de fase (FFT 2D, subpixel)
    FrameReader      leitura de pixels (cenário, botão de tiro, marcador de acerto)
    Detectors        tiro, acerto e calibrações
    StaticMask       ignora HUD parado
    Pipeline         quadro a quadro, linha do tempo (ms)
    SessionAnalyzer  arrastos, erro, correções, rajadas, puxada, tremor, nota
    Advisor          sensi por mira, DPI, resolução, dicas, valores do Gerador
    Gain, Store, ReportText, Models
```

Opção **Salvar linha do tempo ms a ms (CSV)** grava, quadro a quadro, quanto a câmera girou, se você
atirava e se acertou (pasta `Android/data/com.sensi.observer/files`).
ensi.observer/files`).
