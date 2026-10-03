package io.github.mokkori_tom.pwalibrary.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mokkori_tom.pwalibrary.data.AppEntity
import io.github.mokkori_tom.pwalibrary.data.AppRepository
import io.github.mokkori_tom.pwalibrary.data.FolderGrant
import io.github.mokkori_tom.pwalibrary.data.InstallOutcome
import io.github.mokkori_tom.pwalibrary.shortcut.ShortcutHelper
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AppRepository(application)

    val apps: StateFlow<List<AppEntity>> = repository.observeApps()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Non-null while an import is waiting to be told whether it is an update. */
    private val _pendingChoice = MutableStateFlow<InstallOutcome.NeedsChoice?>(null)
    val pendingChoice: StateFlow<InstallOutcome.NeedsChoice?> = _pendingChoice.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** Emitted when a zip has been staged and is ready to hand to a share sheet. */
    private val _shareUris = Channel<Uri>(Channel.BUFFERED)
    val shareUris = _shareUris.receiveAsFlow()

    fun install(uri: Uri, forceUuid: String? = null) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val outcome = repository.install(uri, forceUuid)
            _busy.value = false
            report(outcome)
        }
    }

    /** [asUpdate] false means the user chose to keep both apps. */
    fun resolveChoice(choice: InstallOutcome.NeedsChoice, asUpdate: Boolean) {
        _pendingChoice.value = null
        viewModelScope.launch {
            _busy.value = true
            val outcome = repository.resolveChoice(
                choice.token,
                if (asUpdate) choice.candidate.uuid else null
            )
            _busy.value = false
            report(outcome)
        }
    }

    fun cancelChoice(choice: InstallOutcome.NeedsChoice) {
        _pendingChoice.value = null
        repository.cancelChoice(choice.token)
    }

    private suspend fun report(outcome: InstallOutcome) {
        when (outcome) {
            is InstallOutcome.Installed -> _messages.send("${outcome.app.name} を追加しました")
            is InstallOutcome.Updated -> _messages.send("${outcome.app.name} を更新しました")
            is InstallOutcome.Failed -> _messages.send(outcome.message)
            // Not a result yet: the dialog is the next step.
            is InstallOutcome.NeedsChoice -> _pendingChoice.value = outcome
        }
    }

    fun rename(app: AppEntity, name: String) {
        viewModelScope.launch {
            val updated = repository.rename(app, name)
            if (updated != null) _messages.send("${updated.name} に変更しました")
        }
    }

    fun restoreName(app: AppEntity) {
        viewModelScope.launch {
            val updated = repository.restoreName(app)
            _messages.send("${updated.name} に戻しました")
        }
    }

    fun setIcon(app: AppEntity, source: Uri) {
        viewModelScope.launch {
            val updated = repository.setCustomIcon(app, source)
            _messages.send(
                if (updated != null) "アイコンを変更しました"
                else "この画像は読み込めませんでした"
            )
        }
    }

    fun clearIcon(app: AppEntity) {
        viewModelScope.launch {
            repository.clearCustomIcon(app)
            _messages.send("アイコンを元に戻しました")
        }
    }

    fun delete(app: AppEntity) {
        viewModelScope.launch {
            repository.delete(app)
            _messages.send(
                if (app.shortcutId != null) {
                    "${app.name} を削除しました（ホーム画面のアイコンは手動で削除してください）"
                } else {
                    "${app.name} を削除しました"
                }
            )
        }
    }

    fun grantsFor(appUuid: String) = repository.observeGrants(appUuid)

    fun revokeFolder(grant: FolderGrant) {
        viewModelScope.launch {
            repository.revokeGrant(grant)
            _messages.send("${grant.displayName} へのアクセスを取り消しました")
        }
    }

    fun export(app: AppEntity, dest: Uri) {
        viewModelScope.launch {
            val name = repository.exportTo(app, dest)
            _messages.send(if (name != null) "$name を保存しました" else "保存に失敗しました")
        }
    }

    fun share(app: AppEntity) {
        viewModelScope.launch {
            val uri = repository.shareUri(app)
            if (uri == null) _messages.send("共有用ファイルを作成できませんでした")
            else _shareUris.send(uri)
        }
    }

    fun addToHome(app: AppEntity) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            if (!ShortcutHelper.isPinSupported(context)) {
                _messages.send("このランチャーはホーム画面への追加に対応していません")
                return@launch
            }
            if (ShortcutHelper.requestPin(context, app)) {
                // The launcher owns the confirmation dialog and never reports the
                // result back, so this records that we asked, not that it stuck.
                repository.markPinned(app)
            } else {
                _messages.send("ホーム画面に追加できませんでした")
            }
        }
    }
}
