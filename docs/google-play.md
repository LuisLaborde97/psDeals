# GoLab Deals en Google Play: ficha y formularios

Textos y respuestas para Play Console. Archivo a subir: el `.aab` de la variante `play`
(`./gradlew :app:bundlePlayRelease` → `android/app/build/outputs/bundle/playRelease/app-play-release.aab`).

## 1. Datos de la app

| Campo | Valor |
|---|---|
| Nombre | GoLab Deals |
| Paquete | `co.golab.deals` |
| Idioma predeterminado | Español (Latinoamérica) – es-419 |
| Tipo | App |
| Gratis o pagada | Gratis |
| Categoría | Compras |
| Correo de contacto | Kmilolaborde18@gmail.com |
| Política de privacidad | URL pública de `PRIVACY_POLICY.md` |

## 2. Ficha de la tienda

**Título** (máx. 30 caracteres; sin marcas de terceros):

```
GoLab Deals: ofertas de juegos
```

**Descripción corta** (máx. 80 caracteres):

```
Las mejores ofertas de videojuegos en un solo lugar, con alertas de precio.
```

**Descripción completa** (máx. 4000 caracteres):

```
GoLab Deals reúne cada día las ofertas de videojuegos de PlayStation Store, Steam, Epic Games Store y Microsoft Store (Xbox), con los precios de tu país, para que no tengas que revisar cada tienda por separado.

🎮 TODAS TUS TIENDAS EN UN SOLO LUGAR
Miles de juegos con descuento, actualizados todos los días. Filtra por tienda, por género o busca un juego por su nombre.

🔔 ALERTAS QUE SÍ TE INTERESAN
Elige en qué tiendas juegas y desde qué descuento quieres que te avisemos: -30 %, -50 %, -70 % o solo juegos gratis. Si solo juegas en Xbox, solo te llegan ofertas de Xbox.

❤️ LISTA DE DESEOS
Guarda los juegos que te interesan y te avisamos cuando bajen de precio o vuelvan a estar en oferta. Inicia sesión con Google y tu lista te acompaña si cambias de teléfono.

⚡ ENCUENTRA RÁPIDO
• Gratis: todos los juegos a $0 en un toque.
• Termina pronto: las ofertas que se acaban en las próximas 24 horas.
• Detalle de cada oferta con portada, géneros, precio y fecha de fin.

🌎 PRECIOS DE TU PAÍS
Disponible para Colombia y México, con la moneda que cobra cada tienda.

GoLab Deals es gratis, sin anuncios y sin comisiones: te lleva directo a la tienda oficial para comprar.

GoLab Deals es un proyecto independiente y no está afiliado a Sony Interactive Entertainment, Valve, Epic Games ni Microsoft. Todas las marcas pertenecen a sus respectivos dueños. Los precios pueden variar; el precio final es el de la tienda oficial.
```

**Recursos gráficos:**

| Recurso | Tamaño | Estado |
|---|---|---|
| Ícono | 512 × 512 PNG | Listo: `docs/play-assets/icon-512.png` |
| Imagen destacada | 1024 × 500 PNG/JPG | Listo: `docs/play-assets/feature-graphic-1024x500.png` |
| Capturas de teléfono | Entre 2 y 8, 9:16 | Pendiente (con el teléfono conectado) |

## 3. Contenido de la app

### Acceso a la app

Todas las funciones se pueden usar sin iniciar sesión. El inicio de sesión con Google es opcional (lista de deseos
sincronizada). Respuesta: **Toda la funcionalidad está disponible sin acceso especial**.

### Anuncios

**No**, la app no contiene anuncios.

### Eliminación de cuenta

- La app permite crear cuenta: **Sí** (inicio de sesión con Google).
- Enlace web para pedir la eliminación: URL pública de `PRIVACY_POLICY.md` (sección 6, "Tus derechos y cómo
  eliminar tus datos").
- Desde la app: Cuenta → **Eliminar mi cuenta**.
- Datos que se pueden borrar sin borrar la cuenta: sí (lista de deseos e historial de búsqueda desde la app).

### Seguridad de los datos

Basado en lo que recopilan Firebase Authentication, Cloud Firestore, Cloud Messaging y Google Analytics para
Firebase (documentación oficial de Firebase y Google Analytics) y en lo que guarda la app.

**Preguntas generales:**

| Pregunta | Respuesta |
|---|---|
| ¿La app recopila o comparte datos de usuario? | Sí |
| ¿Todos los datos se cifran en tránsito? | Sí (TLS) |
| ¿Los usuarios pueden pedir que se borren sus datos? | Sí |

**Datos recopilados** (ninguno se *comparte* con terceros: Google actúa como proveedor de servicios):

| Categoría Play | Tipo | ¿Obligatorio? | Finalidad |
|---|---|---|---|
| Información personal | Nombre | Opcional (solo con Google) | Funcionalidad de la app, administración de la cuenta |
| Información personal | Dirección de correo electrónico | Opcional (solo con Google) | Funcionalidad de la app, administración de la cuenta |
| Ubicación | Ubicación aproximada | Obligatorio | Estadísticas (Analytics la deriva de la IP enmascarada) |
| Actividad en la app | Interacciones con la app | Obligatorio | Estadísticas |
| Actividad en la app | Historial de búsqueda en la app | Obligatorio | Funcionalidad de la app |
| Actividad en la app | Otras acciones (lista de deseos) | Obligatorio | Funcionalidad de la app |
| Identificadores del dispositivo u otros | Identificadores del dispositivo | Obligatorio | Funcionalidad de la app (notificaciones), estadísticas |

Notas:

- *Identificadores*: el ID de publicidad de Android y el ID de instancia de Analytics, el token de notificaciones de
  FCM y el identificador anónimo de Firebase Authentication.
- *Datos que no se recopilan*: ubicación precisa, contactos, fotos o archivos, datos financieros, salud, mensajes.
- El país elegido y las tiendas de las alertas se guardan en el teléfono; las suscripciones a temas de FCM los
  envían a Google solo para entregar las notificaciones.

### Clasificación de contenido

- Categoría del cuestionario: **Referencia, noticias o educación** (o la más cercana: catálogo de productos).
- La app no permite que los usuarios se comuniquen entre sí, ni compartir ubicación, ni compras dentro de la app.
- Contenido mostrado: portadas e imágenes promocionales de videojuegos publicadas por las tiendas oficiales. Algunos
  juegos están clasificados para mayores (violencia).

> **Contenido explícito:** el backend marca con `explicit: true` los juegos con contenido sexual explícito (Steam:
> descriptores 3 y 4; Epic vía IsThereAnyDeal: `mature`) y la variante `play` no los muestra ni en la lista ni en la
> lista de deseos; tampoco se envían en notificaciones. La variante `github` los sigue mostrando. Antes de enviar a
> revisión, asegurarse de que el scraper haya corrido al menos una vez con este cambio (los documentos anteriores no
> tienen el campo).

### Público objetivo

**18 años o más.** La app muestra juegos clasificados para mayores, y no está dirigida a menores (evita las
obligaciones de la política de Familias).

### Otras declaraciones

| Declaración | Respuesta |
|---|---|
| App de noticias | No |
| App de gobierno | No |
| Funciones financieras | No |
| Salud | No |
| ID de publicidad | Sí, lo usa Google Analytics para Firebase (estadísticas); no hay anuncios |

## 4. Pruebas antes de producción

Cuentas personales nuevas: **prueba cerrada con al menos 12 testers durante 14 días seguidos** antes de pedir acceso a
producción.

1. **Prueba interna**: subir el `.aab`, probar en el teléfono propio.
2. **Prueba cerrada**: crear una lista de correos (o un Grupo de Google) con 12 o más testers; compartirles el enlace de
   participación; cada uno acepta, instala desde Play y la deja instalada los 14 días.
3. Tras los 14 días: Play Console → **Solicitar acceso a producción** (preguntas sobre la prueba).

Avisar a los testers que tengan la versión de GitHub: **deben desinstalarla primero** (Play vuelve a firmar la app con
su propia clave y Android no instala encima de una firma distinta). Con la sesión de Google recuperan su lista de
deseos.

**Después de la primera subida:** copiar la huella SHA-1 de *Play App Signing* (Play Console → Configuración → Integridad
de la app) en Firebase (app `co.golab.deals`), o el inicio de sesión con Google fallará para quien instale desde Play.

## 5. Avisos de actualización en Play

La variante `play` lee `config/update_play` (no `config/update`, que es el de GitHub) y su botón abre la ficha de Play
Store. Crear ese documento solo cuando haya una versión nueva publicada en Play, con los mismos campos que
`config/update` y `downloadUrl` = `https://play.google.com/store/apps/details?id=co.golab.deals`.

**Notas de la versión para la prueba cerrada** (máx. 500 caracteres):

```
Primera versión de prueba de GoLab Deals en Google Play: ofertas de PlayStation, Steam, Xbox y Epic Games para Colombia y México, alertas por tienda y por descuento, lista de deseos con alertas de precio y filtros Gratis y Termina pronto. ¡Gracias por probarla! Cuéntanos cualquier error o idea.
```
