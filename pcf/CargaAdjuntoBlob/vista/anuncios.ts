// Anuncios para lectores de pantalla (Anatomia.md §9): una región polite visualmente oculta y otra assertive solo
// para errores. Nunca más de un anuncio por segundo por archivo: si llegan varios, gana el último.
import { el } from "./dom";

const INTERVALO_MS = 1000;

interface Pendiente {
    texto: string;
    temporizador: number;
}

export class Anunciador {
    public readonly elementos: readonly HTMLElement[];
    private readonly educado: HTMLDivElement;
    private readonly urgente: HTMLDivElement;
    private readonly ultimo = new Map<string, number>();
    private readonly pendientes = new Map<string, Pendiente>();
    private readonly bufferEducado: string[] = [];
    private readonly bufferUrgente: string[] = [];
    private cuadro: number | undefined;

    constructor() {
        this.educado = el("div", "fu-sr", { "aria-live": "polite", "aria-atomic": "true" });
        this.urgente = el("div", "fu-sr", { "aria-live": "assertive", "aria-atomic": "true" });
        this.elementos = [this.educado, this.urgente];
    }

    /**
     * @param clave agrupa los anuncios que comparten límite de frecuencia (el índice del archivo, o "control").
     * @param urgente true solo para errores (región assertive).
     */
    public anunciar(textoAnuncio: string, clave: string, urgente = false): void {
        if (!textoAnuncio) return;
        const k = `${urgente ? "!" : ""}${clave}`;
        const ahora = Date.now();
        const desde = this.ultimo.get(k) ?? 0;
        const espera = desde + INTERVALO_MS - ahora;
        const pendiente = this.pendientes.get(k);
        if (espera <= 0 && !pendiente) {
            this.emitir(textoAnuncio, urgente, k);
            return;
        }
        if (pendiente) {
            pendiente.texto = textoAnuncio;
            return;
        }
        const p: Pendiente = {
            texto: textoAnuncio,
            temporizador: window.setTimeout(() => {
                this.pendientes.delete(k);
                this.emitir(p.texto, urgente, k);
            }, Math.max(0, espera)),
        };
        this.pendientes.set(k, p);
    }

    public limpiar(): void {
        this.pendientes.forEach((p) => window.clearTimeout(p.temporizador));
        this.pendientes.clear();
        this.ultimo.clear();
        if (this.cuadro !== undefined) window.cancelAnimationFrame(this.cuadro);
        this.cuadro = undefined;
        this.bufferEducado.length = 0;
        this.bufferUrgente.length = 0;
        this.educado.textContent = "";
        this.urgente.textContent = "";
    }

    private emitir(textoAnuncio: string, urgente: boolean, k: string): void {
        this.ultimo.set(k, Date.now());
        const buffer = urgente ? this.bufferUrgente : this.bufferEducado;
        // Vaciar ahora y escribir en el cuadro siguiente hace que el lector repita un texto idéntico al anterior.
        // Lo que llega en el mismo cuadro (dos archivos que cambian juntos) se junta en un solo anuncio.
        if (buffer.length === 0) (urgente ? this.urgente : this.educado).textContent = "";
        buffer.push(textoAnuncio);
        if (this.cuadro !== undefined) return;
        this.cuadro = window.requestAnimationFrame(() => {
            this.cuadro = undefined;
            if (this.bufferEducado.length > 0) this.educado.textContent = this.bufferEducado.join(" ");
            if (this.bufferUrgente.length > 0) this.urgente.textContent = this.bufferUrgente.join(" ");
            this.bufferEducado.length = 0;
            this.bufferUrgente.length = 0;
        });
    }
}
