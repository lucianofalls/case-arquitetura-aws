# Valor de negócio para o Itaú — Plataforma de Limites e Autorização

> **Origem:** requisitos expressos no PDF do case. Benefícios, KPIs e riscos abaixo são **inferências de arquitetura**, não números de resultado prometidos pelo Itaú.

## Por que esta plataforma existe?

O case descreve ~80 milhões de contas, 3–5 contratos/conta, pico de ~5.000 TPS, decisão síncrona e reserva concorrente por contrato. O resultado desejado é **autorizar operações válidas sem ultrapassar limite, sem duplicar débito/reserva e sem bloquear a experiência do cliente esperando retaguarda**.

| Problema do banco | Capacidade entregue | Ganho esperado | Como medir |
|---|---|---|---|
| Duas operações simultâneas consomem o mesmo limite | Reserva condicional e transação ACID | Evitar exposição financeira por aprovação acima do limite | 0 violações de invariante em testes de concorrência; alarmes de divergência |
| Cliente reenvia após timeout | Idempotency-Key persistente | Evitar reserva/lançamento duplicado | Taxa de replay resolvido; 0 duplicações na mesma chave |
| Contabilidade lenta ou indisponível | Outbox + EventBridge + SQS por consumidor | Autorização não aguarda retaguarda | Latência p95/p99 da autorização; atraso dos eventos |
| Volumes sazonais e surtos | EKS autoscaling + DynamoDB dimensionado e testado | Menos recusa por saturação e menor ociosidade fora de pico | TPS, throttles, capacidade, custo por aprovação |
| Integração de legados heterogêneos | Serviço ACL de contratos/contas | Domínio novo não depende dos formatos do core | Tempo de integração de novo produto, falhas por contrato |
| Risco, auditoria e observabilidade | IDs de correlação, eventos, trilha de auditoria | Melhor rastreabilidade e reconciliação | Eventos sem destino, idade de outbox, tempo de reconciliação |

## Prós da arquitetura-base (EKS + DynamoDB + Streams + Pipes + EventBridge + SQS)

1. **Atomicidade verificável:** reserva, autorização, chave idempotente e Outbox persistem na mesma transação DynamoDB.
2. **Escala gerenciada do armazenamento:** não existe um único servidor PostgreSQL de escrita a ser ampliado verticalmente, desde que o particionamento e acesso por chave sejam corretos.
3. **Baixo acoplamento entre domínios:** contabilidade/antifraude/notificações recebem eventos em filas próprias; uma falha de consumidor não precisa invalidar a reserva já confirmada.
4. **Menos código de CDC:** DynamoDB Streams e EventBridge Pipes substituem a necessidade de manter um poller apenas para copiar eventos para o barramento.
5. **Independência de implantação:** consumidores podem ser evoluídos/deployados sem alterar a API de autorização.
6. **Aderência a Well-Architected:** decisões explícitas para confiabilidade, segurança, desempenho e custo; não significa conformidade automaticamente.

## Contras e riscos reais

| Risco / custo | Efeito para o banco | Mitigação ou teste obrigatório |
|---|---|---|
| **Chave quente por contrato** | Conflitos e aumento de latência quando várias operações disputam o mesmo item de limite | Teste concorrência por contrato; backoff com limite; métricas de ConditionalCheckFailed/TransactionCanceled |
| **Custos por item transacional** | Cada aprovação altera múltiplos itens e consome capacidade; em volume sustentado RDS pode ser mais barato | TCO de autorização aprovada/negada e de operações adicionais; comparar mesmo SLO |
| **Streams só 24 h** | Falhas longas podem impedir consumo de alterações | Outbox persistida + reconciliador e replay independente do stream; alertas de IteratorAge |
| **Entrega at-least-once / reordenação** | Contabilidade pode receber duplicata ou fora de ordem | Inbox idempotente e versionamento do estado; tratar sequência por contrato, se requerida |
| **Complexidade da plataforma EKS** | Governança de cluster, nodes, rede, segurança, observabilidade, expertise operacional | IaC/GitOps, Managed Node Groups, PodDisruptionBudget, CloudWatch/OTel, análise ECS alternativa |
| **Produto heterogêneo** | Contratos de crédito/investimento/seguros não compartilham necessariamente regras de limite | Modelo de elegibilidade e política por produto; ACL anti-corrupção; não generalizar Credit Facility |
| **Regra de liquidação ausente no case** | Aprovação/reserva não equivale a lançamento contábil liquidado | Separar estados autorizado, reservado, capturado/liquidado, revertido; definir contratos antes do go-live |
| **SLA e DR não especificados** | Impossível garantir disponibilidade/RTO/RPO apenas pelo desenho | Solicitar SLO p95/p99, RPO/RTO, dimensionar Multi-AZ e DR |

## Banco: escolha e condições de revisão

**Banco-base para o núcleo:** DynamoDB. Motivos específicos: acesso por contrato (key-based), reserva condicional, transação multi-item em mesma região/conta e integração CDC gerenciada. **Não foi escolhido por ser comprovadamente mais barato**, nem porque o BIAN o determina.

**Alternativa legítima:** Amazon RDS for PostgreSQL / Aurora PostgreSQL. Pode ser superior para joins, auditoria SQL operacional, consultas relacionais, custos previsíveis sob tráfego estável e maturidade da equipe. Deve ser comparado com carga equivalente. Não incluir PostgreSQL no caminho síncrono sem uma ADR de revisão.

**Banco do consumidor contábil:** tabela DynamoDB de inbox/estado de integração, propriedade exclusiva do consumidor; o razão geral do core continua externo. O objetivo do MVP não é substituir contabilidade regulatória.

## BIAN — relação com o benefício

- **Transaction Authorization**: decisão e rastreabilidade de autorização.
- **Position Keeping**: movimentos/posições, referência à reserva; não significa reprodução integral desse Service Domain.
- **Financial Accounting**: integração de fatos financeiros ao processamento contábil.
- **Current Account / Consumer Loan**: modelos e sistemas do produto original, através de ACL quando aplicável.

BIAN delimita responsabilidades de negócio, **não determina um microsserviço por Service Domain**.

## Referências

- PDF *Desafio Técnico POD99 — Nova Plataforma de Finanças*, 5 páginas.
- https://docs.aws.amazon.com/wellarchitected/latest/framework/welcome.html
- https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html
- https://aws.amazon.com/pt/blogs/aws-brasil/implementando-o-padrao-de-caixa-de-saida-transacional-com-o-amazon-eventbridge-pipes/
- https://aws.amazon.com/dynamodb/pricing/
