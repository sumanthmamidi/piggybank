package app.piggybank.backup

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore

/** Where `.pbk` files live: the fixed `Documents/PiggyBank` folder, or a folder the user picked. */
interface BackupFolder {
    data class Entry(val name: String, val uri: Uri)

    val label: String
    fun list(): List<Entry>
    fun write(name: String, bytes: ByteArray): Uri
    fun read(entry: Entry): ByteArray
    fun delete(entry: Entry): Boolean

    companion object {
        const val RELATIVE = "Documents/PiggyBank"
        private const val PREFS = "backup"
        private const val TREE = "tree_uri"

        /**
         * After a reinstall, Android won't let the app touch files the old install wrote, so once the
         * user has picked the folder (restore), that grant is used; otherwise the app writes through MediaStore.
         */
        fun current(context: Context): BackupFolder? {
            val tree = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TREE, null)?.let(Uri::parse)
            if (tree != null && context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }) {
                return TreeFolder(context, tree)
            }
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStoreFolder(context) else null
        }

        fun remember(context: Context, tree: Uri) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(TREE, tree.toString()).apply()
        }

        /** Opens the system folder picker at `Documents/PiggyBank` (J2). */
        fun initialUri(): Uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$RELATIVE")
    }
}

/** Files this install created in `Documents/PiggyBank` (no storage permission needed on Android 10+). */
class MediaStoreFolder(private val context: Context) : BackupFolder {
    override val label = BackupFolder.RELATIVE
    private val collection: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val resolver get() = context.contentResolver

    override fun list(): List<BackupFolder.Entry> {
        val out = mutableListOf<BackupFolder.Entry>()
        resolver.query(
            collection, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf("${BackupFolder.RELATIVE}/"), null,
        )?.use { c ->
            while (c.moveToNext()) out += BackupFolder.Entry(c.getString(1), Uri.withAppendedPath(collection, c.getLong(0).toString()))
        }
        return out
    }

    override fun write(name: String, bytes: ByteArray): Uri {
        val existing = list().firstOrNull { it.name == name }
        val uri = existing?.uri ?: resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/PiggyBank")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: error("Couldn't create $name in ${BackupFolder.RELATIVE}")
        resolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
        if (existing == null) resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    override fun read(entry: BackupFolder.Entry) = resolver.openInputStream(entry.uri)!!.use { it.readBytes() }
    override fun delete(entry: BackupFolder.Entry) = resolver.delete(entry.uri, null, null) > 0
}

/** A folder the user granted through the system picker. */
class TreeFolder(private val context: Context, private val tree: Uri) : BackupFolder {
    private val resolver get() = context.contentResolver
    private val dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    override val label: String = DocumentsContract.getTreeDocumentId(tree).substringAfter(':').ifEmpty { "the folder you picked" }

    override fun list(): List<BackupFolder.Entry> {
        val out = mutableListOf<BackupFolder.Entry>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(dir))
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) out += BackupFolder.Entry(c.getString(1), DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)))
        }
        return out
    }

    override fun write(name: String, bytes: ByteArray): Uri {
        val uri = list().firstOrNull { it.name == name }?.uri
            ?: DocumentsContract.createDocument(resolver, dir, "application/octet-stream", name)
            ?: error("Couldn't create $name")
        resolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
        return uri
    }

    override fun read(entry: BackupFolder.Entry) = resolver.openInputStream(entry.uri)!!.use { it.readBytes() }
    override fun delete(entry: BackupFolder.Entry) = runCatching { DocumentsContract.deleteDocument(resolver, entry.uri) }.getOrDefault(false)
}
