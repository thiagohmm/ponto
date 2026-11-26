export function HttpClientPage() {
  return (
    <div className="card" style={{ maxWidth: 720 }}>
      <p>
        Cliente HTTP no estilo Postman/Bruno que roda <strong>na sua máquina</strong>.
        A requisição não passa pelo servidor do Ponto — assim alcança APIs só acessíveis
        da sua rede, VPN ou localhost.
      </p>
      <p className="muted">
        Baixe o JAR (Java 17–25), rode localmente e sincronize a collection com o banco
        deste servidor. Curls importados ficam em Postgres e são baixados de{" "}
        <code>https://ponto-thiagohmm1.us1.pitunnel.net</code>.
      </p>
      <div className="row actions" style={{ marginTop: 16, gap: 12, flexWrap: "wrap" }}>
        <a className="btn primary" href="/downloads/ponto-http.jar" download="ponto-http.jar">
          Baixar ponto-http.jar
        </a>
        <a className="btn" href="http://127.0.0.1:1984" target="_blank" rel="noreferrer">
          Abrir UI local
        </a>
      </div>
      <h2 style={{ marginTop: 28, fontSize: "1.05rem" }}>Como usar</h2>
      <ol className="muted" style={{ lineHeight: 1.7, paddingLeft: 20 }}>
        <li>Instale um JDK 17+ (<code>java -version</code>) — no WSL, o Java do próprio WSL.</li>
        <li>
          No terminal: <code>java -jar ponto-http.jar</code>
        </li>
        <li>
          Na UI local, clique em ☁ Sync: URL
          <code>https://ponto-thiagohmm1.us1.pitunnel.net</code>, usuário/senha do Ponto,
          companyId da empresa ativa, depois <em>Baixar</em> / <em>Enviar</em>.
        </li>
      </ol>
      <h2 style={{ marginTop: 24, fontSize: "1.05rem" }}>Onde fica</h2>
      <p className="muted" style={{ lineHeight: 1.7 }}>
        Fonte da verdade: tabela <code>http_client_collection</code> no Postgres (por empresa).
        Cache local do JAR: <code>~/.local/share/ponto-http/</code>.
      </p>
      <h2 style={{ marginTop: 24, fontSize: "1.05rem" }}>Windows e WSL2</h2>
      <p className="muted" style={{ lineHeight: 1.7 }}>
        Enquanto o JAR estiver rodando no Windows ou WSL2, a tela e o sistema são mantidos
        ligados automaticamente (restaurado ao sair com <code>Ctrl+C</code>). Se a API estiver
        no Windows via WSL2, use a opção <em>localhost → Windows</em>; o browser do Windows
        abre sozinho ao subir o JAR.
      </p>
      <p className="muted" style={{ marginTop: 12 }}>
        Porta alternativa: <code>java -jar ponto-http.jar --port=9090</code>
        {" · "}
        Sem abrir o browser: <code>--no-open</code>
        {" · "}
        Sem manter tela ligada: <code>--no-keep-screen-on</code>
      </p>
    </div>
  );
}
