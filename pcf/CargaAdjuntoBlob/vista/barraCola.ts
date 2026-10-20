// QueueToolbar (Anatomia.md §2): resumen con aria-live polite · espaciador · Limpiar terminados (sutil, si hay
// terminados) · Reintentar fallidos (sutil, si hay errores reintentables) · Subir todo (primario).
import { Traductor } from "../textos";
import { el, texto, visible } from "./dom";

export interface AccionesBarra {
    readonly subirTodo: () => void;
    readonly limpiarTerminados: () => void;
    readonly reintentarFallidos: () => void;
}

export interface ModeloBarraCola {
    readonly titulo: string;
    readonly resumen: string;
    readonly puedeLimpiar: boolean;
    readonly puedeReintentar: boolean;
    readonly puedeSubir: boolean;
    readonly deshabilitada: boolean;
}

export class BarraCola {
    public readonly elemento: HTMLDivElement;
    private readonly titulo: HTMLSpanElement;
    private readonly resumen: HTMLSpanElement;
    private readonly limpiar: HTMLButtonElement;
    private readonly reintentar: HTMLButtonElement;
    private readonly subir: HTMLButtonElement;

    constructor(t: Traductor, acciones: AccionesBarra) {
        this.elemento = el("div", "fu-barra-cola");
        const bloque = el("div", "fu-barra-cola__resumen", { "aria-live": "polite", "aria-atomic": "true" });
        this.titulo = el("span", "fu-barra-cola__titulo");
        this.resumen = el("span", "fu-barra-cola__detalle");
        bloque.append(this.titulo, this.resumen);

        this.limpiar = el("button", "fu-boton fu-boton--sutil fu-barra-cola__boton", { type: "button" });
        this.reintentar = el("button", "fu-boton fu-boton--sutil fu-barra-cola__boton", { type: "button" });
        this.subir = el("button", "fu-boton fu-boton--primario fu-barra-cola__boton fu-barra-cola__subir", {
            type: "button",
        });
        texto(this.limpiar, t("action.clearDone"));
        texto(this.reintentar, t("action.retryFailed"));
        texto(this.subir, t("action.uploadAll"));
        this.limpiar.addEventListener("click", () => acciones.limpiarTerminados());
        this.reintentar.addEventListener("click", () => acciones.reintentarFallidos());
        this.subir.addEventListener("click", () => acciones.subirTodo());

        this.elemento.append(bloque, this.limpiar, this.reintentar, this.subir);
    }

    public actualizar(m: ModeloBarraCola): void {
        texto(this.titulo, m.titulo);
        texto(this.resumen, ` · ${m.resumen}`);
        visible(this.limpiar, m.puedeLimpiar);
        visible(this.reintentar, m.puedeReintentar);
        this.limpiar.disabled = m.deshabilitada;
        this.reintentar.disabled = m.deshabilitada;
        this.subir.disabled = m.deshabilitada || !m.puedeSubir;
    }
}
