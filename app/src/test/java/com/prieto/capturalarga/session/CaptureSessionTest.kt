package com.prieto.capturalarga.session

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CaptureSessionTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun dir() = File(folder.root, "session")

    private fun CaptureSession.addText(text: String): File = add { it.writeText(text) }

    @Test
    fun capturesAreKeptInOrder() {
        val session = CaptureSession(dir())
        session.start()
        session.addText("a")
        session.addText("b")
        session.addText("c")
        assertEquals(listOf("a", "b", "c"), session.files().map { it.readText() })
        assertEquals(3, session.count)
    }

    @Test
    fun startDiscardsAnEarlierSession() {
        val session = CaptureSession(dir())
        session.start()
        session.addText("old")
        session.start()
        assertEquals(0, session.count)
    }

    @Test
    fun undoRemovesOnlyTheLast() {
        val session = CaptureSession(dir())
        session.start()
        session.addText("a")
        session.addText("b")
        assertTrue(session.undo())
        assertEquals(listOf("a"), session.files().map { it.readText() })
        session.addText("c")
        assertEquals(listOf("a", "c"), session.files().map { it.readText() })
    }

    @Test
    fun undoOnAnEmptySessionDoesNothing() {
        val session = CaptureSession(dir())
        session.start()
        assertFalse(session.undo())
    }

    @Test
    fun failedWriteLeavesNothingBehind() {
        val session = CaptureSession(dir())
        session.start()
        assertThrows(IOException::class.java) {
            session.add { it.writeText("half"); throw IOException("disk full") }
        }
        assertEquals(0, session.count)
        assertEquals(0, dir().listFiles()!!.size)
    }

    @Test
    fun fullSessionRejectsMore() {
        val session = CaptureSession(dir(), maxCaptures = 2)
        session.start()
        session.addText("a")
        assertFalse(session.isFull)
        session.addText("b")
        assertTrue(session.isFull)
        assertThrows(IllegalStateException::class.java) { session.addText("c") }
    }

    @Test
    fun filesSurviveANewInstance() {
        CaptureSession(dir()).apply { start(); addText("a"); addText("b") }
        val reopened = CaptureSession(dir())
        assertEquals(listOf("a", "b"), reopened.files().map { it.readText() })
    }

    @Test
    fun clearEmptiesTheFolder() {
        val session = CaptureSession(dir())
        session.start()
        session.addText("a")
        session.clear()
        assertEquals(0, session.count)
    }

    @Test
    fun anyCaptureCanBeRemoved() {
        val session = CaptureSession(dir())
        session.start()
        session.addText("a")
        val middle = session.addText("b")
        session.addText("c")
        assertTrue(session.remove(middle))
        assertEquals(listOf("a", "c"), session.files().map { it.readText() })
        assertFalse(session.remove(middle))
        session.addText("d")
        assertEquals(listOf("a", "c", "d"), session.files().map { it.readText() })
    }

    @Test
    fun onlyTheSessionsOwnCapturesCanBeRemoved() {
        val session = CaptureSession(dir())
        session.start()
        val outside = folder.newFile("frame_0000.raw")
        assertFalse(session.remove(outside))
        assertTrue(outside.exists())
    }

    @Test
    fun missingFolderIsAnEmptySession() {
        val session = CaptureSession(dir())
        assertEquals(0, session.count)
        assertFalse(session.undo())
    }
}
