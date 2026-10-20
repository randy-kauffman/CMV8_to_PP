// Textos de la interfaz: se leen de strings/*.resx (generados desde docs/diseno/anatomia-y-tokens/textos.es.json)
// con context.resources.getString, para que la plataforma elija el idioma del usuario (3082 español, 1033 inglés).
import { ClaveTexto } from "./textos.generado";

export type { ClaveTexto } from "./textos.generado";

/** Valores para los {placeholders} de un texto. Ya formateados: el texto no formatea números ni tamaños. */
export type ValoresTexto = Readonly<Record<string, string | number>>;

/** Traduce una clave y reemplaza sus {placeholders}. Es la única dependencia de textos que ve la vista. */
export type Traductor = (clave: ClaveTexto, valores?: ValoresTexto) => string;

/**
 * Reemplaza cada {nombre} por su valor. Un placeholder sin valor queda tal cual (se nota en pantalla y en revisión,
 * en vez de esconderse como cadena vacía). Función pura para poder probarla aparte.
 * Regla de textos.es.json: nunca se interpolan URLs, tokens, rutas ni ids internos; eso lo cuida quien llama.
 */
export function interpolar(plantilla: string, valores?: ValoresTexto): string {
    if (!valores) return plantilla;
    return plantilla.replace(/\{([a-zA-Z]+)\}/g, (completo: string, nombre: string) => {
        const v = valores[nombre];
        return v === undefined ? completo : String(v);
    });
}

/**
 * Crea el traductor sobre los recursos del PCF. Cachea cada clave porque getString cruza al host en cada llamada
 * y la vista pide los mismos textos en cada repintado. Si el recurso falta, getString devuelve vacío o la clave:
 * se muestra la clave para que el faltante se vea en la prueba en lugar de dejar un hueco.
 */
export function crearTraductor(recursos: ComponentFramework.Resources): Traductor {
    const cache = new Map<ClaveTexto, string>();
    return (clave: ClaveTexto, valores?: ValoresTexto): string => {
        let plantilla = cache.get(clave);
        if (plantilla === undefined) {
            const leido = recursos.getString(clave);
            plantilla = leido && leido.length > 0 ? leido : clave;
            cache.set(clave, plantilla);
        }
        return interpolar(plantilla, valores);
    };
}
