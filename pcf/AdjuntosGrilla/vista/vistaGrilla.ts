// Composición del control model-driven: aviso de sesión o configuración · selector de tipo + vista de carga de
// CargaAdjuntoBlob (Dropzone, QueueToolbar y FileRow, sin cambios) · grilla de adjuntos · regiones aria-live.
// Todo cuelga de una raíz .fu-raiz con las variables --fu-*; la vista de carga anida la suya (el CSS la aplana).
import { AtributosTema, ManejadoresVista, VistaControl, anchoDe } from "../../CargaAdjuntoBlob/vista/vistaControl";
import { Anunciador } from "../../CargaAdjuntoBlob/vista/anuncios";
import { el, texto, visible } from "../../CargaAdjuntoBlob/vista/dom";
import { comoTraductor, TraductorGrilla } from "../textosGrilla";
import { Grilla, ManejadoresGrilla, ModeloGrillaVista } from "./grilla";
import { ModeloSelectorTipo, SelectorTipo } from "./selectorTipo";

export interface ModeloAviso {
    readonly texto: string;
    /** Botón «Iniciar sesión» (abre el popup de MSAL con un clic del usuario). */
    readonly conBoton: boolean;
}

export class VistaGrilla {
    public readonly raiz: HTMLDivElement;
    public readonly anunciador = new Anunciador();
    public readonly carga: VistaControl;
    public readonly selector: SelectorTipo;
    public readonly grilla: Grilla;
    private readonly aviso: HTMLDivElement;
    private readonly avisoTexto: HTMLSpanElement;
    private readonly avisoBoton: HTMLButtonElement;
    private readonly seccionCarga: HTMLDivElement;
    private readonly bloqueo: HTMLDivElement;
    private readonly entrada: HTMLInputElement;
    private alElegirArchivo: ((archivo: File) => void) | null = null;
    private readonly observador: ResizeObserver | null;
    private variablesAplicadas: Record<string, string> = {};

    constructor(
        contenedor: HTMLDivElement,
        t: TraductorGrilla,
        manejadoresCarga: ManejadoresVista,
        manejadoresGrilla: ManejadoresGrilla,
        alCambiarTipo: (id: string) => void,
        alIniciarSesion: () => void
    ) {
        this.raiz = el("div", "fu-raiz fug-raiz");

        this.aviso = el("div", "fug-aviso", { role: "alert" });
        this.avisoTexto = el("span", "fug-aviso__texto");
        this.avisoBoton = el("button", "fu-boton fu-boton--primario fug-aviso__boton", { type: "button" });
        texto(this.avisoBoton, t("auth.signIn"));
        this.avisoBoton.addEventListener("click", () => alIniciarSesion());
        this.aviso.append(this.avisoTexto, this.avisoBoton);
        this.aviso.hidden = true;

        this.bloqueo = el("div", "fug-bloqueo");
        this.bloqueo.hidden = true;

        this.seccionCarga = el("div", "fug-carga");
        this.selector = new SelectorTipo(t, alCambiarTipo);
        const contenedorCarga = el("div", "fug-carga__vista");
        this.seccionCarga.append(this.selector.elemento, contenedorCarga);
        this.carga = new VistaControl(contenedorCarga, comoTraductor(t), manejadoresCarga);

        this.grilla = new Grilla(t, manejadoresGrilla);

        // Selector de un archivo para «Subir» sobre una fila Creada (se abre dentro del clic: gesto del usuario).
        this.entrada = el("input", "fu-oculto", { type: "file", tabindex: "-1", "aria-hidden": "true" });
        this.entrada.addEventListener("change", () => {
            const archivo = this.entrada.files?.[0];
            this.entrada.value = "";
            const alElegir = this.alElegirArchivo;
            this.alElegirArchivo = null;
            if (archivo && alElegir) alElegir(archivo);
        });

        this.raiz.append(this.aviso, this.bloqueo, this.seccionCarga, this.grilla.elemento, this.entrada, ...this.anunciador.elementos);
        contenedor.appendChild(this.raiz);

        this.raiz.dataset.ancho = anchoDe(contenedor.clientWidth || 360);
        this.observador =
            typeof ResizeObserver === "function"
                ? new ResizeObserver((entradas) => {
                      const w = entradas[0]?.contentRect.width;
                      if (w !== undefined && w > 0) this.ponerAncho(w);
                  })
                : null;
        this.observador?.observe(contenedor);
    }

    public ponerAncho(px: number): void {
        const ancho = anchoDe(px);
        if (this.raiz.dataset.ancho !== ancho) this.raiz.dataset.ancho = ancho;
        this.carga.ponerAncho(px);
        this.grilla.ponerAncho(px);
    }

    public aplicarTema(variables: Record<string, string>, atributos: AtributosTema): void {
        const estilo = this.raiz.style;
        for (const nombre of Object.keys(this.variablesAplicadas)) {
            if (!(nombre in variables)) estilo.removeProperty(nombre);
        }
        for (const [nombre, valor] of Object.entries(variables)) {
            if (this.variablesAplicadas[nombre] !== valor) estilo.setProperty(nombre, valor);
        }
        this.variablesAplicadas = { ...variables };
        this.raiz.dataset.variante = atributos.variante;
        this.raiz.dataset.densidad = atributos.densidad;
        this.raiz.dataset.modo = atributos.modo;
        this.carga.aplicarTema(variables, atributos);
    }

    /** Aviso de sesión (con botón) o de error general. null lo oculta. */
    public ponerAviso(m: ModeloAviso | null): void {
        visible(this.aviso, m !== null);
        if (!m) return;
        texto(this.avisoTexto, m.texto);
        visible(this.avisoBoton, m.conBoton);
    }

    /** Sin padre guardado o sin configuración: la carga se oculta y se explica por qué. La grilla sigue visible. */
    public ponerBloqueo(mensaje: string | null, ocultarCarga: boolean): void {
        visible(this.bloqueo, mensaje !== null);
        texto(this.bloqueo, mensaje ?? "");
        visible(this.seccionCarga, !ocultarCarga);
    }

    public pintarSelector(m: ModeloSelectorTipo): void {
        this.selector.actualizar(m);
    }

    public pintarGrilla(m: ModeloGrillaVista): void {
        this.grilla.pintar(m);
    }

    /** Abre el selector del sistema. Llamar dentro del manejador del clic (si no, el navegador lo ignora). */
    public elegirArchivo(accept: string, alElegir: (archivo: File) => void): void {
        this.alElegirArchivo = alElegir;
        this.entrada.accept = accept;
        this.entrada.click();
    }

    public destruir(): void {
        this.observador?.disconnect();
        this.anunciador.limpiar();
        this.carga.destruir();
        this.grilla.destruir();
        this.raiz.remove();
    }
}
