# Diagrama de integração de componentes — C4 Container + sequência transacional

> **Arquitetura-alvo; parcialmente iniciada em código.** O desenho editável com ícones AWS4 encontra-se em [diagramas](../../diagramas/). Consulte também a [matriz BIAN](tabelas-hierarquia-bian-ddd-aws.md).

## O que cada serviço faz

| Componente | Bounded Context / BIAN de referência | Entrada | Saída | Síncrono? | Estado próprio |
|---|---|---|---|---|---|
| **authorization-limit-service** (EKS) | AuthorizationAndLimitReservation / Transaction Authorization, Position Keeping | POST /v1/contratos/{id}/autorizacoes; Idempotency-Key | HTTP 201/200/402/409/422 + evento Outbox | **Sim** no comando; emissão **assíncrona** | DynamoDB financial-authorization |
| **core-banking-acl-service** (EKS) | CoreBankingIntegration / Current Account, Consumer Loan conforme produto | Contrato/conta para validação ou sincronização | Modelo normalizado de contrato | Síncrono fora do fluxo crítico ou pré-sincronização | Stateless no MVP |
| **DynamoDB Streams + EventBridge Pipes** (AWS gerenciado) | Integração técnica, **não BIAN** | Alterações confirmadas de item OUTBOX | Evento de integração para EventBridge | Assíncrono | Cursor em Streams; **24 horas** de retenção |
| **EventBridge custom bus** (AWS gerenciado) | Integração técnica | TransacaoAutorizada v1 | SQS por regra/consumer | Assíncrono | Retentiva configurável somente se habilitar archive/replay |
| **accounting-service** (EKS) | AccountingIntegration / Financial Accounting | Evento na SQS Contabilidade | Registro de inbox e futura integração contábil | Assíncrono | DynamoDB accounting-processing |
| **antifraud-adapter** (futuro) | Fraud Evaluation (integração) | SQS Antifraude | Avaliação/alertas para risco | Assíncrono após autorização | Não implementado |
| **notifications-adapter** (futuro) | Comunicação/Notificação | SQS Notificações | Alerta ao cliente | Assíncrono | Não implementado |
| **data-platform-consumer** (futuro) | Analytics transversal | SQS Data Platform | Ingestão de eventos | Assíncrono | Não implementado |

### O que **não** deve ocorrer

- Consumer contábil lendo a tabela DynamoDB do serviço de limites.
- Uma chamada síncrona à contabilidade participando do tempo de resposta da autorização.
- Serviço de Autorização emitindo diretamente EventBridge depois de atualizar DynamoDB (risco de dual-write).
- Tratar `DEBITO` como sinônimo automático de `RESERVA` sem definir regras de domínio.
- Afirmar exactly-once entre EventBridge/SQS e consumer.
- Presumir que o serviço ACL sempre deve estar no caminho crítico da autorização: sem cache, pre-sincronização ou circuit-breaker isso aumenta latência.

## Fluxo operacional — RESERVA (primeiro vertical slice implementado)

1. Canal chama `POST /v1/contratos/{id}/autorizacoes` com `Idempotency-Key`.
2. API Gateway autentica/autoriza consumidor e encaminha por integração privada ao serviço no EKS.
3. `AuthorizationUseCase` valida payload e regra; `DynamoAuthorizationAdapter` consulta estado idempotente e saldo do contrato com leitura consistente.
4. Em uma única `TransactWriteItems`: (a) Update LIMIT condicional, (b) Put AUTH/reserva, (c) Put idempotência, (d) Put OUTBOX. O app retorna 201 após commit, 200 se replay, 402 se insuficiência, 409 para colisão/conflito conforme o caso.
5. Stream capta a alteração confirmada; Pipes filtra `dynamodb.NewImage` por tipo lógico OUTBOX e transforma o payload; EventBridge roteia para filas SQS.
6. `accounting-service` consome sua fila, registra `ConsumedEvent` por evento de forma idempotente e confirma a mensagem; **no primeiro slice ele não faz lançamento no razão contábil real**.
7. Se publicação falhar por tempo superior à retenção do stream, o item Outbox persistido deve permitir reconciliar e republicar. **Reconciliador não implementado ainda**.
8. Operações válidas `DEBITO`, `CREDITO` e `ESTORNO` exigem transições próprias; **ainda não implementadas**.

## Contratos de dados

- API HTTP/JSON conforme PDF: `valor decimal(18,2)`, `moeda`, `tipo_operacao` (`DEBITO`, `CREDITO`, `RESERVA`, `ESTORNO`), `id_conta`, headers e respostas.
- DynamoDB: valores de BRL em **centavos inteiros**, sem float.
- Mensagem Outbox: envelope CloudEvents `specversion=1.0`, `id` UUID, `type=TransacaoAutorizada`, `source`, `time`, `datacontenttype=application/json`, `data` contendo `id_contrato`, `id_autorizacao`, `valor`, `saldo_reservado`, `correlation_id` e `trace_id` quando houver.
- Nunca confiar em ordenação global de mensagens; para dependência de ordem por contrato, adicionar versão/seqüência do agregado e tratar reordenação no consumidor.
- Reentregas: chave (`Idempotency-Key` + hash) no comando, (`event_id`) no consumidor.

## Segurança AWS

WAF na entrada pública, JWT/OAuth2 no API Gateway/authorizer, VPC Link para ALB/NLB interno, EKS em sub-redes privadas, Pod Identity/IAM com menor privilégio, endpoints VPC quando aplicável, KMS para DynamoDB/SQS, TLS e Secrets Manager quando necessário. Observabilidade com métricas de `TransactionCanceled`, latência p95/p99, lag, DLQ e quantidade de Outbox sem publicação.

## Limites do desenho e testes que faltam

- Benchmark 5.000 TPS de pico, hot keys por contrato, HPA/Karpenter e custo.
- Testes de falha depois do commit, crash/reprocessamento, 24h de perda e reconciliação.
- Adicionar auditoria/retenção/regras regulatórias aprovadas pelo negócio.
- Especificar eventual antifraude **pré-autorização** (síncrona, com SLA) versus avaliação **pós-autorização** (assíncrona); o case apenas cita consumidor antifraude e não define bloqueio síncrono.

Fontes: https://docs.aws.amazon.com/eventbridge/latest/userguide/eb-pipes-dynamodb.html e https://docs.aws.amazon.com/amazondynamodb/latest/APIReference/API_TransactWriteItems.html.
