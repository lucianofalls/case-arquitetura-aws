# ADR-002 — Persistência por Bounded Context e custo-benefício

**Status:** decisão de modelagem para iniciar o código; custos e capacidade ainda não validados.

## Decisão

| Serviço | Escolha atual | Motivo | Alternativa |
|---|---|---|---|
| Authorization & Limits | **DynamoDB** `financial-authorization` | Lookup por contrato, escrita condicional atômica, identidade durável e Outbox, Streams nativo | RDS/Aurora PostgreSQL com transação SQL + CDC/relay |
| Accounting Integration | **DynamoDB** `accounting-processing` | Idempotência por event_id, estado simples por chave e baixo acoplamento, não substitui razão contábil | PostgreSQL quando regras e consultas relacionais crescerem |
| Core Banking ACL | **Sem banco no primeiro slice** | Adapter stateless: não criar estado sem necessidade | Cache/armazenamento de snapshots se houver exigência de disponibilidade fora do core |
| Analítico / relatórios | **Fora do core síncrono** | Evitar queries pesadas contra o limite e autorizações | Eventos para data lake ou serviço específico |

## Consequências e prós/contras

A arquitetura escolhe simplicidade de acesso por chave e integração gerenciada a eventos em vez de SQL relacional no núcleo. O custo transacional pode crescer em cargas contínuas; registros Outbox e de idempotência aumentam armazenamento; hot keys podem elevar conflitos. Não usar o termo "mais barato" sem benchmark comparável.

A consistência exigida é **por contrato e por requisição**, não ACID distribuída entre consumidores. A publicação é após commit e os consumidores são idempotentes. É necessário um reconciliador quando a falha de publicação exceder 24 horas; ausência dele é bloqueio para uma proposta de produção financeira.

## Reabrir a decisão quando

- Auditoria exigir joins e exploração operacional relacional intensiva;
- TCO real do DynamoDB exceder alternativa RDS/Aurora no mesmo throughput/SLO;
- Conflito de chave quente ficar acima do tolerável;
- Regras contratuais multi-entidade exigirem consistência fora do limite transacional DynamoDB;
- Governança/regulação de dados exigir um modelo não representado.

Referências: https://aws.amazon.com/dynamodb/pricing/ e https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html.
