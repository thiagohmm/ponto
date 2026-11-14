import { useState, type CSSProperties } from "react";

/**
 * Gráfico de barras em HTML puro.
 *
 * O Recharts custava 380 kB (112 kB gzip) no primeiro carregamento para
 * desenhar quatro gráficos de barras simples. Aqui são divs com altura em
 * porcentagem: mesmo resultado visual, ~4 kB, sem dependência nenhuma.
 *
 * Cobre o que o app usa: eixo Y com ticks "redondos" (minutos ou contagem),
 * labels no eixo X (todos ou um a cada n), linha tracejada de meta, linha de
 * zero para valores negativos, mínimo de 3 px para barra zerada continuar
 * clicável, tooltip no hover e clique por coluna.
 */

export type Bar = {
  /** valor: minutos, contagem, o que o `format` souber ler */
  value: number;
  /** rótulo do eixo X */
  label: string;
  /** cor da barra */
  color: string;
  /** texto do tooltip; por padrão "rotulo · valor formatado" */
  tip?: string;
};

type Props = {
  bars: Bar[];
  /** altura da área das barras, em px (os rótulos do eixo X ficam abaixo) */
  height?: number;
  /** formata os ticks do eixo Y e o valor do tooltip */
  format?: (v: number) => string;
  /** mostra o rótulo do eixo X a cada n colunas (1 = todas) */
  every?: number;
  /** arredonda o passo do eixo Y para múltiplos disto (60 = horas cheias) */
  snap?: number;
  /** linha tracejada horizontal (plano, meta) */
  mark?: number;
  /** destaca a linha do zero: banco de horas, saldo positivo e negativo */
  zero?: boolean;
  /** clique numa coluna (o índice é o da lista `bars`) */
  onPick?: (index: number) => void;
  /** largura reservada para os rótulos do eixo Y */
  yWidth?: number;
  /** o que o gráfico mostra, para leitores de tela */
  label: string;
};

/** Passo "redondo" (1, 2, 2.5, 5, 10 × 10^n) para um tick de eixo. */
function niceStep(raw: number): number {
  if (!(raw > 0)) return 1;
  const mag = Math.pow(10, Math.floor(Math.log10(raw)));
  const n = raw / mag;
  const step = n <= 1 ? 1 : n <= 2 ? 2 : n <= 2.5 ? 2.5 : n <= 5 ? 5 : 10;
  return step * mag;
}

/** Domínio e ticks que cobrem os valores e a marca, com no máximo 6 linhas. */
function axis(lo: number, hi: number, snap: number): { min: number; max: number; ticks: number[] } {
  const unit = snap > 0 ? snap : 1;
  let step = niceStep((hi - lo) / 3 / unit) * unit;
  for (let guard = 0; (hi - lo) / step > 4 && guard < 8; guard++) step = niceStep(step * 1.5) * unit;
  const min = Math.floor(lo / step) * step;
  const max = Math.ceil(hi / step) * step;
  const ticks: number[] = [];
  for (let v = min; v <= max + step / 2; v += step) ticks.push(Math.round(v * 1000) / 1000);
  return { min, max: max === min ? min + step : max, ticks };
}

export function Bars({
  bars,
  height = 200,
  format = String,
  every = 1,
  snap = 1,
  mark,
  zero = false,
  onPick,
  yWidth = 36,
  label,
}: Props) {
  const [hover, setHover] = useState(-1);
  if (bars.length === 0) return null;

  const values = bars.map((b) => b.value);
  const lo = Math.min(0, ...values, mark ?? 0);
  const hi = Math.max(0, ...values, mark ?? 0);
  const { min, max, ticks } = axis(lo, hi, snap);
  const span = max - min;

  /** 0% no topo do gráfico, 100% na base. */
  const top = (v: number) => ((max - v) / span) * 100;
  const base = top(0);
  const gap = bars.length > 24 ? 1 : 2;
  const hovered = hover >= 0 && hover < bars.length ? bars[hover] : null;

  return (
    <div className="bars" role="group" aria-label={label} style={{ "--bars-h": height + "px" } as CSSProperties}>
      <div className="bars-y" style={{ width: yWidth }}>
        {ticks.map((t) => (
          <span key={t} className="bars-tick" style={{ top: top(t) + "%" }}>{format(t)}</span>
        ))}
      </div>
      <div className="bars-plot">
        {ticks.map((t) => (
          <span key={t} className="bars-line" style={{ top: top(t) + "%" }} />
        ))}
        {mark !== undefined && <span className="bars-mark" style={{ top: top(mark) + "%" }} />}
        {zero && <span className="bars-zero" style={{ top: base + "%" }} />}
        <div className="bars-cols">
          {bars.map((b, i) => {
            const up = top(Math.max(b.value, 0));
            const down = top(Math.min(b.value, 0));
            const px = Math.max(3, down - up);
            const style: CSSProperties = b.value < 0
              ? { background: b.color, top: up + "%", height: px + "%" }
              : { background: b.color, top: down - px + "%", height: px + "%" };
            return (
              <button key={i} type="button" className={onPick ? "bars-col pick" : "bars-col"}
                style={{ "--bars-gap": gap + "px" } as CSSProperties}
                tabIndex={onPick ? 0 : -1}
                onClick={onPick ? () => onPick(i) : undefined}
                onMouseEnter={() => setHover(i)}
                onMouseLeave={() => setHover(-1)}
                onFocus={() => setHover(i)}
                onBlur={() => setHover(-1)}
                aria-label={b.label + ": " + (b.tip ?? format(b.value))}>
                <span className="bars-bar" style={style} />
                {i % every === 0 && <span className="bars-x">{b.label}</span>}
              </button>
            );
          })}
        </div>
        {hovered && (
          <span className="bars-tip" style={{ left: ((hover + 0.5) / bars.length) * 100 + "%" }}>
            {hovered.tip ?? hovered.label + " · " + format(hovered.value)}
          </span>
        )}
      </div>
    </div>
  );
}
