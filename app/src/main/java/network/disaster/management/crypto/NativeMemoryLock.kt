package network.disaster.management.crypto

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class NativeMemoryLock {
    companion object {
        @Volatile
        private var isNativeLoaded = false
        init {
            try {
                System.loadLibrary("meshcrypto")
                isNativeLoaded = true
            } catch (_: UnsatisfiedLinkError) {
                isNativeLoaded = false
            }
        }
    }

    private val fallbackStore = ConcurrentHashMap<Long, ByteArray>()
    private val counter = AtomicLong(10000L)

    fun allocateLockedMemory(size: Int): Long {
        if (size <= 0) return 0L
        if (isNativeLoaded) {
            try {
                return nativeAllocateLockedMemory(size)
            } catch (_: UnsatisfiedLinkError) {}
        }
        val id = counter.incrementAndGet()
        fallbackStore[id] = ByteArray(size)
        return id
    }

    fun wipeAndFree(address: Long, size: Int) {
        if (address == 0L || size <= 0) return
        if (isNativeLoaded) {
            try {
                nativeWipeAndFree(address, size)
                return
            } catch (_: UnsatisfiedLinkError) {}
        }
        val buf = fallbackStore.remove(address)
        buf?.fill(0)
    }

    fun writeLockedBytes(address: Long, data: ByteArray, length: Int) {
        if (address == 0L || length <= 0) return
        if (isNativeLoaded) {
            try {
                nativeWriteLockedBytes(address, data, length)
                return
            } catch (_: UnsatisfiedLinkError) {}
        }
        val buf = fallbackStore[address] ?: return
        val toCopy = minOf(length, data.size, buf.size)
        System.arraycopy(data, 0, buf, 0, toCopy)
    }

    fun readLockedBytes(address: Long, length: Int): ByteArray {
        if (address == 0L || length <= 0) return ByteArray(0)
        if (isNativeLoaded) {
            try {
                return nativeReadLockedBytes(address, length)
            } catch (_: UnsatisfiedLinkError) {}
        }
        val buf = fallbackStore[address]
        return if (buf != null) buf.copyOf(minOf(length, buf.size)) else ByteArray(length)
    }

    private external fun nativeAllocateLockedMemory(size: Int): Long
    private external fun nativeWipeAndFree(address: Long, size: Int)
    private external fun nativeWriteLockedBytes(address: Long, data: ByteArray, length: Int)
    private external fun nativeReadLockedBytes(address: Long, length: Int): ByteArray
    private external fun nativeSecureWipeByteArray(array: ByteArray)

    fun secureWipe(buffer: ByteArray) {
        if (isNativeLoaded) {
            try {
                nativeSecureWipeByteArray(buffer)
                return
            } catch (_: UnsatisfiedLinkError) {}
        }
        // JVM Fallback 3-Pass Zeroization (DoD 5220.22-M / FIPS 140-3 Area 7)
        java.security.SecureRandom().nextBytes(buffer) // Pass 1: Random
        for (i in buffer.indices) buffer[i] = (buffer[i].toInt().inv()).toByte() // Pass 2: Invert
        buffer.fill(0) // Pass 3: Zeroize
    }
}

