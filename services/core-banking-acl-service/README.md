# core-banking-acl-service

Adapter de anticorrupção entre o domínio AuthorizationAndLimitReservation e modelos contratuais legados.

**Estado atual:** endpoint de contrato demonstrativo somente sob perfil Spring `demo`.
Por padrão, `GET /internal/contracts/{contractId}` retorna 501 para não fingir uma integração real com core bancário.
O serviço `authorization-limit-service` **não chama este serviço ainda**: a sincronização de contratos e políticas deve ser desenhada para não introduzir dependência indisponível na rota crítica.

Perfis: `SPRING_PROFILES_ACTIVE=demo` para GET com dados fictícios `DEMO_NOT_REAL_CORE`. Não expor esse perfil em produção.
