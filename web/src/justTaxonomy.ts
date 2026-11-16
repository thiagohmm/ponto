/** Soft justification taxonomy — sourced from GET /api/just-categories (JustCategory.java). */

export type JustCategoryCode =
  | "atestado"
  | "home_office"
  | "cliente"
  | "reuniao"
  | "pessoal"
  | "deslocamento"
  | "outro"
  | (string & {});

export type JustCategory = {
  code: JustCategoryCode;
  label: string;
  synonyms: string[];
  phrases: string[];
};

/** Filled by {@link loadJustCategories}; same array reference for all consumers. */
export const JUST_CATEGORIES: JustCategory[] = [];

let loadPromise: Promise<JustCategory[]> | null = null;

export function justCategories(): JustCategory[] {
  return JUST_CATEGORIES;
}

export async function loadJustCategories(): Promise<JustCategory[]> {
  if (JUST_CATEGORIES.length > 0) return JUST_CATEGORIES;
  if (!loadPromise) {
    loadPromise = fetch("/api/just-categories", { credentials: "include" })
      .then(async (r) => {
        if (!r.ok) return JUST_CATEGORIES;
        const body = (await r.json()) as { categories?: JustCategory[] };
        JUST_CATEGORIES.length = 0;
        for (const c of body.categories ?? []) JUST_CATEGORIES.push(c);
        return JUST_CATEGORIES;
      })
      .catch(() => JUST_CATEGORIES)
      .finally(() => { loadPromise = null; });
  }
  return loadPromise;
}

export function justCategory(code: string | null | undefined): JustCategory | undefined {
  return JUST_CATEGORIES.find((c) => c.code === code);
}

/** True when the justification text matches any synonym of the category. */
export function matchesJustCategory(just: string, code: JustCategoryCode): boolean {
  const cat = justCategory(code);
  if (!cat) return false;
  const n = just.toLowerCase();
  return cat.synonyms.some((s) => s && n.includes(s.toLowerCase()));
}
