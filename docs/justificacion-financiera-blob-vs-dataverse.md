# Justificación financiera: Azure Blob Storage vs Dataverse para los binarios

- Fecha: 2026-10-13
- Moneda: USD, precios de lista. Fuentes: API pública Azure Retail Prices (región eastus), Microsoft Learn y página oficial de precios de Power Apps. Los marcados "aprox." vienen de fuentes secundarias y deben confirmarse con el partner de licencias.
- Complementa a `decisiones/ADR-002-blob-storage-para-binarios.md` y a `costos-estimados.md`.

## 1. Precios unitarios verificados

| Concepto | Precio | Fuente |
| --- | --- | --- |
| Dataverse, capacidad File (donde viven las columnas de archivo) | $2 / GB / mes aprox. (pago por uso $2,40) | Fuentes secundarias; la página oficial solo publica Database |
| Dataverse, capacidad Database | $40 / GB / mes | Página oficial. No aplica a archivos: las columnas de archivo consumen File, no Database |
| Dataverse, File incluido por licencia Power Apps Premium | 2 GB por licencia, pool del tenant | Página oficial |
| Blob Storage hot, LRS, almacenado | $0,0208 / GB / mes | Azure Retail Prices |
| Blob Storage cool, LRS, almacenado | $0,0152 / GB / mes | Azure Retail Prices |
| Blob Storage hot, escrituras | $0,05 por 10.000 | Azure Retail Prices |
| Blob Storage hot, lecturas | $0,004 por 10.000 | Azure Retail Prices |
| Power Platform requests, add-on | $50 / mes por 50.000 requests diarios adicionales | Microsoft Learn. Nota: durante el período de transición no se puede asignar a usuarios ni flows |
| Pool de requests para usuarios no licenciados | 25.000 / día (solo Power Apps) o 500.000 + 5.000 por licencia (con Dynamics 365) | Microsoft Learn |

Relación de precio por GB almacenado: Dataverse File / Blob hot = **96 a 1**.

## 2. Supuestos de volumen

| Parámetro | Escenario chico | Escenario mediano |
| --- | --- | --- |
| Documentos por día hábil | 100 | 500 |
| Días hábiles por mes | 22 | 22 |
| Documentos por mes | 2.200 | 11.000 |
| Tamaño promedio | 30 MB | 60 MB |
| Volumen ingresado por mes | 66 GB | 660 GB |

## 3. Comparación para CMV8 (archivos transitorios, borrados al archivar en Content Manager)

En este caso el binario vive 1 o 2 días en la plataforma. Volumen en tránsito: 6 GB (chico) y 60 GB (mediano).

| Rubro mensual | Dataverse (columna de archivo) | Blob Storage |
| --- | --- | --- |
| Almacenamiento | $0 si el pool File del tenant tiene espacio; $12 a $120 si está agotado | $0,12 a $1,25 |
| Transacciones | incluidas | menos de $2 (escrituras por bloque) |
| Requests Dataverse del application user | ~28 por documento con chunks de 4 MB: 2.800 a 14.000 por día | ~2 por documento: 200 a 1.000 por día |
| Riesgo de throttling del pool de 25.000/día | Alto en el escenario mediano (56 % del pool para una sola solución) | Despreciable |
| Event Grid | no aplica | menos de $1 |
| Defender for Storage, antivirus al subir (control que hoy no existe) | no disponible para columnas de archivo | $9,78 por cuenta/mes ($0,0134/h) + $0,15 por GB escaneado: chico 66 GB → ~$20/mes; mediano 660 GB → ~$109/mes. Precios verificados en Azure Retail Prices, eastus2, 2026-10-14 |

Lectura honesta: **para CMV8 solo, con borrado al archivar, el costo operativo de las dos opciones es parecido y bajo**. Blob es más barato en almacenamiento y requests, y más caro si se activa el antivirus. La diferencia en dólares no es la que justifica el cambio.

## 4. Lo que SÍ justifica el cambio: retención y escala

Varias soluciones del Banco necesitan guardar adjuntos de forma permanente, sin pasar a Content Manager. Ahí el precio por GB manda, porque el volumen se acumula.

Crecimiento acumulado con el volumen del escenario mediano (660 GB/mes), costo mensual al final de cada año y acumulado del período, solo almacenamiento por encima del pool incluido:

| Momento | Volumen acumulado | Dataverse File ($2/GB) | Blob hot ($0,0208/GB) | Blob cool ($0,0152/GB) |
| --- | --- | --- | --- | --- |
| Mes 12, costo mensual | 7,9 TB | $15.840 / mes | $165 / mes | $120 / mes |
| Acumulado año 1 | | $102.960 | $1.071 | $782 |
| Mes 24, costo mensual | 15,8 TB | $31.680 / mes | $330 / mes | $241 / mes |
| Acumulado 2 años | | $411.840 | $4.284 | $3.130 |

Con el volumen del escenario chico (66 GB/mes) las cifras se dividen por 10: Dataverse $10.296 el primer año contra $107 en Blob.

Matiz importante sobre el pool incluido: cada licencia Premium aporta 2 GB de File al pool del tenant. Si el Banco tiene miles de usuarios Premium, el pool puede ser de varios TB, compartido por TODAS las cargas (adjuntos de Dynamics, notas, imágenes, otras apps). Hay que mirar el reporte de capacidad antes de asumir que está libre. Una vez agotado, cada GB nuevo cuesta $2 y la factura sube todos los meses mientras los archivos existan.

## 5. Requests: por qué no es un problema de dólares sino de riesgo

- Costo de cubrir 14.000 requests/día con add-ons: 1 unidad, $50 / mes. Barato.
- Pero el add-on **no se puede asignar durante el período de transición** según Microsoft Learn, y el pool de no licenciados es compartido por todas las integraciones del tenant. Una solución que consume más de la mitad del pool compite con las demás y puede provocar throttling cruzado.
- Blob saca el binario del camino de Dataverse: el consumo baja de ~28 a ~2 requests por documento. Es una reducción de riesgo operativo, no un ahorro de $50.
- Si el Banco tiene licencias Dynamics 365, el pool es de 500.000 o más y este punto pierde peso. Confirmar (pendiente en ADR-001).

## 6. Costo único de construcción: Blob cuesta más

| Componente adicional del camino Blob | Esfuerzo estimado |
| --- | --- |
| Componente PCF de carga directa con SAS | 1 a 2 semanas |
| Broker de SAS en la Function (validación contra Dataverse, emisión, auditoría) | 1 semana |
| Gobierno de Storage (políticas, private endpoint, Defender, alertas, IaC) | 1 semana |
| **Total adicional respecto del camino Dataverse** | **3 a 4 semanas de un desarrollador senior, una sola vez** |

Esta inversión es reutilizable: el broker y el modelo de gobierno sirven a toda solución del Banco que guarde adjuntos. La segunda solución que lo use no paga este costo.

## 7. Conclusión para el comité

1. Para CMV8 aislado, con borrado al archivar, las dos opciones cuestan parecido en operación. Blob reduce a cero el riesgo de throttling del pool de requests y agrega antivirus al subir.
2. Para cualquier solución que retenga archivos, Dataverse File cuesta 96 veces más por GB que Blob. Con 660 GB/mes de crecimiento, la diferencia supera los $100.000 el primer año y los $400.000 acumulados a dos años.
3. El sobrecosto de construcción (3 a 4 semanas) se paga una vez y se amortiza con la primera solución que retenga archivos.
4. Decisión recomendada: construir el camino Blob con CMV8 como primer caso, y establecerlo como estándar de adjuntos para el Banco.

Pendientes que afinan los números: confirmar el precio del add-on File con el partner de licencias, leer el reporte de capacidad File del tenant, confirmar licencias Dynamics 365, y validar el precio de Defender for Storage con el partner (ya verificado en el API de precios: $0,0134/h por cuenta, $0,15/GB escaneado, $0,1492 por millón de transacciones).

## 8. Nota sobre Defender for Storage (2026-10-14)

Decisión del usuario: **no se ejecuta el spike S3** en el entorno propio para no incurrir en el costo fijo. Queda cuantificado para el Banco con precios verificados (Azure Retail Prices, eastus2):

| Meter | Precio | Escenario chico (66 GB/mes) | Escenario mediano (660 GB/mes) |
| --- | --- | --- | --- |
| Standard Node, por cuenta de Storage protegida | $0,0134 / hora ≈ $9,78 / mes | $9,78 | $9,78 |
| Malware Scanning Data Ingested | $0,15 / GB subido y escaneado | $9,90 | $99,00 |
| Standard Transactions | $0,1492 / millón | despreciable | despreciable |
| **Total** | | **≈ $20 / mes** | **≈ $109 / mes** |

Con cap mensual de GB escaneados configurable por cuenta para acotar el máximo. Es el único componente de la solución con costo fijo; el resto escala a cero. Si el Banco ya tiene Defender for Storage habilitado a nivel de suscripción, el cargo por cuenta ya está pagado y solo suma el escaneo por GB. Sin Defender, el trigger del worker es `BlobCreated` directo (fallback ya contemplado en el Bicep) y el antivirus queda a cargo de Content Manager o del endpoint del usuario, como hoy.
