package com.autoai.wifi_sink.display;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.view.Surface;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * 拿到视频的裸流后，使用MediaCodec解码，输出到surface中
 */

public class VideoDataManager {

    private final String TAG = VideoDataManager.class.getSimpleName();

    private static final int MEDIA_INIT = 1;
    private static final int MEDIA_DECODE = 2;
    private static final int MEDIA_RELEASE = 3;

    private static VideoDataManager instance;

    private HandlerThread mHThread = new HandlerThread("video_data_thread");
    private Handler tHandler;

    private volatile Surface mSurface;
    private volatile int mWidth;
    private volatile int mHeight;

    private volatile MediaCodec mMediaCodec;

    private FileOutputStream outputStream = null;

    public static VideoDataManager getInstance() {
        if (instance == null) {
            synchronized (VideoDataManager.class) {
                if (instance == null) {
                    instance = new VideoDataManager();
                }
            }
        }
        return instance;
    }

    private VideoDataManager() {
        mHThread.start();
        tHandler = new Handler(mHThread.getLooper(), tCallback);
    }

    private Handler.Callback tCallback = new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            if (msg.what == MEDIA_INIT) {
                //创建配置
                MediaFormat mediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, mWidth, mHeight);
                //设置解码预期的帧速率【以帧/秒为单位的视频格式的帧速率的键】
                //mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 60);
                //创建解码器 H264的Type为avc
                try {
                    mMediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                    //配置绑定mediaFormat和surface
                    mMediaCodec.configure(mediaFormat, mSurface, null, 0);
                    mMediaCodec.start();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else if (msg.what == MEDIA_DECODE) {
                Bundle bundle = msg.getData();
                byte[] bytes = bundle.getByteArray("bytes");
                long pts = bundle.getLong("pts");
                long dts = bundle.getLong("dts");
                try {
                    decodeVideoData(pts, dts, bytes);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else if (msg.what == MEDIA_RELEASE) {
                if (mMediaCodec != null) {
                    mMediaCodec.release();
                    mMediaCodec = null;
                }
            }

            return true;
        }
    };

    //初始化数据
    public void initDecode(Surface surface, int width, int height) {
        mSurface = surface;
        mWidth = width;
        mHeight = height;
        tHandler.removeCallbacksAndMessages(null);
        tHandler.sendEmptyMessage(MEDIA_INIT);
    }

    //停止解码
    public void stopDecode() {
        mSurface = null;
        tHandler.removeCallbacksAndMessages(null);
        tHandler.sendEmptyMessage(MEDIA_RELEASE);
    }

    //数据放到handler队列中
    void processVideoData(long pts, long dts, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return;
        }
//        try {
//            outputStream.write(bytes);
//        } catch (IOException e) {
//            throw new RuntimeException(e);
//        }
        Message message = Message.obtain();
        message.what = MEDIA_DECODE;
        Bundle bundle = message.getData();
        bundle.putLong("pts", pts);
        bundle.putLong("dts", dts);
        bundle.putByteArray("bytes", bytes);
        tHandler.sendMessage(message);
    }

    private MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

    //解码数据
    private void decodeVideoData(long pts, long dts, byte[] bytes) {
        //LogUtils.i(TAG, "video decode bytes.size = " + bytes.length + " - " + pts);
        if (mMediaCodec == null) {
            return;
        }
        //从输入队列中获取数据索引，100ms超时(微妙为单位)
        int inIndex = mMediaCodec.dequeueInputBuffer(100 * 1000);
        if (inIndex >= 0) {
            //根据返回的index拿到可以用的buffer
            ByteBuffer byteBuffer = mMediaCodec.getInputBuffer(inIndex);
            //清空缓存
            byteBuffer.clear();
            //开始为buffer填充数据
            byteBuffer.put(bytes);
            //将输入buffer放入队列
            mMediaCodec.queueInputBuffer(inIndex, 0, bytes.length, 0, 0);
        }

        int outIndex = mMediaCodec.dequeueOutputBuffer(info, 100 * 1000);
        if (outIndex >= 0) {
            //如果surface绑定了，则直接输入到surface渲染并释放
            mMediaCodec.releaseOutputBuffer(outIndex, true);
        }

    }

}
