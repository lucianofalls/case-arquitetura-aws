# ADR-003 — EventBridge+SQS versus Kinesis e Amazon MSK

**Status:** escolha para demonstração com escalabilidade e ordenação ainda não comprovadas.

O contrato de evento do PDF requer EventBridge, fan-out, CloudEvents e entrega at-least-once. A solução usa DynamoDB Streams → Pipes → EventBridge → SQS (e SNS para notificações).

| Solução | Vantagens | Custos/limites | Decisão |
|---|---|---|---|
| EventBridge + SQS Standard | Filtro por regra e filas independentes, retries, DLQ, baixo acoplamento | Eventos podem chegar fora de ordem; SQS standard é at-least-once | **Baseline** |
| SQS FIFO | Deduplicação/ordem por grupo | Throughput/latência e semântica de agrupamento; eventual fan-out requer desenho adicional | Avaliar quando houver requisito de ordem por contrato |
| Kinesis Data Streams | Ordem dentro de shard/chave, replay e volume alto | Shards/particionamento, consumidores dedicados, custos | Alternativa se stream ordenado for requisito |
| Amazon MSK | Partições por contrato, ecossistema Kafka/retentiva | Operação/cluster, particionamento, custo maior de plataforma | Usar somente se houver ecossistema Kafka/contratos que justifiquem |

A Outbox é **imutável**. O consumidor usa `event_id` para deduplicar e eventual `aggregate_version` para reordenação. A recuperação de lacuna de Streams após 24h exige reconciliação independente.

**Não inferir que EventBridge/SQS preservam ordem global**. A especificação do case exige consumidores tolerantes à reordenação.
