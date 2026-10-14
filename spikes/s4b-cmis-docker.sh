#!/usr/bin/env bash
# S4b: servidor CMIS 1.1 de prueba (Apache Chemistry OpenCMIS InMemory 1.1.0) en Docker, solo loopback.
# Idempotente: elimina el contenedor previo y lo recrea.
#
# Uso: spikes/s4b-cmis-docker.sh [directorio-del-war]
#   CMIS_PUERTO (defecto 18080): 8080 en 127.0.0.1 suele estar ocupado por otros proyectos en este VPS.
#
# Notas:
# - El WAR es javax.servlet: Tomcat 9 (Tomcat 10+ es jakarta y no lo despliega).
# - Tomcat 9 con Java 8: el WAR registra también el binding Web Services y necesita javax.xml.ws (JAX-WS),
#   que Java 11+ ya no trae. Con tomcat:9-jre17 el despliegue falla con
#   "ClassNotFoundException: javax.xml.ws.Endpoint".
# - El WAR se trata como dato no confiable: se descarga a un directorio propio y se verifica su SHA-1.
set -euo pipefail

WAR_DIR="${1:-${CMIS_WAR_DIR:-/tmp/claude-1000/-home-gmaker-projects-CMV8-to-PP/9db2f7fd-c8d1-40b0-b416-09548ef6abf6/scratchpad/cmis/war}}"
PUERTO="${CMIS_PUERTO:-18080}"
IMAGEN="tomcat:9-jre8"
NOMBRE="cmis-inmemory"
WAR="chemistry-opencmis-server-inmemory-1.1.0.war"
URL="https://repo1.maven.org/maven2/org/apache/chemistry/opencmis/chemistry-opencmis-server-inmemory/1.1.0/${WAR}"
SHA1_ESPERADO="a3571ac1c5ffb712a0d61fdbad76282adfb08bb2"

mkdir -p "${WAR_DIR}"
if [ ! -f "${WAR_DIR}/${WAR}" ]; then
  curl -fsSL -o "${WAR_DIR}/${WAR}" "${URL}"
fi
echo "${SHA1_ESPERADO}  ${WAR_DIR}/${WAR}" | sha1sum -c -

docker rm -f "${NOMBRE}" >/dev/null 2>&1 || true
docker pull -q "${IMAGEN}"
docker run -d --name "${NOMBRE}" \
  -p "127.0.0.1:${PUERTO}:8080" \
  -v "${WAR_DIR}/${WAR}:/usr/local/tomcat/webapps/cmis.war:ro" \
  "${IMAGEN}"

for _ in $(seq 1 60); do
  codigo=$(curl -s -o /dev/null -w '%{http_code}' -u test:test "http://127.0.0.1:${PUERTO}/cmis/browser" || true)
  [ "${codigo}" = "200" ] && break
  sleep 2
done
if [ "${codigo}" != "200" ]; then
  echo "El servidor CMIS no respondió 200 (último código: ${codigo})" >&2
  docker logs "${NOMBRE}" 2>&1 | tail -40 >&2
  exit 1
fi

# Repositorio, versión CMIS y carpeta raíz (esperado: A1, 1.1, 100)
curl -s -u test:test "http://127.0.0.1:${PUERTO}/cmis/browser" | python3 -I -c '
import json, sys
for k, v in json.load(sys.stdin).items():
    print("repositoryId=%s cmisVersionSupported=%s rootFolderId=%s rootFolderUrl=%s" % (
        v["repositoryId"], v["cmisVersionSupported"], v["rootFolderId"], v["rootFolderUrl"]))'
echo "Browser binding: http://127.0.0.1:${PUERTO}/cmis/browser (usuario/clave cualquiera, p. ej. test/test)"
echo "Detener: docker stop ${NOMBRE}"
