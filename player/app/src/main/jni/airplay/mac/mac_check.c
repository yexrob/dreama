/*
 * depth3d: the AirPlay receiver's native half, run on a Mac.
 *
 * Builds the same sources the APK carries (UxPlay's lib, libplist,
 * android/dnssd_android.c and android/airplay_server.c) against Homebrew's
 * OpenSSL, starts the server on localhost and talks to it the way an iPad
 * opens a session:
 *
 *   1. both service records: names, every TXT entry, the wire form
 *   2. GET /info (the Bluetooth LE form with the TXT records inside, and
 *      the full form): device id, features, name, public key, display
 *   3. POST /pair-setup: the public key, which must match the TXT pk
 *   4. POST /pair-verify, both steps, as a client: X25519 agreement, the
 *      server's Ed25519 signature checked, ours accepted
 *   5. POST /fp-setup, first message: FairPlay (playfair) answers
 *   6. a second client: nohold hands the server over (video_reset NOHOLD)
 *   7. airplay_server_reset, and /info again on the port it returns
 *
 * What it cannot do is the part only an Apple device can: FairPlay's
 * second message, SETUP with real stream keys, and a mirrored picture.
 *
 * Run: mac/build.sh (prints PASS or the first FAIL).
 *
 * GPLv3, as the rest of depth3d.
 */
#include <arpa/inet.h>
#include <netinet/in.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#include <plist/plist.h>

#include "airplay_server.h"
#include "crypto.h"
#include "dnssd_android.h"
#include "logger.h"
#include "raop.h"

static int failures;
static int conn_inits, conn_destroys, nohold_resets;
static bool verbose;

#define CHECK(cond, ...)                                  \
    do {                                                  \
        if (cond) {                                       \
            printf("  ok   ");                            \
        } else {                                          \
            printf("  FAIL ");                            \
            failures++;                                   \
        }                                                 \
        printf(__VA_ARGS__);                              \
        printf("\n");                                     \
    } while (0)

/* ---- callbacks: count what the checks look at, ignore the rest ---- */

static void cb_log(void *cls, int level, const char *msg) {
    (void) cls;
    if (verbose || level <= LOGGER_ERR) {
        fprintf(stderr, "    [uxplay %d] %s\n", level, msg);
    }
}
static void cb_audio_process(void *cls, raop_ntp_t *ntp, audio_decode_struct *data) {}
static void cb_video_process(void *cls, raop_ntp_t *ntp, video_decode_struct *data) {}
static void cb_void(void *cls) {}
static void cb_conn_init(void *cls) { __sync_fetch_and_add(&conn_inits, 1); }
static void cb_conn_destroy(void *cls) { __sync_fetch_and_add(&conn_destroys, 1); }
static void cb_conn_reset(void *cls, int reason) {}
static void cb_video_reset(void *cls, reset_type_t type) {
    if (type == RESET_TYPE_NOHOLD) __sync_fetch_and_add(&nohold_resets, 1);
}
static double cb_client_volume(void *cls) { return 0.0; }
static void cb_volume(void *cls, float v) {}
static void cb_buffer(void *cls, const void *b, int l) {}
static void cb_remote(void *cls, const char *a, const char *b) {}
static void cb_progress(void *cls, uint32_t *a, uint32_t *b, uint32_t *c) {}
static void cb_format(void *cls, unsigned char *ct, unsigned short *spf, bool *s, bool *m, uint64_t *f) {}
static void cb_size(void *cls, float *a, float *b, float *c, float *d) {}
static void cb_running(void *cls, bool r) {}
static void cb_request(void *cls, char *id, char *model, char *name, bool *admit) { *admit = true; }
static void cb_pin(void *cls, char *pin) {}
static void cb_register(void *cls, const char *a, const char *b, const char *c) {}
static bool cb_check(void *cls, const char *pk) { return true; }
static const char *cb_passwd(void *cls, int *len) { *len = 0; return NULL; }
static int cb_codec(void *cls, video_codec_t c) { return 0; }
static void cb_play(void *cls, const char *l, const float p) {}
static void cb_float(void *cls, const float p) {}
static void cb_info(void *cls, playback_info_t *i) { i->duration = -1.0; i->position = -1.0; }
static float cb_remove(void *cls) { return 0.0f; }

static void fill(raop_callbacks_t *c) {
    memset(c, 0, sizeof(*c));
    c->audio_process = cb_audio_process;
    c->video_process = cb_video_process;
    c->video_pause = cb_void;
    c->video_resume = cb_void;
    c->conn_feedback = cb_void;
    c->conn_reset = cb_conn_reset;
    c->video_reset = cb_video_reset;
    c->conn_init = cb_conn_init;
    c->conn_destroy = cb_conn_destroy;
    c->audio_flush = cb_void;
    c->video_flush = cb_void;
    c->audio_set_client_volume = cb_client_volume;
    c->audio_set_volume = cb_volume;
    c->audio_set_metadata = cb_buffer;
    c->audio_set_coverart = cb_buffer;
    c->audio_stop_coverart_rendering = cb_void;
    c->audio_remote_control_id = cb_remote;
    c->audio_set_progress = cb_progress;
    c->audio_get_format = cb_format;
    c->video_report_size = cb_size;
    c->mirror_video_running = cb_running;
    c->report_client_request = cb_request;
    c->display_pin = cb_pin;
    c->register_client = cb_register;
    c->check_register = cb_check;
    c->passwd = cb_passwd;
    c->export_dacp = cb_remote;
    c->video_set_codec = cb_codec;
    c->on_video_play = cb_play;
    c->on_video_scrub = cb_float;
    c->on_video_rate = cb_float;
    c->on_video_stop = cb_void;
    c->on_video_acquire_playback_info = cb_info;
    c->on_video_playlist_remove = cb_remove;
}

/* ---- a minimal RTSP client ---- */

static int connect_to(unsigned short port) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    struct sockaddr_in sa;
    memset(&sa, 0, sizeof(sa));
    sa.sin_family = AF_INET;
    sa.sin_port = htons(port);
    sa.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (connect(fd, (struct sockaddr *) &sa, sizeof(sa))) {
        close(fd);
        return -1;
    }
    struct timeval tv = { 5, 0 };
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    return fd;
}

/* Sends one request and reads one response. Returns the status code, the
 * body in *body (malloc'd) and its length in *body_len; -1 when the
 * connection failed or closed. cseq < 0 leaves the CSeq header out. */
static int rtsp(int fd, const char *method, const char *url, int cseq, const char *content_type,
                const void *data, int data_len, unsigned char **body, int *body_len) {
    char head[512];
    int n = snprintf(head, sizeof(head), "%s %s RTSP/1.0\r\n", method, url);
    if (cseq >= 0) n += snprintf(head + n, sizeof(head) - n, "CSeq: %d\r\n", cseq);
    n += snprintf(head + n, sizeof(head) - n, "User-Agent: AirPlay/690.7.1\r\n");
    if (content_type) n += snprintf(head + n, sizeof(head) - n, "Content-Type: %s\r\n", content_type);
    if (data_len) n += snprintf(head + n, sizeof(head) - n, "Content-Length: %d\r\n", data_len);
    n += snprintf(head + n, sizeof(head) - n, "\r\n");
    if (send(fd, head, n, 0) != n) return -1;
    if (data_len && send(fd, data, data_len, 0) != data_len) return -1;

    char buf[65536];
    int got = 0;
    char *end = NULL;
    while (!end) {
        ssize_t r = recv(fd, buf + got, sizeof(buf) - 1 - got, 0);
        if (r <= 0) return -1;
        got += (int) r;
        buf[got] = '\0';
        end = strstr(buf, "\r\n\r\n");
    }
    int status = 0;
    if (sscanf(buf, "RTSP/1.0 %d", &status) != 1) return -1;
    int length = 0;
    const char *cl = strcasestr(buf, "Content-Length:");
    if (cl && cl < end) length = atoi(cl + strlen("Content-Length:"));
    int header_len = (int) (end - buf) + 4;
    unsigned char *out = malloc(length + 1);
    int have = got - header_len;
    if (have > length) have = length;
    memcpy(out, buf + header_len, have);
    while (have < length) {
        ssize_t r = recv(fd, out + have, length - have, 0);
        if (r <= 0) {
            free(out);
            return -1;
        }
        have += (int) r;
    }
    *body = out;
    *body_len = length;
    return status;
}

/* ---- helpers ---- */

static const char *txt_value(dnssd_t *d, int which, const char *key) {
    for (int i = 0; i < dnssd_android_txt_count(d, which); i++) {
        if (!strcmp(dnssd_android_txt_key(d, which, i), key)) return dnssd_android_txt_value(d, which, i);
    }
    return NULL;
}

/* The wire form parsed back: does it hold exactly the entries, in order? */
static bool wire_matches(dnssd_t *d, int which, const unsigned char *wire, int len) {
    int at = 0, i = 0;
    while (at < len) {
        int l = wire[at++];
        if (at + l > len || i >= dnssd_android_txt_count(d, which)) return false;
        char entry[300];
        snprintf(entry, sizeof(entry), "%s=%s", dnssd_android_txt_key(d, which, i),
                 dnssd_android_txt_value(d, which, i));
        if ((int) strlen(entry) != l || memcmp(entry, wire + at, l)) return false;
        at += l;
        i++;
    }
    return i == dnssd_android_txt_count(d, which);
}

static void hex(const unsigned char *b, int n, char *out) {
    for (int i = 0; i < n; i++) sprintf(out + 2 * i, "%02x", b[i]);
}

static void print_records(dnssd_t *d) {
    const char *types[2] = { "_raop._tcp", "_airplay._tcp" };
    for (int w = 0; w < 2; w++) {
        printf("  %s \"%s\" port %u\n", types[w], dnssd_android_service_name(d, w), dnssd_android_port(d, w));
        for (int i = 0; i < dnssd_android_txt_count(d, w); i++) {
            printf("      %s=%s\n", dnssd_android_txt_key(d, w, i), dnssd_android_txt_value(d, w, i));
        }
    }
}

static void derive(const unsigned char *secret, const char *salt, unsigned char out[16]) {
    unsigned char hash[64];
    sha_ctx_t *ctx = sha_init();
    sha_update(ctx, (const uint8_t *) salt, (int) strlen(salt));
    sha_update(ctx, secret, 32);
    sha_final(ctx, hash, NULL);
    sha_destroy(ctx);
    memcpy(out, hash, 16);
}

/* pair-setup and both pair-verify steps as a client on fd. With corrupt,
 * one bit of our signature is flipped. Returns 1 when the connection still
 * answers a request after step 2 (accepted), 0 when the server dropped it
 * (refused), -1 when an earlier step failed. UxPlay answers step 2 with 200
 * either way and only closes the connection on a bad signature, so the
 * follow-up request is what tells them apart. */
static int pair(int fd, const char *pk_hex, bool corrupt) {
    unsigned char *body = NULL;
    int len = 0;
    unsigned char setup_req[32];
    get_random_bytes(setup_req, 32);
    int status = rtsp(fd, "POST", "/pair-setup", 2, "application/octet-stream", setup_req, 32, &body, &len);
    char server_pk[65] = {0};
    if (status == 200 && len == 32) hex(body, 32, server_pk);
    CHECK(status == 200 && !strcmp(server_pk, pk_hex), "pair-setup -> %d, server key is the TXT pk", status);
    if (status != 200 || len != 32) {
        free(body);
        return -1;
    }
    unsigned char server_ed_raw[32];
    memcpy(server_ed_raw, body, 32);
    free(body);

    /* Step 1: our X25519 and Ed25519 public keys */
    int r = 0;
    int result = -1;
    x25519_key_t *ours = x25519_key_generate();
    ed25519_key_t *client_ed = ed25519_key_generate("depth3d mac check client", "", &r);
    unsigned char verify1[4 + 32 + 32] = { 1, 0, 0, 0 };
    x25519_key_get_raw(verify1 + 4, ours);
    ed25519_key_get_raw(verify1 + 36, client_ed);
    status = rtsp(fd, "POST", "/pair-verify", 3, "application/octet-stream", verify1, sizeof(verify1), &body, &len);
    CHECK(status == 200 && len == 96, "pair-verify 1 -> %d, %d bytes", status, len);
    if (status == 200 && len == 96) {
        x25519_key_t *theirs = x25519_key_from_raw(body);
        unsigned char secret[32];
        x25519_derive_secret(secret, ours, theirs);
        unsigned char key[16], iv[16];
        derive(secret, "Pair-Verify-AES-Key", key);
        derive(secret, "Pair-Verify-AES-IV", iv);
        aes_ctx_t *aes = aes_ctr_init(key, iv);
        unsigned char sig[64];
        aes_ctr_decrypt(aes, body + 32, sig, 64);
        /* The server signed its key then ours */
        unsigned char msg[64];
        memcpy(msg, body, 32);
        memcpy(msg + 32, verify1 + 4, 32);
        ed25519_key_t *server_ed = ed25519_key_from_raw(server_ed_raw);
        CHECK(ed25519_verify(sig, 64, msg, 64, server_ed), "server's pair-verify signature checks out");

        /* Step 2: ours over our key then theirs, on the same CTR stream */
        unsigned char msg2[64];
        memcpy(msg2, verify1 + 4, 32);
        memcpy(msg2 + 32, body, 32);
        unsigned char verify2[4 + 64] = { 0, 0, 0, 0 };
        ed25519_sign(verify2 + 4, 64, msg2, 64, client_ed);
        if (corrupt) {
            verify2[10] ^= 0x01;
        }
        aes_ctr_encrypt(aes, verify2 + 4, verify2 + 4, 64);
        aes_ctr_destroy(aes);
        free(body);
        body = NULL;
        status = rtsp(fd, "POST", "/pair-verify", 4, "application/octet-stream", verify2, sizeof(verify2), &body, &len);
        free(body);
        body = NULL;
        if (status == 200) {
            status = rtsp(fd, "OPTIONS", "*", 5, NULL, NULL, 0, &body, &len);
            result = status == 200 ? 1 : 0;
        } else {
            result = 0;
        }
        ed25519_key_destroy(server_ed);
        x25519_key_destroy(theirs);
    }
    free(body);
    x25519_key_destroy(ours);
    ed25519_key_destroy(client_ed);
    return result;
}

static void check_info(unsigned short port, const char *name, const char *device_id, const char *pk_hex) {
    int fd = connect_to(port);
    CHECK(fd >= 0, "connect to 127.0.0.1:%u", port);
    if (fd < 0) return;
    unsigned char *body = NULL;
    int len = 0;
    int status = rtsp(fd, "GET", "/info", 1, NULL, NULL, 0, &body, &len);
    CHECK(status == 200, "GET /info -> %d", status);
    if (status == 200) {
        plist_t root = NULL;
        plist_from_bin((const char *) body, len, &root);
        CHECK(root && plist_get_node_type(root) == PLIST_DICT, "/info is a binary plist dict (%d bytes)", len);
        if (root) {
            char *s = NULL;
            plist_get_string_val(plist_dict_get_item(root, "name"), &s);
            CHECK(s && !strcmp(s, name), "name = %s", s ? s : "(none)");
            free(s);
            s = NULL;
            plist_get_string_val(plist_dict_get_item(root, "deviceID"), &s);
            CHECK(s && !strcmp(s, device_id), "deviceID = %s", s ? s : "(none)");
            free(s);
            uint64_t features = 0;
            plist_get_uint_val(plist_dict_get_item(root, "features"), &features);
            CHECK(features & (1ull << 7), "features 0x%llx has screen mirroring (bit 7)", (unsigned long long) features);
            CHECK(!(features & (1ull << 27)), "legacy pairing (bit 27) off");
            char *pk = NULL;
            uint64_t pk_len = 0;
            plist_get_data_val(plist_dict_get_item(root, "pk"), &pk, &pk_len);
            char pk_seen[65] = {0};
            if (pk && pk_len == 32) hex((unsigned char *) pk, 32, pk_seen);
            CHECK(!strcmp(pk_seen, pk_hex), "pk matches the TXT pk");
            free(pk);
            plist_t displays = plist_dict_get_item(root, "displays");
            plist_t display = displays ? plist_array_get_item(displays, 0) : NULL;
            uint64_t w = 0, h = 0, fps = 0;
            if (display) {
                plist_get_uint_val(plist_dict_get_item(display, "width"), &w);
                plist_get_uint_val(plist_dict_get_item(display, "height"), &h);
                plist_get_uint_val(plist_dict_get_item(display, "maxFPS"), &fps);
            }
            CHECK(w == 1920 && h == 1080 && fps == 60, "display offered %llux%llu, maxFPS %llu",
                  (unsigned long long) w, (unsigned long long) h, (unsigned long long) fps);
            plist_free(root);
        }
    }
    free(body);
    close(fd);
}

int main(int argc, char **argv) {
    verbose = argc > 1 && !strcmp(argv[1], "-v");
    const char *keyfile = "/tmp/depth3d-airplay-mac-check.pem";
    unlink(keyfile);

    static const unsigned char hw[6] = { 0x02, 0x11, 0x22, 0x33, 0x44, 0x55 };
    const char *name = "depth3d mac check";
    raop_callbacks_t cbs;
    fill(&cbs);
    airplay_config_t config = {
        .name = name, .hw_addr = hw, .hw_addr_len = 6,
        .width = 1920, .height = 1080, .refresh_rate = 60, .max_fps = 60,
        .h265 = 1, .keyfile = keyfile, .port = 0, .log_level = verbose ? LOGGER_DEBUG : LOGGER_ERR,
        .log_callback = cb_log, .log_cls = NULL,
    };

    printf("1. service records\n");
    int port = airplay_server_start(&config, &cbs);
    CHECK(port > 0, "airplay_server_start -> port %d", port);
    if (port <= 0) return 1;
    dnssd_t *d = airplay_server_dnssd();
    print_records(d);
    CHECK(!strcmp(dnssd_android_service_name(d, DNSSD_ANDROID_RAOP), "021122334455@depth3d mac check"),
          "raop name is MAC@name");
    CHECK(!strcmp(dnssd_android_service_name(d, DNSSD_ANDROID_AIRPLAY), name), "airplay name is the name");
    const char *pk = txt_value(d, DNSSD_ANDROID_AIRPLAY, "pk");
    CHECK(pk && strlen(pk) == 64, "airplay pk is 64 hex digits");
    CHECK(pk && txt_value(d, DNSSD_ANDROID_RAOP, "pk") && !strcmp(pk, txt_value(d, DNSSD_ANDROID_RAOP, "pk")),
          "both records carry the same pk");
    const char *ft = txt_value(d, DNSSD_ANDROID_AIRPLAY, "features");
    CHECK(ft && !strcmp(ft, "0x527FFEE6,0x400"), "features = %s (UxPlay's default set, with h265)", ft ? ft : "-");
    CHECK(!strcmp(txt_value(d, DNSSD_ANDROID_AIRPLAY, "deviceid"), "02:11:22:33:44:55"), "deviceid");
    CHECK(!strcmp(txt_value(d, DNSSD_ANDROID_RAOP, "pw"), "false") &&
          !strcmp(txt_value(d, DNSSD_ANDROID_AIRPLAY, "pw"), "false"), "no password");
    int wl = 0;
    const char *wire = dnssd_get_raop_txt(d, &wl);
    CHECK(wire_matches(d, DNSSD_ANDROID_RAOP, (const unsigned char *) wire, wl), "raop wire form (%d bytes)", wl);
    wire = dnssd_get_airplay_txt(d, &wl);
    CHECK(wire_matches(d, DNSSD_ANDROID_AIRPLAY, (const unsigned char *) wire, wl), "airplay wire form (%d bytes)", wl);
    FILE *kf = fopen(keyfile, "r");
    CHECK(kf != NULL, "key kept in %s", keyfile);
    if (kf) fclose(kf);
    char pk_first[65];
    snprintf(pk_first, sizeof(pk_first), "%s", pk ? pk : "");

    printf("2. GET /info\n");
    int fd = connect_to(port);
    unsigned char *body = NULL;
    int len = 0;
    /* The Bluetooth LE form: no CSeq, the records asked for in the url */
    int status = rtsp(fd, "GET", "/info?txtAirPlay&txtRAOP", -1, NULL, NULL, 0, &body, &len);
    CHECK(status == 200, "GET /info?txtAirPlay&txtRAOP -> %d", status);
    if (status == 200) {
        plist_t root = NULL;
        plist_from_bin((const char *) body, len, &root);
        char *txt = NULL;
        uint64_t txt_len = 0;
        plist_get_data_val(root ? plist_dict_get_item(root, "txtAirPlay") : NULL, &txt, &txt_len);
        CHECK(txt && wire_matches(d, DNSSD_ANDROID_AIRPLAY, (unsigned char *) txt, (int) txt_len),
              "txtAirPlay in /info is the airplay record");
        free(txt);
        txt = NULL;
        plist_get_data_val(root ? plist_dict_get_item(root, "txtRAOP") : NULL, &txt, &txt_len);
        CHECK(txt && wire_matches(d, DNSSD_ANDROID_RAOP, (unsigned char *) txt, (int) txt_len),
              "txtRAOP in /info is the raop record");
        free(txt);
        plist_free(root);
    }
    free(body);
    close(fd);
    check_info((unsigned short) port, name, "02:11:22:33:44:55", pk_first);

    printf("3. pair-setup / 4. pair-verify / 5. fp-setup\n");
    fd = connect_to(port);
    int verified = pair(fd, pk_first, false);
    CHECK(verified == 1, "pair-verify 2 accepted: the connection still answers afterwards");

    unsigned char fp1[16] = { 'F', 'P', 'L', 'Y', 0x03, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x04, 0x02, 0x00, 0x00, 0xbb };
    status = rtsp(fd, "POST", "/fp-setup", 20, "application/octet-stream", fp1, 16, &body, &len);
    CHECK(status == 200 && len == 142 && !memcmp(body, "FPLY", 4), "fp-setup 1 -> %d, %d bytes", status, len);
    free(body);

    printf("6. a second client takes over (nohold), and a bad signature is refused\n");
    int fd2 = connect_to(port);
    verified = pair(fd2, pk_first, true);
    CHECK(nohold_resets == 1, "video_reset(NOHOLD) once (%d)", nohold_resets);
    CHECK(verified == 0, "pair-verify 2 with a corrupted signature: server dropped the connection");
    close(fd2);
    close(fd);

    printf("7. reset, then /info again\n");
    int port2 = airplay_server_reset();
    CHECK(port2 > 0, "airplay_server_reset -> port %d (was %d)", port2, port);
    if (port2 > 0) check_info((unsigned short) port2, name, "02:11:22:33:44:55", pk_first);
    CHECK(conn_inits > 0, "conn_init seen %d times, conn_destroy %d", conn_inits, conn_destroys);
    airplay_server_stop();
    CHECK(airplay_server_dnssd() == NULL, "stopped");

    printf("8. a restart keeps the key\n");
    fill(&cbs);
    port = airplay_server_start(&config, &cbs);
    d = airplay_server_dnssd();
    const char *pk_again = d ? txt_value(d, DNSSD_ANDROID_AIRPLAY, "pk") : NULL;
    CHECK(port > 0 && pk_again && !strcmp(pk_again, pk_first), "same pk after restart");
    airplay_server_stop();
    unlink(keyfile);

    printf(failures ? "FAIL (%d)\n" : "PASS\n", failures);
    return failures ? 1 : 0;
}
