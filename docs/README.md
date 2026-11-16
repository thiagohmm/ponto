# Documentação do Ponto

Bem-vindo! Esta pasta é a **referência oficial** do projeto para quem está chegando agora.

> ⚠️ **REGRA DE MANUTENÇÃO DA DOCUMENTAÇÃO**
> Toda mudança de funcionalidade (novo endpoint, nova regra de negócio, nova página, mudança de schema) **deve atualizar a documentação e os diagramas correspondentes no mesmo PR/commit**.
> Documentação desatualizada é tratada como **bug** — se você mudou o código e não mudou a doc, o trabalho não terminou.

## Por onde começar

| Se você quer… | Leia |
|---|---|
| Entender o que o Ponto faz, em linguagem simples | [README.md](../README.md) — seção "Entendendo o projeto" |
| Ver o quadro geral do sistema (frontend + backend) | [arquitetura.md](arquitetura.md) |
| Trabalhar no backend (Java) | [backend.md](backend.md) |
| Trabalhar no frontend (React) | [frontend.md](frontend.md) |
| Ver os diagramas | [docs/diagramas/](diagramas/) |

## Diagramas (PlantUML)

Os fontes `.puml` ficam em [`diagramas/`](diagramas/) e as versões renderizadas em [`diagramas/img/`](diagramas/img/) (SVG, commitado junto).

| Diagrama | Fonte | Mostra |
|---|---|---|
| Contexto | [01-contexto.puml](diagramas/01-contexto.puml) · [SVG](diagramas/img/contexto.svg) | Quem usa o Ponto e quais sistemas ele toca |
| Contêineres | [02-containers.puml](diagramas/02-containers.puml) · [SVG](diagramas/img/containers.svg) | SPA, API Javalin, Service, Postgres, MinIO, Ollama |
| Camadas do backend | [03-backend-camadas.puml](diagramas/03-backend-camadas.puml) · [SVG](diagramas/img/backend-camadas.svg) | Pacotes `controller → app → model/infra/view` |
| Sequência: lançamento | [04-sequencia-lancamento.puml](diagramas/04-sequencia-lancamento.puml) · [SVG](diagramas/img/sequencia-lancamento.svg) | POST /api/entries de ponta a ponta (confirmações 409) |
| Sequência: login | [05-sequencia-login.puml](diagramas/05-sequencia-login.puml) · [SVG](diagramas/img/sequencia-login.svg) | Cookie `ponto_session`, rate limit, sessão em memória |
| Navegação do frontend | [06-frontend-navegacao.puml](diagramas/06-frontend-navegacao.puml) · [SVG](diagramas/img/frontend-navegacao.svg) | Rotas React, lazy loading, providers |
| Modelo de dados | [07-er-modelo-dados.puml](diagramas/07-er-modelo-dados.puml) · [SVG](diagramas/img/er-modelo-dados.svg) | Tabelas e relações (migrações v6) |
| Build e deploy | [08-deploy.puml](diagramas/08-deploy.puml) · [SVG](diagramas/img/deploy.svg) | Do `mvn package` ao host LAN com rollback |
| Fluxo de dados do frontend | [09-fluxo-dados-frontend.puml](diagramas/09-fluxo-dados-frontend.puml) · [SVG](diagramas/img/fluxo-dados-frontend.svg) | `api.ts`, companyId, tratamento de 401/409 |

### Como renderizar os diagramas

A fonte é a verdade; o SVG é só a cópia renderizada. Para regenerar depois de editar um `.puml`:

```sh
# baixe o plantuml.jar uma vez (fica fora do Git, veja .gitignore)
curl -L -o tools/plantuml.jar \
  "https://github.com/plantuml/plantuml/releases/download/v1.2025.7/plantuml-1.2025.7.jar"

# renderize todos (o layout Smetana é embutido: não precisa de Graphviz)
java -jar tools/plantuml.jar -Playout=smetana -tsvg -o img docs/diagramas/*.puml
```

Alternativas: a extensão **PlantUML** do VS Code (preview com `Alt+D`) ou o IntelliJ PlantUML plugin.

## Outras referências

- [`deploy/README.md`](../deploy/README.md) — operação, backup, restauração e segurança do deploy.
- [`analise-arquitetural-2026-11-15.md`](../analise-arquitetural-2026-11-15.md) — diagnóstico pontual gerado por auditoria (snapshot da data; **não** é a documentação de referência — a referência é esta pasta `docs/`).
