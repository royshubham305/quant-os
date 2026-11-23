package org.torproject.jni;

/*
 * Holder for the libtor JNI entry points. libtor.so resolves these by the
 * exact package, class and field names below, so none of them may be renamed.
 * dev.quant.os.TorEngine owns the thread and the lifecycle.
 */
public final class TorService {
    private long torConfiguration = -1;
    private int torControlFd = -1;

    static {
        System.loadLibrary("tor");
    }

    public native boolean createTorConfiguration();

    public native boolean mainConfigurationSetCommandLine(String[] args);

    public native int runMain();

    public native void mainConfigurationFree();
}
