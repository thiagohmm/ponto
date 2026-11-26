(() => {
  const STORE_KEY = "ponto-http-collection-v1";
  const HISTORY_KEY = "ponto-http-history";

  const CONTENT_TYPES = {
    json: "application/json",
    text: "text/plain",
    xml: "application/xml",
    formUrlEncoded: "application/x-www-form-urlencoded",
    multipartForm: "multipart/form-data",
  };

  const $ = (id) => document.getElementById(id);
  const methodEl = $("method");
  const urlEl = $("url");
  const bodyEl = $("body");
  const bodyModeEl = $("body-mode");
  const bodyModeBar = $("body-mode-bar");
  const bodyNone = $("body-none");
  const bodyEditorWrap = $("body-editor-wrap");
  const bodyForm = $("body-form");
  const bodyGutter = $("body-gutter");
  const formParamsEl = $("form-params");
  const headersEl = $("headers");
  const reqNameEl = $("req-name");
  const collectionNameEl = $("collection-name");
  const collectionListEl = $("collection-list");
  const filterEl = $("collection-filter");
  const followEl = $("follow");
  const sendBtn = $("btn-send");
  const prettifyBtn = $("btn-prettify");
  const respMeta = $("resp-meta");
  const respBody = $("resp-body");
  const respHeaders = $("resp-headers");
  const respError = $("resp-error");
  const curlDialog = $("curl-dialog");
  const curlText = $("curl-text");
  const curlError = $("curl-error");
  const wslMapEl = $("wsl-map");
  const wslMapWrap = $("wsl-map-wrap");
  const wslBanner = $("wsl-banner");
  const keepScreenEl = $("keep-screen");
  const keepScreenWrap = $("keep-screen-wrap");

  let env = { wsl: false, windowsHost: "" };
  let state = emptyState();
  let suppressSave = false;
  let saveTimer = null;
  let syncTimer = null;
  let remoteRevision = 0;
  let bootstrapped = false;

  function uid() {
    return "r_" + Math.random().toString(36).slice(2, 10) + Date.now().toString(36);
  }

  function inferBodyMode(headers, body) {
    const ct = (headers || [])
      .filter((h) => h.enabled !== false)
      .find((h) => (h.name || "").toLowerCase() === "content-type");
    const v = ((ct && ct.value) || "").toLowerCase();
    if (v.includes("application/json") || v.includes("+json")) return "json";
    if (v.includes("xml")) return "xml";
    if (v.includes("x-www-form-urlencoded")) return "formUrlEncoded";
    if (v.includes("multipart/form-data")) return "multipartForm";
    if (v.includes("text/plain")) return "text";
    if (body && /^\s*[\[{]/.test(body)) return "json";
    if (body && body.trim()) return "text";
    return "none";
  }

  function parseFormBody(body) {
    if (!body) return [{ name: "", value: "", enabled: true }];
    return body.split("&").map((part) => {
      const i = part.indexOf("=");
      const name = i < 0 ? decodeURIComponent(part) : decodeURIComponent(part.slice(0, i));
      const value = i < 0 ? "" : decodeURIComponent(part.slice(i + 1).replace(/\+/g, " "));
      return { name, value, enabled: true };
    });
  }

  function defaultRequest(partial = {}) {
    const headers = partial.headers || [{ name: "Accept", value: "application/json", enabled: true }];
    const body = partial.body || "";
    const bodyMode = partial.bodyMode || inferBodyMode(headers, body);
    const formParams = partial.formParams
      || ((bodyMode === "formUrlEncoded" || bodyMode === "multipartForm")
        ? parseFormBody(body)
        : [{ name: "", value: "", enabled: true }]);
    return {
      id: uid(),
      name: partial.name || "Nova requisição",
      method: partial.method || "GET",
      url: partial.url || "",
      headers,
      body,
      bodyMode,
      formParams,
    };
  }

  function emptyState() {
    const first = defaultRequest({ name: "Untitled Request" });
    return { name: "Minha collection", activeId: first.id, items: [first] };
  }

  function normalizeState(raw) {
    if (!raw || !Array.isArray(raw.items) || !raw.items.length) return emptyState();
    raw.items.forEach((it) => {
      if (!it.id) it.id = uid();
      if (!it.bodyMode) it.bodyMode = inferBodyMode(it.headers, it.body);
      if (!it.formParams) {
        it.formParams = (it.bodyMode === "formUrlEncoded" || it.bodyMode === "multipartForm")
          ? parseFormBody(it.body || "")
          : [{ name: "", value: "", enabled: true }];
      }
    });
    return {
      name: raw.name || "Minha collection",
      activeId: raw.activeId || raw.items[0].id,
      items: raw.items,
    };
  }

  function localLegacyState() {
    try {
      const raw = JSON.parse(localStorage.getItem(STORE_KEY) || "null");
      if (raw && Array.isArray(raw.items) && raw.items.length) return normalizeState(raw);
    } catch { /* ignore */ }
    try {
      const hist = JSON.parse(localStorage.getItem(HISTORY_KEY) || "[]");
      if (Array.isArray(hist) && hist.length) {
        const items = hist.slice(0, 40).map((h) => defaultRequest({
          name: requestTitle(h.method, h.url),
          method: h.method,
          url: h.url,
          headers: h.headers || [],
          body: h.body || "",
        }));
        return { name: "Minha collection", activeId: items[0].id, items };
      }
    } catch { /* ignore */ }
    return null;
  }

  function saveState() {
    // keep a local mirror for faster paint / offline reopen of the same tab
    try {
      localStorage.setItem(STORE_KEY, JSON.stringify(state));
    } catch { /* ignore quota */ }
    if (!bootstrapped) return;
    clearTimeout(saveTimer);
    saveTimer = setTimeout(pushRemote, 250);
  }

  async function pushRemote() {
    try {
      const res = await fetch("/api/collection", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          name: state.name,
          activeId: state.activeId,
          items: state.items,
        }),
      });
      const data = await res.json();
      if (res.ok && data.revision != null) remoteRevision = data.revision;
    } catch { /* ignore transient */ }
  }

  async function pullRemote({ force } = {}) {
    try {
      const res = await fetch("/api/collection");
      if (!res.ok) return false;
      const data = await res.json();
      const rev = data.revision || 0;
      const items = Array.isArray(data.items) ? data.items : [];
      if (!force && rev && rev === remoteRevision) return true;
      if (!items.length) return false;
      remoteRevision = rev;
      const prevId = state.activeId;
      state = normalizeState(data);
      if (prevId && state.items.some((i) => i.id === prevId)) state.activeId = prevId;
      try { localStorage.setItem(STORE_KEY, JSON.stringify(state)); } catch { /* ignore */ }
      const item = active();
      if (item) fillForm(item);
      renderCollection();
      return true;
    } catch {
      return false;
    }
  }

  async function bootstrapCollection() {
    // Prefer Ponto DB when sync is configured (download from pitunnel / server).
    try {
      const remoteMeta = await fetch("/api/remote").then((r) => r.json());
      if (remoteMeta.configured) {
        const res = await fetch("/api/remote/pull", { method: "POST" });
        const data = await res.json();
        if (res.ok && Array.isArray(data.items)) {
          remoteRevision = data.revision || 0;
          state = normalizeState(data);
          bootstrapped = true;
          try { localStorage.setItem(STORE_KEY, JSON.stringify(state)); } catch { /* ignore */ }
          const item = active();
          if (item) fillForm(item);
          renderCollection();
          syncTimer = setInterval(() => {
            if (document.hidden) return;
            pullRemote();
          }, 2000);
          return;
        }
      }
    } catch { /* fall through to local */ }

    let loaded = await pullRemote({ force: true });
    if (!loaded) {
      const legacy = localLegacyState();
      if (legacy) {
        state = legacy;
        bootstrapped = true;
        await pushRemote();
        const item = active();
        if (item) fillForm(item);
        renderCollection();
      } else {
        state = emptyState();
        bootstrapped = true;
        await pushRemote();
        fillForm(state.items[0]);
        renderCollection();
      }
    } else {
      bootstrapped = true;
    }
    syncTimer = setInterval(() => {
      if (document.hidden) return;
      pullRemote();
    }, 2000);
  }

  async function loadSyncForm() {
    const data = await fetch("/api/remote").then((r) => r.json());
    $("sync-url").value = data.url || "https://ponto-thiagohmm1.us1.pitunnel.net";
    $("sync-user").value = data.user || "";
    $("sync-password").value = "";
    $("sync-password").placeholder = data.hasPassword ? "•••••••• (inalterada)" : "senha";
    $("sync-company").value = data.companyId || 1;
    $("sync-enabled").checked = data.enabled !== false;
    $("sync-status").textContent = data.configured
      ? "Sync configurado — curls no banco do Ponto."
      : "Informe usuário e senha do Ponto para baixar/enviar a collection.";
    $("sync-error").classList.add("hidden");
  }

  async function saveSyncConfig() {
    $("sync-error").classList.add("hidden");
    const body = {
      url: $("sync-url").value.trim(),
      user: $("sync-user").value.trim(),
      password: $("sync-password").value || "********",
      companyId: Number($("sync-company").value) || 1,
      enabled: $("sync-enabled").checked,
    };
    const res = await fetch("/api/remote", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    const data = await res.json();
    if (!res.ok) {
      $("sync-error").textContent = data.error || "Falha ao salvar.";
      $("sync-error").classList.remove("hidden");
      return false;
    }
    $("sync-status").textContent = data.configured ? "Salvo. Sync ligado." : "Salvo (sync incompleto).";
    $("sync-password").value = "";
    $("sync-password").placeholder = data.hasPassword ? "•••••••• (inalterada)" : "senha";
    return true;
  }

  async function syncPull() {
    $("sync-error").classList.add("hidden");
    await saveSyncConfig();
    const res = await fetch("/api/remote/pull", { method: "POST" });
    const data = await res.json();
    if (!res.ok) {
      $("sync-error").textContent = data.error || "Falha ao baixar.";
      $("sync-error").classList.remove("hidden");
      return;
    }
    remoteRevision = data.revision || 0;
    state = normalizeState(data);
    bootstrapped = true;
    try { localStorage.setItem(STORE_KEY, JSON.stringify(state)); } catch { /* ignore */ }
    const item = active();
    if (item) fillForm(item);
    renderCollection();
    $("sync-status").textContent = "Baixado do servidor (" + (state.items.length) + " requests).";
  }

  async function syncPush() {
    $("sync-error").classList.add("hidden");
    await saveSyncConfig();
    persistFromForm();
    await pushRemote();
    const res = await fetch("/api/remote/push", { method: "POST" });
    const data = await res.json();
    if (!res.ok) {
      $("sync-error").textContent = data.error || "Falha ao enviar.";
      $("sync-error").classList.remove("hidden");
      return;
    }
    if (data.revision != null) remoteRevision = data.revision;
    $("sync-status").textContent = "Enviado para o banco do Ponto.";
  }

  function active() {
    return state.items.find((i) => i.id === state.activeId) || null;
  }

  function requestTitle(method, url) {
    try {
      const u = new URL(url);
      const path = u.pathname === "/" ? u.host : u.pathname;
      return path || method || "Request";
    } catch {
      return (url || "").replace(/^https?:\/\//, "").slice(0, 48) || method || "Request";
    }
  }

  function methodClass(method) {
    const m = (method || "GET").toLowerCase();
    if (["get", "post", "put", "delete", "patch", "options", "head"].includes(m)) return "m-" + m;
    return "m-get";
  }

  function ensureMethodOption(method) {
    if (![...methodEl.options].some((o) => o.value === method)) {
      const opt = document.createElement("option");
      opt.value = method;
      opt.textContent = method;
      methodEl.appendChild(opt);
    }
    methodEl.value = method;
    paintMethod();
  }

  function paintMethod() {
    methodEl.className = "method " + methodClass(methodEl.value);
  }

  function addHeaderRow(name = "", value = "", enabled = true) {
    const row = document.createElement("div");
    row.className = "header-row";
    row.innerHTML = `
      <input type="checkbox" class="en" ${enabled ? "checked" : ""} title="Ativo" />
      <input type="text" class="name" placeholder="Key" spellcheck="false" />
      <input type="text" class="value" placeholder="Value" spellcheck="false" />
      <button type="button" class="del" title="Remover" aria-label="Remover">×</button>
    `;
    row.querySelector(".name").value = name;
    row.querySelector(".value").value = value;
    row.querySelector(".del").addEventListener("click", () => {
      row.remove();
      persistFromForm();
    });
    row.querySelector(".en").addEventListener("change", persistFromForm);
    row.querySelector(".name").addEventListener("input", persistFromForm);
    row.querySelector(".value").addEventListener("input", persistFromForm);
    headersEl.appendChild(row);
  }

  function readHeaders() {
    return [...headersEl.querySelectorAll(".header-row")].map((row) => ({
      enabled: row.querySelector(".en").checked,
      name: row.querySelector(".name").value.trim(),
      value: row.querySelector(".value").value,
    }));
  }

  function setHeaders(list) {
    headersEl.innerHTML = "";
    if (!list || !list.length) {
      addHeaderRow();
      return;
    }
    list.forEach((h) => addHeaderRow(h.name || "", h.value || "", h.enabled !== false));
  }

  function setContentType(mode) {
    const headers = readHeaders();
    const idx = headers.findIndex((h) => (h.name || "").toLowerCase() === "content-type");
    if (mode === "none") {
      if (idx >= 0) headers.splice(idx, 1);
    } else {
      const ct = CONTENT_TYPES[mode];
      if (ct) {
        if (idx >= 0) {
          headers[idx].value = ct;
          headers[idx].enabled = true;
        } else {
          headers.push({ name: "Content-Type", value: ct, enabled: true });
        }
      }
    }
    if (!headers.length) headers.push({ name: "", value: "", enabled: true });
    setHeaders(headers);
  }

  function addParamRow(name = "", value = "", enabled = true) {
    const row = document.createElement("div");
    row.className = "header-row";
    row.innerHTML = `
      <input type="checkbox" class="en" ${enabled ? "checked" : ""} title="Ativo" />
      <input type="text" class="name" placeholder="Key" spellcheck="false" />
      <input type="text" class="value" placeholder="Value" spellcheck="false" />
      <button type="button" class="del" title="Remover" aria-label="Remover">×</button>
    `;
    row.querySelector(".name").value = name;
    row.querySelector(".value").value = value;
    row.querySelector(".del").addEventListener("click", () => {
      row.remove();
      persistFromForm();
    });
    row.querySelector(".en").addEventListener("change", persistFromForm);
    row.querySelector(".name").addEventListener("input", persistFromForm);
    row.querySelector(".value").addEventListener("input", persistFromForm);
    formParamsEl.appendChild(row);
  }

  function readFormParams() {
    return [...formParamsEl.querySelectorAll(".header-row")].map((row) => ({
      enabled: row.querySelector(".en").checked,
      name: row.querySelector(".name").value.trim(),
      value: row.querySelector(".value").value,
    }));
  }

  function setFormParams(list) {
    formParamsEl.innerHTML = "";
    if (!list || !list.length) {
      addParamRow();
      return;
    }
    list.forEach((p) => addParamRow(p.name || "", p.value || "", p.enabled !== false));
  }

  function serializeFormParams(params) {
    return (params || [])
      .filter((p) => p.enabled !== false && p.name)
      .map((p) => encodeURIComponent(p.name) + "=" + encodeURIComponent(p.value || ""))
      .join("&");
  }

  function syncGutter() {
    const lines = Math.max(1, (bodyEl.value || "").split("\n").length);
    let out = "";
    for (let i = 1; i <= lines; i++) out += i + (i < lines ? "\n" : "");
    bodyGutter.textContent = out;
  }

  function applyBodyMode(mode, { syncHeader = false } = {}) {
    bodyModeEl.value = mode || "none";
    const isNone = mode === "none";
    const isForm = mode === "formUrlEncoded" || mode === "multipartForm";
    bodyNone.classList.toggle("hidden", !isNone);
    bodyEditorWrap.classList.toggle("hidden", isNone || isForm);
    bodyForm.classList.toggle("hidden", !isForm);
    prettifyBtn.classList.toggle("hidden", mode !== "json");
    if (mode === "json") bodyEl.placeholder = '{\n  "key": "value"\n}';
    else if (mode === "xml") bodyEl.placeholder = "<root></root>";
    else bodyEl.placeholder = "";
    if (syncHeader) setContentType(mode);
    syncGutter();
  }

  function currentBodyForSend() {
    const mode = bodyModeEl.value;
    if (mode === "none") return "";
    if (mode === "formUrlEncoded" || mode === "multipartForm") {
      return serializeFormParams(readFormParams());
    }
    return bodyEl.value;
  }

  function fillForm(item) {
    suppressSave = true;
    ensureMethodOption(item.method || "GET");
    urlEl.value = item.url || "";
    bodyEl.value = item.body || "";
    reqNameEl.value = item.name || "";
    setHeaders(item.headers || []);
    setFormParams(item.formParams || [{ name: "", value: "", enabled: true }]);
    applyBodyMode(item.bodyMode || inferBodyMode(item.headers, item.body));
    suppressSave = false;
  }

  function persistFromForm() {
    if (suppressSave) return;
    const item = active();
    if (!item) return;
    item.method = methodEl.value;
    item.url = urlEl.value.trim();
    item.bodyMode = bodyModeEl.value;
    item.formParams = readFormParams();
    item.body = currentBodyForSend();
    // keep raw editor text for non-form modes
    if (item.bodyMode !== "formUrlEncoded" && item.bodyMode !== "multipartForm" && item.bodyMode !== "none") {
      item.body = bodyEl.value;
    }
    item.name = reqNameEl.value.trim() || requestTitle(item.method, item.url);
    item.headers = readHeaders();
    saveState();
    renderCollection();
    syncGutter();
  }

  function selectItem(id) {
    state.activeId = id;
    saveState();
    const item = active();
    if (item) fillForm(item);
    renderCollection();
  }

  function addItem(partial, select = true) {
    const item = defaultRequest(partial);
    if (!partial.name && item.url) item.name = requestTitle(item.method, item.url);
    state.items.unshift(item);
    if (select) state.activeId = item.id;
    saveState();
    if (select) fillForm(item);
    renderCollection();
    return item;
  }

  function removeItem(id, ev) {
    if (ev) ev.stopPropagation();
    state.items = state.items.filter((i) => i.id !== id);
    if (!state.items.length) {
      const blank = defaultRequest({ name: "Untitled Request" });
      state.items = [blank];
      state.activeId = blank.id;
      fillForm(blank);
    } else if (state.activeId === id) {
      state.activeId = state.items[0].id;
      fillForm(state.items[0]);
    }
    saveState();
    renderCollection();
  }

  function renderCollection() {
    const q = (filterEl.value || "").trim().toLowerCase();
    collectionNameEl.textContent = state.name || "Minha collection";
    collectionListEl.innerHTML = "";
    const items = state.items.filter((i) => {
      if (!q) return true;
      return (
        (i.name || "").toLowerCase().includes(q) ||
        (i.url || "").toLowerCase().includes(q) ||
        (i.method || "").toLowerCase().includes(q)
      );
    });
    if (!items.length) {
      collectionListEl.innerHTML = `<p class="col-empty">Nenhuma requisição. Importe um curl ou crie uma nova.</p>`;
      return;
    }
    items.forEach((item) => {
      const btn = document.createElement("div");
      btn.className = "col-item" + (item.id === state.activeId ? " active" : "");
      btn.setAttribute("role", "listitem");
      btn.innerHTML = `
        <span class="m ${methodClass(item.method)}">${escapeHtml(item.method || "GET")}</span>
        <span class="n">${escapeHtml(item.name || "Untitled")}</span>
        <button type="button" class="del" title="Remover" aria-label="Remover">×</button>
      `;
      btn.addEventListener("click", () => selectItem(item.id));
      btn.querySelector(".del").addEventListener("click", (e) => removeItem(item.id, e));
      collectionListEl.appendChild(btn);
    });
  }

  function switchTab(name) {
    document.querySelectorAll(".request-pane .tab").forEach((t) => {
      t.classList.toggle("on", t.dataset.tab === name);
    });
    $("panel-headers").classList.toggle("hidden", name !== "headers");
    $("panel-body").classList.toggle("hidden", name !== "body");
    bodyModeBar.classList.toggle("hidden", name !== "body");
  }

  function switchRespTab(name) {
    document.querySelectorAll(".response-pane .tab").forEach((t) => {
      t.classList.toggle("on", t.dataset.rtab === name);
    });
    respBody.classList.toggle("hidden", name !== "body");
    respHeaders.classList.toggle("hidden", name !== "headers");
  }

  function escapeHtml(s) {
    return String(s)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  function prettyBody(text, contentType) {
    if (!text) return "";
    const ct = (contentType || "").toLowerCase();
    if (ct.includes("json") || /^\s*[\[{]/.test(text)) {
      try {
        return JSON.stringify(JSON.parse(text), null, 2);
      } catch { /* keep raw */ }
    }
    return text;
  }

  function prettify() {
    if (bodyModeEl.value !== "json") return;
    try {
      bodyEl.value = JSON.stringify(JSON.parse(bodyEl.value), null, 2);
      persistFromForm();
    } catch (e) {
      respError.textContent = "JSON inválido para Prettify.";
      respError.classList.remove("hidden");
      setTimeout(() => respError.classList.add("hidden"), 2500);
    }
  }

  async function loadEnv() {
    try {
      const res = await fetch("/api/env");
      env = await res.json();
      if (env.wsl && env.windowsHost) {
        wslMapWrap.classList.remove("hidden");
        wslBanner.classList.remove("hidden");
        wslBanner.textContent =
          `WSL: localhost → ${env.windowsHost} (opção no rodapé).`;
      }
      if (env.keepScreenOnSupported) {
        await refreshKeepScreenOn();
      }
    } catch { /* ignore */ }
  }

  async function refreshKeepScreenOn() {
    try {
      const res = await fetch("/api/keep-screen-on");
      const data = await res.json();
      if (data.supported) {
        keepScreenWrap.classList.remove("hidden");
        keepScreenEl.checked = !!(data.active || data.starting);
        if (data.starting) {
          setTimeout(refreshKeepScreenOn, 1500);
        }
      }
    } catch { /* ignore */ }
  }

  keepScreenEl.addEventListener("change", async () => {
    const desired = keepScreenEl.checked;
    keepScreenEl.disabled = true;
    try {
      const res = await fetch("/api/keep-screen-on", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ enabled: desired }),
      });
      const data = await res.json();
      keepScreenEl.checked = !!data.active;
    } catch {
      keepScreenEl.checked = !desired;
    } finally {
      keepScreenEl.disabled = false;
    }
  });

  async function send() {
    persistFromForm();
    const method = methodEl.value;
    const url = urlEl.value.trim();
    if (!url) {
      urlEl.focus();
      return;
    }
    sendBtn.disabled = true;
    respError.classList.add("hidden");
    respMeta.textContent = "Sending…";
    respBody.textContent = "";
    respHeaders.textContent = "";

    const headers = readHeaders();
    const body = currentBodyForSend();
    const payload = {
      method,
      url,
      headers,
      body,
      followRedirects: followEl.checked,
      timeoutSeconds: 30,
      mapWindowsHost: !!(env.wsl && wslMapEl && wslMapEl.checked),
    };

    try {
      const res = await fetch("/api/send", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });
      const data = await res.json();
      if (data.error) {
        respError.textContent = data.error;
        respError.classList.remove("hidden");
        respMeta.textContent = `${data.elapsedMs ?? 0} ms`;
      } else {
        const st = data.statusText ? ` ${data.statusText}` : "";
        const mapped = data.mappedFrom ? ` · via ${data.url}` : "";
        respMeta.textContent = `${data.status}${st} · ${data.elapsedMs} ms${mapped}`;
        if (data.bodyBase64) {
          respBody.textContent = `[binário base64]\n${data.body || ""}`;
        } else {
          respBody.textContent = prettyBody(data.body || "", data.contentType);
        }
        respHeaders.textContent = (data.headers || [])
          .map((h) => `${h.name}: ${h.value}`)
          .join("\n") || "(sem headers)";
        switchRespTab("body");
      }
    } catch (e) {
      respError.textContent = e.message || String(e);
      respError.classList.remove("hidden");
      respMeta.textContent = "failed";
    } finally {
      sendBtn.disabled = false;
    }
  }

  async function applyCurl() {
    curlError.classList.add("hidden");
    const curl = curlText.value.trim();
    if (!curl) {
      curlError.textContent = "Cole um comando curl.";
      curlError.classList.remove("hidden");
      return;
    }
    try {
      const res = await fetch("/api/import-curl", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ curl }),
      });
      const data = await res.json();
      if (!res.ok || data.error) {
        curlError.textContent = data.error || "Não foi possível importar.";
        curlError.classList.remove("hidden");
        return;
      }
      const mode = inferBodyMode(data.headers || [], data.body || "");
      let body = data.body || "";
      if (mode === "json" && body) {
        try { body = JSON.stringify(JSON.parse(body), null, 2); } catch { /* keep */ }
      }
      addItem({
        name: requestTitle(data.method, data.url),
        method: data.method || "GET",
        url: data.url || "",
        headers: data.headers || [],
        body,
        bodyMode: mode,
      });
      switchTab(data.body || mode !== "none" ? "body" : "headers");
      curlText.value = "";
      curlDialog.close();
    } catch (e) {
      curlError.textContent = e.message || String(e);
      curlError.classList.remove("hidden");
    }
  }

  document.querySelectorAll(".request-pane .tab").forEach((t) => {
    t.addEventListener("click", () => switchTab(t.dataset.tab));
  });
  document.querySelectorAll(".response-pane .tab").forEach((t) => {
    t.addEventListener("click", () => switchRespTab(t.dataset.rtab));
  });

  $("btn-add-header").addEventListener("click", () => {
    addHeaderRow();
    persistFromForm();
  });
  $("btn-add-param").addEventListener("click", () => {
    addParamRow();
    persistFromForm();
  });
  $("btn-send").addEventListener("click", send);
  $("btn-prettify").addEventListener("click", prettify);
  $("btn-new").addEventListener("click", () => {
    addItem({ name: "Untitled Request" });
    switchTab("headers");
  });
  $("btn-curl").addEventListener("click", () => {
    curlError.classList.add("hidden");
    curlDialog.showModal();
    curlText.focus();
  });
  $("btn-curl-apply").addEventListener("click", applyCurl);
  $("btn-sync").addEventListener("click", async () => {
    await loadSyncForm();
    $("sync-dialog").showModal();
  });
  $("btn-sync-save").addEventListener("click", () => saveSyncConfig());
  $("btn-sync-pull").addEventListener("click", () => syncPull());
  $("btn-sync-push").addEventListener("click", () => syncPush());

  bodyModeEl.addEventListener("change", () => {
    const mode = bodyModeEl.value;
    if (mode === "formUrlEncoded" || mode === "multipartForm") {
      if (!formParamsEl.children.length) setFormParams(parseFormBody(bodyEl.value));
    }
    applyBodyMode(mode, { syncHeader: true });
    persistFromForm();
  });

  urlEl.addEventListener("keydown", (e) => {
    if (e.key === "Enter") {
      e.preventDefault();
      send();
    }
  });
  bodyEl.addEventListener("keydown", (e) => {
    if (e.key === "Tab") {
      e.preventDefault();
      const start = bodyEl.selectionStart;
      const end = bodyEl.selectionEnd;
      bodyEl.value = bodyEl.value.slice(0, start) + "  " + bodyEl.value.slice(end);
      bodyEl.selectionStart = bodyEl.selectionEnd = start + 2;
      persistFromForm();
    }
  });
  methodEl.addEventListener("change", () => {
    paintMethod();
    persistFromForm();
  });
  urlEl.addEventListener("input", persistFromForm);
  bodyEl.addEventListener("input", () => {
    syncGutter();
    persistFromForm();
  });
  bodyEl.addEventListener("scroll", () => {
    bodyGutter.scrollTop = bodyEl.scrollTop;
  });
  reqNameEl.addEventListener("input", persistFromForm);
  filterEl.addEventListener("input", renderCollection);

  collectionNameEl.addEventListener("blur", () => {
    const name = collectionNameEl.textContent.trim() || "Minha collection";
    state.name = name;
    collectionNameEl.textContent = name;
    saveState();
  });
  collectionNameEl.addEventListener("keydown", (e) => {
    if (e.key === "Enter") {
      e.preventDefault();
      collectionNameEl.blur();
    }
  });

  paintMethod();
  loadEnv();
  bootstrapCollection().then(() => {
    const current = active() || state.items[0];
    if (current) {
      state.activeId = current.id;
      fillForm(current);
    }
    renderCollection();
  });
})();
