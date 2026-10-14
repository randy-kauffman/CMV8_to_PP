// Subida por bloques a Azure Blob Storage (Put Block + Put Block List) con fetch, sin SDK.
// Logica pura: no toca el DOM ni el contexto PCF, para poder probarla aparte.

export const VERSION_SERVICIO_DEFECTO = "2025-07-05";
export const BLOQUE_BYTES_DEFECTO = 4 * 1024 * 1024;
const ESPERAS_REINTENTO_MS = [500, 1000, 2000];

export interface OpcionesSubida {
    /** Tamano de bloque en bytes. Si falta o es invalido, 4 MiB. */
    bloqueBytes?: number;
    /** Cantidad de bloques en vuelo. Por defecto 4. */
    paralelismo?: number;
    /** x-ms-version. Por defecto 2025-07-05. */
    versionServicio?: string;
}

export type CausaError = "sas" | "red" | "servidor" | "cancelado";

export class ErrorSubida extends Error {
    constructor(
        mensaje: string,
        public readonly causa: CausaError,
        public readonly status?: number
    ) {
        super(mensaje);
        this.name = "ErrorSubida";
    }
}

/** Id de bloque: base64 del indice con 6 digitos. Todos los ids deben tener la misma longitud. */
export function codificarIdBloque(indice: number): string {
    return btoa(String(indice).padStart(6, "0"));
}

function escaparXml(texto: string): string {
    return texto.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

/** XML de Put Block List: un <Latest> por bloque, en orden. */
export function construirBlockListXml(ids: string[]): string {
    return (
        '<?xml version="1.0" encoding="utf-8"?><BlockList>' +
        ids.map((id) => `<Latest>${escaparXml(id)}</Latest>`).join("") +
        "</BlockList>"
    );
}

function urlConParametros(uploadUrl: string, parametros: string): string {
    // La SAS ya trae query string; se agregan los parametros con '&'.
    return uploadUrl.includes("?") ? `${uploadUrl}&${parametros}` : `${uploadUrl}?${parametros}`;
}

function esperar(ms: number, signal?: AbortSignal): Promise<void> {
    return new Promise((resolver, rechazar) => {
        if (signal?.aborted) {
            rechazar(new ErrorSubida("Subida cancelada", "cancelado"));
            return;
        }
        const t = setTimeout(() => {
            signal?.removeEventListener("abort", alAbortar);
            resolver();
        }, ms);
        const alAbortar = (): void => {
            clearTimeout(t);
            rechazar(new ErrorSubida("Subida cancelada", "cancelado"));
        };
        signal?.addEventListener("abort", alAbortar, { once: true });
    });
}

/** PUT con reintentos: red/5xx/429 reintenta (500ms, 1s, 2s); 403 corta de inmediato. */
async function putConReintentos(
    url: string,
    init: RequestInit,
    signal: AbortSignal
): Promise<void> {
    let ultimoError: ErrorSubida | undefined;
    for (let intento = 0; intento <= ESPERAS_REINTENTO_MS.length; intento++) {
        if (signal.aborted) throw new ErrorSubida("Subida cancelada", "cancelado");
        try {
            const resp = await fetch(url, { ...init, method: "PUT", signal });
            if (resp.ok) return;
            if (resp.status === 403) {
                throw new ErrorSubida(
                    "Storage rechazo la SAS (403): vencio o no corresponde al blob.",
                    "sas",
                    403
                );
            }
            if (resp.status >= 500 || resp.status === 429) {
                ultimoError = new ErrorSubida(
                    `Storage respondio ${resp.status}.`,
                    "servidor",
                    resp.status
                );
            } else {
                // 4xx no reintentable (400, 409, 412, etc.)
                throw new ErrorSubida(`Storage respondio ${resp.status}.`, "servidor", resp.status);
            }
        } catch (e) {
            if (e instanceof ErrorSubida) {
                if (e.causa === "sas" || e.causa === "cancelado") throw e;
                if (e.causa === "servidor" && (e.status ?? 0) < 500 && e.status !== 429) throw e;
                ultimoError = e;
            } else if (signal.aborted) {
                throw new ErrorSubida("Subida cancelada", "cancelado");
            } else {
                ultimoError = new ErrorSubida(
                    "Error de red al hablar con Storage (revisar CORS, firewall o conexion).",
                    "red"
                );
            }
        }
        if (intento < ESPERAS_REINTENTO_MS.length) {
            await esperar(ESPERAS_REINTENTO_MS[intento], signal);
        }
    }
    throw ultimoError ?? new ErrorSubida("Fallo desconocido en la subida.", "red");
}

/**
 * Sube `file` en bloques y confirma el blob.
 * onProgreso(bytesConfirmados, bytesTotales) se llama cada vez que un bloque termina bien.
 */
export async function subirEnBloques(
    file: Blob,
    uploadUrl: string,
    opciones: OpcionesSubida,
    onProgreso: (bytesConfirmados: number, bytesTotales: number) => void,
    signal?: AbortSignal
): Promise<void> {
    const bloqueBytes =
        opciones.bloqueBytes && opciones.bloqueBytes > 0 ? opciones.bloqueBytes : BLOQUE_BYTES_DEFECTO;
    const paralelismo = Math.max(1, Math.floor(opciones.paralelismo ?? 4));
    const version = opciones.versionServicio || VERSION_SERVICIO_DEFECTO;
    const total = file.size;
    const cantidad = Math.max(1, Math.ceil(total / bloqueBytes));
    const ids: string[] = [];
    for (let i = 0; i < cantidad; i++) ids.push(codificarIdBloque(i));

    // Controlador interno: si un bloque falla definitivamente, se frenan los demas.
    const interno = new AbortController();
    const alAbortarExterno = (): void => interno.abort();
    if (signal?.aborted) interno.abort();
    signal?.addEventListener("abort", alAbortarExterno, { once: true });

    let siguiente = 0;
    let confirmados = 0;
    let primerError: unknown;

    const trabajador = async (): Promise<void> => {
        while (!interno.signal.aborted) {
            const i = siguiente++;
            if (i >= cantidad) return;
            const inicio = i * bloqueBytes;
            const fin = Math.min(inicio + bloqueBytes, total);
            const url = urlConParametros(
                uploadUrl,
                `comp=block&blockid=${encodeURIComponent(ids[i])}`
            );
            try {
                await putConReintentos(
                    url,
                    { headers: { "x-ms-version": version }, body: file.slice(inicio, fin) },
                    interno.signal
                );
            } catch (e) {
                if (primerError === undefined) primerError = e;
                interno.abort();
                return;
            }
            confirmados += fin - inicio;
            onProgreso(confirmados, total);
        }
    };

    try {
        await Promise.all(Array.from({ length: Math.min(paralelismo, cantidad) }, trabajador));
        if (primerError !== undefined) {
            // Si se cancelo desde afuera, se informa cancelacion y no el error derivado.
            if (signal?.aborted) throw new ErrorSubida("Subida cancelada", "cancelado");
            throw primerError;
        }
        if (signal?.aborted) throw new ErrorSubida("Subida cancelada", "cancelado");

        await putConReintentos(
            urlConParametros(uploadUrl, "comp=blocklist"),
            {
                headers: {
                    "x-ms-version": version,
                    "x-ms-blob-content-type": (file as File).type || "application/octet-stream",
                    "Content-Type": "application/xml",
                },
                body: construirBlockListXml(ids),
            },
            interno.signal
        );
    } finally {
        signal?.removeEventListener("abort", alAbortarExterno);
    }
}
