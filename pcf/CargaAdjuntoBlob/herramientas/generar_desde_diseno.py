#!/usr/bin/env python3
"""
Genera los artefactos del PCF CargaAdjuntoBlob a partir del diseño aprobado, para que el código, el manifiesto
y los recursos no se desincronicen a mano.

Fuentes (no se leen en runtime: el PCF no puede leer archivos del repo):
  docs/diseno/anatomia-y-tokens/tokens.json     -> tokens.generado.ts
  docs/diseno/anatomia-y-tokens/textos.es.json  -> textos.generado.ts y strings/*.resx
  docs/diseno/prototipo/_icons_raw.json         -> vista/iconos.generado.ts (solo los íconos que usa la anatomía)
  PROPIEDADES (abajo)                           -> ControlManifest.Input.xml, generated/ManifestTypes.d.ts y resx

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
    ("lote", "Multiple.Lines", "input", None, "Lote de SAS",
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
    ("pendientes", "Multiple.Lines", "Pendientes de SAS",
     "JSON [{indice, id, nombreArchivo, tamanoBytes, contentType}] del pedido de SAS en curso. id viene vacío si el "
     "archivo todavía no tiene fila; la app crea la fila, llama EmitirSasLote en el mismo orden y deja la respuesta en varLote."),
    ("numeroPedido", "Whole.None", "Número de pedido",
     "Crece cada vez que el control publica un pedido nuevo en pendientes. La app atiende cada número una sola vez."),
    ("resultados", "Multiple.Lines", "Resultados",
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
    "Multiple.Lines": "ComponentFramework.PropertyTypes.StringProperty",
    "Whole.None": "ComponentFramework.PropertyTypes.WholeNumberProperty",
}
SALIDA_TS = {"SingleLine.Text": "string", "Multiple.Lines": "string", "Whole.None": "number"}
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


def resx(marcar_traduccion):
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
    for (k, v) in manifest_textos:
        data(k, v, "")
    for (k, s) in textos["strings"].items():
        data(k, s["text"], s.get("notes", ""))
    r.append("</root>")
    r.append("")
    return "\n".join(r)


escribir(os.path.join(CONTROL, "strings", "CargaAdjuntoBlob.3082.resx"), resx(False))
escribir(os.path.join(CONTROL, "strings", "CargaAdjuntoBlob.1033.resx"), resx(True))
