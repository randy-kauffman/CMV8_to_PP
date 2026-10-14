# Costos estimados: Power Platform → Content Manager V8

- Fecha: 2026-10-13
- Moneda: USD, precios de lista. Los precios de Azure salen de la API pública Azure Retail Prices (región eastus) y los de Power Platform de la página oficial de precios. Los marcados "aprox." vienen de fuentes secundarias o de precios que varían por región y hay que confirmar en la calculadora de Azure o con el partner de licencias.
- Complementa a `decisiones/ADR-001-conectividad-power-platform-cmv8.md`.

> **Nota (2026-10-13):** en el Banco todos los usuarios ya tienen Power Apps Premium. Las licencias son costo hundido para este proyecto. El costo recurrente incremental real es solo Azure: entre ~$10 y ~$90 por mes según el escenario de la sección 4, más los opcionales (Always Ready, NAT Gateway) si se activan. La sección 1 se conserva como referencia para el caso de incorporar usuarios externos o sin licencia.

## 1. Costos recurrentes fijos (licencias)

| Concepto | Precio de lista | Notas |
| --- | --- | --- |
| Power Apps Premium | $20 / usuario / mes (anual). $12 con 2.000+ licencias | Obligatoria para CADA usuario que sube documentos, porque la app usa Dataverse y un custom connector. Incluye 250 MB de base de datos y 2 GB de capacidad File por licencia. |
| Power Automate para el flow | $0 adicional | El flow es automático, está en contexto de la app (asociado a ella) y el dueño tiene Power Apps Premium. Con eso el custom connector está cubierto. Si el flow se saca del contexto de la app, pasa a requerir Power Automate Premium ($15 / usuario / mes aprox.) o Process. |
| Plan Power Apps "per app" | No disponible | Ya no figura en la página oficial de precios. No contar con él. |
| Managed Environments | $0 adicional | Ya activo; viene con las licencias premium. |
| Dataverse capacidad extra | Database $40 / GB / mes. File $2 / GB / mes aprox. | Solo si se supera el entitlement. Ver sección 3. |

## 2. Costos recurrentes variables (Azure)

Precios verificados en Azure Retail Prices para Flex Consumption:

| Medidor | Precio | Franquicia mensual gratis |
| --- | --- | --- |
| Tiempo de ejecución on demand | $0.000026 por GB-segundo | 100.000 GB-s por suscripción |
| Ejecuciones on demand | $0.40 por millón | 250.000 ejecuciones por suscripción |
| Always Ready baseline (opcional) | $0.000004 por GB-s de instancia reservada | Ninguna |

Otros componentes:

| Componente | Estimación mensual | Notas |
| --- | --- | --- |
| Storage account (cola + runtime de Functions) | $1 a $5 aprox. | Transacciones y pocos GB. |
| Key Vault | menos de $1 aprox. | Secretos: usuario AS400, credencial CMIS. |
| Application Insights / Log Analytics | $0 a $15 aprox. | Primeros 5 GB de ingesta gratis; después ~$2.30 / GB aprox. |
| VNet integration de la Function | $0 | Sin cargo por la integración en sí. |
| NAT Gateway (solo si seguridad exige salida controlada) | ~$33 / mes + $0.045 / GB aprox. | Opcional. |
| Tráfico Azure → red interna por ExpressRoute | $0 con plan ilimitado; ~$0.025 / GB aprox. con plan medido | Ya existe el circuito. Solo cuenta el egreso de los videos hacia Content Manager. |
| Entra ID (app registration, managed identity) | $0 | |

## 3. Dataverse: por qué el worker DEBE borrar el archivo

Capacidad File incluida con 20 licencias Premium: 20 × 2 GB = 40 GB (más la capacidad base del tenant).

- Con borrado al archivar: en Dataverse solo viven los documentos en tránsito (1 o 2 días). Ejemplo: 100 docs/día × 30 MB = 3 GB. Entra en el entitlement. Costo extra: $0.
- Sin borrado: 2.200 docs/mes × 30 MB = 66 GB de crecimiento POR MES. Se supera el entitlement en el primer mes y el costo del add-on File crece sin techo (~$130/mes al segundo mes, ~$260 al tercero, y sigue).

Conclusión: el borrado del binario tras el archivado no es una optimización, es un requisito de diseño.

## 4. Escenarios de ejemplo

Supuestos de cómputo: instancia de 2 GB, 22 días hábiles/mes, 3 ejecuciones por documento (HTTP, cola, actualización) más el watchdog cada 10 minutos (4.320 ejecuciones/mes).

### Escenario chico: 20 usuarios, 100 documentos/día, 30 MB promedio, 60 s de proceso

| Rubro | Cálculo | Mensual |
| --- | --- | --- |
| Licencias Power Apps Premium | 20 × $20 | $400 |
| Functions tiempo de ejecución | 2.200 docs × 120 GB-s = 264.000 GB-s − 100.000 gratis = 164.000 × $0.000026 | $4.26 |
| Functions ejecuciones | ~11.000, dentro de la franquicia | $0 |
| Storage, Key Vault, App Insights | aprox. | $3 a $8 |
| Dataverse extra | con borrado | $0 |
| **Total recurrente** | | **~$410 / mes** |

### Escenario mediano: 50 usuarios, 500 documentos/día, 60 MB promedio, 120 s de proceso

| Rubro | Cálculo | Mensual |
| --- | --- | --- |
| Licencias Power Apps Premium | 50 × $20 | $1.000 |
| Functions tiempo de ejecución | 11.000 docs × 240 GB-s = 2.640.000 GB-s − 100.000 gratis = 2.540.000 × $0.000026 | $66 |
| Functions ejecuciones | ~37.000, dentro de la franquicia | $0 |
| Storage, Key Vault, App Insights | aprox. | $8 a $20 |
| ExpressRoute medido (si aplica) | 660 GB × $0.025 aprox. | $0 a $17 |
| Dataverse extra | con borrado | $0 |
| **Total recurrente** | | **~$1.080 a $1.100 / mes** |

Opcionales que suman en cualquier escenario:

- Una instancia Always Ready de 2 GB para evitar arranque en frío: 2 × 2.592.000 s × $0.000004 ≈ $21 / mes.
- NAT Gateway: ~$33 / mes más tráfico.

Lectura: en los dos escenarios las licencias de Power Apps son más del 90 % del costo recurrente. Azure es casi despreciable. La variable que mueve el presupuesto es la cantidad de usuarios que suben documentos, no el volumen de videos.

## 5. Costo único: construcción

Estimación gruesa de esfuerzo, no compromiso. La estimación formal se hace en la etapa de definición con el desglose por componente.

| Componente | Perfil | Esfuerzo |
| --- | --- | --- |
| Tabla SolicitudIngesta, roles, Canvas App (subida y estado), flow, custom connector | Maker / consultor Power Platform | 1 a 2 semanas |
| Function Java: HTTP, worker de cola, watchdog, cliente Dataverse Web API, jt400, OpenCMIS, pruebas unitarias | Desarrollador Java senior | 3 a 5 semanas |
| Infraestructura como código, VNet, Key Vault, managed identity, pipeline de despliegue, imagen y monitoreo | DevOps / cloud | 1 a 2 semanas |
| Pruebas de integración con Content Manager y AS400, ajuste de metadatos, piloto con usuarios | Equipo completo | 1 a 2 semanas |
| **Total** | | **6 a 11 semanas calendario, aprox. 1 desarrollador senior + 0,5 maker + apoyo DevOps** |

Riesgos que estiran el esfuerzo: metadatos obligatorios de Content Manager mal documentados, permisos del perfil de IBM i demorados, aprobación de seguridad del endpoint público.

## 5b. Ajuste por ADR-002 (Blob Storage para los binarios)

- Blob Storage hot, LRS: ~$0,02 / GB / mes aprox. Con borrado al archivar y 7 días de retención máxima, el escenario mediano mantiene menos de 250 GB en tránsito: menos de $5 / mes. Transacciones despreciables.
- Event Grid: despreciable a este volumen (primeras 100.000 operaciones/mes gratis aprox.).
- Defender for Storage: cargo por cuenta de almacenamiento protegida más escaneo antivirus por GB subido (con cap mensual configurable). Confirmar en la calculadora de Defender for Cloud. Escenario mediano: ~660 GB escaneados/mes.
- Dataverse: desaparece el riesgo de capacidad File y el consumo del pool de requests baja a ~3 por documento.

## 6. Costos que NO aparecen en la factura pero existen

- Mantenimiento de la Function: actualizaciones de Java y de librerías (jt400, OpenCMIS), rotación de la contraseña de AS400 en Key Vault.
- Monitoreo: alguien tiene que mirar la poison queue y las solicitudes en Error.
- Capacidad de Content Manager: los videos terminan ahí. Confirmar con el administrador de CMV8 el espacio en disco del Resource Manager.
