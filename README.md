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

O administrador inicial é criado como `superadmin` (Administrador geral). Na atualização de um banco V1, a conta de administrador com senha cujo e-mail corresponde a `ADMIN_EMAIL` é promovida sem alterar suas credenciais. Se houver apenas um administrador com senha, ele é reconhecido como inicial. Havendo vários candidatos, a variável é necessária para evitar promover a pessoa errada.

Depois que já existe um geral ativo com senha, as variáveis `ADMIN_*` não alteram contas existentes. A edição deve ser feita pelo portal, com confirmação da senha atual para trocar o próprio e-mail ou senha. Novas contas já são cadastradas com senha de 12 a 128 caracteres. O endpoint de ativação continua funcionando para convites antigos.

## Perfis e auditoria

- `superadmin`: administra usuários, administradores e gerais. O último geral ativo com senha é protegido.
- `admin`: administra usuários comuns e pode consultar a auditoria; não altera contas de outros administradores.
- `usuario`: não altera outras contas. Qualquer perfil pode editar a própria conta, sem elevar o próprio nível de acesso.

`PATCH /api/users/{id}` aceita dados de perfil e, opcionalmente, `senha` e `confirmacaoSenha`. Ao alterar o próprio e-mail/senha, envie também `senhaAtual`. Mudanças de senha/e-mail encerram outras sessões; uma edição própria mantém a sessão atual. Rebaixamento/desativação de terceiros revoga suas sessões.

`GET /api/audit` exige administrador ou geral. Parâmetros: `q`, `action`, `entity`, `outcome`, `from`, `to`, `page`. As datas usam o dia de Brasília. Retorna `items`, `total`, `page`, `pageSize` (50).

A migração V2 adiciona registros persistentes de criação/edição/exclusão nas tabelas de negócio. Os gatilhos PostgreSQL participam da transação original, com autor obtido da sessão pelo servidor; se a ação falha, suas alterações são revertidas. Tentativas de acesso com erro e eventos de autenticação são gravados separadamente, inclusive quando o login falha ou é bloqueado. `last_seen` não gera eventos. Alterar senha gera apenas uma indicação, sem armazenar valor ou hash. Não existem rotas de edição/exclusão de logs, e gatilhos bloqueiam UPDATE, DELETE e TRUNCATE na tabela. Um administrador do próprio banco ainda pode alterar a estrutura; esta funcionalidade não é um armazenamento externo imutável.

A auditoria começa com a V2 e não reconstrói ações anteriores. Ações de processos de fundo sem contexto de sessão recebem `Sistema` e IP nulo. A coleta automática via OpenAI foi adicionada na revisão 1.5 e também é auditada. RSS não está integrado.

No Railway, a API deve continuar privada, acessível pelo Caddy do portal. O Caddy sobrescreve `X-Portal-Client-IP` com `X-Real-IP` do HTTP edge; o servidor valida um endereço numérico. Fora do Railway usa o endereço direto da conexão. Referência: https://docs.railway.com/networking/public-networking/specs-and-limits

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
manual no Radar. RSS e sincronização com Google Drive não estão configurados. A Biblioteca faz upload/download real pelo PostgreSQL.

## Revisão 1.4

A V3 adiciona `member_preferences`, `library_folders`, `library_files` e deduplicação de notificações. V1 e V2 não foram modificadas.

- `GET/PUT /api/me/preferences`: preferências validadas e isoladas por conta, com auditoria.
- `PATCH /api/settings/shortcuts/{id}`: salva nome, link e/ou logo (`label`, `url`, `image`) de um atalho; campos ausentes ficam como estão.
- `POST /api/settings/shortcuts`: cria um atalho no fim da lista (nome vazio vira "Novo atalho").
- `DELETE /api/settings/shortcuts/{id}`: remove o atalho.
- A logo (`image`) é um data URL PNG/JPEG/WEBP/GIF de até 256 KB; `""` remove. Coluna criada pela migração `V7__shortcut_images.sql`.
- `PATCH /api/projects/{id}`: dados, status, responsáveis e marcos do projeto.
- `GET /api/library/folders` e `GET/POST /api/library/files`: arquivos reais da equipe. Upload JSON com `name`, `folderId`, `mediaType` e `content` em base64; limite de 5 MiB por arquivo.
- `GET /api/library/files/{id}/content?download=true`: download autenticado; tipos não permitidos para prévia usam application/octet-stream e attachment. Conteúdo de arquivos não entra na auditoria.
- `DELETE /api/library/files/{id}`: somente dono ou administrador; auditado.
- `NotificationJobs`: lembretes de prazo no portal, deduplicados por tarefa/conta/data; resumo diário do Radar após 8h de Brasília quando ativado. Respeita as preferências e registra Sistema como autor. Notificações de atribuição e de atualização de projeto também respeitam as preferências.

E-mail externo não está configurado, portanto a API recusa ativar essa preferência. A interface informa e desabilita a opção. O push externo foi incluído na revisão 1.5; RSS automático continua não integrado. A pesquisa automática via OpenAI está descrita abaixo.

Rode `mvn test` com JDK 17. Os testes usam PostgreSQL embarcado e não dependem do banco de produção. O Dockerfile compila com Java 17 e não executa o PostgreSQL de testes como root durante a construção da imagem.


## Revisão 1.5 — Radar e categorias reais

> **Descontinuado na V1.9.** A coleta automática pela OpenAI e os endpoints `/api/settings/radar*`, `/api/settings/radar-sources`, `/api/news/saved` e `/api/news/:id/publish` foram removidos. Veja a seção V1.9.

- `OPENAI_API_KEY`: configure somente no serviço **api**, pelo painel de variáveis do Railway. Nunca no frontend, ZIP, repositório ou campo de fonte. Sem chave, a interface informa a pendência e a coleta não é iniciada.
- `OPENAI_MODEL`: opcional; padrão `gpt-6.2-astra`. O modelo precisa aceitar Responses API com web_search, filtros por domínio e Structured Outputs.
- `GET/PUT /api/settings/radar`: somente admin/geral. Configuração persistida: enabled, intervalMinutes (15–1440), lookbackHours (1–168), maxItems (1–10), criteria (10–4000 caracteres), autoPublish e fetchImages. O GET inclui nextRunAt, keyConfigured, model e as últimas 10 execuções. A chave nunca é retornada.
- `POST /api/settings/radar/collect`: retorna 202 e runId. Pesquisa em segundo plano. Bloqueia coletas simultâneas e exige 1 minuto entre pesquisas manuais.
- O processo agendado verifica a configuração a cada minuto. O intervalo é contado a partir do início da execução; não é um horário fixo do dia. Configuração e próximo horário sobrevivem ao reinício. A API precisa permanecer em execução. Execuções interrompidas são marcadas como falha após 20 minutos quando uma nova coleta é iniciada.
- As URLs de fontes ativas delimitam domínios/subdomínios autorizados. Não há monitoramento RSS nesta versão. Não cole link de conversa/agendamento do ChatGPT: ele não é um feed do portal.
- Cada pesquisa tem uma etapa web com referências e outra para estruturar resumos próprios. A API recusa links não citados/fora das fontes, conteúdo extenso, datas inválidas/futuras e matérias fora da janela. Não preenche a cota se não houver novidades. Nenhum modelo elimina a necessidade de conferir as fontes antes de usar uma pauta editorial.
- URLs canônicas (sem parâmetros comuns de rastreamento) e títulos normalizados evitam duplicações exatas; as pautas recentes também são enviadas ao filtro editorial para reduzir republicações do mesmo assunto. Isso não garante deduplicação semântica perfeita entre textos de títulos diferentes.
- Imagens: busca a capa Open Graph/Twitter da matéria; na ausência, pesquisa Wikimedia Commons com licença compatível, guardando autor/licença/origem e marcando como ilustração. PNG/JPEG/WebP/GIF até 2 MiB ficam no PostgreSQL. Sem imagem disponível, usa a capa visual da categoria; o histórico informa a ausência. O vínculo à imagem da matéria não transfere direitos para reutilização em outros materiais.
- `GET /api/news`: publicadas de fontes ativas. `GET /api/news?drafts=true`: fila restrita aos administradores. `POST /api/news/:id/publish`: aprovação. `DELETE /api/news/:id`: administrador. `GET /api/news/:id/image`: imagem autenticada; usuário comum não acessa rascunhos.
- Publicação manual aceita imageUrl e imageCredit opcionais, com os mesmos limites. Referências e créditos são exibidos na leitura. Importações automáticas recebem autor Sistema e IP nulo na auditoria. Configuração/acionamento manual usa a conta autenticada. Imagens e chave não são copiadas aos logs.
- O transporte de fontes valida DNS a cada conexão e redirecionamento, bloqueia endereços privados/locais/metadata e portas especiais, limita bytes/tempo e não encaminha a chave da OpenAI para nenhum site de notícia.
- Projetos agora têm category (opcional; mesmas seis áreas das tarefas) e brand (marca/cliente opcional). Projetos antigos ficam sem categoria. Novas tarefas herdam a categoria como sugestão quando ela não é informada; edições de categoria do projeto não reclassificam tarefas existentes.
- Relatórios carregam tarefas, projetos e pessoas pela API, com filtros por marca/projeto e gráficos distintos para categorias de tarefas e de projetos. Progresso: tarefas concluídas/total; sem tarefas usa marcos concluídos/total ou 100% se o projeto estiver concluído. Sem tarefas/marcos/status concluído, é 0%.

Documentação de referência: https://developers.openai.com/api/docs/guides/tools-web-search e https://developers.openai.com/api/docs/guides/structured-outputs.


## Notificações push (revisão 1.5, migração V5)

`GET /api/push` retorna chave VAPID pública, aparelhos e tentativas da própria conta (sem endpoint/auth/chave privada). `POST /api/push/subscriptions` recebe id UUID do aparelho, endpoint, keys {p256dh,auth} e device. `DELETE /api/push/subscriptions/:id` remove apenas aparelhos da própria conta. `POST /api/push/test` cria um aviso na fila, com intervalo mínimo de 1 minuto entre testes.

VAPID é gerado automaticamente no primeiro início e mantido em push_config. Não exporte private_key para front/logs. WEB_PUSH_SUBJECT é contato opcional mailto: ou https://; padrão é o URL atual do portal. A API envia payload AES128GCM/VAPID por HTTPS a provedores reconhecidos (Google/Mozilla/Apple/Windows), com DNS público e sem redirecionamento. A dependência web-push é usada apenas para cifragem/assinatura; o transporte usa o cliente HTTP público validado. Dependências de transporte antigas são excluídas.

O trigger de notifications insere push_deliveries na mesma transação para os aparelhos inscritos. A cada 15 segundos, o job reivindica uma pequena fila com SKIP LOCKED, envia, persiste estado e retenta falhas temporárias. HTTP 404/410 remove inscrições expiradas; a tentativa é preservada. Avisos expiram em 24 horas. O aceite do provedor não comprova entrega/leitura. Falhas no intervalo entre aceite remoto e commit podem causar reenvio; o Service Worker usa tag por notificationId para reduzir duplicações visuais.

No logout, o cabeçalho X-Push-Device enviado pelo cliente identifica somente a inscrição da própria conta a revogar. O navegador também cancela a inscrição local e limpa a conta do Service Worker. O Service Worker não faz cache de páginas privadas ou API e confere o accountId antes de exibir um aviso. As operações de aparelhos e entrega são auditadas sem gravar as credenciais de inscrição.

Novas tarefas abertas também notificam os membros ativos que não são o criador/responsáveis, respeitando taskAssigned. As notificações já existentes de atribuição, prazos, projetos e resumo do Radar participam da fila de push. Notificações externas por e-mail continuam indisponíveis. A API precisa permanecer em execução para processar os agendamentos e a fila.


## Revisão 1.6

Sem alteração das migrações V1–V5. OpenAiErrors classifica os códigos retornados pela API sem exibir o corpo bruto: crédito esgotado, faturamento, limite de gastos/uso, limite temporário, autenticação e modelo indisponível. HTTP 429 desconhecido é apresentado como causa indeterminada. Um novo teste cobre os principais códigos e a ausência de segredos nas mensagens.


## Revisão 1.7 — Agenda e tickets

Regras persistentes diárias/a cada N dias, semanais e por datas selecionadas. A API libera tarefas individuais por responsável e horário de Brasília, sem antecipar a criação. Contador transacional global identifica cada tarefa manual/automática com ticket próprio, sem renumerar edições ou reaproveitar excluídos. V6 mantém V1–V5 intactas. Usuários recebem somente suas tarefas pela API; administradores podem filtrar todos ou cada responsável. A versão aparece ao lado do sino. Consulte ../REVISAO-1.7.md e ../ATUALIZAR-CMD.md.

## Revisão 1.9 — Radar manual, fotos, notificações e Financeiro (migração V8)

Migração `V8__portal_v19.sql` é aditiva: não apaga dados. Desliga `radar_settings.enabled`, adiciona colunas em `news`, cria `member_photos`, `finance_investments`, `finance_payments` e `members.finance_access`, e atualiza a auditoria para nunca gravar conteúdo binário.

**Radar de notícias (manual)** — gerenciamento: administradores.
- `GET /api/news`: notícias com posição (`slot`): principal, secundárias e gerais, nessa ordem.
- `POST /api/news` `{title, summary, body, slot, image?, sourceName?, sourceUrl?, publishedAt?}`: `image` é data URL PNG/JPEG/WEBP (até 2 MB), obrigatória para `principal` e `secundaria`.
- `PATCH /api/news/{id}`: mesmos campos, todos opcionais; `image: ""` remove (só para gerais).
- `DELETE /api/news/{id}` e `GET /api/news/{id}/image`.
- Limites: 1 principal, 3 secundárias, 5 gerais (409 com mensagem clara). Conferidos com trava transacional; um índice único impede duas principais. Notícias antigas da coleta automática continuam no banco, sem posição, e não aparecem.

**Ideias** — `DELETE /api/ideas/{id}` (administradores): exclusão definitiva; votos saem em cascata. Ideias arquivadas antes da V1.9 são preservadas.

**Foto de perfil** — `PUT /api/users/{id}/photo {image}` (data URL PNG/JPEG/WEBP até 1 MB), `DELETE /api/users/{id}/photo`, `GET /api/users/{id}/photo`. Quem pode editar o usuário pode trocar a foto. `avatarUrl` vem em `/api/users` e `/api/auth/me` (null = iniciais).

**Notificações** — `GET /api/notifications` inclui `createdAtIso`; `POST /api/notifications/{id}/read` marca uma como lida. Eventos: tarefa atribuída, tarefa movida de coluna, prazo próximo, novo projeto, status de projeto alterado.

**Financeiro** — acesso: administradores e usuários com `financeAccess` (concedido por administrador em `PATCH /api/users/{id} {financeAccess}`). Todos os endpoints conferem o acesso.
- `GET /api/finance/summary`: total mensal estimado (recorrentes ativos), pago no mês, pendente, vencido e próximos vencimentos.
- `GET/POST /api/finance/investments`, `PATCH/DELETE /api/finance/investments/{id}`, `GET /api/finance/investments/{id}/payments` (histórico).
- `GET /api/finance/payments?kind=pix|boleto`, `POST /api/finance/payments`, `PATCH/DELETE /api/finance/payments/{id}`, `POST /api/finance/payments/{id}/pay {paidAt, receipt?, receiptName?}`.
- `GET /api/finance/payments/{id}/attachment` e `/receipt` (PDF ou imagem até 4 MB, guardados no banco).
- Status `vencido` é calculado (pendente com vencimento passado). Nada é marcado como pago automaticamente.
- Cobranças recorrentes geram uma ocorrência por período até o mês seguinte, a partir do mês de cadastro. O índice único `(investment_id, period)` impede duplicatas em reinícios. Excluir uma ocorrência recorrente a cancela.
- Validações: CPF/CNPJ com dígitos verificadores, chave Pix por tipo, linha digitável com 47 ou 48 dígitos.
- Variáveis opcionais: `FINANCE_INITIAL_DELAY_MS` (padrão 90000) e `FINANCE_INTERVAL_MS` (padrão 21600000).

**Variáveis que deixaram de ser usadas:** `OPENAI_API_KEY`, `OPENAI_MODEL`, `RADAR_INITIAL_DELAY_MS`, `RADAR_POLL_MS`. Podem ser removidas do Railway quando quiser; mantê-las não causa problema.


## Portal 1.1.11 — vagas do Backlog

`eligibleAssigneeIds` define quem pode aceitar: uma lista vazia permite qualquer conta ativa. Essa lista pode ser maior que `maxAssignees`. `assigneeIds` contém apenas as pessoas que efetivamente aceitaram; convidar não ocupa vaga. No Backlog, o servidor mantém as inscrições abertas até completar o número de aceites, passando então para `a_fazer`. O aceite é idempotente e serializado pelo bloqueio transacional existente, inclusive com várias réplicas. Os candidatos que não aceitaram não ganham permissão de mover a tarefa.

O editor usa campos separados e não permite preencher `assigneeIds` manualmente no Backlog. Para clientes antigos, ao criar uma tarefa de Backlog sem `eligibleAssigneeIds`, a antiga seleção de `assigneeIds` é interpretada como lista de candidatos e os aceites começam vazios. Nas tarefas já em execução, `assigneeIds` continua sendo a equipe responsável.

A migração V10 adiciona a lista de candidatos e abre inscrições de tarefas antigas no Backlog que estavam fechadas indevidamente. Preserva os aceites e tickets existentes. Tarefas antigas de Backlog que já estavam com todas as vagas preenchidas seguem para A fazer. Migrações V1–V9 permanecem intactas.


## Portal 1.1.12 — Agenda compartilhada

A Agenda aceita `initialStatus` (`backlog` ou `a_fazer`) e `maxAssignees` (1–100). Uma ocorrência gera uma única tarefa para toda a equipe: em A fazer, `assigneeIds` da regra é a equipe atribuída; no Backlog, vira `eligibleAssigneeIds` da tarefa, com zero aceites iniciais. A tarefa só passa para A fazer ao completar o número de aceites, usando o mesmo bloqueio transacional e regras das tarefas manuais. Uma lista vazia no Backlog permite qualquer conta ativa. As listagens mostram a equipe completa.

V11 permite ocorrências compartilhadas sem `schedule_member_id` e adiciona índices únicos para impedir lançamento duplicado. Preserva tarefas individuais antigas, tickets e registros de execução, inclusive de tarefas excluídas. Regras antigas continuam em A fazer e passam a gerar uma tarefa com todos os vinculados nas próximas aberturas. O DTO `scheduleIndividual` identifica apenas as ocorrências históricas que continuam com atribuição individual fixa. Edite a regra para mudar as próximas aberturas, ou a tarefa para mudar uma ocorrência compartilhada. Migrações V1–V10 ficam intactas.
