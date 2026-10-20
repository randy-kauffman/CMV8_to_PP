// Parseo y emparejamiento del lote de SAS que la app recibe de 'CMV8 Broker'.EmitirSasLote y pasa en la entrada
// `lote` (respuesta del broker v2, SasHttp.lote). Funciones puras, sin DOM ni PCF: se pueden probar aparte.
//
// Forma esperada (tal cual la devuelve el conector, pasada por JSON() en Power Fx):
//   { items: [ {id, uploadUrl, rutaBlob, expiraUtc} | {id, error, codigo} ], bloqueBytes, versionServicio,
//     tokenStorage, tokenStorageExpiraUtc }
// Extensiones opcionales que la app puede agregar y el control aprovecha (el broker no las conoce):
//   - numeroPedido (raíz): si viene y no coincide con el pedido en curso, el lote se descarta por viejo.
//   - indice, nombreArchivo, tamanoBytes (por item): claves explícitas de emparejamiento.
//   - { error, codigo } en la raíz sin items: la llamada al conector falló entera (IfError en la app).
//
// Seguridad: este módulo nunca registra nada. uploadUrl y tokenStorage salen de acá solo hacia el motor.

/** Lo que el control pidió, en el orden en que lo publicó en `pendientes`. */
export interface ItemPedidoLote {
    readonly indice: number;
    /** Vacío si el archivo todavía no tenía fila cuando se publicó el pedido. */
    readonly id: string;
    readonly nombreArchivo: string;
    readonly tamanoBytes: number;
}

interface ClavesItem {
    readonly indice?: number;
    readonly nombreArchivo?: string;
    readonly tamanoBytes?: number;
}

export interface PermisoLote extends ClavesItem {
    readonly tipo: "permiso";
    readonly id: string;
    readonly uploadUrl: string;
    readonly rutaBlob: string;
    readonly expiraMs?: number;
}

export interface RechazoLote extends ClavesItem {
    readonly tipo: "rechazo";
    readonly id?: string;
    readonly mensaje: string;
    readonly codigo: number;
}

export type ItemLote = PermisoLote | RechazoLote;

export interface LoteParseado {
    readonly items: readonly ItemLote[];
    readonly bloqueBytes?: number;
    readonly versionServicio?: string;
    readonly tokenStorage?: string;
    readonly tokenExpiraMs?: number;
    readonly numeroPedido?: number;
    /** La llamada entera falló: todos los archivos del pedido pasan a error con este código y mensaje. */
    readonly errorGeneral?: { readonly mensaje: string; readonly codigo: number };
}

export type ResultadoParseo =
    | { readonly ok: true; readonly lote: LoteParseado }
    | { readonly ok: false; readonly motivo: "json" | "forma" };

const REGEX_GUID = /^\{?([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\}?$/i;

function esObjeto(v: unknown): v is Record<string, unknown> {
    return typeof v === "object" && v !== null && !Array.isArray(v);
}

function texto(v: unknown): string | undefined {
    if (typeof v !== "string") return undefined;
    const s = v.trim();
    return s.length > 0 ? s : undefined;
}

/** Power Fx serializa números como números, pero se toleran cadenas numéricas por si la app arma el JSON a mano. */
function numero(v: unknown): number | undefined {
    if (typeof v === "number") return Number.isFinite(v) ? v : undefined;
    if (typeof v === "string" && v.trim() !== "") {
        const n = Number(v);
        return Number.isFinite(n) ? n : undefined;
    }
    return undefined;
}

function fecha(v: unknown): number | undefined {
    const s = texto(v);
    if (s === undefined) return undefined;
    const ms = Date.parse(s);
    return Number.isFinite(ms) ? ms : undefined;
}

/** Id de Dataverse normalizado (minúsculas, sin llaves) o undefined si no es un GUID. */
export function normalizarId(v: unknown): string | undefined {
    const s = texto(v);
    const m = s === undefined ? null : REGEX_GUID.exec(s);
    return m ? m[1].toLowerCase() : undefined;
}

/** Solo HTTPS: una SAS por HTTP expondría la firma en la red. */
export function esUrlSegura(url: string): boolean {
    try {
        return new URL(url).protocol === "https:";
    } catch {
        return false;
    }
}

function claves(o: Record<string, unknown>): ClavesItem {
    const indice = numero(o.indice);
    const tamano = numero(o.tamanoBytes);
    return {
        indice: indice !== undefined && Number.isInteger(indice) ? indice : undefined,
        nombreArchivo: texto(o.nombreArchivo),
        tamanoBytes: tamano !== undefined && Number.isInteger(tamano) ? tamano : undefined,
    };
}

function parsearItem(v: unknown): ItemLote {
    if (!esObjeto(v)) return { tipo: "rechazo", mensaje: "", codigo: 0 };
    const id = normalizarId(v.id);
    const k = claves(v);
    // Power Fx deja en null las columnas ausentes del registro tipado: un item exitoso trae "error": null.
    const error = texto(v.error);
    if (error !== undefined) {
        return { tipo: "rechazo", id, mensaje: error, codigo: numero(v.codigo) ?? 0, ...k };
    }
    const uploadUrl = texto(v.uploadUrl);
    const rutaBlob = texto(v.rutaBlob);
    if (id === undefined || uploadUrl === undefined || rutaBlob === undefined || !esUrlSegura(uploadUrl)) {
        return { tipo: "rechazo", id, mensaje: "", codigo: 0, ...k };
    }
    return { tipo: "permiso", id, uploadUrl, rutaBlob, expiraMs: fecha(v.expiraUtc), ...k };
}

export function parsearLote(json: string): ResultadoParseo {
    let raiz: unknown;
    try {
        raiz = JSON.parse(json);
    } catch {
        return { ok: false, motivo: "json" };
    }
    if (!esObjeto(raiz)) return { ok: false, motivo: "forma" };

    const numeroPedido = numero(raiz.numeroPedido);
    const errorRaiz = texto(raiz.error);
    const items: unknown = raiz.items;
    if (!Array.isArray(items)) {
        if (errorRaiz === undefined) return { ok: false, motivo: "forma" };
        return {
            ok: true,
            lote: { items: [], numeroPedido, errorGeneral: { mensaje: errorRaiz, codigo: numero(raiz.codigo) ?? 0 } },
        };
    }
    const bloque = numero(raiz.bloqueBytes);
    return {
        ok: true,
        lote: {
            items: items.map((v: unknown) => parsearItem(v)),
            bloqueBytes: bloque !== undefined && bloque > 0 ? Math.floor(bloque) : undefined,
            versionServicio: texto(raiz.versionServicio),
            tokenStorage: texto(raiz.tokenStorage),
            tokenExpiraMs: fecha(raiz.tokenStorageExpiraUtc),
            numeroPedido,
        },
    };
}

export interface Emparejamiento {
    readonly asignaciones: readonly { readonly indice: number; readonly item: ItemLote }[];
    /** Índices del pedido que ningún item del lote cubrió. */
    readonly sinRespuesta: readonly number[];
    /** Items del lote que no se pudieron asignar con seguridad (se descartan). */
    readonly sobrantes: number;
}

/**
 * Asigna cada item del lote a un archivo del pedido, de la regla más explícita a la más implícita:
 *   1. `indice` (si la app lo agregó al item).
 *   2. `nombreArchivo` + `tamanoBytes` (si la app los agregó). Es único porque la cola rechaza duplicados.
 *   3. `id`, para archivos que ya tenían fila (reintentos).
 *   4. Posición: el broker responde un item por cada item del pedido y en el mismo orden (EmitirSasLote.ejecutar),
 *      y la app arma el pedido con ForAll sobre `pendientes`, que conserva el orden. Solo se usa si el lote trae
 *      exactamente tantos items como el pedido y el id no contradice uno ya conocido; si no, no se adivina.
 * Una SAS asignada al archivo equivocado subiría bytes a la fila de otro documento: ante la duda, sin asignar.
 */
export function emparejar(pedido: readonly ItemPedidoLote[], items: readonly ItemLote[]): Emparejamiento {
    const libres = new Set(pedido.map((p) => p.indice));
    const asignaciones: { indice: number; item: ItemLote }[] = [];
    const posicionSegura = items.length === pedido.length;
    let sobrantes = 0;

    items.forEach((item, k) => {
        const disponibles = pedido.filter((p) => libres.has(p.indice));
        let destino: ItemPedidoLote | undefined;
        if (item.indice !== undefined) {
            destino = disponibles.find((p) => p.indice === item.indice);
        } else if (item.nombreArchivo !== undefined && item.tamanoBytes !== undefined) {
            destino = disponibles.find(
                (p) => p.nombreArchivo === item.nombreArchivo && p.tamanoBytes === item.tamanoBytes
            );
        } else {
            if (item.id !== undefined) destino = disponibles.find((p) => p.id !== "" && p.id === item.id);
            if (destino === undefined && posicionSegura) {
                const p = pedido[k];
                const coherente = p.id === "" || item.id === undefined || p.id === item.id;
                if (libres.has(p.indice) && coherente) destino = p;
            }
        }
        if (destino === undefined) {
            sobrantes++;
            return;
        }
        libres.delete(destino.indice);
        asignaciones.push({ indice: destino.indice, item });
    });

    return { asignaciones, sinRespuesta: pedido.filter((p) => libres.has(p.indice)).map((p) => p.indice), sobrantes };
}
