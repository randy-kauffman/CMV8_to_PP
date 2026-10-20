// Acciones de fila que hablan con el broker o con Storage (Anatomia.md §4): Ver, Archivar ahora y Reanudar.
// Sin DOM salvo lo mínimo para abrir el documento. Nunca muestra ni registra la URL con SAS ni el tokenStorage.
import { esUrlSegura } from "../CargaAdjuntoBlob/lote";
import { VERSION_SERVICIO_DEFECTO } from "../CargaAdjuntoBlob/subidaBloques";
import { ClienteBroker, ErrorBroker } from "./clienteBroker";

/**
 * Tipos que se abren en una pestaña. El documento se sirve como blob: del origen del entorno, así que un HTML o un
 * SVG subido por otro usuario ejecutaría script con la sesión de Dataverse de quien lo abre: todo lo que no está en
 * esta lista se descarga (application/octet-stream), nunca se muestra en línea.
 */
const EN_LINEA = /^(application\/pdf|image\/(png|jpeg|gif|webp|bmp)|video\/(mp4|webm|ogg|quicktime)|audio\/[a-z0-9.+-]+|text\/plain)$/i;

export function seAbreEnLinea(contentType: string): boolean {
    return EN_LINEA.test(contentType.trim());
}

interface Lectura {
    readonly downloadUrl: string;
    readonly tokenStorage?: string;
}

function parsearLectura(texto: string): Lectura | null {
    try {
        const j: unknown = JSON.parse(texto);
        if (typeof j !== "object" || j === null) return null;
        const o = j as { downloadUrl?: unknown; tokenStorage?: unknown };
        if (typeof o.downloadUrl !== "string" || !esUrlSegura(o.downloadUrl)) return null;
        return { downloadUrl: o.downloadUrl, tokenStorage: typeof o.tokenStorage === "string" ? o.tokenStorage : undefined };
    } catch {
        return null;
    }
}

export class AccionesBroker {
    constructor(
        private readonly cliente: ClienteBroker,
        private readonly tabla: string
    ) {}

    public async archivar(id: string, interactivo: boolean): Promise<void> {
        await this.cliente.pedir("POST", `adjuntos/${ClienteBroker.ruta(this.tabla, id)}/archivar`, {}, interactivo);
    }

    public async reanudar(id: string, interactivo: boolean): Promise<void> {
        await this.cliente.pedir("POST", `adjuntos/${ClienteBroker.ruta(this.tabla, id)}/reanudar`, {}, interactivo);
    }

    /**
     * Descarga el documento con las dos llaves (SAS de lectura + tokenStorage) y lo entrega como Blob local. La URL
     * de Storage no sale de esta función.
     */
    public async leer(id: string, contentType: string, interactivo: boolean): Promise<Blob> {
        const r = await this.cliente.pedir("GET", `sas/lectura/${ClienteBroker.ruta(this.tabla, id)}`, undefined, interactivo);
        const lectura = parsearLectura(r.texto);
        if (!lectura) throw new ErrorBroker(0, "", "http");
        const encabezados: Record<string, string> = { "x-ms-version": VERSION_SERVICIO_DEFECTO };
        if (lectura.tokenStorage) encabezados.Authorization = `Bearer ${lectura.tokenStorage}`;
        let resp: Response;
        try {
            resp = await fetch(lectura.downloadUrl, { headers: encabezados, credentials: "omit", cache: "no-store" });
        } catch {
            throw new ErrorBroker(0, "", "red");
        }
        if (!resp.ok) throw new ErrorBroker(resp.status === 404 ? 404 : 0, "", "http");
        const datos = await resp.blob();
        const tipo = seAbreEnLinea(contentType) ? contentType.trim().toLowerCase() : "application/octet-stream";
        return new Blob([datos], { type: tipo });
    }
}

/** Muestra un Blob: en la ventana ya abierta (dentro del clic, para que no la bloqueen) o como descarga. */
export function mostrarBlob(blob: Blob, nombre: string, ventana: Window | null): void {
    const url = URL.createObjectURL(blob);
    if (ventana && !ventana.closed && seAbreEnLinea(blob.type)) {
        ventana.location.href = url;
    } else {
        ventana?.close();
        const a = document.createElement("a");
        a.href = url;
        a.download = nombre || "documento";
        a.rel = "noopener";
        a.style.display = "none";
        document.body.appendChild(a);
        a.click();
        a.remove();
    }
    // La pestaña ya cargó (o la descarga empezó): el objeto se libera en un minuto.
    window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
}
