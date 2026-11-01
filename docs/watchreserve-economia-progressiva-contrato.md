# WatchReserve-Bridge — contrato revisado para economia modular

**01/11/2026 — revisão da arquitetura Shizuku, somente documentação.**
Veredito: **APROVADO para desenho e protótipo restrito no AVD; NÃO APROVADO para escrita em Galaxy Watch ou release da Bridge**.

Plano de produto: [economia progressiva, 01/11](https://github.com/brenopoubel0324/WatchReserve/blob/plan/modular-battery-profiles-2026-11-01/docs/planos/2026-11-01-economia-progressiva.md).
Auditoria: [achados e gates](https://github.com/brenopoubel0324/WatchReserve/blob/plan/modular-battery-profiles-2026-11-01/docs/auditorias/2026-11-01-shizuku-bridge-economia-progressiva.md).
Código atual: `wear/src/main/java/dev/brenopoubel/watchreserve/bridge/`, `journal/`, `wear/src/main/AndroidManifest.xml`.

## 1. Estado REAL (não confundir com proposta)

- `BridgeProvider` estende `ShizukuProvider` e entrega o Binder para o SDK; **não implementa uma política de escrita por operação**.
- `AllowedClient` admite **apenas** `dev.brenopoubel.watchreserve.advancedlab`, versãoCode 2 / nome `0.2-readonly-sessions` e fingerprint fixado. Não autoriza `dev.brenopoubel.watch8battery` (app principal).
- `PermissionActivity` anuncia "este laboratório usa apenas comandos de leitura". **Não reutilizar esse consentimento como permissão para escrita**.
- A Bridge atual aceita startup shell por ADB, sem root, e deve ser iniciada novamente depois de reboot via PC. Sem prova de recuperação autônoma no Watch8.
- `JournalController` e `AtomicJournalStore` guardam três journals e executam fixture em instrumentação no AVD; nenhum backend de produção, broker exportado, controle Samsung ou IPC de perfis foi entregue.
- O SDK Shizuku transmite potencialmente **capacidade shell genérica** ao cliente autorizado. Restringir a UI ou apenas a lista de opções de um cliente não equivale a restringir o Binder privilegiado.

## 2. Bloqueio de namespace e compatibilidade upstream

O [README oficial do Shizuku, seção License](https://github.com/RikkaApps/Shizuku#license) afirma expressamente que distribuições derivadas não devem declarar permissões com prefixo `moe.shizuku.manager.permission.*`. O manifesto Wear **declara** `moe.shizuku.manager.permission.API_V23` como permissão `dangerous`, preservando o protocolo legado.

**NO-GO para distribuir a nova Bridge enquanto isso permanecer sem solução documentada.** A redação upstream não significa, por si só, sentença jurídica; requer revisão de distribuição e/ou permissão explícita dos mantenedores. Não presumir que Apache-2.0 invalida automaticamente a restrição de marca/identidade indicada pelos autores.

Não fazer `sed` simples trocando o identificador: o SDK e o servidor Shizuku esperam permissões e callbacks de nomes específicos e um rename unilateral quebra o contrato. Testar em AVD duas alternativas:

1. **Preferência:** broker privado do produto e infraestrutura Shizuku apenas interna se possível, **sem declaração pública** do namespace reservado, com testes de startup, Binder, revogação e assinatura. Remover ou isolar da distribuição qualquer client/provider legado que requeira declarar essa permissão.
2. Obter autorização expressa upstream, preservando compatibilidade somente no âmbito autorizado.

Se nenhuma alternativa for validada, **não distribuir a Bridge**; manter laboratório isolado para pesquisa até resolver. A API Shizuku usada internamente não dá direito a um namespace público reservado.

## 3. Fronteiras de confiança recomendadas

```text
WatchReserve (UID comum; telas e preferencias; sem shell / Shizuku API_V23)
   |
   | Binder explícito (contrato próprio do produto)
   v
ProfileBrokerService (processo manager da Bridge)
   |- exigir UID real do caller, pacote único, certificado, versão e protocolo
   |- validar consentimento específico para escrita + recurso solicitado
   |- recusar comandos de texto livre e alvo desconhecido
   |- serializar operações e recuperar journal no processo proprietário
   |
   v
JournalController / AtomicJournalStore (PREPARATION, RECOVERY, PROFILE)
   |- persistir estado original -> intenção -> executar -> confirmar
   |- falha, estado desconhecido e conflitos preservados
   |
   v
ProfileExecutorUserService (UID 2000; mecanismo de partida ainda não qualificado)
   |- autenticar broker chamador
   |- revalidar operação enumerada, firmware, identidade exata do alvo,
   |  userId, proteção, timeout e nonce/ID esperado
   |- não expor shell genérico, Binder de service manager ou execução arbitrária
   v
Android APIs qualificadas (com limites de permissão Samsung/SELinux)
```

**Dupla checagem:** o broker valida a seleção e o executor privilegiado revalida **todos os parâmetros que impactam o efeito**; cliente não confiável não pode escolher um destino de comando arbitário. Não retornar um Binder Shizuku ou UserService genérico ao app principal. O código atual `AllowedClient` não implementa isso sozinho.

**Assinaturas:** a Bridge e o app principal têm identificadores/certificados próprios. Se forem assinados com a mesma chave, considerar permissão Android `signature` do produto no Binder; caso contrário, nunca exigir `signature` sem uma migração de certificados comprovada. Em ambos os casos revalidar `Binder.getCallingUid()`, `PackageManager.getPackagesForUid()`, signer, versão de contrato e autorização **em cada IPC**. Rejeitar UID compartilhado não qualificado e pacote com múltiplos signers inesperados. Não confiar em Intent extras para identidade.

**Convivência com laboratório:** `ReadOnlyService` atual pertence ao Lab e faz consultas enumeradas. Qualquer endpoint com escrita é **um serviço diferente**, com consentimento, cliente e AIDL distintos. Não elevar implicitamente o antigo contrato `0.2-readonly-sessions`. Se o Lab precisar exercitar escrita, limitar-se a fixture AVD e versão assinada/qualificada própria. Uma Bridge que não suporta a versão nova deve rejeitá-la com erro legível, não permitir comandos parciais.

## 4. Contrato proposto, sem endpoints implementados

| Operação | Entrada obrigatória | Resultado e garantia |
|---|---|---|
| `GET_CAPABILITIES` | protocolo + identidade da sessão | lista permitida por firmware; status de shell e limites, nunca comandos |
| `GET_TARGET_STATE` | package/component/operation ENUM + userId | valores originais e identidade, ou `UNSUPPORTED` |
| `PREVIEW_PROFILE` | lista tipada de capacidades selecionadas | dependências, proteções, plano ordenado e indisponibilidades sem modificar estado |
| `APPLY_PROFILE` | journal/session UUID, nonce, expectedRevision, consentId, operações tipadas | `VERIFIED`, `PARTIAL`, `BLOCKED`, `UNKNOWN`; efeitos confirmados individualmente |
| `RECONCILE_PROFILE` | session UUID, expectedRevision | relê alvos e atualiza journal sem repetir escrita ambígua |
| `RESTORE_PROFILE` | session UUID, expectedRevision, consentido | restituição exata condicional ao estado observado; conflitos preservados |
| `GET_RECOVERY_STATUS` | versão de contrato | ponte acessível? journals pendentes? reboot/firmware/UID mudou? |

Definir esquema versionado sem string livre de comandos. `expectedRevision` impede resposta atrasada de sobrescrever estado recente; `nonce` impede replay do mesmo pedido. Resposta nunca implica efeito sem observação independente. O motor precisa confirmar estado atual de pacote, component enabled override, AppOps mode, bucket e dependências **pelo mecanismo real daquele controle**. Retornar códigos estruturados, sem exceções genéricas expostas ao usuário.

**Operações e alvos protegidos:**
- `PACKAGE_DISABLE`: não equivale a `force-stop`; não permitir pacote essencial, shared UID ambíguo, app do broker, app de recuperação ou infraestrutura requerida.
- `COMPONENT_DISABLE`: service/receiver/provider só após mapear dependências e validar firmware, assinatura e componente exato; nunca permitir disabled de alarme, tela, SystemUI, conexão de recuperação.
- `APPOP_CHANGE`: preservar `MODE_DEFAULT` e modos explícitos; consultar effective/raw; restaurar o registro original, sem estabelecer `allow` por padrão.
- `STANDBY_BUCKET_CHANGE`: o Android pode reclassificar; não alegar persistência ou economia por simples ACK.
- `NATIVE_SETTINGS`: abertura de Ajustes e confirmação manual não contam como mudança efetuada pelo broker.
- **Proibido no MVP:** shell arbitrário, UID 0, limpeza de dados, uninstall de pacotes de sistema, políticas globais agressivas de Doze, bloqueio de alarmes/boot/restauração.

Os três journals existentes guardam no máximo 16 alvos por sessão; um perfil maior deve ser **rejeitado ou fatiado somente com protocolo formal que preserve recuperabilidade** — nunca truncar silenciosamente.

## 5. Estado, crash, timeout, reboot e disponibilidade

- O **processo manager da Bridge** é o único proprietário de `JournalController`; serializa binder/UI/recovery. `AtomicFile` é atomicidade de arquivo, **não lock cross-process**. O serviço UID shell não grava cópia concorrente do mesmo diário.
- Antes de executar, registrar originais, identidade e `INTENDED`. Depois da escrita, observação independente confirma `VERIFIED`. Crash entre ambas não pode repetir automaticamente uma operação ambígua.
- Intervenções de lote **não são transação atômica de sistema**. Em falha parcial, apresentar quais alvos foram aplicados, quais ficaram desconhecidos e quais foram restaurados; oferecer rollback, sem apagar logs/diários.
- Se um comando demorar além do timeout, **não** assumir que o processo shell terminou. O backend `requireIdle` deve obter certeza de término/invalidar sessão; sem prova, bloquear próxima escrita.
- Depois de reboot sem bridge, perfis persistentes podem continuar ativos e **não podem ser restaurados localmente só pelo app comum**. A UI deve informar `NEEDS_SETUP` ou `RESTORATION_BLOCKED`, preservando o journal, sem autoapagar dados e sem prometer restart com Wi-Fi off.
- Controles persistentes de serviços só entram no produto após ensaio físico de recuperação com/sem Wi-Fi, com economia Samsung ligada, sem PC e com permissões expiradas. Se inviável, limitar a ajustes nativos/controles comprovadamente recuperáveis. Restabelecer automaticamente um estado anterior exige leitura e propriedade verificadas, não escrita cega em boot.
- A Bridge ativa usa processo shell; registrar custo incremental e avaliar stop deliberado do serviço, sem watchdog permanente.

## 6. Ordem de implementação e gates

| Passo | Trabalho | Aceite / NÃO ACEITE |
|---|---|---|
| B0 | Resolver namespace reservado e decidir contrato próprio sem escalar clientes | AVD: compatibilidade/revogação funcionando; parecer documentado e alternativa permitida |
| B1 | Especificar AIDL versionado do broker e allowlist, ameaça, consentimento de escrita | UID falso, app assinado errado, sharedUID, replay, UID do executor errado, userId errado recusados |
| B2 | Ligar `JournalController.Backend` à fixture AVD por broker/executor enumerado | aplicar, observar, matar processos, reconciliar, conflito, restaurar exato; sem comandos arbitrários |
| B3 | Testar físico no Watch8 UID 2000, alarmes, reboot, rádios e recuperação viável | Relatório com firmware, tudo observável; impossível = bloqueado |
| B4 | Qualificar primeiro alvo de pacote opcional, depois componente, depois AppOp/bucket | Casos de dependência, restauração e regressão aprovados individualmente |
| B5 | Integração WatchReserve, perfil editável e UI; medição de consumo | CI nos dois repos, testes UI, baseline e 3 pares R/E sem perda de funções selecionadas |

Não implementar `B2` antes de definir `B0/B1`. Nenhum passo dá permissão para modificar o hardware do usuário automaticamente.

## 7. Evidências mínimas

- Identidade do APK, certificado, versão/protocolo, modelo, firmware, UID shell, estado de power saver e início/fim da sessão.
- Teste Android de integração entre os dois APKs (não apenas testes JVM dos journals).
- Confirmação de que app principal NÃO pede permissão Shizuku nem recebe Binder shell; endpoints não autorizados recusados.
- Crash em gerenciador e executor, morte de Binder, downgrade/upgrade, revogação, perfis parcialmente aplicados, arquivo corrompido, pacote desinstalado/reinstalado e conflito externo.
- Check de `git diff --check`, build/lint/testes de ambos repositórios, PRs vinculadas e logs com dados sensíveis redigidos.
- O status "build verde" não significa que Shizuku/controle de serviço funciona no firmware físico.

## 8. Referências

- [Shizuku oficial: arquitetura e limite das permissões ADB](https://github.com/RikkaApps/Shizuku#how-does-shizuku-work)
- [Shizuku oficial: restrição sobre namespace/identidade](https://github.com/RikkaApps/Shizuku#license)
- [Diários atuais da Bridge](restoration-journals.md)
- [WatchReserve — plano correlato](https://github.com/brenopoubel0324/WatchReserve/blob/plan/modular-battery-profiles-2026-11-01/docs/planos/2026-11-01-economia-progressiva.md)
