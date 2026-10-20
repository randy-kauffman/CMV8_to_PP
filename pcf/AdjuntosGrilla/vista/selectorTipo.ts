// Selector del tipo de documento del lote (model-driven). La fila del adjunto exige tipodocumento (ADR-003 §1.1):
// el control lo pide antes de crear las filas. Un selector por lote, aplicado a los archivos de la cola que todavía
// no tienen fila (decisión: la vista de carga de CargaAdjuntoBlob se reutiliza sin cambios y no tiene una celda de
// tipo por archivo; el prototipo la muestra por archivo).
import { atributo, el, nuevoId, texto, visible } from "../../CargaAdjuntoBlob/vista/dom";
import { TipoDocumento } from "../dataverse";
import { TraductorGrilla } from "../textosGrilla";

export interface ModeloSelectorTipo {
    readonly tipos: readonly TipoDocumento[];
    readonly cargando: boolean;
    readonly errorCarga: boolean;
    readonly seleccionado: string;
    /** Se intentó subir sin tipo: error en línea (file.docType.required). */
    readonly invalido: boolean;
    readonly deshabilitado: boolean;
}

export class SelectorTipo {
    public readonly elemento: HTMLDivElement;
    private readonly select: HTMLSelectElement;
    private readonly ayuda: HTMLSpanElement;
    private readonly error: HTMLSpanElement;
    private claveOpciones = "";

    constructor(
        private readonly t: TraductorGrilla,
        alCambiar: (id: string) => void
    ) {
        this.elemento = el("div", "fug-tipo");
        const idSelect = nuevoId("gtipo");
        const idAyuda = nuevoId("gtipoayuda");
        const idError = nuevoId("gtipoerror");
        const etiqueta = el("label", "fug-tipo__etiqueta", { for: idSelect });
        texto(etiqueta, t("file.docType.label"));
        this.select = el("select", "fug-select fug-tipo__select", {
            id: idSelect,
            "aria-required": "true",
            "aria-describedby": `${idAyuda} ${idError}`,
            title: t("tooltip.docTypeRequired"),
        });
        this.select.addEventListener("change", () => alCambiar(this.select.value));
        this.ayuda = el("span", "fug-tipo__ayuda", { id: idAyuda });
        this.error = el("span", "fug-tipo__error", { id: idError });
        this.elemento.append(etiqueta, this.select, this.ayuda, this.error);
    }

    public enfocar(): void {
        this.select.focus();
    }

    public actualizar(m: ModeloSelectorTipo): void {
        const t = this.t;
        const clave = JSON.stringify(m.tipos.map((x) => [x.id, x.nombre]));
        if (clave !== this.claveOpciones) {
            this.claveOpciones = clave;
            const vacia = el("option");
            vacia.value = "";
            vacia.textContent = t("file.docType.placeholder");
            const opciones = m.tipos.map((x) => {
                const o = el("option");
                o.value = x.id;
                o.textContent = x.codigo && x.codigo !== x.nombre ? `${x.nombre} (${x.codigo})` : x.nombre;
                return o;
            });
            this.select.replaceChildren(vacia, ...opciones);
        }
        this.select.value = m.seleccionado;
        this.select.disabled = m.deshabilitado || m.cargando || m.tipos.length === 0;
        atributo(this.select, "aria-invalid", m.invalido ? "true" : null);

        let ayuda = t("docType.hint");
        if (m.errorCarga) ayuda = t("docType.loadError");
        else if (!m.cargando && m.tipos.length === 0) ayuda = t("docType.empty");
        texto(this.ayuda, ayuda);
        this.ayuda.dataset.error = m.errorCarga || (!m.cargando && m.tipos.length === 0) ? "true" : "false";
        texto(this.error, m.invalido ? t("file.docType.required") : "");
        visible(this.error, m.invalido);
    }
}
