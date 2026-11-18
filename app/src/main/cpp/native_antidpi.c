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

/*
 * Optional TUN relay. HEV receives one datagram-oriented Unix socket end,
 * while this worker forwards complete IP packets to/from Android's TUN fd.
 * In YouTube TCP-first mode outgoing QUIC UDP/443 packets are dropped, so
 * clients that implement HTTP/3 fallback can retry on TCP. Telegram calls
 * also use UDP, which is why this behavior must remain explicitly optional.
 */
#include <poll.h>
#include <unistd.h>
#include <stdint.h>

static volatile int tunnel_bridge_stop = 0;

JNIEXPORT jintArray JNICALL
Java_com_whitevpn_app_antidpi_AntiDpiNative_openPacketBridge(
        JNIEnv *env, jobject self) {
    (void)self;
    int sockets[2] = {-1, -1};
    if (socketpair(AF_UNIX, SOCK_DGRAM, 0, sockets) != 0) return NULL;
    jintArray result = (*env)->NewIntArray(env, 2);
    if (!result) {
        close(sockets[0]); close(sockets[1]);
        return NULL;
    }
    jint values[2] = {sockets[0], sockets[1]};
    (*env)->SetIntArrayRegion(env, result, 0, 2, values);
    tunnel_bridge_stop = 0;
    return result;
}

static int is_quic_udp443(const uint8_t *ip, ssize_t n) {
    if (n < 24 || (ip[0] >> 4) != 4 || ip[9] != 17) return 0;
    const unsigned int ihl = (ip[0] & 15) * 4;
    if (ihl < 20 || n < (ssize_t)(ihl + 4)) return 0;
    if ((ip[6] & 0x1f) || ip[7]) return 0; /* later IP fragment */
    return (((unsigned int)ip[ihl + 2] << 8) | ip[ihl + 3]) == 443;
}

JNIEXPORT jint JNICALL
Java_com_whitevpn_app_antidpi_AntiDpiNative_runPacketBridge(
        JNIEnv *env, jobject self, jint tun_fd, jint socket_fd, jboolean block_quic) {
    (void)env; (void)self;
    uint8_t buffer[16384];
    struct pollfd waits[2] = {
        { tun_fd, POLLIN, 0 }, { socket_fd, POLLIN, 0 }
    };
    int result = 0;
    while (!tunnel_bridge_stop) {
        int rc = poll(waits, 2, 250);
        if (rc < 0) {
            if (errno == EINTR) continue;
            result = -1; break;
        }
        if (rc == 0) continue;
        if ((waits[0].revents | waits[1].revents) & (POLLERR | POLLHUP | POLLNVAL)) {
            if (!tunnel_bridge_stop) result = -1;
            break;
        }
        for (int i = 0; i < 2; ++i) {
            if (!(waits[i].revents & POLLIN)) continue;
            ssize_t n = read(waits[i].fd, buffer, sizeof(buffer));
            if (n <= 0) { result = -1; goto done; }
            if (i == 0 && block_quic && is_quic_udp443(buffer, n)) continue;
            /* Never block the relay on a full datagram socket. */
            if (i == 0) {
                ssize_t written = send(socket_fd, buffer, (size_t)n,
                                       MSG_DONTWAIT | MSG_NOSIGNAL);
                if (written < 0 && errno != EAGAIN && errno != EWOULDBLOCK) {
                    result = -1; goto done;
                }
            } else {
                ssize_t written = write(tun_fd, buffer, (size_t)n);
                if (written < 0) { result = -1; goto done; }
            }
        }
    }
done:
    close(socket_fd);
    return result;
}

JNIEXPORT void JNICALL
Java_com_whitevpn_app_antidpi_AntiDpiNative_stopPacketBridge(
        JNIEnv *env, jobject self) {
    (void)env; (void)self;
    tunnel_bridge_stop = 1;
}

JNIEXPORT void JNICALL
Java_com_whitevpn_app_antidpi_AntiDpiNative_closePacketFd(
        JNIEnv *env, jobject self, jint fd) {
    (void)env; (void)self;
    if (fd >= 0) close(fd);
}
