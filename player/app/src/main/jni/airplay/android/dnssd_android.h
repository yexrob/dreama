/*
 * depth3d: what the Android stand-in for UxPlay's dnssd.c offers beyond
 * dnssd.h. On Android the services are announced by the system's mDNS
 * daemon through NsdManager, so this side only builds the records and the
 * Java side reads them back and registers them.
 *
 * GPLv3, as the rest of depth3d.
 */
#ifndef DEPTH3D_DNSSD_ANDROID_H
#define DEPTH3D_DNSSD_ANDROID_H

#include "dnssd.h"

#ifdef __cplusplus
extern "C" {
#endif

#define DNSSD_ANDROID_RAOP    0
#define DNSSD_ANDROID_AIRPLAY 1

/* Whether the service has been "registered" (its record built), its port,
 * its instance name and its TXT entries as key and value pairs. Valid until
 * the service is unregistered or dnssd_destroy. */
int dnssd_android_is_registered(dnssd_t *dnssd, int which);
unsigned short dnssd_android_port(dnssd_t *dnssd, int which);
const char *dnssd_android_service_name(dnssd_t *dnssd, int which);
int dnssd_android_txt_count(dnssd_t *dnssd, int which);
const char *dnssd_android_txt_key(dnssd_t *dnssd, int which, int index);
const char *dnssd_android_txt_value(dnssd_t *dnssd, int which, int index);

#ifdef __cplusplus
}
#endif
#endif
