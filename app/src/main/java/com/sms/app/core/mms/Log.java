package com.sms.app.core.mms;

/**
 * Stands in for android.util.Log in the MMS code taken from AOSP: its debug
 * lines can carry numbers and message text, and this app logs no personal
 * data, so nothing is written.
 */
public final class Log {
    private Log() {}

    public static int v(String tag, String msg) { return 0; }
    public static int v(String tag, String msg, Throwable tr) { return 0; }
    public static int d(String tag, String msg) { return 0; }
    public static int d(String tag, String msg, Throwable tr) { return 0; }
    public static int i(String tag, String msg) { return 0; }
    public static int i(String tag, String msg, Throwable tr) { return 0; }
    public static int w(String tag, String msg) { return 0; }
    public static int w(String tag, String msg, Throwable tr) { return 0; }
    public static int w(String tag, Throwable tr) { return 0; }
    public static int e(String tag, String msg) { return 0; }
    public static int e(String tag, String msg, Throwable tr) { return 0; }
    public static boolean isLoggable(String tag, int level) { return false; }

    public static final int VERBOSE = 2;
    public static final int DEBUG = 3;
}
