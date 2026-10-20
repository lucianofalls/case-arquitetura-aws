# Case de Arquitetura AWS — POD99 · Nova Plataforma de Finanças

> **Status:** arquitetura-alvo em estudo, sem microsserviços implementados ou recursos AWS provisionados.
>
> **Repositório público:** documentação independente de um case técnico. Não contém informações internas ou infraestrutura real do banco.

## Objetivo do case

Projetar e demonstrar autorização síncrona de operações financeiras, verificação e reserva de limites por contrato, concorrência segura, idempotência, integração com core banking e emissão assíncrona de eventos de domínio.

**Volumetria informada:** aproximadamente 80 milhões de contas, 3–5 contratos por conta e **pico** de 5.000 transações por segundo. Pico não significa TPS sustentado.

## Arquitetura-base atual — DynamoDB

A arquitetura de referência escolhida **para evoluir o desenho** é **Amazon EKS + DynamoDB + DynamoDB Streams + EventBridge Pipes + EventBridge + SQS**. O PostgreSQL permanece como **alternativa técnica e financeira**, não faz parte do fluxo principal.

```text
Cliente
  |
Amazon API Gateway + AWS WAF
  |
Integração privada (ALB/NLB conforme projeto)
  |
Amazon EKS (EC2, Multi-AZ)
  |
  +-- authorization-limit-service
  |     - módulo Authorization
  |     - módulo Contract Limits
  |     |
  |     +-- DynamoDB TransactWriteItems (mesmo account e região)
  |            1. UPDATE condicional do item LIMIT#contrato
  |            2. PUT autorização + dados da reserva
  |            3. PUT chave de idempotência
  |            4. PUT evento Outbox
  |            ---> COMMIT ATÔMICO; resposta síncrona
  |
  +-- core-banking-acl-service (adaptação ao legado)
  |
  +-- accounting-service (consumidor de eventos)

Após commit, sem bloquear a resposta:
DynamoDB Streams (captura de alterações confirmadas)
  |
EventBridge Pipes (filtro: INSERT de Outbox; transformação)
  |
Amazon EventBridge custom event bus
  |
Regras de roteamento
  +-- SQS Contabilidade --> accounting-service --> persistência própria
  +-- SQS Antifraude --> consumidor/integrador
  +-- SQS Notificações --> consumidor/integrador
  +-- SQS Data Platform (se incluída no escopo)
```

**Atenção:** no modelo de **quatro operações de escrita** acima, `LimitReservation` é **um conceito de domínio incorporado no item de autorização**; não é uma quinta gravação. Se a reserva ganhar um item independente, a transação e o custo deverão ser atualizados.

### Garantias e limitações

- A regra de saldo disponível mínimo é verificada no **`Update` condicional do item de limite**, dentro de `TransactWriteItems`.
- Uma transação confirma as quatro mudanças ou nenhuma. A consistência financeira não depende do Streams/EventBridge.
- A chave `Idempotency-Key` deve possuir registro durável no aplicativo; `ClientRequestToken` do SDK **sozinho não resolve** replays após a janela de dez minutos.
- Habilitar DynamoDB Streams e configurar Pipes é obrigatório; **DynamoDB não publica automaticamente no EventBridge**.
- Streams tem retenção de aproximadamente **24 horas**: definir monitoramento, DLQ/retry onde suportado e **reconciliação/republicação da Outbox persistida** para falhas prolongadas.
- Eventos e consumidores operam com semântica **at-least-once**, exigindo deduplicação e tolerância a reordenação; **não há exactly-once ponta a ponta**.
- Testar hot keys e concorrência concentrada por contrato antes de afirmar capacidade de 5.000 TPS.

## Relação BIAN → DDD → Serviços → Persistência

**BIAN** delimita funções bancárias de referência. **DDD** define bounded contexts e agregados. **EKS** executa os nossos serviços. **DynamoDB** mantém os dados do núcleo financeiro.

Os Service Domains BIAN não são templates de microsserviços e não exigem correspondência 1:1.

## Documentação

- [Tabelas: BIAN → DDD → EKS → DynamoDB](docs/arquitetura/tabelas-hierarquia-bian-ddd-aws.md)
- [Hierarquia e decisões de modelagem](docs/arquitetura/bian-ddd-microsservicos-banco.md)
- [ADR-001: arquitetura-base DynamoDB e publicação de eventos](docs/adr/ADR-001-dynamodb-streams-eventbridge.md)
- [FinOps: custos parciais da alternativa DynamoDB](docs/finops/estimativa-custos.md)
- [Alternativa: DynamoDB vs RDS PostgreSQL](docs/finops/dynamodb-vs-rds-postgresql.md)

## Decisões em aberto

Escolha definitiva da base de produção mediante benchmark/FinOps; contratos de domínio por produto; mapeamento operacional BIAN v14 detalhado; modelo de reservas/estorno; RPO/RTO; SLO; retenção financeira e auditoria; padrão de reconciliação da Outbox e processo de recuperação; dimensionamento de EKS/rede.

**Ainda não foram criados serviços, IaC, testes ou deploys**. O fluxo mostrado é proposta de arquitetura, não infraestrutura ativa.

## Fontes

- PDF do desafio: *POD99 — Nova Plataforma de Finanças · Arquiteto de Aplicação*.
- [BIAN Service Landscape](https://bian.org/deliverables/service-landscape/)
- [AWS Well-Architected Framework](https://docs.aws.amazon.com/wellarchitected/latest/framework/welcome.html)
- [DynamoDB TransactWriteItems](https://docs.aws.amazon.com/amazondynamodb/latest/APIReference/API_TransactWriteItems.html)
- [DynamoDB com EventBridge](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/eventbridge-for-dynamodb.html)
- [DynamoDB Stream como fonte do Pipes](https://docs.aws.amazon.com/eventbridge/latest/userguide/eb-pipes-dynamodb.html)
- [Transactional Outbox](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html)
