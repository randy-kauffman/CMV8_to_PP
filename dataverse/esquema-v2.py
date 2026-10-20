#!/usr/bin/env python3
"""Esquema v2 de Dataverse (ADR-003 §1): choice global de estados, catálogo de tipos de documento,
propiedades de Content Manager, conflictos, y las columnas v2 de la primera tabla de adjuntos.

Idempotente: lo que existe se salta; lo que falta se crea dentro de la solución cmv8ingesta.
Uso:
  DATAVERSE_URL=https://org.crm.dynamics.com python3 dataverse/esquema-v2.py            # aplica
  python3 dataverse/esquema-v2.py --dump dataverse/metadata/v2                           # solo vuelca el JSON
Requiere: az login con un administrador del entorno. Sin dependencias fuera de la biblioteca estándar.
"""
from __future__ import annotations

import argparse
import json
import os
import pathlib
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

SOLUCION = "cmv8ingesta"
PREFIJO = "cmv8"
IDIOMA = 3082
TABLA_ADJUNTOS_V1 = "cmv8_solicitudingesta"  # primera tabla de adjuntos (ADR-003 §12)


# ---------- helpers de metadatos ----------

def label(texto: str) -> dict:
    return {"@odata.type": "Microsoft.Dynamics.CRM.Label",
            "LocalizedLabels": [{"@odata.type": "Microsoft.Dynamics.CRM.LocalizedLabel", "Label": texto, "LanguageCode": IDIOMA}]}


def base(schema: str, nombre: str, descripcion: str = "", requerido: bool = False) -> dict:
    d = {"SchemaName": schema, "DisplayName": label(nombre),
         "RequiredLevel": {"Value": "ApplicationRequired" if requerido else "None"}}
    if descripcion:
        d["Description"] = label(descripcion)
    return d


def texto(schema, nombre, largo=200, descripcion="", requerido=False):
    return {"@odata.type": "Microsoft.Dynamics.CRM.StringAttributeMetadata", "MaxLength": largo,
            "FormatName": {"Value": "Text"}, **base(schema, nombre, descripcion, requerido)}


def memo(schema, nombre, descripcion="", largo=4000):
    return {"@odata.type": "Microsoft.Dynamics.CRM.MemoAttributeMetadata", "MaxLength": largo,
            "FormatName": {"Value": "Text"}, **base(schema, nombre, descripcion)}


def entero(schema, nombre, descripcion="", minimo=0, maximo=2147483647):
    return {"@odata.type": "Microsoft.Dynamics.CRM.IntegerAttributeMetadata", "Format": "None",
            "MinValue": minimo, "MaxValue": maximo, **base(schema, nombre, descripcion)}


def booleano(schema, nombre, descripcion="", por_defecto=False):
    return {"@odata.type": "Microsoft.Dynamics.CRM.BooleanAttributeMetadata", "DefaultValue": por_defecto,
            "OptionSet": {"@odata.type": "Microsoft.Dynamics.CRM.BooleanOptionSetMetadata",
                          "TrueOption": {"Value": 1, "Label": label("Sí")},
                          "FalseOption": {"Value": 0, "Label": label("No")}},
            **base(schema, nombre, descripcion)}


def fecha(schema, nombre, descripcion=""):
    return {"@odata.type": "Microsoft.Dynamics.CRM.DateTimeAttributeMetadata", "Format": "DateAndTime",
            "DateTimeBehavior": {"Value": "UserLocal"}, **base(schema, nombre, descripcion)}


def opciones(valores: list[tuple[int, str]]) -> list[dict]:
    return [{"Value": v, "Label": label(t)} for v, t in valores]


def eleccion(schema, nombre, valores, descripcion="", requerido=False, por_defecto=None):
    d = {"@odata.type": "Microsoft.Dynamics.CRM.PicklistAttributeMetadata",
         "OptionSet": {"@odata.type": "Microsoft.Dynamics.CRM.OptionSetMetadata", "IsGlobal": False,
                       "OptionSetType": "Picklist", "Options": opciones(valores)},
         **base(schema, nombre, descripcion, requerido)}
    if por_defecto is not None:
        d["DefaultFormValue"] = por_defecto
    return d


def eleccion_global(schema, nombre, choice, descripcion="", requerido=False, por_defecto=None):
    d = {"@odata.type": "Microsoft.Dynamics.CRM.PicklistAttributeMetadata",
         "GlobalOptionSet@odata.bind": f"/GlobalOptionSetDefinitions(Name='{choice}')",
         **base(schema, nombre, descripcion, requerido)}
    if por_defecto is not None:
        d["DefaultFormValue"] = por_defecto
    return d


def tabla(schema, singular, plural, descripcion, primaria_descripcion):
    return {"@odata.type": "Microsoft.Dynamics.CRM.EntityMetadata", "SchemaName": schema,
            "DisplayName": label(singular), "DisplayCollectionName": label(plural), "Description": label(descripcion),
            "OwnershipType": "OrganizationOwned", "IsActivity": False, "HasNotes": False, "HasActivities": False,
            "Attributes": [{"@odata.type": "Microsoft.Dynamics.CRM.StringAttributeMetadata", "SchemaName": f"{PREFIJO}_Nombre",
                            "IsPrimaryName": True, "MaxLength": 200, "FormatName": {"Value": "Text"},
                            "RequiredLevel": {"Value": "ApplicationRequired"}, "DisplayName": label("Nombre"),
                            "Description": label(primaria_descripcion)}]}


def relacion(schema, padre, hijo, lookup_schema, lookup_nombre, descripcion, requerido=False, borrado="Cascade"):
    """1:N padre → hijo con la lookup en el hijo. borrado: Cascade (hijo muere con el padre) o Restrict."""
    return {"@odata.type": "Microsoft.Dynamics.CRM.OneToManyRelationshipMetadata", "SchemaName": schema,
            "ReferencedEntity": padre, "ReferencingEntity": hijo,
            "AssociatedMenuConfiguration": {"Behavior": "UseCollectionName", "Group": "Details", "Order": 10000},
            "CascadeConfiguration": {"Assign": "NoCascade", "Delete": borrado, "Merge": "NoCascade",
                                     "Reparent": "NoCascade", "Share": "NoCascade", "Unshare": "NoCascade"},
            "Lookup": {"@odata.type": "Microsoft.Dynamics.CRM.LookupAttributeMetadata", "SchemaName": lookup_schema,
                       "DisplayName": label(lookup_nombre), "Description": label(descripcion),
                       "RequiredLevel": {"Value": "ApplicationRequired" if requerido else "None"}}}


# ---------- especificación v2 ----------

CHOICE_ESTADO = {
    "@odata.type": "Microsoft.Dynamics.CRM.OptionSetMetadata", "Name": f"{PREFIJO}_estadoingesta", "IsGlobal": True,
    "OptionSetType": "Picklist", "DisplayName": label("Estado de ingesta CMV8"),
    "Description": label("Los diez estados del ciclo de vida de un adjunto (ADR-003 §2): dónde está el archivo y quién tiene que actuar. "
                         "Lo entrega la solución base; cada tabla de adjuntos liga su columna estadoingesta a este choice."),
    "Options": opciones([(1, "Creada"), (2, "Subido"), (3, "Procesando"), (4, "Archivado"), (5, "Rechazado"), (6, "Error"),
                         (7, "Almacenado"), (8, "Observada"), (9, "Detenida"), (10, "Depurado")]),
}

TABLAS = {
    f"{PREFIJO}_tipodocumento": tabla(
        f"{PREFIJO}_TipoDocumento", "Tipo de documento", "Tipos de documento",
        "Catálogo de tipos de documento (ADR-003 §1.2). Los descubiertos los escribe la Function desde Content Manager por CMIS; "
        "los locales los crea el administrador y nunca van a Content Manager. Lo humano: en uso, carpeta, destino, momento, retención, inmutabilidad.",
        "Nombre visible del tipo. En los descubiertos, el que publica Content Manager."),
    f"{PREFIJO}_propiedadcm": tabla(
        f"{PREFIJO}_PropiedadCm", "Propiedad de Content Manager", "Propiedades de Content Manager",
        "Propiedades escribibles de un tipo descubierto (ADR-003 §1.2). Lo descubierto lo escribe la Function; lo humano es la decisión usar/omitir y el origen del valor.",
        "Nombre canónico de la propiedad (id de wire sin prefijo)."),
    f"{PREFIJO}_conflicto": tabla(
        f"{PREFIJO}_Conflicto", "Conflicto de catálogo", "Conflictos de catálogo",
        "Diferencias entre el catálogo y Content Manager detectadas por el worker o por VigilarCatalogo (ADR-003 §4.4). Una fila por conflicto; "
        "la deduplicación por tipo + propiedad + clase vive en la Azure Table.",
        "Resumen legible del conflicto."),
}

COLUMNAS = {
    f"{PREFIJO}_tipodocumento": [
        texto(f"{PREFIJO}_Codigo", "Código", 20, "ID corto del tipo (derivado al descubrir desde la propiedad convencional o el prefijo del nombre visible; editable)."),
        eleccion(f"{PREFIJO}_OrigenTipo", "Origen del tipo", [(1, "Descubierto"), (2, "Local")],
                 "Descubierto: viene de Content Manager y tiene ItemType. Local: lo creó el administrador, no tiene ItemType y su destino es Solo Blob.", requerido=True, por_defecto=2),
        texto(f"{PREFIJO}_TipoCmisId", "Id del tipo CMIS", 400, "Id de wire del ItemType en Content Manager. Solo descubiertos."),
        texto(f"{PREFIJO}_LocalName", "Nombre local CMIS", 200, "localName que publica Content Manager. Solo descubiertos."),
        booleano(f"{PREFIJO}_Versionable", "Versionable", "Si el tipo es versionable en Content Manager."),
        booleano(f"{PREFIJO}_AusenteEnServidor", "Ausente en el servidor", "Content Manager dejó de publicar este tipo. Se marca, no se borra."),
        booleano(f"{PREFIJO}_Revisado", "Revisado", "El administrador revisó las decisiones de este tipo. Vuelve a No cuando ActualizarTipos detecta cambios."),
        memo(f"{PREFIJO}_Cambios", "Cambios", "Lista de cambios detectados por la última actualización (propiedad nueva, largo distinto, propiedad que desapareció)."),
        fecha(f"{PREFIJO}_DescubiertoEn", "Descubierto en", "Última vez que la Function leyó este tipo de Content Manager."),
        booleano(f"{PREFIJO}_EnUso", "En uso", "Solo los tipos en uso se validan, se vigilan y se ofrecen a los makers."),
        texto(f"{PREFIJO}_Carpeta", "Carpeta en Content Manager", 400, "Ruta de la carpeta destino. Derivada del nombre local o fijada a mano."),
        eleccion(f"{PREFIJO}_OrigenCarpeta", "Origen de la carpeta", [(1, "Derivada"), (2, "Manual")],
                 "Una carpeta manual no se pisa en ActualizarTipos.", por_defecto=1),
        eleccion(f"{PREFIJO}_Destino", "Destino", [(1, "Content Manager"), (2, "Solo Blob")],
                 "A dónde va el archivo. Un tipo local siempre es Solo Blob.", requerido=True, por_defecto=1),
        eleccion(f"{PREFIJO}_Momento", "Momento del archivado", [(1, "Al subir"), (2, "Diferido")],
                 "Solo con destino Content Manager. Diferido: espera en Almacenado hasta la operación Archivar.", por_defecto=1),
        entero(f"{PREFIJO}_DiasRetencion", "Días de retención en Blob", "Días que el archivo queda en el depósito. Vacío = permanente."),
        booleano(f"{PREFIJO}_Inmutable", "Inmutable (WORM)", "Política de inmutabilidad a nivel de versión al mover el blob. Exige días de retención."),
    ],
    f"{PREFIJO}_propiedadcm": [
        texto(f"{PREFIJO}_PropiedadId", "Id de propiedad CMIS", 400, "Id de wire completo (con prefijo) tal como lo exige Content Manager."),
        texto(f"{PREFIJO}_NombreVisible", "Nombre visible", 200, "displayName que publica Content Manager."),
        texto(f"{PREFIJO}_TipoDato", "Tipo de dato CMIS", 50, "string, integer, decimal, datetime, boolean, id, uri, html."),
        booleano(f"{PREFIJO}_Obligatoria", "Obligatoria en CM", "required según Content Manager."),
        texto(f"{PREFIJO}_ValorPorDefecto", "Valor por defecto en CM", 400, "defaultValue que publica Content Manager; si existe, se sugiere omitir."),
        entero(f"{PREFIJO}_LargoMaximo", "Largo máximo", "maxLength según Content Manager."),
        memo(f"{PREFIJO}_ListaValores", "Lista de valores", "choices que publica Content Manager, una por línea."),
        booleano(f"{PREFIJO}_Heredada", "Heredada", "La propiedad viene de un tipo padre."),
        eleccion(f"{PREFIJO}_Decision", "Decisión", [(1, "Usar"), (2, "Omitir")],
                 "Usar: se envía en cada archivado y exige un origen. Omitir: gana el default del servidor. Sugerida Usar si es obligatoria sin default.", requerido=True, por_defecto=2),
        eleccion(f"{PREFIJO}_Origen", "Origen del valor", [(1, "Columna de la tabla de adjuntos"), (2, "Consulta AS400"), (3, "Sistema"), (4, "Literal")],
                 "De dónde sale el valor. Columna: nombre lógico convenido en la tabla de adjuntos. Consulta: nombre declarado en la configuración de Azure (el SQL nunca vive acá). Sistema: id, nombre de archivo, content type."),
        texto(f"{PREFIJO}_NombreLogico", "Nombre lógico", 100, "Para origen Columna: nombre sin prefijo; la Function lee <prefijo>_<nombrelogico> de la tabla de adjuntos. Para Sistema: id, nombrearchivo, contenttype."),
        texto(f"{PREFIJO}_Consulta", "Consulta AS400", 100, "Nombre de la consulta con nombre (AS400_CONSULTAS_JSON) y la columna del resultado, como consulta.columna."),
        texto(f"{PREFIJO}_Literal", "Literal", 400, "Para origen Literal: el valor fijo."),
        texto(f"{PREFIJO}_Patron", "Patrón", 400, "Expresión regular que debe cumplir el valor. Se valida en la app, en el broker y en el worker."),
        eleccion(f"{PREFIJO}_MomentoValidacion", "Momento de validación de existencia", [(1, "Al archivar"), (2, "Antes de subir")],
                 "Solo para origen Consulta AS400: si la consulta es rápida, el broker la corre antes de emitir la SAS.", por_defecto=1),
    ],
    f"{PREFIJO}_conflicto": [
        eleccion(f"{PREFIJO}_Clase", "Clase", [(1, "Catálogo desactualizado"), (2, "Obligatoria sin origen"), (3, "Columna inexistente"),
                                              (4, "Carpeta inexistente"), (5, "Tipo ausente en el servidor"), (6, "Rechazo de CM no clasificado")],
                 "Qué tipo de conflicto es; define quién lo resuelve y cómo.", requerido=True),
        eleccion(f"{PREFIJO}_Severidad", "Severidad", [(1, "Bloquea"), (2, "Aviso")],
                 "Bloquea: Content Manager rechazaría el archivado; los adjuntos quedan Detenida. Aviso: el catálogo es más estricto que CM; no detiene nada.", requerido=True, por_defecto=1),
        memo(f"{PREFIJO}_Detalle", "Detalle", "Qué se esperaba, qué publica Content Manager y qué hay que cambiar."),
        fecha(f"{PREFIJO}_DetectadoEn", "Detectado en"),
        eleccion(f"{PREFIJO}_EstadoConflicto", "Estado", [(1, "Abierto"), (2, "Resuelto")], "Resolver dispara ReanudarConflicto.", requerido=True, por_defecto=1),
        fecha(f"{PREFIJO}_ResueltoEn", "Resuelto en"),
        entero(f"{PREFIJO}_AdjuntosDetenidos", "Adjuntos detenidos", "Cantidad de adjuntos en Detenida por este conflicto, según la Azure Table."),
        texto(f"{PREFIJO}_Clave", "Clave", 400, "tipo + propiedad + clase; la misma que usa la Azure Table para deduplicar."),
    ],
    TABLA_ADJUNTOS_V1: [
        eleccion_global(f"{PREFIJO}_EstadoIngesta", "Estado de ingesta", f"{PREFIJO}_estadoingesta",
                        "Columna reservada v2 (ADR-003 §1.1), ligada al choice global. Reemplaza a cmv8_estado cuando la Function pase a v2.", por_defecto=1),
    ],
}

RELACIONES = [
    relacion(f"{PREFIJO}_tipodocumento_propiedadcm", f"{PREFIJO}_tipodocumento", f"{PREFIJO}_propiedadcm",
             f"{PREFIJO}_TipoDocumento", "Tipo de documento", "Tipo al que pertenece la propiedad.", requerido=True, borrado="Cascade"),
    relacion(f"{PREFIJO}_tipodocumento_conflicto", f"{PREFIJO}_tipodocumento", f"{PREFIJO}_conflicto",
             f"{PREFIJO}_TipoDocumento", "Tipo de documento", "Tipo afectado por el conflicto.", requerido=True, borrado="Cascade"),
    relacion(f"{PREFIJO}_propiedadcm_conflicto", f"{PREFIJO}_propiedadcm", f"{PREFIJO}_conflicto",
             f"{PREFIJO}_PropiedadCm", "Propiedad", "Propiedad afectada, si el conflicto es de una propiedad.", borrado="RemoveLink"),
    relacion(f"{PREFIJO}_tipodocumento_solicitudingesta", f"{PREFIJO}_tipodocumento", TABLA_ADJUNTOS_V1,
             f"{PREFIJO}_TipoDocumento", "Tipo de documento", "Columna reservada v2: tipo de documento del adjunto.", requerido=True, borrado="Restrict"),
]


# ---------- cliente Web API ----------

class Dataverse:
    def __init__(self, url: str):
        self.api = url.rstrip("/") + "/api/data/v9.2"
        token = subprocess.run(["az", "account", "get-access-token", "--resource", url, "--query", "accessToken", "-o", "tsv"],
                               check=True, capture_output=True, text=True).stdout.strip()
        self.h = {"Authorization": f"Bearer {token}", "OData-MaxVersion": "4.0", "OData-Version": "4.0",
                  "Accept": "application/json", "Content-Type": "application/json; charset=utf-8"}

    def _req(self, metodo: str, ruta: str, cuerpo: dict | None = None, solucion: bool = False) -> tuple[int, dict | None]:
        datos = json.dumps(cuerpo).encode("utf-8") if cuerpo is not None else None
        h = dict(self.h)
        if solucion:
            h["MSCRM.SolutionUniqueName"] = SOLUCION
        req = urllib.request.Request(f"{self.api}/{ruta}", data=datos, headers=h, method=metodo)
        try:
            with urllib.request.urlopen(req) as r:
                cuerpo_r = r.read()
                return r.status, (json.loads(cuerpo_r) if cuerpo_r else None)
        except urllib.error.HTTPError as e:
            texto_e = e.read().decode("utf-8", "replace")
            try:
                msg = json.loads(texto_e).get("error", {}).get("message", texto_e)
            except Exception:
                msg = texto_e
            return e.code, {"error": msg[:400]}

    def get(self, ruta):
        return self._req("GET", ruta)

    def post(self, ruta, cuerpo):
        return self._req("POST", ruta, cuerpo, solucion=True)

    def existe(self, ruta) -> bool:
        code, _ = self.get(ruta)
        return code == 200


def q(s: str) -> str:
    """Codifica lo que haga falta sin tocar los separadores de OData (?, &, $, =, comillas, paréntesis)."""
    return urllib.parse.quote(s, safe="=(),'$?&/")


def aplicar(dv: Dataverse) -> int:
    fallos = 0

    def paso(nombre, code, extra=None):
        nonlocal fallos
        ok = 200 <= code < 300
        print(f"  {'ok ' if ok else 'ERR'} {code} {nombre}" + (f" :: {extra['error']}" if (not ok and extra) else ""))
        if not ok:
            fallos += 1

    code, who = dv.get("WhoAmI")
    print(f"== Quién soy: {who.get('UserId') if who else code}")

    print(f"== Choice global {CHOICE_ESTADO['Name']}")
    ruta_choice = q(f"GlobalOptionSetDefinitions(Name='{CHOICE_ESTADO['Name']}')")
    if dv.existe(ruta_choice):
        print("  existe")
    else:
        code, r = dv.post("GlobalOptionSetDefinitions", CHOICE_ESTADO)
        paso("POST GlobalOptionSetDefinitions", code, r)
    # El enlace de una columna a un choice global exige el MetadataId; la clave alterna por Name da
    # "Guid should contain 32 digits" (probado 2026-10-19).
    code, r = dv.get(ruta_choice + "?$select=MetadataId")
    choice_id = r.get("MetadataId") if code == 200 and r else None
    if not choice_id:
        paso("GET MetadataId del choice", code, r)
    for cols in COLUMNAS.values():
        for c in cols:
            if "GlobalOptionSet@odata.bind" in c and choice_id:
                c["GlobalOptionSet@odata.bind"] = f"/GlobalOptionSetDefinitions({choice_id})"

    for logico, meta in TABLAS.items():
        print(f"== Tabla {logico}")
        if dv.existe(q(f"EntityDefinitions(LogicalName='{logico}')?$select=LogicalName")):
            print("  existe")
        else:
            code, r = dv.post("EntityDefinitions", meta)
            paso("POST EntityDefinitions", code, r)

    for logico, cols in COLUMNAS.items():
        print(f"== Columnas de {logico}")
        code, r = dv.get(q(f"EntityDefinitions(LogicalName='{logico}')/Attributes?$select=SchemaName"))
        if code != 200:
            paso("GET Attributes", code, r)
            continue
        existentes = {a["SchemaName"].lower() for a in r["value"]}
        for c in cols:
            if c["SchemaName"].lower() in existentes:
                print(f"  existe: {c['SchemaName']}")
                continue
            code, rr = dv.post(q(f"EntityDefinitions(LogicalName='{logico}')/Attributes"), c)
            paso(f"POST {c['SchemaName']}", code, rr)

    print("== Relaciones")
    for rel in RELACIONES:
        if dv.existe(q(f"RelationshipDefinitions(SchemaName='{rel['SchemaName']}')")):
            print(f"  existe: {rel['SchemaName']}")
            continue
        code, r = dv.post("RelationshipDefinitions", rel)
        paso(f"POST {rel['SchemaName']}", code, r)

    print("== Publicar")
    code, r = dv._req("POST", "PublishAllXml", {})
    paso("PublishAllXml", code, r)
    return fallos


def volcar(destino: pathlib.Path) -> None:
    destino.mkdir(parents=True, exist_ok=True)
    (destino / "choice-estadoingesta.json").write_text(json.dumps(CHOICE_ESTADO, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for logico, meta in TABLAS.items():
        (destino / f"tabla-{logico}.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for logico, cols in COLUMNAS.items():
        (destino / f"columnas-{logico}.json").write_text(json.dumps(cols, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (destino / "relaciones.json").write_text(json.dumps(RELACIONES, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"JSON volcado en {destino}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dump", metavar="DIR", help="solo volcar el JSON generado en DIR, sin tocar el entorno")
    args = ap.parse_args()
    if args.dump:
        volcar(pathlib.Path(args.dump))
        return 0
    url = os.environ.get("DATAVERSE_URL")
    if not url:
        print("Falta DATAVERSE_URL (por ejemplo https://org.crm.dynamics.com)", file=sys.stderr)
        return 2
    fallos = aplicar(Dataverse(url))
    print("Listo." if fallos == 0 else f"Terminó con {fallos} error(es).")
    return 1 if fallos else 0


if __name__ == "__main__":
    sys.exit(main())
