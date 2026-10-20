// StatusChip (Anatomia.md §4): <span> no interactivo con ícono aria-hidden + texto; title = lifecycle.*.desc.
// Estilos: tintado, contorno, punteado y fuerte (Observada: tintado con borde fg). Nunca solo color: ícono + texto.
import { atributo, el, texto } from "../../CargaAdjuntoBlob/vista/dom";
import { CodigoEstado, PRESENTACION } from "../estados";
import { TraductorGrilla } from "../textosGrilla";
import { ponerIconoG } from "./iconoGrilla";

export class ChipEstado {
    public readonly elemento: HTMLSpanElement;
    private readonly icono: HTMLSpanElement;
    private readonly etiqueta: HTMLSpanElement;

    constructor(private readonly t: TraductorGrilla) {
        this.elemento = el("span", "fug-chip");
        this.icono = el("span", "fug-chip__icono");
        this.etiqueta = el("span", "fug-chip__texto");
        this.elemento.append(this.icono, this.etiqueta);
    }

    public actualizar(codigo: CodigoEstado | null): void {
        if (codigo === null) {
            // Valor fuera del choice (o columna sin leer): se muestra el guion y nada más.
            this.elemento.dataset.semantica = "neutral";
            this.elemento.dataset.estilo = "contorno";
            this.icono.replaceChildren();
            texto(this.etiqueta, "—");
            atributo(this.elemento, "title", null);
            return;
        }
        const p = PRESENTACION[codigo];
        this.elemento.dataset.semantica = p.semantica;
        this.elemento.dataset.estilo = p.estilo;
        ponerIconoG(this.icono, p.icono, "var(--fu-size-chip-icon)", p.girar ? "fu-girar" : undefined);
        texto(this.etiqueta, this.t(p.clave));
        atributo(this.elemento, "title", this.t(p.descripcion));
    }
}
