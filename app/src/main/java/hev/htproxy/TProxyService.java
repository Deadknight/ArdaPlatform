package hev.htproxy;

/**
 * JNI binding shipped inside hev-socks5-tunnel.aar.
 *
 * src/hev-jni.c registers its natives to this exact class when the library is
 * loaded (JNI_OnLoad -> FindClass(PKGNAME "/" CLSNAME) + RegisterNatives), with
 * PKGNAME/CLSNAME defaulting to hev/htproxy + TProxyService. Renaming or
 * repackaging this class therefore requires rebuilding the library with other
 * PKGNAME/CLSNAME values (see Application.mk) instead of editing this file.
 */
public final class TProxyService {
    private static final boolean LIBRARY_LOADED;
    private static final String LIBRARY_LOAD_ERROR;

    private TProxyService() {
    }

    static {
        boolean loaded = false;
        String error = "";
        try {
            System.loadLibrary("hev-socks5-tunnel");
            loaded = true;
        } catch (Throwable t) {
            error = t.getClass().getName() + ":" +
                    (t.getMessage() != null ? t.getMessage() : "no-message");
        }
        LIBRARY_LOADED = loaded;
        LIBRARY_LOAD_ERROR = error;
    }

    public static boolean isLibraryLoaded() {
        return LIBRARY_LOADED;
    }

    public static String getLibraryLoadError() {
        return LIBRARY_LOAD_ERROR;
    }

    public static native boolean TProxyStartService(String config_path, int fd);

    public static native boolean TProxyStopService();

    public static native boolean TProxyIsRunning();

    public static native long[] TProxyGetStats();
}
