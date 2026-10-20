package com.banco.cmv8.domain.port;

import com.banco.cmv8.domain.catalogo.Conflicto;
import com.banco.cmv8.domain.catalogo.ConflictoRegistrado;
import com.banco.cmv8.domain.catalogo.PropiedadCm;
import com.banco.cmv8.domain.catalogo.TipoDocumento;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Catálogo de tipos de documento, propiedades y conflictos en la solución base de Dataverse (ADR-003 §1.2).
 * Lo lee el camino caliente a través de un caché (ver {@code CatalogoEnCache}); lo escriben las operaciones
 * de administración y el worker cuando detecta un conflicto.
 */
public interface CatalogoTipos {

    /** Todos los tipos con sus propiedades. */
    List<TipoDocumento> listar();

    Optional<TipoDocumento> obtener(UUID id);

    /**
     * Crea o actualiza un tipo descubierto por {@code tipoCmisId}. Escribe SOLO las columnas descubiertas
     * (nombre, localName, versionable, ausente, revisado, cambios, descubierto en, código si es nuevo, carpeta
     * si no es manual); nunca toca en uso, destino, momento, retención ni inmutable. Devuelve el id.
     */
    UUID guardarTipoDescubierto(TipoDocumento tipo, List<String> cambios, boolean esNuevo);

    /**
     * Crea o actualiza propiedades por {@code propiedadId}. Escribe solo las columnas descubiertas; la decisión
     * la escribe únicamente al crear (sugerida). Las propiedades del tipo que no están en la lista no se borran.
     */
    void guardarPropiedadesDescubiertas(UUID tipoId, List<PropiedadCm> propiedades);

    UUID registrarConflicto(Conflicto conflicto, int adjuntosDetenidos);

    void actualizarAdjuntosDetenidos(UUID conflictoId, int adjuntosDetenidos);

    Optional<ConflictoRegistrado> obtenerConflicto(UUID conflictoId);

    void marcarConflictoResuelto(UUID conflictoId);

    /**
     * Compuerta de las operaciones de administración: lectura suplantada de la tabla de conflictos ({@code $top=1}).
     * Solo el rol de administrador documental (y el worker) la leen; un usuario común recibe 403 → false.
     */
    boolean puedeAdministrar(com.banco.cmv8.domain.ContextoLlamador llamador);
}
