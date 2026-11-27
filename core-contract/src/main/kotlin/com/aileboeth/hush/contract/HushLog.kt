package com.aileboeth.hush.contract

import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DiagnosticLogEntry(
    val timestampMs: Long,
    val level: String,   // "I", "W", "E"
    val source: String,  // "app" or "daemon"
    val tag: String,
    val message: String,
) {
    fun formatLine(): String {
        val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timestampMs))
        return "$ts [$source/$level] $tag: $message"
    }
}

/**
 * Lightweight thread-safe ring buffer + optional rolling file logger shared by both the host app
 * process (`app` / `core-engine` / `adb-bootstrap`) and the `uid=2000` `daemon-shell` process.
 *
 * Automatically redacts any 32+ hex session tokens before recording.
 */
object HushLog {
    private const val MAX_MEMORY_ENTRIES = 360
    private const val MAX_FILE_BYTES = 256 * 1024L

    private val lock = Any()
    private val ring = ArrayDeque<DiagnosticLogEntry>(MAX_MEMORY_ENTRIES)
    private var defaultSource: String = "app"
    private var logFile: File? = null

    private val LONG_HEX_TOKEN_REGEX = Regex("""\b[0-9a-fA-F]{32,}\b""")

    fun configure(source: String, file: File? = null) {
        synchronized(lock) {
            defaultSource = source
            logFile = file
            if (file != null) {
                file.parentFile?.mkdirs()
                loadTailFromFileLocked(file)
            }
        }
    }

    fun i(tag: String, message: String) = append("I", tag, message)

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        val full = if (throwable != null) {
            "$message (${throwable.javaClass.simpleName}: ${throwable.message})"
        } else message
        append("W", tag, full)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val full = if (throwable != null) {
            val allLines = throwable.stackTraceToString().lineSequence().toList()
            val head = allLines.take(12)
            val causedBy = allLines.drop(12).filter { it.trimStart().startsWith("Caused by:") }.take(3)
            val detail = (head + causedBy).joinToString(" | ")
            "$message — $detail"
        } else message
        append("E", tag, full)
    }

    fun snapshot(): List<DiagnosticLogEntry> = synchronized(lock) { ring.toList() }

    fun clear() {
        synchronized(lock) {
            ring.clear()
            runCatching { logFile?.writeText("") }
        }
    }

    internal fun sanitize(raw: String): String =
        LONG_HEX_TOKEN_REGEX.replace(raw, "<redacted-token>")
            .replace('\r', ' ')
            .replace('\n', ' ')
            .trim()
            .take(1400)

    private fun append(level: String, tag: String, rawMessage: String) {
        val cleanMsg = sanitize(rawMessage)
        val entry = DiagnosticLogEntry(
            timestampMs = System.currentTimeMillis(),
            level = level,
            source = defaultSource,
            tag = tag.take(32),
            message = cleanMsg,
        )
        synchronized(lock) {
            if (ring.size >= MAX_MEMORY_ENTRIES) {
                ring.removeFirst()
            }
            ring.addLast(entry)
            writeToFileLocked(entry)
        }
    }

    private fun writeToFileLocked(entry: DiagnosticLogEntry) {
        val file = logFile ?: return
        runCatching {
            if (file.exists() && file.length() > MAX_FILE_BYTES) {
                val tail = file.readLines(Charsets.UTF_8).takeLast(MAX_MEMORY_ENTRIES / 2)
                file.writeText(tail.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
            }
            val encoded = "${entry.timestampMs}\t${entry.level}\t${entry.source}\t${entry.tag}\t${entry.message}\n"
            FileOutputStream(file, true).use { it.write(encoded.toByteArray(Charsets.UTF_8)) }
        }
    }

    private fun loadTailFromFileLocked(file: File) {
        if (!file.exists() || file.length() <= 0L) return
        runCatching {
            val lines = file.readLines(Charsets.UTF_8).takeLast(MAX_MEMORY_ENTRIES)
            ring.clear()
            for (line in lines) {
                val parts = line.split('\t', limit = 5)
                if (parts.size == 5) {
                    val ts = parts[0].toLongOrNull() ?: continue
                    ring.addLast(
                        DiagnosticLogEntry(
                            timestampMs = ts,
                            level = parts[1],
                            source = parts[2],
                            tag = parts[3],
                            message = parts[4],
                        ),
                    )
                }
            }
        }
    }
}
