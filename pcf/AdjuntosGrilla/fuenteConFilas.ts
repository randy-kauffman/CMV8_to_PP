// Decorador del puerto FuenteSas: antes de pedir SAS, asegura que cada archivo del pedido tenga su fila en la tabla
// de adjuntos (la crea con la Web API del componente, con la identidad del usuario: ADR-003 §4.1 paso 1). Así el
// motor de CargaAdjuntoBlob no cambia: para él, «Solicitando permiso» incluye crear la fila.
// Un archivo cuya fila no se pudo crear vuelve como rechazo (403 si fue por privilegios) y no llega al broker.
import { Entregar, FuenteSas, ItemPedido, PedidoSas, RechazoArchivo } from "../CargaAdjuntoBlob/fuenteSas";

/** Devuelve el id de la fila del archivo (creada, actualizada o la que ya tenía). Lanza si no se pudo. */
export type AsegurarFila = (item: ItemPedido) => Promise<string>;

/** Código HTTP equivalente de un error de la Web API: 403 si es de privilegios, 0 si no se sabe. */
export function codigoErrorDataverse(e: unknown): number {
    const o = typeof e === "object" && e !== null ? (e as { errorCode?: unknown; code?: unknown; message?: unknown }) : {};
    const codigo = typeof o.errorCode === "number" ? o.errorCode : typeof o.code === "number" ? o.code : 0;
    const mensaje = typeof o.message === "string" ? o.message.toLowerCase() : "";
    // 0x80040220 (SecLib::AccessCheckEx) y 0x80042F09 (sin privilegio de Create/Append): sin permiso.
    if (codigo === -2147220960 || codigo === -2147209463 || mensaje.includes("privilege")) return 403;
    return 0;
}

const PARALELISMO_FILAS = 4;

export class FuenteConFilas implements FuenteSas {
    private generacion = 0;

    constructor(
        private readonly interna: FuenteSas,
        private readonly asegurar: AsegurarFila
    ) {}

    public solicitar(pedido: PedidoSas, entregar: Entregar): void {
        void this.atender(pedido, entregar, this.generacion);
    }

    private async atender(pedido: PedidoSas, entregar: Entregar, generacion: number): Promise<void> {
        const { listos, rechazos } = await this.preparar(pedido);
        if (generacion !== this.generacion) return;
        if (listos.length === 0) {
            entregar({ numero: pedido.numero, permisos: [], rechazos });
            return;
        }
        this.interna.solicitar({ ...pedido, items: listos }, (r) =>
            entregar({ numero: pedido.numero, permisos: r.permisos, rechazos: [...r.rechazos, ...rechazos] })
        );
    }

    public abandonar(): void {
        this.generacion++;
        this.interna.abandonar();
    }

    private async preparar(pedido: PedidoSas): Promise<{ listos: ItemPedido[]; rechazos: RechazoArchivo[] }> {
        const resultados: (ItemPedido | RechazoArchivo)[] = new Array(pedido.items.length);
        let siguiente = 0;
        const trabajador = async (): Promise<void> => {
            while (siguiente < pedido.items.length) {
                const k = siguiente++;
                const item = pedido.items[k];
                try {
                    resultados[k] = { ...item, id: await this.asegurar(item) };
                } catch (e) {
                    resultados[k] = { indice: item.indice, id: item.id || undefined, codigo: codigoErrorDataverse(e), mensaje: "" };
                }
            }
        };
        await Promise.all(Array.from({ length: Math.min(PARALELISMO_FILAS, pedido.items.length) }, trabajador));
        // Se conserva el orden del pedido: el broker responde en el mismo orden (emparejar, regla de posición).
        const listos: ItemPedido[] = [];
        const rechazos: RechazoArchivo[] = [];
        for (const r of resultados) {
            if ("nombreArchivo" in r) listos.push(r);
            else rechazos.push(r);
        }
        return { listos, rechazos };
    }
}
