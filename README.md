# Journal (app Android)

App do [Johnny's Journal](https://jvhubert.github.io/johnnys-journal/) com janela própria (WebView), desde a casca 3
(17/11/2026). As telas vêm do site e se atualizam sozinhas; o app dá ao site o que só o Android tem, pela ponte
`JJNativo` (`Ponte.kt`): microfone, câmera, digital, tela acesa, compartilhar, salvar em Downloads e avisos.

- Baixar: https://github.com/JVHubert/journal-apk/releases/download/ultima/journal.apk
- O número da casca fica em `CASCA.txt`; o Journal o compara em Ajustes ("Procurar atualização").
- Avisos (casca 4, `Avisos.kt`): a nuvem jv-ditado entrega os próximos avisos (`GET /avisos/proximos`); o app agenda
  cada um com alarme exato e busca a lista a cada 15 min, ao abrir e quando as tarefas mudam.
- Ramo `main` publica o release `ultima`; outros ramos só guardam o APK de teste nos artefatos da compilação.
- A chave de assinatura NÃO está aqui (secrets `CHAVE_B64` e `CHAVE_SENHA`).
