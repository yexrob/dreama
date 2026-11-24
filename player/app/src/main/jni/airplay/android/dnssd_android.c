/*
 * depth3d: dnssd.h for Android, in place of UxPlay's lib/dnssd.c.
 *
 * UxPlay's dnssd.c registers the _raop._tcp and _airplay._tcp services with
 * Apple's dns_sd library (or Avahi's copy of it). Android has no such
 * library for apps; its mDNS daemon is reached through NsdManager. So this
 * file keeps what dnssd.c keeps (name, device id, feature bits, public key)
 * and builds the same TXT records, entry for entry, and the Java side reads
 * them back through dnssd_android.h and registers them. The wire form of
 * each record is kept too, because the RAOP /info handler hands it to a
 * client that asks for it.
 *
 * The record contents follow UxPlay v1.73.7 lib/dnssd.c and lib/dnssdint.h
 * (LGPL-2.1-or-later, Juho Vähä-Herttua and F. Duncanh); the rest is
 * depth3d's, GPLv3.
 */
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "dnssd.h"
#include "dnssdint.h"
#include "dnssd_android.h"
#include "global.h"
#include "utils.h"

#define MAX_SERVNAME 256
#define TXT_MAX_ENTRIES 32

typedef struct {
    int count;
    char *keys[TXT_MAX_ENTRIES];
    char *values[TXT_MAX_ENTRIES];
    /* The record as it goes on the wire: each entry a length byte and then
     * "key=value" */
    char *bytes;
    int length;
} txt_record_t;

typedef struct {
    int registered;
    unsigned short port;
    char name[MAX_SERVNAME];
    txt_record_t txt;
} service_t;

struct dnssd_s {
    char *name;
    int name_len;
    char *hw_addr;
    int hw_addr_len;
    uint32_t features1;
    uint32_t features2;
    unsigned char pin_pw;
    /* Owned by raop, set through raop_set_dnssd */
    const char *pk;
    service_t services[2];
};

static const char empty_txt[1] = { 0 };

static void txt_clear(txt_record_t *txt) {
    for (int i = 0; i < txt->count; i++) {
        free(txt->keys[i]);
        free(txt->values[i]);
    }
    free(txt->bytes);
    memset(txt, 0, sizeof(*txt));
}

/* As TXTRecordSetValue: a key set twice keeps the later value */
static int txt_set(txt_record_t *txt, const char *key, const char *value) {
    for (int i = 0; i < txt->count; i++) {
        if (!strcmp(txt->keys[i], key)) {
            char *copy = strdup(value);
            if (!copy) return -1;
            free(txt->values[i]);
            txt->values[i] = copy;
            return 0;
        }
    }
    if (txt->count == TXT_MAX_ENTRIES) return -1;
    /* One length byte carries key, '=' and value */
    if (strlen(key) + 1 + strlen(value) > 255) return -1;
    char *k = strdup(key);
    char *v = strdup(value);
    if (!k || !v) {
        free(k);
        free(v);
        return -1;
    }
    txt->keys[txt->count] = k;
    txt->values[txt->count] = v;
    txt->count++;
    return 0;
}

static int txt_build(txt_record_t *txt) {
    int length = 0;
    for (int i = 0; i < txt->count; i++) {
        length += 1 + (int) (strlen(txt->keys[i]) + 1 + strlen(txt->values[i]));
    }
    char *bytes = (char *) malloc(length > 0 ? length : 1);
    if (!bytes) return -1;
    int at = 0;
    for (int i = 0; i < txt->count; i++) {
        size_t klen = strlen(txt->keys[i]);
        size_t vlen = strlen(txt->values[i]);
        bytes[at++] = (char) (klen + 1 + vlen);
        memcpy(bytes + at, txt->keys[i], klen);
        at += (int) klen;
        bytes[at++] = '=';
        memcpy(bytes + at, txt->values[i], vlen);
        at += (int) vlen;
    }
    free(txt->bytes);
    txt->bytes = bytes;
    txt->length = length;
    return 0;
}

dnssd_t *
dnssd_init(const char *name, int name_len, const char *hw_addr, int hw_addr_len, int *error, unsigned char pin_pw)
{
    if (error) *error = DNSSD_ERROR_NOERROR;
    if (hw_addr_len <= 0 || hw_addr_len > MAX_HWADDR_LEN) {
        if (error) *error = DNSSD_ERROR_HWADDRLEN;
        return NULL;
    }

    dnssd_t *dnssd = (dnssd_t *) calloc(1, sizeof(dnssd_t));
    if (!dnssd) {
        if (error) *error = DNSSD_ERROR_OUTOFMEM;
        return NULL;
    }
    dnssd->pin_pw = pin_pw;

    char *end = NULL;
    unsigned long features = strtoul(FEATURES_1, &end, 16);
    if (!end || (features & 0xFFFFFFFF) != features) {
        free(dnssd);
        if (error) *error = DNSSD_ERROR_BADFEATURES;
        return NULL;
    }
    dnssd->features1 = (uint32_t) features;
    features = strtoul(FEATURES_2, &end, 16);
    if (!end || (features & 0xFFFFFFFF) != features) {
        free(dnssd);
        if (error) *error = DNSSD_ERROR_BADFEATURES;
        return NULL;
    }
    dnssd->features2 = (uint32_t) features;

    dnssd->name = (char *) calloc(1, name_len + 1);
    dnssd->hw_addr = (char *) calloc(1, hw_addr_len);
    if (!dnssd->name || !dnssd->hw_addr) {
        free(dnssd->name);
        free(dnssd->hw_addr);
        free(dnssd);
        if (error) *error = DNSSD_ERROR_OUTOFMEM;
        return NULL;
    }
    memcpy(dnssd->name, name, name_len);
    dnssd->name_len = name_len;
    memcpy(dnssd->hw_addr, hw_addr, hw_addr_len);
    dnssd->hw_addr_len = hw_addr_len;
    return dnssd;
}

int
dnssd_register_raop(dnssd_t *dnssd, unsigned short port)
{
    assert(dnssd);
    service_t *svc = &dnssd->services[DNSSD_ANDROID_RAOP];
    char features[22] = {0};
    snprintf(features, sizeof(features), "0x%X,0x%X", dnssd->features1, dnssd->features2);

    /* "AABBCCDDEEFF@name", as dnssd.c builds it */
    char servname[MAX_SERVNAME];
    if (utils_hwaddr_raop(servname, sizeof(servname), dnssd->hw_addr, dnssd->hw_addr_len) < 0) {
        return -1;
    }
    if (sizeof(servname) < strlen(servname) + 1 + dnssd->name_len + 1) {
        return -2;
    }
    strncat(servname, "@", sizeof(servname) - strlen(servname) - 1);
    strncat(servname, dnssd->name, sizeof(servname) - strlen(servname) - 1);

    txt_record_t *txt = &svc->txt;
    txt_clear(txt);
    int bad = 0;
    bad |= txt_set(txt, "ch", RAOP_CH);
    bad |= txt_set(txt, "cn", RAOP_CN);
    bad |= txt_set(txt, "da", RAOP_DA);
    bad |= txt_set(txt, "et", RAOP_ET);
    bad |= txt_set(txt, "vv", RAOP_VV);
    bad |= txt_set(txt, "ft", features);
    bad |= txt_set(txt, "am", GLOBAL_MODEL);
    bad |= txt_set(txt, "md", RAOP_MD);
    bad |= txt_set(txt, "rhd", RAOP_RHD);
    switch (dnssd->pin_pw) {
    case 2:
    case 3:
        bad |= txt_set(txt, "pw", "true");
        bad |= txt_set(txt, "sf", "0x84");
        break;
    case 1:
        bad |= txt_set(txt, "pw", "true");
        bad |= txt_set(txt, "sf", "0x8c");
        break;
    default:
        bad |= txt_set(txt, "pw", "false");
        bad |= txt_set(txt, "sf", RAOP_SF);
        break;
    }
    bad |= txt_set(txt, "sr", RAOP_SR);
    bad |= txt_set(txt, "ss", RAOP_SS);
    bad |= txt_set(txt, "sv", RAOP_SV);
    bad |= txt_set(txt, "tp", RAOP_TP);
    bad |= txt_set(txt, "txtvers", RAOP_TXTVERS);
    /* dnssd.c sets sf a second time here, which is what goes out */
    bad |= txt_set(txt, "sf", RAOP_SF);
    bad |= txt_set(txt, "vs", RAOP_VS);
    bad |= txt_set(txt, "vn", RAOP_VN);
    bad |= txt_set(txt, "pk", dnssd->pk ? dnssd->pk : "");
    if (bad || txt_build(txt)) {
        txt_clear(txt);
        return -3;
    }
    strncpy(svc->name, servname, sizeof(svc->name) - 1);
    svc->port = port;
    svc->registered = 1;
    return 0;
}

int
dnssd_register_airplay(dnssd_t *dnssd, unsigned short port)
{
    assert(dnssd);
    service_t *svc = &dnssd->services[DNSSD_ANDROID_AIRPLAY];
    char device_id[3 * MAX_HWADDR_LEN];
    char features[22] = {0};
    snprintf(features, sizeof(features), "0x%X,0x%X", dnssd->features1, dnssd->features2);
    if (utils_hwaddr_airplay(device_id, sizeof(device_id), dnssd->hw_addr, dnssd->hw_addr_len) < 0) {
        return -1;
    }

    txt_record_t *txt = &svc->txt;
    txt_clear(txt);
    int bad = 0;
    bad |= txt_set(txt, "deviceid", device_id);
    bad |= txt_set(txt, "features", features);
    bad |= txt_set(txt, "pw", dnssd->pin_pw ? "true" : "false");
    bad |= txt_set(txt, "flags", "0x4");
    bad |= txt_set(txt, "model", GLOBAL_MODEL);
    bad |= txt_set(txt, "pk", dnssd->pk ? dnssd->pk : "");
    bad |= txt_set(txt, "pi", AIRPLAY_PI);
    bad |= txt_set(txt, "srcvers", AIRPLAY_SRCVERS);
    bad |= txt_set(txt, "vv", AIRPLAY_VV);
    if (bad || txt_build(txt)) {
        txt_clear(txt);
        return -3;
    }
    strncpy(svc->name, dnssd->name, sizeof(svc->name) - 1);
    svc->port = port;
    svc->registered = 1;
    return 0;
}

static void unregister(dnssd_t *dnssd, int which) {
    service_t *svc = &dnssd->services[which];
    txt_clear(&svc->txt);
    svc->registered = 0;
    svc->port = 0;
    svc->name[0] = '\0';
}

void
dnssd_unregister_raop(dnssd_t *dnssd)
{
    assert(dnssd);
    unregister(dnssd, DNSSD_ANDROID_RAOP);
}

void
dnssd_unregister_airplay(dnssd_t *dnssd)
{
    assert(dnssd);
    unregister(dnssd, DNSSD_ANDROID_AIRPLAY);
}

static const char *txt_bytes(dnssd_t *dnssd, int which, int *length) {
    const txt_record_t *txt = &dnssd->services[which].txt;
    if (!txt->bytes) {
        *length = 0;
        return empty_txt;
    }
    *length = txt->length;
    return txt->bytes;
}

const char *
dnssd_get_raop_txt(dnssd_t *dnssd, int *length)
{
    return txt_bytes(dnssd, DNSSD_ANDROID_RAOP, length);
}

const char *
dnssd_get_airplay_txt(dnssd_t *dnssd, int *length)
{
    return txt_bytes(dnssd, DNSSD_ANDROID_AIRPLAY, length);
}

const char *
dnssd_get_name(dnssd_t *dnssd, int *length)
{
    *length = dnssd->name_len;
    return dnssd->name;
}

const char *
dnssd_get_hw_addr(dnssd_t *dnssd, int *length)
{
    *length = dnssd->hw_addr_len;
    return dnssd->hw_addr;
}

uint64_t
dnssd_get_airplay_features(dnssd_t *dnssd)
{
    uint64_t features = ((uint64_t) dnssd->features2) << 32;
    features += (uint64_t) dnssd->features1;
    return features;
}

void
dnssd_set_pk(dnssd_t *dnssd, char *pk_str)
{
    dnssd->pk = pk_str;
}

void
dnssd_set_airplay_features(dnssd_t *dnssd, int bit, int val)
{
    uint32_t mask;
    uint32_t *features;
    if (bit < 0 || bit > 63) return;
    if (val < 0 || val > 1) return;
    if (bit >= 32) {
        mask = 0x1u << (bit - 32);
        features = &dnssd->features2;
    } else {
        mask = 0x1u << bit;
        features = &dnssd->features1;
    }
    if (val) {
        *features |= mask;
    } else {
        *features &= ~mask;
    }
}

void
dnssd_destroy(dnssd_t *dnssd)
{
    if (!dnssd) return;
    unregister(dnssd, DNSSD_ANDROID_RAOP);
    unregister(dnssd, DNSSD_ANDROID_AIRPLAY);
    free(dnssd->name);
    free(dnssd->hw_addr);
    free(dnssd);
}

int
dnssd_android_is_registered(dnssd_t *dnssd, int which)
{
    if (!dnssd || which < 0 || which > 1) return 0;
    return dnssd->services[which].registered;
}

unsigned short
dnssd_android_port(dnssd_t *dnssd, int which)
{
    if (!dnssd || which < 0 || which > 1) return 0;
    return dnssd->services[which].port;
}

const char *
dnssd_android_service_name(dnssd_t *dnssd, int which)
{
    if (!dnssd || which < 0 || which > 1) return NULL;
    return dnssd->services[which].registered ? dnssd->services[which].name : NULL;
}

int
dnssd_android_txt_count(dnssd_t *dnssd, int which)
{
    if (!dnssd || which < 0 || which > 1) return 0;
    return dnssd->services[which].txt.count;
}

const char *
dnssd_android_txt_key(dnssd_t *dnssd, int which, int index)
{
    if (index < 0 || index >= dnssd_android_txt_count(dnssd, which)) return NULL;
    return dnssd->services[which].txt.keys[index];
}

const char *
dnssd_android_txt_value(dnssd_t *dnssd, int which, int index)
{
    if (index < 0 || index >= dnssd_android_txt_count(dnssd, which)) return NULL;
    return dnssd->services[which].txt.values[index];
}
