# accounting-service — AccountingIntegration

Consumer SQS (EventBridge) de `TransacaoAutorizada`, `TransacaoEstornada` e `LimiteAlterado`. Persistência **exclusiva** na tabela `accounting-processing`, com escrita condicional da Inbox por `eventId`, evitando duplo processamento de evento recebido.

**Não substitui** razão geral, escrituração regulatória nem ledger bancário. O primeiro incremento apenas registra o recebimento durável com status `RECEIVED_FOR_ACCOUNTING`. A evolução deve criar uma instrução contábil idempotente com estado e Outbox próprio para chamadas ao core.

Ao receber:
1. SQS long polling.
2. Extrai envelope CloudEvent de `detail` do EventBridge.
3. Confere `id` e evento conhecido.
4. Persiste Inbox condicional; reconhece duplicata.
5. Executa SQS Delete somente após sucesso ou duplicata confirmada.
6. Falha desconhecida: mensagem retorna após timeout de visibilidade; DLQ é configurada no Terraform.

Se `ACCOUNTING_SQS_URL` não estiver preenchida, não faz polling (útil no Compose local).

**IAM mínimo:** DynamoDB PutItem na sua própria tabela; SQS ReceiveMessage/DeleteMessage/GetQueueAttributes apenas na sua fila. O backend não lê a tabela `financial-authorization`. Teste em GitHub Actions: Maven compile/verify.
