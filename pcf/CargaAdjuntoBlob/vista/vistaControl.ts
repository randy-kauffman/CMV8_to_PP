// Composición de la vista: Dropzone + QueueToolbar + lista de FileRow + regiones aria-live.
// Responsive por ResizeObserver con los puntos de quiebre del CONTENEDOR (Anatomia.md, reglas globales):
// < 480 estrecho, 480–899 medio, ≥ 900 ancho. En canvas el ancho lo decide el maker, no la ventana.
import { AccionFila, accionesDe, ArchivoEnCola, Conteos, resumenCola } from "../cola";
import { Traductor } from "../textos";
import { Variante } from "../tokens";
import { Anunciador } from "./anuncios";
import { BarraCola } from "./barraCola";
import { el } from "./dom";
import { construirModeloFila, FilaArchivo } from "./filaArchivo";
import { ZonaCarga } from "./zonaCarga";

export type Ancho = "estrecho" | "medio" | "ancho";

export function anchoDe(px: number): Ancho {
    if (px < 480) return "estrecho";
    if (px < 900) return "medio";
    return "ancho";
}

export interface ManejadoresVista {
    readonly agregar: (archivos: File[]) => void;
    readonly subirTodo: () => void;
    readonly limpiarTerminados: () => void;
    readonly reintentarFallidos: () => void;
    readonly accionFila: (accion: AccionFila, indice: number) => void;
}

export interface ModeloVista {
    readonly items: readonly ArchivoEnCola[];
    readonly conteos: Conteos;
    readonly variante: Variante;
    readonly accept: string;
    readonly tipos: string;
    readonly tamanoMaximo: string;
    readonly maximoArchivos: number;
    readonly deshabilitada: boolean;
}

export interface AtributosTema {
    readonly variante: Variante;
    readonly densidad: "compacta" | "comoda";
    readonly modo: "light" | "dark";
}

export class VistaControl {
    public readonly raiz: HTMLDivElement;
    public readonly anunciador = new Anunciador();
    private readonly zona: ZonaCarga;
    private readonly seccionCola: HTMLDivElement;
    private readonly barra: BarraCola;
    private readonly lista: HTMLDivElement;
    private readonly filas = new Map<number, FilaArchivo>();
    private readonly observador: ResizeObserver | null;
    private variablesAplicadas: Record<string, string> = {};

    constructor(
        contenedor: HTMLDivElement,
        private readonly t: Traductor,
        private readonly manejadores: ManejadoresVista
    ) {
        this.raiz = el("div", "fu-raiz");
        this.zona = new ZonaCarga(t, (archivos) => manejadores.agregar(archivos));
        this.seccionCola = el("div", "fu-cola");
        this.barra = new BarraCola(t, {
            subirTodo: () => manejadores.subirTodo(),
            limpiarTerminados: () => manejadores.limpiarTerminados(),
            reintentarFallidos: () => manejadores.reintentarFallidos(),
        });
        this.lista = el("div", "fu-lista", { role: "list", "aria-label": t("queue.aria") });
        this.seccionCola.append(this.barra.elemento, this.lista);
        this.seccionCola.hidden = true;
        this.raiz.append(this.zona.elemento, this.seccionCola, ...this.anunciador.elementos);
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

    /** Respaldo para hosts sin ResizeObserver: el index pasa allocatedWidth del contexto. */
    public ponerAncho(px: number): void {
        const ancho = anchoDe(px);
        if (this.raiz.dataset.ancho !== ancho) this.raiz.dataset.ancho = ancho;
    }

    /** Aplica las variables --fu-* y los atributos que eligen las reglas por variante, densidad y modo. */
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
    }

    public pintar(m: ModeloVista): void {
        const t = this.t;
        // Se recuerda dónde estaba el foco para devolverlo a un lugar razonable si su elemento desaparece.
        const activo = document.activeElement;
        const focoDentro = activo instanceof HTMLElement && this.raiz.contains(activo) ? activo : null;
        const ordenPrevio = Array.from(this.filas.keys());
        const filaConFoco = focoDentro ? ordenPrevio.find((i) => this.filas.get(i)?.elemento.contains(focoDentro)) : undefined;

        const c = m.conteos;
        this.zona.actualizar({
            compacta: c.total > 0,
            llena: c.vigentes >= m.maximoArchivos,
            deshabilitada: m.deshabilitada,
            accept: m.accept,
            tipos: m.tipos,
            tamanoMaximo: m.tamanoMaximo,
            maximoArchivos: m.maximoArchivos,
            cantidad: c.vigentes,
            variante: m.variante,
        });

        this.seccionCola.hidden = c.total === 0;
        const resumen = resumenCola(c);
        this.barra.actualizar({
            titulo: t("queue.title", { count: c.total }),
            resumen: t(resumen.clave, resumen.valores),
            puedeLimpiar: c.subidos + c.cancelados > 0,
            puedeReintentar: m.items.some((a) => a.estado === "error" && accionesDe(a).includes("reintentar")),
            puedeSubir: c.enCola > 0,
            deshabilitada: m.deshabilitada,
        });

        const ctx = {
            variante: m.variante,
            tipos: m.tipos,
            tamanoMaximo: m.tamanoMaximo,
            maximoArchivos: m.maximoArchivos,
            ahora: Date.now(),
        };
        const presentes = new Set<number>();
        let anterior: HTMLElement | null = null;
        for (const a of m.items) {
            presentes.add(a.indice);
            let fila = this.filas.get(a.indice);
            if (!fila) {
                fila = new FilaArchivo(t, a.indice, { ejecutar: (acc, i) => this.manejadores.accionFila(acc, i) });
                this.filas.set(a.indice, fila);
            }
            const acciones = m.deshabilitada ? [] : accionesDe(a);
            fila.actualizar(construirModeloFila(a, acciones, t, ctx));
            const esperado: ChildNode | null = anterior ? anterior.nextSibling : this.lista.firstChild;
            if (fila.elemento !== esperado) this.lista.insertBefore(fila.elemento, esperado);
            anterior = fila.elemento;
        }
        for (const [indice, fila] of Array.from(this.filas.entries())) {
            if (presentes.has(indice)) continue;
            fila.destruir();
            fila.elemento.remove();
            this.filas.delete(indice);
        }

        if (focoDentro && (!focoDentro.isConnected || focoDentro.closest("[hidden]") !== null)) {
            this.reubicarFoco(filaConFoco, ordenPrevio);
        }
    }

    public destruir(): void {
        this.observador?.disconnect();
        this.zona.destruir();
        this.anunciador.limpiar();
        this.filas.forEach((f) => f.destruir());
        this.filas.clear();
        this.raiz.remove();
    }

    /** Foco a la fila siguiente, si no a la anterior, si no a la zona de carga. */
    private reubicarFoco(filaPerdida: number | undefined, ordenPrevio: readonly number[]): void {
        if (filaPerdida !== undefined) {
            const pos = ordenPrevio.indexOf(filaPerdida);
            const candidatos = [...ordenPrevio.slice(pos + 1), ...ordenPrevio.slice(0, pos).reverse()];
            for (const i of candidatos) {
                const boton = this.filas.get(i)?.primerBoton();
                if (boton) {
                    boton.focus();
                    return;
                }
            }
        }
        this.zona.enfocar();
    }
}
