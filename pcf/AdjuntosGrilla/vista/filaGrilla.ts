// Fila de la grilla (Anatomia.md §7) y su detalle en acordeón (versión simple del DetailPanel, §8).
// La fila se crea una vez por adjunto y se actualiza en el lugar, para no perder el foco al refrescar el dataset.
// Celdas: nombre · tipo · estado · tamaño · fecha · usuario · acción principal · menú. En «medio» y «tarjetas» las
// celdas que no entran quedan solo para lectores de pantalla y una línea «meta» (aria-hidden) las muestra.
import { clasificarArchivo, ClaseArchivo, dividirNombre } from "../../CargaAdjuntoBlob/formato";
import { atributo, el, nuevoId, texto, visible } from "../../CargaAdjuntoBlob/vista/dom";
import { AccionAdjunto, CLAVE_ACCION, ESTADO, PRESENTACION, SemanticaEstado } from "../estados";
import { Adjunto } from "../modeloGrilla";
import { TraductorGrilla } from "../textosGrilla";
import { ChipEstado } from "./chipEstado";
import { iconoG, ponerIconoG } from "./iconoGrilla";
import { IconoGrilla } from "./iconosGrilla.generado";

export interface Aviso {
    readonly texto: string;
    readonly semantica: SemanticaEstado;
}

export interface FilaVista {
    readonly adjunto: Adjunto;
    readonly acciones: readonly AccionAdjunto[];
    readonly ocupado: boolean;
    readonly aviso: Aviso | null;
    readonly expandida: boolean;
    readonly tamanoTexto: string;
}

export interface ManejadoresFila {
    readonly accion: (accion: AccionAdjunto, id: string) => void;
    readonly alternarDetalle: (id: string) => void;
    readonly abrirMenu: (fila: FilaGrilla, boton: HTMLButtonElement) => void;
}

const ICONO_CLASE: Readonly<Record<ClaseArchivo, IconoGrilla>> = {
    pdf: "document_pdf_24_regular",
    imagen: "image_24_regular",
    video: "video_24_regular",
    documento: "document_24_regular",
};

const ICONO_ACCION: Readonly<Record<AccionAdjunto, IconoGrilla>> = {
    subir: "arrow_upload_16_regular",
    ver: "eye_20_regular",
    archivar: "archive_16_regular",
    corregir: "edit_16_filled",
    reintentar: "arrow_clockwise_20_regular",
};

const ICONO_MENSAJE: Readonly<Record<SemanticaEstado, IconoGrilla>> = {
    warning: "warning_16_filled",
    info: "info_16_filled",
    danger: "error_circle_16_filled",
    neutral: "prohibited_16_filled",
    success: "info_16_filled",
};

/** Mensaje bajo el nombre: el de la fila (mensajeerror) o, si falta, la descripción del estado. */
export function mensajeDe(a: Adjunto, t: TraductorGrilla): { texto: string; semantica: SemanticaEstado } | null {
    if (a.estado === null) return null;
    const p = PRESENTACION[a.estado];
    if (!p.conMensaje) return null;
    // Detenida: siempre aclara que el usuario no tiene que hacer nada (Anatomia.md §4).
    if (a.estado === ESTADO.detenida) {
        return { texto: a.mensaje ? `${a.mensaje} ${t("lifecycle.held.desc")}` : t("lifecycle.held.desc"), semantica: "info" };
    }
    return { texto: a.mensaje || t(p.descripcion), semantica: p.semantica };
}

export class FilaGrilla {
    public readonly elemento: HTMLDivElement;
    public readonly detalle: HTMLDivElement;
    public readonly celdaNombre: HTMLDivElement;
    public id: string;
    public acciones: readonly AccionAdjunto[] = [];
    public nombre = "";

    private readonly icono: HTMLSpanElement;
    private readonly base: HTMLSpanElement;
    private readonly extension: HTMLSpanElement;
    private readonly celdaTipo: HTMLDivElement;
    private readonly celdaEstado: HTMLDivElement;
    private readonly chip: ChipEstado;
    private readonly celdaTamano: HTMLDivElement;
    private readonly celdaFecha: HTMLDivElement;
    private readonly celdaUsuario: HTMLDivElement;
    private readonly celdaPrincipal: HTMLDivElement;
    private readonly celdaMenu: HTMLDivElement;
    private readonly botonMenu: HTMLButtonElement;
    private readonly meta: HTMLDivElement;
    private readonly mensaje: HTMLDivElement;
    private readonly mensajeIcono: HTMLSpanElement;
    private readonly mensajeTexto: HTMLSpanElement;
    private claveBotones = "";

    // Detalle
    private readonly detalleCelda: HTMLDivElement;
    private readonly detalleChip: ChipEstado;
    private readonly detalleDescripcion: HTMLSpanElement;
    private readonly detalleCaja: HTMLDivElement;
    private readonly detalleCajaTexto: HTMLSpanElement;
    private readonly detalleCajaIcono: HTMLSpanElement;
    private readonly detallePista: HTMLParagraphElement;
    private readonly detalleDatos: HTMLDListElement;
    private readonly detalleAcciones: HTMLDivElement;
    private claveDetalleAcciones = "";

    constructor(
        private readonly t: TraductorGrilla,
        adjunto: Adjunto,
        private readonly manejadores: ManejadoresFila
    ) {
        this.id = adjunto.id;
        this.elemento = el("div", "fug-fila", { role: "row" });
        const idNombre = nuevoId("gnombre");
        const idMensaje = nuevoId("gmsg");
        const idDetalle = nuevoId("gdet");

        this.celdaNombre = el("div", "fug-celda fug-c-nombre", { role: "gridcell", tabindex: "-1" });
        this.icono = el("span", "fug-c-nombre__icono");
        const nombre = el("span", "fug-c-nombre__texto", { id: idNombre });
        this.base = el("span", "fug-c-nombre__base");
        this.extension = el("span", "fug-c-nombre__ext");
        nombre.append(this.base, this.extension);
        this.celdaNombre.append(this.icono, nombre);

        this.celdaTipo = el("div", "fug-celda fug-c-tipo", { role: "gridcell", tabindex: "-1" });
        this.celdaEstado = el("div", "fug-celda fug-c-estado", { role: "gridcell", tabindex: "-1" });
        this.chip = new ChipEstado(t);
        this.celdaEstado.append(this.chip.elemento);
        this.celdaTamano = el("div", "fug-celda fug-c-tamano", { role: "gridcell", tabindex: "-1" });
        this.celdaFecha = el("div", "fug-celda fug-c-fecha", { role: "gridcell", tabindex: "-1" });
        this.celdaUsuario = el("div", "fug-celda fug-c-usuario", { role: "gridcell", tabindex: "-1" });
        this.celdaPrincipal = el("div", "fug-celda fug-c-principal", { role: "gridcell" });
        this.celdaMenu = el("div", "fug-celda fug-c-menu", { role: "gridcell" });
        this.botonMenu = el("button", "fu-boton-icono fug-menu-boton", {
            type: "button",
            tabindex: "-1",
            "aria-haspopup": "menu",
            "aria-expanded": "false",
            title: t("action.more"),
        });
        this.botonMenu.appendChild(iconoG("more_horizontal_20_regular", 20));
        this.botonMenu.addEventListener("click", (e) => {
            e.stopPropagation();
            this.manejadores.abrirMenu(this, this.botonMenu);
        });
        this.celdaMenu.append(this.botonMenu);

        // Visual: duplica tamaño · fecha · usuario cuando esas celdas no entran (las celdas siguen para lectores).
        this.meta = el("div", "fug-meta", { "aria-hidden": "true" });
        this.mensaje = el("div", "fug-mensaje", { id: idMensaje, "aria-hidden": "true" });
        this.mensajeIcono = el("span", "fug-mensaje__icono");
        this.mensajeTexto = el("span", "fug-mensaje__texto");
        this.mensaje.append(this.mensajeIcono, this.mensajeTexto);

        this.elemento.append(
            this.celdaNombre,
            this.celdaTipo,
            this.celdaEstado,
            this.celdaTamano,
            this.celdaFecha,
            this.celdaUsuario,
            this.celdaPrincipal,
            this.celdaMenu,
            this.meta,
            this.mensaje
        );
        atributo(this.celdaNombre, "aria-describedby", idMensaje);
        atributo(this.elemento, "aria-controls", idDetalle);

        // Clic en la fila (fuera de los botones) = abrir o cerrar el detalle, como Enter.
        this.elemento.addEventListener("click", (e) => {
            const destino = e.target;
            if (destino instanceof Element && destino.closest("button")) return;
            this.manejadores.alternarDetalle(this.id);
        });

        // ------------------------------------------------ detalle (acordeón)
        this.detalle = el("div", "fug-detalle", { role: "row", id: idDetalle });
        this.detalleCelda = el("div", "fug-detalle__celda", {
            role: "gridcell",
            "aria-colspan": "8",
            "aria-labelledby": idNombre,
        });
        const seccionEstado = el("div", "fug-detalle__estado");
        this.detalleChip = new ChipEstado(t);
        this.detalleDescripcion = el("span", "fug-detalle__desc");
        seccionEstado.append(this.detalleChip.elemento, this.detalleDescripcion);
        this.detalleCaja = el("div", "fug-detalle__caja");
        this.detalleCajaIcono = el("span", "fug-detalle__caja-icono");
        this.detalleCajaTexto = el("span", "fug-detalle__caja-texto");
        this.detalleCaja.append(this.detalleCajaIcono, this.detalleCajaTexto);
        this.detallePista = el("p", "fug-detalle__pista");
        const tituloDatos = el("div", "fug-detalle__titulo");
        texto(tituloDatos, t("panel.section.data"));
        this.detalleDatos = el("dl", "fug-detalle__datos");
        this.detalleAcciones = el("div", "fug-detalle__acciones");
        this.detalleCelda.append(
            seccionEstado,
            this.detalleCaja,
            this.detallePista,
            tituloDatos,
            this.detalleDatos,
            this.detalleAcciones
        );
        this.detalle.append(this.detalleCelda);
        this.detalle.hidden = true;
    }

    /** Destinos de foco de la fila para la navegación con flechas, en orden visual (solo los visibles). */
    public objetivos(): HTMLElement[] {
        const lista: HTMLElement[] = [this.celdaNombre, this.celdaTipo, this.celdaEstado, this.celdaTamano, this.celdaFecha, this.celdaUsuario];
        // Las celdas que la disposición deja solo para lectores de pantalla miden 1 px: no reciben foco con flechas.
        const visibles = lista.filter((c) => c.offsetWidth > 1);
        const principal = this.celdaPrincipal.querySelector("button");
        if (principal) visibles.push(principal);
        visibles.push(this.botonMenu);
        return visibles;
    }

    public get botonDelMenu(): HTMLButtonElement {
        return this.botonMenu;
    }

    public actualizar(m: FilaVista, soloLectura: boolean): void {
        const a = m.adjunto;
        const t = this.t;
        this.id = a.id;
        this.nombre = a.nombre;
        this.acciones = m.acciones;
        this.elemento.dataset.estado = a.estado === null ? "" : String(a.estado);
        atributo(this.elemento, "aria-expanded", m.expandida ? "true" : "false");
        atributo(this.elemento, "aria-busy", m.ocupado ? "true" : null);

        ponerIconoG(this.icono, ICONO_CLASE[clasificarArchivo(a.nombre, a.contentType)], 20);
        const { base, extension } = dividirNombre(a.nombre || "—");
        texto(this.base, base);
        texto(this.extension, extension);
        atributo(this.celdaNombre, "title", a.nombre || null);
        texto(this.celdaTipo, a.tipoDocumento || "—");
        atributo(this.celdaTipo, "title", a.tipoDocumento || null);
        this.chip.actualizar(a.estado);
        texto(this.celdaTamano, m.tamanoTexto);
        texto(this.celdaFecha, a.fechaTexto);
        texto(this.celdaUsuario, a.usuario);
        atributo(this.celdaUsuario, "title", a.usuario || null);
        texto(this.meta, [m.tamanoTexto, a.fechaTexto, a.usuario].filter((s) => s && s !== "—").join(" · "));

        const msg = m.aviso ?? mensajeDe(a, t);
        visible(this.mensaje, msg !== null);
        if (msg) {
            this.mensaje.dataset.semantica = msg.semantica;
            ponerIconoG(this.mensajeIcono, ICONO_MENSAJE[msg.semantica], 12);
            texto(this.mensajeTexto, msg.texto);
        }

        this.pintarPrincipal(m, soloLectura);
        atributo(this.botonMenu, "aria-label", `${t("action.more")}: ${a.nombre}`);

        // Detalle
        visible(this.detalle, m.expandida);
        if (m.expandida) this.pintarDetalle(m, msg, soloLectura);
    }

    private pintarPrincipal(m: FilaVista, soloLectura: boolean): void {
        const principal = soloLectura ? m.acciones.find((x) => x === "ver") : m.acciones[0];
        const clave = `${principal ?? ""}|${m.ocupado}|${this.nombre}`;
        if (clave === this.claveBotones) return;
        this.claveBotones = clave;
        const tenia = this.celdaPrincipal.contains(document.activeElement);
        if (!principal) {
            this.celdaPrincipal.replaceChildren();
            if (tenia) this.botonMenu.focus();
            return;
        }
        const b = this.boton(principal, m.ocupado, "fu-boton fu-boton--sutil fug-principal");
        b.tabIndex = tenia ? 0 : -1;
        this.celdaPrincipal.replaceChildren(b);
        if (tenia) b.focus();
    }

    private boton(accion: AccionAdjunto, ocupado: boolean, clase: string): HTMLButtonElement {
        const etiqueta = this.t(CLAVE_ACCION[accion]);
        const b = el("button", clase, { type: "button", "data-accion": accion });
        b.appendChild(iconoG(ICONO_ACCION[accion], 16));
        const span = el("span");
        texto(span, ocupado ? this.t("action.busy") : etiqueta);
        b.appendChild(span);
        atributo(b, "aria-label", `${etiqueta}: ${this.nombre}`);
        b.disabled = ocupado;
        b.addEventListener("click", (e) => {
            e.stopPropagation();
            this.manejadores.accion(accion, this.id);
        });
        return b;
    }

    private pintarDetalle(m: FilaVista, msg: { texto: string; semantica: SemanticaEstado } | null, soloLectura: boolean): void {
        const a = m.adjunto;
        const t = this.t;
        this.detalleChip.actualizar(a.estado);
        texto(this.detalleDescripcion, a.estado === null ? "" : t(PRESENTACION[a.estado].descripcion));
        visible(this.detalleCaja, msg !== null);
        if (msg) {
            this.detalleCaja.dataset.semantica = msg.semantica;
            ponerIconoG(this.detalleCajaIcono, ICONO_MENSAJE[msg.semantica], 16);
            texto(this.detalleCajaTexto, msg.texto);
        }
        const observada = a.estado === ESTADO.observada && m.acciones.includes("corregir");
        visible(this.detallePista, observada);
        texto(this.detallePista, observada ? t("action.fix.hint") : "");

        const pares: [string, string][] = [
            [t("grid.col.docType"), a.tipoDocumento || "—"],
            [t("panel.field.size"), m.tamanoTexto || "—"],
            [t("panel.field.date"), a.fechaTexto || "—"],
            [t("panel.field.user"), a.usuario || "—"],
        ];
        const nodos: HTMLElement[] = [];
        for (const [k, v] of pares) {
            const dt = el("dt");
            texto(dt, k);
            const dd = el("dd");
            texto(dd, v);
            nodos.push(dt, dd);
        }
        this.detalleDatos.replaceChildren(...nodos);

        const acciones = soloLectura ? m.acciones.filter((x) => x === "ver") : m.acciones;
        const clave = `${acciones.join(",")}|${m.ocupado}|${this.nombre}`;
        if (clave !== this.claveDetalleAcciones) {
            this.claveDetalleAcciones = clave;
            const teniaFoco = this.detalleAcciones.contains(document.activeElement);
            const botones = acciones.map((x, i) =>
                this.boton(x, m.ocupado, i === 0 ? "fu-boton fu-boton--primario" : "fu-boton")
            );
            this.detalleAcciones.replaceChildren(...botones);
            visible(this.detalleAcciones, botones.length > 0);
            if (teniaFoco) (botones[0] ?? this.celdaNombre).focus();
        }
    }
}
