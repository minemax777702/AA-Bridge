package dev.caraalink.diagnostics

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class DiagnosticLoggerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `lines from concurrent writers all reach the exported file in per-writer order`() {
        val context = mock(Context::class.java)
        `when`(context.getExternalFilesDir(null)).thenReturn(tmp.root)
        DiagnosticLogger.clearAll(context)

        val writers = (0 until 4).map { t ->
            Thread { DiagnosticLogger(context, "t$t").let { log -> repeat(500) { log.log("line $it") } } }
        }
        writers.forEach { it.start() }
        writers.forEach { it.join() }

        val lines = DiagnosticLogger(context, "export").latestLogFile().readLines()
        for (t in 0 until 4) {
            val mine = lines.filter { "[t$t] " in it }.map { it.substringAfter("line ").toInt() }
            assertEquals((0 until 500).toList(), mine)
        }
    }

    @Test
    fun `rotation keeps current and most recent files`() {
        val files = (1..8).map { index ->
            tmp.newFile("log-$index.log").apply {
                writeText("line")
                setLastModified(index * 1000L)
            }
        }
        val current = tmp.newFile("current.log")
        val other = tmp.newFile("keep.txt")
        DiagnosticLogger.pruneLogs(tmp.root, current, 5)
        assertEquals(setOf("current.log", "log-5.log", "log-6.log", "log-7.log", "log-8.log"),
            tmp.root.listFiles()!!.filter { it.extension == "log" }.map { it.name }.toSet())
        assertEquals(true, other.exists())
    }
}
