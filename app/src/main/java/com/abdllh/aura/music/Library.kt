package com.abdllh.aura.music

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import java.text.Collator
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val path: String
) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
    val folder: String get() = path.substringBeforeLast('/', "")
}

class Group(val key: String, val title: String, val subtitle: String, val tracks: List<Track>)

/**
 * The music on the unit and on USB drives / SD cards, from Android's media index (the system scans a drive when it
 * is plugged in). Reloads by itself when the index changes. Main-thread API; queries run on a worker.
 */
object Library {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var observing = false
    private var app: Context? = null
    private val collator: Collator = Collator.getInstance()

    var tracks: List<Track> = emptyList()
        private set
    var albums: List<Group> = emptyList()
        private set
    var artists: List<Group> = emptyList()
        private set
    var folders: List<Group> = emptyList()
        private set
    var loaded = false
        private set

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private val reload = Runnable { app?.let { load(it) } }

    fun load(ctx: Context) {
        val c = ctx.applicationContext
        app = c
        if (!hasPermission(c)) return
        if (!observing) {
            observing = true
            try {
                c.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, object : ContentObserver(main) {
                    override fun onChange(selfChange: Boolean) {
                        main.removeCallbacks(reload)
                        main.postDelayed(reload, 1500) // a scan reports many changes: reload once it settles
                    }
                })
            } catch (_: Throwable) {
            }
        }
        io.execute {
            val list = try { query(c) } catch (_: Throwable) { emptyList() }
            val byAlbum = list.groupBy { it.albumId }.map { (id, t) ->
                val first = t.first()
                Group("a$id", first.album.ifBlank { "—" }, first.artist, t.sortedBy { it.title.lowercase() })
            }.sortedWith { a, b -> collator.compare(a.title, b.title) }
            val byArtist = list.groupBy { it.artist.lowercase() }.map { (_, t) ->
                Group("r${t.first().artist}", t.first().artist.ifBlank { "—" }, "${t.size}", t.sortedWith { a, b -> collator.compare(a.title, b.title) })
            }.sortedWith { a, b -> collator.compare(a.title, b.title) }
            val byFolder = list.groupBy { it.folder }.map { (f, t) ->
                Group("f$f", f.substringAfterLast('/').ifBlank { f }, f, t.sortedWith { a, b -> collator.compare(a.path, b.path) })
            }.sortedWith { a, b -> collator.compare(a.title, b.title) }
            main.post {
                tracks = list
                albums = byAlbum
                artists = byArtist
                folders = byFolder
                loaded = true
                for (l in listeners) l()
            }
        }
    }

    private fun query(c: Context): List<Track> {
        val cols = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATA
        )
        val out = ArrayList<Track>()
        c.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 20000", null, null)?.use { cur ->
            while (cur.moveToNext()) {
                val path = cur.getString(6) ?: continue
                val unknown = "<unknown>"
                out.add(Track(
                    cur.getLong(0),
                    cur.getString(1)?.takeIf { it.isNotBlank() } ?: path.substringAfterLast('/').substringBeforeLast('.'),
                    cur.getString(2)?.takeIf { it.isNotBlank() && it != unknown } ?: "",
                    cur.getString(3)?.takeIf { it.isNotBlank() && it != unknown } ?: "",
                    cur.getLong(4),
                    cur.getLong(5),
                    path
                ))
            }
        }
        out.sortWith { a, b -> collator.compare(a.title, b.title) }
        return out
    }
}

/**
 * Album art, decoded off the main thread and cached (by album). Requests for an album already being decoded wait for
 * that one decode, and only the most recent requests are kept (a fling through a big USB library would otherwise
 * queue hundreds of decodes that nobody looks at any more).
 */
object ArtLoader {
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val none = HashSet<String>()
    private val pending = HashMap<String, MutableList<(Bitmap?) -> Unit>>() // main thread only
    private val main = Handler(Looper.getMainLooper())

    private class Decode(val key: String, body: Runnable) : Runnable by body

    private val pool = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue<Runnable>(24)) { r, e ->
        // queue full: the oldest request goes (its rows have long scrolled away); forgetting it lets them ask again
        if (!e.isShutdown) {
            (e.queue.poll() as? Decode)?.let { dropped -> main.post { pending.remove(dropped.key) } }
            e.execute(r)
        }
    }

    fun get(ctx: Context, t: Track, size: Int, done: (Bitmap?) -> Unit) {
        val key = "${t.albumId}@$size"
        cache.get(key)?.let { done(it); return }
        if (key in none) { done(null); return }
        pending[key]?.let { it.add(done); return }
        pending[key] = mutableListOf(done)
        val c = ctx.applicationContext
        try {
            pool.execute(Decode(key, Runnable {
                val b = load(c, t, size)
                main.post {
                    if (b != null) cache.put(key, b) else none.add(key)
                    pending.remove(key)?.forEach { it(b) }
                }
            }))
        } catch (_: Throwable) {
            pending.remove(key)
        }
    }

    private fun load(c: Context, t: Track, size: Int): Bitmap? {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) return c.contentResolver.loadThumbnail(t.uri, Size(size, size), null)
        } catch (_: Throwable) {
        }
        return try {
            val r = MediaMetadataRetriever()
            val bytes = try {
                r.setDataSource(t.path)
                r.embeddedPicture
            } finally {
                r.release()
            }
            if (bytes == null) null else {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                var s = 1
                while (o.outWidth / (s * 2) >= size && o.outHeight / (s * 2) >= size) s *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
            }
        } catch (_: Throwable) {
            null
        }
    }
}
