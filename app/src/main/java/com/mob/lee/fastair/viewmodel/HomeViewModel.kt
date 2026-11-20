package com.mob.lee.fastair.viewmodel

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.IntentCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.mob.lee.fastair.R
import com.mob.lee.fastair.base.AppFragment
import com.mob.lee.fastair.model.DataWrap
import com.mob.lee.fastair.model.Record
import com.mob.lee.fastair.repository.DataBaseDataSource
import com.mob.lee.fastair.repository.StorageDataSource
import com.mob.lee.fastair.utils.dialog
import com.mob.lee.fastair.utils.errorToast
import com.mob.lee.fastair.utils.resolveShareUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext

class HomeViewModel : AppViewModel() {
    var isDes = true
    val recordLiveData = MutableLiveData<List<Record>>()
    val hasSelectedLiveData = MutableLiveData<Boolean>()
    var position: Int = -1
    val records = ArrayList<Record>()
    val selectedRecords = HashSet<Record>()

    val dataSource by lazy {
        StorageDataSource()
    }
    val database by lazy {
        DataBaseDataSource()
    }
    var currentChannel: Channel<Record>? = null

    fun parseClip() {

    }

    fun updateLocation(fragment: AppFragment, location: Int) {
        position = location
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            fragment.mParent?.dialog {
                setMessage(R.string.no_permission_to_scan)
                    .setPositiveButton(R.string.turn_on) { _, _ ->
                        withStoragePermissionROrHigher(
                            fragment,
                            Manifest.permission.MANAGE_EXTERNAL_STORAGE
                        ) {
                            if(!it) {
                                fragment.requireContext().errorToast(R.string.no_permission_to_scan)
                            }
                        }
                    }.setNegativeButton(R.string.exit) { _, _ ->
                        fragment.mParent?.finish()
                    }
            }
        } else if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
            fetch(fragment.context)
        } else {
            withPermission(
                fragment,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                action = { hasPermission ->
                    if (hasPermission) {
                        fetch(fragment.context)
                    } else {
                        fragment.requireContext().errorToast(R.string.no_permission_to_scan)
                        fragment.mParent?.dialog {
                            setMessage(R.string.no_permission_to_scan)
                                .setPositiveButton(R.string.turn_on) { _, _ ->
                                    openSetting(fragment)
                                }.setNegativeButton(R.string.exit) { _, _ ->
                                    fragment.mParent?.finish()
                                }
                        }
                    }
                })
        }
    }

    fun fetch(context: Context?) = async(recordLiveData) {
        currentChannel?.cancel()
        records.clear()

        val channel = dataSource.fetch(context, viewModelScope, position)
        currentChannel = channel
        // Collect the whole folder first, then publish once. Streaming one item at a
        // time onto the main thread turned scanning a large category into an O(n^2)
        // series of RecyclerView insertions and caused the UI to hang.
        for (r in channel) {
            records.add(r)
        }
        currentChannel = null
        next(records)
    }


    inline fun <D : Comparable<D>> sortBy(crossinline selector: (Record) -> D) =
        async(recordLiveData) {
            if (isDes) {
                records.sortByDescending(selector)
            } else {
                records.sortBy(selector)
            }
            next(records)
        }


    fun reverse() = async(recordLiveData) {
        isDes = !isDes
        records.reverse()
        next(records)
    }

    fun delete(context: Context?) = async(recordLiveData) {
        val checkedRecords = checkedRecords()
        for (r in checkedRecords) {
            val removed = dataSource.delete(context, r)
            if (removed) {
                records.remove(r)
            }
        }
        update()
    }

    fun update() = async(recordLiveData) {
        next(records)
    }

    fun selectAll() = async(recordLiveData) {
        val hasChecked = checkedRecords().isNotEmpty()
        hasSelectedLiveData.value = !hasChecked
        records.forEach {
            it.state = if (hasChecked) {
                selectedRecords.remove(it)
                Record.STATE_ORIGIN
            } else {
                selectedRecords.add(it)
                Record.STATE_CHECK
            }
        }
        next(records)
    }

    fun toggleState(record: Record) {
        record.state = if (Record.STATE_CHECK == record.state) {
            selectedRecords.remove(record)
            Record.STATE_ORIGIN
        } else {
            selectedRecords.add(record)
            Record.STATE_CHECK
        }
        hasSelectedLiveData.value = selectedRecords.isNotEmpty()
    }

    fun checkedRecords(): Set<Record> {
        return selectedRecords
    }

    fun sortByName() {
        sortBy { it.name }
    }

    fun sortBySize() {
        sortBy { it.size }
    }

    fun sortByDate() {
        sortBy { it.date }
    }

    fun write(context: Context?) = asyncWithWrap<String> {
        database.recordDao(context) {
            val list = this@HomeViewModel.checkedRecords()
            list.forEach {
                it.state = Record.STATE_WAIT
            }
            insert(list)
            selectedRecords.forEach { it.state = Record.STATE_ORIGIN }
            selectedRecords.clear()
            DataWrap.success("")
        }
    }

    fun parseSharedIntent(context: Context?, intent: Intent): LiveData<DataWrap<Int>> =
        asyncWithWrap {
            val records = collectSharedRecords(context, intent)
            database.recordDao(context) {
                insert(records)
                DataWrap.success(records.size)
            }
        }

    /**
     * Turn an incoming ACTION_SEND / ACTION_SEND_MULTIPLE intent into pending
     * transfer records. File managers attach files through EXTRA_STREAM while
     * some sources only set the ClipData, so both are read.
     */
    private suspend fun collectSharedRecords(context: Context?, intent: Intent): List<Record> =
        withContext(Dispatchers.IO) {
            if (null == context) return@withContext emptyList<Record>()
            shareUris(intent).mapNotNull { uri ->
                val file = context.resolveShareUri(uri) ?: return@mapNotNull null
                Record(
                    System.currentTimeMillis(),
                    file.length(),
                    file.lastModified(),
                    file.absolutePath,
                    Record.STATE_WAIT
                )
            }
        }

    private fun shareUris(intent: Intent): List<Uri> {
        val stream = when (intent.action) {
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        }
        if (!stream.isNullOrEmpty()) return stream
        return intent.clipData?.let { clip ->
            (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        } ?: emptyList()
    }
}