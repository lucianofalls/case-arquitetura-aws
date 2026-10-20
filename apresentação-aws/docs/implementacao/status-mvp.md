# Status da implementação e fronteiras de aceite

**Implementação no GitHub, não provisionada em AWS.** O CI confirma compilação/testes e validação estática do Terraform, mas não substitui um `plan/apply` com credenciais reais nem um teste de pico 5.000 TPS.

| Requisito do case | Artefato | Evidência / status |
|---|---|---|
| DDD, agregados, invariantes | `services/authorization-limit-service/domain/` | ContractLimit, Money, OperationType, Authorization; testes de regra |
| SOLID / Clean / Hexagonal | API → UseCase/Port → Domain → DynamoAdapter | Fronteiras separadas; port testável |
| APIs OpenAPI 3.x / Problem Details | `contracts/openapi/authorization-v1.yaml`, AuthorizationController | Contrato YAML CI; HTTP 201/200/402/409/422 |
| Quatro tipos de operação | ContractLimit / DynamoAuthorizationAdapter | RESERVA, DEBITO, CREDITO, ESTORNO implementados **com hipóteses de semântica** |
| Concorrência do limite | TransactWriteItems + optimistic version + condition | Testcontainers DDB Local de duas reservas concorrentes |
| Idempotência financeira | DynamoDB IDEMPOTENCY | Teste de replay e hash; persistência de recusa por saldo insuficiente |
| Transactional Outbox | DynamoDB OUTBOX no commit | Item imutável na transação; teste de estorno |
| Mensageria EventBridge + SQS + SNS | Terraform `plataforma/messaging.tf` | Sintaxe/estrutura Terraform validada; **não aplicado** |
| CloudEvents + versionamento + registry | AsyncAPI e `aws_schemas_schema` | Contratos e Terraform validados estaticamente |
| Banco por serviço | financial-authorization / accounting-processing | DynamoDB no núcleo e no inbox contábil; sem cross-service query |
| EKS + segurança / API Management | EKS Auto Mode, Cognito, WAF, VPC Link, Pod Identity | Terraform validado, **sem deploy** |
| Observabilidade | EKS control plane logs, SQS DLQ, DDB throttling alarms | Terraform presente; sem métricas de aplicação personalizadas |
| Integração Core Banking | core-banking-acl-service | Stub `demo` somente; não existe integração real |
| Contabilização | accounting-service | Inbox SQS idempotente; **sem lançamentos contábeis** |
| Proteção contra falha prolongada | Outbox persistida e `scripts/reconciliar-outbox.py` | Replay manual por event_id, **não reconciliador automático** |
| SLA/Resiliência/FinOps/5000 TPS | docs/ADR + k6 | Metas sem comprovação; dependem de conta AWS e benchmarks |
| Infra totalmente IaC | `terraform/plataforma`, `terraform/workloads` | Dois estágios Terraform; sem `apply` |

## Bloqueadores antes de alegar pronto para produção

- Validação das regras de produto/conta e integração core banking real; Estado de contrato e elegibilidade.
- Definição oficial de captura, liquidação, estorno parcial, crédito, reconciliação de contabilização; homologação jurídica/risco.
- Reconciliação de Outbox automática e escalável para falhas >24h; monitorar atrasos e alertar.
- Evidência de latência e 5000 TPS (p95/p99, contratos altamente concorridos e custo).
- DR multi-região, RTO/RPO, acesso de operadores, auditoria e retenção.
- Hardening NLB/API Gateway, WAF por cliente/plano e escalonamento consumer por backlog SQS (KEDA ou alternativa).

## CI

[GitHub Actions — POD99](https://github.com/lucianofalls/case-arquitetura-aws/actions/workflows/pod99-apresentacao-aws.yml). Todos os jobs são sem credenciais AWS e não criam recursos. Um sucesso de CI valida **compilação/testes e Terraform validate**, não implantação.
