#define _GNU_SOURCE
#include "bridge_core.h"
#include <errno.h>
#include <dirent.h>
#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define DEFAULT_DIR "/data/user/0/com.kevtrinh.rabbitphone/files"
#define MOTOR_PATH "/sys/devices/platform/step_motor_ms35774/orientation"
#define BIT_BYTES(n) (((n) + 7) / 8)
static volatile sig_atomic_t stopping;
static bool camera_owned;
static void stop_signal(int sig) { (void)sig; stopping = 1; }
static int64_t monotonic_ms(void) {
    struct timespec t;
    if (clock_gettime(CLOCK_MONOTONIC, &t) != 0) return -1;
    return (int64_t)t.tv_sec * 1000 + t.tv_nsec / 1000000;
}
static bool bit_set(const unsigned char *bits, unsigned bit) {
    return (bits[bit / 8] & (1u << (bit % 8))) != 0;
}
static void release_inputs(int inputs[2]) {
    for (int i = 0; i < 2; ++i) if (inputs[i] >= 0) {
        (void)ioctl(inputs[i], EVIOCGRAB, 0);
        close(inputs[i]); inputs[i] = -1;
    }
}

/* Open relative to the verified directory, then verify the opened inode. */
static int open_fifo(int directory, const char *name, int mode, uid_t owner) {
    struct stat st;
    int fd = openat(directory, name, mode | O_NONBLOCK | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return -1;
    if (fstat(fd, &st) || !S_ISFIFO(st.st_mode) || st.st_uid != owner ||
        (st.st_mode & 0777) != 0600 || st.st_nlink != 1) {
        close(fd); errno = EPERM; return -1;
    }
    return fd;
}
static bool read_lease(int directory, uid_t owner, char nonce[33]) {
    int fd = openat(directory, "hardware-lease", O_RDONLY | O_NONBLOCK | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return false;
    struct stat st;
    char bytes[64];
    bool valid = !fstat(fd, &st) && S_ISREG(st.st_mode) && st.st_uid == owner &&
                 (st.st_mode & 0777) == 0600 && st.st_nlink == 1;
    ssize_t n = valid ? read(fd, bytes, sizeof(bytes)) : -1;
    close(fd);
    if (n != 33 || bytes[32] != '\n') return false;
    for (int i = 0; i < 32; ++i)
        if (!((bytes[i] >= 'a' && bytes[i] <= 'f') || (bytes[i] >= '0' && bytes[i] <= '9'))) return false;
    memcpy(nonce, bytes, 32); nonce[32] = '\0';
    return true;
}
static bool output_line(int fd, const char *line) {
    size_t n = strlen(line);
    /* Every record fits PIPE_BUF. Backpressure deliberately abandons the grab. */
    return write(fd, line, n) == (ssize_t)n;
}
static bool send_edge(int fd, enum edge edge, int64_t now) {
    if (edge == EDGE_NONE) return true;
    char line[64];
    snprintf(line, sizeof(line), "%s %lld\n", edge == EDGE_DOWN ? "DOWN" : "UP", (long long)now);
    return output_line(fd, line);
}

static bool key_released(int fd) {
    unsigned char keys[BIT_BYTES(KEY_MAX + 1)] = {0};
    return ioctl(fd, EVIOCGKEY(sizeof(keys)), keys) >= 0 && !bit_set(keys, KEY_POWER);
}
static bool acquire_inputs(int inputs[2]) {
    static const char *const names[2] = { "mtk-kpd", "mtk-pmic-keys" };
    /* Event numbers can change across boots; identify the two actual drivers. */
    for (int event = 0; event < 64; ++event) {
        char path[64], name[128] = {0};
        snprintf(path, sizeof(path), "/dev/input/event%d", event);
        int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC | O_NOFOLLOW);
        if (fd < 0) continue;
        int match = -1;
        if (ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name) >= 0)
            for (int i = 0; i < 2; ++i) if (!strcmp(name, names[i]) && inputs[i] < 0) match = i;
        if (match >= 0) inputs[match] = fd;
        else close(fd);
    }
    for (int i = 0; i < 2; ++i) {
        if (inputs[i] < 0) goto fail;
        unsigned char keys[BIT_BYTES(KEY_MAX + 1)] = {0};
        if (ioctl(inputs[i], EVIOCGBIT(EV_KEY, sizeof(keys)), keys) < 0 || !bit_set(keys, KEY_POWER)) goto fail;
    }
    /* Never consume the release of the wake-up press as a launcher click. */
    if (!key_released(inputs[0]) || !key_released(inputs[1])) goto fail;
    for (int i = 0; i < 2; ++i) if (ioctl(inputs[i], EVIOCGRAB, 1) < 0) goto fail;
    /* Drop events queued before ownership, including an already-delivered wake. */
    for (int i = 0; i < 2; ++i) {
        struct input_event queued[32];
        int batches = 0;
        for (;;) {
            ssize_t n = read(inputs[i], queued, sizeof(queued));
            if (n < 0 && errno == EAGAIN) break;
            if (n <= 0 || n % (ssize_t)sizeof(queued[0]) || ++batches > 8) goto fail;
            for (size_t j = 0; j < (size_t)n / sizeof(queued[0]); ++j)
                if ((queued[j].type == EV_KEY && queued[j].code != KEY_POWER) ||
                    (queued[j].type == EV_SYN && queued[j].code == SYN_DROPPED)) goto fail;
        }
    }
    /* A press racing acquisition must fail open rather than become a stale click. */
    if (!key_released(inputs[0]) || !key_released(inputs[1])) goto fail;
    return true;
fail:
    release_inputs(inputs);
    return false;
}

static void child_close_fds(void) {
    signal(SIGTERM, SIG_DFL); signal(SIGINT, SIG_DFL);
    signal(SIGPIPE, SIG_DFL); signal(SIGALRM, SIG_DFL);
    alarm(3); /* Bound command children; never leave an inherited input grab. */
    DIR *open_fds = opendir("/proc/self/fd");
    if (open_fds) {
        int keep = dirfd(open_fds);
        struct dirent *entry;
        while ((entry = readdir(open_fds))) {
            char *end;
            long fd = strtol(entry->d_name, &end, 10);
            if (*entry->d_name && !*end && fd >= 3 && fd != keep) close((int)fd);
        }
        closedir(open_fds);
    } else {
        for (int fd = 3; fd < 65536; ++fd) close(fd);
    }
}
static pid_t run_command(enum command command) {
    if (command < CMD_SLEEP || command > CMD_PRIVACY) return -1;
    pid_t child = fork();
    if (child < 0) { perror("rabbit-hardware: fork"); return -1; }
    if (child != 0) return child;
    child_close_fds();
    if (command == CMD_SLEEP) {
        char *const args[] = { "/system/bin/input", "keyevent", "223", NULL };
        execv(args[0], args);
    } else if (command == CMD_SHUTDOWN) {
        char *const args[] = { "/system/bin/svc", "power", "shutdown", NULL };
        execv(args[0], args);
    } else {
        const char *value = command == CMD_FRONT ? "0\n" : command == CMD_REAR ? "180\n" : "90\n";
        char current[16] = {0};
        int existing = open(MOTOR_PATH, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
        if (existing >= 0) {
            ssize_t got = read(existing, current, sizeof(current) - 1);
            close(existing);
            if (got > 0 && atoi(current) == atoi(value)) _exit(0);
        }
        int fd = open(MOTOR_PATH, O_WRONLY | O_CLOEXEC | O_NOFOLLOW);
        if (fd < 0) { perror("rabbit-hardware: motor open"); _exit(1); }
        bool okay = write(fd, value, strlen(value)) == (ssize_t)strlen(value);
        close(fd);
        if (!okay) perror("rabbit-hardware: motor write");
        _exit(okay ? 0 : 1);
    }
    perror("rabbit-hardware: exec"); _exit(1);
}

static void park_camera(void) {
    if (!camera_owned) return;
    camera_owned = false;
    /* All motor children have a three-second alarm. Drain them before parking,
       so an older rotation cannot finish after the final privacy position. */
    while (waitpid(-1, NULL, 0) > 0 || errno == EINTR) { if (stopping) break; }
    pid_t child = run_command(CMD_PRIVACY);
    /* Grabs are already released. Finish parking before a new camera lease starts. */
    if (child > 0) while (waitpid(child, NULL, 0) < 0 && errno == EINTR && !stopping) { }
}

static void reap_children(void) { while (waitpid(-1, NULL, WNOHANG) > 0) { } }

/* Stay connected after SLEEP/SHUTDOWN, but never re-grab for this reader. */
static void await_disconnect(int output) {
    struct pollfd p = { .fd = output, .events = 0 };
    while (!stopping) {
        reap_children();
        int n = poll(&p, 1, -1);
        if ((n < 0 && errno != EINTR) || (n > 0 && p.revents)) break;
    }
}

static void session(int output, int *commands, int inputs[2]) {
    struct power_state power = {0};
    struct command_parser parser = {0};
    struct heartbeat_lease lease;
    int64_t last_motor = -1000;
    if (!output_line(output, "READY\n")) return;
    int64_t started = monotonic_ms(); if (started < 0) return;
    lease_refresh(&lease, started);
    while (!stopping) {
        reap_children();
        struct pollfd p[4] = {
            { .fd = output, .events = 0 }, { .fd = *commands, .events = POLLIN },
            { .fd = inputs[0], .events = POLLIN }, { .fd = inputs[1], .events = POLLIN }
        };
        int64_t now = monotonic_ms();
        if (now < 0) return;
        int timeout = lease.deadline > now ? (int)(lease.deadline - now) : 0;
        if (power.release_at) {
            int debounce = power.release_at > now ? (int)(power.release_at - now) : 0;
            if (debounce < timeout) timeout = debounce;
        }
        int ready = poll(p, 4, timeout);
        if (ready < 0) { if (errno == EINTR) continue; return; }
        for (int i = 0; i < 4; ++i) if (p[i].revents & (POLLERR | POLLHUP | POLLNVAL)) return;
        now = monotonic_ms(); if (now < 0) return;
        if (lease_expired(&lease, now)) {
            fprintf(stderr, "rabbit-hardware: UI heartbeat expired, releasing grabs\n");
            release_inputs(inputs);
            park_camera();
            close(*commands); *commands = -1;
            await_disconnect(output);
            return;
        }
        /* Process physical events before an elapsed debounce timer. */
        for (unsigned i = 0; i < 2; ++i) if (p[i + 2].revents & POLLIN) {
            struct input_event events[32];
            ssize_t bytes = read(inputs[i], events, sizeof(events));
            if (bytes < 0 && errno == EAGAIN) continue;
            if (bytes <= 0 || bytes % (ssize_t)sizeof(events[0])) return;
            for (size_t j = 0; j < (size_t)bytes / sizeof(events[0]); ++j) {
                struct input_event *event = &events[j];
                if ((event->type == EV_SYN && event->code == SYN_DROPPED) ||
                    (event->type == EV_KEY && event->code != KEY_POWER)) {
                    fprintf(stderr, "rabbit-hardware: unexpected input or lost events, releasing grabs\n");
                    return;
                }
                if (event->type != EV_KEY) continue;
                now = monotonic_ms(); if (now < 0) return;
                if (!send_edge(output, power_event(&power, i, event->value, now), now)) return;
            }
        }
        now = monotonic_ms(); if (now < 0) return;
        if (!send_edge(output, power_tick(&power, now), now)) return;
        if (p[1].revents & POLLIN) {
            char bytes[1024];
            ssize_t n = read(*commands, bytes, sizeof(bytes));
            if (n < 0 && errno != EAGAIN) return;
            for (ssize_t i = 0; i < n; ++i) {
                enum command c = command_byte(&parser, bytes[i]);
                if (c == CMD_NONE) continue;
                if (c == CMD_PING) {
                    now = monotonic_ms(); if (now < 0) return;
                    lease_refresh(&lease, now); continue;
                }
                if (c == CMD_SLEEP || c == CMD_SHUTDOWN) {
                    release_inputs(inputs);
                    park_camera();
                    run_command(c);
                    await_disconnect(output);
                    return;
                }
                now = monotonic_ms(); if (now < 0) return;
                if (now - last_motor >= 250) {
                    last_motor = now;
                    camera_owned = true;
                    run_command(c);
                }
            }
        }
    }
}

int main(int argc, char **argv) {
    if (argc > 2 || (argc == 2 && argv[1][0] != '/')) {
        fprintf(stderr, "usage: rabbit-hardware [absolute-app-files-directory]\n"); return 2;
    }
    if (geteuid() != 0) { fprintf(stderr, "rabbit-hardware: root required\n"); return 1; }
    const char *path = argc == 2 ? argv[1] : DEFAULT_DIR;
    struct sigaction action = {0}; action.sa_handler = stop_signal;
    sigemptyset(&action.sa_mask);
    sigaction(SIGINT, &action, NULL); sigaction(SIGTERM, &action, NULL);
    signal(SIGPIPE, SIG_IGN);
    while (!stopping) {
        int inputs[2] = {-1, -1}, output = -1, commands = -1;
        int directory = open(path, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
        struct stat st;
        if (directory >= 0 && !fstat(directory, &st) && S_ISDIR(st.st_mode) &&
            st.st_uid >= 10000 && !(st.st_mode & 0022)) {
            char nonce[33], event_name[64], command_name[64];
            if (read_lease(directory, st.st_uid, nonce)) {
                snprintf(event_name, sizeof(event_name), "hardware-events-%s", nonce);
                snprintf(command_name, sizeof(command_name), "hardware-commands-%s", nonce);
                output = open_fifo(directory, event_name, O_WRONLY, st.st_uid);
                if (output >= 0) commands = open_fifo(directory, command_name, O_RDWR, st.st_uid);
                if (commands >= 0 && acquire_inputs(inputs)) session(output, &commands, inputs);
            }
        }
        release_inputs(inputs);
        park_camera();
        reap_children();
        if (commands >= 0) close(commands);
        if (output >= 0) close(output);
        if (directory >= 0) close(directory);
        struct timespec delay = { .tv_sec = 0, .tv_nsec = 500000000 };
        if (!stopping) nanosleep(&delay, NULL);
    }
    return 0;
}
