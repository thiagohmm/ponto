/** Marca do Ponto: relógio estilizado (mesmo desenho do ícone PWA). */
export function BrandMark({ className = "brand-mark", title = "Ponto" }: { className?: string; title?: string }) {
  return (
    <svg className={className} viewBox="0 0 40 40" role="img" aria-label={title}>
      <title>{title}</title>
      <rect width="40" height="40" rx="11" fill="#1d1d1f" />
      <circle cx="20" cy="20" r="12.5" fill="none" stroke="#e8e8ed" strokeWidth="2.25" />
      <g stroke="#e8e8ed" strokeLinecap="round">
        <path d="M20 9.2v2.2M20 28.6v2.2M9.2 20h2.2M28.6 20h2.2" strokeWidth="1.8" />
        <path d="M12.4 12.4l1.5 1.5M26.1 26.1l1.5 1.5M26.1 12.4l-1.5 1.5M13.9 26.1l-1.5 1.5" strokeWidth="1.4" opacity=".55" />
      </g>
      <line x1="20" y1="20" x2="20" y2="11.5" stroke="#e8e8ed" strokeWidth="2.2" strokeLinecap="round" />
      <line x1="20" y1="20" x2="26.2" y2="23.6" stroke="#ff3b30" strokeWidth="2.2" strokeLinecap="round" />
      <circle cx="20" cy="20" r="2.1" fill="#ff3b30" />
    </svg>
  );
}
