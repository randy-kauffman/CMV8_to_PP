// Tema de la grilla: mismas variables --fu-* que CargaAdjuntoBlob (tokens.ts) con las propiedades del maker.
// Se calcula igual que en CargaAdjuntoBlob/index.ts; acá la densidad la puede cambiar el usuario desde la grilla.
import { calcularVariables, Densidad, ModoColor, ValoresMaker, Variante } from "../CargaAdjuntoBlob/tokens";
import { NOMBRES_MAKER } from "../CargaAdjuntoBlob/tokens.generado";
import { AtributosTema } from "../CargaAdjuntoBlob/vista/vistaControl";
import { IInputs } from "./generated/ManifestTypes";

const VARIANTES: readonly Variante[] = ["fluent", "institucional", "moderno"];

export interface Tema {
    readonly clave: string;
    readonly variables: Record<string, string>;
    readonly atributos: AtributosTema;
}

export function elegir<T extends string>(valor: unknown, opciones: readonly T[], defecto: T): T {
    return typeof valor === "string" && (opciones as readonly string[]).includes(valor) ? (valor as T) : defecto;
}

export function calcularTema(
    p: IInputs,
    oscuroDelSistema: boolean,
    densidadUsuario: "compacta" | "comoda" | null
): Tema {
    const variante = elegir<Variante>(p.variante.raw, VARIANTES, "fluent");
    const densidadElegida = densidadUsuario ?? (p.densidad.raw === "compacta" ? "compacta" : "comoda");
    const densidad: Densidad = densidadElegida === "compacta" ? "compact" : "comfortable";
    const modoEntrada = elegir(p.modo.raw, ["claro", "oscuro", "auto"] as const, "claro");
    const modo: ModoColor = modoEntrada === "oscuro" || (modoEntrada === "auto" && oscuroDelSistema) ? "dark" : "light";
    const maker: ValoresMaker = {};
    for (const nombre of NOMBRES_MAKER) {
        const v = p[nombre].raw;
        if (v !== null && v !== undefined && v !== "") maker[nombre] = v;
    }
    const clave = JSON.stringify([variante, densidad, modo, maker]);
    const variables = calcularVariables({
        variante,
        modo,
        densidad,
        maker,
        esValorCss: (propiedad, valor) => typeof CSS !== "undefined" && CSS.supports(propiedad, valor),
    });
    return { clave, variables, atributos: { variante, densidad: densidadElegida, modo } };
}
