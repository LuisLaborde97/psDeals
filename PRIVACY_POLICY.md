# Política de privacidad de GoLab Deals

**Última actualización:** 23 de octubre de 2026

GoLab Deals ("la app") es una aplicación para Android que reúne ofertas de videojuegos publicadas por tiendas como PlayStation Store, Steam, Epic Games Store y Microsoft Store (Xbox). Esta política explica qué datos trata la app, para qué y con quién se comparten.

El responsable del tratamiento es **Luis Camilo Laborde Saavedra**, contacto: **[Kmilolaborde18@gmail.com](mailto:Kmilolaborde18@gmail.com)**.

## 1. Resumen

- Puedes usar la app sin crear una cuenta ni darnos tu nombre o correo.
- No vendemos tus datos ni mostramos publicidad.
- No pedimos tu ubicación, contactos, fotos, micrófono ni cámara.
- No procesamos pagos: las compras se hacen en la tienda de cada juego, bajo sus propias condiciones.
- Usamos servicios de Google Firebase para que la app funcione (cuenta, lista de deseos, notificaciones) y para medir de forma agregada qué ofertas interesan.

## 2. Datos que tratamos

### 2.1 Cuenta anónima (todos los usuarios)

Al abrir la app por primera vez se crea automáticamente una cuenta anónima en Firebase Authentication. Esta cuenta solo tiene un identificador aleatorio y no contiene tu nombre ni tu correo.

### 2.2 Inicio de sesión con Google (opcional)

Si eliges iniciar sesión con Google, Firebase Authentication recibe de tu cuenta de Google tu **nombre, dirección de correo electrónico y foto de perfil**. Los usamos únicamente para:

- vincular tu lista de deseos a tu cuenta de Google, de modo que puedas recuperarla en otro teléfono;
- mostrar tu nombre y foto en la sección de cuenta de la app.

Estos datos no se copian a nuestra base de datos de usuarios. Puedes cerrar sesión o eliminar tu cuenta en cualquier momento (ver sección 6).

### 2.3 Datos de uso guardados en tu perfil (Cloud Firestore)

Asociados a tu identificador de cuenta (anónima o de Google), guardamos:

| Dato | Para qué |
|---|---|
| Lista de deseos (identificadores de las ofertas guardadas) | Mostrarte tus juegos guardados y avisarte si bajan de precio o vuelven a estar en oferta |
| Historial de búsquedas recientes | Mostrarte tus búsquedas anteriores en el buscador; puedes borrar cada término desde la app |
| Token de notificaciones (Firebase Cloud Messaging) | Enviarte las notificaciones de ofertas y de tu lista de deseos |
| Fechas de creación y actualización del perfil | Mantenimiento técnico del perfil |

### 2.4 Estadísticas de uso (Google Analytics para Firebase)

Para entender qué ofertas interesan y mejorar la app, Google Analytics para Firebase registra:

- **Eventos de la app**: cuándo abres el detalle de una oferta (`oferta_abierta`) y cuándo pulsas el botón para ir a la tienda (`clic_ir_a_tienda`), junto con el **título del juego** y la **tienda**.
- **Eventos automáticos**: primera apertura, sesiones, tiempo de uso, pantallas vistas y actualizaciones de la app.
- **Datos del dispositivo**: modelo, versión de Android, idioma, versión de la app y país o región aproximados (derivados de la dirección IP, que no se almacena).
- **Identificadores**: un identificador de instancia de la app generado por Firebase y el **ID de publicidad de Android**.

Analizamos estos datos de forma agregada; no los usamos para identificarte personalmente ni para mostrarte anuncios. Puedes restablecer o eliminar el ID de publicidad desde *Ajustes de Android → Google → Anuncios* (la ruta puede variar según el fabricante).

### 2.5 Datos que no tratamos

No recopilamos ubicación precisa, contactos, fotos o archivos, datos financieros o de pago, datos de salud ni otras categorías de datos sensibles.

## 3. Para qué usamos los datos

- Prestar las funciones de la app: lista de deseos, historial de búsqueda y notificaciones.
- Enviar notificaciones sobre ofertas destacadas y sobre los juegos de tu lista de deseos. Puedes desactivarlas en los ajustes de notificaciones de Android.
- Medir el rendimiento y el uso de la app, y saber qué ofertas y tiendas interesan más, para mejorar el catálogo y la experiencia.
- Mantener la seguridad de la app y prevenir abusos.

## 4. Con quién compartimos los datos

No vendemos ni alquilamos tus datos. Los tratan por cuenta nuestra los siguientes servicios de **Google LLC**, que actúa como encargado del tratamiento:

- Firebase Authentication (cuentas)
- Cloud Firestore (perfil, lista de deseos e historial de búsqueda)
- Firebase Cloud Messaging (notificaciones)
- Google Analytics para Firebase (estadísticas de uso)

Estos servicios pueden almacenar y procesar datos en servidores ubicados fuera de tu país, incluidos los Estados Unidos. Más información: [Privacidad y seguridad en Firebase](https://firebase.google.com/support/privacy) y [Política de privacidad de Google](https://policies.google.com/privacy).

Al pulsar "Ir a la tienda" o compartir una oferta, sales de la app. A partir de ahí aplican las políticas de privacidad de la tienda o de la app que elijas.

También podríamos divulgar datos si la ley o una autoridad competente lo exige.

## 5. Conservación

- **Perfil de usuario** (lista de deseos, historial de búsqueda y tokens de notificación): se conserva mientras exista tu cuenta. Los tokens de teléfonos que ya no reciben notificaciones se eliminan automáticamente.
- **Estadísticas de Google Analytics**: se conservan según el período de retención configurado en Google Analytics (por defecto, 2 meses para los datos a nivel de usuario). Después se conservan solo informes agregados.

## 6. Tus derechos y cómo eliminar tus datos

Puedes conocer, actualizar, rectificar y suprimir tus datos, así como revocar tu autorización, de acuerdo con la Ley 1581 de 2012 de Colombia y con la normativa de protección de datos aplicable en tu país.

- **Desde la app**: puedes quitar juegos de tu lista de deseos, borrar búsquedas de tu historial y cerrar sesión.
- **Eliminar tu cuenta desde la app**: toca tu foto de perfil y elige **Eliminar cuenta**. Tras confirmar con tu cuenta de Google, se borran de inmediato tu perfil (lista de deseos, historial de búsqueda y tokens de notificación) y tu cuenta de Firebase Authentication (nombre, correo y foto).
- **Eliminar tu cuenta sin la app**: si ya no tienes la app instalada o usas una cuenta anónima, escríbenos a **[Kmilolaborde18@gmail.com](mailto:Kmilolaborde18@gmail.com)** desde el correo de tu cuenta de Google (o indicando que usabas una cuenta anónima, con el modelo de tu teléfono). Eliminaremos tu cuenta y tu perfil en un plazo máximo de 15 días hábiles.
- Las estadísticas de Google Analytics no están vinculadas a tu cuenta y se eliminan automáticamente al vencer su período de retención (sección 5).
- **Notificaciones**: puedes desactivarlas en cualquier momento desde los ajustes de Android.

Desinstalar la app elimina los datos guardados en el teléfono, pero no tu perfil en la nube. Para borrar el perfil, solicita la eliminación como se indica arriba.

## 7. Seguridad

Los datos viajan cifrados (HTTPS/TLS) entre la app y Firebase. El acceso al perfil de cada usuario está restringido por reglas de seguridad para que solo su titular pueda leerlo o modificarlo.

## 8. Menores de edad

La app no está dirigida a menores de 13 años, y no recopilamos a sabiendas datos de menores de esa edad. Si crees que un menor nos ha proporcionado datos, escríbenos y los eliminaremos.

## 9. Cambios en esta política

Podemos actualizar esta política. Publicaremos la nueva versión en esta misma página con su fecha de actualización y, si los cambios son importantes, te avisaremos dentro de la app.

## 10. Contacto

Si tienes preguntas sobre esta política o sobre tus datos, escríbenos a **[Kmilolaborde18@gmail.com](mailto:Kmilolaborde18@gmail.com)**.
