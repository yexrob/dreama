/*
 * depth3d: one AirPlay mirroring receiver, set up the way uxplay.cpp's
 * start_dnssd / start_raop_server / register_dnssd do it, minus GStreamer.
 * Kept free of JNI so a plain macOS program can run the same code
 * (jni/airplay/mac/build.sh).
 *
 * GPLv3, as the rest of depth3d.
 */
#ifndef DEPTH3D_AIRPLAY_SERVER_H
#define DEPTH3D_AIRPLAY_SERVER_H

#include "raop.h"
#include "dnssd.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    const char *name;               /* what the iPad lists, e.g. "depth3d" */
    const unsigned char *hw_addr;   /* the 6-byte device id */
    int hw_addr_len;
    int width, height;              /* the display offered to the client */
    int refresh_rate, max_fps;
    int h265;                       /* advertise ScreenMultiCodec (bit 42) */
    const char *keyfile;            /* where the ed25519 key is kept, "" for one derived from hw_addr */
    unsigned short port;            /* tried first; 0 or taken falls back to any */
    int log_level;                  /* LOGGER_* */
    raop_log_callback_t log_callback;
    void *log_cls;
} airplay_config_t;

/* Starts the RAOP/AirPlay server and builds both service records. The
 * callbacks are copied; cls in them is passed back to every call. Returns
 * the TCP port it listens on, or a negative error. */
int airplay_server_start(const airplay_config_t *config, raop_callbacks_t *callbacks);

/* Drops every client connection and listens again, the way uxplay's main
 * loop does after a lost connection. Not to be called from a callback.
 * Returns the port, which can differ from before, or a negative error. */
int airplay_server_reset(void);

void airplay_server_stop(void);

/* The records airplay_server_start built, for announcing them; NULL when
 * not running */
dnssd_t *airplay_server_dnssd(void);

#ifdef __cplusplus
}
#endif
#endif
