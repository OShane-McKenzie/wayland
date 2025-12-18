/*
 * wayland_helper.c
 *
 * Bridge process between the JVM (Compose Desktop) and a real Wayland
 * compositor using zwlr_layer_shell_v1.
 *
 * Communication with the JVM:
 *   - Unix-domain socket  →  commands and events (binary IPC protocol)
 *   - Shared file (mmap)  →  pixel data (BGRA 32-bit, width × height × 4)
 *
 * Usage:
 *   wayland-helper --socket <path>
 */

#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <stdbool.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <poll.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <wayland-client.h>
#include "wlr-layer-shell-client-protocol.h"

/* ── IPC Protocol ──────────────────────────────────────────────────────────── */
#define MAGIC           0x56495244u  /* "VIRD" */
#define MSG_CONFIGURE   0x01
#define MSG_CFG_ACK     0x02
#define MSG_FRAME_READY 0x03
#define MSG_FRAME_DONE  0x04
#define MSG_PTR_EVENT   0x05
#define MSG_KEY_EVENT   0x06
#define MSG_RESIZE      0x07
#define MSG_SHUTDOWN    0x08
#define MSG_ERROR       0x09

/* Pointer event sub-types */
#define PTR_ENTER   0
#define PTR_LEAVE   1
#define PTR_MOTION  2
#define PTR_BUTTON  3
#define PTR_AXIS    4

typedef struct {
    uint32_t magic;
    uint32_t type;
    uint32_t len;   /* payload length in bytes */
} MsgHeader;

/* ── State ─────────────────────────────────────────────────────────────────── */
static struct {
    /* Wayland globals */
    struct wl_display            *display;
    struct wl_registry           *registry;
    struct wl_compositor         *compositor;
    struct wl_shm                *shm;
    struct zwlr_layer_shell_v1   *layer_shell;
    struct wl_output             *output;
    struct wl_seat               *seat;
    struct wl_pointer            *pointer;
    struct wl_keyboard           *keyboard;

    /* Our surface */
    struct wl_surface            *surface;
    struct zwlr_layer_surface_v1 *layer_surface;
    struct wl_shm_pool           *shm_pool;
    struct wl_buffer             *buffer;

    /* Frame buffer (shared with JVM) */
    void    *pixels;      /* mmap of the shared file */
    size_t   pixels_size;
    int      shm_fd;

    /* Configured dimensions */
    int32_t  width;
    int32_t  height;

    /* Socket to JVM */
    int      sock_fd;

    /* State flags */
    bool     configured;      /* compositor sent configure */
    bool     buffer_released; /* compositor released the buffer */
    bool     running;

    /* Configure serial (must be ack'd) */
    uint32_t configure_serial;

    /* Sequence for frame sync */
    int64_t  frame_seq;
} state;

/* ── Helpers ────────────────────────────────────────────────────────────────── */
static bool send_all(int fd, const void *buf, size_t n) {
    const uint8_t *p = buf;
    while (n > 0) {
        ssize_t r = write(fd, p, n);
        if (r <= 0) { perror("send_all"); return false; }
        p += r; n -= r;
    }
    return true;
}

static bool recv_all(int fd, void *buf, size_t n) {
    uint8_t *p = buf;
    while (n > 0) {
        ssize_t r = read(fd, p, n);
        if (r <= 0) { if (r < 0) perror("recv_all"); return false; }
        p += r; n -= r;
    }
    return true;
}

/* Send a message with a pre-built payload buffer. */
static bool send_msg(int fd, uint32_t type, const void *payload, uint32_t len) {
    MsgHeader hdr = { MAGIC, type, len };
    return send_all(fd, &hdr, sizeof(hdr)) &&
    (len == 0 || send_all(fd, payload, len));
}

static bool send_error(int fd, int32_t code, const char *msg) {
    uint8_t buf[256];
    uint32_t msglen = (uint32_t)strlen(msg);
    memcpy(buf, &code, 4);
    memcpy(buf + 4, &msglen, 4);
    memcpy(buf + 8, msg, msglen);
    return send_msg(fd, MSG_ERROR, buf, 8 + msglen);
}

/* ── wl_buffer listener ─────────────────────────────────────────────────────── */
static void buffer_release(void *data, struct wl_buffer *buf) {
    (void)data; (void)buf;
    state.buffer_released = true;
}
static const struct wl_buffer_listener buffer_listener = {
    .release = buffer_release,
};

/* ── zwlr_layer_surface_v1 listener ─────────────────────────────────────────── */
static void layer_surface_configure(void *data,
                                    struct zwlr_layer_surface_v1 *ls, uint32_t serial,
                                    uint32_t width, uint32_t height)
{
    (void)data; (void)ls;
    state.configure_serial = serial;

    /* Use compositor-assigned size if non-zero, else keep what we set */
    if (width  > 0) state.width  = (int32_t)width;
    if (height > 0) state.height = (int32_t)height;

    state.configured = true;
    printf("[C] configure: serial=%u  size=%dx%d\n",
           serial, state.width, state.height);
}

static void layer_surface_closed(void *data,
                                 struct zwlr_layer_surface_v1 *ls)
{
    (void)data; (void)ls;
    printf("[C] layer surface closed by compositor\n");
    state.running = false;
}

static const struct zwlr_layer_surface_v1_listener layer_surface_listener = {
    .configure = layer_surface_configure,
    .closed    = layer_surface_closed,
};

/* ── wl_pointer listener ─────────────────────────────────────────────────────── */
static void ptr_enter(void *data, struct wl_pointer *ptr,
                      uint32_t serial, struct wl_surface *surf, wl_fixed_t x, wl_fixed_t y)
{
    (void)data; (void)ptr; (void)serial; (void)surf;
    float fx = wl_fixed_to_double(x);
    float fy = wl_fixed_to_double(y);
    uint8_t buf[16];
    int32_t type = PTR_ENTER, btn = 0, st = 0;
    memcpy(buf,    &type, 4);
    memcpy(buf+4,  &fx,   4);
    memcpy(buf+8,  &fy,   4);
    memcpy(buf+12, &btn,  4);
    /* state field omitted — total 16 bytes (4+4+4+4) */
    send_msg(state.sock_fd, MSG_PTR_EVENT, buf, 16);
}

static void ptr_leave(void *data, struct wl_pointer *ptr,
                      uint32_t serial, struct wl_surface *surf)
{
    (void)data; (void)ptr; (void)serial; (void)surf;
    float fx = 0, fy = 0;
    uint8_t buf[16];
    int32_t type = PTR_LEAVE, btn = 0;
    memcpy(buf,    &type, 4);
    memcpy(buf+4,  &fx,   4);
    memcpy(buf+8,  &fy,   4);
    memcpy(buf+12, &btn,  4);
    send_msg(state.sock_fd, MSG_PTR_EVENT, buf, 16);
}

/* Track last known cursor position so button events include coordinates */
static float g_cursor_x = 0, g_cursor_y = 0;

static void ptr_motion(void *data, struct wl_pointer *ptr,
                       uint32_t time, wl_fixed_t x, wl_fixed_t y)
{
    (void)data; (void)ptr; (void)time;
    float fx = wl_fixed_to_double(x);
    float fy = wl_fixed_to_double(y);
    g_cursor_x = fx; g_cursor_y = fy;
    uint8_t buf[16];
    int32_t type = PTR_MOTION, btn = 0;
    memcpy(buf,    &type, 4);
    memcpy(buf+4,  &fx,   4);
    memcpy(buf+8,  &fy,   4);
    memcpy(buf+12, &btn,  4);
    send_msg(state.sock_fd, MSG_PTR_EVENT, buf, 16);
}

static void ptr_button(void *data, struct wl_pointer *ptr,
                       uint32_t serial, uint32_t time, uint32_t button, uint32_t btn_state)
{
    (void)data; (void)ptr; (void)serial; (void)time;
    float fx = g_cursor_x, fy = g_cursor_y;
    uint8_t buf[20];
    int32_t type = PTR_BUTTON;
    int32_t ibtn  = (int32_t)button;
    int32_t ist   = (int32_t)btn_state;
    memcpy(buf,    &type, 4);
    memcpy(buf+4,  &fx,   4);
    memcpy(buf+8,  &fy,   4);
    memcpy(buf+12, &ibtn, 4);
    memcpy(buf+16, &ist,  4);
    send_msg(state.sock_fd, MSG_PTR_EVENT, buf, 20);
}

static void ptr_axis(void *d, struct wl_pointer *p,
uint32_t t, uint32_t axis, wl_fixed_t v) { (void)d;(void)p;(void)t;(void)axis;(void)v; }
static void ptr_frame(void *d, struct wl_pointer *p) { (void)d;(void)p; }
static void ptr_axis_source(void *d, struct wl_pointer *p, uint32_t s) { (void)d;(void)p;(void)s; }
static void ptr_axis_stop(void *d, struct wl_pointer *p, uint32_t t, uint32_t a) { (void)d;(void)p;(void)t;(void)a; }
static void ptr_axis_discrete(void *d, struct wl_pointer *p, uint32_t a, int32_t v) { (void)d;(void)p;(void)a;(void)v; }

static const struct wl_pointer_listener pointer_listener = {
    .enter         = ptr_enter,
    .leave         = ptr_leave,
    .motion        = ptr_motion,
    .button        = ptr_button,
    .axis          = ptr_axis,
    .frame         = ptr_frame,
    .axis_source   = ptr_axis_source,
    .axis_stop     = ptr_axis_stop,
    .axis_discrete = ptr_axis_discrete,
};

/* ── wl_keyboard listener ────────────────────────────────────────────────────── */
static void kb_keymap(void *d, struct wl_keyboard *kb,
                    uint32_t fmt, int32_t fd, uint32_t size)
{ (void)d;(void)kb;(void)fmt; close(fd); (void)size; }

static void kb_enter(void *d, struct wl_keyboard *kb,
                    uint32_t serial, struct wl_surface *surf, struct wl_array *keys)
{ (void)d;(void)kb;(void)serial;(void)surf;(void)keys; }

static void kb_leave(void *d, struct wl_keyboard *kb,
                    uint32_t serial, struct wl_surface *surf)
{ (void)d;(void)kb;(void)serial;(void)surf; }

static void kb_key(void *data, struct wl_keyboard *kb,
                uint32_t serial, uint32_t time, uint32_t key, uint32_t kb_state)
{
    (void)data; (void)kb; (void)serial; (void)time;
    uint8_t buf[12];
    int32_t keycode = (int32_t)key;
    int32_t st      = (int32_t)kb_state;
    int32_t mods    = 0;  /* filled in by kb_modifiers if needed */
    memcpy(buf,   &keycode, 4);
    memcpy(buf+4, &st,      4);
    memcpy(buf+8, &mods,    4);
    send_msg(state.sock_fd, MSG_KEY_EVENT, buf, 12);
}

static void kb_modifiers(void *d, struct wl_keyboard *kb,
                        uint32_t serial, uint32_t mods_depressed, uint32_t mods_latched,
                        uint32_t mods_locked, uint32_t group)
{ (void)d;(void)kb;(void)serial;(void)mods_depressed;
    (void)mods_latched;(void)mods_locked;(void)group; }

    static void kb_repeat_info(void *d, struct wl_keyboard *kb,
                            int32_t rate, int32_t delay)
    { (void)d;(void)kb;(void)rate;(void)delay; }

    static const struct wl_keyboard_listener keyboard_listener = {
        .keymap      = kb_keymap,
        .enter       = kb_enter,
        .leave       = kb_leave,
        .key         = kb_key,
        .modifiers   = kb_modifiers,
        .repeat_info = kb_repeat_info,
    };

    /* ── wl_seat listener ────────────────────────────────────────────────────────── */
    static void seat_capabilities(void *data, struct wl_seat *seat, uint32_t caps) {
        (void)data;
        if ((caps & WL_SEAT_CAPABILITY_POINTER) && !state.pointer) {
            state.pointer = wl_seat_get_pointer(seat);
            wl_pointer_add_listener(state.pointer, &pointer_listener, NULL);
        }
        if ((caps & WL_SEAT_CAPABILITY_KEYBOARD) && !state.keyboard) {
            state.keyboard = wl_seat_get_keyboard(seat);
            wl_keyboard_add_listener(state.keyboard, &keyboard_listener, NULL);
        }
    }
    static void seat_name(void *d, struct wl_seat *s, const char *n)
    { (void)d;(void)s;(void)n; }

    static const struct wl_seat_listener seat_listener = {
        .capabilities = seat_capabilities,
        .name         = seat_name,
    };

    /* ── Registry ────────────────────────────────────────────────────────────────── */
    static void registry_global(void *data, struct wl_registry *reg,
                                uint32_t name, const char *iface, uint32_t version)
    {
        (void)data;
        if (strcmp(iface, wl_compositor_interface.name) == 0) {
            state.compositor = wl_registry_bind(reg, name,
                                                &wl_compositor_interface, (version < 4) ? version : 4);
        } else if (strcmp(iface, wl_shm_interface.name) == 0) {
            state.shm = wl_registry_bind(reg, name, &wl_shm_interface, 1);
        } else if (strcmp(iface, zwlr_layer_shell_v1_interface.name) == 0) {
            state.layer_shell = wl_registry_bind(reg, name,
                                                &zwlr_layer_shell_v1_interface, (version < 4) ? version : 4);
        } else if (strcmp(iface, wl_output_interface.name) == 0 && !state.output) {
            state.output = wl_registry_bind(reg, name, &wl_output_interface, 1);
        } else if (strcmp(iface, wl_seat_interface.name) == 0) {
            state.seat = wl_registry_bind(reg, name, &wl_seat_interface,
                                        (version < 5) ? version : 5);
            wl_seat_add_listener(state.seat, &seat_listener, NULL);
        }
    }

    static void registry_global_remove(void *data, struct wl_registry *reg,
uint32_t name) { (void)data;(void)reg;(void)name; }

static const struct wl_registry_listener registry_listener = {
    .global        = registry_global,
    .global_remove = registry_global_remove,
};

/* ── Build & commit the first frame ─────────────────────────────────────────── */
static bool setup_shm_buffer(void) {
    size_t size = (size_t)state.width * (size_t)state.height * 4;

    if (state.pixels) {
        munmap(state.pixels, state.pixels_size);
        state.pixels = NULL;
    }
    if (state.shm_pool) {
        wl_shm_pool_destroy(state.shm_pool);
        state.shm_pool = NULL;
    }
    if (state.buffer) {
        wl_buffer_destroy(state.buffer);
        state.buffer = NULL;
    }

    /* Re-open / truncate the shared file to the new size */
    if (ftruncate(state.shm_fd, (off_t)size) < 0) {
        perror("ftruncate");
        return false;
    }

    state.pixels = mmap(NULL, size, PROT_READ | PROT_WRITE,
                        MAP_SHARED, state.shm_fd, 0);
    if (state.pixels == MAP_FAILED) {
        perror("mmap");
        state.pixels = NULL;
        return false;
    }
    state.pixels_size = size;

    state.shm_pool = wl_shm_create_pool(state.shm, state.shm_fd, (int32_t)size);
    state.buffer   = wl_shm_pool_create_buffer(state.shm_pool, 0,
                                                state.width, state.height, state.width * 4,
                                                WL_SHM_FORMAT_ARGB8888);
    wl_buffer_add_listener(state.buffer, &buffer_listener, NULL);
    state.buffer_released = true;
    return true;
}

/* ── Handle CONFIGURE message from JVM ──────────────────────────────────────── */
typedef struct {
    int32_t  layer;
    int32_t  anchor;
    int32_t  exclusive_zone;
    int32_t  keyboard_interactivity;
    int32_t  width;
    int32_t  height;
} ConfigFixed;

static bool handle_configure_msg(const uint8_t *payload, uint32_t len) {
    if (len < sizeof(ConfigFixed) + 8) {
        fprintf(stderr, "CONFIGURE payload too short\n");
        return false;
    }

    ConfigFixed cfg;
    memcpy(&cfg, payload, sizeof(cfg));
    uint32_t offset = sizeof(ConfigFixed);

    uint32_t ns_len;
    memcpy(&ns_len, payload + offset, 4); offset += 4;
    if (offset + ns_len + 4 > len) return false;
    char namespace[256] = {0};
    uint32_t copy_ns = ns_len < 255 ? ns_len : 255;
    memcpy(namespace, payload + offset, copy_ns); offset += ns_len;

    uint32_t shm_len;
    memcpy(&shm_len, payload + offset, 4); offset += 4;
    if (offset + shm_len > len) return false;
    char shm_path[512] = {0};
    uint32_t copy_shm = shm_len < 511 ? shm_len : 511;
    memcpy(shm_path, payload + offset, copy_shm);

    printf("[C] CONFIGURE layer=%d anchor=0x%x ez=%d kb=%d size=%dx%d ns=%s shm=%s\n",
            cfg.layer, cfg.anchor, cfg.exclusive_zone,
            cfg.keyboard_interactivity, cfg.width, cfg.height,
            namespace, shm_path);

    state.width  = cfg.width;
    state.height = cfg.height;

    /* Open the shared pixel file */
    state.shm_fd = open(shm_path, O_RDWR);
    if (state.shm_fd < 0) {
        perror("open shm_path");
        send_error(state.sock_fd, 1, "Cannot open shared pixel file");
        return false;
    }

    /* Create the Wayland surface */
    state.surface = wl_compositor_create_surface(state.compositor);
    if (!state.surface) {
        send_error(state.sock_fd, 2, "wl_compositor_create_surface failed");
        return false;
    }

    /* Create the layer surface */
    state.layer_surface = zwlr_layer_shell_v1_get_layer_surface(
        state.layer_shell,
        state.surface,
        state.output,           /* NULL = any output */
        (uint32_t)cfg.layer,
                                                                namespace
    );
    if (!state.layer_surface) {
        send_error(state.sock_fd, 3, "get_layer_surface failed");
        return false;
    }
    zwlr_layer_surface_v1_add_listener(state.layer_surface,
                                        &layer_surface_listener, NULL);

    zwlr_layer_surface_v1_set_anchor(state.layer_surface, (uint32_t)cfg.anchor);
    zwlr_layer_surface_v1_set_exclusive_zone(state.layer_surface, cfg.exclusive_zone);
    zwlr_layer_surface_v1_set_keyboard_interactivity(state.layer_surface,
                                                        (uint32_t)cfg.keyboard_interactivity);

    /* width=0 or height=0 tells the compositor to fill that axis */
    uint32_t req_w = (cfg.anchor & (ZWLR_LAYER_SURFACE_V1_ANCHOR_LEFT  |
    ZWLR_LAYER_SURFACE_V1_ANCHOR_RIGHT))
    == (ZWLR_LAYER_SURFACE_V1_ANCHOR_LEFT |
    ZWLR_LAYER_SURFACE_V1_ANCHOR_RIGHT)
    ? 0 : (uint32_t)cfg.width;
    uint32_t req_h = (cfg.anchor & (ZWLR_LAYER_SURFACE_V1_ANCHOR_TOP  |
    ZWLR_LAYER_SURFACE_V1_ANCHOR_BOTTOM))
    == (ZWLR_LAYER_SURFACE_V1_ANCHOR_TOP |
    ZWLR_LAYER_SURFACE_V1_ANCHOR_BOTTOM)
    ? 0 : (uint32_t)cfg.height;
    zwlr_layer_surface_v1_set_size(state.layer_surface, req_w, req_h);

    /* Initial empty commit → triggers compositor configure */
    wl_surface_commit(state.surface);
    wl_display_roundtrip(state.display);

    if (!state.configured) {
        send_error(state.sock_fd, 4, "Compositor did not send configure event");
        return false;
    }

    /* Ack the configure */
    zwlr_layer_surface_v1_ack_configure(state.layer_surface, state.configure_serial);

    /* Set up shared memory buffer at compositor-confirmed dimensions */
    if (!setup_shm_buffer()) {
        send_error(state.sock_fd, 5, "Failed to set up SHM buffer");
        return false;
    }

    /* Commit transparent black frame to make the surface visible */
    memset(state.pixels, 0, state.pixels_size);
    wl_surface_attach(state.surface, state.buffer, 0, 0);
    wl_surface_damage_buffer(state.surface, 0, 0, state.width, state.height);
    wl_surface_commit(state.surface);
    wl_display_flush(state.display);

    /* Tell JVM the actual dimensions */
    uint8_t ack[8];
    memcpy(ack,   &state.width,  4);
    memcpy(ack+4, &state.height, 4);
    return send_msg(state.sock_fd, MSG_CFG_ACK, ack, 8);
}

/* ── Handle FRAME_READY message from JVM ────────────────────────────────────── */
static bool handle_frame_ready(const uint8_t *payload, uint32_t len) {
    (void)len;
    int64_t seq;
    memcpy(&seq, payload, 8);

    /* Pixels are in shared mmap — compositor sees latest data immediately.
        *      Attach, damage, commit without waiting for buffer release. */
    wl_surface_attach(state.surface, state.buffer, 0, 0);
    wl_surface_damage_buffer(state.surface, 0, 0, state.width, state.height);
    wl_surface_commit(state.surface);
    wl_display_flush(state.display);

    /* Signal JVM immediately — no need to wait for compositor ack */
    uint8_t done[8];
    memcpy(done, &seq, 8);
    return send_msg(state.sock_fd, MSG_FRAME_DONE, done, 8);
}

/* ── Read and dispatch one JVM message ──────────────────────────────────────── */
static bool dispatch_jvm_message(void) {
    MsgHeader hdr;
    if (!recv_all(state.sock_fd, &hdr, sizeof(hdr))) return false;
    if (hdr.magic != MAGIC) {
        fprintf(stderr, "Bad magic: 0x%08x\n", hdr.magic);
        return false;
    }

    uint8_t *payload = NULL;
    if (hdr.len > 0) {
        payload = malloc(hdr.len);
        if (!payload) return false;
        if (!recv_all(state.sock_fd, payload, hdr.len)) { free(payload); return false; }
    }

    bool ok = true;
    switch (hdr.type) {
        case MSG_CONFIGURE:
            ok = handle_configure_msg(payload, hdr.len);
            break;
        case MSG_FRAME_READY:
            ok = handle_frame_ready(payload, hdr.len);
            break;
        case MSG_SHUTDOWN:
            printf("[C] SHUTDOWN received\n");
            state.running = false;
            break;
        default:
            fprintf(stderr, "[C] Unknown message type: 0x%x\n", hdr.type);
            break;
    }
    free(payload);
    return ok;
}

int main(int argc, char **argv) {
    const char *socket_path = NULL;

    for (int i = 1; i < argc - 1; i++) {
        if (strcmp(argv[i], "--socket") == 0) {
            socket_path = argv[i + 1];
        }
    }
    if (!socket_path) {
        fprintf(stderr, "Usage: wayland-helper --socket <path>\n");
        return 1;
    }

    /* ── Connect back to JVM Unix socket ── */
    state.sock_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (state.sock_fd < 0) { perror("socket"); return 1; }

    struct sockaddr_un addr = { .sun_family = AF_UNIX };
    strncpy(addr.sun_path, socket_path, sizeof(addr.sun_path) - 1);

    /* Retry a few times — JVM may not be listening yet */
    int retries = 0;
    while (connect(state.sock_fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        if (++retries > 10) { perror("connect"); return 1; }
        usleep(100000);
    }
    printf("[C] Connected to JVM socket\n");

    /* ── Connect to Wayland ── */
    state.display = wl_display_connect(NULL);
    if (!state.display) {
        fprintf(stderr, "[C] Cannot connect to Wayland display\n");
        send_error(state.sock_fd, 10, "Cannot connect to Wayland display");
        return 1;
    }

    state.registry = wl_display_get_registry(state.display);
    wl_registry_add_listener(state.registry, &registry_listener, NULL);
    wl_display_roundtrip(state.display);

    if (!state.compositor) {
        send_error(state.sock_fd, 11, "wl_compositor not available");
        return 1;
    }
    if (!state.shm) {
        send_error(state.sock_fd, 12, "wl_shm not available");
        return 1;
    }
    if (!state.layer_shell) {
        send_error(state.sock_fd, 13,
                    "zwlr_layer_shell_v1 not available — compositor may not support it");
        return 1;
    }

    printf("[C] Wayland globals bound. Waiting for CONFIGURE...\n");

    /* ── Main event loop ── */
    state.running = true;

    struct pollfd fds[2];
    fds[0].fd     = wl_display_get_fd(state.display);
    fds[0].events = POLLIN;
    fds[1].fd     = state.sock_fd;
    fds[1].events = POLLIN;

    while (state.running) {
        wl_display_flush(state.display);

        int ret = poll(fds, 2, 5000);
        if (ret < 0) {
            if (errno == EINTR) continue;
            perror("poll");
            break;
        }
        if (ret == 0) continue; /* timeout */

            if (fds[0].revents & POLLIN) {
                if (wl_display_dispatch(state.display) < 0) {
                    fprintf(stderr, "[C] wl_display_dispatch error\n");
                    break;
                }
            }
            if (fds[1].revents & (POLLIN | POLLHUP)) {
                if (!dispatch_jvm_message()) break;
            }
    }

    /* ── Cleanup ── */
    printf("[C] Shutting down\n");
    if (state.pixels)       munmap(state.pixels, state.pixels_size);
    if (state.shm_fd >= 0)  close(state.shm_fd);
    if (state.buffer)       wl_buffer_destroy(state.buffer);
    if (state.shm_pool)     wl_shm_pool_destroy(state.shm_pool);
    if (state.layer_surface) zwlr_layer_surface_v1_destroy(state.layer_surface);
    if (state.surface)      wl_surface_destroy(state.surface);
    if (state.pointer)      wl_pointer_destroy(state.pointer);
    if (state.keyboard)     wl_keyboard_destroy(state.keyboard);
    if (state.seat)         wl_seat_destroy(state.seat);
    if (state.layer_shell)  zwlr_layer_shell_v1_destroy(state.layer_shell);
    if (state.shm)          wl_shm_destroy(state.shm);
    if (state.compositor)   wl_compositor_destroy(state.compositor);
    if (state.output)       wl_output_destroy(state.output);
    if (state.registry)     wl_registry_destroy(state.registry);
    if (state.display)      wl_display_disconnect(state.display);
    close(state.sock_fd);
    return 0;
}
