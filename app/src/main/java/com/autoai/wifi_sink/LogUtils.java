package com.autoai.wifi_sink;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;


public class LogUtils {

    private static Context mContext;
    private static final Handler mHandler;

    private static String sTag = "sink";
    private static boolean sIsDebug = true;
    private static boolean sIsTrace = true;

    static {
        mHandler = new Handler(Looper.getMainLooper());
    }

    public static void setContext(Context context) {
        mContext = context;
    }

    public static Context getContext() {
        return mContext;
    }

    public static Handler getHandler() {
        return mHandler;
    }

    public static void i(Object msg) {
        if (sIsDebug) {
            Log.i(sTag, getLogString() + msg);
        }
    }

    public static void i(String tag, Object msg) {
        if (sIsDebug) {
            Log.i(sTag + "-" + tag, msg.toString());
        }
    }

    public static void d(Object msg) {
        if (sIsDebug) {
            Log.w(sTag, getLogString() + msg);
        }
    }

    public static void d(String tag, Object msg) {
        if (sIsDebug) {
            Log.w(sTag + "-" + tag, msg.toString());
        }
    }

    public static void w(Object msg) {
        if (sIsDebug) {
            Log.w(sTag, getLogString() + msg);
        }
    }

    public static void w(String tag, Object msg) {
        if (sIsDebug) {
            Log.w(sTag + "-" + tag, msg.toString());
        }
    }

    public static void e(Object msg) {
        if (sIsDebug) {
            Log.e(sTag, getLogString() + msg);
        }
    }

    public static void e(String tag, Object msg) {
        if (sIsDebug) {
            Log.e(sTag + "-" + tag, msg.toString());
        }
    }

    private static String getLogString() {
        if (sIsDebug) {
            StackTraceElement[] list = Thread.currentThread().getStackTrace();
            StringBuilder sb = new StringBuilder();
            if (sIsTrace) {
                sb.append("[");
                if (list.length > 4) {
                    sb.append(list[4].getClassName()).append("-").append(list[4].getMethodName()).append("(").append(list[4].getLineNumber()).append(")");
                }
//                if (list.length > 5) {
//                    sb.append(list[5].getClassName()).append("-").append(list[5].getMethodName()).append("(").append(list[5].getLineNumber()).append(")");
//                }
                return sb.append("] ").toString();
            }
        }
        return "";
    }
}
