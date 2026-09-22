#include "bridge_core.h"
#include <assert.h>
#include <stdio.h>

int main(void) {
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
