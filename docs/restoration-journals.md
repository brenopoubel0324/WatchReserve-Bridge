# Diários de restauração

O pacote interno `bridge.journal` é um protótipo de persistência e controle de
alterações. Não está ligado à interface, ao bootstrap ou a um IPC de produção.
O APK continua sem controles para congelar apps ou alterar rádios do relógio.
O único backend de escrita está na instrumentação e admite exclusivamente o
pacote de ensaio `dev.brenopoubel.watchreserve.lab`, no AVD reservado.

## Contrato do núcleo

`AtomicJournalStore` guarda três arquivos em `noBackupFilesDir/restoration`:

| Tipo | Arquivo | Responsabilidade prevista |
|---|---|---|
| `PREPARATION` | `preparation.properties` | Alterações necessárias para preparar o acesso |
| `RECOVERY` | `recovery.properties` | Ajustes temporários para recuperar a ponte |
| `PROFILE` | `profile.properties` | Uma única sessão de ajustes de economia |

Os arquivos de produção ainda não são criados. Os testes usam outro diretório,
`noBackupFilesDir/journal-fixtures`, com contextos isolados e remoção apenas após
restauração. Registros ilegíveis, incompatíveis ou com conflito ficam preservados.

O schema 1 identifica sessão, tipo, vínculo de ambiente, conclusão e até 16 alvos.
Cada alvo contém identidade, original, aplicado, fase e motivo. Ausência de valor,
string vazia, `"null"`, `"0"` e `"1"` são estados distintos. Para pacotes, `DEFAULT`
e `ENABLED` não são intercambiáveis. Chaves duplicadas/desconhecidas, UTF-8 inválido,
tipos inesperados e registros acima de 64 KiB são recusados.

`prepare` captura todos os originais antes da primeira alteração. Repetir a mesma
solicitação aberta devolve a mesma sessão; não captura o estado aplicado como novo
original. Um pedido diferente exige resolver a sessão anterior. `apply`, `restore`
e `reconcile` exigem também o ID esperado para rejeitar chamadas atrasadas.

Antes de cada escrita, o controlador persiste `INTENDED` ou `RESTORE_INTENDED`.
O armazenamento sincroniza o descritor e confere os bytes comprometidos antes de
liberar a chamada ao backend: [algumas falhas de `AtomicFile` são apenas registradas
no log](https://github.com/aosp-mirror/platform_frameworks_base/blob/main/core/java/android/util/AtomicFile.java).
A escrita só vira `VERIFIED`/`RESTORED` depois de observação independente. Falha
ou perda de resposta fica `UNKNOWN`, sem repetir automaticamente o comando.
O alvo é relido também depois de persistir a intenção, antes do comando. Se a
escrita nem começou, `NOT_APPLIED` preserva essa diferença: a reconciliação não
assume propriedade sobre um valor externo e a restauração conclui sem reescrevê-lo.

`reconcile` apenas relê o estado e atualiza o diário. A restauração percorre os
alvos na ordem inversa. Só escreve quando identidade e estado ainda correspondem
ao aplicado; estado diferente fica `CONFLICT`. Uma mudança que nunca foi iniciada
ou já era desnecessária não assume propriedade sobre alterações externas.

A recuperação temporária aberta bloqueia aplicação, restauração e reconciliação
do perfil. O perfil aberto bloqueia essas operações na preparação. Isso impede
interpretar um estado temporário do filho como restauração definitiva do pai.
Restaurar o perfil não exige reaplicar ou aprovar novamente a preparação.

## Requisitos para a futura integração

O `Backend` ainda precisa de implementação qualificada antes de uso real. Deve
admitir apenas operações enumeradas, conferir pacote/UID/usuário/versão/certificado,
limitar duração e observar por uma API independente do comando usado na escrita.
`requireIdle` precisa recusar uma operação anterior ainda em andamento; uma resposta
perdida não comprova que o comando parou. Nenhum texto de intent pode virar terminal.
O lock do app não sincroniza a UI nativa ou outros processos. A integração deverá
validar as pré-condições também no backend e qualificar esse limite por controle.

O vínculo de ambiente deve incluir dispositivo, firmware, dono e versões/assinaturas.
Não deve expirar a cada boot: os diários precisam atravessar reinícios. Uma mudança
incompatível bloqueia novas escritas e mantém o registro para recuperação manual.
O hash de qualificação utilizado no teste permanece privado.

Um lock compartilhado serializa os controladores e o armazenamento no mesmo processo.
A futura UI, execução de boot e IPC devem usar esse único dono; `AtomicFile` não
fornece exclusão entre processos. A preparação inicial pelo PC antes de instalar
a ponte, grants reais, pareamento e recuperação sem PC ainda não foram integrados.
Apagar dados ou desinstalar remove registros e não restaura os ajustes.

## Ensaios

Compile com a toolchain da ponte e instale previamente o APK `:lab` do WatchReserve
no AVD `WatchReserve_Wear5_Small_Round`. Os testes conferem pacote, versão, assinatura,
UID, ausência de `WRITE_SECURE_SETTINGS`, hardware emulado e tela circular de 192 dp.
Não desinstalam gerenciadores pessoais nem iniciam a ponte shell.

```powershell
.\gradlew.bat --no-daemon :wear:testDebugUnitTest `
  :wear:assembleDebug :wear:assembleDebugAndroidTest :wear:lintDebug
.\scripts\Test-RestorationJournals.ps1 -Serial emulator-5580 `
  -AdbPath C:\caminho\android-sdk\platform-tools\adb.exe `
  -OutputDirectory "$env:LOCALAPPDATA\WatchReserveJournalEvidence"
```

O roteiro recupera primeiro eventuais registros do ensaio anterior. Testa os três
diários com `pm` e confere pelo `PackageManager`; preserva o inteiro original.
Também mata o próprio processo depois de desativar a fixture e antes de confirmar
o resultado. A próxima instrumentação exige intenção persistida e efeito real antes
de restaurar. Uma morte esperada no log aprova somente essa injeção, nunca a suíte
normal. O `finally` tenta recuperar, mas uma interrupção do PC pode impedir essa
etapa; execute novamente o mesmo roteiro antes de outro ensaio.

Os testes JVM cobrem conflitos, ausência/default/enabled, idempotência, concorrência,
falha de armazenamento, resposta perdida e ordem entre os três registros. A
instrumentação cobre o pacote real de ensaio, arquivos incompletos, UTF-8/tipo inválidos,
falha real de rename, indisponibilidade do backend e morte/reabertura do processo.
Evidências ficam fora do Git. Isso não qualifica congelamento de apps Samsung,
recuperação após reboot, alarmes físicos ou ganho de autonomia.
