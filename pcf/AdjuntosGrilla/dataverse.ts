// Acceso a Dataverse con la Web API del componente (identidad del usuario, ADR-003 §4.6): crear la fila del
// adjunto, completar sus datos de archivo, marcarla Subido y leer el catálogo de tipos en uso.
// El control NO lee Dataverse para decidir nada del proceso: eso lo hace el broker suplantando al usuario.
import { ItemPedido } from "../CargaAdjuntoBlob/fuenteSas";
import { Columnas } from "./modeloGrilla";
import { ESTADO } from "./estados";

/** Mismo patrón que valida el broker (ADR-003 §1.1). */
export const TABLA_VALIDA = /^[a-z][a-z0-9_]{1,63}$/;
const GUID = /^\{?([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\}?$/i;
/** SchemaName de una propiedad de navegación: letras, dígitos y guion bajo. */
const NAVEGACION_VALIDA = /^[A-Za-z_][A-Za-z0-9_]{0,127}$/;

export const TABLA_TIPOS = "cmv8_tipodocumento";

export interface RegistroPadre {
    readonly entidad: string;
    readonly id: string;
}

export interface TipoDocumento {
    readonly id: string;
    readonly nombre: string;
    readonly codigo: string;
}

export function normalizarGuid(v: unknown): string | null {
    const m = typeof v === "string" ? GUID.exec(v.trim()) : null;
    return m ? m[1].toLowerCase() : null;
}

/** Prefijo del publisher derivado del nombre lógico (cred_adjuntocredito → cred_). Vacío si no tiene. */
export function prefijoDe(tabla: string): string {
    const i = tabla.indexOf("_");
    return i > 0 ? tabla.slice(0, i + 1) : "";
}

export function navegacionValida(nombre: string): boolean {
    return NAVEGACION_VALIDA.test(nombre);
}

/**
 * Registro padre del formulario que aloja la subcuadrícula. contextInfo no está en los tipos públicos de PCF pero
 * es lo que el host model-driven entrega en context.mode (entityTypeName, entityId). Sin id = formulario de creación.
 */
export function leerPadre(modo: ComponentFramework.Mode): RegistroPadre | null {
    const info = (modo as unknown as { contextInfo?: { entityTypeName?: unknown; entityId?: unknown } }).contextInfo;
    const entidad = typeof info?.entityTypeName === "string" ? info.entityTypeName : "";
    const id = normalizarGuid(info?.entityId);
    return entidad && TABLA_VALIDA.test(entidad) && id ? { entidad, id } : null;
}

/** Columnas por defecto (las reservadas de ADR-003 §1.1 con el prefijo de la tabla, y las de sistema). */
export function columnasPorDefecto(prefijo: string): Omit<Columnas, "primaria"> {
    return {
        estado: `${prefijo}estadoingesta`,
        tipoDocumento: `${prefijo}tipodocumento`,
        nombreArchivo: `${prefijo}nombrearchivo`,
        tamano: `${prefijo}tamanobytes`,
        mensaje: `${prefijo}mensajeerror`,
        rutaBlob: `${prefijo}rutablob`,
        fechaSubida: "modifiedon",
        subidoPor: "createdby",
        contentType: `${prefijo}contenttype`,
        modificado: "modifiedon",
    };
}

export interface ConfiguracionDataverse {
    readonly tabla: string;
    readonly prefijo: string;
    readonly padre: RegistroPadre;
    /** SchemaName de la lookup al padre (navegación para @odata.bind). */
    readonly lookupPadre: string;
    /** SchemaName de la lookup al tipo de documento. */
    readonly lookupTipo: string;
}

/** Plural por defecto de Dataverse para tablas personalizadas; solo si getEntityMetadata no está disponible. */
function conjuntoSupuesto(logico: string): string {
    return `${logico}s`;
}

export class RepositorioAdjuntos {
    private readonly metadatos = new Map<string, Promise<ComponentFramework.PropertyHelper.EntityMetadata | null>>();

    constructor(
        private readonly webAPI: ComponentFramework.WebApi,
        private readonly utils: ComponentFramework.Utility | undefined,
        private readonly config: ConfiguracionDataverse
    ) {}

    private metadata(logico: string): Promise<ComponentFramework.PropertyHelper.EntityMetadata | null> {
        let p = this.metadatos.get(logico);
        if (!p) {
            p =
                typeof this.utils?.getEntityMetadata === "function"
                    ? this.utils.getEntityMetadata(logico).catch(() => null)
                    : Promise.resolve(null);
            this.metadatos.set(logico, p);
        }
        return p;
    }

    private async conjunto(logico: string): Promise<string> {
        const m = await this.metadata(logico);
        const s: unknown = m?.EntitySetName;
        return typeof s === "string" && s ? s : conjuntoSupuesto(logico);
    }

    /** Columna primaria de la tabla de adjuntos (se completa con el nombre del archivo si no es nombrearchivo). */
    public async atributoPrimario(): Promise<string | null> {
        const m = await this.metadata(this.config.tabla);
        const p: unknown = m?.PrimaryNameAttribute;
        return typeof p === "string" && p ? p : null;
    }

    public async tiposEnUso(): Promise<TipoDocumento[]> {
        const r = await this.webAPI.retrieveMultipleRecords(
            TABLA_TIPOS,
            "?$select=cmv8_tipodocumentoid,cmv8_nombre,cmv8_codigo&$filter=cmv8_enuso eq true&$orderby=cmv8_nombre asc"
        );
        const tipos: TipoDocumento[] = [];
        for (const e of r.entities) {
            const id = normalizarGuid(e.cmv8_tipodocumentoid);
            if (!id) continue;
            tipos.push({
                id,
                nombre: typeof e.cmv8_nombre === "string" ? e.cmv8_nombre : "",
                codigo: typeof e.cmv8_codigo === "string" ? e.cmv8_codigo : "",
            });
        }
        return tipos;
    }

    private datosArchivo(item: ItemPedido, primaria: string | null): Record<string, unknown> {
        const p = this.config.prefijo;
        const datos: Record<string, unknown> = {
            [`${p}nombrearchivo`]: item.nombreArchivo,
            [`${p}tamanobytes`]: item.tamanoBytes,
            [`${p}contenttype`]: item.contentType,
        };
        // Columna primaria (suele ser obligatoria y de 100 caracteres por defecto): el nombre del archivo, recortado.
        if (primaria && primaria !== `${p}nombrearchivo`) datos[primaria] = item.nombreArchivo.slice(0, 100);
        return datos;
    }

    /** Crea la fila en Creada con el tipo y el padre. Devuelve su id. */
    public async crear(item: ItemPedido, tipoId: string): Promise<string> {
        const c = this.config;
        const [conjuntoPadre, conjuntoTipos, primaria] = await Promise.all([
            this.conjunto(c.padre.entidad),
            this.conjunto(TABLA_TIPOS),
            this.atributoPrimario(),
        ]);
        const datos: Record<string, unknown> = {
            ...this.datosArchivo(item, primaria),
            [`${c.prefijo}estadoingesta`]: ESTADO.creada,
            [`${c.lookupTipo}@odata.bind`]: `/${conjuntoTipos}(${tipoId})`,
            [`${c.lookupPadre}@odata.bind`]: `/${conjuntoPadre}(${c.padre.id})`,
        };
        const r = await this.webAPI.createRecord(c.tabla, datos);
        const id = normalizarGuid(r.id);
        if (!id) throw new Error("createRecord no devolvió un id");
        return id;
    }

    /** «Subir» sobre una fila Creada (o Error sin archivo): se reutiliza la fila con los datos del archivo elegido. */
    public async actualizarArchivo(id: string, item: ItemPedido): Promise<void> {
        // La columna primaria no se toca: la fila ya existe y su nombre lo puso quien la creó.
        await this.webAPI.updateRecord(this.config.tabla, id, this.datosArchivo(item, null));
    }

    /** ADR-003 §4.1 paso 3: el usuario marca Subido al terminar la subida. */
    public async marcarSubido(id: string): Promise<void> {
        await this.webAPI.updateRecord(this.config.tabla, id, { [`${this.config.prefijo}estadoingesta`]: ESTADO.subido });
    }
}
