# Comparativo arquitetural e FinOps — DynamoDB vs RDS PostgreSQL

**Status:** arquitetura-base atual = **DynamoDB + Streams + EventBridge Pipes + EventBridge**; **RDS PostgreSQL é alternativa** para comparação de custo/consistência. Não houve benchmark nem escolha definitiva para produção.

## Pergunta de decisão
Para a plataforma do case Itaú POD99, qual persistência atende com melhor combinação de custo total, atomicidade, rastreabilidade e SLO à autorização/reserva de limite por contrato (até 5.000 TPS em pico)?

## Mesma invariante financeira nas duas alternativas
Para um contrato com limite disponível de R$ 7.000, duas requisições simultâneas de R$ 5.000 **não podem ser aprovadas em conjunto**. Uma deve efetuar a reserva, a outra deve encontrar disponibilidade insuficiente ou sofrer conflito recuperável.

### DynamoDB
- Operação condicional do saldo: `disponivel >= valor`, implementada em `TransactWriteItems`.
- Na mesma transação, criar autorização, registro de idempotência e evento Outbox.
- Streams + Pipes transportam o evento depois do commit, sem integrar o commit à entrega.
- Custos de escritas transacionais aumentam conforme número/tamanho de itens e volume.
- Risco de contenção por `id_contrato`, hot keys e restrições do modelo de acesso.

### RDS PostgreSQL
- Uma transação SQL contém `UPDATE saldo ... WHERE disponivel >= valor`, `INSERT autorizacao`, `INSERT idempotencia` com chave única, `INSERT outbox`, seguido de `COMMIT`.
- `UPDATE` concorrentes sobre a mesma linha aguardam o lock e reavaliam a condição; só uma reserva pode passar quando a disponibilidade é insuficiente para ambas.
- Evento via relay Outbox ou CDC, com idempotência dos consumidores.
- Pagamento por instância(s), storage, IOPS/configuração, backup e disponibilidade; exige dimensionamento real de primário e standby para o pico.

## Critérios antes de escolher

| Critério | DynamoDB | RDS PostgreSQL |
|---|---|---|
| Transação ACID multientidade | TransactWriteItems, dentro das restrições DynamoDB | Transação SQL |
| Reserva condicional | Update condicionado | Update com predicado + row lock |
| Integridade por contrato | Chave/condição por item | Constraint, índice e atualização transacional |
| Desacoplamento de eventos | Streams + Pipes + EventBridge | Outbox + relay/CDC + EventBridge |
| Custo mínimo em baixa carga | Pode ter baixo custo, sem instância dedicada | Instância e storage persistem mesmo com baixa carga |
| Custo em carga intensa e estável | Uso de capacidade on-demand ou provisionada; modelar | Pode ganhar em custo unitário; testar capacidade e HA |
| Queries relacionais e auditoria | Exigem modelagem/indexação/visões especializadas | SQL oferece joins e consultas relacionais |
| Operação, patching e conexões | Gerenciadas, sem pool SQL | Exige gestão de versões, conexões, instâncias e failover |

## Teste a realizar
- Carga: 100, 1.000, 5.000 TPS (pico); medição com média e distribuição temporal realista.
- 10, 100, 1.000 operações concorrentes sobre **o mesmo contrato** e também sobre contratos distintos.
- p50/p95/p99, taxa de conflito, retries, custo por autorização aprovada, custo por rejeição, staleness e lag dos eventos.
- Injeção de falha entre commit e entrega do evento; replay idempotente.
- Comparação de HA em duas ou três zonas de disponibilidade, RPO/RTO e retenção regulatória.

## Situação da decisão
A [ADR-001](../adr/ADR-001-dynamodb-streams-eventbridge.md) documenta **DynamoDB como baseline para prosseguir os diagramas e a modelagem do case**, sem encerrar a avaliação. PostgreSQL permanece alternativa técnica e financeira; qualquer mudança de arquitetura-base exigirá revisão explícita da ADR e dos artefatos. Não concluir sobre custo apenas a partir de 10 milhões de operações nem da facilidade de integração.

Fontes: https://aws.amazon.com/dynamodb/pricing/ e https://aws.amazon.com/rds/postgresql/pricing/
