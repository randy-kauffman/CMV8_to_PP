#!/usr/bin/env python3
"""Genera docs/diagramas/arquitectura.svg (diagrama de arquitectura de CMV8_to_PP)."""
import sys
from xml.sax.saxutils import escape

W, H = 1820, 1210
FONT = "Segoe UI, Inter, Helvetica Neue, Arial, sans-serif"

C = {
    "pp": "#742774", "pp_bg": "#F6EEF7",
    "entra": "#0F6CBD", "entra_bg": "#EAF2FB",
    "azure": "#2B6CB0", "azure_bg": "#EEF4FB",
    "banco": "#B7791F", "banco_bg": "#FBF5E9",
    "ink": "#1F2937", "muted": "#6B7280", "line": "#374151", "dash": "#6B7280",
    "opt": "#9CA3AF", "white": "#FFFFFF", "num": "#111827",
}

out = []
def w(s): out.append(s)

def zone(x, y, wd, ht, color, bg, title, title_pos="top"):
    w(f'<rect x="{x}" y="{y}" width="{wd}" height="{ht}" rx="14" fill="{bg}" stroke="{color}" stroke-width="1.5"/>')
    ty = y + 24 if title_pos == "top" else y + ht - 12
    w(f'<text x="{x+16}" y="{ty}" font-size="13" font-weight="700" fill="{color}" letter-spacing="1.2">{escape(title)}</text>')

def box(x, y, wd, ht, color, title, lines, dashed=False, fill=None, title_size=12.5, body_size=11, lh=15):
    fill = fill or C["white"]
    dash = ' stroke-dasharray="6 4"' if dashed else ""
    w(f'<rect x="{x}" y="{y}" width="{wd}" height="{ht}" rx="10" fill="{fill}" stroke="{color}" stroke-width="1.6"{dash}/>')
    w(f'<rect x="{x}" y="{y}" width="6" height="{ht}" rx="3" fill="{color}" opacity="{0.45 if dashed else 1}"/>')
    ty = y + 20
    w(f'<text x="{x+16}" y="{ty}" font-size="{title_size}" font-weight="700" fill="{C["ink"]}">{escape(title)}</text>')
    ty += 6
    for ln in lines:
        ty += lh
        bold = ln.startswith("**")
        txt = ln[2:] if bold else ln
        fw = ' font-weight="600"' if bold else ""
        col = C["ink"] if bold else "#374151"
        w(f'<text x="{x+16}" y="{ty}" font-size="{body_size}"{fw} fill="{col}">{escape(txt)}</text>')

def arrow(d, dashed=False, color=None, both=False, width=1.8):
    color = color or (C["dash"] if dashed else C["line"])
    dash = ' stroke-dasharray="6 4"' if dashed else ""
    ms = ' marker-start="url(#dotgrey)"' if both else ""
    marker = "url(#arrowgrey)" if dashed else "url(#arrow)"
    w(f'<path d="{d}" fill="none" stroke="{color}" stroke-width="{width}"{dash} marker-end="{marker}"{ms}/>')

def label(x, y, text, anchor="start", size=11, color=None, bold=False, rotate=None):
    color = color or C["ink"]
    fw = ' font-weight="600"' if bold else ""
    tr = f' transform="rotate({rotate} {x} {y})"' if rotate is not None else ""
    w(f'<text x="{x}" y="{y}" font-size="{size}" text-anchor="{anchor}" fill="{color}"{fw}{tr}>{escape(text)}</text>')

def step(x, y, n):
    """Círculo numerado del flujo."""
    w(f'<circle cx="{x}" cy="{y}" r="10" fill="{C["num"]}"/>')
    w(f'<text x="{x}" y="{y+4}" font-size="11" font-weight="700" text-anchor="middle" fill="#FFFFFF">{n}</text>')

# ---------------------------------------------------------------- cabecera
w(f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" font-family="{FONT}">')
w('<defs>'
  '<marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse">'
  f'<path d="M 0 0 L 10 5 L 0 10 z" fill="{C["line"]}"/></marker>'
  '<marker id="arrowgrey" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse">'
  f'<path d="M 0 0 L 10 5 L 0 10 z" fill="{C["dash"]}"/></marker>'
  '<marker id="dotgrey" viewBox="0 0 10 10" refX="5" refY="5" markerWidth="6" markerHeight="6">'
  f'<circle cx="5" cy="5" r="4" fill="{C["line"]}"/></marker>'
  '</defs>')
w(f'<rect width="{W}" height="{H}" fill="#F7F8FA"/>')
w(f'<text x="30" y="42" font-size="24" font-weight="700" fill="{C["ink"]}">CMV8_to_PP · Arquitectura de ingesta de documentos hacia IBM Content Manager V8</text>')
w(f'<text x="30" y="66" font-size="13" fill="{C["muted"]}">Power Apps sube el binario directo a Blob Storage con una SAS ligada al usuario; una Function en Azure lo archiva en Content Manager con metadatos de IBM i. '
  f'Regla del diseño: Dataverse decide, la Function emite, Storage verifica.</text>')

# ---------------------------------------------------------------- zonas
zone(30, 90, 1370, 200, C["entra"], C["entra_bg"], "ENTRA ID · TENANT DEL BANCO")
zone(30, 320, 410, 880, C["pp"], C["pp_bg"], "POWER PLATFORM · EL USUARIO")
zone(560, 320, 840, 880, C["azure"], C["azure_bg"], "AZURE · RG cmv8-<ambiente>")
zone(1430, 320, 360, 880, C["banco"], C["banco_bg"], "RED INTERNA DEL BANCO")

# ---------------------------------------------------------------- Entra
box(60, 130, 380, 130, C["entra"], "App registration · CMV8 Conector (Power Platform)", [
    "Cliente. Permiso delegado: CMV8 Broker / user_impersonation",
    "Redirect: https://global.consent.azure-apim.net/redirect",
    "**El ÚNICO secreto de la solución (6 meses, en el vault del Banco)",
    "Consentimiento de administrador: nadie ve la pantalla de consentir",
])
box(560, 130, 420, 130, C["entra"], "App registration · CMV8 Broker (Function)", [
    "Recurso. Expone api://<appId>/user_impersonation (tokens v2)",
    "Permiso delegado: Azure Storage / user_impersonation (para el OBO)",
    "knownClientApplications = el conector (consentimiento combinado)",
    "**Cero secretos: credencial federada hacia la identidad administrada",
])
box(1130, 130, 250, 130, C["entra"], "Acceso condicional del Banco", [
    "MFA, dispositivo, ubicación.",
    "El OBO hereda las políticas del",
    "usuario sobre Azure Storage.",
    "Si no se cumplen: interaction_",
    "required → el broker responde 502.",
], body_size=10.5, lh=14)

# ---------------------------------------------------------------- Power Platform
box(60, 360, 360, 110, C["pp"], "Conector personalizado · CMV8 Broker", [
    "OAuth 2.0 con la app Conector; token del usuario, no de servicio",
    "Operaciones: EmitirSasSubida · EmitirSasLectura ·",
    "ResolverCliente · Salud. Política DLP: grupo Business",
])
box(130, 500, 290, 160, C["pp"], "Dataverse", [
    "Tabla cmv8_solicitudingesta (solución cmv8ingesta)",
    "Estados: Creada → Subido → Archivado",
    "          | Rechazado | Estancada",
    "Rol CMV8 Solicitante (usuarios piloto)",
    "Usuario de aplicación = identidad administrada",
    "(la Function suplanta al usuario: CallerObjectId)",
], body_size=10.5, lh=14)
box(60, 690, 360, 310, C["pp"], "Navegador del usuario · app canvas", [
    "**PCF Banco.CargaAdjuntoBlob",
    "Recibe uploadUrl (SAS) + tokenStorage del conector",
    "Sube en bloques de 4 MiB: Put Block × N + Put Block List",
    "Cada PUT lleva las DOS llaves:",
    "   · la SAS en la URL (firmada con sduoid = oid del usuario)",
    "   · Authorization: Bearer <token de Storage del usuario>",
    "Ninguna sirve sola: 403. Juntas y del mismo usuario: 201.",
    "",
    "**Lo que nunca sale de la app",
    "La SAS y el token viven en una variable Power Fx.",
    "No se persisten en Dataverse ni se usan desde Power Automate.",
    "Vida: SAS 15 min, un solo blob, permisos cw. Token 60 a 90 min.",
    "",
    "**Videos de hasta 100 MB; el binario NUNCA pasa por",
    "**Dataverse ni por la Function durante la subida.",
], body_size=10.5, lh=14)
box(60, 1030, 360, 150, C["pp"], "Licencias y límites", [
    "Todos los usuarios con Power Apps Premium.",
    "Las operaciones del conector cuentan como Power Platform",
    "requests del usuario; la ingesta (Event Grid) no consume",
    "ninguna. Dataverse: pool de 25.000/día documentado como",
    "riesgo hasta que Microsoft publique el reporte (S5).",
], body_size=10.5, lh=14)

# ---------------------------------------------------------------- Azure
box(560, 360, 420, 300, C["azure"], "Azure Function · Flex Consumption · Java 21", [
    "**Easy Auth: solo tokens v2 con audiencia del broker; si no, 401",
    "Identidad: cmv8-mi (asignada por el usuario). Cero secretos.",
    "",
    "**Broker (HTTP; lo llama el conector en nombre del usuario)",
    "POST /api/sas/subida → autoriza en Dataverse, OBO, firma SAS",
    "GET /api/sas/lectura/{id} · POST /api/clientes/resolver",
    "GET /api/salud (sin autenticación; es el chequeo de vida)",
    "",
    "**Worker (no consume Power Platform requests)",
    "OnBlobCreated (Event Grid) → cola ingesta-pendiente →",
    "ProcesarIngesta: lee el blob con la identidad, pide los",
    "metadatos al AS400, createDocument en CM V8, marca",
    "Archivado + cmisObjectId en Dataverse y borra el blob.",
    "Watchdog (timer): Subido > 15 min → reencola o Estancada.",
    "Idempotente: no vuelve a subir si ya hay cmisObjectId.",
], body_size=10.5, lh=14)
box(1010, 360, 170, 140, C["azure"], "Identidad administrada", [
    "cmv8-mi (user-assigned)",
    "Roles de mínimo privilegio",
    "en host, adjuntos, Key",
    "Vault y App Insights.",
    "Usuario de aplicación",
    "en Dataverse. Credencial",
    "federada del broker.",
], body_size=10.5, lh=13.5)
box(1200, 360, 180, 140, C["azure"], "Key Vault · cmv8-kv", [
    "RBAC · soft delete 7 d",
    "as400-password",
    "cmis-password",
    "La Function los lee por",
    "referencia, sin versión:",
    "rotar sin redeploy.",
    "Banco: privado + endpoint",
], body_size=10.5, lh=13.5)
box(1010, 520, 370, 230, C["azure"], "Storage de adjuntos · cmv8adj (cuenta endurecida)", [
    "Contenedor cmv8-ingesta · blob = {solicitudId}/{archivo}",
    "Shared Key OFF · sin acceso anónimo · solo OAuth · TLS 1.2 · HTTPS",
    "Política de cuenta: SAS máx. 1 h (Block) · SAS ligada a usuario",
    "La Function pide la clave de delegación con la identidad y firma",
    "la SAS con sduoid: Storage exige el bearer del MISMO usuario.",
    "CORS GET/HEAD/PUT/OPTIONS (orígenes *.powerapps.com variables)",
    "Soft delete 7 d · ciclo de vida: borra huérfanos a los 7 d",
    "Firewall de IP opcional (S0) · Diagnósticos → Log Analytics",
    "(StorageBlobLogs: RequesterUpn, scid = solicitudId, IP)",
    "**Roles de la identidad: Blob Data Contributor SOLO en el",
    "**contenedor · Blob Delegator en la cuenta",
], body_size=10.5, lh=14)
box(560, 840, 320, 100, C["azure"], "Observabilidad", [
    "Log Analytics (30 d) + Application Insights",
    "(auth local OFF: trazas con AAD vía la identidad)",
    "Auditoría: quién subió qué (StorageBlobLogs),",
    "diagnóstico de Easy Auth y del OBO, latencias.",
], body_size=10.5, lh=14)
box(1010, 840, 170, 112, C["azure"], "Event Grid", [
    "System topic de la cuenta",
    "de adjuntos. Suscripción",
    "BlobCreated filtrada al",
    "contenedor → OnBlobCreated.",
    "Reintentos 24 h; dead-letter",
    "pendiente en el Bicep.",
], body_size=10.5, lh=13.5)
box(1210, 840, 170, 100, C["azure"], "Storage host", [
    "Runtime de Functions:",
    "paquete de despliegue,",
    "cola ingesta-pendiente,",
    "timers. Shared Key OFF,",
    "acceso por identidad.",
], body_size=10.5, lh=13.5)
box(560, 990, 420, 80, C["opt"], "Opcional · Defender for Storage (NO habilitado, por decisión)", [
    "Escaneo de malware al subir; el disparador pasaría a OnScanResult",
    "(ESCANEO_REQUERIDO=true). Con costo: ver justificación financiera §8.",
], dashed=True, body_size=10.5, lh=14, fill="#FAFAFA")
box(1010, 990, 370, 80, C["opt"], "Pendiente · Red (S0, dentro del Banco)", [
    "VNet integration de la Function + private endpoints (Storage,",
    "Key Vault). Sin esto el worker no llega a IBM i ni a CM V8.",
], dashed=True, body_size=10.5, lh=14, fill="#FAFAFA")
w(f'<rect x="560" y="1095" width="820" height="95" rx="10" fill="{C["azure"]}" opacity="0.92"/>')
label(580, 1118, "Reglas del diseño (ADR-002)", size=12.5, color="#FFFFFF", bold=True)
label(580, 1138, "Dataverse decide quién puede subir qué · la Function emite una SAS ligada a ese usuario · Storage verifica las dos llaves en cada PUT.", size=11, color="#FFFFFF")
label(580, 1156, "Ningún secreto en el repo ni en el código; el broker no tiene secreto y las contraseñas viven en Key Vault.", size=11, color="#FFFFFF")
label(580, 1174, "En la subida, el binario entra a Storage: nunca pasa por Dataverse ni por la Function. La ingesta no consume Power Platform requests.", size=11, color="#FFFFFF")

# ---------------------------------------------------------------- Banco
box(1450, 360, 240, 150, C["banco"], "AS400 / IBM i", [
    "Metadatos obligatorios del",
    "documento (cliente, afiliado,",
    "dueño) vía jt400, JDBC",
    "seguro (TLS 9471). Usuario",
    "técnico; contraseña en",
    "Key Vault, rotación según",
    "política del Banco.",
], body_size=10.5, lh=13.5)
box(1450, 560, 320, 170, C["banco"], "IBM Content Manager V8", [
    "API CMIS 1.1, Browser binding (JSON), solo",
    "en red interna. Cliente OpenCMIS desde la Function:",
    "createDocument con el stream del blob + metadatos",
    "del IBM i en la carpeta y tipo configurados",
    "(CMIS_REPOSITORY_ID, CMIS_FOLDER_PATH, CMIS_TYPE_ID).",
    "Devuelve cmisObjectId → queda en la solicitud.",
    "Riesgo a validar (S4a): Transfer-Encoding chunked.",
], body_size=10.5, lh=14)
box(1450, 780, 320, 120, C["banco"], "Conectividad (decisión de red S0)", [
    "ADR-001: VMs en Azure con línea de red al AS400 y",
    "a CM V8. Hipótesis: VNet con salida NAT. La Function",
    "se integra a esa VNet; hasta probar la ruta, el worker",
    "queda sin validar adentro (/api/diagnostico/red).",
], body_size=10.5, lh=14)
# leyenda
w(f'<rect x="1450" y="940" width="320" height="240" rx="10" fill="#FFFFFF" stroke="{C["banco"]}" stroke-width="1.2"/>')
label(1466, 962, "Leyenda", size=12.5, bold=True)
arrow("M 1466 982 H 1516"); label(1526, 986, "flujo de ejecución, numerado 1 a 11", size=10.5)
arrow("M 1466 1004 H 1516", dashed=True); label(1526, 1008, "identidad o configuración", size=10.5)
w(f'<rect x="1466" y="1018" width="50" height="14" rx="4" fill="#FAFAFA" stroke="{C["opt"]}" stroke-dasharray="6 4"/>')
label(1526, 1030, "componente opcional o pendiente", size=10.5)
step(1475, 1048, "n"); label(1492, 1052, "paso del flujo de una subida", size=10.5)
label(1466, 1078, "SAS: firma de acceso compartido (delegación)", size=10.5, color=C["muted"])
label(1466, 1094, "OBO: On-Behalf-Of, intercambio de token", size=10.5, color=C["muted"])
label(1466, 1110, "sduoid: oid del usuario ligado a la SAS", size=10.5, color=C["muted"])
label(1466, 1126, "PCF: Power Apps component framework", size=10.5, color=C["muted"])
label(1466, 1142, "Flex Consumption: plan serverless de Functions", size=10.5, color=C["muted"])
label(1466, 1158, "Guías de montaje por componente: docs/montaje/", size=10.5, color=C["muted"])
label(1466, 1174, "Decisiones: docs/decisiones/ADR-001 y ADR-002", size=10.5, color=C["muted"])

# ---------------------------------------------------------------- flechas de identidad/config (punteadas)
arrow("M 240 260 V 358", dashed=True)                       # app conector -> conector personalizado
label(250, 312, "OAuth 2.0 con el secreto del conector", size=10.5, color=C["muted"])
arrow("M 1085 360 V 330 H 960 V 262", dashed=True)         # identidad -> broker (credencial federada)
label(968, 347, "credencial federada (sin secreto)", size=10.5, color=C["muted"])
arrow("M 980 420 H 1008", dashed=True)                      # function -> identidad
arrow("M 980 460 H 995 V 510 H 1290 V 502", dashed=True)   # function -> key vault
arrow("M 700 660 V 712 A 8 8 0 0 1 700 728 V 838", dashed=True)  # function -> observabilidad
label(708, 790, "trazas y auditoría", size=10, color=C["muted"])

# ---------------------------------------------------------------- flujo numerado
# 1 y 7: navegador <-> dataverse
arrow("M 250 690 V 662", both=True)
step(214, 676, 1); step(236, 676, 7)
label(262, 673, "1: crea la solicitud (Creada)", size=10)
label(262, 687, "7: marca Subido (tamaño, hash)", size=10)

# 2: navegador -> conector (carril izquierdo)
arrow("M 100 690 V 472", both=True)
step(100, 580, 2)
label(86, 580, "EmitirSasSubida → SAS + token", size=10.5, anchor="middle", rotate=-90)
# 3: conector -> function
arrow("M 420 415 H 558", both=True)
step(489, 400, 3); label(489, 436, "bearer del usuario", size=9.5, anchor="middle")
label(489, 448, "← SAS + token", size=9.5, anchor="middle")
# 4: function -> dataverse (autoriza)
arrow("M 560 540 H 422")
step(489, 526, 4); label(489, 556, "¿es del usuario?", size=9.5, anchor="middle")
label(489, 568, "Dataverse decide", size=9.5, anchor="middle")
# 5: function -> entra (OBO)
arrow("M 760 360 V 262", both=True)
step(760, 312, 5); label(746, 316, "OBO → token de Storage del usuario", size=10.5, anchor="end")
# 6: pcf -> storage adjuntos
arrow("M 420 720 H 1008", width=2.2)
step(620, 720, 6); w('<rect x="632" y="697" width="345" height="16" rx="3" fill="#EEF4FB" opacity="0.95"/>')
label(636, 709, "Put Block × N + Put Block List: SAS + Bearer → 201", size=10.5, bold=True)
# SAS: function -> storage (clave de delegación)
arrow("M 980 600 H 1008", dashed=True)
# 8: storage -> event grid
arrow("M 1095 750 V 838")
step(1095, 790, 8); label(1110, 794, "BlobCreated", size=10.5)
# event grid -> function (worker)
arrow("M 1010 890 H 920 V 728 A 8 8 0 0 1 920 712 V 662")
label(928, 790, "OnBlobCreated →", size=10, color=C["muted"])
label(928, 802, "cola → ProcesarIngesta", size=10, color=C["muted"])
# 9: worker -> AS400
arrow("M 880 360 V 300 H 1670 V 358")
step(1100, 300, 9); label(1116, 295, "jt400: resuelve los metadatos del cliente en IBM i (TLS 9471)", size=10.5)
# 10: worker -> CM V8
arrow("M 910 360 V 312 H 1740 V 558")
step(1462, 540, 10); label(1478, 544, "OpenCMIS: createDocument → cmisObjectId", size=10.5)
# 11: function -> dataverse (Archivado)
arrow("M 560 610 H 422")
step(489, 596, 11); label(489, 626, "Archivado", size=9.5, anchor="middle")
label(489, 638, "+ cmisObjectId", size=9.5, anchor="middle")

w('</svg>')
sys.stdout.write("\n".join(out) + "\n")
