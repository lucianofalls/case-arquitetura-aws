# Arquitetura de Aplicação — BIAN, DDD, Clean/Hexagonal, SOLID e AWS Well-Architected

## Hierarquia correta

A aplicação NÃO 'herda' classes de Service Domains BIAN. BIAN é a **taxonomia de responsabilidades**; DDD identifica as **fronteiras de linguagem e de consistência**; Amazon EKS contém os **deployments**; DynamoDB é uma escolha de persistência do agregado.

| BIAN (Service Domain) | Bounded Context nosso | Serviço | Consistência |
|---|---|---|---|
| Transaction Authorization + aspectos de Position Keeping | AuthorizationAndLimitReservation | authorization-limit-service | LIMIT+AUTH+IDEMPOTENCY+OUTBOX, TransactWriteItems |
| Financial Accounting | AccountingIntegration | accounting-service | Inbox deduplicada, própria |
| Current Account / Consumer Loan etc. | CoreBankingIntegration | core-banking-acl-service | Adapter stateless, sem banco no MVP |
| Fraud Evaluation | Integração a domínio externo | Consumidor externo, fora do MVP | Eventos pós-autorização |

## Clean / Hexagonal

```text
Inbound adapters                   Application                  Domain                   Outbound adapter
api/AuthorizationController
    │                          application/AuthorizationUseCase
    └──── DTO + HTTP ───────────────> │
                                     ├──── Money / OperationType / ContractLimit
                                     │      - reserva, débito, crédito, estorno
                                     └──── port AuthorizationStore
                                              │
                                              └── infrastructure/DynamoAuthorizationAdapter
                                                   └── AWS SDK DynamoDB TransactWriteItems
```

O **domínio** não importa Spring nem AWS SDK. O **Application Use Case** não cria DynamoDbClient. O **adaptador** é substituível por um fake de teste e concentra operações atômicas.

## SOLID no código (com propósito)

| Princípio | Evidência | Motivo |
|---|---|---|
| S — Single Responsibility | `ContractLimit` valida invariantes; `AuthorizationUseCase` orquestra; `DynamoAuthorizationAdapter` persiste | Domínio não conhece HTTP nem infraestrutura |
| O — Open/Closed | Enum `OperationType` e transições declaradas em domínio; novas operações exigem testes e regras novas | Evolução controlada sem misturar regras |
| L — Liskov | `AuthorizationStore` define contrato para adaptadores; falsos implementadores de teste preservam resultados | Port substituível com semântica estável |
| I — Interface Segregation | Porta outbound dedicada a autorização/limite, não uma interface universal de banco | Casos de uso recebem só suas dependências |
| D — Dependency Inversion | `AuthorizationUseCase` depende de interface `AuthorizationStore` | AWS SDK fica na borda |

**Limite de SOLID:** OCP não significa suportar operações bancárias novas sem alterar regras — cada nova operação requer validação financeira explícita.

## Invariantes e semânticas propostas (validar com negócio)

- `available + reserved + used == total`, cada parcela não negativa e valor sempre inteiro em centavos.
- **RESERVA:** move de available → reserved.
- **DEBITO:** move de available → used (consumo direto, hipótese a homologar).
- **CREDITO:** move de used → available (recomposição de limite usado, não elevação do limite total).
- **ESTORNO:** reverte uma RESERVA ou DEBITO original, mesmo contrato/conta/valor e no máximo uma vez; crédito/estorno de estorno não tratados.
- Aprovação + reserva + idempotência + evento Outbox em um commit DynamoDB.
- Recusa por insuficiência é registrada com Idempotency-Key; replay reproduz recusa.
- **At-least-once:** consumidores devem deduplicar por evento. O consumidor do MVP apenas registra Inbox (não executa lançamentos contábeis).

## CQRS, Repository, Outbox e Event-Driven

- CQRS completo (bancos de comando/leitura diferentes) **não adotado neste MVP**: complexo e desnecessário na fronteira da reserva.
- Uso de port/repository nas operações de negócio; DynamoDB single-table no serviço financeiro.
- Outbox evita dual-write DynamoDB → EventBridge, mas Streams retém só 24 horas. Procedimento de reconciliação operacional é obrigatório.
- EventBridge + SNS/SQS fornecem distribuição desacoplada. Não há garantia global de ordem entre contratos; se ordem por contrato for fundamental, avaliar SQS FIFO/Kinesis/MSK com particionamento por contrato e custos.
- Eventos em CloudEvents, EventBridge Schema Registry de versão v1 e API OpenAPI contract-first. Registry **não garante validação runtime automática**: integrar validação nos pipelines/consumidores.

## AWS Well-Architected — seis pilares

| Pilar | Recursos do desenho | Evidência / validação pendente |
|---|---|---|
| Operational Excellence | Terraform IaC, GitHub Actions, CloudWatch logs EKS, ADRs | Runbooks, métricas por evento/limite, rollout GitOps |
| Security | WAF + Cognito, VPC Link, private subnets, IAM Pod Identity, DynamoDB criptografado | Revisão de política WAF, RBAC, Secrets, rede e acesso AWS |
| Reliability | EKS Multi-AZ, DynamoDB ACID+PITR, SQS DLQ, Outbox | Reconciliação >24h, DR, RPO/RTO, testes de falhas |
| Performance Efficiency | EKS Auto Mode, HPA CPU, DynamoDB key-value | Provar p95/p99 5.000 TPS, hot partition, escalabilidade por SQS backlog |
| Cost Optimization | On-demand DynamoDB, EKS Auto Mode, FinOps ADR | Comparar com RDS/Aurora, custo NAT + por evento, tagging |
| Sustainability | Escala por demanda e desligamento da demo | Medir utilização e horas ociosas, evitar recursos permanentes para case |

EKS Auto Mode é decisão adicional relativa ao desenho anterior com Managed Node Groups; não altera o fato de que os microsserviços rodam em EKS. É preciso precificar a taxa de gerenciamento Auto Mode e a capacidade EC2.

## Limites deliberados (não usar para clientes reais)

Validação de elegibilidade do contrato no core ainda não acoplada ao caminho crítico (o ACL está demonstrativo); antifraude antes da autorização não é definida no documento; integração de razão geral não implementada. SLO, RPO/RTO, dados regulatórios, API de administração e reconhecimento de publicação Outbox exigem aprovação do banco. Os testes de 5.000 TPS não foram executados.
