// ProgressBar (Anatomia.md §5). role="progressbar" con aria-valuenow omitido si es indeterminada,
// aria-valuetext «{sent} de {total}» y aria-labelledby al nombre del archivo.
import { atributo, el, visible } from "./dom";

export type ModoBarra = "oculta" | "determinada" | "indeterminada" | "exito";

export interface ModeloBarra {
    readonly modo: ModoBarra;
    /** 0–100, solo en determinada. */
    readonly valor: number;
    readonly textoValor: string;
    /** Marcas de bloque (variante moderno, ≤ 32 bloques); 0 = sin marcas. */
    readonly bloques: number;
}

/** Tiempo que la barra queda en verde al terminar antes de ocultarse («éxito momentáneo»). */
const EXITO_MS = 300;

export class BarraProgreso {
    public readonly elemento: HTMLDivElement;
    private readonly relleno: HTMLDivElement;
    private readonly marcas: HTMLDivElement;
    private modo: ModoBarra = "oculta";
    private temporizador: number | undefined;

    constructor(idEtiqueta: string) {
        this.elemento = el("div", "fu-progreso", {
            role: "progressbar",
            "aria-valuemin": "0",
            "aria-valuemax": "100",
            "aria-labelledby": idEtiqueta,
        });
        this.relleno = el("div", "fu-progreso__relleno");
        this.marcas = el("div", "fu-progreso__marcas");
        this.elemento.append(this.relleno, this.marcas);
        this.elemento.hidden = true;
    }

    public actualizar(m: ModeloBarra): void {
        const anterior = this.modo;
        this.modo = m.modo;
        if (m.modo === "exito") {
            // Solo se muestra el verde si venía de una barra visible; al recrear la fila, directamente oculta.
            if (anterior === "oculta" || anterior === "exito") {
                if (anterior === "oculta") visible(this.elemento, false);
                return;
            }
            this.elemento.dataset.modo = "exito";
            this.relleno.style.width = "100%";
            atributo(this.elemento, "aria-valuenow", "100");
            window.clearTimeout(this.temporizador);
            this.temporizador = window.setTimeout(() => visible(this.elemento, false), EXITO_MS);
            return;
        }
        window.clearTimeout(this.temporizador);
        visible(this.elemento, m.modo !== "oculta");
        if (m.modo === "oculta") return;
        this.elemento.dataset.modo = m.modo;
        if (m.modo === "indeterminada") {
            atributo(this.elemento, "aria-valuenow", null);
            atributo(this.elemento, "aria-valuetext", null);
            this.relleno.style.width = "";
        } else {
            const v = Math.max(0, Math.min(100, m.valor));
            this.relleno.style.width = `${v}%`;
            atributo(this.elemento, "aria-valuenow", String(Math.floor(v)));
            atributo(this.elemento, "aria-valuetext", m.textoValor || null);
        }
        // Separadores de 1 px en color superficie cada bloque (solo moderno y archivos de ≤ 32 bloques).
        this.marcas.style.backgroundImage =
            m.bloques > 1 && m.bloques <= 32
                ? `repeating-linear-gradient(90deg, transparent 0, transparent calc(${100 / m.bloques}% - 1px), ` +
                  `var(--fu-color-surface) calc(${100 / m.bloques}% - 1px), var(--fu-color-surface) ${100 / m.bloques}%)`
                : "none";
    }

    public destruir(): void {
        window.clearTimeout(this.temporizador);
    }
}
