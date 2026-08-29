package com.example.pwalibrary.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.pwalibrary.data.AppEntity
import com.example.pwalibrary.data.AppRepository
import com.example.pwalibrary.data.FolderGrant
import com.example.pwalibrary.data.InstallOutcome
import com.example.pwalibrary.shortcut.ShortcutHelper
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

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** Emitted when a zip has been staged and is ready to hand to a share sheet. */
    private val _shareUris = Channel<Uri>(Channel.BUFFERED)
    val shareUris = _shareUris.receiveAsFlow()

    fun install(uri: Uri, forceUuid: String? = null) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val message = when (val outcome = repository.install(uri, forceUuid)) {
                is InstallOutcome.Installed -> "${outcome.app.name} を追加しました"
                is InstallOutcome.Updated -> "${outcome.app.name} を更新しました"
                is InstallOutcome.Failed -> outcome.message
            }
            _busy.value = false
            _messages.send(message)
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
