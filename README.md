# Case de Arquitetura AWS — Itaú POD99

> **Status:** arquitetura-alvo em refinamento. Nenhum microsserviço ou recurso AWS foi implantado.
>
> **Aviso:** este repositório está **público**. O conteúdo descreve uma proposta técnica baseada no enunciado do desafio; não apresenta infraestrutura ou informação interna do banco.

## Objetivo de negócio

Projetar uma plataforma de autorização síncrona de transações financeiras, gerenciamento de limites por contrato, concorrência consistente, idempotência e publicação assíncrona de eventos contábeis.

**Requisitos de escala apresentados no desafio:** cerca de 80 milhões de contas ativas, 3 a 5 contratos por conta e pico de aproximadamente 5.000 TPS. **Pico de TPS não é média mensal.**

## Visão da solução (proposta em estudo)

```text
Clientes → API Gateway / WAF → Amazon EKS (nós EC2)
                                      |
                         Authorization + Contract Limits
                                      |
                          PostgreSQL (transação ACID)
                                      |
                        Outbox na mesma transação
                                      |
                              Outbox relay
                                      |
                            EventBridge Bus
                              /     |     \
                            SQS    SQS    SQS
                             |      |      |
                      Contabilidade Fraude Notificações
```

A autorização é síncrona e a integração com os consumidores é assíncrona. Na proposta atual para o MVP, a transação ACID termina no PostgreSQL; mensageria tem semântica *at-least-once*, exigindo idempotência e reconciliação.

## Documentação

- [Tabelas e hierarquia BIAN → DDD → EKS → PostgreSQL](docs/arquitetura/tabelas-hierarquia-bian-ddd-aws.md)
- [Fundamentação detalhada da hierarquia e fronteiras DDD](docs/arquitetura/bian-ddd-microsservicos-banco.md)
- [FinOps: custos e premissas](docs/finops/estimativa-custos.md)
- [Comparação DynamoDB vs RDS PostgreSQL](docs/finops/dynamodb-vs-rds-postgresql.md)

## Decisões candidatas e pendências

- **Runtime candidato:** Amazon EKS com nós EC2, HPA e Karpenter (depende de testes).
- **Persistência candidata para o MVP:** PostgreSQL em Docker; RDS for PostgreSQL Multi-AZ como desenho de produção, sujeito a benchmark e avaliação de custos. DynamoDB permanece como alternativa aberta.
- **Integração candidata com PostgreSQL:** Transactional Outbox → relay/CDC → EventBridge → filas SQS. **Alternativa DynamoDB:** Streams → EventBridge Pipes → EventBridge → SQS.
- **BIAN:** mapeamento é referência funcional; Service Domains não implicam necessariamente microsserviços um-para-um.
- **Ainda não definidos:** operações/Control Records detalhados de cada Service Domain BIAN v14, RTO/RPO, SLO p95/p99, retenção de auditoria, regras exatas de estorno, TPS médio, perfil de concorrência, despesas completas AWS e decisão definitiva do banco.
- **Execução do case:** entregar código e testes, IaC, diagramas, ADRs e demonstração local ou AWS em cinco dias úteis.

## Fontes

- Enunciado: *Desafio Técnico — POD99, Nova Plataforma de Finanças, Arquiteto de Aplicação* (5 páginas).
- [AWS Well-Architected Framework](https://docs.aws.amazon.com/wellarchitected/latest/framework/welcome.html)
- [BIAN Service Landscape](https://bian.org/deliverables/service-landscape/)
- [AWS Transactional Outbox](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html)

Este repositório é um estudo/case independente, não uma implementação oficial do Itaú.
