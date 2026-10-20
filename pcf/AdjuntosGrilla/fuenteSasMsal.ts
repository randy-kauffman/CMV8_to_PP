// Fuente de SAS del modo model-driven (ADR-003 §4.6): el control llama al broker directo con su propio token
// (IdentidadMsal) en lugar de recibir el lote de la app. Implementa el mismo puerto que FuenteSasApp, así que cola,
// motor y vista de carga son los de CargaAdjuntoBlob sin cambios. La respuesta del broker es la misma que ve la app
// por el conector: se parsea y empareja con las mismas funciones puras (parsearLote, respuestaDesdeLote).
import {
    Entregar,
    FuenteSas,
    PedidoSas,
    RechazoArchivo,
    respuestaDesdeLote,
    RespuestaFuente,
} from "../CargaAdjuntoBlob/fuenteSas";
import { parsearLote } from "../CargaAdjuntoBlob/lote";
import { ClienteBroker, ErrorBroker } from "./clienteBroker";
import { ErrorAutenticacion } from "./identidadMsal";

export class FuenteSasMsal implements FuenteSas {
    /** Cambia al abandonar: una respuesta que llega después no se entrega. */
    private generacion = 0;

    constructor(
        private readonly cliente: ClienteBroker,
        /** Avisa que hace falta volver a iniciar sesión (lo resuelve un clic del usuario, no esta fuente). */
        private readonly alFallarSesion: (e: ErrorAutenticacion) => void,
        /** true mientras el pedido nace de un clic reciente: permite abrir el popup de MSAL. */
        private readonly hayGesto: () => boolean
    ) {}

    public solicitar(pedido: PedidoSas, entregar: Entregar): void {
        void this.atender(pedido, entregar, this.generacion);
    }

    private async atender(pedido: PedidoSas, entregar: Entregar, generacion: number): Promise<void> {
        const r = await this.ejecutar(pedido);
        if (generacion === this.generacion) entregar(r);
    }

    public abandonar(): void {
        this.generacion++;
    }

    private async ejecutar(pedido: PedidoSas): Promise<RespuestaFuente> {
        // Sin fila no hay SAS: el broker autoriza por la fila (PATCH suplantado). FuenteConFilas las crea antes.
        const conFila = pedido.items.filter((i) => i.id !== "");
        const sinFila: RechazoArchivo[] = pedido.items
            .filter((i) => i.id === "")
            .map((i) => ({ indice: i.indice, codigo: 0, mensaje: "" }));
        if (conFila.length === 0) return { numero: pedido.numero, permisos: [], rechazos: sinFila };

        const sub: PedidoSas = { ...pedido, items: conFila };
        try {
            const r = await this.cliente.pedir(
                "POST",
                "sas/lote",
                {
                    tabla: pedido.tabla,
                    items: conFila.map((i) => ({
                        id: i.id,
                        nombreArchivo: i.nombreArchivo,
                        tamanoBytes: i.tamanoBytes,
                        contentType: i.contentType,
                    })),
                },
                this.hayGesto()
            );
            const respuesta = respuestaDesdeLote(sub, parsearLote(r.texto));
            return { ...respuesta, rechazos: [...respuesta.rechazos, ...sinFila] };
        } catch (e) {
            const status = e instanceof ErrorBroker ? e.status : 0;
            if (e instanceof ErrorBroker && e.autenticacion) this.alFallarSesion(e.autenticacion);
            const mensaje = e instanceof ErrorBroker ? e.mensajeServidor : "";
            return {
                numero: pedido.numero,
                permisos: [],
                // El id va siempre: la fila ya existe y el reintento la reutiliza (lote de uno).
                rechazos: [
                    ...conFila.map((i) => ({ indice: i.indice, id: i.id, codigo: status, mensaje })),
                    ...sinFila,
                ],
            };
        }
    }
}
