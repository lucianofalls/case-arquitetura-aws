# Teste local do case — LocalStack, Docker e DynamoDB Streams

> **Status:** scripts e Compose publicados; testes completos do novo Compose com LocalStack **ainda não foram executados** pelo assistente (ambiente sem Docker). Use os comandos abaixo para realizar a verificação no seu Mac. **Não usa GitHub Actions nem AWS real.**

## O que significa "AWS na minha máquina"?

`docker-compose.localstack.yml` emula **a integração de negócio**, sem instalar um cluster EKS gerenciado. Com LocalStack Hobby, podemos exercer:

```text
curl / API local Spring Boot
     |
 authorization-limit-service :8080 (container)
     |
 DynamoDB no LocalStack (TransactWriteItems)
     ├── LIMIT condicional / AUTH / IDEMPOTENCY / OUTBOX
     └── DynamoDB Streams NEW_IMAGE
             |
         stream-relay (Python LOCAL, substitui EventBridge Pipes)
             |
      EventBridge Bus no LocalStack
             ├── SQS accounting (+ DLQ)
             ├── SQS fraud (+ DLQ)
             ├── SQS data (+ DLQ)
             └── SNS notifications → SQS
                   |
          accounting-service (container)
                   |
        DynamoDB accounting-processing (Inbox idempotente)
```

A **arquitetura AWS real** continua com **EventBridge Pipes gerenciado**, definido no Terraform. O relay local reproduz somente o ato de filtrar OUTBOX e publicar CloudEvents, não todas as semânticas de Pipes (filtros nativos, métricas, backoff, DLQ de Pipes, limites de chamadas).

## LocalStack 2026: token e planos

A imagem `localstack/localstack` exige `LOCALSTACK_AUTH_TOKEN` desde março de 2026, inclusive para Hobby (gratuito para uso não comercial). **Não publique o token no GitHub.** Um exemplo sem segredos está em [`.env.example`](../../.env.example).

Segundo a matriz atual de planos:
- DynamoDB, DynamoDB Streams, EventBridge, SQS e SNS: **Hobby**;
- EventBridge Pipes nativo e EKS emulado pelo LocalStack: **Ultimate**;
- Em Hobby, este repositório usa um relay Python local no lugar de Pipes.
- EKS Auto Mode, WAF, IAM, VPC, NAT e escalabilidade reais **não são comprovados** pelo Compose.

Confira os termos da licença Hobby para o tipo de uso: https://docs.localstack.cloud/aws/licensing/

## Pré-requisitos (Mac Apple Silicon ou equivalente)

- Docker Desktop recente com Docker Compose v2;
- Java e Maven **não são obrigatórios na máquina**, porque os builds acontecem nos Dockerfiles;
- Python 3 disponível apenas se quiser executar scripts auxiliares fora dos containers;
- Token LocalStack, obtido em https://app.localstack.cloud/;
- Memória disponível para os três builds Java, LocalStack e quatro containers auxiliares (reservar cerca de 8 GB ao Docker é uma referência operacional, não uma exigência exata).

## Executar

```bash
git clone https://github.com/lucianofalls/case-arquitetura-aws.git
cd case-arquitetura-aws/apresentação-aws

cp .env.example .env
# Preencha LOCALSTACK_AUTH_TOKEN em .env, SEM comitar este arquivo.

# Validação sintática local do compose
docker compose -f docker-compose.localstack.yml config --quiet

# Sobe LocalStack, inicializa tabelas/filas/event bus, relay e aplicações Java
docker compose -f docker-compose.localstack.yml up --build -d

# Veja quando o bootstrap terminou e se há falhas
docker compose -f docker-compose.localstack.yml logs --tail=100 localstack-init
docker compose -f docker-compose.localstack.yml ps

# Verifica funcionalmente:
# HTTP 201; replay 200; evento Outbox na Inbox contábil;
# 2 reservas de R$5000 em limite de R$7000 => 1 única aprovação
docker compose -f docker-compose.localstack.yml --profile tools run --rm tools

# Diagnóstico, quando necessário
docker compose -f docker-compose.localstack.yml logs --tail=150 \
  localstack-stream-relay accounting-service authorization-limit-service

# Encerra containers e rede. Dados do LocalStack são efêmeros neste cenário.
docker compose -f docker-compose.localstack.yml down
```

### Interpretação dos resultados

```text
OK: reserva HTTP 201 e idempotência HTTP 200
OK: Outbox → Streams → relay local → EventBridge → SQS → Inbox: <eventId>
OK: 2x R$ 5.000 sobre R$ 7.000, somente uma reserva aprovada: [...]
LOCAL E2E SUCCEEDED. ATENÇÃO: LocalStack != teste de AWS real.
```

Os resultados acima são **saídas esperadas**, não logs reais capturados.

O teste usa contratos exclusivos `e2e-*`, não altera `contrato-demo` e consulta a Inbox por `event_id`.

### Caso queira testar somente o núcleo transacional sem token LocalStack

A demo antiga permanece disponível em `docker-compose.yml` com **DynamoDB Local**:

```bash
docker compose up --build -d
bash scripts/demo-concorrencia.sh
docker compose down
```

**Não execute os dois Composes simultaneamente:** ambos publicam a porta 8080/8082, causando conflito.

## E o Kubernetes na máquina?

Fase complementar (ainda não automatizada):
1. Instalar `kind` ou `k3d` e criar um cluster Kubernetes local.
2. Construir imagens Java localmente e carregar no cluster.
3. Aplicar deployments, services e RBAC equivalentes ao Terraform workloads, **sem NLB Auto Mode**, que é um serviço AWS.
4. Apontar Pods para LocalStack acessível na rede Docker do cluster e realizar os mesmos testes.
5. Instalar OpenTelemetry Collector, Jaeger e dashboards para rastreabilidade.

Este cenário exercita **Kubernetes local**, mas não valida preços, latência, IAM, rede VPC ou Auto Mode da AWS.

## O que falta antes de declarar a solução pronta

| Requisito | Nível de validação atual | Próxima etapa |
|---|---|---|
| Consistência, replay e Outbox | Testes Maven + DynamoDB Local previamente aprovados | Rodar Compose LocalStack completo |
| Streams → Pipes → EventBridge | Relay local substitui Pipes | Testar Pipes real no Ultimate ou AWS |
| EventBridge/SQS → Accounting | Scripts e consumer prontos | Executar teste E2E e conferir Inbox |
| Validação de contrato real no core | ACL demo apenas | Simulador de core com regras e dados de contrato |
| Registro contábil efetivo | Apenas Inbox | Simulador contábil idempotente + instruções |
| EKS / ingress / API Gateway / WAF / JWT | Terraform, sem deploy | Kubernetes local + gateway de teste, depois AWS |
| 5.000 TPS e latência p95/p99 | Script k6, sem benchmark | Teste de performance após estabilizar o E2E |
| Falha de Streams superior a 24h | Replay manual por ID | Reconciliador durável automático |
| Well-Architected / RTO/RPO / DR | Decisões e ADRs documentados | Testes de falha, políticas e medições reais |

## Segurança do ambiente

Não coloque credenciais reais AWS no Compose. As variáveis `AWS_ACCESS_KEY_ID=test` / `AWS_SECRET_ACCESS_KEY=test` existem somente para satisfazer a assinatura do AWS SDK contra o endpoint local.

A porta LocalStack (4566) e as APIs da demonstração (8080, 8082) são expostas **apenas em 127.0.0.1**. O arquivo `.env` está ignorado pelo Git; o token de licença não deve ir para logs compartilhados.

## Referências

- https://docs.localstack.cloud/aws/getting-started/auth-token/
- https://docs.localstack.cloud/aws/licensing/
- https://docs.localstack.cloud/aws/services/sqs/
- https://docs.localstack.cloud/aws/services/dynamodb/
