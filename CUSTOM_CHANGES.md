# AA Torque 2.0.33 — modos multimedia

Esta variante se basa en AA Torque 2.0.30 y muestra datos de Torque Pro como una fuente
multimedia de Android Auto. La vista de mapas experimental fue retirada.

## Modos seleccionables

En la biblioteca de **AA Torque Consumo** se puede elegir uno de estos modos:

- **Consumo:** rendimiento promedio e instantáneo, distancia y galones consumidos.
- **Motor:** RPM, temperatura del refrigerante, carga, voltaje y velocidad.
- **Viaje:** distancia, duración, velocidad media, costo estimado y autonomía.
- **Diagnóstico:** temperatura, voltaje, flujo de combustible, carga, RPM y nivel del tanque.
- **Automático:** alterna los cuatro modos anteriores cada ocho segundos.

El botón de siguiente pista también cambia al modo siguiente. Android Auto controla qué campos
y botones son visibles según la pantalla y el vehículo.

## Configuración y persistencia

La aplicación integra velocidad y flujo de combustible una vez por segundo y guarda distancia,
combustible y duración cada diez segundos y al cerrar el servicio. Los datos sobreviven al apagado
del vehículo y se pueden reiniciar desde los ajustes del teléfono.

En **Ajustes > Multimedia** se pueden configurar el precio por galón y la capacidad del tanque,
usados para calcular costo y autonomía. La autonomía también requiere el PID de nivel de combustible.

## Compatibilidad

- Requiere Torque Pro y un adaptador OBD2 conectado.
- Requiere un PID de flujo compatible; acepta L/h, L/min, mL/min, cc/min o gal/h.
- Los valores no disponibles se muestran como `--`.
- La fuente multimedia no reproduce música.
- Es una variante no oficial que debe instalarse manualmente.
- El código continúa bajo GNU GPL v3, igual que el proyecto de origen.
