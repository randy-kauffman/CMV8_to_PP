// Textos de la grilla: las claves comunes de textos.es.json (ClaveTexto) más las propias (TEXTOS_GRILLA del
// generador). Mismo mecanismo que CargaAdjuntoBlob: strings/*.resx leídos con context.resources.getString.
import { ClaveTexto, interpolar, Traductor, ValoresTexto } from "../CargaAdjuntoBlob/textos";
import { ClaveGrillaPropia } from "./textosGrilla.generado";

export type ClaveGrilla = ClaveTexto | ClaveGrillaPropia;

/** Traductor ampliado. Acepta todas las claves comunes, así que también sirve donde la vista de carga pide Traductor. */
export type TraductorGrilla = (clave: ClaveGrilla, valores?: ValoresTexto) => string;

export function crearTraductorGrilla(recursos: ComponentFramework.Resources): TraductorGrilla {
    const cache = new Map<ClaveGrilla, string>();
    return (clave: ClaveGrilla, valores?: ValoresTexto): string => {
        let plantilla = cache.get(clave);
        if (plantilla === undefined) {
            const leido = recursos.getString(clave);
            plantilla = leido && leido.length > 0 ? leido : clave;
            cache.set(clave, plantilla);
        }
        return interpolar(plantilla, valores);
    };
}

/** Comprobación de tipos: un TraductorGrilla se puede pasar a la vista de carga sin adaptar. */
export const comoTraductor = (t: TraductorGrilla): Traductor => t;
