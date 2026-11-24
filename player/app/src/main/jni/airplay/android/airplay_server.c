/*
 * depth3d: AirPlay mirroring receiver lifecycle, after uxplay.cpp (UxPlay
 * v1.73.7, GPLv3): start_dnssd, start_raop_server, register_dnssd and the
 * reset its main loop does. Video and audio go to the callbacks the caller
 * gives instead of GStreamer.
 *
 * GPLv3, as the rest of depth3d.
 */
#include <stdio.h>
#include <string.h>

#include "airplay_server.h"
#include "logger.h"

static raop_t *server_raop;
static dnssd_t *server_dnssd;
static unsigned short server_port;

static void set_features(dnssd_t *dnssd, int h265) {
    /* The feature set uxplay.cpp's start_dnssd writes over dnssdint.h's
     * defaults, with its defaults for the options: no HLS (bits 0 and 4),
     * no legacy pairing (bit 27), H.265 (bit 42) only when asked for */
    static const int on[] = { 1, 2, 5, 6, 7, 9, 10, 11, 12, 13, 14, 15, 16, 17,
                              18, 19, 20, 21, 22, 25, 28, 30 };
    static const int off[] = { 0, 3, 4, 8, 23, 24, 26, 27, 29, 31 };
    for (size_t i = 0; i < sizeof(on) / sizeof(on[0]); i++) {
        dnssd_set_airplay_features(dnssd, on[i], 1);
    }
    for (size_t i = 0; i < sizeof(off) / sizeof(off[0]); i++) {
        dnssd_set_airplay_features(dnssd, off[i], 0);
    }
    dnssd_set_airplay_features(dnssd, 42, h265 ? 1 : 0);
}

static int listen_on(unsigned short wanted) {
    unsigned short port = wanted;
    if (raop_start_httpd(server_raop, &port) > 0) {
        return port;
    }
    if (wanted == 0) {
        return -1;
    }
    /* Taken (or refused): any port does, the records carry it */
    port = 0;
    if (raop_start_httpd(server_raop, &port) > 0) {
        return port;
    }
    return -1;
}

int airplay_server_start(const airplay_config_t *config, raop_callbacks_t *callbacks) {
    if (server_raop) {
        return -1;
    }
    int error = 0;
    /* pin_pw 0: no PIN or password; depth3d has no in-VR confirmation yet */
    server_dnssd = dnssd_init(config->name, (int) strlen(config->name),
                              (const char *) config->hw_addr, config->hw_addr_len, &error, 0);
    if (error || !server_dnssd) {
        server_dnssd = NULL;
        return -2;
    }
    set_features(server_dnssd, config->h265);

    server_raop = raop_init(callbacks);
    if (!server_raop) {
        dnssd_destroy(server_dnssd);
        server_dnssd = NULL;
        return -3;
    }
    if (config->log_callback) {
        raop_set_log_callback(server_raop, config->log_callback, config->log_cls);
    }
    raop_set_log_level(server_raop, config->log_level);

    /* The device id string raop_init2 derives the pairing key from when no
     * key file is given, as uxplay passes its MAC */
    if (config->hw_addr_len != 6) {
        raop_destroy(server_raop);
        server_raop = NULL;
        dnssd_destroy(server_dnssd);
        server_dnssd = NULL;
        return -4;
    }
    char device_id[3 * 6] = {0};
    snprintf(device_id, sizeof(device_id), "%02x:%02x:%02x:%02x:%02x:%02x",
             config->hw_addr[0], config->hw_addr[1], config->hw_addr[2],
             config->hw_addr[3], config->hw_addr[4], config->hw_addr[5]);

    /* nohold 1: a new client takes over from one that is still connected,
     * so a stale connection never locks the iPad out */
    if (raop_init2(server_raop, 1, device_id, config->keyfile ? config->keyfile : "")) {
        raop_destroy(server_raop);
        server_raop = NULL;
        dnssd_destroy(server_dnssd);
        server_dnssd = NULL;
        return -4;
    }

    if (config->width) raop_set_plist(server_raop, "width", config->width);
    if (config->height) raop_set_plist(server_raop, "height", config->height);
    if (config->refresh_rate) raop_set_plist(server_raop, "refreshRate", config->refresh_rate);
    if (config->max_fps) raop_set_plist(server_raop, "maxFPS", config->max_fps);

    unsigned short tcp[2] = { 0, config->port };
    unsigned short udp[3] = { 0, 0, 0 };
    raop_set_tcp_ports(server_raop, tcp);
    raop_set_udp_ports(server_raop, udp);

    int port = listen_on(config->port);
    if (port <= 0) {
        raop_destroy(server_raop);
        server_raop = NULL;
        dnssd_destroy(server_dnssd);
        server_dnssd = NULL;
        return -5;
    }
    server_port = (unsigned short) port;
    raop_set_port(server_raop, server_port);
    /* Hands the public key to the records, so before registering */
    raop_set_dnssd(server_raop, server_dnssd);
    if (dnssd_register_raop(server_dnssd, server_port) ||
        dnssd_register_airplay(server_dnssd, server_port)) {
        airplay_server_stop();
        return -6;
    }
    return server_port;
}

int airplay_server_reset(void) {
    if (!server_raop) {
        return -1;
    }
    raop_stop_httpd(server_raop);
    raop_remove_known_connections(server_raop);
    int port = listen_on(server_port);
    if (port <= 0) {
        return -5;
    }
    if (port != server_port) {
        server_port = (unsigned short) port;
        raop_set_port(server_raop, server_port);
        dnssd_register_raop(server_dnssd, server_port);
        dnssd_register_airplay(server_dnssd, server_port);
    }
    return server_port;
}

void airplay_server_stop(void) {
    if (server_raop) {
        raop_destroy(server_raop);
        server_raop = NULL;
    }
    if (server_dnssd) {
        dnssd_destroy(server_dnssd);
        server_dnssd = NULL;
    }
    server_port = 0;
}

dnssd_t *airplay_server_dnssd(void) {
    return server_raop ? server_dnssd : NULL;
}
