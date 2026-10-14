import { IInputs, IOutputs } from "./generated/ManifestTypes";
import { solicitarSasSubida, ErrorBroker } from "./brokerSas";
import { subirEnBloques, ErrorSubida } from "./subidaBloques";

type Estado =
    | "inactivo"
    | "solicitandoSas"
    | "subiendo"
    | "confirmando"
    | "completado"
    | "error";

const REGEX_GUID = /^\{?[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\}?$/i;

const TEXTO_ESTADO: Record<Estado, string> = {
    inactivo: "Elegí un archivo y presioná Subir.",
    solicitandoSas: "Solicitando autorización de subida...",
    subiendo: "Subiendo archivo...",
    confirmando: "Confirmando la subida...",
    completado: "Subida completada.",
    error: "Error en la subida.",
};

export class CargaAdjuntoBlob implements ComponentFramework.StandardControl<IInputs, IOutputs> {
    private _notificar: () => void = () => undefined;
    private _contexto?: ComponentFramework.Context<IInputs>;

    // DOM
    private _inputArchivo!: HTMLInputElement;
    private _boton!: HTMLButtonElement;
    private _barra!: HTMLDivElement;
    private _relleno!: HTMLDivElement;
    private _linea!: HTMLDivElement;

    // Estado / salidas
    private _estado: Estado = "inactivo";
    private _progreso = 0;
    private _rutaBlob = "";
    private _nombreArchivo = "";
    private _tamanoBytes = 0;
    private _contentType = "";
    private _mensajeError = "";
    private _duracionMs = 0;
    private _velocidadMbps = 0;
    private _velocidadPromedioMbps = 0;

    // Muestras (instante, bytes confirmados) para la velocidad instantánea en ventana de 3 s.
    private _muestras: { t: number; bytes: number }[] = [];

    private _inicioMs = 0;
    private _abort?: AbortController;
    private _ultimoDisparador: number | null | undefined;
    private _primerUpdate = true;

    public init(
        context: ComponentFramework.Context<IInputs>,
        notifyOutputChanged: () => void,
        _state: ComponentFramework.Dictionary,
        container: HTMLDivElement
    ): void {
        this._notificar = notifyOutputChanged;
        this._contexto = context;

        const raiz = document.createElement("div");
        raiz.className = "banco-carga";

        this._inputArchivo = document.createElement("input");
        this._inputArchivo.type = "file";
        this._inputArchivo.className = "banco-carga__archivo";
        this._inputArchivo.setAttribute("aria-label", "Archivo a subir");

        this._boton = document.createElement("button");
        this._boton.type = "button";
        this._boton.className = "banco-carga__boton";
        this._boton.textContent = "Subir";
        this._boton.addEventListener("click", () => {
            void this.iniciarSubida();
        });

        this._barra = document.createElement("div");
        this._barra.className = "banco-carga__barra";
        this._barra.setAttribute("role", "progressbar");
        this._barra.setAttribute("aria-valuemin", "0");
        this._barra.setAttribute("aria-valuemax", "100");
        this._relleno = document.createElement("div");
        this._relleno.className = "banco-carga__relleno";
        this._barra.appendChild(this._relleno);

        this._linea = document.createElement("div");
        this._linea.className = "banco-carga__estado";
        this._linea.setAttribute("role", "status");
        this._linea.setAttribute("aria-live", "polite");

        raiz.append(this._inputArchivo, this._boton, this._barra, this._linea);
        container.appendChild(raiz);

        this.pintar();
    }

    public updateView(context: ComponentFramework.Context<IInputs>): void {
        this._contexto = context;

        // Reinicio: se dispara al cambiar el valor (no en la primera lectura).
        const disparador = context.parameters.disparadorReinicio.raw;
        if (this._primerUpdate) {
            this._ultimoDisparador = disparador;
            this._primerUpdate = false;
        } else if (disparador !== this._ultimoDisparador) {
            this._ultimoDisparador = disparador;
            this.reiniciar();
        }

        this._inputArchivo.accept = context.parameters.tiposAceptados.raw ?? "video/*,application/pdf";
        this.pintar();
    }

    public getOutputs(): IOutputs {
        return {
            estado: this._estado,
            progreso: this._progreso,
            rutaBlob: this._rutaBlob,
            nombreArchivo: this._nombreArchivo,
            tamanoBytes: this._tamanoBytes,
            contentType: this._contentType,
            mensajeError: this._mensajeError,
            duracionMs: this._duracionMs,
            velocidadMbps: this._velocidadMbps,
            velocidadPromedioMbps: this._velocidadPromedioMbps,
        };
    }

    /** Actualiza velocidad instantánea (ventana 3 s) y promedio a partir de los bytes confirmados. */
    private medirVelocidad(bytesConfirmados: number): void {
        const ahora = Date.now();
        this._muestras.push({ t: ahora, bytes: bytesConfirmados });
        const limite = ahora - 3000;
        while (this._muestras.length > 1 && this._muestras[0].t < limite) this._muestras.shift();
        const primera = this._muestras[0];
        const dtInst = (ahora - primera.t) / 1000;
        const dtTotal = (ahora - this._inicioMs) / 1000;
        const aMbps = (bytes: number, seg: number): number =>
            seg > 0 ? Math.round(((bytes * 8) / 1_000_000 / seg) * 10) / 10 : 0;
        this._velocidadMbps = aMbps(bytesConfirmados - primera.bytes, dtInst);
        this._velocidadPromedioMbps = aMbps(bytesConfirmados, dtTotal);
    }

    public destroy(): void {
        // Cancela cualquier subida en curso al destruir el control.
        this._abort?.abort();
    }

    // ---------- Logica ----------

    private ocupado(): boolean {
        return (
            this._estado === "solicitandoSas" ||
            this._estado === "subiendo" ||
            this._estado === "confirmando"
        );
    }

    private reiniciar(): void {
        this._abort?.abort();
        this._abort = undefined;
        this._inputArchivo.value = "";
        this._progreso = 0;
        this._rutaBlob = "";
        this._nombreArchivo = "";
        this._tamanoBytes = 0;
        this._contentType = "";
        this._mensajeError = "";
        this._duracionMs = 0;
        this._velocidadMbps = 0;
        this._velocidadPromedioMbps = 0;
        this._muestras = [];
        this.cambiarEstado("inactivo");
    }

    private cambiarEstado(estado: Estado): void {
        this._estado = estado;
        this.pintar();
        this._notificar();
    }

    private fallar(mensaje: string): void {
        this._mensajeError = mensaje;
        this._duracionMs = Date.now() - this._inicioMs;
        this.cambiarEstado("error");
    }

    private async iniciarSubida(): Promise<void> {
        const contexto = this._contexto;
        if (!contexto || this.ocupado()) return;

        const p = contexto.parameters;
        const archivo = this._inputArchivo.files?.[0];
        this._inicioMs = Date.now();
        this._mensajeError = "";
        this._rutaBlob = "";
        this._progreso = 0;
        this._duracionMs = 0;
        this._velocidadMbps = 0;
        this._velocidadPromedioMbps = 0;
        this._muestras = [];

        // Validaciones previas, antes de pedir la SAS.
        const solicitudId = (p.solicitudId.raw ?? "").trim();
        const urlBroker = (p.urlBroker.raw ?? "").trim();
        if (!archivo) {
            this.fallar("Elegí un archivo antes de subir.");
            return;
        }
        this._nombreArchivo = archivo.name;
        this._tamanoBytes = archivo.size;
        this._contentType = archivo.type || "application/octet-stream";

        if (!REGEX_GUID.test(solicitudId)) {
            this.fallar("El id de la solicitud no es un GUID válido.");
            return;
        }
        if (!urlBroker) {
            this.fallar("Falta la URL del broker.");
            return;
        }
        const maxMB = p.tamanoMaximoMB.raw ?? 250;
        if (archivo.size > maxMB * 1024 * 1024) {
            this.fallar(`El archivo supera el máximo de ${maxMB} MB.`);
            return;
        }
        if (archivo.size === 0) {
            this.fallar("El archivo está vacío.");
            return;
        }

        const abort = new AbortController();
        this._abort = abort;

        try {
            this.cambiarEstado("solicitandoSas");
            const sas = await solicitarSasSubida(
                urlBroker,
                p.tokenBroker.raw,
                {
                    solicitudId: solicitudId.replace(/[{}]/g, ""),
                    nombreArchivo: archivo.name,
                    tamanoBytes: archivo.size,
                    contentType: this._contentType,
                },
                abort.signal
            );
            this._rutaBlob = sas.rutaBlob;

            this.cambiarEstado("subiendo");
            this._muestras = [{ t: Date.now(), bytes: 0 }];
            let ultimoPct = -1;
            await subirEnBloques(
                archivo,
                sas.uploadUrl,
                {
                    bloqueBytes: sas.bloqueBytes,
                    paralelismo: Math.min(Math.max(p.paralelismo.raw ?? 4, 1), 8),
                    versionServicio: sas.versionServicio,
                },
                (hecho, total) => {
                    this.medirVelocidad(hecho);
                    // Se notifica solo cuando cambia el porcentaje entero.
                    const pct = Math.floor((hecho / total) * 100);
                    if (pct !== ultimoPct) {
                        ultimoPct = pct;
                        this._progreso = pct;
                        this.pintar();
                        this._notificar();
                    }
                },
                abort.signal
            );

            this.cambiarEstado("confirmando");
            this._progreso = 100;
            this._duracionMs = Date.now() - this._inicioMs;
            this.medirVelocidad(archivo.size);
            this._velocidadMbps = this._velocidadPromedioMbps;
            this.cambiarEstado("completado");
        } catch (e) {
            if (e instanceof ErrorSubida && e.causa === "cancelado") {
                // Cancelacion por destroy/reinicio: no se publica como error.
                return;
            }
            if (e instanceof ErrorBroker || e instanceof ErrorSubida) {
                this.fallar(e.message);
            } else {
                this.fallar("Error inesperado durante la subida.");
            }
        } finally {
            if (this._abort === abort) this._abort = undefined;
        }
    }

    // ---------- Presentacion ----------

    private pintar(): void {
        const ocupado = this.ocupado();
        const deshabilitado = this._contexto?.mode.isControlDisabled ?? false;
        this._inputArchivo.disabled = ocupado || deshabilitado;
        this._boton.disabled = ocupado || deshabilitado;

        this._relleno.style.width = `${this._progreso}%`;
        this._barra.setAttribute("aria-valuenow", String(this._progreso));
        this._barra.dataset.estado = this._estado;

        let texto = TEXTO_ESTADO[this._estado];
        if (this._estado === "subiendo") {
            texto = `${texto} ${this._progreso}% · ${this._velocidadMbps} Mbps (promedio ${this._velocidadPromedioMbps})`;
        }
        if (this._estado === "completado") {
            const seg = Math.round(this._duracionMs / 100) / 10;
            texto = `${texto} en ${seg} s · promedio ${this._velocidadPromedioMbps} Mbps`;
        }
        if (this._estado === "error" && this._mensajeError) texto = this._mensajeError;
        this._linea.textContent = texto;
        this._linea.dataset.estado = this._estado;
    }
}
