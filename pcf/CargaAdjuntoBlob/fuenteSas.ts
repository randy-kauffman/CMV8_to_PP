// Puerto de obtención de SAS. El motor de subida (motor.ts) no sabe de dónde salen las SAS: pide un pedido y
// recibe una respuesta. Así el modo canvas (la app llama al conector y pasa el lote) y el modo model-driven
// (el PCF llama al broker con su token MSAL, ADR-003 §4.6) comparten cola, motor y vista.
import { emparejar, ItemLote, ItemPedidoLote, parsearLote, ResultadoParseo } from "./lote";
import { huellaTexto } from "./formato";

export interface ItemPedido extends ItemPedidoLote {
    readonly contentType: string;
}

export interface PedidoSas {
    /** Crece con cada pedido publicado; la app lo usa para atender cada pedido una sola vez. */
    readonly numero: number;
    /** Tabla de adjuntos (solo la usa una fuente que llama al broker). */
    readonly tabla: string;
    readonly items: readonly ItemPedido[];
}

/** Credenciales compartidas por todo un lote. tokenStorage es del usuario, no del archivo (ADR-003 §4.1). */
export interface Credenciales {
    readonly tokenStorage?: string;
    readonly tokenExpiraMs?: number;
    readonly bloqueBytes?: number;
    readonly versionServicio?: string;
}

/** Permiso de subida para un archivo. Secreto: vive solo en memoria hasta que el archivo termina. */
export interface PermisoArchivo {
    readonly indice: number;
    readonly id: string;
    readonly uploadUrl: string;
    readonly rutaBlob: string;
    readonly expiraMs?: number;
    readonly credenciales: Credenciales;
}

export interface RechazoArchivo {
    readonly indice: number;
    readonly id?: string;
    /** Código HTTP del broker (403, 404, 409, 400) o 0 si el control no pudo interpretar la respuesta. */
    readonly codigo: number;
    readonly mensaje: string;
}

export interface RespuestaFuente {
    readonly numero: number;
    readonly permisos: readonly PermisoArchivo[];
    readonly rechazos: readonly RechazoArchivo[];
}

export type Entregar = (respuesta: RespuestaFuente) => void;

export interface FuenteSas {
    /** Pide permisos para un pedido. La fuente llama a `entregar` exactamente una vez por pedido. */
    solicitar(pedido: PedidoSas, entregar: Entregar): void;
    /** Olvida el pedido en curso (reinicio o destrucción del control). */
    abandonar(): void;
}

/**
 * Convierte un lote parseado en la respuesta del puerto. Pura y compartida: la fuente model-driven que llame al
 * broker directo recibe el mismo JSON y la puede reutilizar tal cual.
 */
export function respuestaDesdeLote(pedido: PedidoSas, parseo: ResultadoParseo): RespuestaFuente {
    const todosRechazados = (codigo: number, mensaje: string): RespuestaFuente => ({
        numero: pedido.numero,
        permisos: [],
        rechazos: pedido.items.map((p) => ({ indice: p.indice, id: p.id || undefined, codigo, mensaje })),
    });

    if (!parseo.ok) return todosRechazados(0, "");
    const lote = parseo.lote;
    if (lote.errorGeneral) return todosRechazados(lote.errorGeneral.codigo, lote.errorGeneral.mensaje);

    const credenciales: Credenciales = {
        tokenStorage: lote.tokenStorage,
        tokenExpiraMs: lote.tokenExpiraMs,
        bloqueBytes: lote.bloqueBytes,
        versionServicio: lote.versionServicio,
    };
    const { asignaciones, sinRespuesta } = emparejar(pedido.items, lote.items);
    const permisos: PermisoArchivo[] = [];
    const rechazos: RechazoArchivo[] = [];
    for (const { indice, item } of asignaciones) {
        agregar(indice, item, credenciales, permisos, rechazos);
    }
    for (const indice of sinRespuesta) rechazos.push({ indice, codigo: 0, mensaje: "" });
    return { numero: pedido.numero, permisos, rechazos };
}

function agregar(
    indice: number,
    item: ItemLote,
    credenciales: Credenciales,
    permisos: PermisoArchivo[],
    rechazos: RechazoArchivo[]
): void {
    if (item.tipo === "permiso") {
        permisos.push({
            indice,
            id: item.id,
            uploadUrl: item.uploadUrl,
            rutaBlob: item.rutaBlob,
            expiraMs: item.expiraMs,
            credenciales,
        });
    } else {
        rechazos.push({ indice, id: item.id, codigo: item.codigo, mensaje: item.mensaje });
    }
}

/**
 * Fuente del modo canvas («solo sube; el estado lo muestra la app»). No llama a nadie: el pedido sale por la
 * salida `pendientes` y la respuesta entra por la entrada `lote`, que la app llena con EmitirSasLote.
 *
 * El lote no se guarda: se parsea, se reparte al motor y se suelta. Para no consumir dos veces el mismo valor
 * (updateView se llama por cualquier cambio de entrada y la app puede tardar en limpiar varLote) se recuerda solo
 * una huella no reversible del último texto visto.
 */
export class FuenteSasApp implements FuenteSas {
    private pedido: PedidoSas | null = null;
    private entregar: Entregar | null = null;
    private ultimaHuella: string | null = null;

    public solicitar(pedido: PedidoSas, entregar: Entregar): void {
        this.pedido = pedido;
        this.entregar = entregar;
    }

    public abandonar(): void {
        this.pedido = null;
        this.entregar = null;
    }

    /**
     * Llamar en cada updateView con el valor crudo de la entrada `lote`. Un lote que llega sin pedido en curso
     * (viejo, o presente al cargar la pantalla) se marca como visto y se ignora.
     */
    public recibirEntrada(texto: string | null | undefined): void {
        if (texto === null || texto === undefined || texto.trim() === "") return;
        const huella = huellaTexto(texto);
        if (huella === this.ultimaHuella) return;
        this.ultimaHuella = huella;

        const pedido = this.pedido;
        const entregar = this.entregar;
        if (pedido === null || entregar === null) return;

        // Si la app sobrescribió el lote con numeroPedido (opcional), se exige que sea el del pedido en curso.
        const parseo = parsearLote(texto);
        if (parseo.ok && parseo.lote.numeroPedido !== undefined && parseo.lote.numeroPedido !== pedido.numero) return;

        this.pedido = null;
        this.entregar = null;
        entregar(respuestaDesdeLote(pedido, parseo));
    }
}
