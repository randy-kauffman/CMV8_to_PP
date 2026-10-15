# 13. Presupuesto y alertas de costo

> Rol que lo ejecuta: Cloud (con rol Owner, Contributor o Cost Management Contributor sobre el alcance). Depende de: 03 (grupo de recursos `RG`). Lo necesitan: nadie técnicamente; Finanzas y el dueño de la solución lo usan como control. Costo: sin costo propio.

## Qué es y qué hace en esta solución

Un **budget** de Cost Management sobre el grupo de recursos del ambiente (o sobre la suscripción), con período mensual y alertas por correo cuando el gasto real o el pronosticado supera un porcentaje del monto. No detiene ni limita recursos: solo avisa. Cost Management evalúa los budgets cada 24 horas sobre datos de costo que llegan con 8 a 24 horas de demora, y el correo sale normalmente dentro de la hora siguiente a la evaluación. Es decir: un desvío se ve al día siguiente, no en el momento.

En esta solución el costo de Azure es chico y casi todo variable (`docs/costos-estimados.md`): Flex Consumption escala a cero, Blob y Event Grid son centavos, Key Vault menos de 1 USD. Lo que puede disparar el gasto sin que nadie lo note es un componente con costo fijo o sin tope que se active por error: Defender for Storage habilitado a nivel de suscripción (cargo por cada cuenta), instancias Always Ready, un NAT Gateway, o un bucle de reintentos que mantenga instancias de la Function despiertas. El presupuesto es la alarma para esos casos.

## ¿Imprescindible?

**Veredicto: Prescindible por decisión** (técnicamente prescindible; recomendado como control financiero).

- Qué aporta: aviso temprano de desvíos de costo, por gasto real y por pronóstico; visibilidad del gasto del ambiente en Cost analysis contra un monto acordado; evidencia para Finanzas de que el costo incremental de Azure es el estimado.
- Qué pasa si no está: la solución funciona igual. Un desvío (Defender encendido en toda la suscripción, un bucle de reintentos) se descubre en la factura del mes.
- Qué decisión lo haría prescindible (o distinto): que el Banco ya tenga presupuestos y alertas de costo a nivel de suscripción o de management group gobernados por FinOps, con anomaly alerts. En ese caso se omite este budget o se crea solo uno por etiqueta (`proyecto=CMV8_to_PP`) dentro del esquema del Banco.
- Alternativa y su tradeoff: **alertas de anomalías de costo** (Cost Management, solo a nivel de suscripción): detectan picos sin fijar un monto, pero no sirven a nivel de grupo de recursos y no dan un umbral acordado con Finanzas. **Azure Policy** que bloquee SKUs caros: previene en lugar de avisar, pero no cubre costo variable. El budget es el control más barato de mantener; las otras dos se suman, no lo reemplazan.

## Valores del cuaderno

| Variable | Produce / consume | Cómo se obtiene |
| --- | --- | --- |
| `RG`, `AMBIENTE` | Consume | Cuaderno |
| `SUB` | Produce (local) | `az account show --query id -o tsv` |
| `BUDGET_NAME` | Produce | `cmv8-$AMBIENTE-mensual` |
| `BUDGET_MONTO` | Consume | Ver recomendación en "Con Bicep" |
| `ALERTA_EMAIL` | Consume | Buzón del equipo (no personal) que recibe las alertas; lo define el Banco |
| `INICIO` | Produce (local) | Primer día del mes en curso: `date -u +%Y-%m-01` |
| `FIN` | Produce (local) | `date -u -d "$INICIO +1 year" +%Y-%m-%d` |

## Con Bicep

**No está en `infra/main.bicep`.** El Bicep tiene alcance de grupo de recursos y un budget se podría declarar ahí (`Microsoft.Consumption/budgets`), pero se dejó afuera a propósito: el monto y los destinatarios son decisiones de Finanzas, cambian sin cambiar la infraestructura, y el correo de alertas es un dato del Banco que no conviene versionar. Se crea una vez por ambiente con CLI o portal.

En el tenant de desarrollo existe `cmv8-spikes-mensual`, creado con `az rest` (verificado 2026-10-14):

| Propiedad | Valor |
| --- | --- |
| Alcance | **Suscripción** (no el grupo de recursos) |
| Monto | **5 USD** por mes, categoría Cost, sin filtros |
| Período | 2026-10-01 a 2027-10-01 |
| Alertas | Real > 50 %, real > 90 %, pronóstico > 100 %, por correo |

**Recomendación de monto para el Banco**, a partir de `docs/costos-estimados.md` (§2, §4 y §5b; solo Azure, porque las licencias de Power Apps son costo hundido):

| Ambiente | Gasto esperado | Monto sugerido |
| --- | --- | --- |
| dev / qa | Funciones dentro de la franquicia gratis, Storage y App Insights de pocos GB: < 5 USD/mes | **20 USD** |
| prod, escenario chico (100 docs/día) | Functions ~4,3 + Storage/KV/App Insights 3 a 8 ≈ 8 a 13 USD/mes | **30 USD** |
| prod, escenario mediano (500 docs/día) | Functions ~66 + Storage/KV/App Insights 8 a 20 + ExpressRoute medido 0 a 17 ≈ 75 a 105 USD/mes | **120 USD** |
| Sumar si se habilita Defender (guía 12) | ≈ 20 USD (chico) / ≈ 109 USD (mediano) | +25 / +120 USD |
| Sumar si hay Always Ready o NAT Gateway | ~21 USD / ~33 USD + tráfico | + lo que corresponda |

Umbrales: real 50 % y 90 %, pronóstico 100 % (los mismos del sandbox). El pronóstico avisa a mitad de mes si la tendencia termina por encima del monto.

Alcance recomendado: el **grupo de recursos** del ambiente (`rg-cmv8-$AMBIENTE`). Así el presupuesto mide solo esta solución, aunque la suscripción sea compartida con otras del Banco. Un costo que la solución genere fuera del grupo (por ejemplo, Defender habilitado en la suscripción) no entra en ese budget: si esa es la preocupación, sumar uno a nivel de suscripción o una alerta de anomalías.

## Con Azure CLI

Verificado con `az --help` (az CLI 2.91). `az consumption budget create` existe pero está en **preview** y **no tiene parámetros de notificaciones**: crea un budget sin alertas. Para el budget con alertas se usa `az rest` contra `Microsoft.Consumption/budgets` (API `2023-11-01`, la misma con la que se leyó el del sandbox).

### Budget sobre el grupo de recursos, con alertas

```bash
source ~/cmv8-$AMBIENTE.env
SUB=$(az account show --query id -o tsv)
BUDGET_NAME="cmv8-$AMBIENTE-mensual"
BUDGET_MONTO=20                    # ver tabla de recomendación
ALERTA_EMAIL="<buzón del equipo>"  # lo define el Banco
INICIO=$(date -u +%Y-%m-01)
FIN=$(date -u -d "$INICIO +1 year" +%Y-%m-%d)

az rest --method PUT \
  --url "https://management.azure.com/subscriptions/$SUB/resourceGroups/$RG/providers/Microsoft.Consumption/budgets/$BUDGET_NAME?api-version=2023-11-01" \
  --body "{
    \"properties\": {
      \"category\": \"Cost\",
      \"amount\": $BUDGET_MONTO,
      \"timeGrain\": \"Monthly\",
      \"timePeriod\": { \"startDate\": \"${INICIO}T00:00:00Z\", \"endDate\": \"${FIN}T00:00:00Z\" },
      \"notifications\": {
        \"real-50\":       { \"enabled\": true, \"operator\": \"GreaterThan\", \"threshold\": 50,  \"thresholdType\": \"Actual\",     \"contactEmails\": [\"$ALERTA_EMAIL\"] },
        \"real-90\":       { \"enabled\": true, \"operator\": \"GreaterThan\", \"threshold\": 90,  \"thresholdType\": \"Actual\",     \"contactEmails\": [\"$ALERTA_EMAIL\"] },
        \"pronostico-100\":{ \"enabled\": true, \"operator\": \"GreaterThan\", \"threshold\": 100, \"thresholdType\": \"Forecasted\", \"contactEmails\": [\"$ALERTA_EMAIL\"] }
      }
    }
  }" -o none
```

Notas:

- `startDate` tiene que ser el primer día de un mes y no anterior al mes en curso.
- Por API también se puede avisar a roles (`contactRoles: ["Owner"]`) o a un action group (`contactGroups: [<id>]`); el portal no permite asignar roles. Un action group sirve para mandar el aviso a Teams o a un sistema de tickets del Banco.
- Para un budget a nivel de suscripción, quitar `/resourceGroups/$RG` de la URL (así está el del sandbox).

### Budget sin alertas (solo para verlo en Cost analysis)

```bash
az consumption budget create \
  --resource-group "$RG" \
  --budget-name "$BUDGET_NAME" \
  --category cost \
  --amount "$BUDGET_MONTO" \
  --time-grain monthly \
  --start-date "$INICIO" \
  --end-date "$FIN"
```

No se recomienda: un budget sin alertas no avisa a nadie.

## Con el portal

1. Ir al grupo de recursos `rg-cmv8-<ambiente>` → **Cost Management** → **Budgets** (verificar en el portal; camino documentado: **Cost Management + Billing** → **Subscriptions** → la suscripción → **Resource groups** → el grupo → **Budgets**) → **+ Add**.
2. Pantalla **Create budget**:
   - **Scope**: confirmar que es el grupo de recursos (CAMBIAR si muestra la suscripción).
   - **Name**: `cmv8-<ambiente>-mensual`.
   - **Reset period**: **Monthly** (calendario). Las suscripciones de pago por uso ofrecen además **Billing month**; usar **Monthly**.
   - **Creation date** / **Expiration date**: primer día del mes en curso y un año después (CAMBIAR el vencimiento: al vencer, el budget se borra solo).
   - **Amount**: el de la tabla de recomendación (CAMBIAR: el portal sugiere un monto según el pronóstico).
   - **Next**.
3. Pantalla de alertas (**Set alerts**):
   - **Alert conditions**: tres filas: **Type** = **Actual**, **% of budget** = 50; **Actual**, 90; **Forecasted**, 100.
   - **Alert recipients (email)**: el buzón del equipo.
   - **Language preference**: Español, si se quiere el correo en español (verificar en el portal).
   - **Action group**: opcional (Teams, tickets).
   - **Create**.
4. Pedir al dueño del buzón que agregue `azure-noreply@microsoft.com` a remitentes aprobados, para que las alertas no caigan en spam.

## Verificación

```bash
az rest --method GET \
  --url "https://management.azure.com/subscriptions/$SUB/resourceGroups/$RG/providers/Microsoft.Consumption/budgets/$BUDGET_NAME?api-version=2023-11-01" \
  --query 'properties.{monto:amount, periodo:timeGrain, desde:timePeriod.startDate, hasta:timePeriod.endDate, gastado:currentSpend.amount, alertas:keys(notifications)}'
# esperado: el monto, Monthly, las fechas, el gasto del mes y las tres alertas

az consumption budget list --resource-group "$RG" -o table   # preview en 2.91; muestra los budgets del grupo
```

`az consumption budget list` sin `--resource-group` lista los de la suscripción: ahí aparece `cmv8-spikes-mensual` en el tenant de desarrollo (gasto del mes al 2026-10-14: 0,02 USD).

## Errores que ya vimos

| Síntoma | Causa | Arreglo |
| --- | --- | --- |
| El budget se crea pero nadie recibe correos | Se usó `az consumption budget create`, que no admite notificaciones (visto al documentar: el comando de 2.91 no tiene flags de alertas) | `az rest PUT` con `notifications` |
| `az consumption ...` imprime "This command is in preview" | El grupo de comandos está en preview en 2.91 | Es un aviso; para producción preferir `az rest` |
| El budget del grupo no muestra el costo de Defender (anticipado) | Defender se habilitó a nivel de suscripción: el costo no está en el grupo | Habilitarlo por cuenta (guía 12) o sumar un budget de suscripción |
| `startDate` rechazada al crear (anticipado) | Fecha que no es primer día de mes o anterior al mes en curso | `INICIO=$(date -u +%Y-%m-01)` |
| El budget desaparece un año después (anticipado) | Llegó a `endDate` y Cost Management lo borra | Renovarlo antes de vencer; anotarlo en la tabla de operación (guía 23) |
| Alertas en spam (anticipado) | Remitente `azure-noreply@microsoft.com` no aprobado | Agregarlo a remitentes seguros |
