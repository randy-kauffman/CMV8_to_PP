package com.banco.cmv8.spikes;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.banco.cmv8.adapters.out.blob.AlmacenAdjuntosBlob;
import com.banco.cmv8.adapters.out.cmis.ArchivoDocumentalCmis;
import com.banco.cmv8.adapters.out.cmis.MapeoPropiedadesCmis;
import com.banco.cmv8.domain.EstadoSolicitud;
import com.banco.cmv8.domain.SolicitudIngesta;
import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.QueryResult;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.runtime.SessionFactoryImpl;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.enums.BindingType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spike S4b: blob de 100 MB desde Azure Storage hacia un servidor CMIS 1.1 (Browser binding) en streaming,
 * con los adaptadores reales. La prueba de streaming es correrla con -Xmx256m.
 *
 * <pre>
 * spikes/s4b-cmis-docker.sh
 * mvn -q -Pintegracion -Dtest=S4bStreamingCmisIT -DargLine="-Xmx256m" test
 * </pre>
 *
 * Parámetros (propiedad de sistema o variable de entorno, con defecto): S4B_BLOB_ENDPOINT, S4B_CONTAINER,
 * S4B_CMIS_URL, S4B_CMIS_REPOSITORY_ID, S4B_CMIS_USER, S4B_CMIS_PASSWORD.
 */
@Tag("integracion")
class S4bStreamingCmisIT {

    private static final UUID SOLICITUD = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String ARCHIVO = "video-100mb.bin";
    private static final long TAMANO = 104_857_600L;

    private static final String BLOB_ENDPOINT = parametro("S4B_BLOB_ENDPOINT",
            "https://cmv8adjsupfcopn.blob.core.windows.net/");
    private static final String CONTENEDOR = parametro("S4B_CONTAINER", "cmv8-ingesta");
    private static final String CMIS_URL = parametro("S4B_CMIS_URL", "http://127.0.0.1:18080/cmis/browser");
    private static final String CMIS_REPO = parametro("S4B_CMIS_REPOSITORY_ID", "A1");
    // S4a: usuario y clave reales de CM V8 por variable de entorno o -D; el InMemory acepta cualquiera.
    private static final String USUARIO = parametro("S4B_CMIS_USER", "test");
    private static final String CLAVE = parametro("S4B_CMIS_PASSWORD", "test");

    @Test
    void transmiteCienMegasDesdeBlobHaciaCmisSinCargarlosEnMemoria() throws Exception {
        String ruta = SOLICITUD + "/" + ARCHIVO;
        SolicitudIngesta solicitud = new SolicitudIngesta(SOLICITUD, ARCHIVO, TAMANO, "application/octet-stream",
                EstadoSolicitud.PROCESANDO, ruta, null, null, null, null, null, null, null);

        BlobContainerClient contenedor = new BlobServiceClientBuilder()
                .endpoint(BLOB_ENDPOINT)
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient()
                .getBlobContainerClient(CONTENEDOR);
        AlmacenAdjuntosBlob almacen = new AlmacenAdjuntosBlob(contenedor);
        ArchivoDocumentalCmis archivo = new ArchivoDocumentalCmis(
                new ArchivoDocumentalCmis.ParametrosCmis(CMIS_URL, USUARIO, CLAVE, Optional.of(CMIS_REPO), "/",
                        "cmis:document"),
                new MapeoPropiedadesCmis("{}"));

        Session verificacion = sesionVerificacion();
        borrarRestosDeCorridasPrevias(verificacion, SOLICITUD + "_" + ARCHIVO);
        assertTrue(archivo.buscarPorSolicitud(solicitud).isEmpty(), "No debería existir antes de archivar");

        long tamano = almacen.tamano(ruta);
        assertEquals(TAMANO, tamano, "Tamaño del blob");

        Runtime rt = Runtime.getRuntime();
        System.gc();
        long heapBase = rt.totalMemory() - rt.freeMemory();
        AtomicLong heapMax = new AtomicLong(heapBase);
        AtomicLong muestras = new AtomicLong();
        AtomicBoolean midiendo = new AtomicBoolean(true);
        Thread muestreador = new Thread(() -> {
            while (midiendo.get()) {
                long usado = rt.totalMemory() - rt.freeMemory();
                heapMax.accumulateAndGet(usado, Math::max);
                muestras.incrementAndGet();
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "s4b-muestreo-heap");
        muestreador.setDaemon(true);
        muestreador.start();

        String objectId;
        long inicio = System.nanoTime();
        try (InputStream contenido = almacen.abrir(ruta)) {
            objectId = archivo.archivar(solicitud, contenido, tamano, Map.of());
        } finally {
            midiendo.set(false);
            muestreador.join();
        }
        long ms = (System.nanoTime() - inicio) / 1_000_000;

        assertNotNull(objectId);
        assertFalse(objectId.isBlank());

        Optional<String> encontrado = archivo.buscarPorSolicitud(solicitud);

        CmisObject objeto = verificacion.getObject(objectId);
        long longitudServidor = ((Document) objeto).getContentStreamLength();

        System.out.printf("%nS4B RESULTADO jvm=%s xmx=%d MiB tiempoMs=%d MBps=%.1f heapBaseMiB=%.1f "
                        + "heapMaxMiB=%.1f muestras=%d objectId=%s buscarPorSolicitud=%s contentStreamLength=%d%n",
                System.getProperty("java.vm.name"), rt.maxMemory() / (1024 * 1024), ms,
                TAMANO / (1024.0 * 1024.0) / (ms / 1000.0), heapBase / (1024.0 * 1024.0),
                heapMax.get() / (1024.0 * 1024.0), muestras.get(), objectId, encontrado.orElse("<vacío>"),
                longitudServidor);

        assertEquals(Optional.of(objectId), encontrado, "buscarPorSolicitud debe devolver el mismo objectId");
        assertEquals(TAMANO, longitudServidor, "contentStreamLength en el servidor CMIS");
    }

    private static Session sesionVerificacion() {
        Map<String, String> p = new HashMap<>();
        p.put(SessionParameter.BINDING_TYPE, BindingType.BROWSER.value());
        p.put(SessionParameter.BROWSER_URL, CMIS_URL);
        p.put(SessionParameter.USER, USUARIO);
        p.put(SessionParameter.PASSWORD, CLAVE);
        p.put(SessionParameter.REPOSITORY_ID, CMIS_REPO);
        return SessionFactoryImpl.newInstance().createSession(p);
    }

    /** Hace repetible la prueba sin reiniciar el contenedor. */
    private static void borrarRestosDeCorridasPrevias(Session s, String nombre) {
        String consulta = "SELECT " + PropertyIds.OBJECT_ID + " FROM cmis:document WHERE " + PropertyIds.NAME
                + " = '" + nombre + "'";
        for (QueryResult r : s.query(consulta, false)) {
            String id = r.getPropertyValueById(PropertyIds.OBJECT_ID);
            s.delete(s.createObjectId(id), true);
            System.out.println("S4B borrado resto previo objectId=" + id);
        }
    }

    private static String parametro(String nombre, String defecto) {
        String valor = System.getProperty(nombre, System.getenv(nombre));
        return valor == null || valor.isBlank() ? defecto : valor;
    }
}
