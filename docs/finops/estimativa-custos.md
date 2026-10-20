# FinOps — Estimativa preliminar de custos AWS

**Status:** hipótese arquitetural, sem implantação nem benchmark. **Referência:** preços públicos AWS em `us-east-1`, sem descontos; validar em [AWS Pricing Calculator](https://calculator.aws/) antes da defesa.

## 1. Premissas de volume
- O desafio define **pico** de ~5.000 TPS, não 5.000 TPS sustentados.
- Hipótese inicial: 1 aprovação = 4 itens DynamoDB de até 1 KiB, gravados em uma transação ACID: saldo, autorização/reserva, idempotência e Outbox.
- 4 gravações transacionais de 1 KiB = **8 write request units** (WRUs).
- 1 leitura fortemente consistente de até 4 KiB = 1 read request unit (RRU). Esta é uma simplificação: pode ser necessário ler mais dados ou nenhuma leitura prévia, conforme modelo.
- 1 evento Outbox = 1 solicitação EventBridge Pipes (evento até 64 KiB) e 1 evento customizado EventBridge.
- 3 consumidores independentes; cada SQS Standard usa Send, Receive e Delete por evento (9 requests por aprovação).
- Modelo assume aprovações, sem falhas, duplicações ou estornos; rejeições devem ser precificadas separadamente.

## 2. Tarifas ilustrativas (US East, sob demanda)

| Componente | Taxa de referência |
|---|---:|
| DynamoDB Standard WRU | US$ 0,625 / milhão |
| DynamoDB Standard RRU | US$ 0,125 / milhão |
| EventBridge Pipes | US$ 0,40 / milhão de solicitações faturáveis |
| EventBridge custom event | US$ 1,00 / milhão |
| Amazon SQS Standard | US$ 0,40 / milhão de requests |

Taxas indicativas conforme tabelas e exemplos públicos; confirmar vigência, volume faturável, tamanho dos payloads e possíveis franquias. O DynamoDB Streams e os serviços de transporte possuem nuances de cobrança e isenções específicas.

## 3. Projeções variáveis (somente operações, sem storage e EKS)

| Autorizações aprovadas por mês | DynamoDB writes + reads | Pipes + Bus + SQS | Subtotal |
|---:|---:|---:|---:|
| 1.000.000 | US$ 5,13 | US$ 5,00 | US$ 10,13 |
| 10.000.000 | US$ 51,25 | US$ 50,00 | US$ 101,25 |
| 100.000.000 | US$ 512,50 | US$ 500,00 | US$ 1.012,50 |
| 1.000.000.000 | US$ 5.125,00 | US$ 5.000,00 | US$ 10.125,00 |
| 12.960.000.000 (5.000 TPS **sustentados** durante 30 dias) | US$ 66.420,00 | US$ 64.800,00 | US$ 131.220,00 |

**Observação:** o subtotal não é custo total da plataforma. A simulação anterior também não incluía armazenamento e a maioria das despesas de auditoria e operação.

## 4. Custos que faltam (TCO)
- DynamoDB: armazenamento dos itens (inclusive reservas e Outbox), GSIs, PITR, backups, streams, exportações, retries, replicação se houver, idempotência.
- EKS: control plane, nodes EC2, EBS, HPA/Karpenter, disponibilidade Multi-AZ, Observabilidade, CloudWatch, traces, logs.
- Exposição: API Gateway, WAF, ALB/NLB, VPC Link, NAT, endpoints e tráfego.
- Segurança e eventos: KMS, Secrets Manager, EventBridge, SQS, DLQ, replay, retries, data transfer.
- Se alternativa for RDS PostgreSQL: horas das instâncias de primary e standby Multi-AZ, armazenamento, IOPS, snapshots, WAL, conexões, observabilidade e publicação Outbox (polling ou CDC).

## 5. Conclusão provisória
**Não está provado que DynamoDB seja mais barato que RDS PostgreSQL.** O DynamoDB oferece baixa administração e capacidade sob demanda, mas seu custo escala com a quantidade e o tamanho das operações. No PostgreSQL a escrita não tem cobrança direta por unidade, porém a computação provisionada, capacidade do primário e configuração Multi-AZ devem suportar o mesmo TPS e a mesma consistência.

Para uma comparação justa, fixar o mesmo perfil: TPS médio e pico, concorrência por contrato, porcentagem de aprovações/rejeições, quantidade de gravações, retenção, tamanho dos dados, SLA de latência p95/p99, alta disponibilidade e falhas.

## 6. Referências
- https://aws.amazon.com/dynamodb/pricing/
- https://aws.amazon.com/rds/postgresql/pricing/
- https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_OnDemandDBInstances.html
- https://aws.amazon.com/eventbridge/pricing/
- https://aws.amazon.com/sqs/pricing/
- https://aws.amazon.com/eks/pricing/
- https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html
