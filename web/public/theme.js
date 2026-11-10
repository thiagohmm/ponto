// tema antes da primeira pintura: escolha salva, senão escuro
(function () {
  try {
    var t = localStorage.getItem("ponto.tema");
    if (!t) t = "escuro";
    if (t === "sistema")
      t = window.matchMedia("(prefers-color-scheme: dark)").matches ? "escuro" : "claro";
    document.documentElement.dataset.tema = t;
  } catch (e) { }
})();
