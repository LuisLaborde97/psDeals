package co.golab.deals.model

/** Documento `config/update` de Firestore: la última versión publicada de la app. */
data class AppUpdate(
    /** Se compara con BuildConfig.VERSION_CODE; el APK publicado debe tener exactamente este versionCode. */
    val latestVersionCode: Int,
    /** Enlace directo al APK (https). Lo abre el navegador, que lo descarga. */
    val downloadUrl: String,
    val releaseNotes: String,
    /** true: el diálogo no se puede cerrar y la app no se usa sin actualizar. */
    val isMandatory: Boolean,
)
