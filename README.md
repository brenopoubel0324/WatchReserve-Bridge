# WatchReserve Bridge

Ponte experimental para o laboratório do [WatchReserve](https://github.com/brenopoubel0324/WatchReserve),
baseada no Shevery 14.4.0. O módulo `wear` substitui a interface de telefone por telas
roláveis para relógios circulares. Consulte [a proveniência e as licenças](NOTICE-WatchReserve.md).

Nesta etapa, o cliente autorizado é exclusivamente o APK de desenvolvimento
`dev.brenopoubel.watchreserve.advancedlab`, versão 0.1-readonly, com assinatura fixada
em `AllowedClient.java`. Outros builds exigem qualificação e atualização desse contrato.
O aplicativo principal continua independente da ponte.

A ponte não economiza bateria por si só. Ativa, mantém um processo shell UID 2000.
Não inclui os serviços, watchdog, atualização automática, terminal ou inicialização
periódica do gerenciador de telefone. O starter não supervisiona nem reinicia o servidor.
Não concede WRITE_SECURE_SETTINGS nem muda automaticamente depuração, rádios ou timeout ADB.

## Compilar

Use JDK 21, plataforma `platforms;android-37.0`, build-tools 37.0.0,
NDK 29.0.14206865 e CMake 3.22.1. A toolchain é independente da do WatchReserve.

```powershell
.\gradlew.bat :wear:assembleDebug :wear:assembleDebugAndroidTest :wear:lintDebug
```

Sem credenciais privadas, debug usa a chave Android de desenvolvimento e não será
aceito pelo cliente qualificado. Para assinatura persistente, defina
`WATCHRESERVE_BRIDGE_SIGNING` apontando para um arquivo **fora do repositório**:

```properties
storeFile=C:/pasta-privada/bridge.p12
storePassword=senha-privada
keyAlias=watchreserve-bridge
keyPassword=senha-privada
```

Guarde chave e credenciais em backup privado. Release exige essa configuração;
não há fallback de release para chave debug. CI compila/debug/lint sem acesso à chave.

## Instalar e iniciar o laboratório

Esta versão inicia pelo PC/ADB e precisa de nova partida após reboot. Pareamento,
recuperação sem PC e controles de economia ainda não foram entregues. Não instalar
sobre outro gerenciador: a permissão legada do SDK pode conflitar entre assinaturas.
Não desinstale um gerenciador pessoal para contornar isso; use primeiro o AVD reservado.

1. Instale `wear/build/outputs/apk/debug/wear-debug.apk` no relógio explicitamente selecionado.
2. Obtenha `sourceDir` e `legacyNativeLibraryDir` por `dumpsys package dev.brenopoubel.watchreserve.bridge`.
3. Pelo shell ADB UID 2000, execute o `libshizuku.so` dessa instalação com
   `--apk=CAMINHO_DO_APK`. O resultado do comando só confirma a partida; confirme
   **Ativa · shell UID 2000** na interface e a leitura real pelo laboratório.
4. Abra WatchReserve Lab e solicite acesso. A confirmação permite negar, autorizar
   nesta sessão ou persistir a autorização. A ponte permite revogar o laboratório
   e encerrar o servidor. Revogar também encerra o processo cliente e seus helpers.

Os APKs são separados. Não use o pacote do Shevery do celular como destino do starter.
A configuração shell usa `watchreserve-bridge.json`, separada de `shizuku.json`.
Remover o APK não restaura alterações feitas por um cliente privilegiado.

## Testes

A instrumentação `BridgeWearTest` exige AVD Wear circular reservado, cliente Lab
qualificado instalado e ponte iniciada. Exercita negação, autorização de sessão,
persistência após matar o cliente, leitura real, revogação e nova autorização.
O teste só admite hardware `ranchu/goldfish` e o argumento
`-e reservedAvd WatchReserve_Wear5_Small_Round`.

O roteiro completo instala os APKs já compilados, inicia a ponte, opera as
confirmações reais, roda a instrumentação dos dois projetos nas duas fontes e
testa o botão Parar. Restaura a fonte/configurações e encerra os processos de
ensaio no final, inclusive em falha. Requer PowerShell 7.4+:

```powershell
.\scripts\Test-WearBridge.ps1 -Serial emulator-5580 `
  -LabRepository C:\caminho\WatchReserve `
  -AdbPath C:\caminho\android-sdk\platform-tools\adb.exe `
  -OutputDirectory "$env:LOCALAPPDATA\WatchReserveBridgeEvidence"
```

Execute em 192 dp circulares, fontes 1,0 e 1,3. Capturas de revisão ficam nos dados
privados do app, sem upload. Rode também o roteiro `Test-AdvancedLab.ps1` do repositório
principal para conferir arquivos, UID e encerramento do helper. Testes AVD não
comprovam política de economia Samsung, recuperação após reboot ou ganho de autonomia.
