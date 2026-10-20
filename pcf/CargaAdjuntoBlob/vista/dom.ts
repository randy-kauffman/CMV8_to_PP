// Utilidades mínimas de DOM. Sin innerHTML: todo se arma con createElement/createElementNS para no abrir
// la puerta a inyección (los nombres de archivo vienen del usuario) y para respetar la CSP del host.
import { ICONOS, NombreIcono } from "./iconos.generado";

const SVG_NS = "http://www.w3.org/2000/svg";

export function el<K extends keyof HTMLElementTagNameMap>(
    etiqueta: K,
    clase?: string,
    atributos?: Readonly<Record<string, string>>
): HTMLElementTagNameMap[K] {
    const e = document.createElement(etiqueta);
    if (clase) e.className = clase;
    if (atributos) for (const [k, v] of Object.entries(atributos)) e.setAttribute(k, v);
    return e;
}

/** Ícono Fluent en línea, decorativo (aria-hidden) y con fill: currentColor para heredar el color de semántica. */
export function icono(nombre: NombreIcono, tamano: number | string, clase?: string): SVGSVGElement {
    const def = ICONOS[nombre];
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

/** Reemplaza el ícono dentro de un contenedor solo si cambió (evita reiniciar animaciones en cada repintado). */
export function ponerIcono(contenedor: Element, nombre: NombreIcono, tamano: number | string, clase?: string): void {
    const actual = contenedor.firstElementChild;
    if (actual instanceof SVGSVGElement && actual.dataset.icono === nombre) {
        actual.setAttribute("class", clase ? `fu-icono ${clase}` : "fu-icono");
        return;
    }
    contenedor.replaceChildren(icono(nombre, tamano, clase));
}

/** Escribe texto solo si cambió: menos trabajo de layout y no re-dispara lectores de pantalla. */
export function texto(e: Element, valor: string): void {
    if (e.textContent !== valor) e.textContent = valor;
}

export function atributo(e: Element, nombre: string, valor: string | null): void {
    if (valor === null) {
        if (e.hasAttribute(nombre)) e.removeAttribute(nombre);
    } else if (e.getAttribute(nombre) !== valor) {
        e.setAttribute(nombre, valor);
    }
}

export function visible(e: HTMLElement, mostrar: boolean): void {
    if (e.hidden === mostrar) e.hidden = !mostrar;
}

let secuenciaIds = 0;
/** Ids únicos por instancia de control (puede haber varios controles en la misma pantalla). */
export function nuevoId(prefijo: string): string {
    secuenciaIds++;
    return `fu-${prefijo}-${secuenciaIds.toString(36)}`;
}

export function prefiereMenosMovimiento(): boolean {
    return typeof window.matchMedia === "function" && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}
