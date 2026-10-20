// Motor de cola y subida. Orquesta: pedido de SAS (por el puerto FuenteSas) → reparto de permisos → subida de
// hasta `paralelismoArchivos` archivos a la vez con subidaBloques → resultado por archivo.
// No conoce PCF ni DOM: avisa cambios a un observador y la vista o el index deciden qué pintar y qué publicar.
import {
    accionesDe,
    ArchivoEnCola,
    Cola,
    CodigoError,
    contar,
    EstadoArchivo,
    ReglasValidacion,
    registrarMuestra,
} from "./cola";
import { FuenteSas, PedidoSas, PermisoArchivo, RespuestaFuente } from "./fuenteSas";
import { ErrorSubida, subirEnBloques } from "./subidaBloques";

export interface ConfiguracionMotor {
    readonly paralelismoArchivos: number;
    readonly paralelismoBloques: number;
    readonly loteMaximo: number;
    readonly tabla: string;
}

export type EventoMotor =
    | { readonly tipo: "estado"; readonly archivo: ArchivoEnCola }
    | { readonly tipo: "progreso"; readonly archivo: ArchivoEnCola; readonly porcentaje: number }
    | { readonly tipo: "finLote"; readonly subidos: number; readonly fallidos: number };

/** «estructura»: cambió un estado o la lista; «progreso»: solo bytes y velocidad (se puede pintar y publicar con calma). */
export type TipoCambio = "estructura" | "progreso";

export interface ObservadorMotor {
    cambio(tipo: TipoCambio): void;
    evento(e: EventoMotor): void;
}

/** Margen antes del vencimiento: no vale la pena arrancar una subida que la SAS no va a cubrir. */
const MARGEN_VENCIMIENTO_MS = 5000;

export class MotorSubida {
    public readonly cola = new Cola();

    private pedido: PedidoSas | null = null;
    private numeroPedido = 0;
    /** SAS ya recibidas de archivos que esperan turno. Solo memoria; se borran al usarse, cancelarse o reiniciar. */
    private readonly permisos = new Map<number, PermisoArchivo>();
    private readonly cancelaciones = new Map<number, AbortController>();
    private activos = 0;
    /** Se incrementa al reiniciar: una subida que termina después de un reinicio no toca la cola nueva. */
    private generacion = 0;
    private huboActividad = false;
    private destruido = false;

    constructor(
        private readonly fuente: FuenteSas,
        private readonly observador: ObservadorMotor,
        private config: ConfiguracionMotor
    ) {}

    public configurar(config: ConfiguracionMotor): void {
        const masParalelo = config.paralelismoArchivos > this.config.paralelismoArchivos;
        this.config = config;
        if (masParalelo) this.bombear();
    }

    /** Pedido publicado que todavía no tiene respuesta (es lo que sale en `pendientes`). */
    public pedidoEnCurso(): PedidoSas | null {
        return this.pedido;
    }

    public get ultimoNumeroPedido(): number {
        return this.numeroPedido;
    }

    // ------------------------------------------------------------ acciones del usuario

    public agregar(archivos: readonly File[], reglas: ReglasValidacion): { agregados: number; rechazados: number } {
        const r = this.cola.agregar(archivos, reglas);
        this.observador.cambio("estructura");
        return { agregados: r.agregados.length, rechazados: r.rechazados.length };
    }

    public subirTodo(): void {
        this.solicitar(this.cola.todos().filter((a) => a.estado === "enCola"));
    }

    public reintentar(indice: number): void {
        const a = this.cola.buscar(indice);
        if (a && accionesDe(a).includes("reintentar")) this.solicitar([a]);
    }

    public reintentarFallidos(): void {
        this.solicitar(this.cola.todos().filter((a) => a.estado === "error" && accionesDe(a).includes("reintentar")));
    }

    public cancelar(indice: number): void {
        const a = this.cola.buscar(indice);
        if (!a || !accionesDe(a).includes("cancelar")) return;
        const estabaSubiendo = a.estado === "subiendo";
        if (!this.cola.transicionar(a, "cancelado")) return;
        a.finMs = Date.now();
        // La SAS que esperaba turno se descarta ya; la de una subida en curso muere con su AbortController.
        this.permisos.delete(indice);
        if (estabaSubiendo) this.cancelaciones.get(indice)?.abort();
        // Un archivo cancelado mientras su pedido sigue en curso NO abandona el pedido: la respuesta va a llegar
        // igual y, si se abandonara, podría emparejarse por posición con el pedido siguiente.
        this.notificarEstado(a);
        this.verificarFinLote();
    }

    public quitar(indice: number): void {
        if (this.cola.quitar(indice)) {
            this.permisos.delete(indice);
            this.observador.cambio("estructura");
            this.verificarFinLote();
        }
    }

    public limpiarTerminados(): void {
        if (this.cola.limpiarTerminados() > 0) this.observador.cambio("estructura");
    }

    /** disparadorReinicio: cancela todo, olvida el pedido y vacía la cola. numeroPedido NO vuelve a cero. */
    public reiniciar(): void {
        this.generacion++;
        this.cancelaciones.forEach((c) => c.abort());
        this.cancelaciones.clear();
        this.permisos.clear();
        this.pedido = null;
        this.fuente.abandonar();
        this.cola.vaciar();
        this.activos = 0;
        this.huboActividad = false;
        this.observador.cambio("estructura");
    }

    public destruir(): void {
        this.destruido = true;
        this.generacion++;
        this.cancelaciones.forEach((c) => c.abort());
        this.cancelaciones.clear();
        this.permisos.clear();
        this.pedido = null;
        this.fuente.abandonar();
    }

    // ------------------------------------------------------------ pedido de SAS

    private solicitar(archivos: readonly ArchivoEnCola[]): void {
        let alguno = false;
        for (const a of archivos) {
            if (this.cola.transicionar(a, "solicitando")) {
                alguno = true;
                this.huboActividad = true;
                this.observador.evento({ tipo: "estado", archivo: a });
            }
        }
        if (!alguno) return;
        this.publicarPedido();
        this.observador.cambio("estructura");
    }

    /**
     * Un solo pedido en vuelo. Los archivos que pasan a «Solicitando permiso» mientras hay otro pedido esperan y
     * salen en el siguiente, cuando llega la respuesta del actual.
     */
    private publicarPedido(): void {
        if (this.pedido !== null || this.destruido) return;
        const listos = this.cola
            .todos()
            .filter((a) => a.estado === "solicitando")
            .slice(0, Math.max(1, this.config.loteMaximo));
        if (listos.length === 0) return;
        const pedido: PedidoSas = {
            numero: ++this.numeroPedido,
            tabla: this.config.tabla,
            items: listos.map((a) => ({
                indice: a.indice,
                id: a.id ?? "",
                nombreArchivo: a.nombre,
                tamanoBytes: a.tamano,
                contentType: a.contentType,
            })),
        };
        this.pedido = pedido;
        const generacion = this.generacion;
        this.fuente.solicitar(pedido, (r) => {
            if (generacion === this.generacion && this.pedido === pedido) this.recibir(r);
        });
    }

    private recibir(r: RespuestaFuente): void {
        for (const p of r.permisos) {
            const a = this.cola.buscar(p.indice);
            if (!a) continue;
            if (a.id === undefined) a.id = p.id;
            // Cancelado mientras se pedía: se aprende el id (la fila ya existe) pero la SAS se descarta.
            if (a.estado !== "solicitando") continue;
            a.rutaBlob = p.rutaBlob;
            a.bloqueBytes = p.credenciales.bloqueBytes;
            if (this.vencido(p)) {
                this.fallar(a, "passExpired");
                continue;
            }
            this.permisos.set(a.indice, p);
            this.cola.transicionar(a, "esperando");
            this.notificarEstado(a);
        }
        for (const rechazo of r.rechazos) {
            const a = this.cola.buscar(rechazo.indice);
            if (!a) continue;
            if (a.id === undefined && rechazo.id !== undefined) a.id = rechazo.id;
            if (a.estado !== "solicitando") continue;
            a.mensajeServidor = rechazo.mensaje || undefined;
            // 403 del broker = el usuario no puede escribir esa fila: reintentar no sirve (error.permissionDenied).
            this.fallar(a, rechazo.codigo === 403 ? "permissionDenied" : "generic");
        }
        this.pedido = null;
        this.publicarPedido();
        this.bombear();
        this.observador.cambio("estructura");
        this.verificarFinLote();
    }

    private vencido(p: PermisoArchivo): boolean {
        const limite = Date.now() + MARGEN_VENCIMIENTO_MS;
        return (
            (p.expiraMs !== undefined && p.expiraMs <= limite) ||
            (p.credenciales.tokenExpiraMs !== undefined && p.credenciales.tokenExpiraMs <= limite)
        );
    }

    // ------------------------------------------------------------ subida

    private bombear(): void {
        if (this.destruido) return;
        while (this.activos < Math.max(1, this.config.paralelismoArchivos)) {
            const siguiente = this.cola.todos().find((a) => a.estado === "esperando" && this.permisos.has(a.indice));
            if (!siguiente) return;
            const permiso = this.permisos.get(siguiente.indice);
            this.permisos.delete(siguiente.indice);
            if (permiso === undefined) return;
            void this.subir(siguiente, permiso);
        }
    }

    private async subir(a: ArchivoEnCola, permiso: PermisoArchivo): Promise<void> {
        if (this.vencido(permiso)) {
            this.fallar(a, "passExpired");
            this.observador.cambio("estructura");
            return;
        }
        const generacion = this.generacion;
        const abort = new AbortController();
        this.cancelaciones.set(a.indice, abort);
        this.activos++;
        this.cola.transicionar(a, "subiendo");
        const inicio = Date.now();
        a.inicioMs = inicio;
        a.muestras = [{ t: inicio, bytes: 0 }];
        this.notificarEstado(a);

        try {
            await subirEnBloques(
                a.archivo,
                permiso.uploadUrl,
                {
                    bloqueBytes: permiso.credenciales.bloqueBytes,
                    paralelismo: Math.min(Math.max(this.config.paralelismoBloques, 1), 8),
                    versionServicio: permiso.credenciales.versionServicio,
                    tokenStorage: permiso.credenciales.tokenStorage,
                    contentType: a.contentType,
                },
                (hecho, total) => {
                    if (generacion !== this.generacion || a.estado !== "subiendo") return;
                    this.progresar(a, hecho, total);
                },
                abort.signal
            );
            if (generacion !== this.generacion) return;
            if (a.estado === "subiendo") this.cola.transicionar(a, "verificando");
            if (a.estado === "verificando") {
                // Put Block List confirmado: el blob existe con todos sus bloques.
                a.bytesConfirmados = a.tamano;
                a.finMs = Date.now();
                this.cola.transicionar(a, "subido");
                this.notificarEstado(a);
            }
        } catch (e) {
            if (generacion !== this.generacion) return;
            // La cancelación ya dejó el archivo en «Cancelado»; no es un error.
            if (a.estado === "cancelado") return;
            this.fallar(a, await clasificarError(e, a.archivo, permiso));
        } finally {
            if (generacion === this.generacion) {
                this.cancelaciones.delete(a.indice);
                this.activos = Math.max(0, this.activos - 1);
                this.bombear();
                this.observador.cambio("estructura");
                this.verificarFinLote();
            }
        }
    }

    private progresar(a: ArchivoEnCola, hecho: number, total: number): void {
        registrarMuestra(a, hecho, Date.now());
        // Anuncio cada 25 % (Anatomia.md §9); el 100 % lo cubre el cambio a «Verificando».
        const cuarto = Math.min(3, Math.floor((hecho / Math.max(1, total)) * 4));
        if (cuarto > a.cuartoAnunciado) {
            a.cuartoAnunciado = cuarto;
            this.observador.evento({ tipo: "progreso", archivo: a, porcentaje: cuarto * 25 });
        }
        if (hecho >= total) {
            // Todos los bloques confirmados: falta el Put Block List («Verificando», estado 4).
            this.cola.transicionar(a, "verificando");
            this.notificarEstado(a);
            return;
        }
        this.observador.cambio("progreso");
    }

    private fallar(a: ArchivoEnCola, codigo: CodigoError): void {
        a.codigoError = codigo;
        a.finMs = Date.now();
        if (this.cola.transicionar(a, "error")) this.notificarEstado(a);
    }

    private notificarEstado(a: ArchivoEnCola): void {
        this.observador.evento({ tipo: "estado", archivo: a });
        this.observador.cambio("estructura");
    }

    /** Anuncia el fin de una tanda cuando no queda nada en circuito (a11y.done). */
    private verificarFinLote(): void {
        if (!this.huboActividad) return;
        const c = contar(this.cola.todos());
        if (c.activos > 0) return;
        this.huboActividad = false;
        this.observador.evento({ tipo: "finLote", subidos: c.subidos, fallidos: c.conError });
    }
}

/** Estados con fila en Dataverse que terminaron: son los que van a la salida `resultados`. */
export const ESTADOS_RESULTADO: readonly EstadoArchivo[] = ["subido", "error", "cancelado"];

/**
 * Traduce una falla de subida a un código error.*:
 *   - Storage rechazó las llaves (401/403): si la SAS o el token ya vencieron → passExpired; si no, generic
 *     (reintentar pide SAS nueva).
 *   - Red: si el archivo ya no se puede leer, cambió en disco mientras subía → fileChanged; si no, network.
 */
export async function clasificarError(e: unknown, archivo: Blob, permiso: PermisoArchivo): Promise<CodigoError> {
    if (!(e instanceof ErrorSubida)) return "generic";
    if (e.causa === "sas") {
        const ahora = Date.now();
        const vencio =
            (permiso.expiraMs !== undefined && ahora >= permiso.expiraMs) ||
            (permiso.credenciales.tokenExpiraMs !== undefined && ahora >= permiso.credenciales.tokenExpiraMs);
        return vencio ? "passExpired" : "generic";
    }
    if (e.causa === "red") {
        try {
            await archivo.slice(0, 1).arrayBuffer();
            return "network";
        } catch {
            return "fileChanged";
        }
    }
    return "generic";
}
