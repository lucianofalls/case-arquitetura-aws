# Terraform 02 — Deploy dos microsserviços em EKS Auto Mode

Executar **depois** do terraform em ../plataforma e de publicar três imagens Docker com tag imutável no ECR. K8s API precisa estar acessível (VPN/bastion se privada).

1. `cp terraform.tfvars.example terraform.tfvars` e substituir ACCOUNT / COMMIT_SHA / Queue URL por outputs reais.
2. `terraform init && terraform validate && terraform plan`.
3. Revisar Kubernetes objects; `terraform apply`.
4. `terraform output private_nlb_hostname` retorna DNS interno; buscar ARN via AWS CLI `aws elbv2 describe-load-balancers --names ...` ou console e configurar em ../plataforma `enable_edge=true`.
5. Reiniciar `terraform apply` em ../plataforma para etapa 03 Edge REST API/WAF.

**Importante:** Role IAM por Pod Identity vem da etapa 01. Contagem inicial de Pods exige custo de nós EKS Auto Mode. HPA é CPU; evolução para consumer SQS deve usar backlog/idade da fila via KEDA/CloudWatch.

**EKS Auto Mode:** suporta NLB a partir de Kubernetes Service `loadBalancerClass=eks.amazonaws.com/nlb`; verificar no apply versão do provider e integração EKS. Nenhum Pod assume IAM de node por padrão.
