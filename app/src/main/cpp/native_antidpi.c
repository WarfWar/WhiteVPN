#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <dlfcn.h>
#include <sys/socket.h>
#include <android/log.h>

extern int main(int argc, char **argv);
extern int server_fd;

static pthread_mutex_t proxy_lock = PTHREAD_MUTEX_INITIALIZER;
static int proxy_used = 0;
static int proxy_running = 0;

int __wrap_daemon(int nochdir, int noclose) {
    (void)nochdir; (void)noclose;
    errno = EPERM;
    return -1;
}

JNIEXPORT jint JNICALL
Java_com_whitevpn_app_antidpi_AntiDpiNative_proxyStart(
        JNIEnv *env, jobject self, jobjectArray args) {
    (void)self;
    pthread_mutex_lock(&proxy_lock);
    if (proxy_used) {
        pthread_mutex_unlock(&proxy_lock);
        return -1; /* byedpi has process-wide native state: restart process */
    }
    proxy_used = 1;
    pthread_mutex_unlock(&proxy_lock);

    const jsize count = (*env)->GetArrayLength(env, args);
    if (count < 1 || count > 64) return -1;
    char **argv = calloc((size_t) count + 1, sizeof(char *));
    if (!argv) return -1;

    for (int i = 0; i < count; ++i) {
        jstring value = (jstring) (*env)->GetObjectArrayElement(env, args, i);
        if (!value) goto invalid;
        const char *utf = (*env)->GetStringUTFChars(env, value, 0);
        if (utf) {
            argv[i] = strdup(utf);
            (*env)->ReleaseStringUTFChars(env, value, utf);
        }
        (*env)->DeleteLocalRef(env, value);
        if (!argv[i]) goto invalid;
    }

    pthread_mutex_lock(&proxy_lock);
    server_fd = -1;
    proxy_running = 1;
    pthread_mutex_unlock(&proxy_lock);
    int result = main(count, argv);
    pthread_mutex_lock(&proxy_lock);
    proxy_running = 0;
    pthread_mutex_unlock(&proxy_lock);
    for (int i = 0; i < count; ++i) free(argv[i]);
    free(argv);
    return result;

invalid:
    for (int i = 0; i < count; ++i) free(argv[i]);
    free(argv);
    return -1;
}

JNIEXPORT jint JNICALL
Java_com_whitevpn_app_antidpi_AntiDpiNative_proxyStop(
        JNIEnv *env, jobject self) {
    (void)env; (void)self;
    pthread_mutex_lock(&proxy_lock);
    int result = proxy_running && server_fd >= 0
        ? shutdown(server_fd, SHUT_RDWR) : -1;
    pthread_mutex_unlock(&proxy_lock);
    return result;
}

static pthread_once_t hev_once = PTHREAD_ONCE_INIT;
static int (*hev_main)(const unsigned char *, unsigned int, int) = NULL;
static void (*hev_quit)(void) = NULL;

static void resolve_hev(void) {
    void *library = dlopen("libhev-socks5-tunnel.so", RTLD_NOW | RTLD_LOCAL);
    if (!library) {
        __android_log_print(ANDROID_LOG_ERROR, "WhiteVPN-DPI",
                            "HEV library missing: %s", dlerror());
        return;
    }
    hev_main = (int (*)(const unsigned char *, unsigned int, int))
        dlsym(library, "hev_socks5_tunnel_main_from_str");
    hev_quit = (void (*)(void))
        dlsym(library, "hev_socks5_tunnel_quit");
}

JNIEXPORT jint JNICALL
Java_com_whitevpn_app_antidpi_AntiDpiNative_tunnelStart(
        JNIEnv *env, jobject self, jstring config, jint fd) {
    (void)self;
    pthread_once(&hev_once, resolve_hev);
    if (!hev_main || !hev_quit || !config) return -1;
    const char *utf = (*env)->GetStringUTFChars(env, config, NULL);
    if (!utf) return -1;
    unsigned int length = (unsigned int)(*env)->GetStringUTFLength(env, config);
    int ret = hev_main((const unsigned char *)utf, length, fd);
    (*env)->ReleaseStringUTFChars(env, config, utf);
    return ret;
}

JNIEXPORT void JNICALL
Java_com_whitevpn_app_antidpi_AntiDpiNative_tunnelStop(
        JNIEnv *env, jobject self) {
    (void)env; (void)self;
    pthread_once(&hev_once, resolve_hev);
    if (hev_quit) hev_quit();
}
