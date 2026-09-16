# ARL Android — Phase 5 / Production

Este documento descreve o fluxo de produção do launcher Android do Amazing Real Life.

## Arquivos de controle

- `launcher.json`: ponteiro pequeno consumido pelo APK em runtime.
- `production-state.json`: histórico imutável das releases promovidas, versão ativa e identidade do certificado de assinatura.
- `tools/production_state.py`: valida/promove o estado de produção.

A chave privada de assinatura **nunca** deve ser commitada no repositório.

## 1. Secrets necessários para APK de produção

Configure em GitHub → Settings → Secrets and variables → Actions:

- `ARL_KEYSTORE_BASE64`: conteúdo Base64 do `.jks` de produção.
- `ARL_KEYSTORE_PASSWORD`: senha do keystore.
- `ARL_KEY_ALIAS`: alias da chave.
- `ARL_KEY_PASSWORD`: senha da chave.

Depois da primeira release assinada, `production-state.json` grava somente o SHA-256 público do certificado. Releases posteriores com certificado diferente são bloqueadas automaticamente.

**Faça backup offline do `.jks` e das senhas. Perder a chave de produção impede atualizar a instalação existente pelo mecanismo normal do Android.**

## 2. Publicar APK

Execute o workflow `Publish ARL Android APK`.

Entradas:

- `version_code`: inteiro crescente.
- `version_name`: versão pública, por exemplo `1.0.0`.
- `min_version_code`: menor versão que ainda pode jogar.
- `mandatory`: quando `true`, força a atualização antes do botão Jogar.

O workflow:

1. valida o estado de produção;
2. valida os secrets;
3. compila `assembleRelease`;
4. verifica a assinatura com `apksigner`;
5. compara o certificado com o certificado histórico do ARL;
6. calcula SHA-256 do APK;
7. cria uma GitHub Release imutável;
8. atualiza `launcher.json`;
9. faz health-check do asset publicado;
10. registra a release em `production-state.json` e promove a versão.

## 3. Publicar DATA

Execute `Publish ARL Android DATA` com uma URL HTTPS direta para o ZIP real da DATA Android.

O workflow normaliza automaticamente backups do tipo `Android/data/com.samp.mobile/files/...`, remove arquivos locais/pessoais excluídos pelo empacotador, cria ZIP + manifesto por arquivo, calcula SHA-256, publica a release e só então ativa a versão.

Cada DATA usa tag imutável `data-v<versão>`.

## 4. Controles de emergência

Execute `ARL Android Production Controls`.

Ações disponíveis:

- `maintenance-on`: bloqueia Jogar remotamente.
- `maintenance-off`: libera após a correção.
- `data-promote`: promove ou faz rollback para uma DATA que já exista no histórico.
- `app-policy`: altera `minVersionCode` e se a atualização é obrigatória.

### Rollback de DATA

Rollback de DATA é seguro porque apenas muda `launcher.json` para um pacote anterior conhecido e validado. Na próxima abertura/reparo, o launcher detecta mudança de versão e instala a DATA promovida.

### APK não é rebaixado

O Android não permite um downgrade normal de `versionCode`. Se uma versão de APK já distribuída tiver um defeito, a correção correta é publicar uma nova release com **versionCode maior**, mesmo que o código seja baseado numa versão anterior estável.

## 5. Ordem recomendada para primeira publicação

1. criar e guardar a chave de assinatura ARL;
2. configurar os quatro secrets;
3. publicar o APK assinado inicial;
4. instalar/testar em um Android limpo;
5. fornecer o ZIP real da DATA ARL;
6. publicar a DATA;
7. testar download, reparo e entrada no servidor;
8. somente depois divulgar o APK aos jogadores.

## Segurança implementada

- SHA-256 do APK antes de instalar;
- SHA-256 do ZIP da DATA;
- SHA-256 por arquivo da DATA;
- proteção contra Zip Slip;
- staging antes da instalação da DATA;
- releases imutáveis;
- health-check dos assets antes da promoção;
- continuidade obrigatória do certificado de assinatura;
- histórico de produção separado do ponteiro runtime;
- modo manutenção remoto;
- rollback/promoção de DATA sem recompilar APK.
