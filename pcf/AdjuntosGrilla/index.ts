// AdjuntosGrilla — control de dataset para la subcuadrícula de adjuntos del formulario del padre (ADR-003 §4.6).
// Orquesta solo, sin la app: identidad propia (MSAL) → crea las filas con la Web API del componente → pide el lote
// de SAS al broker → sube con el motor de CargaAdjuntoBlob (dos llaves, por bloques, en paralelo) → marca Subido →
// refresca el dataset. La grilla muestra los diez estados y ejecuta las acciones de cada uno contra el broker.
// Este archivo solo conecta PCF ↔ motor ↔ broker ↔ vista; la lógica vive en los módulos importados.
import { IInputs, IOutputs } from "./generated/ManifestTypes";
import { AccionFila, contar, parsearAceptados, ReglasValidacion } from "../CargaAdjuntoBlob/cola";
import { etiquetarTipos, formatearTamano } from "../CargaAdjuntoBlob/formato";
import { ItemPedido } from "../CargaAdjuntoBlob/fuenteSas";
import { ConfiguracionMotor, EventoMotor, MotorSubida } from "../CargaAdjuntoBlob/motor";
import { etiquetaEstado, textoError } from "../CargaAdjuntoBlob/vista/filaArchivo";
import { AccionesBroker, mostrarBlob, seAbreEnLinea } from "./acciones";
import { ClienteBroker, ErrorBroker, normalizarUrlBroker } from "./clienteBroker";
import {
    columnasPorDefecto,
    leerPadre,
    navegacionValida,
    normalizarGuid,
    prefijoDe,
    RegistroPadre,
    RepositorioAdjuntos,
    TABLA_VALIDA,
    TipoDocumento,
} from "./dataverse";
import { AccionAdjunto, cambiaSolo, ESTADO, GrupoFiltro, PRESENTACION } from "./estados";
import { FuenteConFilas } from "./fuenteConFilas";
import { FuenteSasMsal } from "./fuenteSasMsal";
import { ErrorAutenticacion, IdentidadMsal } from "./identidadMsal";
import { Adjunto, Columnas, contarGrupos, filtrar, leerAdjunto, Orden, ordenar } from "./modeloGrilla";
import { calcularTema, elegir } from "./tema";
import { crearTraductorGrilla, TraductorGrilla } from "./textosGrilla";
import { Aviso, FilaVista } from "./vista/filaGrilla";
import { DensidadGrilla, ModeloGrillaVista } from "./vista/grilla";
import { VistaGrilla } from "./vista/vistaGrilla";

const MIB = 1024 * 1024;
/** Anatomia.md §7: sin paginación hasta 100; luego «Cargar más». */
const TAMANO_PAGINA = 100;
const INTERVALO_RELOJ_MS = 1000;
/** Refresco automático mientras hay adjuntos en Subido o Procesando (los mueve el worker). */
const INTERVALO_AUTOREFRESCO_MS = 15_000;
const MAXIMO_AUTOREFRESCOS = 60;
/** Espera tras el último cambio de la subida antes de refrescar (agrupa varios archivos). */
const ESPERA_REFRESCO_MS = 800;
const AVISO_MS = 10_000;
/** Marcadores de «Corregir y reanudar»: sobreviven a la navegación al formulario del adjunto y vuelta. */
const CLAVE_MARCADORES = "cmv8.adjuntosGrilla.reanudar";
const VIDA_MARCADOR_MS = 2 * 60 * 60 * 1000;
const REGEX_TENANT = /^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[a-z0-9-]+(\.[a-z0-9-]+)+)$/i;

/** Nombres de property-set del manifiesto → columna del modelo. */
const CONJUNTOS: readonly [keyof Columnas, string][] = [
    ["estado", "estado"],
    ["tipoDocumento", "tipoDocumento"],
    ["nombreArchivo", "nombreArchivo"],
    ["tamano", "tamano"],
    ["mensaje", "mensaje"],
    ["rutaBlob", "rutaBlob"],
    ["fechaSubida", "fechaSubida"],
    ["subidoPor", "subidoPor"],
];

interface Marcador {
    readonly tabla: string;
    readonly id: string;
    /** modifiedon de la fila (reloj del servidor) al abrir el formulario: guardar lo hace avanzar. */
    readonly modificadoMs: number | null;
    readonly creadoMs: number;
}

interface Configuracion {
    readonly valida: boolean;
    readonly urlBroker: string;
    readonly brokerClientId: string;
    readonly clientIdPcf: string;
    readonly tenantId: string;
    readonly lookupPadre: string;
    readonly lookupTipo: string;
}

function acotar(valor: number | null | undefined, defecto: number, min: number, max: number): number {
    if (valor === null || valor === undefined || !Number.isFinite(valor)) return defecto;
    return Math.min(max, Math.max(min, Math.floor(valor)));
}

/** Activación transitoria del usuario (un clic reciente): solo así se puede abrir el popup de MSAL. */
function hayGesto(): boolean {
    const activacion = (navigator as Navigator & { userActivation?: { isActive: boolean } }).userActivation;
    return activacion ? activacion.isActive : true;
}

function leerMarcadores(): Marcador[] {
    try {
        const v: unknown = JSON.parse(window.sessionStorage.getItem(CLAVE_MARCADORES) ?? "[]");
        return Array.isArray(v) ? (v as Marcador[]).filter((m) => typeof m?.id === "string" && typeof m?.tabla === "string") : [];
    } catch {
        return [];
    }
}

function guardarMarcadores(m: readonly Marcador[]): void {
    try {
        if (m.length === 0) window.sessionStorage.removeItem(CLAVE_MARCADORES);
        else window.sessionStorage.setItem(CLAVE_MARCADORES, JSON.stringify(m));
    } catch {
        // sessionStorage bloqueado: «Corregir y reanudar» queda en «Corregir» y el usuario reintenta a mano.
    }
}

export class AdjuntosGrilla implements ComponentFramework.StandardControl<IInputs, IOutputs> {
    private contexto: ComponentFramework.Context<IInputs>;
    private t: TraductorGrilla;
    private vista: VistaGrilla;
    private motor: MotorSubida;
    private config: Configuracion;
    private tabla = "";
    private prefijo = "";
    private padre: RegistroPadre | null = null;
    private identidad: IdentidadMsal | null = null;
    private acciones: AccionesBroker | null = null;
    private repo: RepositorioAdjuntos | null = null;

    // Dataset
    private columnas: Columnas | null = null;
    private columnasPresentes = new Set<string>();
    private columnasPedidas = false;
    private adjuntos: Adjunto[] = [];
    private estadosPrevios = new Map<string, number | null>();
    private cargado = false;
    /** Firma del último modelo de grilla pintado: el reloj de la subida repinta cada segundo y la grilla no cambia. */
    private firmaGrilla = "";

    // Grilla
    private filtro: GrupoFiltro = "todos";
    private orden: Orden = "fechaDesc";
    private densidadUsuario: DensidadGrilla | null = null;
    private readonly expandidas = new Set<string>();
    private readonly ocupados = new Set<string>();
    private readonly avisos = new Map<string, { aviso: Aviso; hasta: number }>();
    /** Filas en Error cuyo archivo ya no está (404 de reanudar): se ofrece Subir. */
    private readonly sinArchivo = new Set<string>();

    // Carga
    private reglas: ReglasValidacion = { aceptados: [], tamanoMaximoBytes: 250 * MIB, loteMaximo: 20 };
    private tiposEtiqueta = "";
    private tamanoMaximoEtiqueta = "";
    private tipos: TipoDocumento[] = [];
    private tiposCargando = true;
    private tiposError = false;
    private tipoSeleccionado = "";
    private tipoInvalido = false;
    /** Tipo elegido al pulsar «Subir todo», por archivo (el selector puede cambiar mientras se crean las filas). */
    private readonly tipoPorIndice = new Map<number, string>();
    /** «Subir» sobre una fila existente: índice de la cola → id de la fila a reutilizar. */
    private readonly reutilizar = new Map<number, string>();
    private sesion: ErrorAutenticacion | null = null;

    // Tema y tiempos
    private claveTema = "";
    private consultaOscuro: MediaQueryList | null = null;
    private cuadroPintado: number | undefined;
    private reloj: number | undefined;
    private temporizadorRefresco: number | undefined;
    private temporizadorAuto: number | undefined;
    private autorefrescos = 0;
    private destruido = false;

    private readonly alCambiarEsquema = (): void => {
        if (this.contexto.parameters.modo.raw === "auto") this.aplicarTema();
    };

    public init(
        context: ComponentFramework.Context<IInputs>,
        _notifyOutputChanged: () => void,
        _state: ComponentFramework.Dictionary,
        container: HTMLDivElement
    ): void {
        this.contexto = context;
        this.t = crearTraductorGrilla(context.resources);
        context.mode.trackContainerResize(true);

        const dataset = context.parameters.adjuntos;
        const tabla = (dataset.getTargetEntityType?.() ?? "").trim();
        this.tabla = TABLA_VALIDA.test(tabla) ? tabla : "";
        this.prefijo = prefijoDe(this.tabla);
        this.padre = leerPadre(context.mode);
        this.config = this.leerConfiguracion(context);

        if (this.config.valida) {
            this.identidad = new IdentidadMsal({
                clientId: this.config.clientIdPcf,
                tenantId: this.config.tenantId,
                brokerClientId: this.config.brokerClientId,
                nombreUsuario: context.userSettings?.userName ?? "",
            });
            const cliente = new ClienteBroker(this.config.urlBroker, this.identidad);
            this.acciones = new AccionesBroker(cliente, this.tabla);
            if (this.padre) {
                this.repo = new RepositorioAdjuntos(context.webAPI, context.utils, {
                    tabla: this.tabla,
                    prefijo: this.prefijo,
                    padre: this.padre,
                    lookupPadre: this.config.lookupPadre,
                    lookupTipo: this.config.lookupTipo,
                });
            }
            const fuente = new FuenteConFilas(
                new FuenteSasMsal(cliente, (e) => this.alFallarSesion(e), hayGesto),
                (item) => this.asegurarFila(item)
            );
            this.motor = new MotorSubida(
                fuente,
                { cambio: () => this.alCambiarCola(), evento: (e) => this.alEventoMotor(e) },
                this.configuracionMotor(context)
            );
        } else {
            // Sin configuración no se sube nada; el motor existe igual para que la vista tenga una cola (vacía).
            this.motor = new MotorSubida(
                { solicitar: (p, entregar) => entregar({ numero: p.numero, permisos: [], rechazos: [] }), abandonar: () => undefined },
                { cambio: () => this.programarPintado(), evento: () => undefined },
                this.configuracionMotor(context)
            );
        }

        this.vista = new VistaGrilla(
            container,
            this.t,
            {
                agregar: (archivos) => this.agregar(archivos),
                subirTodo: () => this.subirTodo(),
                limpiarTerminados: () => this.motor.limpiarTerminados(),
                reintentarFallidos: () => this.motor.reintentarFallidos(),
                accionFila: (accion, indice) => this.accionCola(accion, indice),
            },
            {
                filtrar: (g) => {
                    this.filtro = g;
                    this.programarPintado();
                },
                ordenar: (o) => {
                    this.orden = o;
                    this.programarPintado();
                },
                densidad: (d) => {
                    this.densidadUsuario = d;
                    this.aplicarTema();
                    this.programarPintado();
                },
                accion: (a, id) => this.accionAdjunto(a, id),
                alternarDetalle: (id) => {
                    if (!this.expandidas.delete(id)) this.expandidas.add(id);
                    this.programarPintado();
                },
                cargarMas: () => this.contexto.parameters.adjuntos.paging.loadNextPage(),
                refrescar: () => this.refrescar(),
            },
            (id) => {
                this.tipoSeleccionado = id;
                this.tipoInvalido = false;
                this.programarPintado();
            },
            () => void this.iniciarSesion()
        );

        if (typeof window.matchMedia === "function") {
            this.consultaOscuro = window.matchMedia("(prefers-color-scheme: dark)");
            this.consultaOscuro.addEventListener("change", this.alCambiarEsquema);
        }

        dataset.paging.setPageSize(TAMANO_PAGINA);
        this.leerEntradas(context);
        void this.cargarTipos();
        // Token en caché antes de que el usuario actúe (sin popup): así «Subir todo» no depende de un popup.
        if (this.identidad) void this.identidad.precalentar();
        this.pintar();
    }

    public updateView(context: ComponentFramework.Context<IInputs>): void {
        this.contexto = context;
        this.leerEntradas(context);
        if (context.mode.allocatedWidth > 0) this.vista.ponerAncho(context.mode.allocatedWidth);
        this.leerDataset(context.parameters.adjuntos);
        this.programarPintado();
    }

    public getOutputs(): IOutputs {
        return {};
    }

    public destroy(): void {
        this.destruido = true;
        this.motor.destruir();
        this.vista.destruir();
        this.consultaOscuro?.removeEventListener("change", this.alCambiarEsquema);
        window.clearInterval(this.reloj);
        window.clearTimeout(this.temporizadorRefresco);
        window.clearTimeout(this.temporizadorAuto);
        if (this.cuadroPintado !== undefined) window.cancelAnimationFrame(this.cuadroPintado);
    }

    // ------------------------------------------------------------ configuración

    private leerConfiguracion(context: ComponentFramework.Context<IInputs>): Configuracion {
        const p = context.parameters;
        const urlBroker = normalizarUrlBroker(p.urlBroker.raw);
        const brokerClientId = normalizarGuid(p.brokerClientId.raw) ?? "";
        const clientIdPcf = normalizarGuid(p.clientIdPcf.raw) ?? "";
        const tenant = (p.tenantId.raw ?? "").trim();
        const tenantId = REGEX_TENANT.test(tenant) ? tenant : "";
        const lookupPadre = (p.lookupPadre.raw ?? "").trim();
        // El SchemaName lleva el prefijo del maker (cred_TipoDocumento), no el del catálogo (cmv8_).
        const lookupTipo = (p.lookupTipoDocumento.raw ?? "").trim() || `${this.prefijo}TipoDocumento`;
        const valida =
            urlBroker !== "" &&
            brokerClientId !== "" &&
            clientIdPcf !== "" &&
            tenantId !== "" &&
            navegacionValida(lookupPadre) &&
            navegacionValida(lookupTipo) &&
            this.tabla !== "" &&
            this.prefijo !== "";
        return { valida, urlBroker, brokerClientId, clientIdPcf, tenantId, lookupPadre, lookupTipo };
    }

    private configuracionMotor(context: ComponentFramework.Context<IInputs>): ConfiguracionMotor {
        const p = context.parameters;
        return {
            paralelismoArchivos: acotar(p.paralelismoArchivos.raw, 2, 1, 4),
            paralelismoBloques: acotar(p.paralelismoBloques.raw, 4, 1, 8),
            loteMaximo: acotar(p.loteMaximo.raw, 20, 1, 100),
            tabla: this.tabla,
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
        this.aplicarTema();
    }

    private aplicarTema(): void {
        const tema = calcularTema(this.contexto.parameters, this.consultaOscuro?.matches === true, this.densidadUsuario);
        if (tema.clave === this.claveTema) return;
        this.claveTema = tema.clave;
        this.vista.aplicarTema(tema.variables, tema.atributos);
    }

    private get soloLectura(): boolean {
        return this.contexto.mode.isControlDisabled;
    }

    // ------------------------------------------------------------ dataset

    /** Columna de cada dato: la ligada por el maker (property-set) o la reservada; las que falten se agregan. */
    private resolverColumnas(dataset: ComponentFramework.PropertyTypes.DataSet): Columnas {
        const porDefecto = columnasPorDefecto(this.prefijo);
        const resueltas: Record<string, string> = { ...porDefecto };
        for (const [campo, alias] of CONJUNTOS) {
            const ligada = dataset.columns.find((c) => c.alias === alias);
            if (ligada?.name) resueltas[campo] = ligada.name;
        }
        const primaria = dataset.columns.find((c) => c.isPrimary)?.name ?? null;
        return { ...(resueltas as unknown as Omit<Columnas, "primaria">), primaria };
    }

    private leerDataset(dataset: ComponentFramework.PropertyTypes.DataSet): void {
        if (dataset.loading) return;
        if (!this.tabla) {
            this.cargado = true;
            return;
        }
        const columnas = this.resolverColumnas(dataset);
        this.columnas = columnas;
        this.columnasPresentes = new Set(dataset.columns.map((c) => c.name));

        // Las columnas reservadas pueden no estar en la vista de la subcuadrícula: se piden una vez y se refresca.
        if (!this.columnasPedidas && typeof dataset.addColumn === "function") {
            this.columnasPedidas = true;
            const necesarias = new Set<string>([
                columnas.estado,
                columnas.tipoDocumento,
                columnas.nombreArchivo,
                columnas.tamano,
                columnas.mensaje,
                columnas.rutaBlob,
                columnas.fechaSubida,
                columnas.subidoPor,
                columnas.contentType,
                columnas.modificado,
            ]);
            const faltan = Array.from(necesarias).filter((n) => n && !this.columnasPresentes.has(n));
            if (faltan.length > 0) {
                for (const n of faltan) dataset.addColumn(n);
                dataset.refresh();
                return;
            }
        }
        this.cargado = true;

        const adjuntos = dataset.sortedRecordIds
            .map((id) => dataset.records[id])
            .filter((r) => r !== undefined)
            .map((r) => leerAdjunto(r, columnas, this.columnasPresentes));
        this.anunciarCambios(adjuntos);
        this.adjuntos = adjuntos;
        // Lo local que ya no aplica (filas que desaparecieron o cambiaron de estado) se olvida.
        const ids = new Set(adjuntos.map((a) => a.id));
        for (const id of Array.from(this.expandidas)) if (!ids.has(id)) this.expandidas.delete(id);
        for (const id of Array.from(this.sinArchivo)) {
            const a = adjuntos.find((x) => x.id === id);
            if (!a || a.estado !== ESTADO.error) this.sinArchivo.delete(id);
        }
        void this.procesarMarcadores();
        this.programarAutorefresco();
    }

    /** a11y.status para las filas cuyo estado cambió desde el último refresco (lo movió el worker). */
    private anunciarCambios(adjuntos: readonly Adjunto[]): void {
        const previos = this.estadosPrevios;
        const nuevos = new Map<string, number | null>();
        for (const a of adjuntos) {
            nuevos.set(a.id, a.estado);
            if (!previos.has(a.id) || previos.get(a.id) === a.estado || a.estado === null) continue;
            const p = PRESENTACION[a.estado];
            this.vista.anunciador.anunciar(this.t("a11y.status", { name: a.nombre, status: this.t(p.clave) }), a.id);
        }
        this.estadosPrevios = nuevos;
    }

    private refrescar(): void {
        window.clearTimeout(this.temporizadorRefresco);
        this.temporizadorRefresco = undefined;
        this.contexto.parameters.adjuntos.refresh();
    }

    private refrescarPronto(): void {
        window.clearTimeout(this.temporizadorRefresco);
        this.temporizadorRefresco = window.setTimeout(() => this.refrescar(), ESPERA_REFRESCO_MS);
    }

    private programarAutorefresco(): void {
        window.clearTimeout(this.temporizadorAuto);
        this.temporizadorAuto = undefined;
        if (this.autorefrescos >= MAXIMO_AUTOREFRESCOS || !this.adjuntos.some((a) => cambiaSolo(a.estado))) return;
        this.temporizadorAuto = window.setTimeout(() => {
            this.temporizadorAuto = undefined;
            if (this.destruido || document.visibilityState !== "visible") {
                this.programarAutorefresco();
                return;
            }
            this.autorefrescos++;
            this.refrescar();
        }, INTERVALO_AUTOREFRESCO_MS);
    }

    // ------------------------------------------------------------ tipos de documento

    private async cargarTipos(): Promise<void> {
        if (!this.repo) {
            this.tiposCargando = false;
            return;
        }
        this.tiposCargando = true;
        this.tiposError = false;
        try {
            this.tipos = await this.repo.tiposEnUso();
            if (this.tipos.length === 1) this.tipoSeleccionado = this.tipos[0].id;
        } catch {
            this.tiposError = true;
        } finally {
            this.tiposCargando = false;
            this.programarPintado();
        }
    }

    // ------------------------------------------------------------ subida

    private agregar(archivos: File[]): void {
        const r = this.motor.agregar(archivos, this.reglas);
        const partes: string[] = [];
        if (r.agregados > 0) partes.push(this.t("a11y.added", { count: r.agregados }));
        if (r.rechazados > 0) partes.push(this.t("a11y.rejected", { count: r.rechazados }));
        this.vista.anunciador.anunciar(partes.join(" "), "control");
    }

    /** Archivos en cola que todavía no tienen fila (necesitan tipo de documento). */
    private sinFila(): number[] {
        return this.motor.cola
            .todos()
            .filter((a) => a.estado === "enCola" && a.id === undefined)
            .map((a) => a.indice);
    }

    private subirTodo(): void {
        const sinFila = this.sinFila();
        if (sinFila.length > 0 && !this.tipoSeleccionado) {
            this.tipoInvalido = true;
            this.programarPintado();
            this.vista.selector.enfocar();
            this.vista.anunciador.anunciar(this.t("file.docType.required"), "tipo", true);
            return;
        }
        for (const i of sinFila) this.tipoPorIndice.set(i, this.tipoSeleccionado);
        void this.arrancar();
    }

    /**
     * Pide el token dentro del clic (si hace falta popup, el navegador lo permite) y recién después arranca el
     * motor: la fuente lo reutiliza de la caché aunque crear las filas tarde más que la activación del clic.
     */
    private async arrancar(): Promise<void> {
        if (!this.identidad) return;
        try {
            await this.identidad.token({ interactivo: hayGesto() });
        } catch (e) {
            this.alFallarSesion(e instanceof ErrorAutenticacion ? e : new ErrorAutenticacion("otro"));
            return;
        }
        this.limpiarSesion();
        this.motor.subirTodo();
    }

    /** Puerto de FuenteConFilas: id de la fila de cada archivo, creándola o completándola si hace falta. */
    private async asegurarFila(item: ItemPedido): Promise<string> {
        if (!this.repo) throw new Error("Sin registro padre");
        const reutilizada = this.reutilizar.get(item.indice);
        if (reutilizada) {
            await this.repo.actualizarArchivo(reutilizada, item);
            this.reutilizar.delete(item.indice);
            return reutilizada;
        }
        if (item.id) return item.id;
        const tipo = this.tipoPorIndice.get(item.indice) ?? this.tipoSeleccionado;
        if (!tipo) throw new Error("Sin tipo de documento");
        const id = await this.repo.crear(item, tipo);
        this.tipoPorIndice.delete(item.indice);
        return id;
    }

    private accionCola(accion: AccionFila, indice: number): void {
        if (accion === "quitar") {
            this.motor.quitar(indice);
            this.reutilizar.delete(indice);
            this.tipoPorIndice.delete(indice);
        } else if (accion === "cancelar") this.motor.cancelar(indice);
        else this.motor.reintentar(indice);
    }

    private alCambiarCola(): void {
        if (this.destruido) return;
        this.programarPintado();
        const subiendo = this.motor.cola.todos().some((a) => a.estado === "subiendo");
        if (subiendo && this.reloj === undefined) {
            this.reloj = window.setInterval(() => this.programarPintado(), INTERVALO_RELOJ_MS);
        } else if (!subiendo && this.reloj !== undefined) {
            window.clearInterval(this.reloj);
            this.reloj = undefined;
        }
    }

    /** Anuncios (Anatomia.md §9) y paso 3 de ADR-003 §4.1: al terminar cada archivo, la fila pasa a Subido. */
    private alEventoMotor(e: EventoMotor): void {
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
            if (a.estado === "subido" && a.id) void this.marcarSubido(a.id);
            if (a.estado === "error" || a.estado === "cancelado") this.refrescarPronto();
        } else if (e.tipo === "progreso") {
            anunciador.anunciar(t("a11y.progress", { name: e.archivo.nombre, percent: e.porcentaje }), String(e.archivo.indice));
        } else {
            anunciador.anunciar(t("a11y.done", { ok: e.subidos, failed: e.fallidos }), "control");
            this.refrescarPronto();
        }
    }

    private async marcarSubido(id: string): Promise<void> {
        if (!this.repo) return;
        for (let intento = 0; intento < 2; intento++) {
            try {
                await this.repo.marcarSubido(id);
                break;
            } catch {
                // El blob ya está y Event Grid lo procesa igual; el estado de la fila lo corrige el worker al tomarlo.
                await new Promise<void>((resolve) => window.setTimeout(resolve, 2000));
            }
        }
        this.refrescarPronto();
    }

    // ------------------------------------------------------------ sesión

    private alFallarSesion(e: ErrorAutenticacion): void {
        this.sesion = e;
        this.programarPintado();
    }

    private limpiarSesion(): void {
        if (this.sesion === null) return;
        this.sesion = null;
        this.programarPintado();
    }

    /** Botón «Iniciar sesión» del aviso: popup dentro del clic y, si sale bien, reintenta lo que falló por sesión. */
    private async iniciarSesion(): Promise<void> {
        if (!this.identidad) return;
        try {
            await this.identidad.token({ interactivo: true, forzar: true });
        } catch (e) {
            this.alFallarSesion(e instanceof ErrorAutenticacion ? e : new ErrorAutenticacion("otro"));
            return;
        }
        this.limpiarSesion();
        this.motor.reintentarFallidos();
    }

    // ------------------------------------------------------------ acciones de fila

    private accionAdjunto(accion: AccionAdjunto, id: string): void {
        const a = this.adjuntos.find((x) => x.id === id);
        if (!a || this.ocupados.has(id)) return;
        switch (accion) {
            case "ver": {
                // La pestaña se abre ya, dentro del clic; si no, el bloqueador de popups la frena tras el await.
                const ventana = seAbreEnLinea(a.contentType) ? window.open("", "_blank") : null;
                if (ventana) ventana.opener = null;
                void this.ejecutar(a, async (gesto) => {
                    if (!this.acciones) return;
                    try {
                        mostrarBlob(await this.acciones.leer(a.id, a.contentType, gesto), a.nombre, ventana);
                    } catch (e) {
                        ventana?.close();
                        throw e;
                    }
                });
                break;
            }
            case "archivar":
                void this.ejecutar(a, async (gesto) => {
                    await this.acciones?.archivar(a.id, gesto);
                    this.avisar(a.id, { texto: this.t("action.done.archive", { name: a.nombre }), semantica: "success" });
                    this.vista.anunciador.anunciar(this.t("action.done.archive", { name: a.nombre }), a.id);
                    this.refrescarPronto();
                });
                break;
            case "reintentar":
                void this.ejecutar(a, async (gesto) => {
                    await this.acciones?.reanudar(a.id, gesto);
                    this.avisar(a.id, { texto: this.t("action.done.resume", { name: a.nombre }), semantica: "success" });
                    this.vista.anunciador.anunciar(this.t("action.done.resume", { name: a.nombre }), a.id);
                    this.refrescarPronto();
                });
                break;
            case "corregir":
                this.corregir(a);
                break;
            case "subir":
                this.vista.elegirArchivo(this.reglas.aceptados.join(","), (archivo) => this.subirEnFila(a.id, archivo));
                break;
        }
    }

    /** Corre una acción contra el broker con la fila ocupada y traduce el error a un aviso legible. */
    private async ejecutar(a: Adjunto, accion: (gesto: boolean) => Promise<void>): Promise<void> {
        const gesto = hayGesto();
        this.ocupados.add(a.id);
        this.avisos.delete(a.id);
        this.programarPintado();
        try {
            await accion(gesto);
            this.limpiarSesion();
        } catch (e) {
            const mensaje = this.textoErrorAccion(e, a);
            this.avisar(a.id, { texto: mensaje, semantica: "danger" });
            this.vista.anunciador.anunciar(`${a.nombre}: ${mensaje}`, a.id, true);
        } finally {
            this.ocupados.delete(a.id);
            this.programarPintado();
        }
    }

    /** Texto legible del error de una acción. Efecto: registra el aviso de sesión o la falta de archivo. */
    private textoErrorAccion(e: unknown, a: Adjunto): string {
        const t = this.t;
        if (e instanceof ErrorBroker) {
            if (e.autenticacion) {
                this.alFallarSesion(e.autenticacion);
                return e.autenticacion.motivo === "popupBloqueado" ? t("auth.popupBlocked") : t("auth.reauth");
            }
            if (e.status === 403) return t("action.error.forbidden");
            if (e.status === 0 && e.causa === "red") return t("error.network");
            if (a.estado === ESTADO.error && e.status === 404) {
                // El archivo ya no está en ingesta: se ofrece volver a subirlo sobre la misma fila.
                this.sinArchivo.add(a.id);
                return t("action.error.missingFile");
            }
            if (e.status === 404 || (e.status === 409 && (a.estado === ESTADO.archivado || a.estado === ESTADO.almacenado))) {
                return t("action.error.notAvailable");
            }
            if (e.status === 409) return t("action.error.state");
        }
        if (a.estado === ESTADO.almacenado) return t("action.error.archive");
        if (a.estado === ESTADO.error || a.estado === ESTADO.observada) return t("action.error.resume");
        return t("action.error.view");
    }

    private avisar(id: string, aviso: Aviso): void {
        this.avisos.set(id, { aviso, hasta: Date.now() + AVISO_MS });
        window.setTimeout(() => this.programarPintado(), AVISO_MS + 50);
    }

    /**
     * «Corregir y reanudar»: abre el formulario del adjunto. El host navega (el control se destruye), así que la
     * intención queda en sessionStorage; al volver al padre, si la fila sigue Observada y modifiedon avanzó (el
     * usuario guardó), el control llama a reanudar. Si no guardó, no se reanuda: el worker la volvería a observar.
     */
    private corregir(a: Adjunto): void {
        const marcadores = leerMarcadores().filter((m) => !(m.tabla === this.tabla && m.id === a.id));
        marcadores.push({ tabla: this.tabla, id: a.id, modificadoMs: a.modificadoMs, creadoMs: Date.now() });
        guardarMarcadores(marcadores);
        void this.contexto.navigation.openForm({ entityName: this.tabla, entityId: a.id }).catch(() => {
            guardarMarcadores(leerMarcadores().filter((m) => !(m.tabla === this.tabla && m.id === a.id)));
            this.avisar(a.id, { texto: this.t("action.error.resume"), semantica: "danger" });
            this.programarPintado();
        });
    }

    private async procesarMarcadores(): Promise<void> {
        const todos = leerMarcadores();
        const propios = todos.filter((m) => m.tabla === this.tabla);
        if (propios.length === 0 || !this.acciones) return;
        const ahora = Date.now();
        const quedan: Marcador[] = todos.filter((m) => m.tabla !== this.tabla);
        const reanudar: Adjunto[] = [];
        for (const m of propios) {
            const a = this.adjuntos.find((x) => x.id === m.id);
            if (ahora - m.creadoMs > VIDA_MARCADOR_MS) continue;
            if (!a) {
                quedan.push(m);
                continue;
            }
            if (a.estado !== ESTADO.observada) continue;
            const guardo = a.modificadoMs !== null && (m.modificadoMs === null || a.modificadoMs > m.modificadoMs);
            if (guardo && !this.ocupados.has(a.id)) reanudar.push(a);
            else quedan.push(m);
        }
        guardarMarcadores(quedan);
        for (const a of reanudar) {
            await this.ejecutar(a, async (gesto) => {
                await this.acciones?.reanudar(a.id, gesto);
                this.avisar(a.id, { texto: this.t("action.done.resume", { name: a.nombre }), semantica: "success" });
                this.vista.anunciador.anunciar(this.t("action.done.resume", { name: a.nombre }), a.id);
                this.refrescarPronto();
            });
        }
    }

    /** «Subir» sobre una fila Creada (o Error sin archivo): el archivo entra a la cola con el id de esa fila. */
    private subirEnFila(id: string, archivo: File): void {
        const r = this.motor.agregar([archivo], this.reglas);
        const a = this.motor.cola
            .todos()
            .slice()
            .reverse()
            .find((x) => x.archivo === archivo);
        if (!a || r.agregados === 0 || a.estado !== "enCola") {
            this.vista.anunciador.anunciar(this.t("a11y.rejected", { count: 1 }), "control");
            return;
        }
        a.id = id;
        this.reutilizar.set(a.indice, id);
        this.vista.anunciador.anunciar(this.t("a11y.added", { count: 1 }), "control");
        // Si hay otros archivos sin fila esperando tipo, se espera a «Subir todo» para no crearlos sin tipo.
        if (this.sinFila().length > 0 && !this.tipoSeleccionado) return;
        for (const i of this.sinFila()) this.tipoPorIndice.set(i, this.tipoSeleccionado);
        void this.arrancar();
    }

    // ------------------------------------------------------------ pintado

    private programarPintado(): void {
        if (this.cuadroPintado !== undefined || this.destruido) return;
        this.cuadroPintado = window.requestAnimationFrame(() => {
            this.cuadroPintado = undefined;
            if (!this.destruido) this.pintar();
        });
    }

    private accionesDe(a: Adjunto): AccionAdjunto[] {
        if (!this.config.valida || a.estado === null) return [];
        if (this.sinArchivo.has(a.id)) return this.padre ? ["subir"] : [];
        let acciones = [...PRESENTACION[a.estado].acciones];
        // Archivado sin copia en Blob (retención vencida): el broker todavía no trae de Content Manager.
        if (a.estado === ESTADO.archivado && a.rutaBlob === "") acciones = acciones.filter((x) => x !== "ver");
        if (!this.padre) acciones = acciones.filter((x) => x !== "subir");
        return acciones;
    }

    private pintar(): void {
        const t = this.t;
        const ahora = Date.now();
        for (const [id, v] of Array.from(this.avisos)) if (v.hasta <= ahora) this.avisos.delete(id);

        // Avisos generales: sesión, configuración, registro sin guardar.
        if (this.sesion) {
            this.vista.ponerAviso({
                texto: this.sesion.motivo === "popupBloqueado" ? t("auth.popupBlocked") : t("auth.reauth"),
                conBoton: true,
            });
        } else {
            this.vista.ponerAviso(null);
        }
        let bloqueo: string | null = null;
        if (!this.config.valida) bloqueo = t("config.missing");
        else if (!this.padre) bloqueo = t("config.noParent");
        // Formulario de solo lectura: sin zona de carga y sin explicación (el formulario ya lo muestra).
        this.vista.ponerBloqueo(bloqueo, bloqueo !== null || this.soloLectura);

        // Carga (selector de tipo + vista de CargaAdjuntoBlob).
        const items = this.motor.cola.todos();
        const deshabilitada = this.soloLectura || !this.config.valida || !this.padre;
        this.vista.pintarSelector({
            tipos: this.tipos,
            cargando: this.tiposCargando,
            errorCarga: this.tiposError,
            seleccionado: this.tipoSeleccionado,
            invalido: this.tipoInvalido && !this.tipoSeleccionado,
            deshabilitado: deshabilitada,
        });
        this.vista.carga.pintar({
            items,
            conteos: contar(items),
            variante: elegir(this.contexto.parameters.variante.raw, ["fluent", "institucional", "moderno"] as const, "fluent"),
            accept: this.reglas.aceptados.join(","),
            tipos: this.tiposEtiqueta,
            tamanoMaximo: this.tamanoMaximoEtiqueta,
            maximoArchivos: this.reglas.loteMaximo,
            deshabilitada,
        });

        // Grilla.
        const dataset = this.contexto.parameters.adjuntos;
        const visibles = ordenar(filtrar(this.adjuntos, this.filtro), this.orden);
        const filas: FilaVista[] = visibles.map((a) => ({
            adjunto: a,
            acciones: this.accionesDe(a),
            ocupado: this.ocupados.has(a.id),
            aviso: this.avisos.get(a.id)?.aviso ?? null,
            expandida: this.expandidas.has(a.id),
            tamanoTexto: a.tamano !== null ? formatearTamano(a.tamano, t) : "—",
        }));
        const modelo: ModeloGrillaVista = {
            carga: dataset.error ? "error" : !this.cargado || dataset.loading ? "cargando" : "listo",
            filas,
            total: this.adjuntos.length,
            conteos: contarGrupos(this.adjuntos),
            filtro: this.filtro,
            orden: this.orden,
            densidad: this.densidadUsuario ?? (this.contexto.parameters.densidad.raw === "compacta" ? "compacta" : "comoda"),
            hayMas: dataset.paging?.hasNextPage === true,
            soloLectura: this.soloLectura,
        };
        const firma = JSON.stringify(modelo);
        if (firma === this.firmaGrilla) return;
        this.firmaGrilla = firma;
        this.vista.pintarGrilla(modelo);
    }
}
