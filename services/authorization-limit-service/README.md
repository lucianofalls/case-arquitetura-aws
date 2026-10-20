# authorization-limit-service

**Estado:** primeiro slice de código, ainda sem build/execução validado neste ambiente.

- Java 17, Spring Boot, AWS SDK v2.
- Bounded Context proposto: `AuthorizationAndLimitReservation`.
- Primeira operação implementada: `RESERVA`. DEBITO/CREDITO/ESTORNO retornam HTTP 501 até definição de semântica financeira.
- DynamoDB `TransactWriteItems` (4 itens): LIMIT condicional, AUTH com estado de reserva, IDEMPOTENCY persistente, OUTBOX.
- O serviço **não publica EventBridge diretamente**; infraestrutura de Streams e Pipes virá em IaC separada.
- Respostas: 201 nova reserva, 200 replay, 402 limite insuficiente, 404 contrato ausente, 409 conflito ou Idempotency-Key reutilizada com payload distinto, 422 payload inválido e 501 operação não implementada.

## Exemplo

```bash
curl -i http://localhost:8080/v1/contratos/contrato-demo/autorizacoes \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 11111111-2222-4333-8444-555555555555' \
  -H 'X-Correlation-Id: teste-01' \
  -d '{"id_conta":"conta-demo","valor":5000.00,"moeda":"BRL","tipo_operacao":"RESERVA"}'
```

Tabela e conta pré-populadas são necessárias, veja `docker-compose.yml` e `scripts/bootstrap-ddb-local.sh`.

## Limitações conhecidas

- Recusas por falta de limite ainda não são gravadas para replay idempotente imutável; requisito pendente antes de produção.
- Sem implementação de estorno/captura/liquidação.
- Integração ACL pré-autorização ainda não conectada.
- CloudEvent armazenado no atributo `payload` Outbox; Pipes deve filtrar `NewImage.entityType.S=OUTBOX` e transformar adequadamente para EventBridge.
- Sem reconciliador Outbox para falhas acima das 24h do Streams; não considerar sistema pronto para produção.
- DynamoDB Local não é equivalência perfeita de operação e escala DynamoDB AWS.
- Ainda falta teste de concorrência real no DynamoDB Local e benchmark de 5.000 TPS.
