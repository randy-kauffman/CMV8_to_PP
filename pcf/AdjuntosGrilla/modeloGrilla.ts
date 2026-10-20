// Modelo de la grilla: lectura de las filas del dataset, filtro rápido, orden y conteos (Anatomia.md §6 y §7).
// Sin DOM. Lo único de PCF que toca es la interfaz EntityRecord, para poder probarlo con registros falsos.
import { CodigoEstado, enGrupo, esCodigoEstado, GrupoFiltro, GRUPOS, PRESENTACION } from "./estados";

type Registro = ComponentFramework.PropertyHelper.DataSetApi.EntityRecord;

/** Nombre lógico de cada columna que lee el control (resuelto por Dataverse.columnas()). */
export interface Columnas {
    readonly estado: string;
    readonly tipoDocumento: string;
    readonly nombreArchivo: string;
    readonly tamano: string;
    readonly mensaje: string;
    readonly rutaBlob: string;
    readonly fechaSubida: string;
    readonly subidoPor: string;
    readonly contentType: string;
    /** Siempre modifiedon: detecta que el usuario guardó la corrección de una Observada. */
    readonly modificado: string;
    /** Columna primaria de la vista, por si la fila no tiene nombre de archivo (creada con «+ Nuevo»). */
    readonly primaria: string | null;
}

export interface Adjunto {
    readonly id: string;
    readonly nombre: string;
    readonly contentType: string;
    readonly tamano: number | null;
    readonly tipoDocumento: string;
    readonly estado: CodigoEstado | null;
    readonly mensaje: string;
    /** null = la columna no vino en el dataset (no se sabe); "" = vacía. */
    readonly rutaBlob: string | null;
    readonly fechaMs: number | null;
    readonly fechaTexto: string;
    readonly usuario: string;
    readonly modificadoMs: number | null;
}

export type Orden = "fechaDesc" | "fechaAsc" | "estado";

function crudo(r: Registro, columna: string): unknown {
    if (!columna) return undefined;
    try {
        return r.getValue(columna);
    } catch {
        return undefined;
    }
}

function formateado(r: Registro, columna: string): string {
    if (!columna) return "";
    try {
        const v = r.getFormattedValue(columna);
        return typeof v === "string" ? v : "";
    } catch {
        return "";
    }
}

function textoPlano(v: unknown): string {
    if (typeof v === "string") return v;
    if (typeof v === "number") return String(v);
    return "";
}

function numero(v: unknown): number | null {
    if (typeof v === "number" && Number.isFinite(v)) return v;
    if (typeof v === "string" && v.trim() !== "" && Number.isFinite(Number(v))) return Number(v);
    return null;
}

function fecha(v: unknown): number | null {
    if (v instanceof Date) return Number.isFinite(v.getTime()) ? v.getTime() : null;
    if (typeof v === "string" && v) {
        const ms = Date.parse(v);
        return Number.isFinite(ms) ? ms : null;
    }
    return null;
}

/** Choice: el dataset entrega el número (a veces como texto). Cualquier otro valor se trata como desconocido. */
function estado(v: unknown): CodigoEstado | null {
    const n = numero(v);
    return esCodigoEstado(n) ? n : null;
}

/** Nombre visible de una lookup: el formateado, o el name de la referencia si el host no formatea. */
function nombreLookup(r: Registro, columna: string): string {
    const f = formateado(r, columna);
    if (f) return f;
    const v = crudo(r, columna);
    const ref = Array.isArray(v) ? v[0] : v;
    if (typeof ref === "object" && ref !== null && "name" in ref) {
        const n = (ref as { name?: unknown }).name;
        if (typeof n === "string") return n;
    }
    return "";
}

export function leerAdjunto(r: Registro, c: Columnas, columnasPresentes: ReadonlySet<string>): Adjunto {
    const nombre = textoPlano(crudo(r, c.nombreArchivo)) || (c.primaria ? formateado(r, c.primaria) : "");
    return {
        id: r.getRecordId(),
        nombre,
        contentType: textoPlano(crudo(r, c.contentType)),
        tamano: numero(crudo(r, c.tamano)),
        tipoDocumento: nombreLookup(r, c.tipoDocumento),
        estado: estado(crudo(r, c.estado)),
        mensaje: textoPlano(crudo(r, c.mensaje)),
        rutaBlob: columnasPresentes.has(c.rutaBlob) ? textoPlano(crudo(r, c.rutaBlob)) : null,
        fechaMs: fecha(crudo(r, c.fechaSubida)),
        fechaTexto: formateado(r, c.fechaSubida),
        usuario: nombreLookup(r, c.subidoPor),
        modificadoMs: fecha(crudo(r, c.modificado)),
    };
}

export function filtrar(items: readonly Adjunto[], grupo: GrupoFiltro): Adjunto[] {
    return items.filter((a) => enGrupo(a.estado, grupo));
}

const porFechaDesc = (a: Adjunto, b: Adjunto): number => (b.fechaMs ?? -Infinity) - (a.fechaMs ?? -Infinity);

/** Orden estable. Sin fecha va al final en los dos sentidos; sin estado conocido, al final del orden por estado. */
export function ordenar(items: readonly Adjunto[], orden: Orden): Adjunto[] {
    const copia = items.slice();
    if (orden === "fechaDesc") copia.sort(porFechaDesc);
    else if (orden === "fechaAsc") {
        copia.sort((a, b) => (a.fechaMs ?? Infinity) - (b.fechaMs ?? Infinity));
    } else {
        copia.sort((a, b) => {
            const oa = a.estado === null ? 99 : PRESENTACION[a.estado].orden;
            const ob = b.estado === null ? 99 : PRESENTACION[b.estado].orden;
            return oa !== ob ? oa - ob : porFechaDesc(a, b);
        });
    }
    return copia;
}

export function contarGrupos(items: readonly Adjunto[]): Readonly<Record<GrupoFiltro, number>> {
    const c = {} as Record<GrupoFiltro, number>;
    for (const g of GRUPOS) c[g] = items.filter((a) => enGrupo(a.estado, g)).length;
    return c;
}
