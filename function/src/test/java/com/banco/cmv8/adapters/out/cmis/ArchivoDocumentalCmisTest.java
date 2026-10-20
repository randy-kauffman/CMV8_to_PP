package com.banco.cmv8.adapters.out.cmis;

import com.banco.cmv8.domain.catalogo.DefinicionTipoCmis;
import org.apache.chemistry.opencmis.client.api.ObjectType;
import org.apache.chemistry.opencmis.client.api.Tree;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.definitions.Choice;
import org.apache.chemistry.opencmis.commons.definitions.DocumentTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDateTimeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyIdDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyIntegerDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyStringDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArchivoDocumentalCmisTest {

    // ------------------------------------------------------------------ conversión de tipos

    @Test
    void convierteUnTipoDeDocumentoConSoloLasPropiedadesEscribibles() {
        Map<String, PropertyDefinition<?>> definiciones = new LinkedHashMap<>();
        definiciones.put(PropertyIds.NAME, cadena(PropertyIds.NAME, Updatability.READWRITE, true, null, null));
        definiciones.put(PropertyIds.OBJECT_TYPE_ID, id(PropertyIds.OBJECT_TYPE_ID, Updatability.ONCREATE));
        definiciones.put(PropertyIds.OBJECT_ID, id(PropertyIds.OBJECT_ID, Updatability.READONLY));
        definiciones.put("clb.CUENTA", cadena("clb.CUENTA", Updatability.READWRITE, true, List.of("0000"), BigInteger.valueOf(20)));
        definiciones.put("clb.CANAL", conOpciones());
        definiciones.put("clb.SECUENCIA", entero("clb.SECUENCIA"));
        definiciones.put("clb.FECHA", fecha("clb.FECHA"));
        definiciones.put("clb.CHECKOUT", cadena("clb.CHECKOUT", Updatability.WHENCHECKEDOUT, false, null, null));

        DocumentTypeDefinition tipo = mock(DocumentTypeDefinition.class);
        when(tipo.getId()).thenReturn("clb.EXTRACTO");
        when(tipo.getLocalName()).thenReturn("EXTRACTO");
        when(tipo.getDisplayName()).thenReturn("EXT - Extracto");
        when(tipo.getParentTypeId()).thenReturn("cmis:document");
        when(tipo.getBaseTypeId()).thenReturn(BaseTypeId.CMIS_DOCUMENT);
        when(tipo.isVersionable()).thenReturn(true);
        when(tipo.getPropertyDefinitions()).thenReturn(definiciones);

        DefinicionTipoCmis d = ArchivoDocumentalCmis.convertir(tipo);

        assertEquals("clb.EXTRACTO", d.tipoId());
        assertEquals("EXTRACTO", d.localName());
        assertEquals("EXT - Extracto", d.nombreVisible());
        assertEquals("cmis:document", d.tipoPadreId());
        assertTrue(d.versionable());
        assertEquals(List.of("clb.CUENTA", "clb.CANAL", "clb.SECUENCIA", "clb.FECHA"),
                d.propiedades().stream().map(DefinicionTipoCmis.Propiedad::id).toList());

        DefinicionTipoCmis.Propiedad cuenta = d.propiedad("clb.CUENTA").orElseThrow();
        assertEquals("string", cuenta.tipoDato());
        assertTrue(cuenta.obligatoria());
        assertEquals("0000", cuenta.valorPorDefecto());
        assertEquals(20, cuenta.largoMaximo());
        assertFalse(cuenta.obligatoriaSinDefault());

        DefinicionTipoCmis.Propiedad canal = d.propiedad("clb.CANAL").orElseThrow();
        assertEquals(List.of("Sucursal", "WEB", "Móvil"), canal.listaValores());
        assertNull(canal.valorPorDefecto());

        DefinicionTipoCmis.Propiedad secuencia = d.propiedad("clb.SECUENCIA").orElseThrow();
        assertEquals("integer", secuencia.tipoDato());
        assertNull(secuencia.largoMaximo());
        assertTrue(secuencia.heredada());
        assertTrue(secuencia.obligatoriaSinDefault());

        DefinicionTipoCmis.Propiedad fecha = d.propiedad("clb.FECHA").orElseThrow();
        assertEquals("datetime", fecha.tipoDato());
        assertEquals("2026-01-02T03:04:05Z", fecha.valorPorDefecto());
    }

    @Test
    void tipoNoVersionableYSinPropiedades() {
        DocumentTypeDefinition tipo = mock(DocumentTypeDefinition.class);
        when(tipo.getId()).thenReturn("cmis:document");
        when(tipo.getBaseTypeId()).thenReturn(BaseTypeId.CMIS_DOCUMENT);
        when(tipo.isVersionable()).thenReturn(null);
        when(tipo.getPropertyDefinitions()).thenReturn(null);

        DefinicionTipoCmis d = ArchivoDocumentalCmis.convertir(tipo);

        assertFalse(d.versionable());
        assertTrue(d.propiedades().isEmpty());
    }

    @Test
    void aplanaElArbolYSoloDejaDocumentos() {
        ObjectType documento = tipo("cmis:document", BaseTypeId.CMIS_DOCUMENT);
        ObjectType hijo = tipo("clb.EXTRACTO", BaseTypeId.CMIS_DOCUMENT);
        ObjectType nieto = tipo("clb.EXTRACTO_TC", BaseTypeId.CMIS_DOCUMENT);
        ObjectType carpeta = tipo("cmis:folder", BaseTypeId.CMIS_FOLDER);
        ObjectType subcarpeta = tipo("clb.CARPETA", BaseTypeId.CMIS_FOLDER);

        List<Tree<ObjectType>> arbol = List.of(
                nodo(documento, List.of(nodo(hijo, List.of(nodo(nieto, null))))),
                nodo(carpeta, List.of(nodo(subcarpeta, List.of()))));

        List<TypeDefinition> planos = new ArrayList<>();
        ArchivoDocumentalCmis.aplanar(arbol, planos);

        assertEquals(List.of("cmis:document", "clb.EXTRACTO", "clb.EXTRACTO_TC", "cmis:folder", "clb.CARPETA"),
                planos.stream().map(TypeDefinition::getId).toList());
        assertEquals(List.of("cmis:document", "clb.EXTRACTO", "clb.EXTRACTO_TC"),
                planos.stream().filter(ArchivoDocumentalCmis::esDocumento).map(TypeDefinition::getId).toList());
    }

    // ------------------------------------------------------------------ CMIS QL

    @Test
    void escapaComillaYBarraInvertida() {
        assertEquals("O\\'Brien", ArchivoDocumentalCmis.escapar("O'Brien"));
        assertEquals("C:\\\\tmp\\\\a", ArchivoDocumentalCmis.escapar("C:\\tmp\\a"));
        assertEquals("\\\\\\'", ArchivoDocumentalCmis.escapar("\\'"));
    }

    @Test
    void consultaPorNombreUsaElQueryNameYEscapa() {
        String consulta = ArchivoDocumentalCmis.consultaPorNombre("clb:EXTRACTO", "d'a.pdf", "idf-1");

        assertEquals("SELECT cmis:objectId FROM clb:EXTRACTO WHERE cmis:name = 'd\\'a.pdf' AND IN_FOLDER('idf-1')",
                consulta);
    }

    @Test
    void parametrosNoMuestranLaClave() {
        String texto = new ArchivoDocumentalCmis.ParametrosCmis("https://cm/cmis", "svc", "secreta", null).toString();

        assertFalse(texto.contains("secreta"));
    }

    // ------------------------------------------------------------------ fixtures

    private static PropertyStringDefinition cadena(String id, Updatability u, boolean obligatoria,
                                                   List<String> defaults, BigInteger maximo) {
        PropertyStringDefinition p = mock(PropertyStringDefinition.class);
        when(p.getId()).thenReturn(id);
        when(p.getDisplayName()).thenReturn(id);
        when(p.getPropertyType()).thenReturn(PropertyType.STRING);
        when(p.getUpdatability()).thenReturn(u);
        when(p.isRequired()).thenReturn(obligatoria);
        when(p.isInherited()).thenReturn(false);
        when(p.getDefaultValue()).thenReturn(defaults);
        when(p.getMaxLength()).thenReturn(maximo);
        return p;
    }

    @SuppressWarnings("unchecked")
    private static PropertyStringDefinition conOpciones() {
        PropertyStringDefinition p = cadena("clb.CANAL", Updatability.READWRITE, false, List.of(), null);
        Choice<String> sucursal = mock(Choice.class);
        when(sucursal.getDisplayName()).thenReturn("Sucursal");
        Choice<String> web = mock(Choice.class);
        when(web.getDisplayName()).thenReturn(" ");
        when(web.getValue()).thenReturn(List.of("WEB"));
        Choice<String> movil = mock(Choice.class);
        when(movil.getDisplayName()).thenReturn("Móvil");
        // Lista jerárquica: Móvil cuelga de WEB.
        when(web.getChoice()).thenReturn(List.of(movil));
        when(p.getChoices()).thenReturn(List.of(sucursal, web));
        return p;
    }

    private static PropertyIntegerDefinition entero(String id) {
        PropertyIntegerDefinition p = mock(PropertyIntegerDefinition.class);
        when(p.getId()).thenReturn(id);
        when(p.getPropertyType()).thenReturn(PropertyType.INTEGER);
        when(p.getUpdatability()).thenReturn(Updatability.ONCREATE);
        when(p.isRequired()).thenReturn(true);
        when(p.isInherited()).thenReturn(true);
        return p;
    }

    private static PropertyDateTimeDefinition fecha(String id) {
        GregorianCalendar valor = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        valor.clear();
        valor.set(2026, 0, 2, 3, 4, 5);
        PropertyDateTimeDefinition p = mock(PropertyDateTimeDefinition.class);
        when(p.getId()).thenReturn(id);
        when(p.getPropertyType()).thenReturn(PropertyType.DATETIME);
        when(p.getUpdatability()).thenReturn(Updatability.READWRITE);
        when(p.getDefaultValue()).thenReturn(List.of(valor));
        return p;
    }

    private static PropertyIdDefinition id(String id, Updatability u) {
        PropertyIdDefinition p = mock(PropertyIdDefinition.class);
        when(p.getId()).thenReturn(id);
        when(p.getPropertyType()).thenReturn(PropertyType.ID);
        when(p.getUpdatability()).thenReturn(u);
        return p;
    }

    private static ObjectType tipo(String id, BaseTypeId base) {
        ObjectType t = mock(ObjectType.class);
        when(t.getId()).thenReturn(id);
        when(t.getBaseTypeId()).thenReturn(base);
        return t;
    }

    @SuppressWarnings("unchecked")
    private static Tree<ObjectType> nodo(ObjectType item, List<Tree<ObjectType>> hijos) {
        Tree<ObjectType> t = mock(Tree.class);
        when(t.getItem()).thenReturn(item);
        when(t.getChildren()).thenReturn(hijos);
        return t;
    }
}
