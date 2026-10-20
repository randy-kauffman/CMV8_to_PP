// Formato de tamaños, velocidades, porcentajes y tiempos según las notas de textos.es.json (unit.*, upload.eta.*).
// Funciones puras: reciben el traductor y no tocan DOM ni PCF, para poder probarlas sin harness.
import { Traductor } from "./textos";

const KIB = 1024;
const MIB = 1024 * 1024;
const GIB = 1024 * 1024 * 1024;

// Formateadores en español: la interfaz está escrita en español neutro (textos.es.json, meta.tone).
const NF1 = new Intl.NumberFormat("es", { minimumFractionDigits: 1, maximumFractionDigits: 1 });
const NF0 = new Intl.NumberFormat("es", { maximumFractionDigits: 0 });

/** unit.kb: base 1024, 0 decimales (mínimo 1 KB). unit.mb: 1 decimal bajo 100 MB, 0 encima. unit.gb: 1 decimal. */
export function formatearTamano(bytes: number, t: Traductor): string {
    if (!Number.isFinite(bytes) || bytes < 0) return "—";
    if (bytes < MIB) return t("unit.kb", { n: NF0.format(Math.max(1, Math.round(bytes / KIB))) });
    if (bytes < GIB) {
        const mb = bytes / MIB;
        return t("unit.mb", { n: mb < 100 ? NF1.format(mb) : NF0.format(mb) });
    }
    return t("unit.gb", { n: NF1.format(bytes / GIB) });
}

/** unit.mbps: 1 decimal. */
export function formatearMbps(mbps: number, t: Traductor): string {
    return t("unit.mbps", { n: NF1.format(Number.isFinite(mbps) ? mbps : 0) });
}

/** unit.percent: entero truncado y espacio fino (U+202F) antes de %, como pide la nota del texto. */
export function formatearPorcentaje(porcentaje: number, t: Traductor): string {
    const n = Math.max(0, Math.min(100, Math.floor(porcentaje)));
    return t("unit.percent", { n }).replace(" %", " %");
}

/** Tiempo restante: «calculando» los primeros 3 s (upload.eta.calculating), luego segundos o minutos. */
export function formatearEta(segundosRestantes: number, segundosTranscurridos: number, t: Traductor): string {
    if (segundosTranscurridos < 3 || !Number.isFinite(segundosRestantes) || segundosRestantes < 0) {
        return t("upload.eta.calculating");
    }
    if (segundosRestantes < 60) return t("upload.eta.seconds", { n: Math.max(1, Math.ceil(segundosRestantes)) });
    return t("upload.eta.minutes", { n: Math.ceil(segundosRestantes / 60) });
}

/** Megabits por segundo a partir de bytes y milisegundos. 0 si no hay tiempo transcurrido. */
export function calcularMbps(bytes: number, milisegundos: number): number {
    if (milisegundos <= 0 || bytes <= 0) return 0;
    return (bytes * 8) / 1_000_000 / (milisegundos / 1000);
}

/**
 * Separa nombre base y extensión para truncar solo la base (Anatomia.md §3: «el sufijo de extensión no se corta»).
 * Un nombre que empieza con punto (.env) no tiene extensión.
 */
export function dividirNombre(nombre: string): { base: string; extension: string } {
    const i = nombre.lastIndexOf(".");
    return i > 0 ? { base: nombre.slice(0, i), extension: nombre.slice(i) } : { base: nombre, extension: "" };
}

export type ClaseArchivo = "pdf" | "imagen" | "video" | "documento";

/** Clase visual del archivo para elegir el ícono de tipo (DocumentPdf, Image, Video o Document). */
export function clasificarArchivo(nombre: string, contentType: string): ClaseArchivo {
    const mime = contentType.toLowerCase();
    const ext = dividirNombre(nombre).extension.toLowerCase();
    if (mime === "application/pdf" || ext === ".pdf") return "pdf";
    if (mime.startsWith("image/")) return "imagen";
    if (mime.startsWith("video/")) return "video";
    return "documento";
}

/**
 * Convierte la lista accept del maker en una etiqueta legible para {types}: «.pdf» → PDF, «application/pdf» → PDF,
 * «video/*» → video. Sin duplicados. Vacía si se acepta todo.
 */
export function etiquetarTipos(aceptados: readonly string[]): string {
    const etiquetas: string[] = [];
    for (const a of aceptados) {
        let e: string;
        if (a.startsWith(".")) e = a.slice(1).toUpperCase();
        else if (a.endsWith("/*")) e = a.slice(0, -2);
        else {
            const barra = a.indexOf("/");
            e = (barra >= 0 ? a.slice(barra + 1) : a).toUpperCase();
        }
        if (e && !etiquetas.includes(e)) etiquetas.push(e);
    }
    return etiquetas.join(", ");
}

/**
 * Huella FNV-1a de 32 bits más el largo. Sirve para reconocer un lote ya consumido sin guardar el lote: la huella no
 * permite reconstruir la SAS ni el token (no es una credencial), y cada respuesta del broker trae firmas nuevas.
 */
export function huellaTexto(texto: string): string {
    let h = 0x811c9dc5;
    for (let i = 0; i < texto.length; i++) {
        h ^= texto.charCodeAt(i);
        h = Math.imul(h, 0x01000193) >>> 0;
    }
    return `${texto.length.toString(36)}.${h.toString(36)}`;
}
