package com.garag.nikoncopy.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.provider.DocumentsContract
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.garag.nikoncopy.copy.CopyMode
import com.garag.nikoncopy.copy.CopyService
import com.garag.nikoncopy.copy.CopyState
import com.garag.nikoncopy.data.CacheRecord
import com.garag.nikoncopy.data.ManifestStore
import com.garag.nikoncopy.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CopyViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Context get() = getApplication()
    val settings = SettingsRepository(ctx)
    private val manifest = ManifestStore(ctx)

    val destinationUri: StateFlow<String?> = settings.destinationUri.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = null,
    )

    val sourceUri: StateFlow<String?> = settings.sourceUri.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = null,
    )

    val cacheRecord: StateFlow<CacheRecord> = settings.cacheRecord.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = CacheRecord(0, 0, 0),
    )

    private var service: CopyService? = null
    private val _state = MutableStateFlow<CopyState>(CopyState.Idle)
    val state: StateFlow<CopyState> = _state.asStateFlow()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as? CopyService.LocalBinder)?.service() ?: return
            service = s
            viewModelScope.launch {
                s.state.collect { _state.value = it }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    init {
        bindService()
    }

    private fun bindService() {
        val intent = Intent(ctx, CopyService::class.java)
        ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun setDestination(uri: Uri) {
        viewModelScope.launch { settings.setDestinationUri(uri.toString()) }
    }

    fun setSource(uri: Uri) {
        viewModelScope.launch { settings.setSourceUri(uri.toString()) }
    }

    fun clearSource() {
        viewModelScope.launch { settings.clearSourceUri() }
    }

    /** Clear cache record AND the success manifest. */
    fun clearCache() {
        viewModelScope.launch {
            settings.clearCache()
            manifest.clear()
        }
    }

    fun startCopyAll(sourceTree: Uri) = startCopy(sourceTree, CopyMode.ALL)
    fun startCopyIncremental(sourceTree: Uri) = startCopy(sourceTree, CopyMode.INCREMENTAL)

    /**
     * Probe whether [uri] is currently usable (camera plugged in, permission still
     * valid). Returns true iff we can list children of the tree's root.
     */
    suspend fun probeSource(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val rootId = DocumentsContract.getTreeDocumentId(uri)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, rootId)
            ctx.contentResolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null, null, null,
            )?.use { /* successful query is enough */ true } ?: false
        }.getOrElse {
            Log.w("CopyViewModel", "probeSource failed: ${it.message}")
            false
        }
    }

    private fun startCopy(sourceTree: Uri, mode: CopyMode) {
        val dest = destinationUri.value?.let(Uri::parse) ?: return
        val intent = Intent(ctx, CopyService::class.java)
        ctx.startForegroundService(intent)
        if (service == null) bindService()
        val s = service
        if (s != null) {
            s.start(sourceTree, dest, mode)
        } else {
            viewModelScope.launch {
                var i = 0
                while (service == null && i < 40) {
                    kotlinx.coroutines.delay(50)
                    i++
                }
                service?.start(sourceTree, dest, mode)
            }
        }
    }

    fun cancelCopy() {
        service?.cancel()
    }

    fun acknowledgeResult() {
        service?.acknowledge()
    }

    override fun onCleared() {
        try { ctx.unbindService(connection) } catch (_: Throwable) {}
        super.onCleared()
    }
}
