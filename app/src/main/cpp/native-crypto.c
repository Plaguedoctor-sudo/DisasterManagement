#include <jni.h>
#include <sys/mman.h>
#include <string.h>
#include <stdlib.h>

JNIEXPORT jlong JNICALL
Java_network_disaster_management_crypto_NativeMemoryLock_allocateLockedMemory(JNIEnv *env, jobject thiz, jint size) {
    if (size <= 0) return 0;
    void *ptr = mmap(NULL, (size_t)size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (ptr == MAP_FAILED) return 0;

    // Pin memory in RAM to prevent swapping/paging
    mlock(ptr, (size_t)size);

    // Prevent dump in coredumps or proc inspection
    #ifdef MADV_DONTDUMP
    madvise(ptr, (size_t)size, MADV_DONTDUMP);
    #endif

    return (jlong)ptr;
}

JNIEXPORT void JNICALL
Java_network_disaster_management_crypto_NativeMemoryLock_wipeAndFree(JNIEnv *env, jobject thiz, jlong addr, jint size) {
    if (addr == 0 || size <= 0) return;
    void *ptr = (void *)addr;
    volatile unsigned char *vptr = (volatile unsigned char *)ptr;
    for (size_t i = 0; i < (size_t)size; i++) {
        vptr[i] = 0x00;
    }
    munlock(ptr, (size_t)size);
    munmap(ptr, (size_t)size);
}

JNIEXPORT void JNICALL
Java_network_disaster_management_crypto_NativeMemoryLock_writeLockedBytes(JNIEnv *env, jobject thiz, jlong addr, jbyteArray data, jint len) {
    if (addr == 0 || len <= 0 || data == NULL) return;
    jsize arrayLen = (*env)->GetArrayLength(env, data);
    if (len > arrayLen) len = arrayLen;
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes) return;
    memcpy((void *)addr, bytes, (size_t)len);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
}

JNIEXPORT jbyteArray JNICALL
Java_network_disaster_management_crypto_NativeMemoryLock_readLockedBytes(JNIEnv *env, jobject thiz, jlong addr, jint len) {
    if (addr == 0 || len <= 0) return NULL;
    jbyteArray array = (*env)->NewByteArray(env, len);
    if (!array) return NULL;
    (*env)->SetByteArrayRegion(env, array, 0, len, (const jbyte *)addr);
    return array;
}

JNIEXPORT jlong JNICALL
Java_network_disaster_management_crypto_NativeMemoryLock_nativeAllocateLockedMemory(JNIEnv *env, jobject thiz, jint size) {
    return Java_network_disaster_management_crypto_NativeMemoryLock_allocateLockedMemory(env, thiz, size);
}

JNIEXPORT void JNICALL
Java_network_disaster_management_crypto_NativeMemoryLock_nativeWipeAndFree(JNIEnv *env, jobject thiz, jlong addr, jint size) {
    Java_network_disaster_management_crypto_NativeMemoryLock_wipeAndFree(env, thiz, addr, size);
}

JNIEXPORT void JNICALL
Java_network_disaster_management_crypto_NativeMemoryLock_nativeWriteLockedBytes(JNIEnv *env, jobject thiz, jlong addr, jbyteArray data, jint len) {
    Java_network_disaster_management_crypto_NativeMemoryLock_writeLockedBytes(env, thiz, addr, data, len);
}

JNIEXPORT jbyteArray JNICALL
Java_network_disaster_management_crypto_NativeMemoryLock_nativeReadLockedBytes(JNIEnv *env, jobject thiz, jlong addr, jint len) {
    return Java_network_disaster_management_crypto_NativeMemoryLock_readLockedBytes(env, thiz, addr, len);
}

