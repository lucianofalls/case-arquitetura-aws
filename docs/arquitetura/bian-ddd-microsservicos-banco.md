# Hierarquia de nomes: case → BIAN v14 → DDD → microsserviços → DynamoDB

> **Status:** proposta de arquitetura para discussão; **nenhum serviço ou banco foi implantado**. Não confundir classificações BIAN com nomes que criamos neste projeto. Este repositório é público.

## 1. Origem: documento de requisitos

O PDF *Desafio Técnico — POD99, Nova Plataforma de Finanças, Arquiteto de Aplicação* descreve uma **Plataforma de Gestão de Limites e Autorização de Transações**, responsável por:
- autorização síncrona e decisão de aprovação/recusa;
- verificação e reserva de limite **por contrato** sob concorrência;
- idempotência de todas as operações mutantes;
- operações `DEBITO`, `CREDITO`, `RESERVA`, `ESTORNO`;
- eventos `TransacaoAutorizada`, limite alterado e estorno para retaguarda;
- integração de sistemas de core banking via anticorruption layer;
- pico estimado de 5.000 TPS, 80 milhões de contas, 3–5 contratos/conta;
- DDD, microsserviços e estratégia de persistência *database-per-service*.

O enunciado **não escolhe** BIAN, Bounded Contexts específicos ou SGBD. O mapeamento BIAN abaixo é uma proposta nossa baseada no v14.

## 2. As duas hierarquias do BIAN

O Business Capability Model representa **o que** o banco precisa fazer. Não o confundir com a árvore do Service Landscape. Nosso agrupamento `Gestão de Limites e Autorização de Transações` é o **nome do case**, não um Business Capability padronizado como tal pelo BIAN.

**No BIAN Service Landscape Value Chain View v14**, a hierarquia formal é:

```text
Business Area
  └── Business Domain
        └── Service Domain
              ├── Control Record / Behavior Qualifiers (quando definidos pelo domínio)
              └── Service Operations / Semantic APIs
```

Recortes verificáveis:

| Business Area v14 | Business Domain v14 | Service Domain v14 | Natureza |
|---|---|---|---|
| Channels | Cross Channel | Transaction Authorization | Avaliação/autorização de transações interativas |
| Operations | Accounting Services | Position Keeping | Registros de movimentos, posições, limites e bloqueios |
| Operations | Accounting Services | Financial Accounting | Conversão de fatos financeiros em instruções contábeis |
| Operations | Accounting Services | Fraud Evaluation | Testes de comportamento e suspeita de fraude |
| Products | Consumer Banking | Current Account | Ciclo de vida operacional de conta corrente de consumidor |
| Products | Loans and Deposits | Consumer Loan | Ciclo de vida de empréstimo ao consumidor |
| Products | Corporate Banking | Credit Facility | Facilidade de crédito **corporativo**; não é contrato genérico |
| Finance And Risk Management | Market Risk | Limit and Exposure Management | Gestão de limites/exposição agregados de risco; não é a reserva operacional de um contrato |

**Atenção:** esses caminhos usam a versão *Value Chain*, não o antigo layout *Matrix* (cujas áreas e domínios de agrupamento podem ter nomes diferentes).

Fontes:
- https://bian.org/servicelandscape-14-0-0/views/view_54486.html
- https://bian.org/servicelandscape-14-0-0/views/view_54028.html
- https://bian.org/servicelandscape-14-0-0/views/view_54342.html
- https://bian.org/servicelandscape-14-0-0/views/view_53608.html
- https://bian.org/servicelandscape-14-0-0/object_33.html?object=34194
- Transcrição de referência da hierarquia da Value Chain v14: https://github.com/OpenDigitalProductFactory/opendigitalproductfactory/blob/main/docs/Reference/bian/bian-v14-service-landscape.json

## 3. Dos domínios BIAN aos microsserviços propostos

**Arquitetura-base atual:** Amazon EKS, DynamoDB (`TransactWriteItems`), DynamoDB Streams, EventBridge Pipes, EventBridge e filas SQS. PostgreSQL é **alternativa comparativa**, não fluxo principal.

| Microsserviço proposto | Referência funcional BIAN | Bounded Context / módulo DDD | Modelo de domínio | Persistência candidata |
|---|---|---|---|---|
| `authorization-limit-service` | Transaction Authorization; aspectos de Position Keeping; regras do produto | `AuthorizationAndLimitReservation`, módulos `authorization` e `limits` | `ContractLimit`, `Authorization`, `LimitReservation`, `IdempotencyRecord`, `OutboxEvent` | DynamoDB: tabela `financial-authorization` |
| `accounting-service` | Financial Accounting | `AccountingIntegration` | `ConsumedEvent`, `AccountingInstruction` | Persistência própria: tabela DynamoDB `accounting-processing`, a validar |
| `core-banking-acl-service` | Current Account, Consumer Loan ou outro conforme produto | `CoreBankingIntegration` | Adaptadores e traduções de modelos legados | Inicialmente stateless |

Os nomes de microsserviços, contextos e tabelas **não pertencem ao BIAN**. O enunciado usa serviços de autorização, limites e contabilização como exemplos, não obriga implantação 1:1.

**Por que juntar dois módulos no `authorization-limit-service`?** A decisão da autorização e a reserva do limite precisam ser coerentes. No primeiro recorte, o microsserviço executa uma única transação sobre os itens de que é proprietário; separar dois deploys com bancos independentes exigiria protocolo adicional de coordenação, reconciliação e recuperação de falha.

## 4. Hierarquia até a persistência

```text
[CASE] Plataforma de Gestão de Limites e Autorização de Transações
    ↓ referências BIAN (não herança automática)
[BIAN] Channels > Cross Channel > Transaction Authorization
[BIAN] Operations > Accounting Services > Position Keeping
[BIAN] Products > Loans and Deposits > Consumer Loan (quando aplicável)
    ↓ interpretação DDD nossa
[DDD] Bounded Context: AuthorizationAndLimitReservation
      Aggregate Root: ContractLimit
      Entidades/conceitos: Authorization, LimitReservation
      Value Objects: Money, ContractId, AuthorizationId
    ↓
[EKS] Deployment: authorization-limit-service
    ↓
[DynamoDB] Tabela: financial-authorization
           Itens lógicos: LIMIT, AUTH, IDEMPOTENCY, OUTBOX
    ↓ CDC após commit
[DynamoDB Streams] → [EventBridge Pipes] → [EventBridge Bus] → [SQS]
    ↓
[EKS] accounting-service → persistência própria accounting-processing
```

**Não utilizar** `authorization_limits_db`, `schema core` ou tabelas SQL como modelo físico DynamoDB. Eles pertencem apenas à alternativa PostgreSQL.

## 5. Modelo de itens (candidato)

| Tipo lógico | Partition Key (exemplo) | Sort Key | Responsabilidade |
|---|---|---|---|
| `LIMIT` | `CONTRACT#<id_contrato>` | `LIMIT` | Saldo disponível e reservado, em centavos inteiros |
| `AUTH` | `AUTH#<id_autorizacao>` | `META` | Autorização e estado da reserva |
| `IDEMPOTENCY` | `IDEMPOTENCY#<consumidor>#<UUID>` | `REQUEST` | Hash do payload, resultado original, referência à autorização |
| `OUTBOX` | `OUTBOX#<event_id>` | `EVENT` | Evento de negócio versionado e dados para publicação |

Os tipos lógicos podem estar em **uma tabela DynamoDB**; não são quatro tabelas físicas. O conceito DDD `LimitReservation` fica incorporado em `AUTH` nesta **hipótese de quatro escritas**. Se a reserva exigir item próprio, a transação e o custo serão atualizados. A distribuição de chaves, consultas por contrato, possíveis GSIs e hot keys ainda precisam de desenho e benchmark.

## 6. Garantia de concorrência

Exemplo do case: contrato com R$ 7.000 de limite disponível, duas requisições simultâneas de R$ 5.000.

Uma chamada **`TransactWriteItems`** inclui:

1. `Update` do item `LIMIT` **com condição `available_minor_units >= amount_minor_units`**, reduzindo disponível e aumentando reservado, na mesma ação.
2. `Put` do item `AUTH`, registrando a autorização e os dados de reserva.
3. `Put` de `IDEMPOTENCY` com condição de chave inexistente.
4. `Put` do item `OUTBOX` com evento `TransacaoAutorizada` v1.

**Atomicidade:** ou as quatro ações confirmam ou nenhuma confirma. Não realizar um `ConditionCheck` separado e um `Update` sobre o **mesmo item**, o que o DynamoDB não permite.

A segunda requisição não pode confirmar reserva além do saldo disponível. Uma falha pode representar condição não atendida ou conflito transacional; o serviço deve distinguir insuficiência de limite (HTTP 402) de conflito transitório (HTTP 409 e eventual retry controlado). A `Idempotency-Key` da API precisa ser durável; o `ClientRequestToken` do DynamoDB possui janela de dez minutos e não a substitui. O tratamento idempotente de recusas também deve ser detalhado.

## 7. Como chegam os eventos ao EventBridge

```text
authorization-limit-service
  │ TransactWriteItems (inclui OUTBOX)
  ├─→ resposta síncrona depois do commit
  └─→ DynamoDB Streams habilitado
         │ registros de alterações
         ▼
       EventBridge Pipes (filtra NewImage do tipo OUTBOX + transforma payload)
         │
         ▼
       EventBridge custom event bus (roteamento por regra)
         ├─→ SQS Contabilidade → accounting-service → banco próprio
         ├─→ SQS Antifraude → consumidor/integrador
         ├─→ SQS Notificações → consumidor/integrador
         └─→ SQS Data Platform → consumidor/integrador
```

O **DynamoDB não publica automaticamente no EventBridge**: Streams e Pipes precisam ser habilitados e configurados. O `OUTBOX` é uma representação persistida do evento de domínio. Streams mantém alterações por aproximadamente **24 horas**: o projeto precisa de monitoração de atraso, política de falhas/retries/DLQ, retenção e **reconciliação/republicação de eventos persistidos** para interrupções prolongadas. Entrega e processamento são **at-least-once**, não exactly-once.

## 8. Papel de PostgreSQL e da comparação de custos

PostgreSQL poderia garantir a mesma invariante com `UPDATE` condicional dentro de uma transação SQL e Outbox na mesma transação, mas exigiria relay ou CDC. Isso é uma **alternativa** documentada em [DynamoDB vs RDS PostgreSQL](../finops/dynamodb-vs-rds-postgresql.md). **Nenhuma mudança de banco foi aprovada.**

A baseline DynamoDB não é prova de menor custo ou maior throughput: testar pico de 5.000 TPS, distribuição por contrato, latência, hot keys, capacidade, índices, retenção e TCO AWS.

## 9. Referências e pendências

- PDF: `Desafio Técnico — POD99, Nova Plataforma de Finanças, Arquiteto de Aplicação` (5 páginas).
- [BIAN Service Landscape](https://bian.org/deliverables/service-landscape/)
- [AWS TransactWriteItems](https://docs.aws.amazon.com/amazondynamodb/latest/APIReference/API_TransactWriteItems.html)
- [Integração DynamoDB com EventBridge](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/eventbridge-for-dynamodb.html)
- [Streams como fonte EventBridge Pipes](https://docs.aws.amazon.com/eventbridge/latest/userguide/eb-pipes-dynamodb.html)
- [Transactional Outbox](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html)

Pendentes: operações oficiais dos Service Domains BIAN v14, contratos detalhados por produto, ciclo de reserva/estorno, idempotência de recusa, dimensionamento, custos, rastreabilidade e estratégia de recuperação Outbox.
