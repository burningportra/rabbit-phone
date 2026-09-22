#ifndef RABBIT_BRIDGE_CORE_H
#define RABBIT_BRIDGE_CORE_H
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

enum command { CMD_NONE, CMD_SLEEP, CMD_SHUTDOWN, CMD_FRONT, CMD_REAR, CMD_PRIVACY, CMD_PING };
static inline enum command parse_command(const char *s, size_t n) {
    static const char *const names[] = { "", "SLEEP", "SHUTDOWN", "MOTOR_FRONT", "MOTOR_REAR", "MOTOR_PRIVACY", "PING" };
    for (int i = CMD_SLEEP; i <= CMD_PING; ++i)
        if (strlen(names[i]) == n && memcmp(s, names[i], n) == 0) return (enum command)i;
    return CMD_NONE;
}

/* UI-thread heartbeat, not a background worker: a hung UI must lose its grab. */
struct heartbeat_lease { int64_t deadline; };
static inline void lease_refresh(struct heartbeat_lease *lease, int64_t now) { lease->deadline = now + 3000; }
static inline bool lease_expired(const struct heartbeat_lease *lease, int64_t now) { return now >= lease->deadline; }

/* Lines longer than the buffer are discarded whole, never parsed as a suffix. */
struct command_parser { char line[32]; size_t used; bool overflow; };
static inline enum command command_byte(struct command_parser *p, char ch) {
    if (ch == '\n') {
        enum command c = p->overflow ? CMD_NONE : parse_command(p->line, p->used);
        p->used = 0; p->overflow = false;
        return c;
    }
    if (p->used < sizeof(p->line)) p->line[p->used++] = ch;
    else p->overflow = true;
    return CMD_NONE;
}

/* Two physical drivers can report the same button. Keep one logical press. */
struct power_state { bool down[2]; bool pressed; int64_t release_at; };
enum edge { EDGE_NONE, EDGE_DOWN, EDGE_UP };
static inline enum edge power_event(struct power_state *p, unsigned device, int value, int64_t now) {
    if (device >= 2 || (value != 0 && value != 1)) return EDGE_NONE;
    p->down[device] = value != 0;
    if (p->down[0] || p->down[1]) {
        p->release_at = 0;
        if (!p->pressed) { p->pressed = true; return EDGE_DOWN; }
    } else if (p->pressed && !p->release_at) p->release_at = now + 25;
    return EDGE_NONE;
}
static inline enum edge power_tick(struct power_state *p, int64_t now) {
    if (p->pressed && p->release_at && now >= p->release_at) {
        p->pressed = false; p->release_at = 0; return EDGE_UP;
    }
    return EDGE_NONE;
}
#endif
