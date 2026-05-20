#include <jni.h>
#include <sys/stat.h>
#include <errno.h>
#include <android/log.h>

#define TAG "MtimeJNI"

/*
 * Set file mtime via futimens(). This is a standard POSIX syscall — NOT an
 * Android hidden API — so it works on any Android version without reflection
 * or hidden-API exemptions.
 *
 * fd must be opened with write permission.
 * timeMillis is epoch milliseconds (e.g. from EXIF DateTimeOriginal).
 *
 * Returns JNI_TRUE on success, JNI_FALSE on failure.
 */
JNIEXPORT jboolean JNICALL
Java_com_garag_nikoncopy_copy_NativeMtime_nSetMtimeByFd(
        JNIEnv *env, jclass clazz, jint fd, jlong timeMillis) {
    struct timespec times[2];
    /* atime: UTIME_OMIT = leave unchanged */
    times[0].tv_sec  = 0;
    times[0].tv_nsec = UTIME_OMIT;
    /* mtime: set to requested value */
    times[1].tv_sec  = (time_t)(timeMillis / 1000);
    times[1].tv_nsec = (long)((timeMillis % 1000) * 1000000L);

    if (futimens(fd, times) == 0) {
        /* Verify by stat */
        struct stat st;
        if (fstat(fd, &st) == 0) {
            if (st.st_mtime == times[1].tv_sec) {
                return JNI_TRUE;
            }
            __android_log_print(ANDROID_LOG_WARN, TAG,
                "futimens OK but mtime mismatch: target=%ld actual=%ld",
                (long)times[1].tv_sec, (long)st.st_mtime);
            return JNI_FALSE;
        }
        /* futimens succeeded, fstat failed — assume success */
        return JNI_TRUE;
    }
    __android_log_print(ANDROID_LOG_WARN, TAG,
        "futimens failed: fd=%d errno=%d", fd, errno);
    return JNI_FALSE;
}
