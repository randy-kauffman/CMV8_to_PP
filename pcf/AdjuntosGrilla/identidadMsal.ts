// Identidad propia del control en model-driven (ADR-003 §4.6): MSAL Browser contra la app registration de cliente
// público «CMV8 PCF» (guía 24), con audiencia del broker (api://<broker>/user_impersonation).
//
// Orden: caché de MSAL (acquireTokenSilent) → SSO silencioso con la sesión de Entra que el usuario ya tiene en
// Dataverse (ssoSilent) → popup (acquireTokenPopup), solo si quien llama lo permite (gesto del usuario).
// Seguridad: nunca se registra un token. El logger de MSAL queda en Error, sin PII y sin salida a consola.
import {
    AccountInfo,
    AuthenticationResult,
    AuthError,
    BrowserCacheLocation,
    InteractionRequiredAuthError,
    IPublicClientApplication,
    LogLevel,
    PublicClientApplication,
} from "@azure/msal-browser";

export interface ConfiguracionIdentidad {
    readonly clientId: string;
    readonly tenantId: string;
    readonly brokerClientId: string;
    /** context.userSettings.userName: se usa como loginHint solo si parece un UPN. */
    readonly nombreUsuario: string;
}

export type MotivoAutenticacion = "interaccion" | "popupBloqueado" | "cancelado" | "otro";

/** Falla de identidad. `interaccion`: hace falta un gesto del usuario para abrir el popup. */
export class ErrorAutenticacion extends Error {
    constructor(public readonly motivo: MotivoAutenticacion) {
        super(`Autenticación: ${motivo}`);
        this.name = "ErrorAutenticacion";
    }
}

export interface OpcionesToken {
    /** Puede abrir un popup. Solo true cuando la llamada nace de un clic (los navegadores bloquean el resto). */
    readonly interactivo: boolean;
    /** Desafío de claims del broker (401 con WWW-Authenticate): obliga a pasar por el popup. */
    readonly claims?: string;
    /** Ignora el token en caché (el broker lo rechazó con 401 sin claims). */
    readonly forzar?: boolean;
}

const REGEX_UPN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const ERRORES_POPUP_BLOQUEADO = new Set(["popup_window_error", "empty_window_error", "block_nested_popups"]);

export class IdentidadMsal {
    private app: Promise<IPublicClientApplication> | null = null;
    /** Un popup a la vez: MSAL rechaza un segundo con interaction_in_progress. */
    private interactivoEnCurso: Promise<AuthenticationResult> | null = null;

    constructor(private readonly config: ConfiguracionIdentidad) {}

    public get scopes(): string[] {
        return [`api://${this.config.brokerClientId}/user_impersonation`];
    }

    private get loginHint(): string | undefined {
        const u = (this.config.nombreUsuario ?? "").trim();
        return REGEX_UPN.test(u) ? u : undefined;
    }

    /** Devuelve un access token del broker. Lanza ErrorAutenticacion si no se pudo. */
    public async token(o: OpcionesToken): Promise<string> {
        const app = await this.obtenerApp();
        if (o.claims) return (await this.interactivo(app, o, this.cuenta(app))).accessToken;

        const cuenta = this.cuenta(app);
        if (cuenta) {
            try {
                const r = await app.acquireTokenSilent({ scopes: this.scopes, account: cuenta, forceRefresh: o.forzar === true });
                return r.accessToken;
            } catch (e) {
                // InteractionRequiredAuthError (y cualquier AuthError) sigue al SSO silencioso y, si hace falta, al popup.
                if (!(e instanceof AuthError)) throw this.traducir(e);
            }
        }
        try {
            const r = await app.ssoSilent({ scopes: this.scopes, loginHint: this.loginHint });
            if (r.account) app.setActiveAccount(r.account);
            return r.accessToken;
        } catch (e) {
            // Sin sesión utilizable, cookies de terceros bloqueadas (monitor_window_timeout) o consentimiento: popup.
            if (!(e instanceof AuthError)) throw this.traducir(e);
        }
        return (await this.interactivo(app, o, cuenta)).accessToken;
    }

    /** Intenta dejar un token en caché sin molestar al usuario (al cargar el formulario). Nunca abre popups. */
    public async precalentar(): Promise<boolean> {
        try {
            await this.token({ interactivo: false });
            return true;
        } catch {
            return false;
        }
    }

    private async interactivo(
        app: IPublicClientApplication,
        o: OpcionesToken,
        cuenta: AccountInfo | null
    ): Promise<AuthenticationResult> {
        if (!o.interactivo) throw new ErrorAutenticacion("interaccion");
        if (this.interactivoEnCurso) return this.interactivoEnCurso;
        this.interactivoEnCurso = app
            .acquireTokenPopup({
                scopes: this.scopes,
                account: cuenta ?? undefined,
                loginHint: cuenta ? undefined : this.loginHint,
                claims: o.claims,
            })
            .then((r) => {
                if (r.account) app.setActiveAccount(r.account);
                return r;
            })
            .catch((e: unknown) => {
                throw this.traducir(e);
            })
            .finally(() => {
                this.interactivoEnCurso = null;
            });
        return this.interactivoEnCurso;
    }

    private cuenta(app: IPublicClientApplication): AccountInfo | null {
        const activa = app.getActiveAccount();
        if (activa) return activa;
        const cuentas = app.getAllAccounts();
        const hint = this.loginHint?.toLowerCase();
        const elegida = (hint && cuentas.find((c) => c.username.toLowerCase() === hint)) || cuentas[0] || null;
        if (elegida) app.setActiveAccount(elegida);
        return elegida;
    }

    private traducir(e: unknown): ErrorAutenticacion {
        if (e instanceof ErrorAutenticacion) return e;
        if (e instanceof AuthError) {
            if (ERRORES_POPUP_BLOQUEADO.has(e.errorCode)) return new ErrorAutenticacion("popupBloqueado");
            if (e.errorCode === "user_cancelled") return new ErrorAutenticacion("cancelado");
            if (e instanceof InteractionRequiredAuthError) return new ErrorAutenticacion("interaccion");
        }
        return new ErrorAutenticacion("otro");
    }

    private obtenerApp(): Promise<IPublicClientApplication> {
        this.app ??= PublicClientApplication.createPublicClientApplication({
            auth: {
                clientId: this.config.clientId,
                authority: `https://login.microsoftonline.com/${this.config.tenantId}`,
                // Registrada como redirect SPA en «CMV8 PCF». La página del entorno no necesita código: MSAL lee la
                // respuesta del popup (mismo origen) y lo cierra.
                redirectUri: `${window.location.origin}/`,
                navigateToLoginRequestUrl: false,
            },
            cache: { cacheLocation: BrowserCacheLocation.SessionStorage },
            system: {
                loggerOptions: {
                    logLevel: LogLevel.Error,
                    piiLoggingEnabled: false,
                    loggerCallback: () => undefined,
                },
            },
        }).catch((e: unknown) => {
            this.app = null;
            throw e;
        });
        return this.app;
    }
}
