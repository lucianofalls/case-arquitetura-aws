# Tabelas de rastreabilidade — BIAN → DDD → EKS → PostgreSQL

> **Status:** proposta para o desafio técnico POD99. **Nada está implantado.**
> Origem de requisitos: PDF *Desafio Técnico — POD99, Nova Plataforma de Finanças · Arquiteto de Aplicação* (5 páginas).
> O enunciado não prescreve BIAN, nomes de Bounded Contexts, EKS ou PostgreSQL. São opções de modelagem e implementação a justificar.
>
> **Convenção:** **[BIAN]** = referência de Service Landscape; **[CASE]** = exigência do PDF; **[SOLUÇÃO]** = nomenclatura/decisão nossa.

## 1. Hierarquia e o motivo de cada nível

| Nível | Nome | Origem | Por que usar |
|---|---|---|---|
| 1 | Business Capability | BCM do BIAN, relacionado ao problema | Descrever **o que** o banco precisa fazer, sem amarrar tecnologia |
| 2 | Business Area | **[BIAN]** Service Landscape | Agrupar grandes áreas funcionais da instituição |
| 3 | Business Domain | **[BIAN]** Service Landscape | Identificar o agrupamento funcional de cada Service Domain |
| 4 | Service Domain | **[BIAN]** Service Landscape | Usar linguagem de referência para responsabilidades bancárias |
| 5 | Bounded Context | **[SOLUÇÃO]** DDD | Delimitar modelo de domínio, linguagem e propriedade dos dados |
| 6 | Aggregate / Entity / Value Object | **[SOLUÇÃO]** DDD | Definir invariantes, consistência e ciclo de vida |
| 7 | Microsserviço no Amazon EKS | **[SOLUÇÃO]** AWS | Executar componentes com interfaces, deploy e propriedade definidos |
| 8 | Database / Schema / Table | **[SOLUÇÃO]** persistência | Persistir estados, chaves de idempotência, reservas e eventos Outbox |

**Atenção:** o *Business Capability Model* (BCM) e o *BIAN Service Landscape* (BSL) são visões relacionadas, **não uma árvore única oficial**. O nome `Plataforma de Gestão de Limites e Autorização de Transações` é dado pelo **case**, não um item padronizado do BCM. Um Service Domain BIAN **não precisa corresponder a um microsserviço**.

## 2. BIAN Service Landscape — recorte para o desafio

| Business Area **[BIAN]** | Business Domain **[BIAN]** | Service Domain **[BIAN]** | Responsabilidade e motivo no case |
|---|---|---|---|
| Channels | Cross Channel | **Transaction Authorization** | Referência para o ato de **autorizar** a transação |
| Operations | Accounting Services | **Position Keeping** | Referência para manter posições, movimentos e bloqueios; **não significa** que o nosso MVP implementa o domínio inteiro |
| Operations | Accounting Services | **Financial Accounting** | Receber e transformar fatos para processamento contábil, após autorização |
| Operations | Accounting Services | **Fraud Evaluation** | Potencial consumidor de evento ou integração de avaliação de fraude |
| Products | Consumer Banking | **Current Account** | Conta envolvida na transação, conforme produto do cliente |
| Products | Loans and Deposits | **Consumer Loan** | Produto de crédito ao consumidor, quando o contrato corresponder a essa modalidade |
| Products | Corporate Banking | **Credit Facility** | Facilidade de crédito corporativo; **não utilizar como sinônimo genérico de contrato** |
| Finance And Risk Management | Market Risk | **Limit and Exposure Management** | Exposição/limites agregados de risco; **não confundir** com a reserva transacional por contrato |

**Escopo:** o PDF cita crédito, investimento, seguros e consórcio. **Não mapear todos os produtos para Credit Facility ou Consumer Loan.** Esses Service Domains são referências conforme o tipo de contrato. O case exige a plataforma de limites/autorização e a integração, não a reconstrução integral dos produtos de origem.

**Fonte de consulta:** [BIAN Service Landscape](https://bian.org/deliverables/service-landscape/), v14, visualização *Value Chain*. A confirmação de Service Operations, Control Records e Behavior Qualifiers específicos permanece pendente de extração do catálogo oficial por Service Domain.

## 3. Dos Service Domains aos Bounded Contexts e microsserviços

| Microsserviço proposto **[SOLUÇÃO]** | Referência funcional **[BIAN]** | Bounded Context / módulo **[SOLUÇÃO]** | Agregados/objetos candidatos | Persistência proposta |
|---|---|---|---|---|
| `authorization-limit-service` | Transaction Authorization; Position Keeping; regras do produto contratual | `AuthorizationAndLimitReservation`, módulos `authorization` e `limits` | `ContractLimit` (raiz do agregado de limite), `Authorization`, `LimitReservation`, `IdempotencyRecord`, `OutboxEvent` | PostgreSQL `authorization_limits_db` |
| `accounting-service` | Financial Accounting | `AccountingIntegration` | `ConsumedEvent`, `AccountingInstruction` | PostgreSQL `accounting_integration_db` |
| `core-banking-acl-service` | Current Account / Consumer Loan / outros produtos conforme contrato | `CoreBankingIntegration` | Adaptadores ACL, DTOs, traduções e contratos de integração | Inicialmente **sem banco** se for stateless |

**Justificativa:** `authorization-limit-service` reúne dois **módulos de domínio** em **um deploy e uma transação local**. Não é mapeamento 1:1 dos Service Domains BIAN. O case exige consistência financeira sob concorrência; dois serviços com databases isoladas exigiriam um desenho explícito de coordenação, compensação e reconciliação.

O `accounting-service` apenas integra o processamento contábil. Não implica substituir o razão geral do banco. `core-banking-acl-service` traduz modelos e protocolos legados, evitando que contaminem o domínio de autorização.

## 4. Da API ao banco — nomes de implementação

| Etapa | Nome escolhido | Motivo |
|---|---|---|
| Entrada HTTP **[CASE]** | `POST /v1/contratos/{id_contrato}/autorizacoes` | Autorização síncrona, versionada e contract-first |
| Proteção contra repetição **[CASE]** | `Idempotency-Key` (UUID) | Reentrega da mesma solicitação não duplica reserva |
| Contexto **[SOLUÇÃO]** | `AuthorizationAndLimitReservation` | Manter regras de aprovação e reserva sob uma fronteira de consistência |
| Aggregate Root **[SOLUÇÃO]** | `ContractLimit` | Preservar limite disponível não negativo e não exceder disponibilidade |
| Objetos de domínio **[SOLUÇÃO]** | `Authorization`, `LimitReservation`, `Money`, `ContractId` | Estados e tipos expressivos; `Money` evita aritmética imprecisa com `float` |
| Deployment Kubernetes **[SOLUÇÃO]** | `authorization-limit-service` | Executar o núcleo no EKS |
| Engine de banco **[SOLUÇÃO]** | Amazon RDS for PostgreSQL, **candidato** | ACID, constraints, SQL e teste local equivalente em PostgreSQL |
| Database **[SOLUÇÃO]** | `authorization_limits_db` | Propriedade do núcleo de autorização e limites |
| Schema **[SOLUÇÃO]** | `core` | Organizar os objetos transacionais |
| Evento **[CASE]** | `TransacaoAutorizada` v1 | Informar consumidores após confirmação da operação |

### Tabelas propostas do núcleo

| Database | Schema | Table | Por que existe |
|---|---|---|---|
| `authorization_limits_db` | `core` | `contract_limit` | Guardar limite disponível e reservado por contrato |
| `authorization_limits_db` | `core` | `authorization` | Registrar decisão e estado da autorização |
| `authorization_limits_db` | `core` | `limit_reservation` | Controlar o ciclo de vida da reserva e estornos |
| `authorization_limits_db` | `core` | `idempotency_record` | Vincular chave, hash de payload e resposta original |
| `authorization_limits_db` | `core` | `outbox_event` | Persistir evento de negócio no mesmo commit, para publicação confiável |
| `accounting_integration_db` | `integration` | `consumed_event` | Deduplicar eventos recebidos |
| `accounting_integration_db` | `integration` | `accounting_instruction` | Registrar instrução e status da integração com contabilidade |

Os nomes de tabela são propostas iniciais, **não estruturas impostas pelo BIAN**. A modelagem do agregado e índices devem ser testados com os padrões reais de acesso.

## 5. Encadeamento completo — autorização e reserva

```text
[CASE] Gestão de Limites e Autorização de Transações
  ↳ [BIAN] Channels > Cross Channel > Transaction Authorization
  ↳ [BIAN] Operations > Accounting Services > Position Keeping
  ↳ [BIAN] Products > Loans and Deposits > Consumer Loan (só se esse for o produto)
      ↓ referências para análise; não herança técnica automática
[SOLUÇÃO] Bounded Context: AuthorizationAndLimitReservation
      ↓
[SOLUÇÃO] Aggregate Root: ContractLimit
          Entidades/objetos: Authorization, LimitReservation, Money
      ↓
[SOLUÇÃO] EKS / Deployment: authorization-limit-service
      ↓
[SOLUÇÃO] RDS for PostgreSQL:
          authorization_limits_db
          └── core
              ├── contract_limit
              ├── authorization
              ├── limit_reservation
              ├── idempotency_record
              └── outbox_event
```

**Regra crítica do case:** com R$ 7.000 disponíveis, duas operações simultâneas de R$ 5.000 não podem ser aprovadas em conjunto.

Exemplo conceitual da atualização condicional dentro de **uma transação SQL**:

```sql
BEGIN;
UPDATE core.contract_limit
SET available_amount = available_amount - :valor,
    reserved_amount = reserved_amount + :valor
WHERE contract_id = :id_contrato
  AND available_amount >= :valor;

-- Aprovação somente se exatamente uma linha foi atualizada.
-- Na mesma transação: INSERT authorization, limit_reservation,
-- idempotency_record (chave UNIQUE) e outbox_event.
COMMIT;
```

O código real deve usar parâmetros preparados, diferenciação de débito/crédito/reserva/estorno, verificação de resultado do UPDATE e tratamento de conflito idempotente. Concorrência na mesma linha é serializada pelos locks de escrita PostgreSQL.

## 6. Fluxo assíncrono até a contabilidade

```text
Cliente → API Gateway / WAF
           ↓
Amazon EKS: authorization-limit-service
           ↓
Amazon RDS PostgreSQL (UPDATE limite + INSERTs + Outbox, COMMIT)
           ├──→ Resposta síncrona ao cliente
           └──→ Outbox relay/worker (com retry e monitoramento)
                      ↓
                 Amazon EventBridge
                      ↓
                 Amazon SQS (fila por consumidor)
                      ↓
                 EKS: accounting-service
                      ↓
                 PostgreSQL: accounting_integration_db
                      ↓
                 Core contábil existente
```

**Motivo de Outbox:** evitar que uma reserva seja confirmada no banco e o evento se perca se a publicação falhar. O relay pode reenviar; consumidores devem ser idempotentes (sem garantia exactly-once de ponta a ponta).

## 7. Por que PostgreSQL e não DynamoDB nesta primeira implementação?

**Recomendação candidata para o MVP de cinco dias úteis:** PostgreSQL em Docker na demo e Amazon RDS for PostgreSQL Multi-AZ no desenho AWS. A comparação definitiva de produção permanece pendente.

| Critério | PostgreSQL — candidato MVP | DynamoDB — alternativa |
|---|---|---|
| Atomicidade do limite | Transação SQL + UPDATE condicionado, constraints e lock de linha | `TransactWriteItems` com condição atômica |
| Demonstração local | Mesmo engine PostgreSQL via Docker | DynamoDB Local não replica todos os aspectos de produção |
| Registro de autorização, reserva, idempotência e Outbox | Um único `COMMIT` | Uma transação entre itens |
| Consultas relacionais para auditoria | SQL e índices tradicionais | Projetar chaves, índices e consultas por acesso |
| Custo | Instâncias, alta disponibilidade, storage e I/O | Leituras/escritas, storage, backups e recursos adicionais |
| Escala de 5.000 TPS | **A provar por benchmark e capacity planning** | **A provar com distribuição por chave e contention tests** |

**A escolha do PostgreSQL é de implementação e clareza do case, não uma exigência do BIAN, uma preferência declarada da AWS nem prova de menor custo.**

Veja o [comparativo de custos](../finops/dynamodb-vs-rds-postgresql.md) e as [estimativas de DynamoDB](../finops/estimativa-custos.md).

## 8. Pendências de desenho

- Validar Service Operations, Control Records e Behavior Qualifiers reais por Service Domain no catálogo BIAN v14.
- Especificar máquina de estados da autorização, reserva, captura/liquidação e estorno; regras de contratos por produto.
- Modelar `UNIQUE` para idempotência, resultado de replay, hash de payload e prazo de retenção.
- Definir SLO p95/p99, RTO/RPO, dimensionamento Multi-AZ, reconciliação de Outbox, testes de falha e carga de 5.000 TPS.
- Completar ADR de banco PostgreSQL vs DynamoDB e custos totais (incluindo EKS, rede e observabilidade).

## Referências

- Documento fornecido no case: *Desafio Técnico — POD99 — Nova Plataforma de Finanças · Arquiteto de Aplicação*.
- [BIAN Service Landscape](https://bian.org/deliverables/service-landscape/)
- [AWS Well-Architected Framework](https://docs.aws.amazon.com/wellarchitected/latest/framework/welcome.html)
- [AWS Prescriptive Guidance — Transactional Outbox](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html)
- [Amazon RDS PostgreSQL — Pricing](https://aws.amazon.com/rds/postgresql/pricing/)
- [Amazon DynamoDB — Pricing](https://aws.amazon.com/dynamodb/pricing/)
