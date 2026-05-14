package com.brahmadeo.supertonic.tts

import android.util.Log
import java.util.concurrent.atomic.AtomicReference

object SupertonicTTS {
    private var nativePtr: Long = 0
    private var currentModelPath: String? = null

    init {
        try {
            System.loadLibrary("onnxruntime")
            System.loadLibrary("supertonic_tts")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("SupertonicTTS", "Failed to load native library: ${e.message}")
        }
    }

    private external fun init(modelPath: String, libPath: String, ortThreads: Int, xnnThreads: Int): Long
    private external fun synthesize(ptr: Long, text: String, lang: String, stylePath: String, speed: Float, bufferSeconds: Float, steps: Int, gain: Float): ByteArray
    private external fun getSocClass(ptr: Long): Int
    private external fun getSampleRate(ptr: Long): Int
    private external fun close(ptr: Long)
    private external fun reset(ptr: Long)

    @Synchronized
    fun isInitialized(modelPath: String): Boolean {
        if (nativePtr == 0L || currentModelPath != modelPath) return false
        return getSocClass(nativePtr) != -1
    }

    @Synchronized
    fun initialize(modelPath: String, libPath: String, ortThreads: Int = 4, xnnThreads: Int = 1): Boolean {
        if (nativePtr != 0L) {
            // Health check: Can we still talk to the engine?
            if (getSocClass(nativePtr) != -1) {
                if (currentModelPath == modelPath) {
                    Log.i("SupertonicTTS", "Engine already initialized and healthy for this path: $modelPath")
                    return true
                } else {
                    Log.i("SupertonicTTS", "Model path changed ($currentModelPath -> $modelPath). Re-initializing...")
                    release()
                }
            } else {
                Log.w("SupertonicTTS", "Engine pointer exists but is unhealthy. Re-initializing...")
                release()
            }
        }
        
        nativePtr = init(modelPath, libPath, ortThreads, xnnThreads)
        val success = nativePtr != 0L
        if (success) {
            currentModelPath = modelPath
            Log.i("SupertonicTTS", "Engine initialized successfully (ORT: $ortThreads, XNN: $xnnThreads) with model: $modelPath")
        } else {
            currentModelPath = null
            Log.e("SupertonicTTS", "Engine initialization FAILED")
        }
        return success
    }

    private var listeners = java.util.concurrent.CopyOnWriteArrayList<ProgressListener>()
    
    // VULN-003 fix: Use an atomic SessionContext to ensure sid and listener are updated together
    private class SessionContext(val sid: Long, val listener: ProgressListener?)
    private val currentSession = AtomicReference<SessionContext?>(null)

    interface ProgressListener {
        fun onProgress(sessionId: Long, current: Int, total: Int)
        fun onAudioChunk(sessionId: Long, data: ByteArray)
    }

    fun addProgressListener(listener: ProgressListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeProgressListener(listener: ProgressListener) {
        listeners.remove(listener)
    }

    // Called from JNI
    fun notifyProgress(current: Int, total: Int) {
        // VULN-003 fix: Get session context atomically
        val ctx = synchronized(this) { currentSession.get() }
        val sid = ctx?.sid ?: 0L
        val listener = ctx?.listener

        // Priority to task-specific listener
        if (listener != null) {
            try {
                listener.onProgress(sid, current, total)
            } catch (e: Exception) {
                Log.w("SupertonicTTS", "Progress listener error", e)
            }
        } else {
            // Only notify global listeners if no specific task listener is set
            for (l in listeners) {
                try {
                    l.onProgress(sid, current, total)
                } catch (e: Exception) {
                    Log.w("SupertonicTTS", "Global progress listener error", e)
                }
            }
        }
    }

    // Called from JNI
    fun notifyAudioChunk(data: ByteArray) {
        // VULN-003 fix: Get session context atomically and validate audio data
        val ctx = synchronized(this) { currentSession.get() }
        val sid = ctx?.sid ?: 0L
        val listener = ctx?.listener

        // Validate audio data before processing
        if (data.isEmpty()) {
            Log.w("SupertonicTTS", "Received empty audio chunk")
            return
        }

        // STRICT ISOLATION: Audio chunks ONLY go to the requester
        if (listener != null) {
            try {
                listener.onAudioChunk(sid, data)
            } catch (e: Exception) {
                Log.e("SupertonicTTS", "Audio chunk listener error", e)
            }
        } else {
            // Only if no specific task listener is active (e.g. legacy app call)
            // we send to global listeners - but with error handling
            for (l in listeners) {
                try {
                    l.onAudioChunk(sid, data)
                } catch (e: Exception) {
                    Log.w("SupertonicTTS", "Global audio listener error", e)
                }
            }
        }
    }

    @Volatile
    private var isCancelled = false

    fun setCancelled(cancelled: Boolean) {
        isCancelled = cancelled
    }

    // Called from JNI
    fun isCancelled(): Boolean {
        return isCancelled
    }

    @Volatile
    private var sessionIdCounter: Long = 0

    @Synchronized
    fun generateAudio(text: String, lang: String, stylePath: String, speed: Float = 1.0f, bufferDuration: Float = 0.0f, steps: Int = 5, gain: Float = 1.0f, listener: ProgressListener? = null): ByteArray? {
        // VULN-001 fix: Add comprehensive null pointer checks
        if (nativePtr == 0L) {
            Log.e("SupertonicTTS", "Engine not initialized - cannot generate audio")
            return null
        }

        // Validate inputs before proceeding
        if (text.isEmpty() || lang.isEmpty() || stylePath.isEmpty()) {
            Log.w("SupertonicTTS", "Invalid input parameters - empty values detected")
            return null
        }

        val sid = ++sessionIdCounter

        // VULN-003 fix: Use synchronized block for complete atomic session context update
        synchronized(this) {
            currentSession.set(SessionContext(sid, listener))
        }

        try {
            val data = synthesize(nativePtr, text, lang, stylePath, speed, bufferDuration, steps, gain)
            return if (data.isNotEmpty()) data else null
        } catch (e: Exception) {
            Log.e("SupertonicTTS", "Native synthesis exception: ${e.message}", e)
            return null
        } finally {
            // VULN-003 fix: Atomic cleanup within synchronized context
            synchronized(this) {
                currentSession.set(null)
            }
        }
    }

    @Synchronized
    fun getSoC(): Int {
        if (nativePtr == 0L) return -1
        return getSocClass(nativePtr)
    }

    @Synchronized
    fun getAudioSampleRate(): Int {
        if (nativePtr == 0L) return 44100
        return getSampleRate(nativePtr)
    }

    @Synchronized
    fun release() {
        if (nativePtr != 0L) {
            Log.i("SupertonicTTS", "Releasing engine: $nativePtr")
            close(nativePtr)
            nativePtr = 0
        }
    }

    @Synchronized
    fun reset() {
        if (nativePtr != 0L) {
            reset(nativePtr)
        }
    }
}
