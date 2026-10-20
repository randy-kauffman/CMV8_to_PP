// FileRow (Anatomia.md §3): role="listitem", grid [ícono] [contenido 1fr] [acciones auto]. La fila se crea una vez
// por archivo y se actualiza en el lugar, para no perder el foco de sus botones mientras sube.
import { AccionFila, ArchivoEnCola, CodigoError, EstadoArchivo } from "../cola";
import {
    clasificarArchivo,
    ClaseArchivo,
    dividirNombre,
    formatearEta,
    formatearMbps,
    formatearPorcentaje,
    formatearTamano,
} from "../formato";
import { ClaveTexto, Traductor } from "../textos";
import { Variante } from "../tokens";
import { BarraProgreso, ModeloBarra } from "./barraProgreso";
import { atributo, el, icono, nuevoId, ponerIcono, texto } from "./dom";
import { NombreIcono } from "./iconos.generado";

export type Semantica = "neutral" | "info" | "primary" | "success" | "danger";

interface PresentacionEstado {
    readonly clave: ClaveTexto;
    readonly icono: NombreIcono;
    readonly semantica: Semantica;
    readonly girar?: boolean;
}

/** Tabla «Estados de subida» de Anatomia.md §3 (esperando turno se presenta como En cola). */
const PRESENTACION: Readonly<Record<EstadoArchivo, PresentacionEstado>> = {
    enCola: { clave: "upload.queued", icono: "clock_16_regular", semantica: "neutral" },
    solicitando: { clave: "upload.authorizing", icono: "arrow_sync_16_regular", semantica: "info", girar: true },
    esperando: { clave: "upload.queued", icono: "clock_16_regular", semantica: "neutral" },
    subiendo: { clave: "upload.uploading", icono: "arrow_upload_16_regular", semantica: "primary" },
    verificando: { clave: "upload.verifying", icono: "shield_checkmark_16_regular", semantica: "info" },
    subido: { clave: "upload.uploaded", icono: "checkmark_circle_16_filled", semantica: "success" },
    error: { clave: "upload.error", icono: "error_circle_16_filled", semantica: "danger" },
    cancelado: { clave: "upload.canceled", icono: "dismiss_circle_16_regular", semantica: "neutral" },
    rechazado: { clave: "upload.rejected", icono: "warning_16_filled", semantica: "danger" },
};

/** Etiqueta corta del estado, la misma de la fila; la usan también los anuncios (a11y.status). */
export function etiquetaEstado(estado: EstadoArchivo, t: Traductor): string {
    return t(PRESENTACION[estado].clave);
}

const ICONO_CLASE: Readonly<Record<ClaseArchivo, NombreIcono>> = {
    pdf: "document_pdf_24_regular",
    imagen: "image_24_regular",
    video: "video_24_regular",
    documento: "document_24_regular",
};

interface PresentacionAccion {
    readonly icono: NombreIcono;
    readonly etiqueta: ClaveTexto;
    readonly ayuda: ClaveTexto;
}

const ACCIONES: Readonly<Record<AccionFila, PresentacionAccion>> = {
    quitar: { icono: "dismiss_20_regular", etiqueta: "action.remove", ayuda: "tooltip.remove" },
    cancelar: { icono: "dismiss_circle_16_regular", etiqueta: "action.cancel", ayuda: "tooltip.cancel" },
    reintentar: { icono: "arrow_clockwise_20_regular", etiqueta: "action.retry", ayuda: "tooltip.retry" },
};

const CLAVE_ERROR: Readonly<Record<CodigoError, ClaveTexto>> = {
    permissionDenied: "error.permissionDenied",
    passExpired: "error.passExpired",
    network: "error.network",
    fileChanged: "error.fileChanged",
    generic: "error.generic",
};

/** Texto de error para la fila y para resultados.mensajeError. */
export function textoError(codigo: CodigoError | undefined, t: Traductor): string {
    return t(CLAVE_ERROR[codigo ?? "generic"]);
}

const BLOQUE_POR_DEFECTO = 4 * 1024 * 1024;

export interface ContextoFila {
    readonly variante: Variante;
    readonly tipos: string;
    readonly tamanoMaximo: string;
    readonly maximoArchivos: number;
    readonly ahora: number;
}

export interface ModeloFila {
    readonly nombre: string;
    readonly base: string;
    readonly extension: string;
    readonly tamano: string;
    readonly iconoArchivo: NombreIcono;
    readonly estado: EstadoArchivo;
    readonly etiquetaEstado: string;
    readonly iconoEstado: NombreIcono;
    readonly semantica: Semantica;
    readonly girar: boolean;
    /** Detalle para contenedores estrechos (< 480): solo MB enviados. */
    readonly detalleCorto: string;
    /** Detalle completo: MB, velocidad y tiempo restante, o el mensaje de error o rechazo. */
    readonly detalle: string;
    readonly porcentaje: string;
    readonly barra: ModeloBarra;
    readonly acciones: readonly AccionFila[];
}

/** Arma lo que muestra una fila a partir del archivo. Pura: no toca DOM. */
export function construirModeloFila(
    a: ArchivoEnCola,
    acciones: readonly AccionFila[],
    t: Traductor,
    ctx: ContextoFila
): ModeloFila {
    const p = PRESENTACION[a.estado];
    const { base, extension } = dividirNombre(a.nombre);
    let detalle = "";
    let detalleCorto = "";
    let porcentaje = "";
    let barra: ModeloBarra = { modo: "oculta", valor: 0, textoValor: "", bloques: 0 };

    if (a.estado === "subiendo") {
        const enviados = formatearTamano(a.bytesConfirmados, t);
        const total = formatearTamano(a.tamano, t);
        const transcurridos = (ctx.ahora - (a.inicioMs ?? ctx.ahora)) / 1000;
        const bytesPorSeg = (a.velocidadMbps * 1_000_000) / 8;
        const restantes = bytesPorSeg > 0 ? (a.tamano - a.bytesConfirmados) / bytesPorSeg : Number.POSITIVE_INFINITY;
        detalle = t("upload.uploading.detail", {
            sent: enviados,
            total,
            speed: formatearMbps(a.velocidadMbps, t),
            eta: formatearEta(restantes, transcurridos, t),
        });
        // En estrecho se ocultan velocidad y ETA: se toma la plantilla hasta {speed} sin el separador final.
        const plantilla = t("upload.uploading.detail");
        const corte = plantilla.indexOf("{speed}");
        const corta = corte > 0 ? plantilla.slice(0, corte).replace(/[\s·]+$/, "") : plantilla;
        detalleCorto = corta.replace("{sent}", enviados).replace("{total}", total);
        const pct = a.tamano > 0 ? (a.bytesConfirmados / a.tamano) * 100 : 0;
        porcentaje = formatearPorcentaje(pct, t);
        const bloques = ctx.variante === "moderno" ? Math.ceil(a.tamano / (a.bloqueBytes ?? BLOQUE_POR_DEFECTO)) : 0;
        // aria-valuetext «{sent} de {total}» (Anatomia.md §5) sale de la misma plantilla traducida.
        barra = { modo: "determinada", valor: pct, textoValor: detalleCorto, bloques };
    } else if (a.estado === "enCola") {
        barra = { modo: "determinada", valor: 0, textoValor: "", bloques: 0 };
    } else if (a.estado === "solicitando" || a.estado === "verificando") {
        barra = { modo: "indeterminada", valor: 0, textoValor: "", bloques: 0 };
    } else if (a.estado === "subido") {
        barra = { modo: "exito", valor: 100, textoValor: "", bloques: 0 };
    } else if (a.estado === "error") {
        detalle = textoError(a.codigoError, t);
    } else if (a.estado === "rechazado" && a.motivoRechazo) {
        detalle = t(a.motivoRechazo, {
            types: ctx.tipos,
            size: formatearTamano(a.tamano, t),
            maxSize: ctx.tamanoMaximo,
            maxFiles: ctx.maximoArchivos,
        });
    }

    return {
        nombre: a.nombre,
        base,
        extension,
        tamano: formatearTamano(a.tamano, t),
        iconoArchivo: ICONO_CLASE[clasificarArchivo(a.nombre, a.contentType)],
        estado: a.estado,
        etiquetaEstado: t(p.clave),
        iconoEstado: p.icono,
        semantica: p.semantica,
        girar: p.girar === true,
        detalleCorto: detalleCorto || detalle,
        detalle,
        porcentaje,
        barra,
        acciones,
    };
}

export interface AccionesFila {
    readonly ejecutar: (accion: AccionFila, indice: number) => void;
}

export class FilaArchivo {
    public readonly elemento: HTMLDivElement;
    private readonly iconoArchivo: HTMLSpanElement;
    private readonly nombre: HTMLSpanElement;
    private readonly base: HTMLSpanElement;
    private readonly extension: HTMLSpanElement;
    private readonly tamano1: HTMLSpanElement;
    private readonly estado: HTMLSpanElement;
    private readonly estadoIcono: HTMLSpanElement;
    private readonly estadoTexto: HTMLSpanElement;
    private readonly tamano2: HTMLSpanElement;
    private readonly detalle: HTMLSpanElement;
    private readonly detalleCorto: HTMLSpanElement;
    private readonly detalleLargo: HTMLSpanElement;
    private readonly porcentaje: HTMLSpanElement;
    private readonly barra: BarraProgreso;
    private readonly contAcciones: HTMLDivElement;
    private claveAcciones = "";
    private acciones: readonly AccionFila[] = [];
    private nombreArchivo = "";

    constructor(
        private readonly t: Traductor,
        public readonly indice: number,
        private readonly manejador: AccionesFila
    ) {
        this.elemento = el("div", "fu-fila", { role: "listitem" });
        this.iconoArchivo = el("span", "fu-fila__icono");

        const principal = el("div", "fu-fila__principal");
        const linea1 = el("div", "fu-fila__linea1");
        const idNombre = nuevoId("nombre");
        this.nombre = el("span", "fu-fila__nombre", { id: idNombre });
        this.base = el("span", "fu-fila__base");
        this.extension = el("span", "fu-fila__ext");
        this.nombre.append(this.base, this.extension);
        this.tamano1 = el("span", "fu-fila__tamano fu-fila__tamano--l1");
        linea1.append(this.nombre, this.tamano1);

        const linea2 = el("div", "fu-fila__linea2");
        this.estado = el("span", "fu-fila__estado");
        this.estadoIcono = el("span", "fu-fila__estado-icono");
        this.estadoTexto = el("span", "fu-fila__estado-texto");
        this.estado.append(this.estadoIcono, this.estadoTexto);
        this.tamano2 = el("span", "fu-fila__tamano fu-fila__tamano--l2");
        this.detalle = el("span", "fu-fila__detalle");
        this.detalleCorto = el("span", "fu-solo-estrecho");
        this.detalleLargo = el("span", "fu-no-estrecho");
        this.detalle.append(this.detalleCorto, this.detalleLargo);
        this.porcentaje = el("span", "fu-fila__pct");
        linea2.append(this.estado, this.tamano2, this.detalle, this.porcentaje);

        this.barra = new BarraProgreso(idNombre);
        principal.append(linea1, linea2, this.barra.elemento);

        this.contAcciones = el("div", "fu-fila__acciones");
        this.elemento.append(this.iconoArchivo, principal, this.contAcciones);

        // Supr en una fila de la cola = Quitar (Anatomia.md §10). El evento sube desde el botón enfocado.
        this.elemento.addEventListener("keydown", (e) => {
            if (e.key === "Delete" && this.acciones.includes("quitar")) {
                e.preventDefault();
                this.manejador.ejecutar("quitar", this.indice);
            }
        });
    }

    public actualizar(m: ModeloFila): void {
        this.nombreArchivo = m.nombre;
        this.elemento.dataset.estado = m.estado;
        ponerIcono(this.iconoArchivo, m.iconoArchivo, "var(--fu-size-icon-file)");
        texto(this.base, m.base);
        texto(this.extension, m.extension);
        atributo(this.nombre, "title", m.nombre);
        texto(this.tamano1, m.tamano);
        texto(this.tamano2, `· ${m.tamano}`);

        this.estado.dataset.semantica = m.semantica;
        ponerIcono(this.estadoIcono, m.iconoEstado, 14, m.girar ? "fu-girar" : undefined);
        texto(this.estadoTexto, m.etiquetaEstado);

        texto(this.detalleCorto, m.detalleCorto);
        texto(this.detalleLargo, m.detalle);
        atributo(this.detalle, "title", m.detalle || null);
        texto(this.porcentaje, m.porcentaje);
        this.porcentaje.hidden = m.porcentaje === "";

        this.barra.actualizar(m.barra);
        this.pintarAcciones(m.acciones);
    }

    /** Primer botón de acción, para mover el foco hacia esta fila cuando otra desaparece. */
    public primerBoton(): HTMLButtonElement | null {
        return this.contAcciones.querySelector("button");
    }

    public destruir(): void {
        this.barra.destruir();
    }

    private pintarAcciones(acciones: readonly AccionFila[]): void {
        this.acciones = acciones;
        const clave = `${acciones.join(",")}|${this.nombreArchivo}`;
        if (clave === this.claveAcciones) return;
        this.claveAcciones = clave;
        // Si el foco estaba en una acción que desaparece (Cancelar → Reintentar), se pasa a la primera nueva.
        const teniaFoco = this.contAcciones.contains(document.activeElement);
        const botones = acciones.map((a) => this.crearBoton(a));
        this.contAcciones.replaceChildren(...botones);
        if (teniaFoco && botones.length > 0) botones[0].focus();
    }

    private crearBoton(accion: AccionFila): HTMLButtonElement {
        const p = ACCIONES[accion];
        const etiqueta = this.t(p.etiqueta);
        const b = el("button", "fu-boton-icono", {
            type: "button",
            "aria-label": `${etiqueta}: ${this.nombreArchivo}`,
            title: this.t(p.ayuda),
            "data-accion": accion,
        });
        b.appendChild(icono(p.icono, "var(--fu-size-icon-action)"));
        b.addEventListener("click", () => this.manejador.ejecutar(accion, this.indice));
        return b;
    }
}
