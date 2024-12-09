package com.autoai.wifi_sink.display;

import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.support.annotation.MainThread;
import android.support.annotation.WorkerThread;
import android.text.TextUtils;

import com.autoai.wifi_sink.LogUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 解析RTSP协议
 */

public class RtspSink {

    private final String TAG = RtspSink.class.getSimpleName();

    private static RtspSink mRtspSink;

    private final int ACTION_CONNECT_SOCKET = 0;
    private final int ACTION_CLOSE_SOCKET = 1;
    private final int ACTION_CLOSE_RTSP = 2;

    private final int M1 = 1;
    private final int M2 = 2;
    private final int M3 = 3;
    private final int M4 = 4;
    private final int M5 = 5;
    private final int M6 = 6;
    private final int M7 = 7;
    private final int M8 = 8;
    private final int M16 = 16;

    //RTSP协议连接状态
    public final static int STATE_RTSP_IDLE = 0;
    public final static int STATE_RTSP_CONNECTING = 1;
    public final static int STATE_RTSP_CONNECT_SUCCESS = 2;
    public final static int STATE_RTSP_CONNECT_FAILED = 3;
    public final static int STATE_RTSP_DISCONNECTED = 4;

    private HandlerThread handlerThread = new HandlerThread("rtspThread");
    private Handler mTHandler;

    private volatile RTPReceiver rtpReceiver;
    private volatile String mAddress;
    private volatile int mPort;
    private volatile int mWfdVideoResolution = 0;
    private volatile String rtspUrl;
    private volatile String rtspSession;
    private volatile int mStepMessage = 0;

    private int rtspConnectState = STATE_RTSP_IDLE;

    private List<OnConnectStateListener> stateListeners = new ArrayList<>();

    public static RtspSink getInstance() {
        if (mRtspSink == null) {
            synchronized (RtspSink.class) {
                if (mRtspSink == null) {
                    mRtspSink = new RtspSink();
                }
            }
        }
        return mRtspSink;
    }

    private RtspSink() {
        handlerThread.start();
        mTHandler = new Handler(handlerThread.getLooper(), tCallback);
    }

    @MainThread
    @WorkerThread
    public void createRTSPClient(String address, int port,int wfdVideoResolution) {
        LogUtils.d(TAG, "createRTSPClient " + address + ", " + port);
        mAddress = address;
        mPort = port;
        mWfdVideoResolution = wfdVideoResolution;
        mTHandler.sendEmptyMessage(ACTION_CONNECT_SOCKET);
    }

    //断开RTSP连接
    @MainThread
    @WorkerThread
    public void closeRtspSession() {
        LogUtils.d(TAG, "closeRtspSession");
        if (getRtspConnectState() == STATE_RTSP_CONNECT_SUCCESS) {
            new Thread() {
                @Override
                public void run() {
                    try {
                        requestM8();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            }.start();

        }
    }

    @MainThread
    @WorkerThread
    public void close(int status) {
        Message msg = Message.obtain();
        msg.what = ACTION_CLOSE_SOCKET;
        msg.arg1 = status;
        mTHandler.sendMessage(msg);
    }


    /**
     * ---------------------------------------------------------------------------------------------
     * -----------------------------------协议连接以及解析---------------------------------------------
     * ---------------------------------------------------------------------------------------------
     */


    private volatile Socket mSocket = null;
    private volatile BufferedInputStream reader = null;
    private volatile BufferedOutputStream writer = null;

    private Handler.Callback tCallback = new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            switch (msg.what) {
                case ACTION_CONNECT_SOCKET:
                    connectSocket();
                    break;
                case ACTION_CLOSE_SOCKET:
                    closeSocket(msg.arg1);
                    break;
                case ACTION_CLOSE_RTSP:

                    break;
            }
            return true;
        }
    };

    @WorkerThread
    private void connectSocket() {
        LogUtils.i(TAG, "connectSocket");
        if (TextUtils.isEmpty(mAddress) || mPort == 0) {
            close(STATE_RTSP_CONNECT_FAILED);
            return;
        }
        rtpReceiver = new RTPReceiver(new RTPReceiver.OnRTPExceptionListener() {
            @Override
            public void onIOException() {
                close(STATE_RTSP_DISCONNECTED);
            }
        });

        //设置连接中的状态
        setRtspConnectState(STATE_RTSP_CONNECTING);
        try {
            mSocket = new Socket();
            mSocket.connect((new InetSocketAddress(mAddress, mPort)), 3 * 1000);
            //创建IO流
            reader = new BufferedInputStream(mSocket.getInputStream());
            writer = new BufferedOutputStream(mSocket.getOutputStream());
            //读取数据
            readData();
        } catch (Exception e) {
            e.printStackTrace();
            close(STATE_RTSP_CONNECT_FAILED);
        }
    }

    @WorkerThread
    private void resetData() {
        LogUtils.d(TAG, "resetData");
        mAddress = "";
        mPort = 0;
        rtspUrl = "";
        rtspSession = "";
        mStepMessage = 0;
        setRtspConnectState(STATE_RTSP_IDLE);
        if (rtpReceiver != null) {
            rtpReceiver.close();
            rtpReceiver = null;
        }
    }

    @WorkerThread
    private void closeSocket(int status) {
        LogUtils.i(TAG, "closeSocket WifiDisplaySink");
        try {
            if (reader != null) {
                reader.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            reader = null;
        }
        try {
            if (writer != null) {
                writer.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            writer = null;
        }
        try {
            if (mSocket != null) {
                mSocket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            mSocket = null;
        }
        setRtspConnectState(status);
        //重置数据
        resetData();
    }

    public synchronized void addOnConnectStateListener(OnConnectStateListener listener) {
        if (stateListeners.contains(listener)) {
            return;
        }
        stateListeners.add(listener);
    }

    public synchronized void removeOnConnectStateListener(OnConnectStateListener listener) {
        if (!stateListeners.contains(listener)) {
            return;
        }
        stateListeners.remove(listener);
    }

    //设置连接状态
    private synchronized void setRtspConnectState(int state) {
        if (rtspConnectState == state) {
            return;
        }
        LogUtils.getHandler().post(new Runnable() {
            @Override
            public void run() {
                rtspConnectState = state;
                LogUtils.i(TAG, "setConnectState: " + rtspConnectState);
                for (int i = 0; i < stateListeners.size(); i++) {
                    stateListeners.get(i).onConnectStateListener(rtspConnectState);
                }
            }
        });

    }

    //获取连接状态
    public synchronized int getRtspConnectState() {
        return rtspConnectState;
    }

    @WorkerThread
    private void readData() {
        new Thread() {
            @Override
            public void run() {
                mStepMessage = M1;
                byte[] buffered = new byte[2 * 1024];
                String mSourceData = "";
                boolean isReadData = true;
                try {
                    while (isReadData) {
                        int count = reader.read(buffered, 0, buffered.length);
                        LogUtils.i(TAG, "-------------------source data--------------------readCount: " + count);
                        mSourceData = new String(buffered, 0, count);
                        LogUtils.d(TAG, mSourceData);

                        if (mStepMessage == M1) {
                            //相应第一个消息
                            responseM1(getCSeq(mSourceData));
                            //接着发送第二个消息
                            mStepMessage = M2;
                            requestM2(getCSeq(mSourceData));
                            continue;
                        }
                        if (mStepMessage == M2) {
                            //第二个消息source端回复了，并且source端接着发了第三个消息
                            String[] splits = mSourceData.split("\r\n");
                            for (int i = 0; i < splits.length; i++) {
                                if (splits[i].contains("GET_PARAMETER ")) {
                                    mStepMessage = M3;
                                    break;
                                }
                            }
                        }
                        if (mStepMessage == M3) {
                            responseM3(getCSeq(mSourceData));
                            //后面会是第四个消息
                            mStepMessage = M4;
                            continue;
                        }
                        if (mStepMessage == M4) {
                            //第四个消息中，获取地址
                            String[] splits = mSourceData.split("\r\n");
                            for (int i = 0; i < splits.length; i++) {
                                if (splits[i].contains("wfd_presentation_URL")) {
                                    String[] values = splits[i].split(" ");
                                    rtspUrl = values[1];
                                }
                            }
                            responseM4(getCSeq(mSourceData));
                            //后面会是第五个消息
                            mStepMessage = M5;
                            continue;
                        }
                        if (mStepMessage == M5) {
                            //解析第五个消息中的wfd_trigger_method值，如果是 TEARDOWN，表示Source端要断开连接
                            String[] splits = mSourceData.split("\r\n");
                            for (int i = 0; i < splits.length; i++) {
                                if (splits[i].contains("wfd_trigger_method:")) {
                                    String[] values = splits[i].split(" ");
                                    if ("TEARDOWN".equals(values[1])) {
                                        return;
                                    }
                                }
                            }
                            responseM5(getCSeq(mSourceData));
                            //Sink端发送第六个消息
                            mStepMessage = M6;
                            requestM6(getCSeq(mSourceData));
                            continue;
                        }
                        if (mStepMessage == M6) {
                            //第六个消息中，获取Session值
                            String[] splits = mSourceData.split("\r\n");
                            for (int i = 0; i < splits.length; i++) {
                                if (splits[i].contains("Session:")) {
                                    String[] values = splits[i].split(" ");
                                    rtspSession = values[1].split(";")[0];
                                }
//                    else if (splits[i].contains("Transport:")) {
//                        mRtpServerPort = 0;
//                        String value = splits[i];
//                        String substring = value.substring(value.lastIndexOf("=") + 1);
//                        mRtpServerPort = Integer.parseInt(substring);
//                    }
                            }
                            //Sink端发送第七个消息
                            mStepMessage = M7;
                            requestM7(getCSeq(mSourceData));
                            continue;
                        }
                        if (mStepMessage == M7) {
                            if (mSourceData.contains("RTSP/1.0 200 OK")) {
                                setRtspConnectState(STATE_RTSP_CONNECT_SUCCESS);
                                //消息发送完毕，置空
                                mStepMessage = 0;
                                continue;
                            }
                        }

                        //这是心跳包的回复
                        if (mSourceData.contains("GET_PARAMETER ") && mSourceData.contains("Session")) {
                            responseM16(getCSeq(mSourceData));
                            continue;
                        }

                        //断开连接
                        String[] splits = mSourceData.split("\r\n");
                        for (int i = 0; i < splits.length; i++) {
                            if (splits[i].contains("wfd_trigger_method:")) {
                                String[] values = splits[i].split(" ");
                                if ("TEARDOWN".equals(values[1])) {
                                    //Source端要断开连接
                                    isReadData = false;
                                }
                            } else if (splits[i].equals("CSeq: " + M8)) {
                                //Sink端要断开连接
                                isReadData = false;
                            }
                        }
                    }
                    //跳出循环，关闭socket
                    close(STATE_RTSP_DISCONNECTED);
                } catch (Exception e) {
                    e.printStackTrace();
                    close(STATE_RTSP_CONNECT_FAILED);
                }
            }
        }.start();
    }

    private String getCSeq(String data) {
        String value = "0";
        String[] heartSplits = data.split("\r\n");
        for (int i = 0; i < heartSplits.length; i++) {
            if (heartSplits[i].contains("CSeq:")) {
                String[] values = heartSplits[i].split(" ");
                value = values[1];
            }
        }
        return value;
    }

    private String getDate() {
//        %a, %d %b %Y %H:%M:%S %z
        SimpleDateFormat format = new SimpleDateFormat("E, d M yyyy HH:mm:ss Z", Locale.ENGLISH);
        return format.format(new Date());
    }

    private String getUserAgent() {
        StringBuilder sb = new StringBuilder();
        sb.append("stagefright/1.2 (Linux;Android ");
        sb.append(Build.VERSION.RELEASE);
        sb.append(")");
        return sb.toString();
    }

    @WorkerThread
    //M1，响应数据
    private void responseM1(String cseq) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("RTSP/1.0 200 OK\r\n");
        sb.append("User-Agent: " + getUserAgent() + "\r\n");
        sb.append("Date: " + getDate() + "\r\n");
        sb.append("CSeq: " + cseq + "\r\n");
        sb.append("Public: org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER\r\n");
        sb.append("\r\n");

        LogUtils.i(TAG, "-------------------sink data--------------------responseM1");
        LogUtils.d(TAG, sb.toString());

        writer.write(sb.toString().getBytes());
        writer.flush();

    }

    @WorkerThread
    //M2，向source端发送数据
    private void requestM2(String cseq) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("OPTIONS * RTSP/1.0\r\n");
        sb.append("User-Agent: " + getUserAgent() + "\r\n");
        sb.append("Date: " + getDate() + "\r\n");
        sb.append("CSeq: " + cseq + "\r\n");
        sb.append("Require: org.wfa.wfd1.0\r\n");
        sb.append("\r\n");

        LogUtils.i(TAG, "-------------------sink data--------------------requestM2");
        LogUtils.d(TAG, sb.toString());

        writer.write(sb.toString().getBytes());
        writer.flush();
    }

    @WorkerThread
    //M3，响应数据
    private void responseM3(String cseq) throws Exception {
        StringBuilder bodyBuilder = new StringBuilder();
//        bodyBuilder.append("wfd_video_formats: %02x 00 %02x %02x %08x %08x %08x 00 0000 0000 00 none none\r\n");值都是16进制表示
        if (mWfdVideoResolution == VideoResolutionConstant.Resolution1) {
            //1280x720
            bodyBuilder.append("wfd_video_formats: 30 00 02 02 00008c60 00000000 00000000 00 0000 0000 00 none none\r\n");
        } else if (mWfdVideoResolution == VideoResolutionConstant.Resolution2) {
            //1920x1080
            bodyBuilder.append("wfd_video_formats: 38 00 02 02 00017380 00000000 00000000 00 0000 0000 00 none none\r\n");
        }
        //音频是AAC格式的
        bodyBuilder.append("wfd_audio_codecs: AAC 00000001 00\r\n");
        bodyBuilder.append("wfd_client_rtp_ports: RTP/AVP/UDP;unicast " + rtpReceiver.getLocalRTPPort() + " 0 mode=play\r\n");

        StringBuilder responseBuilder = new StringBuilder();
        responseBuilder.append("RTSP/1.0 200 OK\r\n");
        responseBuilder.append("User-Agent: " + getUserAgent() + "\r\n");
        responseBuilder.append("Date: " + getDate() + "\r\n");
        responseBuilder.append("CSeq: " + cseq + "\r\n");
        responseBuilder.append("Content-type: text/parameters\r\n");
        responseBuilder.append("Content-length: " + bodyBuilder.toString().length() + "\r\n");
        responseBuilder.append("\r\n");
        responseBuilder.append(bodyBuilder);

        LogUtils.i(TAG, "-------------------sink data--------------------responseM3");
        LogUtils.d(TAG, responseBuilder.toString());

        writer.write(responseBuilder.toString().getBytes());
        writer.flush();
    }

    @WorkerThread
    //M4，响应数据
    private void responseM4(String cseq) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("RTSP/1.0 200 OK\r\n");
        sb.append("User-Agent: " + getUserAgent() + "\r\n");
        sb.append("Date: " + getDate() + "\r\n");
        sb.append("CSeq: " + cseq + "\r\n");
        sb.append("\r\n");

        LogUtils.i(TAG, "-------------------sink data--------------------responseM4");
        LogUtils.d(TAG, sb.toString());

        writer.write(sb.toString().getBytes());
        writer.flush();
    }

    @WorkerThread
    //M5，响应数据
    private void responseM5(String cseq) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("RTSP/1.0 200 OK\r\n");
        sb.append("CSeq: " + cseq + "\r\n");
        sb.append("\r\n");

        LogUtils.i(TAG, "-------------------sink data--------------------responseM5");
        LogUtils.d(TAG, sb.toString());

        writer.write(sb.toString().getBytes());
        writer.flush();

    }

    @WorkerThread
    //M6，向source端发送数据
    private void requestM6(String cseq) throws Exception {
        //准备接受UDP数据
        rtpReceiver.createRTPServer();

        //发送M6消息给Source端
        int rtpPort = rtpReceiver.getLocalRTPPort();
        StringBuilder sb = new StringBuilder();
        sb.append("SETUP " + rtspUrl + " RTSP/1.0\r\n");
        sb.append("User-Agent: " + getUserAgent() + "\r\n");
        sb.append("Date: " + getDate() + "\r\n");
        sb.append("CSeq: " + cseq + "\r\n");
        sb.append("Transport: RTP/AVP/UDP;unicast;client_port=" + rtpPort + "-" + (rtpPort + 1) + "\r\n");
        sb.append("\r\n");

        LogUtils.i(TAG, "-------------------sink data--------------------requestM6");
        LogUtils.d(TAG, sb.toString());

        writer.write(sb.toString().getBytes());
        writer.flush();
    }

    @WorkerThread
    //M7，向source端发送数据
    private void requestM7(String cseq) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("PLAY " + rtspUrl + " RTSP/1.0\r\n");
        sb.append("User-Agent: " + getUserAgent() + "\r\n");
        sb.append("Date: " + getDate() + "\r\n");
        sb.append("CSeq: " + cseq + "\r\n");
        sb.append("Session: " + rtspSession + "\r\n"); //荣耀手机如果最后一个结尾没有\r\n，会出现M7指令延迟25秒回复的情况
        sb.append("\r\n");

//        sb.append("PLAY rtsp://x.x.x.x:x/wfd1.0/streamid=0 RTSP/1.0" + "\r\n");
//        sb.append("Date: Thu, 31 Oct 2024 09:41:46 +0000" + "\r\n");
//        sb.append("User-Agent: stagefright/1.2 (Linux;Android 9)" + "\r\n");
//        sb.append("CSeq: 3" + "\r\n");
//        sb.append("Session: " + rtspSession + "\r\n");
//        sb.append("\r\n");

        LogUtils.i(TAG, "-------------------sink data--------------------requestM7");
        LogUtils.d(TAG, sb.toString());

        writer.write(sb.toString().getBytes());
        writer.flush();
    }

    @WorkerThread
    //M8指令，断开投屏连接
    private void requestM8() throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("TEARDOWN " + rtspUrl + " RTSP/1.0\r\n");
        sb.append("User-Agent: " + getUserAgent() + "\r\n");
        sb.append("Date: " + getDate() + "\r\n");
        sb.append("CSeq: " + M8 + "\r\n");
        sb.append("Session: " + rtspSession + "\r\n");
        sb.append("\r\n");

        LogUtils.i(TAG, "-------------------sink data--------------------requestM8");
        LogUtils.d(TAG, sb.toString());

        writer.write(sb.toString().getBytes());
        writer.flush();
    }

    @WorkerThread
    //心跳回复
    private void responseM16(String cseq) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("RTSP/1.0 200 OK\r\n");
        sb.append("User-Agent: " + getUserAgent() + "\r\n");
        sb.append("Date: " + getDate() + "\r\n");
        sb.append("CSeq: " + cseq + "\r\n");
        sb.append("\r\n");

        LogUtils.i(TAG, "-------------------sink data--------------------responseM16");
        LogUtils.d(TAG, sb.toString());

        writer.write(sb.toString().getBytes());
        writer.flush();
    }


    public interface OnConnectStateListener {
        void onConnectStateListener(int state);
    }
}
