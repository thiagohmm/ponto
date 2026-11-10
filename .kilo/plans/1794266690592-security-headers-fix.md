# Correção dos achados de segurança (scan 2026-11-09)

Origem: avaliação sem login em `http://192.168.0.8:8090` e na URL pública PiTunnel. Quatro achados; decisões tomadas com o usuário nesta sessão.

## Decisões

| Achado | Decisão |
|---|---|
| Login por HTTP na LAN (8090) | **Risco aceito** — o endereço `192.168.0.8` só é acessível de dentro de casa (decisão do usuário). Sem mudança de bind. Apenas documentar. |
| HSTS ausente no HTTPS | Corrigir **na aplicação** (Javalin): cobre PiTunnel, Caddy e acesso direto. |
| CSP ausente | Corrigir **na aplicação** + mover o script inline de tema para arquivo externo. |
| `Server` expõe Caddy / nginx-Ubuntu | Caddy: remover com `header -Server`. Jetty: desligar explicitamente. **nginx/Ubuntu é a borda do PiTunnel: fora do nosso controle — documentar como fora de escopo.** |

## Tarefas (em ordem)

### 1. Headers de segurança na aplicação — `src/main/java/ponto/controller/Web.java`

No `app.before(...)` existente (linha ~62, junto de `X-Content-Type-Options` etc.), adicionar:

```java
ctx.header("Content-Security-Policy", "default-src 'self'; script-src 'self'; "
    + "style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self'; "
    + "connect-src 'self'; worker-src 'self'; manifest-src 'self'; frame-src blob:; "
    + "object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'");
ctx.header("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
```

Justificativa da CSP (inventário verificado no código):
- `script-src 'self'` — possível após a tarefa 2; build Vite/React é todo externo, sem `eval`.
- `style-src ... 'unsafe-inline'` — 19 usos de `style={{...}}` no JSX (Form/Config/Notes/NoteNavigation). Risco baixo com scripts travados.
- `img-src ... data: blob:` — `data:` usado em máscaras SVG do `styles.css`; `blob:` usado por `URL.createObjectURL` na prévia de imagens (`FilePreview.tsx`).
- `frame-src blob:` — `<iframe>` com blob URL na prévia de PDF (`FilePreview.tsx:85`).
- `worker-src 'self'` + `manifest-src 'self'` — service worker e PWA manifest.
- **Não** incluir `upgrade-insecure-requests`: quebraria o acesso HTTP da LAN (mantido).
- HSTS sem `preload` (domínio `*.pitunnel.net` não é nosso); browsers ignoram HSTS em HTTP, então enviar sempre é seguro.

### 2. Remover script inline — `web/index.html` + novo `web/public/theme.js`

- Mover o `<script>` inline de tema (`web/index.html:13-24`) para `web/public/theme.js`, sem alterar o conteúdo.
- Em `index.html`: `<script src="/theme.js"></script>` (script clássico no `<head>`: executa antes da primeira pintura, mesma garantia do inline).
- Adicionar `"/theme.js"` à lista `CORE` em `web/public/sw.js` (o `stamp-sw.mjs` só injeta `/assets/*` em `__ASSETS__`; arquivos raiz de `public/` precisam estar explícitos no precache, senão o tema falha offline).
- Vite copia `public/` para `dist/` automaticamente; nenhuma mudança em `vite.config.ts`.

### 3. Esconder versão do servidor

- **Jetty (Javalin 6.7, API confirmada na documentação/issue #2333):** no `Javalin.create(cfg -> {...})` de `Web.java`:
  ```java
  cfg.jetty.modifyHttpConfiguration(httpConfig -> httpConfig.setSendServerVersion(false));
  ```
- **Caddy (`Caddyfile`):** dentro do bloco `:8443 { ... }`, adicionar `header -Server` (remove o `Server: Caddy` — confirmado na documentação/comunidade Caddy).
- nginx/Ubuntu (borda PiTunnel): documentar como fora de escopo em `deploy/README.md`.

### 4. Testes

- `test/ponto/controller/WebTest.java` — estender o teste existente: na resposta de `/api/health`, assertar presença de `Content-Security-Policy` (contendo `default-src 'self'`), `Strict-Transport-Security` (contendo `max-age=`), `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`, `Permissions-Policy`, e que o header `Server` está ausente ou não contém `Jetty`.
- `deploy/smoke.py` — após o `health`, assertar os mesmos headers de segurança na resposta (verificação pós-deploy no servidor). `smoke.py` acessa `127.0.0.1:8090` direto (sem Caddy), então valida a camada da aplicação.
- Suites existentes devem passar sem alteração de comportamento: `npm --prefix web test` (inclui `sw.test.ts`), e2e Playwright (tema/navegação cobrem a mudança do script), `mvn verify`.

### 5. Documentação

- `deploy/README.md`: nota curta — app serve HSTS/CSP/nosniff/frame-deny em todas as respostas; HTTP da LAN (8090) é risco aceito por ser restrito à rede de casa; `Server` da borda pública (nginx do PiTunnel) é infraestrutura de terceiro, fora de escopo.
- `README.md`: uma linha junto ao trecho do `--serve` mencionando os headers de segurança. Mínimo, sem reescrever seções.

### 6. Deploy

```sh
./deploy/deploy.sh
```
O script roda toda a bateria (web tests, build, `mvn verify`, smoke terminal, backup/restore, e2e) antes de enviar. O `remote.py` já faz `--force-recreate` do serviço `edge` (linha 136), então o `Caddyfile` novo entra em vigor no mesmo deploy, com snapshot e rollback automáticos.

### 7. Commit e push (autorizados pelo usuário nesta sessão)

Após deploy verde, commit único incluindo: `Web.java`, `Caddyfile`, `web/index.html`, `web/public/theme.js`, `web/public/sw.js`, `test/ponto/controller/WebTest.java`, `deploy/smoke.py`, `README.md`, `deploy/README.md`. Mensagem sugerida: `security: CSP, HSTS e ocultação de Server header`. Push para o remoto da branch atual.

## Validação pós-deploy (evidência de correção)

```sh
curl -sI http://192.168.0.8:8090/api/health          # CSP, HSTS, nosniff... sem Server: Jetty
curl -skI https://192.168.0.8:8443/api/health        # idem, sem Server: Caddy
curl -sI https://ponto-thiagohmm1.us1.pitunnel.net/api/health   # HSTS + CSP presentes
```
E no browser (DevTools > Console): nenhum erro de CSP ao abrir a app, alternar tema, pré-visualizar anexo de imagem e de PDF (valida `blob:` em img/frame).

## Riscos e observações

- **CSP quebrando algo não inventariado**: mitigado pelo inventário acima + validação manual pós-deploy no browser (login, lançamentos, notas, anexos, exportações, tema claro/escuro).
- **HSTS com `includeSubDomains`** no host `ponto-thiagohmm1.us1.pitunnel.net`: só afeta subdomínios desse host (nossos); sem `preload`.
- **`header -Server` do Caddy** também remove o `Server` que viria do upstream — comportamento desejado aqui.
- Rollback de qualquer falha: automático pelo `remote.py` (snapshot de banco/objetos/config + imagem anterior).
