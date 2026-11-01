# WatchReserve-Bridge — contrato para economia modular

**01/11/2026 — proposta de integração; nenhum backend novo implementado.**
Plano de produto: [WatchReserve — economia progressiva](https://github.com/brenopoubel0324/WatchReserve/blob/plan/modular-battery-profiles-2026-11-01/docs/planos/2026-11-01-economia-progressiva.md).

## O que a Bridge tem e não tem

- Tem autorização Wear, serviço shell, validação de cliente e núcleo dos diários `PREPARATION`, `RECOVERY`, `PROFILE`, testado com fixture AVD.
- Não tem API produtiva de desativação de app/serviço, controle de AppOps/standby, bootstrap autônomo após reboot ou prova física Samsung.
- O cliente admitido em `AllowedClient.java` é exclusivamente o Lab v0.2 de leitura, certificado fixo; a UI de permissão descreve somente leituras.
- A versão atual não deve ser promovida a executor de perfis sem atualizar contrato/consentimento e testes.

## Novo contrato proposto

Operações **tipadas e enumeradas**, nunca shell genérico:
- `GET_CAPABILITIES` — recursos/versão, UID, usuário e motivo de não suporte.
- `GET_TARGET_STATE` — pacote/componente/estado cru/identidade/dependências conhecidas.
- `PREVIEW_PROFILE` — operações ordenadas, proteções, impacto e suportabilidade.
- `APPLY_PROFILE` — sessão e nonce esperados, lista allowlisted, timeout, intenção durável antes da escrita.
- `RESTORE_PROFILE` — reversão exata e condicional ao estado ainda pertencer à sessão.
- `RECONCILE_PROFILE` — releitura sem repetir comando de escrita ambíguo.
- `GET_RECOVERY_STATUS` — ponte parada, journal pendente, ambiente incompatível, conflitos.

Primeira versão de escrita aceita **apenas fixture no AVD**, sem pacote Samsung.
A operação de desativar pacote, desativar componente, definir AppOp e mudar bucket
são **tipos distintos**; cada qual exige observador, política de risco,
allowlist específica do firmware e testes de reversibilidade. Nunca desabilitar
alarme, SystemUI, bootstrap, armazenamento do diário ou serviços de recuperação.

Toda chamada revalida UID de caller, pacote/assinatura, protocolo, usuário,
identidade do alvo e permissão por operação. A identidade/contrato da
Bridge deve ser versionada com o Lab e migrada de modo coordenado.
A autorização para **escrita** exige texto e confirmação diferentes da autorização
atual de **somente leitura**; nenhuma elevação implícita por upgrade.

Um único controlador serializa UI/IPC/boot e é dono dos diários. O bloqueio
`synchronized` dentro de um processo não sincroniza processos distintos.
A perda da Bridge não conclui nem apaga diário; impedir nova aplicação e
oferecer diagnóstico/recuperação manual. Nunca alegar reboot sem PC até prova
no dispositivo, com/sem rede e economia Samsung.

## Integração e gates

1. P0: preservar código existente, revisar contrato/assinaturas/uso da permissão.
2. P1: escrever adaptador de teste do `JournalController.Backend` para fixture e IPC
   allowlisted; rodar crash entre intenção, escrita e confirmação, conflito e restauração.
3. P2: validar autorização, revogação, timeout, morte de Binder e duas versões do cliente
   compatíveis ou rejeitadas com mensagem explícita; executar CI nos dois repositórios.
4. P3: provar qualificação do firmware físico e recuperação segura antes de liberar controles
   de serviços opcionais. Medir custo de processo shell residente.
5. P4: um controle real de cada tipo aprovado individualmente; a política da UI não pode
   assumir que sucesso de shell equivale a serviço parado ou economia obtida.

Critérios de NO-GO: pacote/UID compartilhado sem identificação, alvo protegido,
perda de acesso de restauração, inconsistência do diário, operação parcial desconhecida,
falha na prova física ou alteração de firmware sem requalificação.
