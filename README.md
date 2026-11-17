# Firewalldo Portal API

API REST em Java 17, Spring Boot 3.5, Spring JDBC, PostgreSQL e Flyway. O
Dockerfile compila e executa com Java 17. As migrações aplicam o esquema
automaticamente ao subir a API.

| Variável | Uso |
| --- | --- |
| `PORT` | Porta HTTP. Padrão local 3333. |
| `PGHOST`, `PGPORT` | Host e porta do PostgreSQL. |
| `PGDATABASE`, `PGUSER`, `PGPASSWORD` | Banco, usuário e senha do PostgreSQL. |
| `ADMIN_EMAIL`, `ADMIN_PASSWORD` | Credenciais do primeiro administrador. Use de 12 a 128 caracteres na senha. |
| `ADMIN_NAME` | Nome do primeiro administrador. |
| `SESSION_SECURE` | Padrão `true` para HTTPS. Use `false` em desenvolvimento via HTTP. |

O administrador inicial é criado quando ainda não há um administrador ativo
com senha. Depois do primeiro login, as variáveis `ADMIN_EMAIL`,
`ADMIN_PASSWORD` e `ADMIN_NAME` podem ser removidas no próximo deploy.
Outras contas recebem um link de convite gerado no portal, válido por 48 horas.
O administrador copia o link e o envia por um canal privado.

## Execução local

Use JDK 17, Maven 3.9 e uma instância PostgreSQL com um banco vazio.
Exporte as variáveis acima no terminal e execute:

```bash
mvn package
java -jar target/firewalldo-portal-api-1.0.0.jar
```

- `GET /api/health` verifica a conexão com o banco.
- `POST /api/auth/login` aceita `{ "email": "...", "senha": "..." }`.
- `GET /api/auth/me` devolve o perfil da sessão atual.
- `POST /api/auth/activate` aceita `{ "token": "...", "senha": "..." }`.
- `POST /api/auth/logout` encerra a sessão.

O navegador envia o cookie HttpOnly pelo mesmo domínio do portal. O front
encaminha `/api/*` para a API usando proxy. Os demais endpoints seguem o
contrato do portal. As fontes ativas aparecem como opções para publicação
manual no Radar. Convites por e-mail, RSS automático e upload para o Google
Drive ficam para uma etapa posterior.
