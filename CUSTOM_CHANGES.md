# AA Torque 2.0.31 — tarjeta de consumo

Esta variante se basa en AA Torque 2.0.30 y añade una fuente multimedia para Android Auto.

## Datos mostrados

- Rendimiento promedio del viaje en km/galón estadounidense.
- Rendimiento instantáneo en km/galón estadounidense.
- Distancia acumulada en kilómetros.
- Combustible consumido en galones estadounidenses.
- Estado de conexión y registro.

## Funcionamiento

La aplicación consulta en Torque Pro los PID de velocidad y flujo de combustible. Reconoce
velocidad en km/h o mph y flujo en L/h, L/min, mL/min, cc/min o gal/h. Integra ambos valores
en el tiempo y guarda el total cada diez segundos y al cerrar el servicio.

Los datos se conservan al apagar el vehículo. En la pantalla de ajustes del teléfono, dentro
de **Multimedia**, se puede consultar el total actual y reiniciar manualmente el viaje.

Android Auto controla el diseño de la tarjeta. Para verla, se debe elegir **AA Torque Consumo**
como fuente multimedia. Al hacerlo, la tarjeta utiliza los campos de título, artista y álbum
para presentar los valores.

## Consideraciones

- Requiere Torque Pro y un adaptador OBD2 conectado.
- El vehículo o Torque Pro debe proporcionar un PID de flujo de combustible compatible.
- Esta fuente ocupa la tarjeta multimedia; no reproduce música.
- Es una variante no oficial y debe instalarse manualmente.
- El código continúa bajo GNU GPL v3, igual que el proyecto de origen.
