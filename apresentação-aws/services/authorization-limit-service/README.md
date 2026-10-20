# authorization-limit-service — AuthorizationAndLimitReservation

**Java 17 / Spring Boot / Clean Architecture / DDD.** Aplicação implementada, com testes de unidade e integração contra DynamoDB Local no pipeline GitHub Actions.

## Bounded Context e funções

Módulos DDD: Authorization (decisão/autorização) e Contract Limits (posição/disponibilidade). Referências BIAN: Transaction Authorization + aspectos de Position Keeping. **Não** são dois bancos ou duas transações independentes.

| Operação | Transição em centavos | Evento Outbox | Observação |
|---|---|---|---|
| RESERVA | available -= value; reserved += value | TransacaoAutorizada | Reserva temporária, sem captura/liquidação |
| DEBITO | available -= value; used += value | TransacaoAutorizada | Consumo direto (hipótese a homologar) |
| CREDITO | used -= value; available += value | LimiteAlterado | Recompõe utilização; não aumenta limite total |
| ESTORNO | Reverte RESERVA ou DEBITO original | TransacaoEstornada | Exige `id_autorizacao_original`, mesmo contrato/conta/valor, uma vez só |

**Invariantes**: available, reserved e used são não negativos e a soma é igual a total. Valores inteiros em centavos; REST recebe decimal(18,2). Cada aprovação escreve atualização condicionada do limite (versão), AUTH, IDEMPOTENCY, OUTBOX, com uma única `TransactWriteItems` DynamoDB. ESTORNO também atualiza a autorização original (cinco ações).

**Idempotência:** Header `Idempotency-Key` UUID persistido com hash do payload; replay de aprovação retorna 200 com resposta original; repetição com payload diferente retorna 409. Recusa por insuficiência de limite é persistida e reexecutada como 402.

## Estrutura

```text
src/main/java/br/com/caseitau/auth/
  domain/
    ContractLimit.java          # aggregate root + invariantes
    Authorization.java          # entity
    OperationType.java          # enum de intenção
    Money.java                  # value object
  application/
    AuthorizationUseCase.java   # orquestração e porta AuthorizationStore
  api/
    AuthorizationController.java # REST + validação + Problem Details
  infrastructure/
    DynamoAuthorizationAdapter.java # AWS SDK e transação ACID
```

## Desenvolvimento local

```bash
cd apresentação-aws
docker compose up --build
bash scripts/demo-concorrencia.sh
```

Depois de iniciado, `contrato-demo` contém limite disponível R$ 7.000. Dois POST RESERVA de R$ 5.000 não podem ser aprovados ao mesmo tempo.

```bash
curl -i http://localhost:8080/v1/contratos/contrato-demo/autorizacoes \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 11111111-2222-4333-8444-555555555555' \
  -d '{"id_conta":"conta-demo","valor":5000.00,"moeda":"BRL","tipo_operacao":"RESERVA"}'
```

## Não está pronto para produção

A ACL de core real não está integrada à autorização (domínio de contrato ainda é stub); não há liquidação/posting; não há reconciliador automático após 24h do Streams; políticas regulatórias, antifraude pré-autorização, SLOs e 5.000 TPS precisam de validação. Cliente HTTP sem header `Idempotency-Key` é rejeitado pelo framework. A operação CREDITO/DÉBITO tem semântica de exemplo e **não deve receber dinheiro real sem aceite do negócio**.

O `authorization-limit-service` **não chama EventBridge diretamente**. Apenas grava Outbox. Streams/Pipes/EventBridge/SQS são configurados no Terraform AWS.
