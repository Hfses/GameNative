/*
 * GPU clock pin without root: turns KGSL power control off on /dev/kgsl-3d0 so the Adreno GPU
 * stays at its top clock (adrenotools' "turbo"). The kernel's thermal limits still apply.
 * Same approach as DroidDeck / Bannerlator (GPL-3.0). The property belongs to the device and
 * outlives this fd, so Java clears it when the game stops and again at the next app start.
 */
#include <jni.h>
#include <fcntl.h>
#include <stdint.h>
#include <stddef.h>
#include <sys/ioctl.h>
#include <unistd.h>

#define KGSL_IOC_TYPE 0x09
#define KGSL_PROP_PWRCTRL 0xE

struct kgsl_device_getproperty {
    unsigned int type;
    void *value;
    size_t sizebytes;
};

#define IOCTL_KGSL_SETPROPERTY _IOW(KGSL_IOC_TYPE, 0x32, struct kgsl_device_getproperty)

JNIEXPORT jboolean JNICALL
Java_app_gamenative_utils_GpuClockPin_nativeSetGpuTurbo(JNIEnv *env, jclass clazz, jboolean on) {
    (void) env; (void) clazz;
    uint32_t enable = on ? 0U : 1U; /* power control off == turbo on */
    struct kgsl_device_getproperty prop = {
        .type = KGSL_PROP_PWRCTRL,
        .value = &enable,
        .sizebytes = sizeof(enable),
    };
    int fd = open("/dev/kgsl-3d0", O_RDWR);
    if (fd < 0) return JNI_FALSE;
    int rc = ioctl(fd, IOCTL_KGSL_SETPROPERTY, &prop);
    close(fd);
    return rc == 0 ? JNI_TRUE : JNI_FALSE;
}
