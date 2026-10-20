// CargaAdjuntoBlob v2 — ciclo de vida del PCF (ADR-003 §4.1, modo canvas: «solo sube; el estado lo muestra la app»).
//
// Protocolo con la app (detalle y Power Fx en README.md):
//   1. El usuario agrega archivos; se validan al instante (tipo, tamaño, vacío, duplicado, lote lleno).
//   2. «Subir todo» publica un pedido en `pendientes` (estado listo-para-sas, `numeroPedido` nuevo).
//   3. La app crea las filas que falten, llama EmitirSasLote en el mismo orden y deja JSON(respuesta) en varLote,
//      que entra por `lote`.
//   4. El control empareja, sube con las dos llaves (SAS + tokenStorage) y publica `resultados` por archivo.
// Este archivo solo conecta PCF ↔ motor ↔ vista: la lógica vive en cola.ts, lote.ts, motor.ts y vista/.
import { IInputs, IOutputs } from "./generated/ManifestTypes";
import {
    AccionFila,
    ArchivoEnCola,
    contar,
    estadoControl,
    parsearAceptados,
    progresoGlobal,
    ReglasValidacion,
    resumenCola,
} from "./cola";
import { calcularMbps, etiquetarTipos, formatearTamano } from "./formato";
import { FuenteSasApp } from "./fuenteSas";
import { ConfiguracionMotor, ESTADOS_RESULTADO, EventoMotor, MotorSubida, TipoCambio } from "./motor";
import { crearTraductor, Traductor } from "./textos";
import { calcularVariables, Densidad, ModoColor, ValoresMaker, Variante } from "./tokens";
import { NOMBRES_MAKER } from "./tokens.generado";
import { etiquetaEstado, textoError } from "./vista/filaArchivo";
import { VistaControl } from "./vista/vistaControl";

const MIB = 1024 * 1024;
/** Mismo patrón que valida el broker (ADR-003 §1.1). */
const TABLA_VALIDA = /^[a-z][a-z0-9_]{1,63}$/;
/** Cada notificación dispara el OnChange de la app: el progreso se publica como mucho dos veces por segundo. */
const INTERVALO_NOTIFICACION_MS = 500;
/** Refresco de velocidad y tiempo restante entre bloques. */
const INTERVALO_RELOJ_MS = 1000;

const VARIANTES: readonly Variante[] = ["fluent", "institucional", "moderno"];

function elegir<T extends string>(valor: unknown, opciones: readonly T[], defecto: T): T {
    return typeof valor === "string" && (opciones as readonly string[]).includes(valor) ? (valor as T) : defecto;
}

function acotar(valor: number | null | undefined, defecto: number, min: number, max: number): number {
    if (valor === null || valor === undefined || !Number.isFinite(valor)) return defecto;
    return Math.min(max, Math.max(min, Math.floor(valor)));
}

/** Fila de la salida `resultados`. Nunca lleva uploadUrl ni tokenStorage. */
interface ResultadoSalida {
    id: string;
    estado: string;
    rutaBlob: string;
    nombreArchivo: string;
    tamanoBytes: number;
    contentType: string;
    mensajeError: string;
    duracionMs: number;
    velocidadMbps: number;
}

export class CargaAdjuntoBlob implements ComponentFramework.StandardControl<IInputs, IOutputs> {
    private contexto: ComponentFramework.Context<IInputs>;
    private notificar: () => void = () => undefined;
    private t: Traductor;
    private readonly fuente = new FuenteSasApp();
    private motor: MotorSubida;
    private vista: VistaControl;

    private reglas: ReglasValidacion = { aceptados: [], tamanoMaximoBytes: 250 * MIB, loteMaximo: 20 };
    private tiposEtiqueta = "";
    private tamanoMaximoEtiqueta = "";
    private varianteActual: Variante = "fluent";
    private claveTema = "";
    private consultaOscuro: MediaQueryList | null = null;
    private ultimoDisparador: number | null = null;

    private salidas: IOutputs = {};
    private salidasPendientes: IOutputs | null = null;
    private firmaSalidas = "";
    private ultimaNotificacion = 0;
    private temporizadorNotificacion: number | undefined;
    private cuadroPintado: number | undefined;
    private reloj: number | undefined;
    private destruido = false;

    private readonly alCambiarEsquema = (): void => {
        if (this.contexto.parameters.modo.raw === "auto") this.aplicarTema(this.contexto);
    };

    public init(
        context: ComponentFramework.Context<IInputs>,
        notifyOutputChanged: () => void,
        _state: ComponentFramework.Dictionary,
        container: HTMLDivElement
    ): void {
        this.contexto = context;
        this.notificar = notifyOutputChanged;
        this.t = crearTraductor(context.resources);
        context.mode.trackContainerResize(true);

        this.motor = new MotorSubida(
            this.fuente,
            {
                cambio: (tipo: TipoCambio) => this.alCambiar(tipo),
                evento: (e: EventoMotor) => this.alEvento(e),
            },
            this.configuracionMotor(context)
        );
        this.vista = new VistaControl(container, this.t, {
            agregar: (archivos) => this.agregar(archivos),
            subirTodo: () => this.motor.subirTodo(),
            limpiarTerminados: () => this.motor.limpiarTerminados(),
            reintentarFallidos: () => this.motor.reintentarFallidos(),
            accionFila: (accion, indice) => this.accionFila(accion, indice),
        });

        if (typeof window.matchMedia === "function") {
            this.consultaOscuro = window.matchMedia("(prefers-color-scheme: dark)");
            this.consultaOscuro.addEventListener("change", this.alCambiarEsquema);
        }

        this.ultimoDisparador = context.parameters.disparadorReinicio.raw;
        // Un lote presente al cargar (varLote sin limpiar al volver a la pantalla) se marca visto y no se usa:
        // no hay pedido en curso al que pertenezca.
        this.fuente.recibirEntrada(context.parameters.lote.raw);
        this.leerEntradas(context);
        this.pintar();
        this.recalcularSalidas(true);
    }

    public updateView(context: ComponentFramework.Context<IInputs>): void {
        this.contexto = context;

        const disparador = context.parameters.disparadorReinicio.raw;
        if (disparador !== this.ultimoDisparador) {
            this.ultimoDisparador = disparador;
            this.vista.anunciador.limpiar();
            this.motor.reiniciar();
        }

        this.leerEntradas(context);
        if (context.mode.allocatedWidth > 0) this.vista.ponerAncho(context.mode.allocatedWidth);
        // El lote se parsea, se reparte al motor y se suelta en esta misma llamada: no queda en ningún campo.
        this.fuente.recibirEntrada(context.parameters.lote.raw);
        this.programarPintado();
    }

    public getOutputs(): IOutputs {
        return this.salidas;
    }

    public destroy(): void {
        this.destruido = true;
        this.motor.destruir();
        this.vista.destruir();
        this.consultaOscuro?.removeEventListener("change", this.alCambiarEsquema);
        window.clearTimeout(this.temporizadorNotificacion);
        window.clearInterval(this.reloj);
        if (this.cuadroPintado !== undefined) window.cancelAnimationFrame(this.cuadroPintado);
    }

    // ------------------------------------------------------------ entradas

    private configuracionMotor(context: ComponentFramework.Context<IInputs>): ConfiguracionMotor {
        const p = context.parameters;
        const tabla = (p.tabla.raw ?? "").trim();
        return {
            paralelismoArchivos: acotar(p.paralelismoArchivos.raw, 2, 1, 4),
            paralelismoBloques: acotar(p.paralelismoBloques.raw, 4, 1, 8),
            loteMaximo: acotar(p.loteMaximo.raw, 20, 1, 100),
            tabla: TABLA_VALIDA.test(tabla) ? tabla : "",
        };
    }

    private leerEntradas(context: ComponentFramework.Context<IInputs>): void {
        const p = context.parameters;
        const aceptados = parsearAceptados(p.tiposAceptados.raw);
        const maximoMB = acotar(p.tamanoMaximoMB.raw, 250, 1, 1024 * 1024);
        const config = this.configuracionMotor(context);
        this.reglas = { aceptados, tamanoMaximoBytes: maximoMB * MIB, loteMaximo: config.loteMaximo };
        this.tiposEtiqueta = etiquetarTipos(aceptados);
        this.tamanoMaximoEtiqueta = formatearTamano(maximoMB * MIB, this.t);
        this.motor.configurar(config);
        this.aplicarTema(context);
    }

    private aplicarTema(context: ComponentFramework.Context<IInputs>): void {
        const p = context.parameters;
        const variante = elegir<Variante>(p.variante.raw, VARIANTES, "fluent");
        const densidad: Densidad = p.densidad.raw === "compacta" ? "compact" : "comfortable";
        const modoEntrada = elegir(p.modo.raw, ["claro", "oscuro", "auto"] as const, "claro");
        const modo: ModoColor =
            modoEntrada === "oscuro" || (modoEntrada === "auto" && this.consultaOscuro?.matches === true)
                ? "dark"
                : "light";
        const maker: ValoresMaker = {};
        for (const nombre of NOMBRES_MAKER) {
            const v = p[nombre].raw;
            if (v !== null && v !== undefined && v !== "") maker[nombre] = v;
        }

        const clave = JSON.stringify([variante, densidad, modo, maker]);
        if (clave === this.claveTema) return;
        this.claveTema = clave;
        this.varianteActual = variante;
        const variables = calcularVariables({
            variante,
            modo,
            densidad,
            maker,
            esValorCss: (propiedad, valor) => typeof CSS !== "undefined" && CSS.supports(propiedad, valor),
        });
        this.vista.aplicarTema(variables, {
            variante,
            densidad: densidad === "compact" ? "compacta" : "comoda",
            modo,
        });
    }

    // ------------------------------------------------------------ acciones

    private agregar(archivos: File[]): void {
        const r = this.motor.agregar(archivos, this.reglas);
        const partes: string[] = [];
        if (r.agregados > 0) partes.push(this.t("a11y.added", { count: r.agregados }));
        if (r.rechazados > 0) partes.push(this.t("a11y.rejected", { count: r.rechazados }));
        this.vista.anunciador.anunciar(partes.join(" "), "control");
    }

    private accionFila(accion: AccionFila, indice: number): void {
        if (accion === "quitar") this.motor.quitar(indice);
        else if (accion === "cancelar") this.motor.cancelar(indice);
        else this.motor.reintentar(indice);
    }

    // ------------------------------------------------------------ cambios del motor

    private alCambiar(tipo: TipoCambio): void {
        if (this.destruido) return;
        this.programarPintado();
        this.recalcularSalidas(tipo === "estructura");
        const subiendo = this.motor.cola.todos().some((a) => a.estado === "subiendo");
        if (subiendo && this.reloj === undefined) {
            this.reloj = window.setInterval(() => this.programarPintado(), INTERVALO_RELOJ_MS);
        } else if (!subiendo && this.reloj !== undefined) {
            window.clearInterval(this.reloj);
            this.reloj = undefined;
        }
    }

    /** Anuncios de Anatomia.md §9. Nunca incluyen ids, rutas ni URLs: solo nombre de archivo y estado. */
    private alEvento(e: EventoMotor): void {
        const t = this.t;
        const anunciador = this.vista.anunciador;
        if (e.tipo === "estado") {
            const a = e.archivo;
            const clave = String(a.indice);
            if (a.estado === "error") {
                anunciador.anunciar(t("a11y.error", { name: a.nombre, message: textoError(a.codigoError, t) }), clave, true);
            } else {
                anunciador.anunciar(t("a11y.status", { name: a.nombre, status: etiquetaEstado(a.estado, t) }), clave);
            }
        } else if (e.tipo === "progreso") {
            anunciador.anunciar(
                t("a11y.progress", { name: e.archivo.nombre, percent: e.porcentaje }),
                String(e.archivo.indice)
            );
        } else {
            anunciador.anunciar(t("a11y.done", { ok: e.subidos, failed: e.fallidos }), "control");
        }
    }

    // ------------------------------------------------------------ pintado

    private programarPintado(): void {
        if (this.cuadroPintado !== undefined || this.destruido) return;
        this.cuadroPintado = window.requestAnimationFrame(() => {
            this.cuadroPintado = undefined;
            if (!this.destruido) this.pintar();
        });
    }

    private pintar(): void {
        const items = this.motor.cola.todos();
        this.vista.pintar({
            items,
            conteos: contar(items),
            variante: this.varianteActual,
            accept: this.reglas.aceptados.join(","),
            tipos: this.tiposEtiqueta,
            tamanoMaximo: this.tamanoMaximoEtiqueta,
            maximoArchivos: this.reglas.loteMaximo,
            deshabilitada: this.contexto.mode.isControlDisabled,
        });
    }

    // ------------------------------------------------------------ salidas

    private recalcularSalidas(inmediata: boolean): void {
        const items = this.motor.cola.todos();
        const c = contar(items);
        const pedido = this.motor.pedidoEnCurso();
        const resumen = resumenCola(c);
        const salidas: IOutputs = {
            pendientes: JSON.stringify(
                pedido
                    ? pedido.items.map((i) => ({
                          indice: i.indice,
                          id: i.id,
                          nombreArchivo: i.nombreArchivo,
                          tamanoBytes: i.tamanoBytes,
                          contentType: i.contentType,
                      }))
                    : []
            ),
            numeroPedido: this.motor.ultimoNumeroPedido,
            resultados: JSON.stringify(this.resultados(items)),
            resumen: c.total === 0 ? "" : this.t(resumen.clave, resumen.valores),
            progreso: progresoGlobal(items),
            estado: estadoControl(c, pedido !== null),
            cantidadEnCola: c.enCola + c.solicitando + c.esperando,
            cantidadSubidos: c.subidos,
            cantidadConError: c.conError,
        };
        const firma = JSON.stringify(salidas);
        if (firma === this.firmaSalidas) return;
        this.firmaSalidas = firma;
        this.salidasPendientes = salidas;

        const espera = this.ultimaNotificacion + INTERVALO_NOTIFICACION_MS - Date.now();
        if (inmediata || espera <= 0) {
            this.emitirSalidas();
        } else if (this.temporizadorNotificacion === undefined) {
            this.temporizadorNotificacion = window.setTimeout(() => this.emitirSalidas(), espera);
        }
    }

    private emitirSalidas(): void {
        window.clearTimeout(this.temporizadorNotificacion);
        this.temporizadorNotificacion = undefined;
        if (this.salidasPendientes === null || this.destruido) return;
        this.salidas = this.salidasPendientes;
        this.salidasPendientes = null;
        this.ultimaNotificacion = Date.now();
        this.notificar();
    }

    private resultados(items: readonly ArchivoEnCola[]): ResultadoSalida[] {
        const salida: ResultadoSalida[] = [];
        for (const a of items) {
            if (a.id === undefined || !ESTADOS_RESULTADO.includes(a.estado)) continue;
            const duracionMs = a.inicioMs !== undefined && a.finMs !== undefined ? a.finMs - a.inicioMs : 0;
            const subido = a.estado === "subido";
            salida.push({
                id: a.id,
                estado: a.estado,
                rutaBlob: subido ? a.rutaBlob ?? "" : "",
                nombreArchivo: a.nombre,
                tamanoBytes: a.tamano,
                contentType: a.contentType,
                // El texto del broker es más específico (por ejemplo, falta el tipo de documento); si no hay, el
                // mensaje de error.* que ve el usuario.
                mensajeError: a.estado === "error" ? a.mensajeServidor ?? textoError(a.codigoError, this.t) : "",
                duracionMs,
                velocidadMbps: subido ? Math.round(calcularMbps(a.tamano, duracionMs) * 10) / 10 : 0,
            });
        }
        return salida;
    }
}
