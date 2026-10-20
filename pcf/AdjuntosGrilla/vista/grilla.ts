// GridToolbar + AttachmentGrid (Anatomia.md §6 y §7) con detalle en acordeón (§8 simplificado).
// Teclado (§10): la grilla es una sola parada de tabulación; flechas entre celdas y botones (roving tabindex),
// Inicio/Fin, Ctrl+Inicio/Fin, Enter o Espacio sobre una celda abre el detalle, Esc lo cierra o cierra el menú.
import { atributo, el, nuevoId, texto, visible } from "../../CargaAdjuntoBlob/vista/dom";
import { AccionAdjunto, CLAVE_ACCION, CLAVE_GRUPO, GrupoFiltro, GRUPOS } from "../estados";
import { Orden } from "../modeloGrilla";
import { TraductorGrilla } from "../textosGrilla";
import { FilaGrilla, FilaVista } from "./filaGrilla";
import { iconoG } from "./iconoGrilla";

export type DensidadGrilla = "compacta" | "comoda";
export type Disposicion = "ancho" | "medio" | "tarjetas";

/** Puntos de quiebre del contenedor para la grilla (Anatomia.md §7): ≥ 900, 600–899 y < 600 (tarjetas). */
export function disposicionDe(px: number): Disposicion {
    if (px < 600) return "tarjetas";
    if (px < 900) return "medio";
    return "ancho";
}

export interface ModeloGrillaVista {
    readonly carga: "cargando" | "error" | "listo";
    readonly filas: readonly FilaVista[];
    /** Adjuntos cargados (sin filtrar). */
    readonly total: number;
    readonly conteos: Readonly<Record<GrupoFiltro, number>>;
    readonly filtro: GrupoFiltro;
    readonly orden: Orden;
    readonly densidad: DensidadGrilla;
    readonly hayMas: boolean;
    readonly soloLectura: boolean;
}

export interface ManejadoresGrilla {
    readonly filtrar: (g: GrupoFiltro) => void;
    readonly ordenar: (o: Orden) => void;
    readonly densidad: (d: DensidadGrilla) => void;
    readonly accion: (a: AccionAdjunto, id: string) => void;
    readonly alternarDetalle: (id: string) => void;
    readonly cargarMas: () => void;
    readonly refrescar: () => void;
}

const ORDENES: readonly [Orden, "grid.sort.dateDesc" | "grid.sort.dateAsc" | "grid.sort.status"][] = [
    ["fechaDesc", "grid.sort.dateDesc"],
    ["fechaAsc", "grid.sort.dateAsc"],
    ["estado", "grid.sort.status"],
];

function opcion(valor: string, etiqueta: string): HTMLOptionElement {
    const o = el("option");
    o.value = valor;
    o.textContent = etiqueta;
    return o;
}

export class Grilla {
    public readonly elemento: HTMLElement;
    private readonly titulo: HTMLSpanElement;
    private readonly contador: HTMLSpanElement;
    private readonly grupoFiltros: HTMLDivElement;
    private readonly botonesFiltro = new Map<GrupoFiltro, { boton: HTMLButtonElement; cuenta: HTMLSpanElement; texto: HTMLSpanElement }>();
    private readonly selectorFiltro: HTMLSelectElement;
    private readonly selectorOrden: HTMLSelectElement;
    private readonly selectorDensidad: HTMLSelectElement;
    private readonly grid: HTMLDivElement;
    private readonly encabezado: HTMLDivElement;
    private readonly encabezadoEstado: HTMLDivElement;
    private readonly encabezadoFecha: HTMLDivElement;
    private readonly iconoOrdenEstado: HTMLSpanElement;
    private readonly iconoOrdenFecha: HTMLSpanElement;
    private readonly cuerpo: HTMLDivElement;
    private readonly vacio: HTMLDivElement;
    private readonly vacioTitulo: HTMLSpanElement;
    private readonly vacioCuerpo: HTMLSpanElement;
    private readonly vacioBoton: HTMLButtonElement;
    private readonly cargarMas: HTMLButtonElement;
    private readonly menu: HTMLDivElement;
    private readonly filas = new Map<string, FilaGrilla>();
    private orden: readonly FilaGrilla[] = [];
    private modelo: ModeloGrillaVista | null = null;
    /** Posición del foco con flechas: fila (id) y columna (índice entre los objetivos visibles). */
    private activa: { id: string; columna: number } | null = null;
    private menuDe: FilaGrilla | null = null;
    private claveFiltros = "";
    private readonly observador: ResizeObserver | null;

    private readonly alClicFuera = (e: MouseEvent): void => {
        if (this.menuDe && e.target instanceof Node && !this.menu.contains(e.target)) this.cerrarMenu(false);
    };

    constructor(
        private readonly t: TraductorGrilla,
        private readonly manejadores: ManejadoresGrilla
    ) {
        this.elemento = el("section", "fug-grilla");

        // ------------------------------------------------ GridToolbar (§6)
        const barra = el("div", "fug-barra");
        const idTitulo = nuevoId("gtitulo");
        const encabezadoBarra = el("div", "fug-barra__titulo");
        this.titulo = el("span", "fug-barra__nombre", { id: idTitulo });
        this.contador = el("span", "fug-barra__contador");
        encabezadoBarra.append(this.titulo, this.contador);

        this.grupoFiltros = el("div", "fug-filtros", { role: "radiogroup", "aria-label": t("grid.filter.label") });
        for (const g of GRUPOS) {
            const boton = el("button", "fug-filtro", { type: "button", role: "radio", "data-grupo": g });
            const textoFiltro = el("span");
            const cuenta = el("span", "fug-filtro__cuenta");
            boton.append(textoFiltro, cuenta);
            boton.addEventListener("click", () => this.manejadores.filtrar(g));
            this.botonesFiltro.set(g, { boton, cuenta, texto: textoFiltro });
            this.grupoFiltros.appendChild(boton);
        }
        this.grupoFiltros.addEventListener("keydown", (e) => this.teclaFiltros(e));

        this.selectorFiltro = el("select", "fug-select fug-filtro-select", { "aria-label": t("grid.filter.label") });
        this.selectorFiltro.addEventListener("change", () => this.manejadores.filtrar(this.selectorFiltro.value as GrupoFiltro));

        const espaciador = el("span", "fug-barra__espacio");

        const etiquetaOrden = el("label", "fug-barra__control");
        etiquetaOrden.appendChild(iconoG("arrow_sort_20_regular", 16));
        const textoOrden = el("span");
        texto(textoOrden, t("grid.sort.label"));
        this.selectorOrden = el("select", "fug-select");
        for (const [valor, clave] of ORDENES) this.selectorOrden.appendChild(opcion(valor, t(clave)));
        this.selectorOrden.addEventListener("change", () => this.manejadores.ordenar(this.selectorOrden.value as Orden));
        etiquetaOrden.append(textoOrden, this.selectorOrden);

        const etiquetaDensidad = el("label", "fug-barra__control");
        etiquetaDensidad.appendChild(iconoG("text_density_20_regular", 16));
        const textoDensidad = el("span");
        texto(textoDensidad, t("grid.density.label"));
        this.selectorDensidad = el("select", "fug-select");
        this.selectorDensidad.append(
            opcion("compacta", t("grid.density.compact")),
            opcion("comoda", t("grid.density.comfortable"))
        );
        this.selectorDensidad.addEventListener("change", () =>
            this.manejadores.densidad(this.selectorDensidad.value === "compacta" ? "compacta" : "comoda")
        );
        etiquetaDensidad.append(textoDensidad, this.selectorDensidad);

        const refrescar = el("button", "fu-boton-icono", {
            type: "button",
            "aria-label": t("grid.refresh"),
            title: t("grid.refresh"),
        });
        refrescar.appendChild(iconoG("arrow_clockwise_20_regular", 20));
        refrescar.addEventListener("click", () => this.manejadores.refrescar());

        barra.append(encabezadoBarra, this.grupoFiltros, this.selectorFiltro, espaciador, etiquetaOrden, etiquetaDensidad, refrescar);

        // ------------------------------------------------ AttachmentGrid (§7)
        this.grid = el("div", "fug-tabla", { role: "grid", "aria-label": t("grid.aria") });
        this.encabezado = el("div", "fug-fila fug-encabezado", { role: "row" });
        const columna = (clave: Parameters<TraductorGrilla>[0], clase: string): HTMLDivElement => {
            const c = el("div", `fug-celda ${clase}`, { role: "columnheader" });
            texto(c, t(clave));
            return c;
        };
        this.encabezadoEstado = el("div", "fug-celda fug-c-estado", { role: "columnheader" });
        this.encabezadoFecha = el("div", "fug-celda fug-c-fecha", { role: "columnheader" });
        this.iconoOrdenEstado = el("span", "fug-orden-icono");
        this.iconoOrdenFecha = el("span", "fug-orden-icono");
        this.encabezadoEstado.append(this.botonOrden(t("grid.col.status"), this.iconoOrdenEstado, "estado"));
        this.encabezadoFecha.append(this.botonOrden(t("grid.col.date"), this.iconoOrdenFecha, "fecha"));
        // Acciones abarca la acción principal y el menú (dos celdas por fila); el título es solo para lectores.
        const acciones = el("div", "fug-celda fug-c-acciones", { role: "columnheader", "aria-colspan": "2" });
        const accionesTexto = el("span", "fu-sr");
        acciones.appendChild(accionesTexto);
        texto(accionesTexto, t("grid.col.actions"));
        this.encabezado.append(
            columna("grid.col.name", "fug-c-nombre"),
            columna("grid.col.docType", "fug-c-tipo"),
            this.encabezadoEstado,
            columna("grid.col.size", "fug-c-tamano"),
            this.encabezadoFecha,
            columna("grid.col.user", "fug-c-usuario"),
            acciones
        );
        this.cuerpo = el("div", "fug-cuerpo", { role: "rowgroup" });
        this.grid.append(this.encabezado, this.cuerpo);
        this.grid.addEventListener("keydown", (e) => this.teclaGrilla(e));
        this.grid.addEventListener("focusin", (e) => this.alEnfocar(e));

        this.vacio = el("div", "fug-vacio");
        const vacioIcono = el("span", "fug-vacio__icono");
        vacioIcono.appendChild(iconoG("attach_20_regular", 24));
        this.vacioTitulo = el("span", "fug-vacio__titulo");
        this.vacioCuerpo = el("span", "fug-vacio__cuerpo");
        this.vacioBoton = el("button", "fu-boton", { type: "button" });
        this.vacioBoton.addEventListener("click", () => {
            if (this.modelo?.carga === "error") this.manejadores.refrescar();
            else this.manejadores.filtrar("todos");
        });
        this.vacio.append(vacioIcono, this.vacioTitulo, this.vacioCuerpo, this.vacioBoton);

        this.cargarMas = el("button", "fu-boton fug-cargar-mas", { type: "button" });
        texto(this.cargarMas, t("grid.loadMore"));
        this.cargarMas.addEventListener("click", () => this.manejadores.cargarMas());

        this.menu = el("div", "fug-menu-lista", { role: "menu" });
        this.menu.hidden = true;
        this.menu.addEventListener("keydown", (e) => this.teclaMenu(e));

        this.elemento.append(barra, this.grid, this.vacio, this.cargarMas, this.menu);

        this.elemento.dataset.disposicion = "ancho";
        this.observador =
            typeof ResizeObserver === "function"
                ? new ResizeObserver((entradas) => {
                      const w = entradas[0]?.contentRect.width;
                      if (w !== undefined && w > 0) this.ponerAncho(w);
                  })
                : null;
        this.observador?.observe(this.elemento);
        document.addEventListener("mousedown", this.alClicFuera, true);
    }

    public ponerAncho(px: number): void {
        const d = disposicionDe(px);
        if (this.elemento.dataset.disposicion !== d) {
            this.elemento.dataset.disposicion = d;
            this.aplicarRoving(false);
        }
    }

    public pintar(m: ModeloGrillaVista): void {
        this.modelo = m;
        const t = this.t;
        texto(this.titulo, t("grid.title"));
        texto(this.contador, m.total > 0 ? t("grid.count", { count: m.total }) : "");
        this.elemento.dataset.densidad = m.densidad;

        // Filtros: botones conmutables (≥ 600) o desplegable (< 600), los dos con el número por grupo.
        // Las opciones se rehacen solo si cambian los números: rehacerlas cierra el desplegable si estaba abierto.
        const claveFiltros = GRUPOS.map((g) => m.conteos[g]).join(",");
        if (claveFiltros !== this.claveFiltros) {
            this.claveFiltros = claveFiltros;
            this.selectorFiltro.replaceChildren(
                ...GRUPOS.map((g) => opcion(g, `${t(CLAVE_GRUPO[g])} (${m.conteos[g]})`))
            );
        }
        this.selectorFiltro.value = m.filtro;
        for (const [g, b] of this.botonesFiltro) {
            const marcado = g === m.filtro;
            texto(b.texto, t(CLAVE_GRUPO[g]));
            texto(b.cuenta, String(m.conteos[g]));
            atributo(b.boton, "aria-checked", marcado ? "true" : "false");
            b.boton.tabIndex = marcado ? 0 : -1;
        }
        this.selectorOrden.value = m.orden;
        this.selectorDensidad.value = m.densidad;

        // Indicadores de orden en el encabezado (aria-sort solo en la columna ordenada).
        atributo(this.encabezadoEstado, "aria-sort", m.orden === "estado" ? "ascending" : null);
        atributo(this.encabezadoFecha, "aria-sort", m.orden === "fechaDesc" ? "descending" : m.orden === "fechaAsc" ? "ascending" : null);
        this.iconoOrdenEstado.replaceChildren(...(m.orden === "estado" ? [iconoG("arrow_sort_down_16_regular", 12)] : []));
        this.iconoOrdenFecha.replaceChildren(
            ...(m.orden === "fechaDesc"
                ? [iconoG("arrow_sort_down_16_regular", 12)]
                : m.orden === "fechaAsc"
                  ? [iconoG("arrow_sort_up_16_regular", 12)]
                  : [])
        );

        this.pintarFilas(m);

        const sinFilas = m.filas.length === 0;
        visible(this.grid, !sinFilas);
        visible(this.vacio, sinFilas);
        if (sinFilas) {
            if (m.carga === "cargando") {
                texto(this.vacioTitulo, t("grid.loading"));
                texto(this.vacioCuerpo, "");
                visible(this.vacioBoton, false);
            } else if (m.carga === "error") {
                texto(this.vacioTitulo, t("grid.loadError"));
                texto(this.vacioCuerpo, "");
                texto(this.vacioBoton, t("grid.refresh"));
                visible(this.vacioBoton, true);
            } else if (m.total === 0) {
                texto(this.vacioTitulo, t("grid.empty.title"));
                texto(this.vacioCuerpo, m.soloLectura ? "" : t("grid.empty.body"));
                visible(this.vacioBoton, false);
            } else {
                texto(this.vacioTitulo, t("grid.filterEmpty"));
                texto(this.vacioCuerpo, "");
                texto(this.vacioBoton, t("grid.clearFilter"));
                visible(this.vacioBoton, true);
            }
        }
        visible(this.cargarMas, m.hayMas);
    }

    public destruir(): void {
        this.observador?.disconnect();
        document.removeEventListener("mousedown", this.alClicFuera, true);
        this.filas.clear();
        this.elemento.remove();
    }

    // ------------------------------------------------------------ filas

    private pintarFilas(m: ModeloGrillaVista): void {
        const activo = document.activeElement;
        const focoEnGrilla = activo instanceof HTMLElement && this.grid.contains(activo);
        const ordenPrevio = this.orden.map((f) => f.id);

        const presentes = new Set<string>();
        const nuevas: FilaGrilla[] = [];
        let anterior: ChildNode | null = null;
        let indice = 2; // aria-rowindex: el encabezado es la fila 1
        for (const fv of m.filas) {
            const id = fv.adjunto.id;
            presentes.add(id);
            let fila = this.filas.get(id);
            if (!fila) {
                fila = new FilaGrilla(this.t, fv.adjunto, {
                    accion: (a, i) => this.manejadores.accion(a, i),
                    alternarDetalle: (i) => this.manejadores.alternarDetalle(i),
                    abrirMenu: (f, b) => this.abrirMenu(f, b),
                });
                this.filas.set(id, fila);
            }
            fila.actualizar(fv, m.soloLectura);
            atributo(fila.elemento, "aria-rowindex", String(indice++));
            if (fv.expandida) atributo(fila.detalle, "aria-rowindex", String(indice++));
            const esperado: ChildNode | null = anterior ? anterior.nextSibling : this.cuerpo.firstChild;
            if (fila.elemento !== esperado) this.cuerpo.insertBefore(fila.elemento, esperado);
            if (fila.detalle.previousSibling !== fila.elemento) fila.elemento.after(fila.detalle);
            anterior = fila.detalle;
            nuevas.push(fila);
        }
        for (const [id, fila] of Array.from(this.filas.entries())) {
            if (presentes.has(id)) continue;
            if (this.menuDe === fila) this.cerrarMenu(false);
            fila.elemento.remove();
            fila.detalle.remove();
            this.filas.delete(id);
        }
        this.orden = nuevas;
        atributo(this.grid, "aria-rowcount", String(indice - 1));

        // La fila activa desapareció (filtro, refresco): pasa a la siguiente, o a la anterior.
        if (this.activa && !this.filas.has(this.activa.id)) {
            const pos = ordenPrevio.indexOf(this.activa.id);
            const candidatos = [...ordenPrevio.slice(pos + 1), ...ordenPrevio.slice(0, Math.max(0, pos)).reverse()];
            const siguiente = candidatos.find((id) => this.filas.has(id)) ?? this.orden[0]?.id;
            this.activa = siguiente ? { id: siguiente, columna: 0 } : null;
            this.aplicarRoving(focoEnGrilla);
        } else {
            this.aplicarRoving(focoEnGrilla && !(activo instanceof HTMLElement && activo.isConnected));
        }
    }

    // ------------------------------------------------------------ roving tabindex

    private filaDe(nodo: Node): FilaGrilla | undefined {
        return this.orden.find((f) => f.elemento.contains(nodo));
    }

    private alEnfocar(e: FocusEvent): void {
        const destino = e.target;
        if (!(destino instanceof HTMLElement)) return;
        const fila = this.filaDe(destino);
        if (!fila) return;
        const columna = fila.objetivos().indexOf(destino);
        if (columna < 0) return;
        this.activa = { id: fila.id, columna };
        this.aplicarRoving(false);
    }

    /** Deja tabindex=0 solo en el objetivo activo (o en la primera celda) y, si se pide, lo enfoca. */
    private aplicarRoving(enfocar: boolean): void {
        if (!this.activa && this.orden.length > 0) this.activa = { id: this.orden[0].id, columna: 0 };
        let objetivo: HTMLElement | null = null;
        for (const fila of this.orden) {
            const objetivos = fila.objetivos();
            for (const o of Array.from(fila.elemento.querySelectorAll<HTMLElement>("[role=gridcell], button"))) {
                o.tabIndex = -1;
            }
            if (this.activa && fila.id === this.activa.id) {
                const c = Math.min(this.activa.columna, objetivos.length - 1);
                objetivo = objetivos[Math.max(0, c)] ?? null;
            }
        }
        if (objetivo) {
            objetivo.tabIndex = 0;
            if (enfocar) objetivo.focus();
        }
    }

    private mover(fila: number, columna: number): void {
        if (this.orden.length === 0) return;
        const f = Math.max(0, Math.min(this.orden.length - 1, fila));
        const objetivos = this.orden[f].objetivos();
        const c = Math.max(0, Math.min(objetivos.length - 1, columna));
        this.activa = { id: this.orden[f].id, columna: c };
        this.aplicarRoving(true);
    }

    private teclaGrilla(e: KeyboardEvent): void {
        const destino = e.target;
        if (!(destino instanceof HTMLElement)) return;
        const fila = this.filaDe(destino);
        if (!fila) {
            // Dentro del detalle: Esc lo cierra y devuelve el foco a la fila.
            if (e.key === "Escape") {
                const duena = this.orden.find((f) => f.detalle.contains(destino));
                if (duena) {
                    e.preventDefault();
                    this.activa = { id: duena.id, columna: 0 };
                    this.manejadores.alternarDetalle(duena.id);
                    duena.celdaNombre.focus();
                }
            }
            return;
        }
        const f = this.orden.indexOf(fila);
        const objetivos = fila.objetivos();
        const c = Math.max(0, objetivos.indexOf(destino));
        const esBoton = destino instanceof HTMLButtonElement;
        switch (e.key) {
            case "ArrowRight":
                this.mover(f, c + 1);
                break;
            case "ArrowLeft":
                this.mover(f, c - 1);
                break;
            case "ArrowDown":
                this.mover(f + 1, c);
                break;
            case "ArrowUp":
                this.mover(f - 1, c);
                break;
            case "PageDown":
                this.mover(f + 5, c);
                break;
            case "PageUp":
                this.mover(f - 5, c);
                break;
            case "Home":
                this.mover(e.ctrlKey ? 0 : f, 0);
                break;
            case "End":
                this.mover(e.ctrlKey ? this.orden.length - 1 : f, e.ctrlKey ? c : objetivos.length - 1);
                break;
            case "Enter":
            case " ":
                if (esBoton) return;
                this.manejadores.alternarDetalle(fila.id);
                break;
            case "Escape":
                if (fila.elemento.getAttribute("aria-expanded") !== "true") return;
                this.manejadores.alternarDetalle(fila.id);
                break;
            default:
                return;
        }
        e.preventDefault();
    }

    private teclaFiltros(e: KeyboardEvent): void {
        const actual = GRUPOS.indexOf(this.modelo?.filtro ?? "todos");
        let siguiente = -1;
        if (e.key === "ArrowRight" || e.key === "ArrowDown") siguiente = (actual + 1) % GRUPOS.length;
        else if (e.key === "ArrowLeft" || e.key === "ArrowUp") siguiente = (actual - 1 + GRUPOS.length) % GRUPOS.length;
        else if (e.key === "Home") siguiente = 0;
        else if (e.key === "End") siguiente = GRUPOS.length - 1;
        if (siguiente < 0) return;
        e.preventDefault();
        const g = GRUPOS[siguiente];
        this.manejadores.filtrar(g);
        this.botonesFiltro.get(g)?.boton.focus();
    }

    private botonOrden(etiqueta: string, icono: HTMLSpanElement, columna: "estado" | "fecha"): HTMLButtonElement {
        const b = el("button", "fug-orden", { type: "button" });
        const s = el("span");
        texto(s, etiqueta);
        b.append(s, icono);
        b.addEventListener("click", () => {
            const actual = this.modelo?.orden ?? "fechaDesc";
            if (columna === "estado") this.manejadores.ordenar("estado");
            else this.manejadores.ordenar(actual === "fechaDesc" ? "fechaAsc" : "fechaDesc");
        });
        return b;
    }

    // ------------------------------------------------------------ menú «Más acciones»

    private abrirMenu(fila: FilaGrilla, boton: HTMLButtonElement): void {
        if (this.menuDe === fila) {
            this.cerrarMenu(true);
            return;
        }
        this.cerrarMenu(false);
        const soloLectura = this.modelo?.soloLectura === true;
        const disponibles = soloLectura ? fila.acciones.filter((a) => a === "ver") : fila.acciones;
        // La principal ya está a la vista como botón; el menú lleva las demás y «Ver detalle».
        const secundarias = disponibles.slice(1);
        const items: HTMLButtonElement[] = [];
        for (const a of secundarias) {
            items.push(this.itemMenu(this.t(CLAVE_ACCION[a]), () => this.manejadores.accion(a, fila.id)));
        }
        const expandida = fila.elemento.getAttribute("aria-expanded") === "true";
        items.push(
            this.itemMenu(expandida ? this.t("panel.close") : this.t("action.details"), () =>
                this.manejadores.alternarDetalle(fila.id)
            )
        );
        this.menu.replaceChildren(...items);
        atributo(this.menu, "aria-label", `${this.t("action.more")}: ${fila.nombre}`);
        this.menuDe = fila;
        atributo(boton, "aria-expanded", "true");
        this.menu.hidden = false;
        const caja = this.elemento.getBoundingClientRect();
        const b = boton.getBoundingClientRect();
        this.menu.style.top = `${b.bottom - caja.top + 4}px`;
        this.menu.style.right = `${Math.max(0, caja.right - b.right)}px`;
        items[0]?.focus();
    }

    private itemMenu(etiqueta: string, ejecutar: () => void): HTMLButtonElement {
        const b = el("button", "fug-menu-item", { type: "button", role: "menuitem", tabindex: "-1" });
        texto(b, etiqueta);
        b.addEventListener("click", () => {
            this.cerrarMenu(true);
            ejecutar();
        });
        return b;
    }

    private cerrarMenu(devolverFoco: boolean): void {
        const fila = this.menuDe;
        if (!fila) return;
        this.menuDe = null;
        this.menu.hidden = true;
        this.menu.replaceChildren();
        atributo(fila.botonDelMenu, "aria-expanded", "false");
        if (devolverFoco && fila.botonDelMenu.isConnected) fila.botonDelMenu.focus();
    }

    private teclaMenu(e: KeyboardEvent): void {
        const items = Array.from(this.menu.querySelectorAll<HTMLButtonElement>("[role=menuitem]"));
        const i = items.indexOf(document.activeElement as HTMLButtonElement);
        if (e.key === "Escape" || e.key === "Tab") {
            e.preventDefault();
            this.cerrarMenu(true);
        } else if (e.key === "ArrowDown") {
            e.preventDefault();
            items[(i + 1) % items.length]?.focus();
        } else if (e.key === "ArrowUp") {
            e.preventDefault();
            items[(i - 1 + items.length) % items.length]?.focus();
        } else if (e.key === "Home") {
            e.preventDefault();
            items[0]?.focus();
        } else if (e.key === "End") {
            e.preventDefault();
            items[items.length - 1]?.focus();
        }
    }
}
