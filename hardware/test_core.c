#include "bridge_core.h"
#include <assert.h>
#include <stdio.h>

static void test_assistant(void) {
    struct assist_state s;
    assert(assist_begin(&s, false, false, 100) == ASSIST_RELEASED);
    assist_event(&s, 0, 1, 101);
    assert(assist_tick(&s, 200) == ASSIST_NONE);
    assert(!s.power.pressed); /* An already released invocation cannot start audio/clicks. */

    assert(assist_begin(&s, true, false, 100) == ASSIST_HELD);
    assist_event(&s, 0, 2, 105);
    assert(assist_tick(&s, 150) == ASSIST_NONE);
    assist_event(&s, 0, 0, 160);
    assert(assist_tick(&s, 184) == ASSIST_NONE);
    assert(assist_tick(&s, 185) == ASSIST_RELEASED);
    assist_event(&s, 1, 1, 186);
    assist_event(&s, 1, 0, 190);
    assert(assist_tick(&s, 215) == ASSIST_NONE); /* Terminal: never a second hold/click. */

    assert(assist_begin(&s, false, true, 100) == ASSIST_HELD);
    assist_event(&s, 1, 0, 110);
    assert(assist_tick(&s, 135) == ASSIST_RELEASED);

    assert(assist_begin(&s, true, true, 100) == ASSIST_HELD);
    assist_event(&s, 0, 0, 110);
    assert(assist_tick(&s, 200) == ASSIST_NONE); /* Second driver still held. */
    assist_event(&s, 1, 2, 201);
    assist_event(&s, 1, 0, 210);
    assist_event(&s, 0, 1, 230); /* Renewed down cancels the release debounce. */
    assert(assist_tick(&s, 240) == ASSIST_NONE);
    assist_event(&s, 0, 0, 250);
    assist_event(&s, 0, 0, 260); /* Duplicate UP does not extend it. */
    assert(assist_tick(&s, 274) == ASSIST_NONE);
    assert(assist_tick(&s, 275) == ASSIST_RELEASED);

    assert(assist_begin(&s, true, false, 100) == ASSIST_HELD);
    assert(assist_tick(&s, 65099) == ASSIST_NONE);
    assert(assist_tick(&s, 65100) == ASSIST_TIMEOUT);
    assist_event(&s, 0, 0, 65101);
    assert(assist_tick(&s, 65126) == ASSIST_NONE);
    assert(assist_begin(&s, true, false, 100) == ASSIST_HELD);
    assist_event(&s, 0, 0, 65080);
    assert(assist_tick(&s, 65105) == ASSIST_TIMEOUT); /* Timeout wins over late release. */

    assert(assist_marker_valid("ASSIST\n", 7));
    assert(!assist_marker_valid("ASSIST", 6));
    assert(!assist_marker_valid("ASSIST\r\n", 8));
    assert(!assist_marker_valid("ASSIST\0", 7));
    assert(!assist_marker_valid("ASSIST\nextra", 12));
    assert(!assist_marker_valid("assist\n", 7));
    size_t used = 0;
    const char *ping = "PING\nPING\n";
    for (const char *c = ping; *c; ++c)
        assert(assist_command_byte(&used, *c) == (*c == '\n' ? 1 : 0));
    assert(used == 0);
    const char *invalid[] = {"SLEEP\n", "MOTOR_FRONT\n", "PING \n", "PING\r\n", "\n", "ping\n", "PINGX"};
    for (size_t i = 0; i < sizeof(invalid) / sizeof(invalid[0]); ++i) {
        used = 0;
        bool rejected = false;
        for (const char *c = invalid[i]; *c; ++c) {
            if (assist_command_byte(&used, *c) < 0) { rejected = true; break; }
        }
        assert(rejected);
    }
    used = 0;
    assert(assist_command_byte(&used, '\0') == -1);
    puts("PASS: assistant initial held/released, both drivers, repeats, staggered release, debounce, terminal isolation, 65s timeout, strict marker and PING-only commands");
}

int main(void) {
    test_assistant();
    struct power_state p = {0};
    assert(power_event(&p, 0, 1, 100) == EDGE_DOWN);
    assert(power_event(&p, 1, 1, 101) == EDGE_NONE);
    assert(power_event(&p, 0, 2, 102) == EDGE_NONE);
    assert(power_event(&p, 0, 0, 110) == EDGE_NONE);
    assert(power_tick(&p, 200) == EDGE_NONE); /* Other driver remains down. */
    assert(power_event(&p, 1, 0, 201) == EDGE_NONE);
    assert(power_tick(&p, 225) == EDGE_NONE);
    assert(power_tick(&p, 226) == EDGE_UP);
    assert(power_tick(&p, 227) == EDGE_NONE);

    p = (struct power_state){0};
    assert(power_event(&p, 0, 1, 100) == EDGE_DOWN);
    power_event(&p, 0, 0, 110);
    assert(power_event(&p, 1, 1, 120) == EDGE_NONE); /* Staggered drivers. */
    assert(power_tick(&p, 140) == EDGE_NONE);
    power_event(&p, 1, 0, 150);
    assert(power_tick(&p, 175) == EDGE_UP);
    assert(power_event(&p, 0, 1, 180) == EDGE_DOWN); /* Next physical click. */

    struct command_parser parser = {0};
    const char *valid = "SLEEP\nSHUTDOWN\nMOTOR_FRONT\nMOTOR_REAR\nMOTOR_PRIVACY\nPING\n";
    int expected = CMD_SLEEP;
    for (const char *s = valid; *s; ++s) {
        enum command c = command_byte(&parser, *s);
        if (*s == '\n') assert(c == (enum command)expected++);
        else assert(c == CMD_NONE);
    }
    assert(expected == CMD_PING + 1);
    assert(parse_command("SLEEP;id", 8) == CMD_NONE);
    assert(parse_command("SLEEP\r", 6) == CMD_NONE);
    assert(parse_command("SLEEP\0junk", 10) == CMD_NONE);
    for (int i = 0; i < 40; ++i) assert(command_byte(&parser, 'x') == CMD_NONE);
    for (const char *s = "SLEEP\n"; *s; ++s) assert(command_byte(&parser, *s) == CMD_NONE);
    for (const char *s = "SLEEP\n"; *s; ++s)
        assert(command_byte(&parser, *s) == (*s == '\n' ? CMD_SLEEP : CMD_NONE));
    struct heartbeat_lease lease;
    lease_refresh(&lease, 100);
    assert(!lease_expired(&lease, 3099));
    assert(lease_expired(&lease, 3100));
    lease_refresh(&lease, 2000);
    assert(!lease_expired(&lease, 3100));
    assert(!lease_expired(&lease, 4999));
    assert(lease_expired(&lease, 5000));
    assert(lease_expired(&lease, 99999));
    puts("PASS: dual-driver edges, repeats, debounce, next click, exact commands, overflow recovery, heartbeat expiry/refresh");
    return 0;
}
