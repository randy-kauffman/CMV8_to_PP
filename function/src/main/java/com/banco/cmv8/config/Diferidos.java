package com.banco.cmv8.config;

import com.banco.cmv8.domain.DocumentoAArchivar;
import com.banco.cmv8.domain.catalogo.DefinicionTipoCmis;
import com.banco.cmv8.domain.port.ArchivoDocumental;
import com.banco.cmv8.domain.port.ResolutorAs400;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Delegados perezosos: el adaptador real (y su configuración obligatoria) se construye recién en la primera
 * llamada. Así un caso de uso que depende de Content Manager o del AS400 se puede armar en un entorno sin esa
 * configuración y solo falla si de verdad la usa (por ejemplo, ValidarTipo sin tipos en uso en el entorno de spikes).
 */
final class Diferidos {

    private Diferidos() {
    }

    static ArchivoDocumental archivo(Supplier<ArchivoDocumental> real) {
        return new ArchivoDocumental() {
            @Override
            public String archivar(DocumentoAArchivar documento, InputStream contenido, long tamano) {
                return real.get().archivar(documento, contenido, tamano);
            }

            @Override
            public Optional<String> buscarPorNombre(String tipoCmisId, String carpeta, String nombre) {
                return real.get().buscarPorNombre(tipoCmisId, carpeta, nombre);
            }

            @Override
            public List<DefinicionTipoCmis> descubrirTipos() {
                return real.get().descubrirTipos();
            }

            @Override
            public Optional<DefinicionTipoCmis> definicionTipo(String tipoCmisId) {
                return real.get().definicionTipo(tipoCmisId);
            }

            @Override
            public boolean existeCarpeta(String ruta) {
                return real.get().existeCarpeta(ruta);
            }

            @Override
            public void borrar(String cmisObjectId) {
                real.get().borrar(cmisObjectId);
            }
        };
    }

    static ResolutorAs400 as400(Supplier<ResolutorAs400> real) {
        return new ResolutorAs400() {
            @Override
            public Set<String> consultasDeclaradas() {
                return real.get().consultasDeclaradas();
            }

            @Override
            public Optional<Map<String, Object>> consultar(String nombreConsulta, Map<String, Object> parametros) {
                return real.get().consultar(nombreConsulta, parametros);
            }
        };
    }
}
