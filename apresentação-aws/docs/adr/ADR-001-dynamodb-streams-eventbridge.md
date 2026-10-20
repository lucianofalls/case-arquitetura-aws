# ADR-001 — DynamoDB transacional com Outbox, Streams e EventBridge

**Data:** 2026-10-20  
**Status:** **baseline de trabalho para os desenhos, em estudo**; não é aprovação definitiva de produção. Validação final de TCO, SLO e benchmark pendente.  
**Escopo:** núcleo de autorização e limites do desafio técnico POD99.

## Contexto

O enunciado requer autorização síncrona em tempo real, limite/reserva por contrato, consistência sob concorrência, idempotência, eventos contábeis e pico estimado de 5.000 TPS. Os padrões DDD, arquitetura hexagonal, database-per-service, eventos e AWS Well-Architected devem ser demonstráveis na defesa.

O BIAN fornece referências para Transaction Authorization, Position Keeping, Financial Accounting e domínios dos produtos. **Não define banco de dados nem runtime.**

## Decisão — arquitetura-base

- **Runtime:** Amazon EKS com nós EC2, Multi-AZ, autoscaling e observabilidade como requisitos de desenho.
- **Serviço crítico:** `authorization-limit-service`, com módulos DDD `authorization` e `limits`, e **uma única propriedade transacional dos dados**.
- **Persistência do núcleo:** tabela DynamoDB `financial-authorization` (nome candidato) com itens lógicos `LIMIT`, `AUTH`, `IDEMPOTENCY` e `OUTBOX`.
- **Transação de aprovação:** `TransactWriteItems` com atualização condicional do limite, `Put` da autorização com representação da reserva, `Put` idempotência e `Put` Outbox, todos no mesmo commit. O valor monetário é armazenado como inteiro na unidade mínima.
- **Publicação:** **DynamoDB Streams (NEW_IMAGE habilitado) → EventBridge Pipes (filtro por item OUTBOX em NewImage + transformação) → EventBridge custom event bus → SQS** por consumidor.
- **Consumo:** `accounting-service` com persistência/inbox própria, consumidor idempotente; outros consumidores ligados ao EventBridge por regras/SQS.
- **Integração legada:** `core-banking-acl-service` com responsabilidade exclusiva de adaptação de modelos/protocolos.

Esta ADR **não** afirma que os serviços foram implementados nem que testes de 5.000 TPS foram concluídos.

## Justificativas

1. **Consistência:** a condição do limite e as gravações de autorização/idempotência/Outbox participam de uma transação atômica dentro da mesma região/conta AWS.
2. **Separação de preocupações:** o serviço responde ao cliente após o commit financeiro; contabilidade e demais integrações não bloqueiam o caminho síncrono.
3. **Desacoplamento:** a Outbox evita dual-write direto DynamoDB e EventBridge. Filtragem no Pipes evita propagar alterações técnicas como fatos de negócio.
4. **Operação AWS:** os componentes de CDC e roteamento são gerenciados; EKS comporta instrumentação e escalabilidade horizontal dos serviços.
5. **BIAN/DDD:** responsabilidades podem ser mapeadas separadamente sem introduzir dois bancos e dois commits para a invariante crítica da reserva.

## Restrições e riscos assumidos

- **Filtro correto do Pipes:** Outbox deve ser imutável e identificável por atributo do `dynamodb.NewImage`. O atributo `eventName` não é suportado como filtro em EventBridge Pipes. Ver [event filtering](https://docs.aws.amazon.com/eventbridge/latest/userguide/eb-pipes-event-filtering.html).
- **Não é publicação automática DynamoDB → EventBridge:** Streams e Pipes exigem habilitação/configuração.
- **Não é exactly-once ponta a ponta:** retries e duplicidade exigem inbox/deduplicação, reconciliação e evolução de esquema de eventos.
- **DynamoDB Streams: retenção de aproximadamente 24 horas.** A Outbox persistida precisará de retenção e processo de reconciliação/republicação para falhas prolongadas.
- **TransactWriteItems:** operações devem atingir itens únicos, sem mais de uma ação por item na mesma transação; condição de saldo no próprio `Update`. Validade ACID é regional.
- **Idempotência:** `ClientRequestToken` do DynamoDB vale por janela curta (cerca de 10 minutos); a `Idempotency-Key` do HTTP precisa de retenção conforme regra do negócio.
- **Hot keys:** muitas autorizações concorrentes do mesmo contrato podem gerar contenção; dimensionamento em 5.000 TPS globais não prova desempenho por chave quente.
- **Modelagem física:** `LimitReservation` é conceito lógico armazenado no item `AUTH` na estimativa de quatro operações; separar em item próprio aumenta custo e exige revisão.
- **Risco de FinOps:** escrever transacionalmente itens pode custar mais que RDS em cargas sustentadas; ainda não há benchmark nem TCO equivalente.

## Alternativa considerada e preservada

**Amazon RDS for PostgreSQL com Outbox transacional SQL e publisher/CDC.** Vantagens potenciais: SQL relacional e modelo familiar de locking/constraints; riscos: dimensionamento de instâncias/HA, capacidade do primário, conexões, WAL/I/O e operação do relay. Não substituir a arquitetura-base silenciosamente; futura decisão requer nova ADR ou revisão explicitamente registrada.

Comparação: [DynamoDB versus RDS PostgreSQL](../finops/dynamodb-vs-rds-postgresql.md).

## Critérios de validação antes de fechar a arquitetura

- Testar 100, 1.000 e 5.000 TPS com distribuição de contratos e p95/p99, conflito e throttling.
- Testar duas autorizações simultâneas de R$ 5.000 sobre R$ 7.000 de limite; nunca confirmar ambas.
- Definir ciclo de reserva/liberação/estorno; idempotência de operações aprovadas e recusadas.
- Demonstrar falha depois do commit e antes da entrega EventBridge; retries, perda de Stream após 24 horas e reconciliação.
- Comparar TCO com alternativa PostgreSQL considerando leituras, escritas, índices, armazenamento, backups, tráfego, EKS e HA.
- Documentar acesso privado, autorização, IAM/KMS, observabilidade, RTO/RPO e auditoria.

## Referências

- [DynamoDB TransactWriteItems](https://docs.aws.amazon.com/amazondynamodb/latest/APIReference/API_TransactWriteItems.html)
- [DynamoDB e EventBridge](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/eventbridge-for-dynamodb.html)
- [Amazon EventBridge Pipes com origem DynamoDB](https://docs.aws.amazon.com/eventbridge/latest/userguide/eb-pipes-dynamodb.html)
- [AWS Transactional Outbox](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html)
- [AWS Well-Architected Framework](https://docs.aws.amazon.com/wellarchitected/latest/framework/welcome.html)
