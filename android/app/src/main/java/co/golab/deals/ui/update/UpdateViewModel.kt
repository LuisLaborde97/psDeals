package co.golab.deals.ui.update

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.golab.deals.BuildConfig
import co.golab.deals.data.AppConfigRepository
import co.golab.deals.model.AppUpdate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class UpdateViewModel(
    repository: AppConfigRepository,
    private val savedState: SavedStateHandle,
    private val installedVersionCode: Int = BuildConfig.VERSION_CODE,
) : ViewModel() {

    /**
     * Versión que el usuario pospuso con "Más tarde". En SavedStateHandle para que rotar no reabra el
     * diálogo; al cerrar la app se olvida, así el aviso vuelve en el siguiente arranque.
     */
    private val postponedVersion = savedState.getStateFlow<Int?>(KEY_POSTPONED, null)

    /** Actualización a mostrar ahora: más nueva que la instalada y no pospuesta (las obligatorias no se posponen). */
    val pendingUpdate: StateFlow<AppUpdate?> = combine(repository.observeUpdate(), postponedVersion) { update, postponed ->
        update?.takeIf { it.latestVersionCode > installedVersionCode && (it.isMandatory || it.latestVersionCode != postponed) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun postpone(update: AppUpdate) {
        if (!update.isMandatory) savedState[KEY_POSTPONED] = update.latestVersionCode
    }

    companion object {
        private const val KEY_POSTPONED = "postponed_version"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { UpdateViewModel(AppConfigRepository(), createSavedStateHandle()) }
        }
    }
}
