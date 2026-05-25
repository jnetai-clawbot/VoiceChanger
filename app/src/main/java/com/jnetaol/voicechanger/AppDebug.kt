package com.jnetaol.voicechanger

import android.util.Log
import java.io.StringWriter
import java.io.PrintWriter

/**
 * Debug logging for VoiceChanger — errors are stored and surfaced
 * so users can share them for troubleshooting without needing ADB.
 */
object AppDebug {
    private val errorLog = mutableListOf<String>()
    private const val MAX_ERRORS = 50
    private const val TAG = "VoiceChanger"

    fun log(tag: String, message: String) {
        Log.d(tag, message)
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        pw.println("[ERROR] $message")
        if (throwable != null) {
            throwable.printStackTrace(pw)
        }
        pw.flush()
        synchronized(errorLog) {
            errorLog.add(sw.toString())
            while (errorLog.size > MAX_ERRORS) errorLog.removeAt(0)
        }
    }

    fun getErrorLog(): List<String> = synchronized(errorLog) { errorLog.toList() }

    fun clearLog() { synchronized(errorLog) { errorLog.clear() } }

    fun hasErrors(): Boolean = synchronized(errorLog) { errorLog.isNotEmpty() }
}
