# Proveniência da adaptação Wear

Esta adaptação parte do [Shevery 14.4.0](https://github.com/HmnDev-Tech/shevery),
commit `20e0174836b0e78847c83aef9c27521c65fa61fb`, derivado do
[Shizuku](https://github.com/RikkaApps/Shizuku).

Preservam-se a licença Apache 2.0 na raiz, as licenças/notices do código original,
os avisos em arquivos-fonte e a licença do SDK em `api/`. Dependências mantêm suas
próprias licenças. O diretório `manager/` conserva código de telefone como referência;
não participa do APK Wear, salvo os fontes nativos de partida reutilizados.

Alterações WatchReserve: novo módulo `wear`, identidade própria do gerenciador e
provider, autorização circular, assinatura independente, servidor somente shell,
configuração separada e remoção do watchdog/supervisor e do grant automático de
`WRITE_SECURE_SETTINGS`. A inscrição do listener sticky do SDK usa o mesmo lock
do despacho para evitar perda de prontidão. O provider Wear aguarda essa prontidão
antes de anexar um helper. Inclui também o protótipo interno dos diários de restauração.
A API, o formato Binder e a permissão de cliente
`moe.shizuku.manager.permission.API_V23` permanecem compatíveis com o SDK Shizuku.

Esta distribuição não é uma versão oficial de Shevery ou Shizuku.
