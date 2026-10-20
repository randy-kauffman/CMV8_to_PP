// Cliente HTTP del broker CMV8 (function/src/main/java/com/banco/cmv8/adapters/in): POST /api/sas/lote,
// GET /api/sas/lectura/{tabla}/{id}, POST /api/adjuntos/{tabla}/{id}/archivar y /reanudar.
// Autenticación: Bearer del usuario (IdentidadMsal). 401 con desafío de claims (acceso condicional sobre Storage,
// RespuestasHttp.desafioClaims) → popup con esos claims y un reintento. 401 sin claims → token nuevo y un reintento.
// Seguridad: no registra nada; las respuestas (SAS, tokenStorage) solo vuelven a quien llamó.
import { ErrorAutenticacion, IdentidadMsal } from "./identidadMsal";

export type CausaErrorBroker = "http" | "red" | "autenticacion";

export class ErrorBroker extends Error {
    constructor(
        /** Código HTTP, o 0 si no hubo respuesta. */
        public readonly status: number,
        /** Texto del campo error del broker (para diagnóstico en la fila; nunca trae secretos). */
        public readonly mensajeServidor: string,
        public readonly causa: CausaErrorBroker,
        public readonly autenticacion?: ErrorAutenticacion
    ) {
        super(`Broker ${status}${mensajeServidor ? `: ${mensajeServidor}` : ""}`);
        this.name = "ErrorBroker";
    }
}

export interface RespuestaBroker {
    readonly status: number;
    readonly texto: string;
}

/** https://host sin barra final ni /api. Solo HTTPS. Vacío si no es válida. */
export function normalizarUrlBroker(url: string | null | undefined): string {
    const s = (url ?? "").trim().replace(/\/+$/, "").replace(/\/api$/i, "");
    try {
        const u = new URL(s);
        return u.protocol === "https:" && u.pathname.replace(/\/+$/, "") === "" ? u.origin : "";
    } catch {
        return "";
    }
}

/**
 * Claims del desafío. Primero el cuerpo JSON ({claims}) porque WWW-Authenticate solo es legible si el CORS de la
 * Function lo expone (Access-Control-Expose-Headers). Entra suele mandar los claims en base64; MSAL los quiere en JSON.
 */
export function extraerClaims(cuerpo: string, encabezado: string | null): string | undefined {
    let claims: string | undefined;
    try {
        const j: unknown = JSON.parse(cuerpo);
        if (typeof j === "object" && j !== null && typeof (j as { claims?: unknown }).claims === "string") {
            claims = (j as { claims: string }).claims;
        }
    } catch {
        // cuerpo no JSON (Easy Auth responde 401 sin cuerpo)
    }
    if (!claims && encabezado) {
        const m = /claims="((?:[^"\\]|\\.)*)"/i.exec(encabezado);
        if (m) claims = m[1].replace(/\\"/g, '"');
    }
    claims = claims?.trim();
    if (!claims) return undefined;
    if (claims.startsWith("{")) return claims;
    try {
        const json = atob(claims.replace(/-/g, "+").replace(/_/g, "/"));
        return json.trim().startsWith("{") ? json : undefined;
    } catch {
        return undefined;
    }
}

function mensajeDe(texto: string): string {
    try {
        const j: unknown = JSON.parse(texto);
        const e = typeof j === "object" && j !== null ? (j as { error?: unknown }).error : undefined;
        return typeof e === "string" ? e : "";
    } catch {
        return "";
    }
}

export class ClienteBroker {
    constructor(
        private readonly base: string,
        private readonly identidad: IdentidadMsal
    ) {}

    /** Segmento de ruta seguro: tabla validada y GUID sin llaves. */
    public static ruta(...partes: string[]): string {
        return partes.map((p) => encodeURIComponent(p)).join("/");
    }

    public async pedir(
        metodo: "GET" | "POST",
        ruta: string,
        cuerpo: unknown,
        interactivo: boolean
    ): Promise<RespuestaBroker> {
        let token = await this.token({ interactivo });
        let r = await this.enviar(metodo, ruta, cuerpo, token);
        if (r.status === 401) {
            const claims = extraerClaims(r.texto, r.desafio);
            token = await this.token({ interactivo, claims, forzar: true });
            r = await this.enviar(metodo, ruta, cuerpo, token);
            if (r.status === 401) {
                throw new ErrorBroker(401, mensajeDe(r.texto), "autenticacion", new ErrorAutenticacion("interaccion"));
            }
        }
        if (r.status < 200 || r.status >= 300) throw new ErrorBroker(r.status, mensajeDe(r.texto), "http");
        return { status: r.status, texto: r.texto };
    }

    private async token(o: { interactivo: boolean; claims?: string; forzar?: boolean }): Promise<string> {
        try {
            return await this.identidad.token(o);
        } catch (e) {
            const a = e instanceof ErrorAutenticacion ? e : new ErrorAutenticacion("otro");
            throw new ErrorBroker(401, "", "autenticacion", a);
        }
    }

    private async enviar(
        metodo: "GET" | "POST",
        ruta: string,
        cuerpo: unknown,
        token: string
    ): Promise<{ status: number; texto: string; desafio: string | null }> {
        const encabezados: Record<string, string> = { Authorization: `Bearer ${token}`, Accept: "application/json" };
        if (cuerpo !== undefined) encabezados["Content-Type"] = "application/json";
        let resp: Response;
        try {
            resp = await fetch(`${this.base}/api/${ruta}`, {
                method: metodo,
                headers: encabezados,
                body: cuerpo === undefined ? undefined : JSON.stringify(cuerpo),
                // Sin cookies: la única credencial es el bearer (y así el CORS no necesita Allow-Credentials).
                credentials: "omit",
                cache: "no-store",
            });
        } catch {
            throw new ErrorBroker(0, "", "red");
        }
        let texto = "";
        try {
            texto = await resp.text();
        } catch {
            texto = "";
        }
        return { status: resp.status, texto, desafio: resp.headers.get("WWW-Authenticate") };
    }
}
