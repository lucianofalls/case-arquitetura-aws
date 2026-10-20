# Tabelas de rastreabilidade — BIAN → DDD → EKS → DynamoDB

> **Status:** proposta para o desafio técnico POD99. **Nada está implantado.**
> Origem de requisitos: PDF *Desafio Técnico — POD99, Nova Plataforma de Finanças · Arquiteto de Aplicação* (5 páginas).
> O enunciado não prescreve BIAN, nomes de Bounded Contexts, EKS ou DynamoDB. São opções de modelagem e implementação a justificar.
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
| 8 | Tabela / Chave / Item | **[SOLUÇÃO]** persistência | Persistir estados, chaves de idempotência, reservas e eventos Outbox com padrões de acesso definidos |

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

| Microsserviço **[SOLUÇÃO]** | Referência funcional **[BIAN]** | Bounded Context / módulo **[SOLUÇÃO]** | Agregados/conceitos | Banco da arquitetura-base |
|---|---|---|---|---|
| `authorization-limit-service` | Transaction Authorization; Position Keeping; regras do produto | `AuthorizationAndLimitReservation`, módulos `authorization` e `limits` | `ContractLimit`, `Authorization`, `LimitReservation`, `IdempotencyRecord`, `OutboxEvent` | DynamoDB `financial-authorization` |
| `accounting-service` | Financial Accounting | `AccountingIntegration` | `ConsumedEvent`, `AccountingInstruction` | DynamoDB `accounting-processing`, própria (candidata) |
| `core-banking-acl-service` | Current Account / Consumer Loan e demais produtos aplicáveis | `CoreBankingIntegration` | Adaptadores ACL e DTOs | Inicialmente stateless, sem banco |

**Motivo:** a autorização e a reserva financeira estão em dois módulos DDD, mas são gravadas por um **único microsserviço** para ter um único limite de transação. BIAN não impõe que cada Service Domain tenha seu próprio deploy.

## 4. Da API à estrutura física DynamoDB

| Camada | Nome | Motivo |
|---|---|---|
| API **[CASE]** | `POST /v1/contratos/{id_contrato}/autorizacoes` | Contract-first, síncrona e versionada |
| Idempotência **[CASE]** | `Idempotency-Key` (UUID) | Impedir duplicidade da mesma operação |
| Bounded Context **[SOLUÇÃO]** | `AuthorizationAndLimitReservation` | Proteger conjuntamente a decisão e a reserva |
| Aggregate Root **[SOLUÇÃO]** | `ContractLimit` | Limite disponível não pode ficar negativo |
| Value Objects **[SOLUÇÃO]** | `Money`, `ContractId`, `AuthorizationId` | Tipos expressivos e valor monetário exato |
| Deployment **[SOLUÇÃO]** | `authorization-limit-service` | Runtime de aplicação no Amazon EKS |
| Banco **[SOLUÇÃO]** | Amazon DynamoDB | Transações ACID por `TransactWriteItems` |
| Tabela do núcleo **[SOLUÇÃO]** | `financial-authorization` | Propriedade exclusiva do núcleo |
| Item `LIMIT` **[SOLUÇÃO]** | `PK=CONTRACT#<id>; SK=LIMIT` | Limite em unidades monetárias inteiras |
| Item `AUTH` **[SOLUÇÃO]** | `PK=AUTH#<id>; SK=META` | Autorização e dados do ciclo da reserva |
| Item `IDEMPOTENCY` **[SOLUÇÃO]** | `PK=IDEMPOTENCY#<consumidor>#<UUID>; SK=REQUEST` | Hash do payload e resultado original |
| Item `OUTBOX` **[SOLUÇÃO]** | `PK=OUTBOX#<event_id>; SK=EVENT` | Evento de domínio imutável |
| Evento **[CASE]** | `TransacaoAutorizada` v1 | Comunicação com consumidores depois do commit |

Os quatro tipos de item **não são quatro tabelas físicas**. Nesse desenho, `LimitReservation` é conceito de domínio representado no item `AUTH`. Se a reserva passar a possuir item independente, isso acrescentará uma escrita transacional e afetará o custo.

## 5. Regra de concorrência e quatro escritas atômicas

**Requisito do case:** limite de R$ 7.000 e duas operações simultâneas de R$ 5.000. Não se podem aprovar as duas.

| Ação `TransactWriteItems` | Item | Efeito e condição |
|---|---|---|
| `Update` condicional | `LIMIT` | `available_minor_units >= amount_minor_units`; subtrai disponível e acrescenta reservado |
| `Put` | `AUTH` | Cria autorização e representação da reserva |
| `Put` condicional | `IDEMPOTENCY` | Exige que a chave idempotente ainda não exista |
| `Put` | `OUTBOX` | Cria evento `TransacaoAutorizada` v1 na mesma transação |

**A condição e a atualização devem estar na mesma ação `Update`**. O DynamoDB não permite executar `ConditionCheck` e `Update` separados sobre o mesmo item no mesmo `TransactWriteItems`. Tudo é confirmado ou tudo é cancelado.

Uma falha de transação pode ser saldo insuficiente ou conflito de concorrência: o serviço tem de diferenciar HTTP 402 de HTTP 409 e aplicar retries apenas em situações apropriadas. `ClientRequestToken` não substitui o registro durável da `Idempotency-Key`.

## 6. Fluxo de eventos após o commit

```text
Cliente → API Gateway / WAF
           ↓
Amazon EKS: authorization-limit-service
           ↓
DynamoDB: TransactWriteItems (LIMIT + AUTH + IDEMPOTENCY + OUTBOX)
           ├─→ resposta síncrona após commit
           └─→ DynamoDB Streams habilitado
                    ↓
                EventBridge Pipes
                - filtro: registros INSERT do tipo OUTBOX
                - transformação de payload de negócio
                    ↓
                EventBridge custom event bus
                    ↓
                regras e filas independentes
                 ├─→ SQS Contabilidade → accounting-service → banco próprio
                 ├─→ SQS Antifraude
                 ├─→ SQS Notificações
                 └─→ SQS Data Platform
```

O EventBridge **não recebe automaticamente** qualquer escrita DynamoDB; Streams e Pipes precisam estar configurados. Eventos podem ser duplicados e reordenados; os consumidores deduplicam com `event_id`. **Streams retém alterações por aproximadamente 24 horas**; a solução requer monitoração, falhas/retries, reconciliação e republicação de eventos persistidos na Outbox para indisponibilidade prolongada.

## 7. Por que DynamoDB aqui? E PostgreSQL?

| Critério | DynamoDB (arquitetura-base do case) | RDS PostgreSQL (alternativa não selecionada) |
|---|---|---|
| Reserva segura | `TransactWriteItems` + `Update` condicional | Transação SQL + `UPDATE` condicional e lock |
| Idempotência | Item dedicado na mesma transação | Unique key + registro SQL |
| Evento confiável | Item Outbox no mesmo commit | Linha Outbox no mesmo COMMIT |
| Publicação | **Streams → Pipes → EventBridge → SQS** | Relay/CDC → EventBridge → SQS |
| Escalabilidade e custo | Dependem de padrão de chaves, leituras, escritas, índices, concorrência | Dependem de primário, HA, I/O, conexões, carga e índice |
| Situação | **Baseline para os desenhos** | **Comparação técnica/FinOps pendente** |

A decisão de DynamoDB para a baseline **não comprova menor custo ou 5.000 TPS**. Esses requisitos exigem carga realista e benchmark. Ver [FinOps](../finops/estimativa-custos.md) e [comparação](../finops/dynamodb-vs-rds-postgresql.md).

## 8. Pendências e referências

A modelagem de tipos `DEBITO`, `CREDITO`, `RESERVA` e `ESTORNO`, o comportamento de recusas idempotentes, o ciclo de vida das reservas, auditoria, as operações BIAN v14 detalhadas, SLOs e retenção ainda precisam de refinamento.

- PDF fornecido: `Desafio Técnico — POD99 — Nova Plataforma de Finanças · Arquiteto de Aplicação`.
- [BIAN Service Landscape v14](https://bian.org/deliverables/service-landscape/)
- [Amazon DynamoDB — TransactWriteItems](https://docs.aws.amazon.com/amazondynamodb/latest/APIReference/API_TransactWriteItems.html)
- [DynamoDB ↔ EventBridge](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/eventbridge-for-dynamodb.html)
- [EventBridge Pipes com origem DynamoDB Streams](https://docs.aws.amazon.com/eventbridge/latest/userguide/eb-pipes-dynamodb.html)
- [AWS Transactional Outbox](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html)
