import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import org.json.JSONObject;

/** Read-only status of one root-owned APK install on the owner's R1. */
public final class InstallSessionStatus {
    public static void main(String[] args) {
        try {
            if ((args.length != 2 && args.length != 3) || Process.myUid() != 0 || !"r1".equals(Build.DEVICE)
                    || !"16".equals(Build.VERSION.RELEASE)) {
                throw new IllegalStateException("Requires session ID/package and root on Android 16 R1");
            }
            int id = Integer.parseInt(args[0]);
            if (id <= 0) throw new IllegalArgumentException("Invalid session ID");
            boolean staged = args.length == 3;
            if (staged && (!"staged".equals(args[2]) || !"com.android.systemui".equals(args[1]))) {
                throw new IllegalArgumentException("Staged status is restricted to SystemUI");
            }
            IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "package");
            if (binder == null) throw new IllegalStateException("Package service unavailable");
            Class<?> pmClass = Class.forName("android.content.pm.IPackageManager");
            Object pm = Class.forName("android.content.pm.IPackageManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
            Object installer = pmClass.getMethod("getPackageInstaller").invoke(pm);
            PackageInstaller.SessionInfo info = (PackageInstaller.SessionInfo)
                Class.forName("android.content.pm.IPackageInstaller")
                .getMethod("getSessionInfo", int.class).invoke(installer, id);
            // Root owns these sessions, so package-visibility filtering cannot hide them.
            // Android 16 PackageInstallerService removes non-staged sessions only after
            // completion/abandonment; a present session never proves completion.
            if (info != null && (info.getInstallerUid() != 0 || info.isStaged() != staged
                    || !args[1].equals(info.getAppPackageName()))) {
                throw new IllegalStateException("Session owner/package/type mismatch");
            }
            if (staged) {
                String state = info == null ? "ABSENT" : info.isStagedSessionFailed() ? "FAILED"
                    : info.isStagedSessionApplied() ? "APPLIED"
                    : info.isStagedSessionReady() ? "READY" : "PENDING";
                JSONObject result = new JSONObject();
                result.put("session_id", id);
                result.put("state", state);
                if ("FAILED".equals(state)) {
                    result.put("error_code", info.getStagedSessionErrorCode());
                    result.put("error_message", info.getStagedSessionErrorMessage());
                }
                System.out.println(result.toString());
            } else if (info == null) {
                System.out.println("ABSENT " + id);
            } else {
                System.out.println("PRESENT " + id);
            }
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
