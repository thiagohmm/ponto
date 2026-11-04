package ponto;

/**
 * Punto de entrada.
 *
 *   ponto                 -> o menu (essa é a forma normal de usar)
 *   ponto --db=path     -> outro arquivo de banco de dados
 *   ponto --help        -> este texto
 *   ponto --version
 *
 * A UI interativa é deliberadamente minimal: uma tecla por ação, o valor
 * atual é sempre o default, e '-' limpa um campo.
 */
public final class Main {

    private static final String HELP = """
        ponto %s - controle de ponto eletrônico no terminal (SQLite)

        uso
          ponto                     abre o menu
          ponto --db=<arquivo>    usa outro banco de dados
          ponto --version | --help

        o menu
          1 novo      dia, entrada, saída, justificativa  (avisa em fim de semana / dia de folga)
          2 editar    qualquer dia / hora / justificativa de qualquer registro
          3 excluir   um dia inteiro ou ids ("3", "3 a", "3 01/10", "3 5 6 7")
          4 listar    registros mais recentes, pageando, total embaixo
          d data      todos os registros de um dia, mês ou ano ("d 2026", "d out")
          5 soma      totais por dia para um período
          6 buscar    pesquisa as justificativas ("6 doente")
          7 pdf       relatório como PDF
          8 xls       relatório como Excel real .xls
          9 csv       relatório como CSV (BOM + ';')
          c config    nome do usuário, seus próprios dias de folga, caminho do banco de dados, vacuum
          x limpar    apaga a tela
          0 sair

        entrada
          datas    08/10 · 08/10/2026 · 2026-10-08 · 5out · hoje · y · -3
          por data  2026 (ano inteiro) · 10/2026 · out · out 2026 · 01/10/2026
          horas    07:30 · 07h30 · 0730 · 8 · agora
          Enter    mantiene o valor mostrado entre colchetes
          -        limpa o campo

        dias de folga
          Brasil e São Paulo (estado e cidade), do jollyday, sem internet needed
          uma linha amarela no 1 e 2, nunca uma barreira: work naqueles dias é ainda registrado
          seus próprios dias de folga: c config, "27/10/2026 31/12" (espaço, vírgula ou ;)

        ambiente
          PONTO_DB          caminho do banco de dados (default ~/.local/share/ponto/ponto.db)
          PONTO_JAVA        JDK 25+ usado pelo launcher
          PONTO_CSV_SEP     ',' se você prefere vírgulas over ponto e vírgulas
        """.formatted(Ver.N);

    public static void main(String[] args) {
        if (flag(args, "-h", "--help")) { System.out.println(HELP); return; }
        if (flag(args, "-v", "--version")) { System.out.println(Ver.NAME + " " + Ver.N); return; }
        String db = value(args, "--db=");
        try {
            try (Db d = Db.open(db)) { new App(d).run(); }
            System.out.flush();
        } catch (Problema e) {
            System.out.flush();
            System.err.println("x   " + e.getMessage());
            System.exit(1);
        } catch (Eof ignored) {
            System.out.flush();
            System.exit(0);
        }
    }

    private static boolean flag(String[] args, String... names) {
        for (String a : args) for (String n : names) if (a.equals(n)) return true;
        return false;
    }

    private static String value(String[] args, String prefix) {
        for (String a : args) if (a.startsWith(prefix)) return a.substring(prefix.length());
        if (args.length > 0 && !args[0].startsWith("-")) return args[0];
        return null;
    }
}
