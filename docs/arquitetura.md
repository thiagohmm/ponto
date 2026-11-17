# Arquitetura — visão geral

> ⚠️ Mudou a arquitetura (novo serviço, novo banco, nova integração)? Atualize este arquivo e os diagramas [01](diagramas/01-contexto.puml), [02](diagramas/02-containers.puml) e [03](diagramas/03-backend-camadas.puml) no mesmo PR.

## O que é o Ponto, em uma frase

Um controle de jornada ("cartão de ponto") brasileiro — dia, entrada, saída, justificativa — que funciona **no terminal** e **no navegador**, com PostgreSQL atrás de tudo, distribuído como **um único JAR Java 25**.

## A ideia central que um dev júnior precisa entender

**Um só núcleo, duas portas de entrada.**

```
terminal (menu CLI)  ──┐
                       ├──> Service (fachada) ──> PostgreSQL
navegador (React SPA) ─┘
```

- O código de negócio (calcular saldo, avisar sobre par sobreposto, feriados) mora **uma vez só**, na fachada `ponto.app.Service`.
- O menu do terminal (`controller/App.java`) e a API HTTP (`controller/Web.java`) são apenas "apresentação": formatação, perguntas ao usuário, JSON.
- Por isso qualquer regra nova aparece nos dois lugares de graça — e por isso a regra de ouro: **lógica de negócio nunca vai para controller**.

## Decisões de arquitetura (e o porquê)

| Decisão | Por quê |
|---|---|
| **Monólito em um JAR** | Aplicação pessoal: um comando (`./ponto` ou `./ponto --serve`) roda tudo. Sem rede externa — o calendário de feriados é código (`model/Holidays.java`). |
| **Camadas simples** `controller → app → infra/model` | Fácil de navegar sem container de DI. `Service` é o ponto de composição: ele instancia os apps e os stores. |
| **Uma conexão JDBC, um `lock`** | Todos os escopos de empresa compartilham a mesma conexão e um objeto de trava. Simples e correto para uma pessoa usuária. |
| **`companyId` explícito na web** | Cada aba do navegador lembra a própria empresa (`sessionStorage`) sem mexer na preferência do terminal. O backend cria um escopo `Service.forCompany(id)` por requisição. |
| **Empresas como partições** | Empresa é uma partição dos dados do mesmo usuário, **não** um tenant com autorização independente. O schema garante isso com `company_id` + FKs compostas. |
| **PWA + offline shell** | `web/public/sw.js` cacheia a casca para instalar como app; dados continuam vindo da API quando há rede. |
| **Anexos fora do banco** | Notas e cartões kanban têm arquivos; os metadados ficam em `note_file`/`kanban_card_file` e os bytes vão para MinIO/S3 através de uma fila (`object_job`) com retries. |
| **Cofre zero-knowledge** | Las passwords são seladas **en el browser** (PBKDF2-SHA256 + AES-256-GCM): o server guardan base64 opaqueos, un salt, un iteration count e un verifier, **jamai** plaintext, hint ou "usado recently". Por isso no existe "forgot master password" — e por isso `VaultStore` não importane cipher ni plaintext model. |
| **Empresas como tags dentro del cofre** | One master password opens **every** company's credential; `vault_item.company_id` is a filter the server can use, not a cryptographic boundary. Una cofre per company would trap the boss with a password per laptop — the dangerous thing here is the forgotten password, not the curious admin. |

## O mapa dos pacotes (backend)

```
src/main/java/ponto/
├── Main.java          # ponto de entrada: --serve, --db, menu, --help
├── controller/        # presentation: menu CLI (v = cofre), rotas HTTP, Auth, VaultRoutes
├── app/               # FACHADA Service + apps por capacity (VaultApp, TimesheetApp…)
├── model/             # records + regras puras (Entry, BankRules, Holidays…)
│                      #   + VaultCrypto (PBKDF2/AES-GCM), VaultPayload, VaultGen
├── infra/            # JDBC, migrações (v7), stores com SQL (VaultStore), MinIO, Ollama
└── view/              # saída: Json (HTTP), Csv/Xls/Pdf, In (text, secret)/Out (terminal)
                       #   + VaultFile (backup ponto-cofre/1)
```

`VaultApp` and `VaultRoutes` exist because the cofre has its own rules (seal before any record, rekey all or nothing) and its own endpoints: it is a capacity, not a column in `TimesheetApp`. `VaultCrypto` lives in `model/` because it is a rule about bytes; `VaultFile` lives in `view/` because it is one way of rendering those blobs to a file.

Detalhes em [backend.md](backend.md).

## O mapa do frontend

```
web/src/
├── main.tsx / App.tsx   # rotas, providers, shell (sidebar + abas)
├── api.ts               # único lugar que fala com /api/*
├── Auth.tsx             # login, cookie, guarda RequireAuth
├── Company.tsx          # empresa ativa por aba (sessionStorage)
├── vault/               # crypto (WebCrypto) + generator, strength, duplicates,
│                        #   clipboard, auto-lock, backup file, Vault.tsx (hook)
├── pages/               # uma aba = um arquivo (lazy loading)
└── ui/                  # Confirm, Unsaved, Connection, ícones
```

Detalhes em [frontend.md](frontend.md).

## Diagramas

- Visão macro: ![Contexto](diagramas/img/contexto.svg)
- Por dentro do JAR e do host: ![Contêineres](diagramas/img/containers.svg)
- Camadas do backend: ![Camadas](diagramas/img/backend-camadas.svg)

## Onde cada coisa mora (cheat sheet)

| Quero mudar… | Arquivo(s) | Cuidado com |
|---|---|---|
| Uma regra de saldo/banco de horas | `model/BankRules.java`, `app/TimesheetApp.java` | Testes em `test/` |
| Um endpoint HTTP | `controller/Web.java` ou `controller/*Routes.java` | Atualizar `web/src/api.ts` + `types.ts` |
| Uma página do web | `web/src/pages/*.tsx` | Tipos e textos de acessibilidade |
| Schema do banco | `infra/Migrations.java` | Versão + migração in-place + deploy com snapshot |
| Feriados | `model/Holidays.java` | Não precisa internet — teste no ano seguinte |
| Deploy | `deploy/` | Ler `deploy/README.md` antes |
