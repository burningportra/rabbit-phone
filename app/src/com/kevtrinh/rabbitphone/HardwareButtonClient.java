package com.kevtrinh.rabbitphone;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.system.StructPollfd;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Foreground-only, private FIFO connection to the bounded hardware input helper. */
public final class HardwareButtonClient {
    public interface Listener {
        void onReady();
        void onDown(long time);
        void onUp(long time);
        void onDisconnected();
    }

    public enum Command { PING, SLEEP, SHUTDOWN, MOTOR_FRONT, MOTOR_REAR, MOTOR_PRIVACY }
    private final File directory;
    private File events;
    private File commands;
    private String leaseId;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile int generation;
    private volatile boolean running;
    private volatile boolean ready;
    private FileDescriptor input;
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (!running || !ready) return;
            if (!send(Command.PING)) { stop(); listener.onDisconnected(); return; }
            main.postDelayed(this, 750);
        }
    };

    public HardwareButtonClient(Context context, Listener listener) {
        this.directory = context.getFilesDir();
        this.listener = listener;
    }

    private void ensureFifo(File file) throws Exception {
        try {
            StructStat value = Os.lstat(file.getAbsolutePath());
            if (!OsConstants.S_ISFIFO(value.st_mode) || value.st_uid != android.os.Process.myUid())
                throw new IllegalStateException("Invalid private input channel");
        } catch (ErrnoException error) {
            if (error.errno != OsConstants.ENOENT) throw error;
            Os.mkfifo(file.getAbsolutePath(), 0600);
        }
    }

    public synchronized void start() {
        if (running) return;
        try {
            Os.chmod(directory.getAbsolutePath(), 0700);
            leaseId = UUID.randomUUID().toString().replace("-", "");
            events = new File(directory, "hardware-events-" + leaseId);
            commands = new File(directory, "hardware-commands-" + leaseId);
            ensureFifo(events); ensureFifo(commands);
            input = Os.open(events.getAbsolutePath(),
                    OsConstants.O_RDONLY | OsConstants.O_NONBLOCK | OsConstants.O_NOFOLLOW, 0);
            File temporary = new File(directory, "hardware-lease-" + leaseId + ".tmp");
            try (FileOutputStream file = new FileOutputStream(temporary)) {
                Os.chmod(temporary.getAbsolutePath(), 0600);
                file.write((leaseId + "\n").getBytes(StandardCharsets.US_ASCII));
            }
            Os.rename(temporary.getAbsolutePath(), new File(directory, "hardware-lease").getAbsolutePath());
        } catch (Exception error) {
            android.util.Log.w("RabbitPhoneHardware", "Private control channel unavailable", error);
            stop();
            listener.onDisconnected(); return;
        }
        running = true;
        ready = false;
        final int session = ++generation;
        final FileDescriptor descriptor = input;
        Thread reader = new Thread(new Runnable() {
            @Override public void run() { readLoop(descriptor, session); }
        }, "Rabbit button events");
        reader.setDaemon(true);
        reader.start();
    }

    public synchronized void stop() {
        running = false;
        ready = false;
        generation++;
        main.removeCallbacks(heartbeat);
        FileDescriptor fd = input;
        input = null;
        if (fd != null) try { Os.close(fd); } catch (Exception ignored) { }
        File pointer = new File(directory, "hardware-lease");
        try (FileInputStream file = new FileInputStream(pointer)) {
            byte[] bytes = new byte[64]; int count = file.read(bytes);
            if (count > 0 && new String(bytes, 0, count, StandardCharsets.US_ASCII).trim().equals(leaseId))
                pointer.delete();
        } catch (Exception ignored) { }
        if (events != null) events.delete();
        if (commands != null) commands.delete();
        leaseId = null;
        events = null;
        commands = null;
    }

    public boolean isReady() { return ready && running; }

    public boolean send(Command command) {
        if (!running || !ready) return false;
        FileDescriptor output = null;
        try {
            output = Os.open(commands.getAbsolutePath(),
                    OsConstants.O_WRONLY | OsConstants.O_NONBLOCK | OsConstants.O_NOFOLLOW, 0);
            byte[] bytes = (command.name() + "\n").getBytes(StandardCharsets.US_ASCII);
            return Os.write(output, bytes, 0, bytes.length) == bytes.length;
        } catch (Exception ignored) { return false; }
        finally { if (output != null) try { Os.close(output); } catch (Exception ignored) { } }
    }

    private void readLoop(FileDescriptor fd, final int session) {
        byte[] bytes = new byte[256];
        StringBuilder pending = new StringBuilder();
        boolean connected = false;
        StructPollfd poll = new StructPollfd();
        poll.fd = fd;
        poll.events = (short) (OsConstants.POLLIN | OsConstants.POLLHUP | OsConstants.POLLERR);
        StructPollfd[] watched = {poll};
        try {
            while (running && generation == session) {
                if (Os.poll(watched, 500) == 0) continue;
                if (!running || generation != session) return;
                int count;
                try { count = Os.read(fd, bytes, 0, bytes.length); }
                catch (ErrnoException error) {
                    if (error.errno == OsConstants.EAGAIN) { Thread.sleep(15); continue; }
                    throw error;
                }
                if (count == 0) {
                    if (connected) throw new IllegalStateException("Input helper disconnected");
                    Thread.sleep(200); continue;
                }
                pending.append(new String(bytes, 0, count, StandardCharsets.US_ASCII));
                if (pending.length() > 1024) throw new IllegalStateException("Invalid input message");
                int newline;
                while ((newline = pending.indexOf("\n")) >= 0) {
                    final String line = pending.substring(0, newline);
                    pending.delete(0, newline + 1);
                    if (line.equals("READY")) connected = true;
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (!running || generation != session) return;
                            if (line.equals("READY")) {
                                ready = true;
                                main.removeCallbacks(heartbeat);
                                main.post(heartbeat);
                                listener.onReady();
                            } else if (ready && line.startsWith("DOWN ")) {
                                try { listener.onDown(Long.parseLong(line.substring(5))); }
                                catch (NumberFormatException ignored) { }
                            } else if (ready && line.startsWith("UP ")) {
                                try { listener.onUp(Long.parseLong(line.substring(3))); }
                                catch (NumberFormatException ignored) { }
                            }
                        }
                    });
                }
            }
        } catch (final Exception error) {
            main.post(new Runnable() {
                @Override public void run() {
                    if (!running || generation != session) return;
                    android.util.Log.w("RabbitPhoneHardware", "Private control channel closed", error);
                    stop(); listener.onDisconnected();
                }
            });
        }
    }
}
