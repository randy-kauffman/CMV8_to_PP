package com.banco.cmv8.domain.port;

import com.banco.cmv8.domain.DocumentoAArchivar;
import com.banco.cmv8.domain.catalogo.DefinicionTipoCmis;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/** Content Manager por CMIS (ADR-003 §4.2 y §6): archivar, buscar, descubrir tipos y compensar. */
public interface ArchivoDocumental {

    /** Crea el documento en streaming y devuelve el objectId. */
    String archivar(DocumentoAArchivar documento, InputStream contenido, long tamano);

    /** Idempotencia: documento con ese nombre, de ese tipo, en esa carpeta (caída entre archivar y marcar). */
    Optional<String> buscarPorNombre(String tipoCmisId, String carpeta, String nombre);

    /** Árbol completo de tipos de documento con sus propiedades escribibles ({@code typeDescendants}). */
    List<DefinicionTipoCmis> descubrirTipos();

    /** Definición viva de un tipo; vacío si el servidor ya no lo publica. OpenCMIS la cachea en la sesión. */
    Optional<DefinicionTipoCmis> definicionTipo(String tipoCmisId);

    boolean existeCarpeta(String ruta);

    /** Compensación: el PATCH final a Dataverse falló y el documento recién creado no debe quedar en CM. */
    void borrar(String cmisObjectId);
}
