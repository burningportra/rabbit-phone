#!/system/bin/sh
# A finite one-shot after boot, because SystemUI recreates its dynamic overlays.
# No user input, arbitrary command API, network listener, or wake lock.
THEME=/data/system/rabbit-phone-theme
[ -f "$THEME/enabled" ] || exit 0

apply_one() {
    [ -f "$THEME/enabled" ] || return 1
    # An independently removed optional app must not block other packages.
    if [ "$1" != android ] && ! pm path "$1" >/dev/null 2>&1; then
        return 0
    fi
    [ -r "$THEME/$2.xml" ] || return 1
    cmd overlay fabricate --target "$1" --name "$2" --file "$THEME/$2.xml" || return 1
    cmd overlay enable --user 0 "com.android.shell:$2"
}

# Let SystemUI finish loading its settings and wallpaper colors first.
sleep 3
old_spacing=$(cmd overlay lookup com.android.systemui com.android.systemui:dimen/keyguard_clock_line_spacing_scale)
old_size=$(cmd overlay lookup com.android.systemui com.android.systemui:dimen/small_clock_text_size)
reload_clock=0
if [ "$old_spacing" != 1.0 ] || [ "$old_size" != 68.0dip ]; then
    reload_clock=1
fi
attempt=0
while [ "$attempt" -lt 3 ]; do
    [ -f "$THEME/enabled" ] || exit 0
    failed=0
    if CLASSPATH=/data/local/rabbit-phone/clock-overlay.jar app_process /system/bin ClockOverlay; then
        # The persistent clock reads dimensions only when its view is created.
        # Android restarts this exact persistent UI process; its keyguard remains
        # the system keyguard. Never kill another process or retry the restart.
        if [ "$reload_clock" = 1 ]; then
            ui_pid=$(pidof com.android.systemui)
            case "$ui_pid" in
                ''|*[!0-9]*) exit 1 ;;
            esac
            kill -TERM "$ui_pid" || exit 1
            reload_clock=0
            sleep 3
        fi
    else
        failed=1
    fi
    cmd overlay fabricate --target android --name RabbitPhoneLegacyBar --config hdpi \
        android:drawable/ab_solid_light_holo color 0xff0a0a09 || failed=1
    cmd overlay enable --user 0 com.android.shell:RabbitPhoneLegacyBar || failed=1
    apply_one android RabbitPhonePalette || failed=1
    apply_one com.android.calendar RabbitPhoneCalendar || failed=1
    apply_one com.android.contacts RabbitPhoneContacts || failed=1
    apply_one com.cipheros.messaging RabbitPhoneMessaging || failed=1
    apply_one org.fdroid.fdroid RabbitPhoneFDroid || failed=1
    sleep 2
    primary=$(cmd overlay lookup android android:color/system_primary_dark)
    background=$(cmd overlay lookup android android:color/system_background_dark)
    if [ "$failed" = 0 ] && [ "$primary" = '#ffff5a1f' ] && [ "$background" = '#ff0a0a09' ]; then
        exit 0
    fi
    attempt=$((attempt + 1))
done
exit 1
