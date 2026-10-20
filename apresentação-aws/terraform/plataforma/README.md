# Terraform 01 — Infraestrutura AWS

**Não execute apply sem orçamento e aprovação.** Cria VPC em 3 AZs (incluindo NAT por AZ), EKS Auto Mode, ECR, tabelas DynamoDB com PITR, Streams, EventBridge Pipes, event bus, SQS+DLQ, SNS, IAM Pod Identity.

## Execução faseada

1. `cp terraform.tfvars.example terraform.tfvars`
2. `terraform init && terraform fmt -recursive && terraform validate && terraform plan`
3. Revisar custos e segurança; somente então `terraform apply`.
4. Construir imagens e publicar ECR; preencher os três URIs em `../workloads/terraform.tfvars`.
5. Aplicar `../workloads`. Um NLB interno será criado pelo EKS Auto Mode. Ler ARN/DNS do NLB no console/AWS CLI.
6. Configurar `enable_edge=true`, `private_nlb_arn` e `private_nlb_dns`, reexecutar `terraform apply` para API Gateway REST, Cognito e WAF.
7. Criar usuário Cognito autorizado e obter token; validar autorização de rede e contratos.
   
**EKS Auto Mode** gerencia o EC2 capacity scaling e o NLB. Tem preço distinto de managed node groups; comparar TCO antes de produção. Cluster API privado por padrão, com admin_cidrs=[].

A publicação da Outbox depende de item DynamoDB imutável com entityType=OUTBOX e de atributos `eventId,eventType,occurredAt,authorizationId,contractId,amountDecimal,reservedDecimal,correlationId`; Pipes transforma o item e envia CloudEvents para EventBridge. **DynamoDB Streams retém alterações por 24 horas:** reconciliador de eventos persistidos ainda deve ser entregue antes de produção financeira.

Ao destruir, `deletion_protection_enabled=true` protege tabelas. Para demonstração, remover proteção conscientemente após backup antes do destroy; não remover proteção em produção.

**Segurança:** endpoint EKS privado por padrão, identidade mínima Pod Identity, WAF REST API, Cognito e DNS HTTPS API GW, NAT multi-AZ, dados privados e criptografia gerenciada. Multi-region DR, WAF tuning, SLO e alarmes são evolução após a validação do case.

Doc: https://registry.terraform.io/modules/terraform-aws-modules/eks/aws/latest
