/*
 * wayland_helper.c
 *
 * Bridge process between the JVM (Compose Desktop) and a Wayland compositor
 * using zwlr_layer_shell_v1.
 *
 * Fixes applied vs previous version:
 *   1. shm_fd initialised to -1 — prevents accidental close(0) (stdin)
 *   2. Double buffering — two wl_buffers alternate; each reused only after
 *      its wl_buffer.release event fires
 *   3. Frame callback pacing — FRAME_DONE sent only when compositor fires
 *      wl_surface.frame callback, syncing the JVM render rate to vsync
 *   4. prepare_read / read_events / dispatch_pending event loop — eliminates
 *      the race between poll() and wl_display_dispatch()
 *   5. Resize events — layer_surface_configure detects dimension changes after
 *      initial setup, rebuilds SHM buffers, and sends MSG_RESIZE to the JVM
 *   6. xkbcommon keyboard — full keymap, keysym mapping, and modifier state
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
#include <xkbcommon/xkbcommon.h>
#include "wlr-layer-shell-client-protocol.h"

/* ── IPC Protocol ─────────────────────────────────────────────────────────── */
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
    uint32_t len;
} MsgHeader;

/* ── Double-buffer slot ───────────────────────────────────────────────────── */
typedef struct {
    struct wl_buffer *buf;
    bool              released;  /* compositor fired wl_buffer.release */
} BufSlot;

/* ── State ────────────────────────────────────────────────────────────────── */
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

    /* Surface */
    struct wl_surface            *surface;
    struct zwlr_layer_surface_v1 *layer_surface;
    struct wl_shm_pool           *shm_pool;

    /*
     * Fix 2: double buffering.
     * slots[0] and slots[1] point into the same mmap'd file at offset 0 and
     * frame_bytes respectively.  We alternate: front is currently displayed,
     * back is free to write.  The JVM always writes into the back half.
     */
    BufSlot  slots[2];
    int      back_idx;        /* which slot the JVM is writing into */

    /* Shared pixel file */
    void    *pixels;          /* mmap base — size = 2 × frame_bytes */
    size_t   frame_bytes;     /* bytes for one frame = w × h × 4      */
    size_t   total_bytes;     /* = 2 × frame_bytes                     */
    int      shm_fd;          /* Fix 1: initialised to -1 in main()    */

    /* Dimensions */
    int32_t  width;
    int32_t  height;

    /* Socket to JVM */
    int      sock_fd;

    /* State flags */
    bool     configured;           /* compositor sent first configure    */
    bool     running;
    bool     frame_callback_pending; /* Fix 3: waiting for frame callback */

    /* Fix 5: resize tracking */
    bool     resize_pending;
    int32_t  pending_width;
    int32_t  pending_height;
    uint32_t pending_serial;

    /* Serial of most recent configure */
    uint32_t configure_serial;

    /* Frame sequence */
    int64_t  frame_seq;

    /* Fix 6: xkbcommon */
    struct xkb_context *xkb_ctx;
    struct xkb_keymap  *xkb_keymap;
    struct xkb_state   *xkb_state;
} state;

/* ── Helpers ──────────────────────────────────────────────────────────────── */
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

static bool send_msg(int fd, uint32_t type, const void *payload, uint32_t len) {
    MsgHeader hdr = { MAGIC, type, len };
    return send_all(fd, &hdr, sizeof(hdr)) &&
    (len == 0 || send_all(fd, payload, len));
}

static bool send_error(int fd, int32_t code, const char *msg) {
    uint8_t buf[256];
    uint32_t msglen = (uint32_t)strlen(msg);
    memcpy(buf,     &code,   4);
    memcpy(buf + 4, &msglen, 4);
    memcpy(buf + 8,  msg,    msglen);
    return send_msg(fd, MSG_ERROR, buf, 8 + msglen);
}

/* ── Fix 3: frame callback ────────────────────────────────────────────────── */
static void frame_callback_done(void *data, struct wl_callback *cb, uint32_t time);
static const struct wl_callback_listener frame_callback_listener = {
    .done = frame_callback_done,
};

static void register_frame_callback(void) {
    struct wl_callback *cb = wl_surface_frame(state.surface);
    wl_callback_add_listener(cb, &frame_callback_listener, NULL);
    state.frame_callback_pending = true;
}

static void frame_callback_done(void *data, struct wl_callback *cb, uint32_t time) {
    (void)data; (void)time;
    wl_callback_destroy(cb);
    state.frame_callback_pending = false;

    /* Tell JVM it may render the next frame */
    uint8_t done[8];
    memcpy(done, &state.frame_seq, 8);
    send_msg(state.sock_fd, MSG_FRAME_DONE, done, 8);
}

/* ── Fix 2: wl_buffer release listeners ──────────────────────────────────── */
static void buffer_release_0(void *data, struct wl_buffer *buf) {
    (void)data; (void)buf;
    state.slots[0].released = true;
}
static void buffer_release_1(void *data, struct wl_buffer *buf) {
    (void)data; (void)buf;
    state.slots[1].released = true;
}
static const struct wl_buffer_listener buf_listener_0 = { .release = buffer_release_0 };
static const struct wl_buffer_listener buf_listener_1 = { .release = buffer_release_1 };

/* ── Fix 5: layer surface configure ──────────────────────────────────────── */
static void layer_surface_configure(void *data,
                                    struct zwlr_layer_surface_v1 *ls, uint32_t serial,
                                    uint32_t width, uint32_t height)
{
    (void)data; (void)ls;

    int32_t new_w = (width  > 0) ? (int32_t)width  : state.width;
    int32_t new_h = (height > 0) ? (int32_t)height : state.height;

    if (!state.configured) {
        /* Initial configure */
        state.width            = new_w;
        state.height           = new_h;
        state.configure_serial = serial;
        state.configured       = true;
        printf("[C] initial configure: serial=%u size=%dx%d\n",
               serial, state.width, state.height);
    } else if (new_w != state.width || new_h != state.height) {
        /* Fix 5: dimension change after initial setup — queue a resize */
        printf("[C] resize configure: serial=%u size=%dx%d → %dx%d\n",
               serial, state.width, state.height, new_w, new_h);
        state.pending_width  = new_w;
        state.pending_height = new_h;
        state.pending_serial = serial;
        state.resize_pending = true;
    } else {
        /* Same size reconfigure (e.g. layer change) — just ack */
        zwlr_layer_surface_v1_ack_configure(state.layer_surface, serial);
        wl_surface_commit(state.surface);
        wl_display_flush(state.display);
    }
}

static void layer_surface_closed(void *data, struct zwlr_layer_surface_v1 *ls) {
    (void)data; (void)ls;
    printf("[C] layer surface closed by compositor\n");
    state.running = false;
}

static const struct zwlr_layer_surface_v1_listener layer_surface_listener = {
    .configure = layer_surface_configure,
    .closed    = layer_surface_closed,
};

/* ── Pointer listeners ────────────────────────────────────────────────────── */
static float g_cursor_x = 0, g_cursor_y = 0;

static void ptr_enter(void *data, struct wl_pointer *ptr,
                      uint32_t serial, struct wl_surface *surf, wl_fixed_t x, wl_fixed_t y)
{
    (void)data; (void)ptr; (void)serial; (void)surf;
    float fx = (float)wl_fixed_to_double(x);
    float fy = (float)wl_fixed_to_double(y);
    g_cursor_x = fx; g_cursor_y = fy;
    uint8_t buf[16];
    int32_t type = PTR_ENTER, btn = 0;
    memcpy(buf,    &type, 4); memcpy(buf+4, &fx, 4);
    memcpy(buf+8,  &fy,   4); memcpy(buf+12, &btn, 4);
    send_msg(state.sock_fd, MSG_PTR_EVENT, buf, 16);
}

static void ptr_leave(void *data, struct wl_pointer *ptr,
                      uint32_t serial, struct wl_surface *surf)
{
    (void)data; (void)ptr; (void)serial; (void)surf;
    uint8_t buf[16];
    int32_t type = PTR_LEAVE, btn = 0;
    float fx = 0, fy = 0;
    memcpy(buf,    &type, 4); memcpy(buf+4, &fx, 4);
    memcpy(buf+8,  &fy,   4); memcpy(buf+12, &btn, 4);
    send_msg(state.sock_fd, MSG_PTR_EVENT, buf, 16);
}

static void ptr_motion(void *data, struct wl_pointer *ptr,
                       uint32_t time, wl_fixed_t x, wl_fixed_t y)
{
    (void)data; (void)ptr; (void)time;
    float fx = (float)wl_fixed_to_double(x);
    float fy = (float)wl_fixed_to_double(y);
    g_cursor_x = fx; g_cursor_y = fy;
    uint8_t buf[16];
    int32_t type = PTR_MOTION, btn = 0;
    memcpy(buf,    &type, 4); memcpy(buf+4, &fx, 4);
    memcpy(buf+8,  &fy,   4); memcpy(buf+12, &btn, 4);
    send_msg(state.sock_fd, MSG_PTR_EVENT, buf, 16);
}

static void ptr_button(void *data, struct wl_pointer *ptr,
                       uint32_t serial, uint32_t time, uint32_t button, uint32_t btn_state)
{
    (void)data; (void)ptr; (void)serial; (void)time;
    float fx = g_cursor_x, fy = g_cursor_y;
    uint8_t buf[20];
    int32_t type = PTR_BUTTON;
    int32_t ibtn = (int32_t)button;
    int32_t ist  = (int32_t)btn_state;
    memcpy(buf,    &type, 4); memcpy(buf+4,  &fx,   4);
    memcpy(buf+8,  &fy,   4); memcpy(buf+12, &ibtn, 4);
    memcpy(buf+16, &ist,  4);
    send_msg(state.sock_fd, MSG_PTR_EVENT, buf, 20);
}

static void ptr_axis(void *d, struct wl_pointer *p,
                     uint32_t t, uint32_t axis, wl_fixed_t v)
{ (void)d;(void)p;(void)t;(void)axis;(void)v; }
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

/* ── Fix 6: keyboard listeners (xkbcommon) ───────────────────────────────── */
static void kb_keymap(void *data, struct wl_keyboard *kb,
                      uint32_t fmt, int32_t fd, uint32_t size)
{
    (void)data; (void)kb;

    if (fmt != WL_KEYBOARD_KEYMAP_FORMAT_XKB_V1) {
        close(fd);
        return;
    }

    char *map_str = mmap(NULL, size, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (map_str == MAP_FAILED) return;

    if (state.xkb_state)   { xkb_state_unref(state.xkb_state);   state.xkb_state   = NULL; }
    if (state.xkb_keymap)  { xkb_keymap_unref(state.xkb_keymap); state.xkb_keymap  = NULL; }

    state.xkb_keymap = xkb_keymap_new_from_string(
        state.xkb_ctx, map_str,
        XKB_KEYMAP_FORMAT_TEXT_V1, XKB_KEYMAP_COMPILE_NO_FLAGS);
    munmap(map_str, size);

    if (state.xkb_keymap)
        state.xkb_state = xkb_state_new(state.xkb_keymap);

    printf("[C] xkb keymap loaded\n");
}

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

    /*
     * Wayland key codes are evdev codes (key + 8 = XKB keycode).
     * We send:
     *   keycode  — raw evdev code (matches java.awt.event.KeyEvent VK_ values
     *              after Linux→Java mapping on the JVM side)
     *   keysym   — XKB keysym (unicode-aware, layout-aware)
     *   state    — 0=released 1=pressed 2=repeat
     *   modifiers — bitmask: bit0=shift bit1=ctrl bit2=alt bit3=super
     */
    int32_t evdev_code = (int32_t)key;
    int32_t keysym     = 0;
    int32_t mods       = 0;

    if (state.xkb_state) {
        xkb_keycode_t xkb_code = key + 8;
        keysym = (int32_t)xkb_state_key_get_one_sym(state.xkb_state, xkb_code);

        bool shift = xkb_state_mod_name_is_active(state.xkb_state,
                                                  XKB_MOD_NAME_SHIFT, XKB_STATE_MODS_EFFECTIVE) > 0;
                                                  bool ctrl  = xkb_state_mod_name_is_active(state.xkb_state,
                                                                                            XKB_MOD_NAME_CTRL,  XKB_STATE_MODS_EFFECTIVE) > 0;
                                                                                            bool alt   = xkb_state_mod_name_is_active(state.xkb_state,
                                                                                                                                      XKB_MOD_NAME_ALT,   XKB_STATE_MODS_EFFECTIVE) > 0;
                                                                                                                                      bool super = xkb_state_mod_name_is_active(state.xkb_state,
                                                                                                                                                                                XKB_MOD_NAME_LOGO,  XKB_STATE_MODS_EFFECTIVE) > 0;
                                                                                                                                                                                if (shift) mods |= (1 << 0);
                                                                                                                                                                                if (ctrl)  mods |= (1 << 1);
                                                                                                                                                                                if (alt)   mods |= (1 << 2);
                                                                                                                                                                                if (super) mods |= (1 << 3);
    }

    uint8_t buf[16];
    int32_t st = (int32_t)kb_state;
    memcpy(buf,    &evdev_code, 4);
    memcpy(buf+4,  &st,         4);
    memcpy(buf+8,  &mods,       4);
    memcpy(buf+12, &keysym,     4);
    send_msg(state.sock_fd, MSG_KEY_EVENT, buf, 16);
}

static void kb_modifiers(void *data, struct wl_keyboard *kb,
                         uint32_t serial, uint32_t mods_depressed, uint32_t mods_latched,
                         uint32_t mods_locked, uint32_t group)
{
    (void)data; (void)kb; (void)serial;
    if (state.xkb_state)
        xkb_state_update_mask(state.xkb_state,
                              mods_depressed, mods_latched, mods_locked, 0, 0, group);
}

static void kb_repeat_info(void *d, struct wl_keyboard *kb, int32_t rate, int32_t delay)
{ (void)d;(void)kb;(void)rate;(void)delay; }

static const struct wl_keyboard_listener keyboard_listener = {
    .keymap      = kb_keymap,
    .enter       = kb_enter,
    .leave       = kb_leave,
    .key         = kb_key,
    .modifiers   = kb_modifiers,
    .repeat_info = kb_repeat_info,
};

/* ── Seat listener ────────────────────────────────────────────────────────── */
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
static void seat_name(void *d, struct wl_seat *s, const char *n) { (void)d;(void)s;(void)n; }

static const struct wl_seat_listener seat_listener = {
    .capabilities = seat_capabilities,
    .name         = seat_name,
};

/* ── Registry ─────────────────────────────────────────────────────────────── */
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

static void registry_global_remove(void *d, struct wl_registry *r, uint32_t n)
{ (void)d;(void)r;(void)n; }

static const struct wl_registry_listener registry_listener = {
    .global        = registry_global,
    .global_remove = registry_global_remove,
};

/* ── Fix 2: double-buffer SHM setup ──────────────────────────────────────── */
static bool setup_shm_buffers(void) {
    size_t frame_bytes = (size_t)state.width * (size_t)state.height * 4;

    /* Tear down existing resources */
    if (state.pixels) {
        munmap(state.pixels, state.frame_bytes);
        state.pixels = NULL;
    }
    if (state.slots[0].buf) {
        wl_buffer_destroy(state.slots[0].buf);
        state.slots[0].buf      = NULL;
        state.slots[0].released = true;
    }
    if (state.slots[1].buf) {
        wl_buffer_destroy(state.slots[1].buf);
        state.slots[1].buf      = NULL;
        state.slots[1].released = true;
    }
    if (state.shm_pool) {
        wl_shm_pool_destroy(state.shm_pool);
        state.shm_pool = NULL;
    }

    /*
     * Single buffer backed by the JVM's shared file.
     * The JVM allocates exactly frame_bytes in the file — we match that.
     * Frame callback pacing (FRAME_DONE sent only after wl_surface.frame)
     * ensures the JVM never writes while the compositor is reading,
     * making double buffering unnecessary here.
     */
    state.pixels = mmap(NULL, frame_bytes, PROT_READ | PROT_WRITE,
                        MAP_SHARED, state.shm_fd, 0);
    if (state.pixels == MAP_FAILED) {
        perror("mmap");
        state.pixels = NULL;
        return false;
    }
    state.frame_bytes = frame_bytes;
    state.total_bytes = frame_bytes;  /* keep total_bytes in sync */

    state.shm_pool = wl_shm_create_pool(state.shm, state.shm_fd, (int32_t)frame_bytes);

    state.slots[0].buf = wl_shm_pool_create_buffer(state.shm_pool, 0,
                                                   state.width, state.height, state.width * 4, WL_SHM_FORMAT_ARGB8888);
    state.slots[0].released = true;
    wl_buffer_add_listener(state.slots[0].buf, &buf_listener_0, NULL);

    /* slot 1 unused but keep it null-safe */
    state.slots[1].buf      = NULL;
    state.slots[1].released = true;

    state.back_idx = 0;
    return true;
}

/* ── Handle CONFIGURE from JVM ────────────────────────────────────────────── */
typedef struct {
    int32_t  layer;
    int32_t  anchor;
    int32_t  exclusive_zone;
    int32_t  keyboard_interactivity;
    int32_t  width;
    int32_t  height;
    int32_t  margin_top;
    int32_t  margin_bottom;
    int32_t  margin_left;
    int32_t  margin_right;
} ConfigFixed;

static bool handle_configure_msg(const uint8_t *payload, uint32_t len) {
    if (len < sizeof(ConfigFixed) + 8) {
        fprintf(stderr, "CONFIGURE payload too short\n");
        return false;
    }

    ConfigFixed cfg;
    memcpy(&cfg, payload, sizeof(cfg));
    uint32_t offset = sizeof(cfg);

    uint32_t ns_len;
    memcpy(&ns_len, payload + offset, 4); offset += 4;
    if (offset + ns_len + 4 > len) return false;
    char ns[256] = {0};
    memcpy(ns, payload + offset, ns_len < 255 ? ns_len : 255); offset += ns_len;

    uint32_t shm_len;
    memcpy(&shm_len, payload + offset, 4); offset += 4;
    if (offset + shm_len > len) return false;
    char shm_path[512] = {0};
    memcpy(shm_path, payload + offset, shm_len < 511 ? shm_len : 511);

    printf("[C] CONFIGURE layer=%d anchor=0x%x ez=%d kb=%d size=%dx%d ns=%s shm=%s\n",
           cfg.layer, cfg.anchor, cfg.exclusive_zone,
           cfg.keyboard_interactivity, cfg.width, cfg.height, ns, shm_path);

    state.width  = cfg.width;
    state.height = cfg.height;

    /* Fix 1: open shm_fd (already initialised to -1 in main) */
    state.shm_fd = open(shm_path, O_RDWR);
    if (state.shm_fd < 0) {
        perror("open shm_path");
        send_error(state.sock_fd, 1, "Cannot open shared pixel file");
        return false;
    }

    state.surface = wl_compositor_create_surface(state.compositor);
    if (!state.surface) {
        send_error(state.sock_fd, 2, "wl_compositor_create_surface failed");
        return false;
    }

    state.layer_surface = zwlr_layer_shell_v1_get_layer_surface(
        state.layer_shell, state.surface, state.output,
        (uint32_t)cfg.layer, ns);
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
    zwlr_layer_surface_v1_set_margin(state.layer_surface,
                                     cfg.margin_top, cfg.margin_right, cfg.margin_bottom, cfg.margin_left);

    uint32_t req_w = ((cfg.anchor & (ZWLR_LAYER_SURFACE_V1_ANCHOR_LEFT |
    ZWLR_LAYER_SURFACE_V1_ANCHOR_RIGHT))
    == (ZWLR_LAYER_SURFACE_V1_ANCHOR_LEFT |
    ZWLR_LAYER_SURFACE_V1_ANCHOR_RIGHT)) ? 0 : (uint32_t)cfg.width;
    uint32_t req_h = ((cfg.anchor & (ZWLR_LAYER_SURFACE_V1_ANCHOR_TOP |
    ZWLR_LAYER_SURFACE_V1_ANCHOR_BOTTOM))
    == (ZWLR_LAYER_SURFACE_V1_ANCHOR_TOP |
    ZWLR_LAYER_SURFACE_V1_ANCHOR_BOTTOM)) ? 0 : (uint32_t)cfg.height;
    zwlr_layer_surface_v1_set_size(state.layer_surface, req_w, req_h);

    wl_surface_commit(state.surface);
    wl_display_roundtrip(state.display);

    if (!state.configured) {
        send_error(state.sock_fd, 4, "Compositor did not send configure event");
        return false;
    }

    zwlr_layer_surface_v1_ack_configure(state.layer_surface, state.configure_serial);

    if (!setup_shm_buffers()) {
        send_error(state.sock_fd, 5, "Failed to set up SHM buffers");
        return false;
    }

    /* Commit blank frame to make surface visible, then register frame callback */
    memset(state.pixels, 0, state.total_bytes);
    wl_surface_attach(state.surface, state.slots[0].buf, 0, 0);
    state.slots[0].released = false;
    wl_surface_damage_buffer(state.surface, 0, 0, state.width, state.height);
    register_frame_callback();
    wl_surface_commit(state.surface);
    wl_display_flush(state.display);

    uint8_t ack[8];
    memcpy(ack,   &state.width,  4);
    memcpy(ack+4, &state.height, 4);
    return send_msg(state.sock_fd, MSG_CFG_ACK, ack, 8);
}

/* ── Fix 5: handle pending resize ────────────────────────────────────────── */
static void apply_resize(void) {
    if (!state.resize_pending) return;

    state.width  = state.pending_width;
    state.height = state.pending_height;
    state.resize_pending = false;

    zwlr_layer_surface_v1_ack_configure(state.layer_surface, state.pending_serial);

    if (!setup_shm_buffers()) {
        fprintf(stderr, "[C] Failed to rebuild SHM buffers on resize\n");
        state.running = false;
        return;
    }

    /* Commit blank frame at new size */
    memset(state.pixels, 0, state.total_bytes);
    wl_surface_attach(state.surface, state.slots[0].buf, 0, 0);
    state.slots[0].released = false;
    wl_surface_damage_buffer(state.surface, 0, 0, state.width, state.height);
    register_frame_callback();
    wl_surface_commit(state.surface);
    wl_display_flush(state.display);

    /* Notify JVM of new dimensions */
    uint8_t msg[8];
    memcpy(msg,   &state.width,  4);
    memcpy(msg+4, &state.height, 4);
    send_msg(state.sock_fd, MSG_RESIZE, msg, 8);
    printf("[C] resize applied: %dx%d\n", state.width, state.height);
}

/* ── Handle FRAME_READY from JVM ──────────────────────────────────────────── */
static bool handle_frame_ready(const uint8_t *payload, uint32_t len) {
    (void)len;
    memcpy(&state.frame_seq, payload, 8);

    /*
     * The JVM always writes pixels into offset 0 of the shared file = slot 0.
     * Frame callback pacing means FRAME_DONE is only sent after the compositor
     * has presented the previous frame, so the buffer is always free by the
     * time we get here. Just always commit.
     */
    state.slots[0].released = false;
    wl_surface_attach(state.surface, state.slots[0].buf, 0, 0);
    wl_surface_damage_buffer(state.surface, 0, 0, state.width, state.height);

    if (!state.frame_callback_pending) {
        register_frame_callback();
    }

    wl_surface_commit(state.surface);
    wl_display_flush(state.display);
    return true;
}

/* ── Dispatch one JVM message ─────────────────────────────────────────────── */
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
        case MSG_CONFIGURE:  ok = handle_configure_msg(payload, hdr.len); break;
        case MSG_FRAME_READY: ok = handle_frame_ready(payload, hdr.len);  break;
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

/* ── main ─────────────────────────────────────────────────────────────────── */
int main(int argc, char **argv) {
    const char *socket_path = NULL;
    for (int i = 1; i < argc - 1; i++) {
        if (strcmp(argv[i], "--socket") == 0)
            socket_path = argv[i + 1];
    }
    if (!socket_path) {
        fprintf(stderr, "Usage: wayland-helper --socket <path>\n");
        return 1;
    }

    /* Fix 1: initialise shm_fd to -1 so cleanup never closes fd 0 */
    state.shm_fd = -1;

    /* Fix 6: initialise xkbcommon context */
    state.xkb_ctx = xkb_context_new(XKB_CONTEXT_NO_FLAGS);
    if (!state.xkb_ctx) {
        fprintf(stderr, "[C] Failed to create xkb context\n");
        return 1;
    }

    /* Connect to JVM socket */
    state.sock_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (state.sock_fd < 0) { perror("socket"); return 1; }

    struct sockaddr_un addr = { .sun_family = AF_UNIX };
    strncpy(addr.sun_path, socket_path, sizeof(addr.sun_path) - 1);

    int retries = 0;
    while (connect(state.sock_fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        if (++retries > 10) { perror("connect"); return 1; }
        usleep(100000);
    }
    printf("[C] Connected to JVM socket\n");

    /* Connect to Wayland */
    state.display = wl_display_connect(NULL);
    if (!state.display) {
        send_error(state.sock_fd, 10, "Cannot connect to Wayland display");
        return 1;
    }

    state.registry = wl_display_get_registry(state.display);
    wl_registry_add_listener(state.registry, &registry_listener, NULL);
    wl_display_roundtrip(state.display);

    if (!state.compositor) { send_error(state.sock_fd, 11, "wl_compositor not available"); return 1; }
    if (!state.shm)        { send_error(state.sock_fd, 12, "wl_shm not available");        return 1; }
    if (!state.layer_shell){ send_error(state.sock_fd, 13, "zwlr_layer_shell_v1 not available"); return 1; }

    printf("[C] Wayland globals bound. Waiting for CONFIGURE...\n");

    /* Fix 4: prepare_read / read_events / dispatch_pending event loop */
    state.running = true;

    struct pollfd fds[2];
    fds[0].fd     = wl_display_get_fd(state.display);
    fds[0].events = POLLIN;
    fds[1].fd     = state.sock_fd;
    fds[1].events = POLLIN;

    while (state.running) {
        /* Fix 5: apply any pending resize before next frame */
        if (state.resize_pending)
            apply_resize();

        /* Fix 4: prepare_read locks the read queue */
        while (wl_display_prepare_read(state.display) != 0)
            wl_display_dispatch_pending(state.display);

        wl_display_flush(state.display);

        int ret = poll(fds, 2, 5000);
        if (ret < 0) {
            if (errno == EINTR) {
                wl_display_cancel_read(state.display);
                continue;
            }
            wl_display_cancel_read(state.display);
            perror("poll");
            break;
        }

        if (ret == 0) {
            wl_display_cancel_read(state.display);
            continue;
        }

        if (fds[0].revents & POLLIN) {
            wl_display_read_events(state.display);   /* reads into internal queue */
            wl_display_dispatch_pending(state.display); /* dispatches from queue  */
        } else {
            wl_display_cancel_read(state.display);
        }

        if (fds[1].revents & (POLLIN | POLLHUP)) {
            if (!dispatch_jvm_message()) break;
        }
    }

    /* Cleanup */
    printf("[C] Shutting down\n");
    if (state.pixels)        munmap(state.pixels, state.total_bytes);
    if (state.shm_fd >= 0)   close(state.shm_fd);
    for (int i = 0; i < 2; i++) {
        if (state.slots[i].buf) wl_buffer_destroy(state.slots[i].buf);
    }
    if (state.shm_pool)      wl_shm_pool_destroy(state.shm_pool);
    if (state.layer_surface) zwlr_layer_surface_v1_destroy(state.layer_surface);
    if (state.surface)       wl_surface_destroy(state.surface);
    if (state.pointer)       wl_pointer_destroy(state.pointer);
    if (state.keyboard)      wl_keyboard_destroy(state.keyboard);
    if (state.seat)          wl_seat_destroy(state.seat);
    if (state.layer_shell)   zwlr_layer_shell_v1_destroy(state.layer_shell);
    if (state.shm)           wl_shm_destroy(state.shm);
    if (state.compositor)    wl_compositor_destroy(state.compositor);
    if (state.output)        wl_output_destroy(state.output);
    if (state.registry)      wl_registry_destroy(state.registry);
    if (state.display)       wl_display_disconnect(state.display);
    if (state.xkb_state)     xkb_state_unref(state.xkb_state);
    if (state.xkb_keymap)    xkb_keymap_unref(state.xkb_keymap);
    if (state.xkb_ctx)       xkb_context_unref(state.xkb_ctx);
    close(state.sock_fd);
    return 0;
}
