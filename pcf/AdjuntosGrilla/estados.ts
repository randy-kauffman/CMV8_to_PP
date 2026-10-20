// Los diez estados del ciclo de vida (ADR-003 §2) y su presentación (Anatomia.md §4). Puro: sin DOM ni PCF.
// El código coincide con el choice global cmv8_estadoingesta y con EstadoIngesta.java del broker.
import { ClaveGrilla } from "./textosGrilla";
import { IconoGrilla } from "./vista/iconosGrilla.generado";

export const ESTADO = {
    creada: 1,
    subido: 2,
    procesando: 3,
    archivado: 4,
    rechazado: 5,
    error: 6,
    almacenado: 7,
    observada: 8,
    detenida: 9,
    depurado: 10,
} as const;

export type NombreEstado = keyof typeof ESTADO;
export type CodigoEstado = (typeof ESTADO)[NombreEstado];

export type SemanticaEstado = "neutral" | "info" | "success" | "warning" | "danger";
/** tintado: fondo bg + borde border; contorno: fondo surface; punteado: contorno con borde dashed; fuerte: tintado con borde fg. */
export type EstiloChip = "tintado" | "contorno" | "punteado" | "fuerte";

/** Acciones de fila (Anatomia.md §4). «detalle» no depende del estado: siempre está en el menú. */
export type AccionAdjunto = "subir" | "ver" | "archivar" | "corregir" | "reintentar";

export type GrupoFiltro = "todos" | "accion" | "enProceso" | "guardados" | "cerrados";

export interface PresentacionEstado {
    readonly nombre: NombreEstado;
    readonly clave: ClaveGrilla;
    readonly descripcion: ClaveGrilla;
    readonly semantica: SemanticaEstado;
    readonly estilo: EstiloChip;
    readonly icono: IconoGrilla;
    readonly girar: boolean;
    readonly acciones: readonly AccionAdjunto[];
    readonly grupo: Exclude<GrupoFiltro, "todos">;
    /** Posición en el orden «por estado» (requieren acción primero). */
    readonly orden: number;
    /** Muestra el mensaje de detalle bajo el nombre (Anatomia.md §7). */
    readonly conMensaje: boolean;
}

const P = (
    nombre: NombreEstado,
    semantica: SemanticaEstado,
    estilo: EstiloChip,
    icono: IconoGrilla,
    acciones: readonly AccionAdjunto[],
    grupo: PresentacionEstado["grupo"],
    orden: number,
    extra: { girar?: boolean; conMensaje?: boolean } = {}
): PresentacionEstado => ({
    nombre,
    clave: `lifecycle.${claveCiclo(nombre)}` as ClaveGrilla,
    descripcion: `lifecycle.${claveCiclo(nombre)}.desc` as ClaveGrilla,
    semantica,
    estilo,
    icono,
    girar: extra.girar === true,
    acciones,
    grupo,
    orden,
    conMensaje: extra.conMensaje === true,
});

function claveCiclo(n: NombreEstado): string {
    const mapa: Readonly<Record<NombreEstado, string>> = {
        creada: "created",
        subido: "uploaded",
        procesando: "processing",
        archivado: "archived",
        rechazado: "rejected",
        error: "error",
        almacenado: "stored",
        observada: "observed",
        detenida: "held",
        depurado: "purged",
    };
    return mapa[n];
}

/** Tabla de Anatomia.md §4. Orden «por estado»: Observada, Error, Creada, Detenida, Procesando, Subido, Almacenado, Archivado, Rechazado, Depurado. */
export const PRESENTACION: Readonly<Record<CodigoEstado, PresentacionEstado>> = {
    1: P("creada", "neutral", "punteado", "document_add_16_regular", ["subir"], "accion", 2),
    2: P("subido", "info", "contorno", "cloud_arrow_up_16_regular", [], "enProceso", 5),
    3: P("procesando", "info", "tintado", "arrow_sync_16_regular", [], "enProceso", 4, { girar: true }),
    4: P("archivado", "success", "tintado", "archive_16_regular", ["ver"], "guardados", 7),
    5: P("rechazado", "neutral", "tintado", "prohibited_16_filled", [], "cerrados", 8, { conMensaje: true }),
    6: P("error", "danger", "tintado", "error_circle_16_filled", ["reintentar"], "accion", 1, { conMensaje: true }),
    7: P("almacenado", "success", "contorno", "cloud_checkmark_16_regular", ["ver", "archivar"], "guardados", 6),
    8: P("observada", "warning", "fuerte", "edit_16_filled", ["corregir"], "accion", 0, { conMensaje: true }),
    9: P("detenida", "info", "tintado", "person_clock_16_filled", [], "enProceso", 3, { conMensaje: true }),
    10: P("depurado", "neutral", "contorno", "delete_16_regular", [], "cerrados", 9),
};

export function esCodigoEstado(v: unknown): v is CodigoEstado {
    return typeof v === "number" && Number.isInteger(v) && v >= 1 && v <= 10;
}

export function presentacion(codigo: CodigoEstado | null): PresentacionEstado | null {
    return codigo === null ? null : PRESENTACION[codigo];
}

export const GRUPOS: readonly GrupoFiltro[] = ["todos", "accion", "enProceso", "guardados", "cerrados"];

export const CLAVE_GRUPO: Readonly<Record<GrupoFiltro, ClaveGrilla>> = {
    todos: "grid.filter.all",
    accion: "grid.filter.action",
    enProceso: "grid.filter.inProgress",
    guardados: "grid.filter.saved",
    cerrados: "grid.filter.closed",
};

export const CLAVE_ACCION: Readonly<Record<AccionAdjunto, ClaveGrilla>> = {
    subir: "action.upload",
    ver: "action.view",
    archivar: "action.archiveNow",
    corregir: "action.fix",
    reintentar: "action.retry",
};

export function enGrupo(codigo: CodigoEstado | null, grupo: GrupoFiltro): boolean {
    if (grupo === "todos") return true;
    return codigo !== null && PRESENTACION[codigo].grupo === grupo;
}

/** Estados que todavía cambian solos (los mueve el worker): justifican refrescar la grilla cada tanto. */
export function cambiaSolo(codigo: CodigoEstado | null): boolean {
    return codigo === ESTADO.subido || codigo === ESTADO.procesando;
}
