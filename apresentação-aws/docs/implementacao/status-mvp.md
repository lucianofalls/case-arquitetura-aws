# Escopo de implementação incremental — POD99

**Objetivo:** construir uma demonstração pequena, honesta e verificável, não simular 80 milhões de contas nem alegar 5.000 TPS sem teste.

## Ordem dos incrementos

| Incremento | Componente | Critério de aceite |
|---|---|---|
| 1 | `authorization-limit-service` | Endpoint POST, validade da idempotência, RESERVA condicional DynamoDB, Outbox na transação |
| 2 | `accounting-service` | Ler da SQS; deduplicar event_id; persistir evento recebido, sem afirmar escrituração bancária |
| 3 | `core-banking-acl-service` | Interface adapter/mapeamento e endpoint demo; legado real não conectado |
| 4 | Infra AWS | EKS, tabela Streams, Pipes, EventBridge, SQS, IAM/Pod Identity, metrics, DLQ e deploy automatizados |
| 5 | Consistência completa | Ciclo de vida de DEBITO/CREDITO/RESERVA/ESTORNO, rejeições idempotentes, estorno concorrente |
| 6 | Operação financeira | Reconciliador de Outbox >24h, métricas, auditoria, segurança, SLO e disaster recovery |
| 7 | Teste escalabilidade | Testes de 5.000 TPS e hot contract; FinOps versus PostgreSQL com carga idêntica |

## Honestidade de entrega

Código-fonte, arquivos de teste e definições de ambiente **não equivalem a build/teste executados nem a serviços implantados**. Acompanhar builds GitHub Actions quando forem configurados. Código pode ser revisado antes de executar `mvn test` ou `docker compose up --build`.

## Comandos previstos

```bash
cd services/authorization-limit-service
mvn test
mvn spring-boot:run

# Para ambiente local isolado:
docker compose up --build
```

Até configurar SQS local ou cloud, o consumidor é executável apenas quando a fila existe e as credenciais AWS estão disponíveis.
