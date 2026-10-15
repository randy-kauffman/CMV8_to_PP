# Consultas y puntos de mejora

Bitácora de las preguntas que van surgiendo al recorrer `docs/aprendizaje/roadmap-explicacion.md` y de los puntos de mejora de la solución que salen de esas conversaciones. Cada mejora tiene un estado; cuando se implementa o se descarta, se dice por qué. Los hallazgos técnicos de las guías de montaje están en `docs/backlog-tecnico.md` §11; acá solo se referencian.

## Consultas

| Fecha | Pregunta | Respuesta corta | Detalle |
| --- | --- | --- | --- |
| 2026-10-14 | ¿Tengo que crear app registrations en el tenant del Banco? | Sí, dos (broker y conector), porque una app registration vive en un solo directorio y las nuestras son single tenant. Application Developer alcanza para crearlas; Cloud Application Administrator para el consentimiento de administrador. | guías 01 y 02 |
| 2026-10-14 | ¿Qué permisos necesita cada app registration? | Broker: permiso delegado Azure Storage `user_impersonation` (para el OBO) y expone su propio scope `user_impersonation`. Conector: solo el scope del broker. Ninguna necesita Graph, permisos de aplicación, roles RBAC ni usuario de aplicación en Dataverse (eso lo tiene la identidad administrada). | guías 01 y 02 |
| 2026-10-14 | No entiendo nada: ¿me lo explicás desde cero? | Se armó el roadmap de 10 pasos y se recorrió el paso 0 (el problema, la analogía de la consigna de equipaje, las piezas). | `roadmap-explicacion.md` |

## Puntos de mejora

| # | Fecha | Punto de mejora | Qué aporta | Estado |
| --- | --- | --- | --- | --- |
| M1 | 2026-10-14 | Conector personalizado con identidad administrada en lugar de client secret (soportado por Microsoft, no probado acá) | Elimina el único secreto que queda en la solución y su rotación de 6 meses | Por probar en desarrollo (backlog 11.10) |
| M2 | 2026-10-14 | Dead-letter en la suscripción de Event Grid | Hoy un evento no entregado en 24 h se descarta y solo lo recupera el Watchdog | Por agregar al Bicep (backlog 11.1) |
| M3 | 2026-10-14 | Parámetro para reutilizar el Log Analytics central del Banco | Evita crear un workspace propio donde ya hay uno conectado al SIEM | Por agregar al Bicep (backlog 11.4) |
