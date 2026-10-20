// Mapeo de tokens.json a variables CSS --fu-* sobre la raíz del control.
// Orden de precedencia: valor de la variante (por modo y densidad) → propiedad del maker → derivados del maker.
// Los derivados solo se recalculan cuando el maker cambia el token base; si no, se respeta el valor diseñado
// (que ya tiene el contraste verificado, ver meta.contrast de tokens.json).
import { Densidad, ModoColor, NombreMaker, TOKENS, ValorToken, Variante } from "./tokens.generado";

export type { Densidad, ModoColor, NombreMaker, Variante } from "./tokens.generado";

/** Valores que el maker dejó en las propiedades de entrada. Ausente o vacío = usar el de la variante. */
export type ValoresMaker = Partial<Record<NombreMaker, string | number>>;

export interface OpcionesTema {
    readonly variante: Variante;
    readonly modo: ModoColor;
    readonly densidad: Densidad;
    readonly maker: ValoresMaker;
    /** Validador de colores y familias; en el navegador es CSS.supports. Inyectado para poder probar sin DOM. */
    readonly esValorCss?: (propiedad: string, valor: string) => boolean;
}

function resolver(valor: ValorToken, modo: ModoColor, densidad: Densidad): string {
    if (typeof valor === "string") return valor;
    if ("light" in valor) return modo === "light" ? valor.light : valor.dark;
    return densidad === "compact" ? valor.compact : valor.comfortable;
}

/** Luminancia relativa WCAG de un color #RGB o #RRGGBB. null si no es hexadecimal (no se puede medir). */
export function luminancia(color: string): number | null {
    const m = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(color.trim());
    if (!m) return null;
    let hex = m[1];
    if (hex.length === 3) hex = hex.split("").map((c) => c + c).join("");
    const canales = [0, 2, 4].map((i) => {
        const u = parseInt(hex.slice(i, i + 2), 16) / 255;
        return u <= 0.03928 ? u / 12.92 : Math.pow((u + 0.055) / 1.055, 2.4);
    });
    return 0.2126 * canales[0] + 0.7152 * canales[1] + 0.0722 * canales[2];
}

/** Razón de contraste WCAG. null si alguno de los dos colores no se puede medir. */
export function contraste(a: string, b: string): number | null {
    const x = luminancia(a);
    const y = luminancia(b);
    if (x === null || y === null) return null;
    return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
}

const mezcla = (color: string, porcentaje: number, con: string): string =>
    `color-mix(in oklch, ${color} ${porcentaje}%, ${con})`;

function textoMaker(maker: ValoresMaker, nombre: NombreMaker): string | undefined {
    const v = maker[nombre];
    if (v === undefined || v === null) return undefined;
    const s = String(v).trim();
    return s.length > 0 ? s : undefined;
}

function enteroMaker(maker: ValoresMaker, nombre: NombreMaker, min: number, max: number): number | undefined {
    const v = maker[nombre];
    const n = typeof v === "number" ? v : typeof v === "string" && v.trim() !== "" ? Number(v) : NaN;
    if (!Number.isFinite(n)) return undefined;
    return Math.min(max, Math.max(min, Math.round(n)));
}

const px = (n: number): string => `${Math.round(n)}px`;

// Tokens de color con "maker" cuyo valor del maker se copia tal cual; sus derivados se aplican aparte.
const COLOR_DIRECTO: readonly [NombreMaker, string][] = [
    ["PrimaryColor", "--fu-color-primary"],
    ["PrimaryHoverColor", "--fu-color-primary-hover"],
    ["OnPrimaryColor", "--fu-color-on-primary"],
    ["BackgroundColor", "--fu-color-bg"],
    ["SurfaceColor", "--fu-color-surface"],
    ["BorderColor", "--fu-color-border"],
    ["TextColor", "--fu-color-text"],
    ["TextSecondaryColor", "--fu-color-text-secondary"],
];

// Colores de semántica: el maker define fg; bg y border se derivan (nota de tokens.json).
const SEMANTICA: readonly [NombreMaker, string][] = [
    ["SuccessColor", "success"],
    ["WarningColor", "warning"],
    ["InfoColor", "info"],
    ["DangerColor", "danger"],
    ["NeutralColor", "neutral"],
];

/**
 * Calcula todas las variables --fu-* para una variante, modo y densidad, aplicando lo que definió el maker.
 * Pura: devuelve un diccionario; aplicarlo al DOM es trabajo de la vista.
 */
export function calcularVariables(o: OpcionesTema): Record<string, string> {
    const valido: (propiedad: string, valor: string) => boolean = o.esValorCss ?? ((): boolean => true);
    const v: Record<string, string> = {};
    for (const t of TOKENS) v[t.css] = resolver(t.valores[o.variante], o.modo, o.densidad);

    const color = (nombre: NombreMaker): string | undefined => {
        const c = textoMaker(o.maker, nombre);
        // Un color inválido se ignora en vez de romper la paleta entera.
        return c !== undefined && valido("color", c) ? c : undefined;
    };

    for (const [nombre, css] of COLOR_DIRECTO) {
        const c = color(nombre);
        if (c !== undefined) v[css] = c;
    }

    const superficie = v["--fu-color-surface"];
    const primario = color("PrimaryColor");
    if (primario !== undefined) {
        if (color("PrimaryHoverColor") === undefined) v["--fu-color-primary-hover"] = mezcla(primario, 85, "black");
        v["--fu-color-primary-pressed"] = mezcla(primario, 60, "black");
        if (color("OnPrimaryColor") === undefined) {
            // Texto sobre primario: blanco si llega a 4.5:1; si no, negro. Sin medir (no hex), se queda el diseñado.
            const r = contraste(primario, "#FFFFFF");
            if (r !== null) v["--fu-color-on-primary"] = r >= 4.5 ? "#FFFFFF" : "#000000";
        }
        if (o.modo === "light") {
            const r = contraste(primario, superficie);
            v["--fu-color-primary-text"] = r === null || r >= 4.5 ? primario : mezcla(primario, 65, "black");
        } else {
            v["--fu-color-primary-text"] = mezcla(primario, 65, "white");
        }
        if (o.variante === "moderno") v["--fu-color-focus"] = v["--fu-color-primary-text"];
    }
    if (primario !== undefined || color("SurfaceColor") !== undefined) {
        const p = v["--fu-color-primary"];
        v["--fu-color-primary-subtle"] = mezcla(p, o.modo === "light" ? 8 : 22, superficie);
    }

    for (const [nombre, sem] of SEMANTICA) {
        const fg = color(nombre);
        if (fg === undefined) continue;
        v[`--fu-color-status-${sem}-fg`] = fg;
        v[`--fu-color-status-${sem}-bg`] = mezcla(fg, 8, superficie);
        v[`--fu-color-status-${sem}-border`] = mezcla(fg, 35, superficie);
    }

    const familia = textoMaker(o.maker, "FontFamily");
    if (familia !== undefined && valido("font-family", familia)) {
        v["--fu-font-family"] = familia;
        // Institucional usa serif en títulos a propósito; las otras variantes titulan con la misma familia.
        if (o.variante !== "institucional") v["--fu-font-family-heading"] = familia;
    }

    // Escala tipográfica (descripción de font-scale): caption = base/scale; subtitle = base×scale; title = base×scale².
    const base = enteroMaker(o.maker, "FontSize", 10, 24);
    const escalaTexto = textoMaker(o.maker, "FontScale");
    const escalaNum = escalaTexto !== undefined ? Number(escalaTexto.replace(",", ".")) : NaN;
    const escala = Number.isFinite(escalaNum) && escalaNum >= 1 && escalaNum <= 2 ? escalaNum : undefined;
    if (base !== undefined || escala !== undefined) {
        const b = base ?? parseFloat(v["--fu-font-size-base"]);
        const s = escala ?? parseFloat(v["--fu-font-scale"]);
        v["--fu-font-size-base"] = px(b);
        v["--fu-font-scale"] = String(s);
        v["--fu-font-size-body"] = px(b);
        v["--fu-font-size-caption"] = px(b / s);
        v["--fu-font-size-subtitle"] = px(b * s);
        v["--fu-font-size-title"] = px(b * s * s);
        // Alturas de línea en la rejilla de 4 px, ~1.4 del tamaño.
        const linea = (n: number): string => px(Math.ceil((n * 1.4) / 4) * 4);
        v["--fu-font-line-caption"] = linea(b / s);
        v["--fu-font-line-body"] = linea(b);
        v["--fu-font-line-subtitle"] = linea(b * s);
        v["--fu-font-line-title"] = linea(b * s * s);
    }

    const radio = enteroMaker(o.maker, "BorderRadius", 0, 32);
    if (radio !== undefined) {
        v["--fu-radius-md"] = px(radio);
        v["--fu-radius-sm"] = px(radio / 2);
        v["--fu-radius-lg"] = px(o.variante === "institucional" ? radio : radio * 2);
        // Moderno mantiene chips en píldora (999px); las demás siguen el radio del maker.
        if (o.variante !== "moderno") v["--fu-radius-chip"] = px(radio);
    }

    const borde = enteroMaker(o.maker, "BorderWidth", 0, 4);
    if (borde !== undefined) v["--fu-border-width"] = px(borde);

    return v;
}
