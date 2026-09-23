import android.content.om.FabricatedOverlay;
import android.content.om.OverlayIdentifier;
import android.content.om.OverlayInfo;
import android.content.om.OverlayManagerTransaction;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.util.TypedValue;

/** One-shot root tool for the owner's Android 16 R1 clock spacing overlay. */
public final class ClockOverlay {
    private static final String OWNER = "com.android.shell";
    private static final String NAME = "RabbitPhoneClock";
    private static final String TARGET = "com.android.systemui";
    private static final String RESOURCE =
        "com.android.systemui:dimen/keyguard_clock_line_spacing_scale";
    private static final String SIZE_RESOURCE = "com.android.systemui:dimen/small_clock_text_size";
    private static final int USER = 0;

    private static void run(String[] args) throws Exception {
        if (args.length != 0) {
            throw new IllegalArgumentException("ClockOverlay takes no arguments");
        }
        if (Process.myUid() != 0 || !"r1".equals(Build.DEVICE)
                || !"16".equals(Build.VERSION.RELEASE)) {
            throw new IllegalStateException("Requires root on the owner's Android 16 Rabbit R1");
        }

        FabricatedOverlay overlay = new FabricatedOverlay(NAME, TARGET);
        FabricatedOverlay.class.getMethod("setOwningPackage", String.class)
            .invoke(overlay, OWNER);
        // Android 16's float overload is flagged and absent from the SDK stubs.
        // It emits TYPE_FLOAT (0x04) and Float.floatToIntBits(1.0f) (0x3f800000).
        // The deprecated Builder/CLI integer overload rejects TYPE_FLOAT.
        // AOSP android16-release/core/java/android/content/om/FabricatedOverlay.java:494,647
        // idmap2d/Idmap2Service.cpp:306 and libidmap2/FabricatedOverlay.cpp:262,282
        // preserve the float type and raw bits through the native serializer.
        FabricatedOverlay.class.getMethod("setResourceValue", String.class, float.class,
                                          String.class)
            .invoke(overlay, RESOURCE, 1.0f, null);
        // Android 16's dimension overload uses TYPE_DIMENSION and
        // TypedValue.createComplexDimension(value, unit): 68dp becomes 0x00004401.
        // AOSP FabricatedOverlay.java:480,626 and android/util/TypedValue.java:690.
        FabricatedOverlay.class.getMethod("setResourceValue", String.class, float.class,
                                          int.class, String.class)
            .invoke(overlay, SIZE_RESOURCE, 68.0f, TypedValue.COMPLEX_UNIT_DIP, null);
        OverlayIdentifier identifier = overlay.getIdentifier();
        if (!(OWNER + ":" + NAME).equals(identifier.toString())) {
            throw new IllegalStateException("Unexpected overlay identifier: " + identifier);
        }

        // Use the real transaction/Stub APIs; never handcraft Binder parcels.
        Class<?> builderClass = Class.forName("android.content.om.OverlayManagerTransaction$Builder");
        Object builder = builderClass.getConstructor().newInstance();
        builderClass.getMethod("registerFabricatedOverlay", FabricatedOverlay.class)
            .invoke(builder, overlay);
        builderClass.getMethod("setEnabled", OverlayIdentifier.class, boolean.class, int.class)
            .invoke(builder, identifier, true, USER);
        OverlayManagerTransaction transaction = (OverlayManagerTransaction)
            builderClass.getMethod("build").invoke(builder);

        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
            .getMethod("getService", String.class).invoke(null, "overlay");
        if (binder == null) throw new IllegalStateException("Overlay service unavailable");
        Class<?> managerClass = Class.forName("android.content.om.IOverlayManager");
        Object manager = Class.forName("android.content.om.IOverlayManager$Stub")
            .getMethod("asInterface", IBinder.class).invoke(null, binder);
        managerClass.getMethod("commit", OverlayManagerTransaction.class)
            .invoke(manager, transaction);

        OverlayInfo info = (OverlayInfo) managerClass
            .getMethod("getOverlayInfoByIdentifier", OverlayIdentifier.class, int.class)
            .invoke(manager, identifier, USER);
        if (info == null || !TARGET.equals(info.getTargetPackageName())
                || !Boolean.TRUE.equals(OverlayInfo.class.getMethod("isEnabled").invoke(info))) {
            throw new IllegalStateException("Clock overlay commit returned without an enabled overlay");
        }
        System.out.println("CLOCK_OVERLAY_ENABLED " + identifier + " user=" + USER
            + " resource=" + RESOURCE + " type=0x04 data=0x3f800000 value=1.0"
            + " size_resource=" + SIZE_RESOURCE + " size=68dp");
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            System.err.flush();
            System.out.flush();
            System.exit(1);
        }
    }
}
