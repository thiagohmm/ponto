# Operação e recuperação

O Ponto continua sendo uma aplicação pessoal na rede local, sem contas de usuários.
O deploy padrão usa SSH em `thiagohmm@192.168.0.8` e publica a web na porta 8090.
O diretório padrão da instalação é `/home/thiagohmm/ponto-web`.

## Verificação e deploy

É necessário Java 25, Maven, Node 24, Python 3.11+, zsh e SSH. Antes do primeiro
uso dos testes de navegador, execute `cd web && npx playwright install chromium`.
No Linux, as bibliotecas do navegador podem ser instaladas com
`npx playwright install --with-deps chromium`.

```sh
./deploy/deploy.sh
```

O comando executa testes da interface, build, testes Java, smoke do terminal,
teste de restauração e testes no Chromium. Envia somente o JAR e os arquivos
operacionais. `PONTO_HOST` e `PONTO_REMOTE` substituem servidor e instalação.
O servidor precisa de Docker Compose, Python 3.11+ e crontab.

No servidor, o deploy:

1. Constrói uma imagem candidata enquanto a aplicação atual continua disponível.
2. Salva a imagem anterior, interrompe Ponto/MinIO e cria um snapshot consistente
   de banco, objetos, uploads pendentes e configuração. Outros processos que
   escrevam nesses volumes, incluindo o terminal, devem estar parados.
3. Migra uma cópia do snapshot usando a imagem candidata, sem rede, e confere
   a preservação da quantidade de registros.
4. Instala o candidato e verifica saúde, empresas, registros, notas, upload,
   download e CSV/XLS/PDF com dados temporários removidos ao final.
5. Confere integridade do banco e agenda o backup diário. Se a atualização
   falhar, restaura o banco, objetos, configuração e imagem anteriores.

O arquivo `release.json` registra a imagem, SHA-256 do JAR e snapshot anterior.
O horário e a porta são configurados pela `.env` do servidor. Não envie essa
`.env` ao Git. Credenciais antigas padrão são substituídas por valores aleatórios
na primeira atualização; o script não imprime seus valores.

O MinIO usa imagem fixada por digest. Sua API fica apenas na rede dos
contêineres; a console está em `127.0.0.1:9001` no servidor e pode ser acessada
por túnel SSH: `ssh -L 9001:127.0.0.1:9001 thiagohmm@192.168.0.8`.

## Backup

O deploy instala uma entrada identificada por `# ponto-backup` no crontab do
usuário, às 03:15 no fuso do servidor, preservando as outras entradas.
O backup interrompe brevemente os dois serviços para manter banco e objetos
coerentes. Não copie somente o arquivo SQLite em uso: há dados no WAL.

```sh
python3 deploy/backup.py backup --project /home/thiagohmm/ponto-web
python3 deploy/backup.py verify --snapshot /caminho/do/snapshot
```

O destino padrão é `~/.local/share/ponto-backups` (ou `PONTO_BACKUP_DIR`):
sete snapshots diários, quatro semanais e três anteriores a deploys.
Snapshots incluem segredos da configuração; diretórios e arquivos são privados
ao usuário. O `manifest.json` contém hashes e a verificação também executa
`PRAGMA integrity_check`. Snapshots incompletos não entram na retenção.
Os backups ficam no mesmo host; uma cópia fora desse host deve ser mantida
se a recuperação de falha física do disco for necessária.

## Restauração

Primeiro valide o snapshot e confira os destinos. Sem `--apply`, nenhuma
restauração é feita. `--offline` é destinado a testes isolados ou a volumes
sem nenhum processo escritor; não deve ser usado para contornar a parada.

```sh
python3 deploy/backup.py restore --snapshot /caminho/do/snapshot
python3 deploy/backup.py restore --snapshot /caminho/do/snapshot --apply --restore-config
```

O segundo comando interrompe os serviços, restaura os volumes e recria os
contêineres. A opção `--restore-config` recupera também o JAR, Compose, `.env`
e, quando presente, a imagem Docker anterior. Os diretórios substituídos são
preservados com sufixo `.before-restore-*` para inspeção posterior.
Uma imagem antiga não deve abrir o banco já migrado: use seu snapshot anterior.

Para ensaiar sem tocar na instalação, use `--offline`, destinos `--data` e
`--objects` diferentes dos volumes reais, e não use `--restore-config`.

## API e diagnóstico

- `/api/health` não exige empresa e informa banco, armazenamento, versão e fila.
  Falha no S3 não torna o banco indisponível.
- `/api/companies` permite carregar o catálogo sem seleção prévia.
- As demais rotas exigem `?companyId=N`, inclusive anexos e exportações.
  A empresa selecionada fica no `sessionStorage` de cada aba; o terminal mantém
  sua preferência no banco. O endpoint antigo `/api/companies/active` apenas
  valida a escolha e não altera a preferência do terminal.
- A atualização de conteúdo de nota exige `revision`. Uma versão desatualizada
  recebe 409 e o texto salvo permanece intacto. A interface permite baixar o
  rascunho e reabrir a versão salva.
- Anexos têm estado `uploading`, `ready` ou `failed`. Operações pendentes ficam
  no SQLite, com upload temporário no volume `uploads`, e têm no máximo três
  tentativas automáticas. Configurações permite reagendá-las por
  `POST /api/storage/retry?companyId=N`.
- `PONTO_UPLOAD_MIB` aceita 1 a 100, padrão 20. `PONTO_TZ` tem padrão
  `America/Sao_Paulo`. A interface usa o relógio local do navegador nos botões
  “Agora”; mantenha os dispositivos no fuso de uso desejado.
- `docker compose logs --tail=100 ponto` mostra falhas com identificador da
  requisição. Não é necessário ativar logs de bibliotecas no terminal.

A PWA precacheia os assets de cada build e pede confirmação para atualizar com
alterações pendentes. Service workers exigem HTTPS ou localhost. No endereço
HTTP da rede local, a interface web funciona normalmente, mas cache offline e
instalação PWA dependem de um contexto seguro. Não há fila de alterações offline:
a interface informa a falha e permite tentar salvar novamente.
