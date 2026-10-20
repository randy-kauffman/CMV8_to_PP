// Dropzone (Anatomia.md §1). El contenedor es una región clicable; la única parada de tabulación es el botón
// (evita controles interactivos anidados). Con archivos en la cola se reduce a una línea (versión compacta).
import { Traductor } from "../textos";
import { Variante } from "../tokens";
import { atributo, el, nuevoId, ponerIcono, texto, visible } from "./dom";

export interface ModeloZona {
    readonly compacta: boolean;
    readonly llena: boolean;
    readonly deshabilitada: boolean;
    /** Valor del atributo accept del input (lo que configuró el maker). */
    readonly accept: string;
    readonly tipos: string;
    readonly tamanoMaximo: string;
    readonly maximoArchivos: number;
    readonly cantidad: number;
    readonly variante: Variante;
}

export class ZonaCarga {
    public readonly elemento: HTMLDivElement;
    private readonly icono: HTMLSpanElement;
    private readonly titulo: HTMLSpanElement;
    private readonly pista: HTMLSpanElement;
    private readonly separador: HTMLSpanElement;
    private readonly boton: HTMLButtonElement;
    private readonly iconoCompacto: HTMLSpanElement;
    private readonly enlace: HTMLButtonElement;
    private readonly contador: HTMLSpanElement;
    private readonly input: HTMLInputElement;
    private arrastrando = false;
    private modelo: ModeloZona | null = null;
    private readonly alTeclaGlobal = (e: KeyboardEvent): void => {
        // Esc cancela el estado visual de arrastre (Anatomia.md §10).
        if (e.key === "Escape" && this.arrastrando) this.ponerArrastre(false);
    };

    constructor(
        private readonly t: Traductor,
        private readonly alAgregar: (archivos: File[]) => void
    ) {
        this.elemento = el("div", "fu-zona", { role: "region" });

        this.icono = el("span", "fu-zona__icono");
        const textos = el("div", "fu-zona__textos");
        this.titulo = el("span", "fu-zona__titulo");
        const idPista = nuevoId("pista");
        this.pista = el("span", "fu-zona__pista", { id: idPista });
        textos.append(this.titulo, this.pista);
        this.separador = el("span", "fu-zona__o");
        this.boton = el("button", "fu-boton fu-zona__boton", { type: "button", "aria-describedby": idPista });

        this.iconoCompacto = el("span", "fu-zona__icono-compacto");
        ponerIcono(this.iconoCompacto, "arrow_upload_20_regular", 20);
        this.enlace = el("button", "fu-zona__enlace", { type: "button", "aria-describedby": idPista });
        this.contador = el("span", "fu-zona__contador");

        this.input = el("input", "fu-oculto", { type: "file", tabindex: "-1", "aria-hidden": "true" });
        this.input.multiple = true;

        this.elemento.append(
            this.icono,
            textos,
            this.separador,
            this.boton,
            this.iconoCompacto,
            this.enlace,
            this.contador,
            this.input
        );

        const abrir = (e: Event): void => {
            e.stopPropagation();
            if (this.permiteAgregar()) this.input.click();
        };
        this.boton.addEventListener("click", abrir);
        this.enlace.addEventListener("click", abrir);
        this.elemento.addEventListener("click", abrir);
        this.input.addEventListener("click", (e) => e.stopPropagation());
        this.input.addEventListener("change", () => {
            const archivos = this.input.files ? Array.from(this.input.files) : [];
            // Se limpia para poder volver a elegir el mismo archivo (por ejemplo, después de quitarlo).
            this.input.value = "";
            if (archivos.length > 0) this.alAgregar(archivos);
        });

        this.elemento.addEventListener("dragenter", (e) => this.alArrastrarEncima(e));
        this.elemento.addEventListener("dragover", (e) => this.alArrastrarEncima(e));
        this.elemento.addEventListener("dragleave", (e) => {
            const destino = e.relatedTarget;
            if (!(destino instanceof Node) || !this.elemento.contains(destino)) this.ponerArrastre(false);
        });
        this.elemento.addEventListener("drop", (e) => {
            e.preventDefault();
            this.ponerArrastre(false);
            if (!this.permiteAgregar()) return;
            const archivos = e.dataTransfer?.files ? Array.from(e.dataTransfer.files) : [];
            if (archivos.length > 0) this.alAgregar(archivos);
        });
        window.addEventListener("keydown", this.alTeclaGlobal);
    }

    /** Botón que recibe el foco cuando la fila enfocada desaparece y no queda otra a la cual ir. */
    public enfocar(): void {
        const destino = this.modelo?.compacta ? this.enlace : this.boton;
        if (!destino.disabled) destino.focus();
    }

    public actualizar(m: ModeloZona): void {
        this.modelo = m;
        const t = this.t;
        const bloqueada = m.deshabilitada || m.llena;
        this.elemento.classList.toggle("fu-zona--compacta", m.compacta);
        atributo(this.elemento, "data-arrastrando", this.arrastrando ? "true" : null);
        atributo(this.elemento, "data-bloqueada", bloqueada ? "true" : null);
        atributo(this.elemento, "aria-disabled", m.deshabilitada ? "true" : null);
        atributo(this.elemento, "aria-label", t("dropzone.aria", { types: m.tipos, maxSize: m.tamanoMaximo }));
        this.input.accept = m.accept;

        // Moderno: ícono de 32 en un círculo; institucional: 20; fluent: 24 (Anatomia.md §1, parte 2).
        const tam = m.variante === "moderno" ? 32 : m.variante === "institucional" ? 20 : 24;
        ponerIcono(this.icono, m.variante === "institucional" ? "arrow_upload_20_regular" : "arrow_upload_24_regular", tam);

        let titulo: string;
        if (this.arrastrando) titulo = t("dropzone.dragActive");
        else if (m.deshabilitada) titulo = t("dropzone.disabled");
        else if (m.llena) titulo = t("dropzone.full", { maxFiles: m.maximoArchivos });
        else titulo = m.compacta ? t("dropzone.compact") : t("dropzone.title");

        // Si el maker no restringió tipos, {types} queda vacío y se evita la pista que empieza con «·».
        const pista = t("dropzone.hint", { types: m.tipos, maxSize: m.tamanoMaximo, maxFiles: m.maximoArchivos });
        texto(this.pista, pista.replace(/^\s*·\s*/, ""));
        texto(this.separador, t("dropzone.or"));
        texto(this.boton, t("dropzone.browse"));
        this.boton.disabled = bloqueada;

        if (m.compacta) {
            texto(this.titulo, "");
            texto(this.enlace, titulo);
            this.enlace.disabled = bloqueada;
            texto(this.contador, t("dropzone.compactCount", { count: m.cantidad, maxFiles: m.maximoArchivos }));
        } else {
            texto(this.titulo, titulo);
            texto(this.enlace, "");
        }
        visible(this.contador, m.compacta);
    }

    public destruir(): void {
        window.removeEventListener("keydown", this.alTeclaGlobal);
    }

    private permiteAgregar(): boolean {
        return this.modelo !== null && !this.modelo.deshabilitada && !this.modelo.llena;
    }

    private alArrastrarEncima(e: DragEvent): void {
        e.preventDefault();
        const permite = this.permiteAgregar();
        if (e.dataTransfer) e.dataTransfer.dropEffect = permite ? "copy" : "none";
        if (permite) this.ponerArrastre(true);
    }

    private ponerArrastre(valor: boolean): void {
        if (this.arrastrando === valor) return;
        this.arrastrando = valor;
        if (this.modelo) this.actualizar(this.modelo);
    }
}
