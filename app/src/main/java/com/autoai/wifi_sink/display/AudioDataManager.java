package com.autoai.wifi_sink.display;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;

import com.autoai.wifi_sink.LogUtils;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * 拿到音频的裸流后，使用MediaCodec解码，AudioTrack播放
 */

public class AudioDataManager implements AudioManager.OnAudioFocusChangeListener {

    private final String TAG = AudioDataManager.class.getSimpleName();

    private static final int MEDIA_INIT = 1;
    private static final int MEDIA_DECODE = 2;
    private static final int MEDIA_RELEASE = 3;

    private static AudioDataManager instance;

    private volatile AudioManager mAudioManager = null;
    private volatile AudioTrack mAudioTrack = null;
    private volatile MediaCodec mMediaCodec = null;
    private volatile boolean isInitDecode;

    private HandlerThread mHThread = new HandlerThread("audio_data_thread");
    private Handler tHandler;

    private FileOutputStream outputStream = null;

    public static AudioDataManager getInstance() {
        if (instance == null) {
            synchronized (AudioDataManager.class) {
                if (instance == null) {
                    instance = new AudioDataManager();
                }
            }
        }
        return instance;
    }

    private AudioDataManager() {
        mHThread.start();
        tHandler = new Handler(mHThread.getLooper(), tCallback);
    }

    private Handler.Callback tCallback = new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            if (msg.what == MEDIA_INIT) {
                isInitDecode = true;

            } else if (msg.what == MEDIA_DECODE) {
                Bundle bundle = msg.getData();
                byte[] bytes = bundle.getByteArray("bytes");
                long pts = bundle.getLong("pts");
                long dts = bundle.getLong("dts");
                try {
                    decodeAudioData(pts, dts, bytes);
                } catch (Exception e) {
                    e.printStackTrace();
                    tHandler.removeCallbacksAndMessages(null);
                    tHandler.sendEmptyMessage(MEDIA_RELEASE);
                    tHandler.sendEmptyMessage(MEDIA_INIT);
                }
            } else if (msg.what == MEDIA_RELEASE) {
                if (mMediaCodec != null) {
                    mMediaCodec.release();
                    mMediaCodec = null;
                }
                isInitDecode = false;
            }

            return true;
        }
    };

    public void initDecode() {
        tHandler.removeCallbacksAndMessages(null);
        tHandler.sendEmptyMessage(MEDIA_INIT);
    }

    //停止解码
    public void stopDecode() {
        tHandler.removeCallbacksAndMessages(null);
        tHandler.sendEmptyMessage(MEDIA_RELEASE);
    }

    //初始化数据
    private void initData(int profile, int sampleRate, int channelCount) {
        int sampleRateValue;
        if (sampleRate == 0) {
            sampleRateValue = 96000;
        } else if (sampleRate == 1) {
            sampleRateValue = 88000;
        } else if (sampleRate == 2) {
            sampleRateValue = 64000;
        } else if (sampleRate == 3) {
            sampleRateValue = 48000;
        } else if (sampleRate == 4) {
            sampleRateValue = 44100;
        } else {
            sampleRateValue = 32000;
        }

        MediaFormat mediaFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRateValue, channelCount);
        mediaFormat.setInteger(MediaFormat.KEY_IS_ADTS, 1);
        //必须要设置csd-0的值，Mediacodec需要这些特定值
        //csd_0的前5位是版本号（0x01是AAC_Main,0x02是AAC_LC,0x3是AAC_SSR），然后4位是采样率（0x04是44100, 0x03是48000, 0x01是88200，0x00是96000），然后4位是信道数（ 0x01单声道，0x02双声道），后三位固定是0，
        int scd0 = ((profile & 0x1F) << 11) | ((sampleRate & 0xF) << 7) | ((channelCount & 0xF) << 3);
        byte[] bytes = ByteBuffer.allocate(4).putInt(scd0).array();
        ByteBuffer buffer = ByteBuffer.wrap(new byte[]{bytes[2], bytes[3]});
        mediaFormat.setByteBuffer("csd-0", buffer);
        try {
            mMediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            mMediaCodec.configure(mediaFormat, null, null, 0);
            mMediaCodec.start();
        } catch (IOException e) {
            e.printStackTrace();
        }

        int channelConfig = 0;
        if (channelCount == 1) {
            channelConfig = AudioFormat.CHANNEL_OUT_MONO;
        } else if (channelCount == 2) {
            channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
        }
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        int trackBuffSize = AudioTrack.getMinBufferSize(sampleRateValue, channelConfig, audioFormat);
        mAudioManager = (AudioManager) LogUtils.getContext().getSystemService(Context.AUDIO_SERVICE);
        mAudioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRateValue, channelConfig,
                audioFormat, trackBuffSize, AudioTrack.MODE_STREAM);
        play();
    }

    //数据放到handler队列中
    void processAudioData(long pts, long dts, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return;
        }
//        try {
//            if (outputStream == null) {
//                File file = new File(LogUtils.getContext().getExternalCacheDir(), "audio.aac");
//                if (!file.exists()) {
//                    file.createNewFile();
//                }
//                outputStream = new FileOutputStream(file, false);
//            }
//            outputStream.write(bytes);
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
        Message message = Message.obtain();
        message.what = MEDIA_DECODE;
        Bundle bundle = message.getData();
        bundle.putLong("pts", pts);
        bundle.putLong("dts", dts);
        bundle.putByteArray("bytes", bytes);
        tHandler.sendMessage(message);
    }

    private MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

    //解码数据
    private void decodeAudioData(long pts, long dts, byte[] bytes) {
        //LogUtils.i(TAG, "audio decode bytes.size = " + bytes.length + " - " + pts);
        int profile = (bytes[2] >> 6) & 0x3;
        int sf = (bytes[2] >> 2) & 0xF;
        int cc = ((bytes[2] & 0x1) << 2) | ((bytes[3] >> 6) & 0x3);
        //LogUtils.i(TAG, "profile: " + profile + " - sf: " + sf + " - cc: " + cc);

        if (mMediaCodec == null && isInitDecode) {
            //profile的值等于 Audio Object Type的值减1，这里把AudioObjectType值传过去
            initData((profile + 1), sf, cc);
        }
        if (!isPlay()) {
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

        int outIndex = mMediaCodec.dequeueOutputBuffer(bufferInfo, 100 * 1000);
        if (outIndex >= 0) {
            ByteBuffer outputBuffer = mMediaCodec.getOutputBuffer(outIndex);
            byte[] chunkPCM = new byte[bufferInfo.size];
            outputBuffer.get(chunkPCM);
            //数据取出后一定记得清空此Buffer MediaCodec是循环使用这些Buffer的，不清空下次会得到同样的数
            outputBuffer.clear();
            // 播放解码后的PCM数据
            mAudioTrack.write(chunkPCM, 0, chunkPCM.length);
            //如果surface绑定了，则直接输入到surface渲染并释放
            mMediaCodec.releaseOutputBuffer(outIndex, false);
        }
    }

    private void play() {
        if (!requestAudioFocus()) {
            return;
        }
        if (mAudioTrack.getState() == AudioTrack.STATE_INITIALIZED
                && mAudioTrack.getPlayState() == AudioTrack.PLAYSTATE_STOPPED) {
            mAudioTrack.play();
        }
    }

    private boolean isPlay() {
        return mAudioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING;
    }

    private void pause() {
        if (mAudioTrack.getState() == AudioTrack.STATE_INITIALIZED) {
            mAudioTrack.pause();
        }
    }

    private boolean isPause() {
        return mAudioTrack.getPlayState() == AudioTrack.PLAYSTATE_PAUSED;
    }

    private void stop() {
        if (mAudioTrack.getState() == AudioTrack.STATE_INITIALIZED) {
            mAudioTrack.stop();
        }
    }

    private boolean isStop() {
        return mAudioTrack.getPlayState() == AudioTrack.PLAYSTATE_STOPPED;
    }

    private AudioFocusRequest mAudioFocusRequest = null;
    private boolean isTransient; //短暂失去焦点，比如来电
    private boolean isTransientCanDuck; //是否是瞬间失去焦点,比如通知

    //获取音频焦点
    private boolean requestAudioFocus() {
        // AudioAttributes Usage参照上表，替换为对应音源
        AudioAttributes.Builder builder = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA);
        AudioAttributes attributes = builder.build();
        // mAudioFocusRequest
        mAudioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(this)
                .build();
        int audioFocus = mAudioManager.requestAudioFocus(mAudioFocusRequest);
        LogUtils.i(TAG, "requestAudioFocus audioFocus: " + audioFocus);
        boolean isSuccess = (audioFocus == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        return isSuccess;
    }

    //释放音频焦点
    private void abandonAudioFocus() {
        LogUtils.i(TAG, "abandonAudioFocus");
        pause();
        if (mAudioFocusRequest != null) {
            mAudioManager.abandonAudioFocusRequest(mAudioFocusRequest);
            mAudioFocusRequest = null;
        }
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        LogUtils.i(TAG, "onFocusChange focusChange: " + focusChange);
        switch (focusChange) {
            // 重新获得焦点
            case AudioManager.AUDIOFOCUS_GAIN:
                LogUtils.i(TAG, "onFocusChange AUDIOFOCUS_GAIN");
                //是否是短暂失去焦点
                if (isTransient) {
                    // 通话结束，恢复播放
                    if (isPause()) {
                        play();
                    }
                    isTransient = false;
                }
                //是否是瞬间失去焦点
                if (isTransientCanDuck) {
                    isTransientCanDuck = false;
                }
                break;
            // 永久丢失焦点，如被其他播放器抢占
            case AudioManager.AUDIOFOCUS_LOSS:
                LogUtils.i(TAG, "onFocusChange AUDIOFOCUS_LOSS");
                abandonAudioFocus();
                break;
            // 短暂丢失焦点，如来电
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                LogUtils.i(TAG, "onFocusChange AUDIOFOCUS_LOSS_TRANSIENT");
                if (isPlay()) {
                    isTransient = true;
                    pause();
                }
                break;
            // 瞬间丢失焦点，如通知
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                LogUtils.i(TAG, "onFocusChange AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK");
                if (isPlay()) {
                    isTransientCanDuck = true;
                }
                break;
            default:
                break;
        }
    }
}
