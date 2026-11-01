package com.eyecompanion

import java.util.concurrent.Executors

/** The offline language model (llama.cpp), plus a short rolling memory of the conversation. */
class Brain(private val modelPath: String, private val systemPrompt: () -> String) {

    fun interface TokenListener {
        /** Return false to stop generating. */
        fun onToken(text: String): Boolean
    }

    private external fun nativeLoad(path: String, nCtx: Int, nThreads: Int): Long
    private external fun nativeChat(
        handle: Long, roles: Array<String>, contents: Array<String>,
        maxTokens: Int, listener: TokenListener
    ): Boolean
    private external fun nativeFree(handle: Long)

    private val worker = Executors.newSingleThreadExecutor()
    private var handle = 0L
    private val history = ArrayList<Pair<String, String>>()   // role to text
    @Volatile private var stopRequested = false

    fun load(onDone: (Boolean) -> Unit) = worker.execute {
        handle = nativeLoad(modelPath, CONTEXT, THREADS)
        onDone(handle != 0L)
    }

    /** onToken returns false to stop this reply early. */
    fun ask(userText: String, onToken: (String) -> Boolean, onDone: (String) -> Unit) = worker.execute {
        stopRequested = false
        history.add("user" to userText)
        trimHistory()
        val roles = arrayOf("system") + history.map { it.first }
        val texts = arrayOf(systemPrompt()) + history.map { it.second }
        val reply = StringBuilder()
        val ok = handle != 0L && nativeChat(handle, roles, texts, MAX_REPLY_TOKENS) { piece ->
            reply.append(piece)
            onToken(piece) && !stopRequested
        }
        val text = reply.toString().trim()
        if (ok && text.isNotEmpty()) history.add("assistant" to text)
        else history.removeAt(history.lastIndex)
        onDone(text)
    }

    fun stop() { stopRequested = true }

    fun forget() = worker.execute { history.clear() }

    fun release() = worker.execute {
        if (handle != 0L) nativeFree(handle)
        handle = 0L
    }

    private fun trimHistory() {
        // keep the last few exchanges so prompts stay quick on a tablet CPU
        while (history.size > MAX_TURNS) history.removeAt(0)
        // the conversation must start with a user turn
        while (history.isNotEmpty() && history[0].first != "user") history.removeAt(0)
        // rough size cap (~4 chars per token)
        while (history.size > 1 && history.sumOf { it.second.length } > CONTEXT * 2) {
            history.removeAt(0)
            while (history.isNotEmpty() && history[0].first != "user") history.removeAt(0)
        }
    }

    companion object {
        const val CONTEXT = 2048
        const val THREADS = 4
        const val MAX_REPLY_TOKENS = 160
        const val MAX_TURNS = 10

        init { System.loadLibrary("companion") }
    }
}
