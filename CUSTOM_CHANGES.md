# AA Torque 2.0.32 — consumo sobre mapa

Esta variante se basa en AA Torque 2.0.30 y muestra el consumo de combustible dentro de una
aplicación de mapas independiente para Android Auto.

## Datos mostrados

- Rendimiento promedio del viaje en km/galón estadounidense.
- Rendimiento instantáneo en km/galón estadounidense.
- Distancia acumulada en kilómetros.
- Combustible consumido en galones estadounidenses.
- Estado de conexión con Torque Pro.

## Mapa

La aplicación se anuncia en Android Auto como **AA Torque Mapa** mediante la Car App Library.
Dibuja teselas de OpenStreetMap, la posición GPS del vehículo y una tarjeta de consumo sobre la
superficie del mapa. Incluye controles para acercar, alejar, centrar y reiniciar el viaje.

El mapa necesita conexión a Internet y permiso de ubicación. La atribución de OpenStreetMap se
muestra permanentemente. Esta aplicación no modifica ni superpone contenido sobre Google Maps o
Waze: al abrirla se convierte en el mapa activo de Android Auto.

## Consumo persistente

La aplicación consulta en Torque Pro los PID de velocidad y flujo de combustible. Reconoce
velocidad en km/h o mph y flujo en L/h, L/min, mL/min, cc/min o gal/h. Integra ambos valores en el
tiempo y guarda el total cada diez segundos y al cerrar la sesión del automóvil.

## Consideraciones

- Requiere Torque Pro y un adaptador OBD2 conectado.
- El vehículo o Torque Pro debe proporcionar un PID de flujo de combustible compatible.
- La ubicación se concede abriendo AA Torque una vez en el teléfono.
- Las teselas cartográficas son © colaboradores de OpenStreetMap.
- Es una variante no oficial y debe instalarse manualmente.
- El código continúa bajo GNU GPL v3, igual que el proyecto de origen.
