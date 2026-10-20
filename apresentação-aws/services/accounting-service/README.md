# accounting-service

**Implementação inicial:** consumer Amazon SQS → inbox idempotente DynamoDB.
Não há débito, crédito ou lançamento em razão contábil real, nem integração com core existente. Isso será um segundo incremento.

Se `ACCOUNTING_SQS_URL` não estiver preenchida, o consumer não consulta fila.
Com SQS real, é necessário IAM `sqs:ReceiveMessage`/`sqs:DeleteMessage`, `dynamodb:PutItem` para tabela `accounting-processing` e uma DLQ configurada na origem.

A mensagem esperada é EventBridge JSON cuja propriedade `detail` contém um CloudEvent com `id` e `type=TransacaoAutorizada`. O Pipe precisa transformar `OUTBOX.payload` nessa forma antes de enviar para EventBridge.

O registro `RECEIVED_FOR_ACCOUNTING` **não equivale a lançamento no razão**. Se futuramente o consumidor executar efeitos downstream, terá de adicionar estado de processamento e outbox local para não perder efeitos entre persistência e ACK da fila.
