# Hierarquia de nomes: case → BIAN v14 → DDD → microsserviços → PostgreSQL

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

## 3. Da referência BIAN aos nomes implementados

Os três microsserviços inicialmente propostos continuam sendo:

| Serviço no EKS (nosso nome) | Referência BIAN | Bounded Context / módulo DDD | Agregados / entidades próprios | Persistência proposta |
|---|---|---|---|---|
| `authorization-limit-service` | Transaction Authorization + regras de contrato + aspectos de Position Keeping | Bounded Context **AuthorizationAndLimitReservation**, módulos `authorization` e `limits` | `ContractLimit` (aggregate root), `Authorization`, `LimitReservation`, `IdempotencyRecord`, `OutboxEvent` | `authorization_limits_db` PostgreSQL |
| `accounting-service` | Financial Accounting | `AccountingIntegration` (integração, sem recriar razão contábil do banco) | `ConsumedEvent`, `AccountingInstruction` | `accounting_integration_db` PostgreSQL |
| `core-banking-acl-service` | Current Account e Consumer Loan, conforme contrato/produto | `CoreBankingIntegration` | Mapeadores/DTOs e contratos de integração; sem agregado persistido no MVP | Sem banco próprio no MVP, por ser stateless |

A função de **Position Keeping** é mais ampla do que nosso agregado `ContractLimit`: no BIAN acompanha transações, posições e bloqueios. No MVP, reusamos o conceito para conservar a posição de limite local; não alegamos implementar todo o Service Domain.

O BIAN **não determina** quantos deployments/pods construir. Usamos **um único limite ACID no serviço crítico** para a autorização mais reserva; forçar dois bancos independentes sem coordenação abriria uma janela de inconsistência.

### Uma trilha completa de nomeação — operação de reserva

```text
Caso de negócio (nome do documento)
  Plataforma de Gestão de Limites e Autorização de Transações
    ↓ referência funcional BIAN
  Channels > Cross Channel > Transaction Authorization
  Operations > Accounting Services > Position Keeping
  Products > Loans and Deposits > Consumer Loan (se crédito ao consumidor)
    ↓ modelo da solução, NÃO nomenclatura BIAN
  Bounded Context: AuthorizationAndLimitReservation
    ↓
  Aggregate Root: ContractLimit
    - Entities: Authorization, LimitReservation
    - Value Objects: Money, ContractId, AuthorizationId
    - Invariantes: disponível >= 0; reserva não excede disponível
    ↓
  Microsserviço: authorization-limit-service
    ↓
  Amazon EKS: deployment/authorization-limit-service
    ↓
  Amazon RDS for PostgreSQL (candidato para MVP)
    Database: authorization_limits_db
    Schema: core
    Tables:
      contract_limit
      authorization
      limit_reservation
      idempotency_record
      outbox_event
```

### Outra trilha — contabilização

```text
Operations > Accounting Services > Financial Accounting
    ↓
  Bounded Context: AccountingIntegration
    ↓
  ConsumedEvent / AccountingInstruction
    ↓
  Microsserviço: accounting-service
    ↓
  Amazon EKS: deployment/accounting-service
    ↓
  PostgreSQL database: accounting_integration_db
    schema: integration
      consumed_event
      accounting_instruction
```

A contabilização é **consumidora do evento**, não parte da transação que reserva o limite. O sistema de razão geral pode permanecer legado.

## 4. Por que PostgreSQL neste recorte?

**Candidato preferencial para implementação do case, não escolha definitiva de produção:** PostgreSQL local para demonstrar e Amazon RDS for PostgreSQL Multi-AZ como desenho AWS.

Motivos:
- `UPDATE ... WHERE available_amount >= :amount` sob transação controla concorrência por linha do contrato.
- `INSERT` da autorização, reserva, idempotência e Outbox ocorre no **mesmo COMMIT**, evitando dual-write.
- SQL, constraints e relações permitem demonstrar claramente invariantes, estornos e auditoria.
- Um ambiente local em Docker/PostgreSQL permite prova reprodutível durante o prazo de 5 dias úteis.

**Trade-offs:** 80 milhões de contas e possíveis centenas de milhões de contratos exigem dimensionamento, particionamento/indexação e benchmark. Um banco relacional tem limites de throughput do primário; **5.000 TPS de pico não foram comprovados** em RDS. DynamoDB permanece alternativa válida e deve ser comparado com o mesmo perfil de carga e SLO.

Os preços estimados de DynamoDB estão em [FinOps](../finops/estimativa-custos.md) e o comparativo em [DynamoDB vs RDS PostgreSQL](../finops/dynamodb-vs-rds-postgresql.md).

## 5. Onde ocorre a transação e a publicação?

```text
API Gateway + WAF
   → private integration → EKS: authorization-limit-service
       → RDS PostgreSQL / authorization_limits_db (ACID):
           conditional UPDATE contract_limit
           INSERT authorization
           INSERT limit_reservation
           INSERT idempotency_record
           INSERT outbox_event
           COMMIT
       ← HTTP 201 (após commit)

   [assíncrono]
   outbox publisher/relay (pod worker, com lease/retry e idempotência)
       → EventBridge event bus → regras → SQS por consumidor
       → accounting-service → accounting_integration_db
       → antifraude/notificações/data platform (integrações)
```

Se houver falha após o commit e antes da publicação, o evento continua na Outbox e poderá ser reenviado. **Não há garantia exactly-once de ponta a ponta:** consumidores devem deduplicar por event_id.

## 6. Banco por serviço: regra de propriedade, não isolamento obrigatório por instância

- `authorization-limit-service`: único dono das tabelas de autorização/limite/reserva/Outbox.
- `accounting-service`: único dono de inbox e instruções contábeis.
- `core-banking-acl-service`: sem banco caso seja exclusivamente adaptador stateless.
- Os schemas/databases podem compartilhar instância **no ambiente de demonstração** com permissões distintas. Em produção, isolamento e dimensionamento são reavaliados.

**Não deixar o accounting-service realizar joins diretos no database do núcleo.** Integração apenas via API ou eventos.

## 7. A decisão ainda aberta

Caso se decida por microsserviços independentes `authorization-service` e `limit-service`, explicitar:
1. qual serviço pode confirmar a aprovação;
2. onde fica o registro canônico da autorização;
3. como lidar com falha entre reservar e persistir a decisão;
4. mecanismo de compensação/reconciliação;
5. quem produz `TransacaoAutorizada` sem risco de evento falso.

Por isso, para o MVP, a proposta mantém dois módulos do domínio com uma **fronteira única de transação e deploy**, deixando a extração futura condicionada a requisitos reais.

## 8. Referências

- PDF fornecido: *Desafio Técnico POD99 — Nova Plataforma de Finanças, Arquiteto de Aplicação*, 5 páginas.
- https://bian.org/deliverables/service-landscape/
- https://docs.aws.amazon.com/wellarchitected/latest/framework/welcome.html
- https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html
