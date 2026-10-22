package com.prieto.capturalarga.session

import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * The captures of the session in progress, as files in one folder. All state
 * is read from the folder, so another instance pointed at the same folder
 * (another screen, or the process after being recreated) sees the same session.
 */
class CaptureSession(private val dir: File, private val maxCaptures: Int = MAX_CAPTURES) {

    /** Begins a new session, discarding whatever an earlier one left behind. */
    fun start() {
        clear()
        dir.mkdirs()
    }

    /** The stored captures, oldest first. */
    fun files(): List<File> =
        (dir.listFiles { file -> FRAME_NAME.matches(file.name) } ?: emptyArray()).sortedBy { it.name }

    val count: Int get() = files().size

    val isFull: Boolean get() = count >= maxCaptures

    /**
     * Stores one capture. [write] fills the file it is given; the capture only
     * joins the session if [write] returns normally.
     */
    fun add(write: (File) -> Unit): File {
        check(!isFull) { "the session already has $maxCaptures captures" }
        dir.mkdirs()
        val index = (files().lastOrNull()?.let(::indexOf) ?: -1) + 1
        val target = File(dir, String.format(Locale.ROOT, "frame_%04d.raw", index))
        val temp = File(dir, target.name + ".tmp")
        try {
            write(temp)
            if (!temp.renameTo(target)) throw IOException("could not store capture $index")
        } finally {
            temp.delete()
        }
        return target
    }

    /** Removes the newest capture; false when there is none. */
    fun undo(): Boolean = files().lastOrNull()?.delete() ?: false

    /** Removes one capture of this session, wherever it is in the order; false when it is not one. */
    fun remove(file: File): Boolean =
        file.parentFile == dir && FRAME_NAME.matches(file.name) && file.delete()

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun indexOf(file: File): Int = FRAME_NAME.matchEntire(file.name)!!.groupValues[1].toInt()

    companion object {
        const val MAX_CAPTURES = 20
        const val DIR_NAME = "session"
        private val FRAME_NAME = Regex("""frame_(\d{4})\.raw""")
    }
}
