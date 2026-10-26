#include <jni.h>
#include <errno.h>
#include <linux/usbdevice_fs.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>

/* Uses only an already-authorized Java connection FD. Never opens, owns or closes it. */
static jintArray transfer_result(JNIEnv *env, int result, int saved_errno) {
    const jint values[2] = {(jint) result, (jint) saved_errno};
    jintArray out = (*env)->NewIntArray(env, 2);
    if (out != NULL) (*env)->SetIntArrayRegion(env, out, 0, 2, values);
    return out;
}

static int valid_endpoint(jint endpoint) {
    return endpoint >= 0 && endpoint <= 0xff &&
        (endpoint & 0x70) == 0 && (endpoint & 0x0f) != 0;
}

JNIEXPORT jintArray JNICALL
Java_dev_corn_flashing_usb_NativeUsbFs_bulk(
    JNIEnv *env, jobject self, jint fd, jint endpoint, jbyteArray buffer, jint timeout_ms) {
    (void) self;
    if (fd < 0) return transfer_result(env, -1, EBADF);
    if (!valid_endpoint(endpoint) || buffer == NULL || timeout_ms < 1 || timeout_ms > 3000)
        return transfer_result(env, -1, EINVAL);
    const jsize length = (*env)->GetArrayLength(env, buffer);
    if (length < 1 || length > 1024) return transfer_result(env, -1, EINVAL);
    jbyte *data = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (data == NULL) return NULL; /* Preserve the JVM's pending allocation exception. */

    struct usbdevfs_bulktransfer transfer;
    memset(&transfer, 0, sizeof(transfer));
    transfer.ep = (unsigned int) endpoint;
    transfer.len = (unsigned int) length;
    transfer.timeout = (unsigned int) timeout_ms;
    transfer.data = data;
    const int result = ioctl(fd, USBDEVFS_BULK, &transfer);
    const int saved_errno = result < 0 ? errno : 0; /* Capture before any JNI/string call. */

    (*env)->ReleaseByteArrayElements(env, buffer, data, (endpoint & 0x80) ? 0 : JNI_ABORT);
    return transfer_result(env, result, saved_errno);
}

JNIEXPORT jintArray JNICALL
Java_dev_corn_flashing_usb_NativeUsbFs_clearHalt(
    JNIEnv *env, jobject self, jint fd, jint endpoint) {
    (void) self;
    if (fd < 0) return transfer_result(env, -1, EBADF);
    if (!valid_endpoint(endpoint)) return transfer_result(env, -1, EINVAL);
    unsigned int ep = (unsigned int) endpoint;
    const int result = ioctl(fd, USBDEVFS_CLEAR_HALT, &ep);
    const int saved_errno = result < 0 ? errno : 0;
    return transfer_result(env, result, saved_errno);
}

JNIEXPORT jstring JNICALL
Java_dev_corn_flashing_usb_NativeUsbFs_errorText(JNIEnv *env, jobject self, jint error_number) {
    (void) self;
    char text[128];
    if (strerror_r(error_number, text, sizeof(text)) != 0)
        snprintf(text, sizeof(text), "errno %d", error_number);
    return (*env)->NewStringUTF(env, text);
}
