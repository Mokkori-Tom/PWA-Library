package com.example.pwalibrary

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.core.content.IntentCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.pwalibrary.data.AppEntity
import com.example.pwalibrary.data.AppRepository
import com.example.pwalibrary.ui.AppDetailDialog
import com.example.pwalibrary.ui.DeleteConfirmDialog
import com.example.pwalibrary.ui.ImportChoiceDialog
import com.example.pwalibrary.ui.RenameDialog
import com.example.pwalibrary.ui.LibraryScreen
import com.example.pwalibrary.ui.LibraryViewModel
import com.example.pwalibrary.ui.PwaLibraryTheme
import com.example.pwalibrary.web.WebAppActivity

class MainActivity : ComponentActivity() {

    companion object {
        /**
         * Some pickers report a zip as octet-stream, so that type is included or
         * the file ends up greyed out and unselectable.
         */
        private val ZIP_MIME_TYPES = arrayOf(
            "application/zip",
            "application/x-zip-compressed",
            "multipart/x-zip",
            "application/octet-stream"
        )

        /** Anything BitmapFactory might read; the decode is what actually decides. */
        private val IMAGE_MIME_TYPES = arrayOf("image/*")
    }

    private var viewModelRef: LibraryViewModel? = null
    private var pendingUpdateUuid: String? = null
    private var pendingExport: AppEntity? = null
    private var pendingIconFor: AppEntity? = null

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val uuid = pendingUpdateUuid
            pendingUpdateUuid = null
            if (uri != null) viewModelRef?.install(uri, uuid)
        }

    private val iconLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val app = pendingIconFor
            pendingIconFor = null
            if (uri != null && app != null) viewModelRef?.setIcon(app, uri)
        }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            val app = pendingExport
            pendingExport = null
            if (uri != null && app != null) viewModelRef?.export(app, uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val vm: LibraryViewModel = viewModel()
            viewModelRef = vm

            val apps by vm.apps.collectAsState()
            val busy by vm.busy.collectAsState()
            val pendingChoice by vm.pendingChoice.collectAsState()
            val snackbarHostState = remember { SnackbarHostState() }

            // Held by uuid rather than by value: an edit replaces the row, and
            // a dialog holding the old copy would keep showing the old name and
            // offer to undo a change that has already been undone.
            var detailUuid by remember { mutableStateOf<String?>(null) }
            val detailsFor = detailUuid?.let { uuid -> apps.firstOrNull { it.uuid == uuid } }
            var renameTarget by remember { mutableStateOf<String?>(null) }
            var deleteTarget by remember { mutableStateOf<AppEntity?>(null) }

            LaunchedEffect(vm) {
                vm.messages.collect { snackbarHostState.showSnackbar(it) }
            }

            LaunchedEffect(vm) {
                vm.shareUris.collect { uri -> startShare(uri) }
            }

            LaunchedEffect(Unit) {
                consumeSharedZip()?.let { vm.install(it) }
            }

            PwaLibraryTheme {
                LibraryScreen(
                    apps = apps,
                    busy = busy,
                    snackbarHostState = snackbarHostState,
                    onImportClick = {
                        pendingUpdateUuid = null
                        importLauncher.launch(ZIP_MIME_TYPES)
                    },
                    onLaunch = { app ->
                        startActivity(WebAppActivity.intentFor(this, app.uuid))
                    },
                    onDetails = { detailUuid = it.uuid }
                )

                detailsFor?.let { app ->
                    val grantFlow = remember(app.uuid) { vm.grantsFor(app.uuid) }
                    val folders by grantFlow.collectAsState(initial = emptyList())

                    AppDetailDialog(
                        app = app,
                        folders = folders,
                        onRevokeFolder = { vm.revokeFolder(it) },
                        onDismiss = { detailUuid = null },
                        // Renaming swaps one dialog for another rather than
                        // stacking them. The instant edits below keep the sheet
                        // open instead, because their result shows up in it.
                        onRename = {
                            detailUuid = null
                            renameTarget = app.uuid
                        },
                        onRestoreName = { vm.restoreName(app) },
                        onChangeIcon = {
                            pendingIconFor = app
                            iconLauncher.launch(IMAGE_MIME_TYPES)
                        },
                        onClearIcon = { vm.clearIcon(app) },
                        onAddToHome = {
                            detailUuid = null
                            vm.addToHome(app)
                        },
                        onShare = {
                            detailUuid = null
                            vm.share(app)
                        },
                        onExport = {
                            detailUuid = null
                            pendingExport = app
                            exportLauncher.launch(AppRepository.exportFileName(app))
                        },
                        onUpdate = {
                            detailUuid = null
                            pendingUpdateUuid = app.uuid
                            importLauncher.launch(ZIP_MIME_TYPES)
                        },
                        onDelete = {
                            detailUuid = null
                            deleteTarget = app
                        }
                    )
                }

                renameTarget?.let { uuid ->
                    apps.firstOrNull { it.uuid == uuid }?.let { app ->
                        RenameDialog(
                            app = app,
                            onDismiss = { renameTarget = null },
                            onConfirm = { name ->
                                renameTarget = null
                                vm.rename(app, name)
                            }
                        )
                    }
                }

                pendingChoice?.let { choice ->
                    ImportChoiceDialog(
                        incomingName = choice.incomingName,
                        candidate = choice.candidate,
                        onUpdate = { vm.resolveChoice(choice, asUpdate = true) },
                        onAddNew = { vm.resolveChoice(choice, asUpdate = false) },
                        onDismiss = { vm.cancelChoice(choice) }
                    )
                }

                deleteTarget?.let { app ->
                    DeleteConfirmDialog(
                        app = app,
                        onDismiss = { deleteTarget = null },
                        onConfirm = {
                            deleteTarget = null
                            vm.delete(app)
                        }
                    )
                }
            }
        }
    }

    private fun startShare(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            // The chooser forwards this to whichever app the user picks; without
            // it the receiver gets a uri it is not allowed to read.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_chooser)))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeSharedZip()?.let { viewModelRef?.install(it) }
    }

    /**
     * Pulls the zip out of a VIEW or SEND intent and clears it, so a rotation or
     * a return to the task does not import the same file a second time.
     */
    private fun consumeSharedZip(): Uri? {
        val current = intent ?: return null
        val uri = when (current.action) {
            Intent.ACTION_VIEW -> current.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(
                current,
                Intent.EXTRA_STREAM,
                Uri::class.java
            )
            else -> null
        } ?: return null

        setIntent(Intent(this, MainActivity::class.java))
        return uri
    }
}
