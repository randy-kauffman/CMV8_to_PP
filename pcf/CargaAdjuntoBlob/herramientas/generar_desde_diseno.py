#!/usr/bin/env python3
"""
Genera los artefactos de los dos PCF (CargaAdjuntoBlob para canvas y AdjuntosGrilla para model-driven) a partir del
diseño aprobado, para que el código, los manifiestos y los recursos no se desincronicen a mano.

Fuentes (no se leen en runtime: el PCF no puede leer archivos del repo):
  docs/diseno/anatomia-y-tokens/tokens.json     -> tokens.generado.ts (lo comparten los dos controles)
  docs/diseno/anatomia-y-tokens/textos.es.json  -> textos.generado.ts y strings/*.resx de cada control
  docs/diseno/prototipo/_icons_raw.json         -> vista/iconos.generado.ts y AdjuntosGrilla/vista/iconosGrilla.generado.ts
  PROPIEDADES (abajo)                           -> ControlManifest.Input.xml, generated/ManifestTypes.d.ts y resx
  CargaAdjuntoBlob/css/CargaAdjuntoBlob.css     -> AdjuntosGrilla/css/base.generado.css (la grilla reutiliza la vista
                                                   de carga; un manifiesto no puede referenciar CSS fuera de su carpeta)

Uso (desde la raíz del repo):  python3 pcf/CargaAdjuntoBlob/herramientas/generar_desde_diseno.py
No es un build: solo escribe archivos de texto. Si editás el manifiesto a mano, reflejalo acá o se pisa.
"""
import json
import os
from xml.sax.saxutils import escape

RAIZ = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
CONTROL = os.path.join(RAIZ, "pcf", "CargaAdjuntoBlob")
DISENO = os.path.join(RAIZ, "docs", "diseno")

VERSION_CONTROL = "1.0.0"

# Íconos Fluent UI System Icons que usa la anatomía (Anatomia.md §1, §3 y §5). Nada más, para no inflar el bundle.
ICONOS = [
    "arrow_upload_24_regular", "arrow_upload_20_regular", "arrow_upload_16_regular",
    "clock_16_regular", "arrow_sync_16_regular", "shield_checkmark_16_regular",
    "checkmark_circle_16_filled", "error_circle_16_filled", "dismiss_circle_16_regular", "warning_16_filled",
    "document_pdf_24_regular", "image_24_regular", "video_24_regular", "document_24_regular",
    "dismiss_20_regular", "arrow_clockwise_20_regular",
]

# Nombre visible en español de cada propiedad de token del maker (el valor de "maker" en tokens.json).
NOMBRES_MAKER = {
    "PrimaryColor": "Color primario",
    "PrimaryHoverColor": "Color primario al pasar el puntero",
    "OnPrimaryColor": "Color de texto sobre primario",
    "BackgroundColor": "Color de fondo",
    "SurfaceColor": "Color de superficie",
    "BorderColor": "Color de borde",
    "TextColor": "Color de texto",
    "TextSecondaryColor": "Color de texto secundario",
    "SuccessColor": "Color de éxito",
    "WarningColor": "Color de advertencia",
    "InfoColor": "Color de información",
    "DangerColor": "Color de error",
    "NeutralColor": "Color neutro",
    "FontFamily": "Familia tipográfica",
    "FontSize": "Tamaño de texto (px)",
    "FontScale": "Escala tipográfica",
    "BorderRadius": "Radio de borde (px)",
    "BorderWidth": "Grosor de borde (px)",
}
# Tokens del maker que son números enteros en px (Whole.None); el resto es texto (colores, familia y escala).
MAKER_ENTEROS = {"FontSize", "BorderRadius", "BorderWidth"}

# (nombre, tipo, uso, default, nombre visible, descripción, valores de enum o None)
PROPIEDADES_BASE = [
    ("lote", "Multiple", "input", None, "Lote de SAS",
     "JSON con la respuesta de EmitirSasLote tal cual la devuelve el conector ({items, bloqueBytes, versionServicio, "
     "tokenStorage, tokenStorageExpiraUtc}). Secreto: guardarlo en UNA variable (varLote), nunca en una colección ni "
     "en Dataverse; limpiarlo cuando estado deja de ser listo-para-sas. El control lo consume una vez y no lo guarda.", None),
    ("tabla", "SingleLine.Text", "input", None, "Tabla de adjuntos",
     "Nombre lógico de la tabla de adjuntos de la solución (por ejemplo cred_adjuntocredito). En canvas solo se valida; "
     "la usa el modo model-driven para llamar al broker.", None),
    ("tiposAceptados", "SingleLine.Text", "input", "video/*,application/pdf", "Tipos aceptados",
     "Lista separada por comas con la sintaxis del atributo accept: extensiones (.pdf), tipos MIME (application/pdf) o "
     "comodines (video/*). Vacío acepta todo.", None),
    ("tamanoMaximoMB", "Whole.None", "input", "250", "Tamaño máximo (MB)",
     "Tamaño máximo por archivo, en MB (base 1024). Por defecto 250.", None),
    ("loteMaximo", "Whole.None", "input", "20", "Archivos por lote",
     "Cantidad máxima de archivos en la cola (y por pedido de SAS). Por defecto 20, igual que el broker.", None),
    ("paralelismoArchivos", "Whole.None", "input", "2", "Archivos en paralelo",
     "Archivos que se suben a la vez. Por defecto 2; entre 1 y 4.", None),
    ("paralelismoBloques", "Whole.None", "input", "4", "Bloques en paralelo",
     "Bloques en vuelo por archivo. Por defecto 4; entre 1 y 8.", None),
    ("disparadorReinicio", "Whole.None", "input", None, "Disparador de reinicio",
     "Al cambiar su valor, el control cancela todo y vacía la cola. No cambiarlo mientras la app espera EmitirSasLote.", None),
    ("densidad", "Enum", "input", "comoda", "Densidad", "Compacta o cómoda.",
     [("compacta", "Compacta"), ("comoda", "Cómoda")]),
    ("variante", "Enum", "input", "fluent", "Variante visual",
     "Fluent nativo, institucional sobrio o moderno con tarjetas.",
     [("fluent", "Fluent nativo"), ("institucional", "Institucional sobrio"), ("moderno", "Moderno con tarjetas")]),
    ("modo", "Enum", "input", "claro", "Modo de color",
     "Claro, oscuro o automático (sigue la preferencia del sistema operativo).",
     [("claro", "Claro"), ("oscuro", "Oscuro"), ("auto", "Automático")]),
]

PROPIEDADES_SALIDA = [
    ("pendientes", "Multiple", "Pendientes de SAS",
     "JSON [{indice, id, nombreArchivo, tamanoBytes, contentType}] del pedido de SAS en curso. id viene vacío si el "
     "archivo todavía no tiene fila; la app crea la fila, llama EmitirSasLote en el mismo orden y deja la respuesta en varLote."),
    ("numeroPedido", "Whole.None", "Número de pedido",
     "Crece cada vez que el control publica un pedido nuevo en pendientes. La app atiende cada número una sola vez."),
    ("resultados", "Multiple", "Resultados",
     "JSON [{id, estado, rutaBlob, nombreArchivo, tamanoBytes, contentType, mensajeError, duracionMs, velocidadMbps}] "
     "de los archivos con fila que terminaron (subido, error o cancelado)."),
    ("resumen", "SingleLine.Text", "Resumen", "Texto del resumen de la cola, el mismo que muestra la barra de cola."),
    ("progreso", "Whole.None", "Progreso", "Avance global de 0 a 100 por bytes confirmados."),
    ("estado", "SingleLine.Text", "Estado",
     "vacio, encola, listo-para-sas, subiendo, completado o con-errores."),
    ("cantidadEnCola", "Whole.None", "En cola", "Archivos esperando subir (en cola, solicitando permiso o esperando turno)."),
    ("cantidadSubidos", "Whole.None", "Subidos", "Archivos subidos."),
    ("cantidadConError", "Whole.None", "Con error", "Archivos con error de subida."),
]


def leer_json(*partes):
    with open(os.path.join(*partes), encoding="utf-8") as f:
        return json.load(f)


def escribir(ruta, contenido):
    os.makedirs(os.path.dirname(ruta), exist_ok=True)
    with open(ruta, "w", encoding="utf-8", newline="\n") as f:
        f.write(contenido)
    print("escrito", os.path.relpath(ruta, RAIZ))


def ts_str(s):
    return json.dumps(s, ensure_ascii=False)


tokens = leer_json(DISENO, "anatomia-y-tokens", "tokens.json")
textos = leer_json(DISENO, "anatomia-y-tokens", "textos.es.json")
iconos = leer_json(DISENO, "prototipo", "_icons_raw.json")

# ---------------------------------------------------------------- tokens.generado.ts
lineas = [
    "// GENERADO por herramientas/generar_desde_diseno.py desde docs/diseno/anatomia-y-tokens/tokens.json.",
    "// No editar a mano: cambiar tokens.json y regenerar. Se embebe como constante porque el PCF no lee archivos.",
    "",
    'export type Variante = "fluent" | "institucional" | "moderno";',
    'export type ModoColor = "light" | "dark";',
    'export type Densidad = "compact" | "comfortable";',
    "",
    "/** Valor de un token por variante: fijo, por modo de color o por densidad (meta.valueShapes de tokens.json). */",
    "export type ValorToken = string | { readonly light: string; readonly dark: string } | "
    "{ readonly compact: string; readonly comfortable: string };",
    "",
    "export interface DefinicionToken {",
    "    readonly css: string;",
    "    readonly maker?: NombreMaker;",
    "    readonly valores: Readonly<Record<Variante, ValorToken>>;",
    "}",
    "",
]
makers = [t["maker"] for t in tokens["tokens"] if "maker" in t]
lineas.append("export type NombreMaker =\n    | " + "\n    | ".join(ts_str(m) for m in makers) + ";")
lineas.append("")
lineas.append("export const NOMBRES_MAKER: readonly NombreMaker[] = [" + ", ".join(ts_str(m) for m in makers) + "];")
lineas.append("")
lineas.append("export const TOKENS: readonly DefinicionToken[] = [")
for t in tokens["tokens"]:
    vals = []
    for v in ("fluent", "institucional", "moderno"):
        x = t["values"][v]
        if isinstance(x, str):
            vals.append(f"{v}: {ts_str(x)}")
        elif "light" in x:
            vals.append(f"{v}: {{ light: {ts_str(x['light'])}, dark: {ts_str(x['dark'])} }}")
        else:
            vals.append(f"{v}: {{ compact: {ts_str(x['compact'])}, comfortable: {ts_str(x['comfortable'])} }}")
    maker = f", maker: {ts_str(t['maker'])}" if "maker" in t else ""
    lineas.append(f"    {{ css: {ts_str(t['css'])}{maker}, valores: {{ {', '.join(vals)} }} }},")
lineas.append("];")
lineas.append("")
escribir(os.path.join(CONTROL, "tokens.generado.ts"), "\n".join(lineas))

# ---------------------------------------------------------------- textos.generado.ts
claves = list(textos["strings"].keys())
cont = [
    "// GENERADO por herramientas/generar_desde_diseno.py desde docs/diseno/anatomia-y-tokens/textos.es.json.",
    "// Solo las claves: los textos viven en strings/*.resx y se leen con context.resources.getString.",
    "",
    "export type ClaveTexto =\n    | " + "\n    | ".join(ts_str(c) for c in claves) + ";",
    "",
]
escribir(os.path.join(CONTROL, "textos.generado.ts"), "\n".join(cont))

# ---------------------------------------------------------------- vista/iconos.generado.ts
cont = [
    "// GENERADO por herramientas/generar_desde_diseno.py desde docs/diseno/prototipo/_icons_raw.json",
    "// (Fluent UI System Icons, licencia MIT). Solo los íconos que usa la anatomía.",
    "",
    "export interface DefinicionIcono {",
    "    readonly vb: string;",
    "    readonly d: readonly string[];",
    "}",
    "",
    "export type NombreIcono =\n    | " + "\n    | ".join(ts_str(n) for n in ICONOS) + ";",
    "",
    "export const ICONOS: Readonly<Record<NombreIcono, DefinicionIcono>> = {",
]
for n in ICONOS:
    ic = iconos[n]
    cont.append(f"    {n}: {{ vb: {ts_str(ic['vb'])}, d: [{', '.join(ts_str(d) for d in ic['d'])}] }},")
cont.append("};")
cont.append("")
escribir(os.path.join(CONTROL, "vista", "iconos.generado.ts"), "\n".join(cont))

# ---------------------------------------------------------------- propiedades de tokens del maker
desc_token = {t["maker"]: t["description"] for t in tokens["tokens"] if "maker" in t}
PROPIEDADES_MAKER = []
for m in makers:
    tipo = "Whole.None" if m in MAKER_ENTEROS else "SingleLine.Text"
    d = desc_token[m] + " Vacío = valor de la variante."
    PROPIEDADES_MAKER.append((m, tipo, "input", None, NOMBRES_MAKER[m], d, None))

ENTRADAS = PROPIEDADES_BASE + PROPIEDADES_MAKER

# ---------------------------------------------------------------- ControlManifest.Input.xml
x = [
    '<?xml version="1.0" encoding="utf-8" ?>',
    "<!-- GENERADO por herramientas/generar_desde_diseno.py. Cambiar las propiedades allí y regenerar. -->",
    "<manifest>",
    f'  <control namespace="Banco" constructor="CargaAdjuntoBlob" version="{VERSION_CONTROL}" '
    'display-name-key="CargaAdjuntoBlob_Display" description-key="CargaAdjuntoBlob_Desc" control-type="standard">',
    "    <!-- v2 (ADR-003 §4.1): el control solo habla con Blob Storage (Put Block / Put Block List). El broker lo",
    "         llama la app por el conector. external-service-usage es solo licenciamiento (componente premium).",
    "         Ajustar el dominio real del Storage al desplegar. -->",
    '    <external-service-usage enabled="true">',
    "      <domain>cmv8adjsupfcopn.blob.core.windows.net</domain>",
    "    </external-service-usage>",
    "",
    "    <!-- Entradas -->",
]
for (n, tipo, uso, dflt, _, _, enum) in PROPIEDADES_BASE:
    attrs = f'name="{n}" display-name-key="{n}_Display" description-key="{n}_Desc" of-type="{tipo}" usage="{uso}" required="false"'
    if dflt is not None:
        attrs += f' default-value="{escape(dflt)}"'
    if enum:
        x.append(f"    <property {attrs}>")
        for (v, _) in enum:
            x.append(f'      <value name="{v}" display-name-key="{n}_{v}">{v}</value>')
        x.append("    </property>")
    else:
        x.append(f"    <property {attrs} />")
x.append("")
x.append('    <!-- Tokens del maker (tokens.json, campo "maker"). Vacío = valor de la variante y modo. -->')
for (n, tipo, uso, _, _, _, _) in PROPIEDADES_MAKER:
    x.append(f'    <property name="{n}" display-name-key="{n}_Display" description-key="{n}_Desc" of-type="{tipo}" usage="{uso}" required="false" />')
x.append("")
x.append("    <!-- Salidas -->")
for (n, tipo, _, _) in PROPIEDADES_SALIDA:
    x.append(f'    <property name="{n}" display-name-key="{n}_Display" description-key="{n}_Desc" of-type="{tipo}" usage="output" />')
x += [
    "",
    "    <resources>",
    '      <code path="index.ts" order="1" />',
    '      <css path="css/CargaAdjuntoBlob.css" order="1" />',
    '      <resx path="strings/CargaAdjuntoBlob.1033.resx" version="1.0.0" />',
    '      <resx path="strings/CargaAdjuntoBlob.3082.resx" version="1.0.0" />',
    "    </resources>",
    "  </control>",
    "</manifest>",
    "",
]
escribir(os.path.join(CONTROL, "ControlManifest.Input.xml"), "\n".join(x))

# ---------------------------------------------------------------- generated/ManifestTypes.d.ts
TIPOS_TS = {
    "SingleLine.Text": "ComponentFramework.PropertyTypes.StringProperty",
    "Multiple": "ComponentFramework.PropertyTypes.StringProperty",
    "Whole.None": "ComponentFramework.PropertyTypes.WholeNumberProperty",
}
SALIDA_TS = {"SingleLine.Text": "string", "Multiple": "string", "Whole.None": "number"}
t = [
    "/*",
    "*This is auto generated from the ControlManifest.Input.xml file",
    "*/",
    "",
    "// Define IInputs and IOutputs Type. They should match with ControlManifest.",
    "export interface IInputs {",
]
for (n, tipo, _, _, _, _, enum) in ENTRADAS:
    if enum:
        union = " | ".join(f'"{v}"' for (v, _) in enum)
        t.append(f"    {n}: ComponentFramework.PropertyTypes.EnumProperty<{union}>;")
    else:
        t.append(f"    {n}: {TIPOS_TS[tipo]};")
t.append("}")
t.append("export interface IOutputs {")
for (n, tipo, _, _) in PROPIEDADES_SALIDA:
    t.append(f"    {n}?: {SALIDA_TS[tipo]};")
t.append("}")
t.append("")
escribir(os.path.join(CONTROL, "generated", "ManifestTypes.d.ts"), "\n".join(t))

# ---------------------------------------------------------------- strings/*.resx
manifest_textos = [
    ("CargaAdjuntoBlob_Display", "Carga de adjuntos a Blob"),
    ("CargaAdjuntoBlob_Desc", "Cola de archivos que sube directo del navegador a Azure Blob Storage con las SAS que la "
                              "app obtiene del conector CMV8 Broker (EmitirSasLote)."),
]
for (n, _, _, _, nom, desc, enum) in ENTRADAS:
    manifest_textos.append((f"{n}_Display", nom))
    manifest_textos.append((f"{n}_Desc", desc))
    for (v, etiqueta) in (enum or []):
        manifest_textos.append((f"{n}_{v}", etiqueta))
for (n, _, nom, desc) in PROPIEDADES_SALIDA:
    manifest_textos.append((f"{n}_Display", nom))
    manifest_textos.append((f"{n}_Desc", desc))


def resx(marcar_traduccion, textos_manifest=None, textos_extra=None):
    """Recursos de un control: textos del manifiesto, todos los de textos.es.json y los extra del control."""
    textos_manifest = manifest_textos if textos_manifest is None else textos_manifest
    r = [
        '<?xml version="1.0" encoding="utf-8"?>',
        "<!-- GENERADO por herramientas/generar_desde_diseno.py desde textos.es.json y la tabla de propiedades. -->",
        "<root>",
        '  <resheader name="resmimetype"><value>text/microsoft-resx</value></resheader>',
        '  <resheader name="version"><value>2.0</value></resheader>',
        '  <resheader name="reader"><value>System.Resources.ResXResourceReader, System.Windows.Forms, Version=4.0.0.0, Culture=neutral, PublicKeyToken=b77a5c561934e089</value></resheader>',
        '  <resheader name="writer"><value>System.Resources.ResXResourceWriter, System.Windows.Forms, Version=4.0.0.0, Culture=neutral, PublicKeyToken=b77a5c561934e089</value></resheader>',
    ]
    def data(nombre, valor, nota):
        comentario = ("TRADUCIR al inglés. " + nota).strip() if marcar_traduccion else nota
        c = f"<comment>{escape(comentario)}</comment>" if comentario else ""
        r.append(f'  <data name="{escape(nombre)}" xml:space="preserve"><value>{escape(valor)}</value>{c}</data>')
    for (k, v) in textos_manifest:
        data(k, v, "")
    for (k, s) in textos["strings"].items():
        data(k, s["text"], s.get("notes", ""))
    for (k, (v, nota)) in (textos_extra or {}).items():
        data(k, v, nota)
    r.append("</root>")
    r.append("")
    return "\n".join(r)


escribir(os.path.join(CONTROL, "strings", "CargaAdjuntoBlob.3082.resx"), resx(False))
escribir(os.path.join(CONTROL, "strings", "CargaAdjuntoBlob.1033.resx"), resx(True))


# =====================================================================================================================
# AdjuntosGrilla (model-driven, ADR-003 §4.6). Control de dataset que orquesta solo: comparte con CargaAdjuntoBlob
# cola, motor, subida, tokens y vista de carga (los importa por ruta relativa ../CargaAdjuntoBlob/...), y tiene su
# propio manifiesto, recursos, íconos de la grilla y textos extra.
# =====================================================================================================================
GRILLA = os.path.join(RAIZ, "pcf", "AdjuntosGrilla")
VERSION_GRILLA = "1.0.0"

# Íconos de la grilla (Anatomia.md §4, §6, §7 y §8). La vista de carga usa los suyos (vista/iconos.generado.ts).
ICONOS_GRILLA = [
    "document_add_16_regular", "cloud_arrow_up_16_regular", "arrow_sync_16_regular", "archive_16_regular",
    "cloud_checkmark_16_regular", "edit_16_filled", "person_clock_16_filled", "error_circle_16_filled",
    "prohibited_16_filled", "delete_16_regular", "info_16_filled", "warning_16_filled",
    "more_horizontal_20_regular", "arrow_sort_20_regular", "arrow_sort_down_16_regular", "arrow_sort_up_16_regular",
    "text_density_20_regular", "attach_20_regular", "chevron_down_16_regular", "eye_20_regular",
    "arrow_clockwise_20_regular", "arrow_upload_16_regular", "dismiss_20_regular",
    "document_pdf_24_regular", "image_24_regular", "video_24_regular", "document_24_regular",
]

PROPIEDADES_GRILLA = [
    ("urlBroker", "SingleLine.Text", "input", None, "URL del broker",
     "https://<function>.azurewebsites.net, sin /api ni barra final. El dominio tiene que estar también en "
     "external-service-usage del manifiesto y el origen del entorno en el CORS de la Function.", None),
    ("brokerClientId", "SingleLine.Text", "input", None, "Client ID del broker",
     "Application (client) ID de la app registration del broker (CMV8 Broker). El control pide el scope "
     "api://<este id>/user_impersonation.", None),
    ("clientIdPcf", "SingleLine.Text", "input", None, "Client ID del PCF",
     "Application (client) ID de la app registration de cliente público «CMV8 PCF» (guía 24), con redirect SPA "
     "= URL del entorno terminada en /.", None),
    ("tenantId", "SingleLine.Text", "input", None, "Tenant ID", "Id (GUID) del tenant de Entra.", None),
    ("lookupPadre", "SingleLine.Text", "input", None, "Lookup al padre (SchemaName)",
     "Nombre de navegación (SchemaName) de la lookup de la tabla de adjuntos hacia el registro padre, por ejemplo "
     "cred_Credito. Distingue mayúsculas: se usa como <lookupPadre>@odata.bind al crear filas.", None),
    ("lookupTipoDocumento", "SingleLine.Text", "input", None, "Lookup al tipo de documento (SchemaName)",
     "SchemaName de la lookup <prefijo>tipodocumento hacia cmv8_tipodocumento. Vacío = <prefijo>TipoDocumento "
     "(por ejemplo cred_TipoDocumento).", None),
    ("tiposAceptados", "SingleLine.Text", "input", "video/*,application/pdf", "Tipos aceptados",
     "Lista separada por comas con la sintaxis del atributo accept: extensiones (.pdf), tipos MIME (application/pdf) o "
     "comodines (video/*). Vacío acepta todo.", None),
    ("tamanoMaximoMB", "Whole.None", "input", "250", "Tamaño máximo (MB)",
     "Tamaño máximo por archivo, en MB (base 1024). Por defecto 250.", None),
    ("loteMaximo", "Whole.None", "input", "20", "Archivos por lote",
     "Cantidad máxima de archivos en la cola (y por pedido de SAS). Por defecto 20, igual que el broker.", None),
    ("paralelismoArchivos", "Whole.None", "input", "2", "Archivos en paralelo",
     "Archivos que se suben a la vez. Por defecto 2; entre 1 y 4.", None),
    ("paralelismoBloques", "Whole.None", "input", "4", "Bloques en paralelo",
     "Bloques en vuelo por archivo. Por defecto 4; entre 1 y 8.", None),
    ("densidad", "Enum", "input", "comoda", "Densidad", "Compacta o cómoda (el usuario la puede cambiar en la grilla).",
     [("compacta", "Compacta"), ("comoda", "Cómoda")]),
    ("variante", "Enum", "input", "fluent", "Variante visual",
     "Fluent nativo, institucional sobrio o moderno con tarjetas.",
     [("fluent", "Fluent nativo"), ("institucional", "Institucional sobrio"), ("moderno", "Moderno con tarjetas")]),
    ("modo", "Enum", "input", "claro", "Modo de color",
     "Claro, oscuro o automático (sigue la preferencia del sistema operativo).",
     [("claro", "Claro"), ("oscuro", "Oscuro"), ("auto", "Automático")]),
]

# Columnas del dataset (property-set). Todas opcionales: si el maker no las liga, el control usa la columna reservada
# <prefijo><sufijo> (o la de sistema) y la agrega a la consulta con addColumn.
CONJUNTOS_GRILLA = [
    ("estado", "OptionSet", "Estado de ingesta", "Opcional. Por defecto <prefijo>estadoingesta."),
    ("tipoDocumento", "Lookup.Simple", "Tipo de documento", "Opcional. Por defecto <prefijo>tipodocumento."),
    ("nombreArchivo", "SingleLine.Text", "Nombre de archivo", "Opcional. Por defecto <prefijo>nombrearchivo."),
    ("tamano", "Whole.None", "Tamaño (bytes)", "Opcional. Por defecto <prefijo>tamanobytes."),
    ("mensaje", "Multiple", "Mensaje", "Opcional. Por defecto <prefijo>mensajeerror."),
    ("rutaBlob", "SingleLine.Text", "Ruta del blob", "Opcional. Por defecto <prefijo>rutablob."),
    ("fechaSubida", "DateAndTime.DateAndTime", "Fecha de subida", "Opcional. Por defecto modifiedon."),
    ("subidoPor", "Lookup.Simple", "Subido por", "Opcional. Por defecto createdby."),
]

# Textos propios de la grilla que textos.es.json no tiene. Mismo tono (español neutro, tuteo) y misma regla: nunca
# URLs, tokens, rutas ni ids. Si el diseño los adopta, pasan a textos.es.json y se borran de acá.
TEXTOS_GRILLA = {
    "auth.reauth": ("Tu sesión necesita confirmarse. Vuelve a iniciar sesión para continuar.",
                    "El broker pidió reautenticar (acceso condicional) o el token no se pudo obtener en silencio."),
    "auth.popupBlocked": ("El navegador bloqueó la ventana para iniciar sesión. Permite las ventanas emergentes de este "
                          "sitio y vuelve a intentarlo.", ""),
    "auth.signIn": ("Iniciar sesión", "Botón del aviso de sesión."),
    "config.missing": ("El control de adjuntos no está configurado. Pide al administrador que revise sus propiedades.",
                       "Falta urlBroker, brokerClientId, clientIdPcf, tenantId o lookupPadre, o la tabla no es válida."),
    "config.noParent": ("Guarda el registro para poder adjuntar archivos.", "Formulario de creación (sin id)."),
    "grid.loading": ("Cargando adjuntos…", ""),
    "grid.loadError": ("No se pudieron cargar los adjuntos. Vuelve a intentarlo.", "dataset.error."),
    "grid.loadMore": ("Cargar más", "Más de 100 adjuntos (Anatomia.md §7)."),
    "grid.refresh": ("Actualizar", ""),
    "docType.loadError": ("No se pudieron cargar los tipos de documento. Vuelve a intentarlo.", ""),
    "docType.empty": ("No hay tipos de documento en uso. Pide al administrador que habilite alguno.", ""),
    "docType.hint": ("Se aplica a los archivos de la cola que todavía no se subieron.", "Selector único por lote."),
    "action.busy": ("En curso…", "Botón de acción mientras espera al broker."),
    "action.done.archive": ("{name}: se pidió el archivado.", "aria-live polite."),
    "action.done.resume": ("{name}: se reanudó el proceso.", "aria-live polite."),
    "action.fix.hint": ("Corrige los datos en el formulario del adjunto y guarda. Al volver aquí, el proceso se "
                        "reanuda solo.", "Detalle de Observada."),
    "action.error.view": ("No se pudo abrir el documento. Vuelve a intentarlo.", ""),
    "action.error.notAvailable": ("El documento ya no está disponible para verlo aquí.", "409/404 de sas/lectura."),
    "action.error.archive": ("No se pudo pedir el archivado. Vuelve a intentarlo.", ""),
    "action.error.resume": ("No se pudo reanudar. Vuelve a intentarlo.", ""),
    "action.error.missingFile": ("No se encontró el archivo. Súbelo de nuevo.", "404 de reanudar: ofrece Subir."),
    "action.error.forbidden": ("No tienes permiso para hacer esto con este adjunto.", "403 del broker."),
    "action.error.state": ("El adjunto cambió de estado. Actualiza la lista.", "409 del broker."),
}

desc_grilla = {
    "AdjuntosGrilla_Display": "Adjuntos (grilla con carga)",
    "AdjuntosGrilla_Desc": "Grilla de adjuntos para la subcuadrícula del formulario del registro padre: muestra los diez "
                           "estados, sube archivos directo a Blob Storage con el broker CMV8 y ejecuta las acciones de "
                           "cada estado. Identidad propia con MSAL (app registration «CMV8 PCF»).",
    "adjuntos_Display": "Adjuntos",
    "adjuntos_Desc": "Subcuadrícula de la tabla de adjuntos de la solución, filtrada por el registro padre.",
}

ENTRADAS_GRILLA = PROPIEDADES_GRILLA + PROPIEDADES_MAKER

# ---------------------------------------------------------------- AdjuntosGrilla/ControlManifest.Input.xml
x = [
    '<?xml version="1.0" encoding="utf-8" ?>',
    "<!-- GENERADO por CargaAdjuntoBlob/herramientas/generar_desde_diseno.py. Cambiar las propiedades allí y regenerar. -->",
    "<manifest>",
    f'  <control namespace="Banco" constructor="AdjuntosGrilla" version="{VERSION_GRILLA}" '
    'display-name-key="AdjuntosGrilla_Display" description-key="AdjuntosGrilla_Desc" control-type="standard">',
    "    <!-- ADR-003 §4.6: el control llama al broker con su propio token (MSAL) y sube directo a Blob Storage.",
    "         external-service-usage es licenciamiento (componente premium). Ajustar los dominios reales al desplegar. -->",
    '    <external-service-usage enabled="true">',
    "      <domain>cmv8adjsupfcopn.blob.core.windows.net</domain>",
    "      <domain>cmv8-func-supfcopn.azurewebsites.net</domain>",
    "      <domain>login.microsoftonline.com</domain>",
    "    </external-service-usage>",
    "",
    '    <data-set name="adjuntos" display-name-key="adjuntos_Display" description-key="adjuntos_Desc">',
]
for (n, tipo, _, _) in CONJUNTOS_GRILLA:
    x.append(f'      <property-set name="{n}" display-name-key="{n}_Display" description-key="{n}_Desc" '
             f'of-type="{tipo}" usage="bound" required="false" />')
x.append("    </data-set>")
x.append("")
x.append("    <!-- Entradas -->")
for (n, tipo, uso, dflt, _, _, enum) in PROPIEDADES_GRILLA:
    attrs = f'name="{n}" display-name-key="{n}_Display" description-key="{n}_Desc" of-type="{tipo}" usage="{uso}" required="false"'
    if dflt is not None:
        attrs += f' default-value="{escape(dflt)}"'
    if enum:
        x.append(f"    <property {attrs}>")
        for (v, _) in enum:
            x.append(f'      <value name="{v}" display-name-key="{n}_{v}">{v}</value>')
        x.append("    </property>")
    else:
        x.append(f"    <property {attrs} />")
x.append("")
x.append('    <!-- Tokens del maker (tokens.json, campo "maker"). Vacío = valor de la variante y modo. -->')
for (n, tipo, uso, _, _, _, _) in PROPIEDADES_MAKER:
    x.append(f'    <property name="{n}" display-name-key="{n}_Display" description-key="{n}_Desc" of-type="{tipo}" usage="{uso}" required="false" />')
x += [
    "",
    "    <resources>",
    '      <code path="index.ts" order="1" />',
    '      <css path="css/base.generado.css" order="1" />',
    '      <css path="css/AdjuntosGrilla.css" order="2" />',
    '      <resx path="strings/AdjuntosGrilla.1033.resx" version="1.0.0" />',
    '      <resx path="strings/AdjuntosGrilla.3082.resx" version="1.0.0" />',
    "    </resources>",
    "",
    "    <feature-usage>",
    '      <uses-feature name="WebAPI" required="true" />',
    '      <uses-feature name="Utility" required="false" />',
    "    </feature-usage>",
    "  </control>",
    "</manifest>",
    "",
]
escribir(os.path.join(GRILLA, "ControlManifest.Input.xml"), "\n".join(x))

# ---------------------------------------------------------------- AdjuntosGrilla/generated/ManifestTypes.d.ts
t = [
    "/*",
    "*This is auto generated from the ControlManifest.Input.xml file",
    "*/",
    "",
    "// Define IInputs and IOutputs Type. They should match with ControlManifest.",
    "export interface IInputs {",
]
for (n, tipo, _, _, _, _, enum) in ENTRADAS_GRILLA:
    if enum:
        union = " | ".join(f'"{v}"' for (v, _) in enum)
        t.append(f"    {n}: ComponentFramework.PropertyTypes.EnumProperty<{union}>;")
    else:
        t.append(f"    {n}: {TIPOS_TS[tipo]};")
t.append("    adjuntos: ComponentFramework.PropertyTypes.DataSet;")
t.append("}")
t.append("export interface IOutputs {")
t.append("}")
t.append("")
escribir(os.path.join(GRILLA, "generated", "ManifestTypes.d.ts"), "\n".join(t))

# ---------------------------------------------------------------- AdjuntosGrilla/textosGrilla.generado.ts
cont = [
    "// GENERADO por CargaAdjuntoBlob/herramientas/generar_desde_diseno.py (TEXTOS_GRILLA).",
    "// Claves propias de la grilla; las comunes son ClaveTexto (textos.es.json). Los textos viven en strings/*.resx.",
    "",
    "export type ClaveGrillaPropia =\n    | " + "\n    | ".join(ts_str(c) for c in TEXTOS_GRILLA) + ";",
    "",
]
escribir(os.path.join(GRILLA, "textosGrilla.generado.ts"), "\n".join(cont))

# ---------------------------------------------------------------- AdjuntosGrilla/vista/iconosGrilla.generado.ts
cont = [
    "// GENERADO por CargaAdjuntoBlob/herramientas/generar_desde_diseno.py desde docs/diseno/prototipo/_icons_raw.json",
    "// (Fluent UI System Icons, licencia MIT). Solo los íconos que usa la grilla.",
    'import { DefinicionIcono } from "../../CargaAdjuntoBlob/vista/iconos.generado";',
    "",
    "export type IconoGrilla =\n    | " + "\n    | ".join(ts_str(n) for n in ICONOS_GRILLA) + ";",
    "",
    "export const ICONOS_GRILLA: Readonly<Record<IconoGrilla, DefinicionIcono>> = {",
]
for n in ICONOS_GRILLA:
    ic = iconos[n]
    cont.append(f"    {n}: {{ vb: {ts_str(ic['vb'])}, d: [{', '.join(ts_str(d) for d in ic['d'])}] }},")
cont.append("};")
cont.append("")
escribir(os.path.join(GRILLA, "vista", "iconosGrilla.generado.ts"), "\n".join(cont))

# ---------------------------------------------------------------- AdjuntosGrilla/css/base.generado.css
with open(os.path.join(CONTROL, "css", "CargaAdjuntoBlob.css"), encoding="utf-8") as f:
    base_css = f.read()
escribir(os.path.join(GRILLA, "css", "base.generado.css"),
         "/* GENERADO por CargaAdjuntoBlob/herramientas/generar_desde_diseno.py: copia de CargaAdjuntoBlob/css/"
         "CargaAdjuntoBlob.css.\n   No editar: cambiar el original y regenerar. Lo propio de la grilla está en "
         "AdjuntosGrilla.css. */\n" + base_css)

# ---------------------------------------------------------------- AdjuntosGrilla/strings/*.resx
textos_manifest_grilla = list(desc_grilla.items())
for (n, _, nom, desc) in CONJUNTOS_GRILLA:
    textos_manifest_grilla.append((f"{n}_Display", nom))
    textos_manifest_grilla.append((f"{n}_Desc", desc))
for (n, _, _, _, nom, desc, enum) in ENTRADAS_GRILLA:
    textos_manifest_grilla.append((f"{n}_Display", nom))
    textos_manifest_grilla.append((f"{n}_Desc", desc))
    for (v, etiqueta) in (enum or []):
        textos_manifest_grilla.append((f"{n}_{v}", etiqueta))

escribir(os.path.join(GRILLA, "strings", "AdjuntosGrilla.3082.resx"), resx(False, textos_manifest_grilla, TEXTOS_GRILLA))
escribir(os.path.join(GRILLA, "strings", "AdjuntosGrilla.1033.resx"), resx(True, textos_manifest_grilla, TEXTOS_GRILLA))
