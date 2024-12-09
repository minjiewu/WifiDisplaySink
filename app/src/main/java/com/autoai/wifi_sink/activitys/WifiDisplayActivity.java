package com.autoai.wifi_sink.activitys;

import android.app.Activity;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.Window;
import android.view.WindowManager;

import com.autoai.wifi_sink.LogUtils;
import com.autoai.wifi_sink.R;
import com.autoai.wifi_sink.display.AudioDataManager;
import com.autoai.wifi_sink.display.RtspSink;
import com.autoai.wifi_sink.display.VideoDataManager;
import com.autoai.wifi_sink.display.VideoResolutionConstant;

public class WifiDisplayActivity extends Activity {

    private final String TAG = WifiDisplayActivity.class.getSimpleName();

    private SurfaceView mSurfaceView = null;
    private Surface mSurface;

    private RtspSink mRtspSink;
    private AudioDataManager audioDataManager;
    private VideoDataManager videoDataManager;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 设置全屏
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        setContentView(R.layout.activity_wifidisplay);
        initView();
        initData();

    }

    @Override
    protected void onResume() {
        super.onResume();
        LogUtils.i(TAG, "onResume");
    }

    @Override
    protected void onPause() {
        super.onPause();
        LogUtils.i(TAG, "onPause");
    }

    private void initView() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        LogUtils.i(TAG, "width=" + width + ", height=" + height);

        mSurfaceView = findViewById(R.id.surface_view);
        mSurfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                LogUtils.i(TAG, "surfaceCreated " + mSurfaceView.getWidth() + " - " + mSurfaceView.getHeight());
                //surface传给VideoDataManager，准备渲染
                mSurface = holder.getSurface();
                videoDataManager.initDecode(mSurface, mSurfaceView.getWidth(), mSurfaceView.getHeight());
                //音频先初始化一下
                audioDataManager.initDecode();
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                LogUtils.i(TAG, "surfaceDestroyed");
                //停止播放
                audioDataManager.stopDecode();
                //VideoDataManager停止渲染
                videoDataManager.stopDecode();
                //断开连接
                mRtspSink.closeRtspSession();
            }
        });
    }

    private void initData() {
        audioDataManager = AudioDataManager.getInstance();
        videoDataManager = VideoDataManager.getInstance();
        String address = getIntent().getStringExtra("address");
        int port = getIntent().getIntExtra("port", 0);
        mRtspSink = RtspSink.getInstance();
        mRtspSink.createRTSPClient(address, port, VideoResolutionConstant.Resolution1);

        mRtspSink.addOnConnectStateListener(stateListener);
    }

    private RtspSink.OnConnectStateListener stateListener = new RtspSink.OnConnectStateListener() {
        @Override
        public void onConnectStateListener(int state) {
            if (state == RtspSink.STATE_RTSP_IDLE
                    || state == RtspSink.STATE_RTSP_CONNECT_FAILED
                    || state == RtspSink.STATE_RTSP_DISCONNECTED) {
                if (!isFinishing()) {
                    finish();
                }
            }
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LogUtils.i(TAG, "onDestroy");
        mRtspSink.removeOnConnectStateListener(stateListener);
    }
}
