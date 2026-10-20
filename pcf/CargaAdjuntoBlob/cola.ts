// Modelo de la cola y máquina de estados por archivo (Anatomia.md §3, «Estados de subida»).
// No conoce PCF, DOM ni red: el motor (motor.ts) decide cuándo transicionar y la vista solo lee.
import { ClaveTexto } from "./textos";
import { calcularMbps } from "./formato";

/**
 * Estados por archivo. Coinciden con la tabla de Anatomia.md §3 salvo `esperando`, que es interno: el archivo ya
 * tiene SAS pero espera turno por el paralelismo de archivos. Se muestra como «En cola» (la anatomía dice «máx. 2 en
 * Subiendo; el resto queda En cola») pero, a diferencia de `enCola`, ya tiene fila en Dataverse y se puede cancelar.
 */
export type EstadoArchivo =
    | "enCola"
    | "solicitando"
    | "esperando"
    | "subiendo"
    | "verificando"
    | "subido"
    | "error"
    | "cancelado"
    | "rechazado";

export type MotivoRechazo =
    | "validation.type"
    | "validation.size"
    | "validation.duplicate"
    | "validation.batchFull"
    | "validation.empty";

/** Sufijo de las claves error.* de textos.es.json. */
export type CodigoError = "permissionDenied" | "passExpired" | "network" | "fileChanged" | "generic";

export type AccionFila = "quitar" | "cancelar" | "reintentar";

export interface Muestra {
    readonly t: number;
    readonly bytes: number;
}

export interface ArchivoEnCola {
    /** Identificador local, único y creciente. Es el `indice` que la app ve en `pendientes`. */
    readonly indice: number;
    readonly archivo: File;
    readonly nombre: string;
    readonly tamano: number;
    readonly contentType: string;
    estado: EstadoArchivo;
    motivoRechazo?: MotivoRechazo;
    codigoError?: CodigoError;
    /** Texto del broker para un rechazo de lote. Va a `resultados.mensajeError`, no a la interfaz. */
    mensajeServidor?: string;
    /** Id de la fila en Dataverse, una vez que el lote lo trajo. Se reutiliza al reintentar (lote de uno). */
    id?: string;
    rutaBlob?: string;
    /** Tamaño de bloque del lote (no es secreto): lo usa la barra de progreso de la variante moderno. */
    bloqueBytes?: number;
    bytesConfirmados: number;
    inicioMs?: number;
    finMs?: number;
    /** Muestras (instante, bytes) de los últimos 5 s para la velocidad (nota de unit.mbps: media móvil de 5 s). */
    muestras: Muestra[];
    velocidadMbps: number;
    /** Último cuarto anunciado (0–3) para no repetir el anuncio de progreso cada 25 %. */
    cuartoAnunciado: number;
}

// Transiciones permitidas. Cualquier otra es un error de programación y se ignora (no rompe la cola).
const TRANSICIONES: Readonly<Record<EstadoArchivo, readonly EstadoArchivo[]>> = {
    enCola: ["solicitando"],
    solicitando: ["esperando", "error", "cancelado"],
    esperando: ["subiendo", "error", "cancelado"],
    subiendo: ["verificando", "error", "cancelado"],
    verificando: ["subido", "error"],
    subido: [],
    error: ["solicitando"],
    cancelado: ["solicitando"],
    rechazado: [],
};

export function puedeTransicionar(de: EstadoArchivo, a: EstadoArchivo): boolean {
    return TRANSICIONES[de].includes(a);
}

/** Estados en los que el archivo ocupa el circuito de subida (cuentan para «Subiendo N de M»). */
export const ESTADOS_ACTIVOS: readonly EstadoArchivo[] = ["solicitando", "esperando", "subiendo", "verificando"];

/** Acciones por estado (columna «Acciones» de Anatomia.md §3). permissionDenied no ofrece Reintentar. */
export function accionesDe(a: ArchivoEnCola): AccionFila[] {
    switch (a.estado) {
        case "enCola":
        case "subido":
        case "rechazado":
            return ["quitar"];
        case "solicitando":
        case "esperando":
        case "subiendo":
            return ["cancelar"];
        case "verificando":
            return [];
        case "error":
            return a.codigoError === "permissionDenied" ? ["quitar"] : ["reintentar", "quitar"];
        case "cancelado":
            return ["reintentar", "quitar"];
    }
}

// ------------------------------------------------------------------ validación previa

export interface ReglasValidacion {
    /** Entradas de accept normalizadas (minúsculas, sin espacios). Vacío = acepta todo. */
    readonly aceptados: readonly string[];
    readonly tamanoMaximoBytes: number;
    readonly loteMaximo: number;
}

export interface Candidato {
    readonly nombre: string;
    readonly tamano: number;
    readonly contentType: string;
}

/** Normaliza la lista del maker con sintaxis de accept: «.PDF, video/*» → [".pdf", "video/*"]. */
export function parsearAceptados(texto: string | null | undefined): string[] {
    return (texto ?? "")
        .split(",")
        .map((s) => s.trim().toLowerCase())
        .filter((s) => s.length > 0);
}

/** Misma semántica que el atributo accept: extensión, tipo MIME exacto o comodín tipo/*. */
export function tipoAceptado(nombre: string, contentType: string, aceptados: readonly string[]): boolean {
    if (aceptados.length === 0) return true;
    const n = nombre.toLowerCase();
    const mime = contentType.toLowerCase();
    return aceptados.some((a) => {
        if (a.startsWith(".")) return n.endsWith(a);
        if (a.endsWith("/*")) return mime.startsWith(a.slice(0, -1));
        return mime === a;
    });
}

/**
 * Valida un archivo antes de agregarlo, en el orden en que el usuario puede corregirlo: lote lleno, tipo, tamaño,
 * vacío, duplicado. Duplicado = mismo nombre y tamaño que otro archivo vigente (no rechazado): además de evitar subir
 * dos veces lo mismo, garantiza que nombre+tamaño identifique a un solo archivo al emparejar el lote (lote.ts).
 */
export function validarCandidato(
    c: Candidato,
    vigentes: readonly Candidato[],
    reglas: ReglasValidacion
): MotivoRechazo | null {
    if (vigentes.length >= reglas.loteMaximo) return "validation.batchFull";
    if (!tipoAceptado(c.nombre, c.contentType, reglas.aceptados)) return "validation.type";
    if (c.tamano > reglas.tamanoMaximoBytes) return "validation.size";
    if (c.tamano === 0) return "validation.empty";
    if (vigentes.some((v) => v.nombre === c.nombre && v.tamano === c.tamano)) return "validation.duplicate";
    return null;
}

/** El navegador puede no conocer el MIME (por ejemplo .msg); el broker y Storage necesitan uno. */
export function contentTypeDe(archivo: File): string {
    return archivo.type && archivo.type.length > 0 ? archivo.type : "application/octet-stream";
}

// ------------------------------------------------------------------ cola

export class Cola {
    private readonly items: ArchivoEnCola[] = [];
    private siguienteIndice = 1;

    public todos(): readonly ArchivoEnCola[] {
        return this.items;
    }

    public buscar(indice: number): ArchivoEnCola | undefined {
        return this.items.find((a) => a.indice === indice);
    }

    /** Archivos que cuentan para el lote (todos menos los rechazados por validación). */
    public vigentes(): ArchivoEnCola[] {
        return this.items.filter((a) => a.estado !== "rechazado");
    }

    /**
     * Agrega archivos validándolos uno por uno contra la cola y contra los ya agregados en la misma tanda.
     * Los rechazados quedan en la lista como «No se agregó» con su motivo, para que el usuario vea por qué.
     */
    public agregar(
        archivos: readonly File[],
        reglas: ReglasValidacion
    ): { agregados: ArchivoEnCola[]; rechazados: ArchivoEnCola[] } {
        const agregados: ArchivoEnCola[] = [];
        const rechazados: ArchivoEnCola[] = [];
        for (const archivo of archivos) {
            const candidato: Candidato = {
                nombre: archivo.name,
                tamano: archivo.size,
                contentType: contentTypeDe(archivo),
            };
            const motivo = validarCandidato(candidato, this.vigentes(), reglas);
            const item: ArchivoEnCola = {
                indice: this.siguienteIndice++,
                archivo,
                nombre: candidato.nombre,
                tamano: candidato.tamano,
                contentType: candidato.contentType,
                estado: motivo ? "rechazado" : "enCola",
                motivoRechazo: motivo ?? undefined,
                bytesConfirmados: 0,
                muestras: [],
                velocidadMbps: 0,
                cuartoAnunciado: 0,
            };
            this.items.push(item);
            (motivo ? rechazados : agregados).push(item);
        }
        return { agregados, rechazados };
    }

    /**
     * Cambia el estado si la transición es válida y limpia lo que no corresponde al estado nuevo.
     * Devuelve false si la transición no está permitida (por ejemplo, cancelar algo ya subido).
     */
    public transicionar(a: ArchivoEnCola, nuevo: EstadoArchivo): boolean {
        if (!puedeTransicionar(a.estado, nuevo)) return false;
        a.estado = nuevo;
        if (nuevo === "solicitando") {
            // Reintento: se conserva el id (la fila ya existe) y se descarta lo de la subida anterior.
            a.codigoError = undefined;
            a.mensajeServidor = undefined;
            a.rutaBlob = undefined;
            a.bytesConfirmados = 0;
            a.inicioMs = undefined;
            a.finMs = undefined;
            a.muestras = [];
            a.velocidadMbps = 0;
            a.cuartoAnunciado = 0;
        }
        return true;
    }

    public quitar(indice: number): boolean {
        const i = this.items.findIndex((a) => a.indice === indice);
        if (i < 0 || !accionesDe(this.items[i]).includes("quitar")) return false;
        this.items.splice(i, 1);
        return true;
    }

    /** action.clearDone: «Quita Subidos y Cancelados de la cola». */
    public limpiarTerminados(): number {
        const antes = this.items.length;
        for (let i = this.items.length - 1; i >= 0; i--) {
            const e = this.items[i].estado;
            if (e === "subido" || e === "cancelado") this.items.splice(i, 1);
        }
        return antes - this.items.length;
    }

    public vaciar(): void {
        this.items.length = 0;
    }
}

// ------------------------------------------------------------------ velocidad

const VENTANA_VELOCIDAD_MS = 5000;

/** Registra bytes confirmados y recalcula la velocidad como media móvil de 5 s. */
export function registrarMuestra(a: ArchivoEnCola, bytes: number, ahora: number): void {
    a.bytesConfirmados = bytes;
    a.muestras.push({ t: ahora, bytes });
    while (a.muestras.length > 1 && a.muestras[0].t < ahora - VENTANA_VELOCIDAD_MS) a.muestras.shift();
    const primera = a.muestras[0];
    a.velocidadMbps = calcularMbps(bytes - primera.bytes, ahora - primera.t);
}

// ------------------------------------------------------------------ agregados para la barra y las salidas

export interface Conteos {
    readonly total: number;
    readonly enCola: number;
    readonly solicitando: number;
    readonly esperando: number;
    readonly subiendo: number;
    readonly verificando: number;
    readonly subidos: number;
    readonly conError: number;
    readonly cancelados: number;
    readonly rechazados: number;
    /** No rechazados. Es lo que cuenta contra loteMaximo. */
    readonly vigentes: number;
    readonly activos: number;
}

export function contar(items: readonly ArchivoEnCola[]): Conteos {
    const n = (e: EstadoArchivo): number => items.filter((a) => a.estado === e).length;
    const c = {
        total: items.length,
        enCola: n("enCola"),
        solicitando: n("solicitando"),
        esperando: n("esperando"),
        subiendo: n("subiendo"),
        verificando: n("verificando"),
        subidos: n("subido"),
        conError: n("error"),
        cancelados: n("cancelado"),
        rechazados: n("rechazado"),
    };
    return {
        ...c,
        vigentes: c.total - c.rechazados,
        activos: c.solicitando + c.esperando + c.subiendo + c.verificando,
    };
}

export type EstadoControl = "vacio" | "encola" | "listo-para-sas" | "subiendo" | "completado" | "con-errores";

/**
 * Estado global para la app. listo-para-sas tiene prioridad sobre subiendo: si se reintenta un archivo mientras
 * otros suben, la app tiene que enterarse igual de que hay un pedido nuevo.
 */
export function estadoControl(c: Conteos, hayPedido: boolean): EstadoControl {
    if (hayPedido) return "listo-para-sas";
    if (c.activos > 0) return "subiendo";
    if (c.enCola > 0) return "encola";
    if (c.conError > 0) return "con-errores";
    if (c.subidos > 0 || c.cancelados > 0) return "completado";
    return "vacio";
}

/** Progreso global 0–100 por bytes, sobre lo que entró al circuito (sin en cola, rechazados, cancelados ni errores). */
export function progresoGlobal(items: readonly ArchivoEnCola[]): number {
    let hecho = 0;
    let total = 0;
    for (const a of items) {
        if (a.estado === "subido") {
            hecho += a.tamano;
            total += a.tamano;
        } else if (ESTADOS_ACTIVOS.includes(a.estado)) {
            hecho += a.bytesConfirmados;
            total += a.tamano;
        }
    }
    return total > 0 ? Math.floor((hecho / total) * 100) : 0;
}

/** Clave y valores del resumen de la barra de cola (queue.summary.*), igual que el prototipo. */
export function resumenCola(c: Conteos): { clave: ClaveTexto; valores: Record<string, number> } {
    if (c.activos > 0) {
        return {
            clave: "queue.summary.uploading",
            valores: { done: c.subidos, total: c.vigentes - c.cancelados },
        };
    }
    if (c.enCola > 0) return { clave: "queue.summary.ready", valores: { count: c.enCola } };
    if (c.conError > 0) return { clave: "queue.summary.mixed", valores: { ok: c.subidos, failed: c.conError } };
    return { clave: "queue.summary.done", valores: { count: c.subidos } };
}
