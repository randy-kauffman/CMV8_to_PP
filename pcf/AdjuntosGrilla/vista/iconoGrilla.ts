// Íconos de la grilla con la misma técnica que vista/dom.ts de CargaAdjuntoBlob (SVG en línea, aria-hidden,
// fill: currentColor), sobre el juego de íconos propio de la grilla.
import { ICONOS_GRILLA, IconoGrilla } from "./iconosGrilla.generado";

const SVG_NS = "http://www.w3.org/2000/svg";

export function iconoG(nombre: IconoGrilla, tamano: number | string, clase?: string): SVGSVGElement {
    const def = ICONOS_GRILLA[nombre];
    const svg = document.createElementNS(SVG_NS, "svg");
    svg.setAttribute("viewBox", def.vb);
    svg.setAttribute("aria-hidden", "true");
    svg.setAttribute("focusable", "false");
    svg.setAttribute("fill", "currentColor");
    const medida = typeof tamano === "number" ? `${tamano}px` : tamano;
    svg.style.width = medida;
    svg.style.height = medida;
    svg.setAttribute("class", clase ? `fu-icono ${clase}` : "fu-icono");
    svg.dataset.icono = nombre;
    for (const d of def.d) {
        const path = document.createElementNS(SVG_NS, "path");
        path.setAttribute("d", d);
        svg.appendChild(path);
    }
    return svg;
}

/** Reemplaza el ícono del contenedor solo si cambió (no reinicia el giro de Procesando en cada repintado). */
export function ponerIconoG(contenedor: Element, nombre: IconoGrilla, tamano: number | string, clase?: string): void {
    const actual = contenedor.firstElementChild;
    if (actual instanceof SVGSVGElement && actual.dataset.icono === nombre) {
        actual.setAttribute("class", clase ? `fu-icono ${clase}` : "fu-icono");
        return;
    }
    contenedor.replaceChildren(iconoG(nombre, tamano, clase));
}
