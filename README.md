# Journal (casca do app)

Moldura Android do [Johnny's Journal](https://jvhubert.github.io/johnnys-journal/): abre o Journal em tela cheia,
sem barra de navegador (Trusted Web Activity, usando o Chrome por baixo). Não guarda dado nenhum.

- Baixar: https://github.com/JVHubert/journal-apk/releases/download/ultima/journal.apk
- O número da casca fica em `CASCA.txt`; o Journal o compara em Ajustes ("Procurar atualização").
- O app só abre sem barra de endereço porque o site declara este app em
  `https://jvhubert.github.io/.well-known/assetlinks.json` (repositório `JVHubert/JVHubert.github.io`).
- A chave de assinatura NÃO está aqui (secrets `CHAVE_B64` e `CHAVE_SENHA`).
