// Cliente del broker de SAS (Azure Function). Nunca se registra el token en consola.

export interface SolicitudSas {
    solicitudId: string;
    nombreArchivo: string;
    tamanoBytes: number;
    contentType: string;
}

export interface RespuestaSas {
    uploadUrl: string;
    rutaBlob: string;
    expiraUtc?: string;
    bloqueBytes?: number;
    versionServicio?: string;
    /** S1b: token Entra de Storage del usuario (SAS ligada con sduoid). Secreto: nunca registrarlo. */
    tokenStorage?: string;
    tokenStorageExpiraUtc?: string;
}

export class ErrorBroker extends Error {
    constructor(mensaje: string, public readonly status?: number) {
        super(mensaje);
        this.name = "ErrorBroker";
    }
}

export async function solicitarSasSubida(
    urlBroker: string,
    tokenBroker: string | null | undefined,
    cuerpo: SolicitudSas,
    signal?: AbortSignal
): Promise<RespuestaSas> {
    const base = urlBroker.trim().replace(/\/+$/, "");
    const headers: Record<string, string> = { "Content-Type": "application/json" };
    if (tokenBroker && tokenBroker.trim() !== "") {
        headers["Authorization"] = `Bearer ${tokenBroker.trim()}`;
    }

    let resp: Response;
    try {
        resp = await fetch(`${base}/api/sas/subida`, {
            method: "POST",
            headers,
            body: JSON.stringify(cuerpo),
            signal,
        });
    } catch (e) {
        if (signal?.aborted) throw new ErrorBroker("Solicitud de SAS cancelada.");
        throw new ErrorBroker("No se pudo contactar al broker (revisar URL, CORS o conexion).");
    }

    if (!resp.ok) {
        const detalle =
            resp.status === 401 || resp.status === 403
                ? "El broker rechazo la solicitud (sin permiso)."
                : `El broker respondio ${resp.status}.`;
        throw new ErrorBroker(detalle, resp.status);
    }

    let datos: Partial<RespuestaSas>;
    try {
        datos = (await resp.json()) as Partial<RespuestaSas>;
    } catch {
        throw new ErrorBroker("La respuesta del broker no es JSON valido.", resp.status);
    }
    if (!datos.uploadUrl || !datos.rutaBlob) {
        throw new ErrorBroker("La respuesta del broker no trae uploadUrl o rutaBlob.", resp.status);
    }
    if (!/^https:\/\//i.test(datos.uploadUrl)) {
        throw new ErrorBroker("El broker devolvio una uploadUrl que no es HTTPS.", resp.status);
    }
    return datos as RespuestaSas;
}
