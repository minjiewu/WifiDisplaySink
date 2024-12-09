package com.autoai.wifi_sink.display;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.support.annotation.MainThread;
import android.support.annotation.WorkerThread;

import com.autoai.wifi_sink.LogUtils;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 接收RTP协议传递过来的数据，根据TS协议，把音视频的数据都解析出来
 */

class RTPReceiver {

    private final String TAG = RTPReceiver.class.getSimpleName();

    private final int ACTION_CREATE_SOCKET = 0;
    private final int ACTION_CLOSE_SOCKET = 1;

    private volatile int mLocalRTPPort;
    private volatile DatagramSocket socket;
    private volatile OnRTPExceptionListener mUdpDataListener;

    private HandlerThread handlerThread = new HandlerThread("rtpThread");
    private Handler mTHandler;

    private VideoDataManager videoDataManager;
    private AudioDataManager audioDataManager;

    public RTPReceiver(OnRTPExceptionListener udpDataListener) {
        mUdpDataListener = udpDataListener;
        mLocalRTPPort = getPickRandomRTPPort();
        handlerThread.start();
        mTHandler = new Handler(handlerThread.getLooper(), callback);
        videoDataManager = VideoDataManager.getInstance();
        audioDataManager = AudioDataManager.getInstance();

    }

    private int getPickRandomRTPPort() {
        // Pick an even integer in range [1024, 65534)
        int kRange = 2048 + ((int) (Math.random() * 62000));
        return kRange;
    }

    @MainThread
    @WorkerThread
    int getLocalRTPPort() {
        return mLocalRTPPort;
    }

    @MainThread
    @WorkerThread
    void createRTPServer() {
        mTHandler.removeCallbacksAndMessages(null);
        mTHandler.sendEmptyMessage(ACTION_CREATE_SOCKET);
    }

    @MainThread
    @WorkerThread
    void close() {
        mTHandler.removeCallbacksAndMessages(null);
        mTHandler.sendEmptyMessage(ACTION_CLOSE_SOCKET);
    }


    /**
     * ---------------------------------------------------------------------------------------------
     * -----------------------------------协议连接以及解析---------------------------------------------
     * ---------------------------------------------------------------------------------------------
     */


    private Handler.Callback callback = new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            switch (msg.what) {
                case ACTION_CREATE_SOCKET:
                    createDatagramSocket();
                    break;
                case ACTION_CLOSE_SOCKET:
                    closeSocket();
                    break;
            }
            return true;
        }

    };

    private synchronized void exceptionRTP() {
        if (mUdpDataListener != null) {
            mUdpDataListener.onIOException();
        }
    }

    @WorkerThread
    private void createDatagramSocket() {
        LogUtils.d(TAG, "createDatagramSocket");
        if (socket != null) {
            return;
        }
        try {
            // 创建一个 UDP 套接字，并绑定到指定端口
            socket = new DatagramSocket(mLocalRTPPort);
            socket.setSoTimeout(5 * 1000); // 设置超时时间
            //开始接收数据
            receiveUdpData();
        } catch (Exception e) {
            e.printStackTrace();
            exceptionRTP();
        }
    }

    @WorkerThread
    private void receiveUdpData() {
        new ReceiveThread().start();
        new ParseTsThread().start();
    }

    private LinkedBlockingQueue queue = new LinkedBlockingQueue();

    private class ReceiveThread extends Thread {

        @Override
        public void run() {
            byte[] buffer = new byte[1536];//1.5k
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                while (socket != null) {
                    socket.receive(packet);
                    byte[] bytes = new byte[packet.getLength()];
                    System.arraycopy(packet.getData(), 0, bytes, 0, bytes.length);
                    queue.offer(bytes);
                }
            } catch (Exception e) {
                e.printStackTrace();
                exceptionRTP();
            }
        }

    }

    private class ParseTsThread extends Thread {

        @Override
        public void run() {
            try {
                while (socket != null) {
                    byte[] bytes = (byte[]) queue.take();

                    //解析RTP协议
                    if (parseRTP(bytes, bytes.length)) {
                        //解析TS包数据
                        parseTS();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private byte[] tsBuffers = null;
        //TS包标准长度值
        private int tsPackageLen = 188;
        //单个TS包数据
        private byte[] tsPackageBuffer = new byte[tsPackageLen];

        private int pmt_pid = -1;
        private int video_pid = -1;
        private int audio_pid = -1;
        private long videoPts = -1;
        private long videoDts = -1;
        private long audioPts = -1;
        private int video_es_data_length = 0;

        private ByteArrayOutputStream videoBuffer = new ByteArrayOutputStream();

        private ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream();

        //解析RTP协议的头部信息,占12个字节
        @WorkerThread
        private boolean parseRTP(byte[] buffer, int readSizes) {
            //不是RTP协议
            if (readSizes < 12) {
                return false;
            }
            //RTP协议版本号
            int version = (buffer[0] >> 6) & 0x03;
            //RTP协议版本不是2的话，continue
            if (version != 2) {
                LogUtils.i(TAG, "RTP version not 2, continue;");
                return false;
            }
            //填充标志，占1位，如果P=1，则在该报文的尾部填充一个或多个额外的八位组，它们不是有效载荷的一部分。
            int padding = (buffer[0] >> 5) & 0x01;
            if (padding == 1) {
                int paddingLength = buffer[readSizes - 1] & 0xFF;
                if (paddingLength + 12 > readSizes) {
                    return false;
                }
                readSizes -= paddingLength;
            }
            //扩展标志，占1位，如果X=1，则在RTP报头后跟有一个扩展报头
            int extension = (buffer[0] >> 4) & 0x01;
            //CSRC计数器，占4位，指示CSRC 标识符的个数,每个CSRC标识符占32位，可以有0～15个。
            int cc = buffer[0] & 0x0F;
            int payloadOffset = 12 + 4 * cc;
            if (readSizes < payloadOffset) {
                return false;
            }
            //标记，占1位，不同的有效载荷有不同的含义，对于视频，标记一帧的结束；对于音频，标记会话的开始。
            int mark = buffer[1] >> 7 & 0x1;
            //有效荷载类型，占7位，用于说明RTP报文中有效载荷的类型
            int pt = buffer[1] & 0x7F;
            //序列号：占16位，用于标识发送者所发送的RTP报文的序列号，每发送一个报文，序列号增1。这个字段当下层的承载协议用UDP的时候，网络状况不好的时候可以用来检查丢包。同时出现网络抖动的情况可以用来对数据进行重新排序
            int sn = ((buffer[2] << 8) & 0xFFFF) | (buffer[3] & 0xFF);
            // 时戳(Timestamp)：占32位，必须使用90 kHz 时钟频率。时戳反映了该RTP报文的第一个八位组的采样时刻。接收者使用时戳来计算延迟和延迟抖动，并进行同步控制。
            int time = ((buffer[4] & 0xFF) << 24) | ((buffer[5] & 0xFF) << 16) | ((buffer[6] & 0xFF) << 8) | (buffer[7] & 0xFF);
            //同步信源(SSRC)标识符：占32位，用于标识同步信源。该标识符是随机选择的，参加同一视频会议的两个同步信源不能有相同的SSRC。
            int ssrc = ((buffer[8] & 0xFF) << 24) | ((buffer[9] & 0xFF) << 16) | ((buffer[10] & 0xFF) << 8) | (buffer[11] & 0xFF);
            //LogUtils.i(TAG, "version=" + version + ", padding=" + padding + ", extension=" + extension + ", cc=" + cc);
            //LogUtils.i(TAG, "mark=" + mark + ", pt=" + pt + ", sn=" + sn + ", time=" + time + ", ssrc=" + ssrc);

            tsBuffers = new byte[readSizes - payloadOffset];
            System.arraycopy(buffer, payloadOffset, tsBuffers, 0, tsBuffers.length);

            return true;
        }

        //解析MPEG-2 TS 数据
        @WorkerThread
        private boolean parseTS() {
            if (tsBuffers == null) {
                return false;
            }
            int tsSize = tsBuffers.length;
            //不是标准的TS包
            if ((tsSize > 0) && (tsSize % tsPackageLen != 0)) {
                return false;
            }
            //获取到TS包的数量
            int tsPackageCount = tsSize / tsPackageLen;
            for (int i = 0; i < tsPackageCount; i++) {
                //分割成一个个TS数据包
                System.arraycopy(tsBuffers, (i * tsPackageLen), tsPackageBuffer, 0, tsPackageLen);
                //开始解析TS包数据，头部数据占4个字节
                //同步字节(8b),表示TS包的开头，值固定为0x47
                int syncByte = tsPackageBuffer[0] & 0xFF;
                //传输错误指示位(1b)（Transport Error Indicator）,值为1时，表示在相关的传送包中至少有一个不可纠正的错误位
                int tei = (tsPackageBuffer[1] >> 7) & 0x1;
                //Payload单元开始指示位(1b)（Payload Unit Start Indicator）该字段用来表示有效Payload中带有PES包或PSI数据，也代表一个完整的音视频数据帧的开始
                int pusi = (tsPackageBuffer[1] >> 6) & 0x1;
                //传输优先级(1b)（Transport Priority）,值为1时，表示此包在相同PID的分组中具有更高的优先级
                int tp = (tsPackageBuffer[1] >> 5) & 0x1;
                //分组ID(13b)（PID）,用于识别TS分组的ID，音视频流分别对应不同的PID
                int pid = ((tsPackageBuffer[1] & 0x1F) << 8) | (tsPackageBuffer[2] & 0xFF);
                //传输加扰控制(2b)（Transport Scrambling control）,值为0时表示Payload未加密，Miracast中一般为0
                int tsc = (tsPackageBuffer[3] >> 6) & 0x3;
                //适配域存在标志(2b)（adaptation_field_control）,表示在包头后面是否有适配域或Payload，其中1代表仅有载荷，2代表仅有适配域，3代表适配域和载荷都存在
                int afc = (tsPackageBuffer[3] >> 4) & 0x3;
                //连续性计数器(4b)（Continuity counter）,对于具有相同PID值的Payload而言，从0~15连续循环，用来检测是否有丢失的TS包
                int conc = tsPackageBuffer[3] & 0xF;
                //LogUtils.i(TAG, "pid=" + pid + ", pusi=" + pusi + ", afc=" + afc + ", conc=" + conc);

                //------------------------------头部的4个字节的数据解析完成-------------------------------

                //解析适配域
                int skipByte = 4;
                if (afc == 2 || afc == 3) {
                    skipByte += parseAdaptationField(tsPackageBuffer);
                }
                //LogUtils.i(TAG, "skipByte=" + skipByte);
                //适配域长度加上TS头，如果超过188，continue
                if (skipByte >= tsPackageLen) {
                    continue;
                }

                if (pid == 0) {
                    parseProgramAssociationTable(pusi, skipByte, tsPackageBuffer);
                } else {
                    if (pid == pmt_pid) {
                        //解析PMT
                        parseProgramMapTable(pusi, skipByte, tsPackageBuffer);
                    } else if (pid == video_pid) {
                        //视频的PES数据
                        parseVideoPES(conc, pusi, skipByte, tsPackageBuffer);
                    } else if (pid == audio_pid) {
                        //音频的PES数据
                        parseAudioPES(conc, pusi, skipByte, tsPackageBuffer);
                    }
                }
                if (pusi == 1) {
                    StringBuilder sb = new StringBuilder();
                    for (byte b : tsPackageBuffer) {
                        sb.append(String.format("%02X", b));
                        sb.append(" ");
                    }
                    //LogUtils.i(TAG, sb.toString());
                }
                //LogUtils.i(TAG, "------------------------------------------------------------------");
            }

            //LogUtils.i(TAG, "======================================================================");
            return true;
        }

        @WorkerThread
        private int parseAdaptationField(byte[] tsBuffer) {
            int skipByte = 0;
            //跳过头部的4个字节，读取弟5个字节
            int adaptation_field_length = tsBuffer[4] & 0xFF;
            //LogUtils.i(TAG, "adaptation_field_length=" + adaptation_field_length);
            if (adaptation_field_length > 0) {
                skipByte = adaptation_field_length + 1;
            }
            return skipByte;
        }

        @WorkerThread
        private void parseProgramAssociationTable(int pusi, int skipByte, byte[] tsBuffer) {
            //LogUtils.i(TAG,"pat parse");
            //TS数据中的PAT表，第5个字节是调整字节，其的数值为后面调整字段的长度length。因此有效载荷开始的位置应再偏移1+[length]个字节
            if (pusi == 1) {
                int adjustLen = tsBuffer[skipByte] & 0xFF;
                skipByte = skipByte + (1 + adjustLen);
            }
            int patStartIndex = skipByte;
            //(8)固定为0x00，标志该表是PAT表。
            int tableId = tsBuffer[patStartIndex] & 0xFF;
            //(1)段语法标志位，固定为1
            int section_syntax_indicator = (tsBuffer[patStartIndex + 1] >> 7) & 0x1;
            //（1）这个位置是个0
            //（2）reserved
            //(12)表示这个字节后面有用的字节数，包括CRC32。节目套数：（section length-9）/4
            int section_length = ((tsBuffer[patStartIndex + 1] & 0xF) << 8) | (tsBuffer[patStartIndex + 2] & 0xFF);
            //LogUtils.i(TAG, "tableId=" + tableId + ", section_syntax_indicator=" + section_syntax_indicator + ", section_length=" + section_length);

//            //（16）transport_stream_id：16位字段，表示该TS流的ID，区别于同一个网络中其它多路复用流。
//            int transport_stream_id = ((tsBuffer[skipByte + 3] & 0xFF) << 8) | (tsBuffer[skipByte + 4] & 0xFF);
//            //（2）reserved
//            //（5）version_number：表示PAT的版本号。
//            int version_number = (tsBuffer[skipByte + 5] >> 1) & 0x1F;
//            //（1）current_next_indicator：表示发送的PAT表是当前有效还是下一个PAT有效。
//            int current_next_indicator = tsBuffer[skipByte + 5] & 0x1;
//            //（8）section_number：表示分段的号码。PAT可能分为多段传输，第一段为0，以后每个分段加1，最多可能有256个分段。
//            int section_number = tsBuffer[skipByte + 6] & 0xFF;
//            //（8）last_section_number：表示PAT最后一个分段的号码。
//            int last_section_number = tsBuffer[skipByte + 7] & 0xFF;

            int program_number = ((tsBuffer[patStartIndex + 8] & 0xFF) << 8) | (tsBuffer[patStartIndex + 9] & 0xFF);
            //（3）reserved
            //（13）网络信息表（NIT）的PID,节目号为0时对应ID为network_PID。
            int network_PID = -1;
            //（13）节目映射表（PMT）的PID号，节目号为大于等于1时，对应的ID为program_map_PID。一个PAT中可以有多个program_map_PID。
            if (program_number == 0) {
                network_PID = ((tsBuffer[patStartIndex + 10] & 0x1F) << 8) | (tsBuffer[patStartIndex + 11] & 0xFF);
            } else {
                pmt_pid = ((tsBuffer[patStartIndex + 10] & 0x1F) << 8) | (tsBuffer[patStartIndex + 11] & 0xFF);
            }
            //CRC_32：32位字段，CRC32校验码Cyclic RedundancyCheck。
            int CRC_32 = ((tsBuffer[patStartIndex + 12] & 0xFF) << 24) | ((tsBuffer[patStartIndex + 13] & 0xFF) << 16) | ((tsBuffer[patStartIndex + 14] & 0xFF) << 8) | (tsBuffer[patStartIndex + 15] & 0xFF);
            //LogUtils.i(TAG, "program_number=" + program_number + ", network_PID=" + network_PID + ", program_map_PID=" + pmt_pid + ", CRC_32=" + CRC_32);
        }

        @WorkerThread
        private void parseProgramMapTable(int pusi, int skipByte, byte[] tsBuffer) {
            //LogUtils.i(TAG,"pmt parse");
            //获取video跟audio的pid之前，先恢复默认值
            video_pid = -1;
            audio_pid = -1;
            //TS数据中的PMT表，第5个字节是调整字节，其的数值为后面调整字段的长度length。因此有效载荷开始的位置应再偏移1+[length]个字节
            if (pusi == 1) {
                int adjustLen = tsBuffer[skipByte] & 0xFF;
                skipByte = skipByte + (1 + adjustLen);
            }
            int pmtStartIndex = skipByte;
            //（8）固定为0x02，标志该表是PMT 表。
            int tableId = tsBuffer[pmtStartIndex] & 0xFF;
            //（1）段语法标志位，固定为1
            int section_syntax_indicator = (tsBuffer[pmtStartIndex + 1] >> 7) & 0x1;
            //（1）这个位置是个0
            //（2）reserved
            //（12）表示这个字节后面有用的字节数，包括CRC32。节目套数：（section length-9）/4
            int section_length = ((tsBuffer[pmtStartIndex + 1] & 0xF) << 8) | (tsBuffer[pmtStartIndex + 2] & 0xFF);
            //LogUtils.i(TAG, "tableId=" + tableId + ", section_syntax_indicator=" + section_syntax_indicator + ", section_length=" + section_length);

            //（16）它指出该节目对应于可应用的Program map PID
            int program_number = ((tsBuffer[pmtStartIndex + 3] & 0xFF) << 8) | (tsBuffer[pmtStartIndex + 4] & 0xFF);
            //（2）reserved
            //（5）version_number：指出PMT 的版本号。
            int version_number = (tsBuffer[pmtStartIndex + 5] >> 1) & 0x1F;
            //（1）current_next_indicator：当该位置’1’时，当前传送的Program map section可用；当该位置’0’时，指示当前传送的Program map section不可用，下一个TS流的Programmap section 有效。
            int current_next_indicator = tsBuffer[pmtStartIndex + 5] & 0x1;
            //（8）section_number：总是置为0x00（因为PMT表里表示一个service的信息，一个section 的长度足够）。
            int section_number = tsBuffer[pmtStartIndex + 6] & 0xFF;
            //（8）last_section_number：该域的值总是0x00 。
            int last_section_number = tsBuffer[pmtStartIndex + 7] & 0xFF;
            //（3）reserved
            //（13）节目中包含有效PCR字段的传送流中PID
            int PCR_PID = ((tsBuffer[pmtStartIndex + 8] & 0x1F) << 8) | (tsBuffer[pmtStartIndex + 9] & 0xFF);
            //（4）reserved
            //（12）前两位为00。该域指出跟随其后对节目信息的描述的byte 数。
            int program_info_length = ((tsBuffer[pmtStartIndex + 10] & 0xF) << 8) | (tsBuffer[pmtStartIndex + 11] & 0xFF);

            int infoBytesRemaining = section_length - 9 - program_info_length - 4;
            //LogUtils.i(TAG, "program_info_length=" + program_info_length + ", infoBytesRemaining=" + infoBytesRemaining);

            int index = 0;
            int es_info_length = 0;
            while (infoBytesRemaining >= 5) {
                //（8）Stream type：8bit域，指示特定PID的节目元素包的类型。该处PID由elementary PID 指定
                int stream_type = tsBuffer[pmtStartIndex + 12 + (index * 5) + es_info_length] & 0xFF;
                //（3）reserved
                //（13）
                int elementary_PID = ((tsBuffer[pmtStartIndex + 13 + (index * 5) + es_info_length] & 0x1F) << 8) | (tsBuffer[pmtStartIndex + 14 + (index * 5) + es_info_length] & 0xFF);
                //（4）reserved
                //（12）,前2位没用，取后10位的值
                es_info_length = ((tsBuffer[pmtStartIndex + 15 + (index * 5) + es_info_length] & 0x3) << 8) | (tsBuffer[pmtStartIndex + 16 + (index * 5) + es_info_length] & 0xFF);
                //LogUtils.i(TAG, "stream_type=" + stream_type + ", elementary_PID=" + elementary_PID + ", ES_info_length=" + es_info_length);
                infoBytesRemaining -= 5 + es_info_length;
                index++;

                if (stream_type == 15) {
                    audio_pid = elementary_PID;
                } else if (stream_type == 27) {
                    video_pid = elementary_PID;
                }

            }

            //（32）
            //int CRC_32 = ((tsBuffer[skipByte + 17] & 0xFF) << 24) | ((tsBuffer[skipByte + 18] & 0xFF) << 16) | ((tsBuffer[skipByte + 19] & 0xFF) << 8) | (tsBuffer[skipByte + 20] & 0xFF);

        }

        @WorkerThread
        private void parseVideoPES(int conc, int pusi, int skipByte, byte[] tsBuffer) {
            if (pusi == 1) {
                //新的PES包的开始，把之前从PES包解析出来的ES数据，交给媒体管理者
                if (video_es_data_length == videoBuffer.size()) {
                    videoDataManager.processVideoData(videoPts, videoDts, videoBuffer.toByteArray());
                }
                //清空视频缓存
                videoBuffer.reset();
                videoPts = -1;
                videoDts = -1;
                video_es_data_length = -1;

                //(24)
                int packet_start_code_prefix = ((tsBuffer[skipByte] & 0xFF) << 16) | ((tsBuffer[skipByte + 1] & 0xFF) << 8) | (tsBuffer[skipByte + 2] & 0xFF);
                //(8)
                int stream_id = tsBuffer[skipByte + 3] & 0xFF;
                //(16)表示 PES 包中在该字段后的数据字节数
                int pes_packet_length = ((tsBuffer[skipByte + 4] & 0xFF) << 8) | (tsBuffer[skipByte + 5] & 0xFF);
                //LogUtils.i(TAG, "video packet_start_code_prefix=" + packet_start_code_prefix + ", stream_id=" + stream_id + ", pes_packet_length=" + pes_packet_length);

                if (stream_id != 0xbc  // program_stream_map
                        && stream_id != 0xbe  // padding_stream
                        && stream_id != 0xbf  // private_stream_2
                        && stream_id != 0xf0  // ECM
                        && stream_id != 0xf1  // EMM
                        && stream_id != 0xff  // program_stream_directory
                        && stream_id != 0xf2  // DSMCC
                        && stream_id != 0xf8) {  // H.222.1 type E

                    //(2) 固定值：2
                    //(2) 加密模式：0是不加密，1是加密
                    int PES_scrambling_control = (tsBuffer[skipByte + 6] >> 4) & 0x3;
                    //(1) 优先级，1表示优先级较高
                    int PES_priority = (tsBuffer[skipByte + 6] >> 3) & 0x1;
                    //(1) 1表示ES数据紧跟着PES头文件，0表示不一定
                    int data_alignment_indicator = (tsBuffer[skipByte + 6] >> 3) & 0x1;
                    //(1) 1表示PES的有效负载是有版权的
                    int copyright = (tsBuffer[skipByte + 6] >> 1) & 0x1;
                    //(1) 1表示负载是原始数据，0表示负载时备份
                    int original_or_copy = tsBuffer[skipByte + 6] & 0x1;

                    //(2) 0表示无PTS/DTS, 1表示被禁止，2表示PES包头文件有PTS，3表示有PTS/DTS
                    int PTS_DTS_flags = (tsBuffer[skipByte + 7] >> 6) & 0x3;
                    //（1）1表示有ESCR字段，0表示没有
                    int ESCR_flag = (tsBuffer[skipByte + 7] >> 5) & 0x1;
                    //(1) 1表示有ES_rate字段
                    int ES_rate_flag = (tsBuffer[skipByte + 7] >> 4) & 0x1;
                    //(1) 1表示有此字段
                    int DSM_trick_mode_flag = (tsBuffer[skipByte + 7] >> 3) & 0x1;
                    //(1) 1表示有此字段
                    int additional_copy_info_flag = (tsBuffer[skipByte + 7] >> 2) & 0x1;
                    //(1) 1表示有此字段
                    int PES_CRC_flag = (tsBuffer[skipByte + 7] >> 1) & 0x1;
                    //(1) 1表示有此字段
                    int PES_extension_flag = (tsBuffer[skipByte + 7]) & 0x1;

                    //(8) 该字段后属于PES包 头文件部分的字节数
                    int PES_header_data_length = (tsBuffer[skipByte + 8]) & 0xFF;
                    //计算ES的数据长度
                    video_es_data_length = pes_packet_length - 3 - PES_header_data_length;
                    //LogUtils.i(TAG, "video es_data_length=" + video_es_data_length + ", PES_header_data_length=" + PES_header_data_length);

                    //PTS开始解析的位置
                    skipByte = skipByte + 9;
                    //2表示PES包头文件有PTS
                    if (PTS_DTS_flags == 2) {
//                        unsigned reserved_1           : 4;  // 保留位，固定为 0010
//                        unsigned PTS_32_30            : 3;  // PTS
//                        unsigned marker_1             : 1;  // 保留位，固定为 1
//                        unsigned PTS_29_15            : 15; // PTS
//                        unsigned marker_2             : 1;  // 保留位，固定为 1
//                        unsigned PTS_14_0             : 15; // PTS
//                        unsigned marker_3             : 1;  // 保留位，固定为 1
//                        int pts_32_30 = (tsBuffer[skipByte] >> 1) & 0x7;
//                        int PTS_29_15 = ((tsBuffer[skipByte + 1] & 0xFF) << 7) | ((tsBuffer[skipByte + 2] >> 1) & 0x7F);
//                        int PTS_14_0 = ((tsBuffer[skipByte + 3] & 0xFF) << 7) | ((tsBuffer[skipByte + 4] >> 1) & 0x7F);

                        videoPts = ((long) ((tsBuffer[skipByte] >> 1) & 0x7) << 30) | ((((tsBuffer[skipByte + 1] & 0xFF) << 7) | ((tsBuffer[skipByte + 2] >> 1) & 0x7F)) << 15) | (((tsBuffer[skipByte + 3] & 0xFF) << 7) | ((tsBuffer[skipByte + 4] >> 1) & 0x7F));
                        videoPts = videoPts * 100L / 9L;
                        //LogUtils.i(TAG, "video pts=" + videoPts + " - " + (videoPts / 1000 / 1000));

                    } else if (PTS_DTS_flags == 3) {
//                        unsigned reserved_1           : 4;  // 保留位，固定为 0011
//                        unsigned PTS_32_30            : 3;  // PTS
//                        unsigned marker_1             : 1;  // 保留位，固定为 1
//                        unsigned PTS_29_15            : 15; // PTS
//                        unsigned marker_2             : 1;  // 保留位，固定为 1
//                        unsigned PTS_14_0             : 15; // PTS
//                        unsigned marker_3             : 1;  // 保留位，固定为 1
//                        unsigned reserved_2           : 4;  // 保留位，固定为 0001
//                        unsigned DTS_32_30            : 3;  // DTS
//                        unsigned marker_1             : 1;  // 保留位，固定为 1
//                        unsigned DTS_29_15            : 15; // DTS
//                        unsigned marker_2             : 1;  // 保留位，固定为 1
//                        unsigned DTS_14_0             : 15; // DTS
//                        unsigned marker_3             : 1;  // 保留位，固定为 1
                        int pts_32_30 = (tsBuffer[skipByte] >> 1) & 0x7;
                        int PTS_29_15 = ((tsBuffer[skipByte + 1] & 0xFF) << 7) | ((tsBuffer[skipByte + 2] >> 1) & 0x7F);
                        int PTS_14_0 = ((tsBuffer[skipByte + 3] & 0xFF) << 7) | ((tsBuffer[skipByte + 4] >> 1) & 0x7F);
                        int dts_32_30 = (tsBuffer[skipByte] >> 5) & 0x7;
                        int dTS_29_15 = ((tsBuffer[skipByte + 6] & 0xFF) << 7) | ((tsBuffer[skipByte + 7] >> 1) & 0x7F);
                        int dTS_14_0 = ((tsBuffer[skipByte + 8] & 0xFF) << 7) | ((tsBuffer[skipByte + 9] >> 1) & 0x7F);

                        videoPts = ((long) ((tsBuffer[skipByte] >> 1) & 0x7) << 30) | ((((tsBuffer[skipByte + 1] & 0xFF) << 7) | ((tsBuffer[skipByte + 2] >> 1) & 0x7F)) << 15) | (((tsBuffer[skipByte + 3] & 0xFF) << 7) | ((tsBuffer[skipByte + 4] >> 1) & 0x7F));
                        videoPts = videoPts * 100L / 9L;
                        videoDts = ((long) ((tsBuffer[skipByte] >> 5) & 0x7) << 30) | ((((tsBuffer[skipByte + 6] & 0xFF) << 7) | ((tsBuffer[skipByte + 7] >> 1) & 0x7F)) << 15) | (((tsBuffer[skipByte + 8] & 0xFF) << 7) | ((tsBuffer[skipByte + 9] >> 1) & 0x7F));
                        //LogUtils.i(TAG, "video pts=" + videoPts + ", dts=" + videoDts);
                    }

                    skipByte = skipByte + PES_header_data_length;
                }
            }

            //把ES数据放到缓存中
            videoBuffer.write(tsBuffer, skipByte, tsPackageLen - skipByte);
            //LogUtils.i(TAG, "video write " + (tsPackageLen - skipByte) + ", videoBuffer length: " + videoBuffer.size());
        }

        @WorkerThread
        private void parseAudioPES(int conc, int pusi, int skipByte, byte[] tsBuffer) {
            if (pusi == 1) {
                //新的PES包的开始，把之前从PES包解析出来的ES数据，交给媒体管理者
                audioDataManager.processAudioData(audioPts, audioPts, audioBuffer.toByteArray());
                //清空视频缓存
                audioBuffer.reset();
                audioPts = -1;

                //(24)
                int packet_start_code_prefix = ((tsBuffer[skipByte] & 0xFF) << 16) | ((tsBuffer[skipByte + 1] & 0xFF) << 8) | (tsBuffer[skipByte + 2] & 0xFF);
                //(8)
                int stream_id = tsBuffer[skipByte + 3] & 0xFF;
                //(16)表示 PES 包中在该字段后的数据字节数
                int pes_packet_length = ((tsBuffer[skipByte + 4] & 0xFF) << 8) | (tsBuffer[skipByte + 5] & 0xFF);
                //LogUtils.i(TAG, "audio packet_start_code_prefix=" + packet_start_code_prefix + ", stream_id=" + stream_id + ", pes_packet_length=" + pes_packet_length);

                if (stream_id != 0xbc  // program_stream_map
                        && stream_id != 0xbe  // padding_stream
                        && stream_id != 0xbf  // private_stream_2
                        && stream_id != 0xf0  // ECM
                        && stream_id != 0xf1  // EMM
                        && stream_id != 0xff  // program_stream_directory
                        && stream_id != 0xf2  // DSMCC
                        && stream_id != 0xf8) {  // H.222.1 type E

                    //(2) 固定值：2
                    //(2) 加密模式：0是不加密，1是加密
                    int PES_scrambling_control = (tsBuffer[skipByte + 6] >> 4) & 0x3;
                    //(1) 优先级，1表示优先级较高
                    int PES_priority = (tsBuffer[skipByte + 6] >> 3) & 0x1;
                    //(1) 1表示ES数据紧跟着PES头文件，0表示不一定
                    int data_alignment_indicator = (tsBuffer[skipByte + 6] >> 3) & 0x1;
                    //(1) 1表示PES的有效负载是有版权的
                    int copyright = (tsBuffer[skipByte + 6] >> 1) & 0x1;
                    //(1) 1表示负载是原始数据，0表示负载时备份
                    int original_or_copy = tsBuffer[skipByte + 6] & 0x1;

                    //(2) 0表示无PTS/DTS, 1表示被禁止，2表示PES包头文件有PTS，3表示有PTS/DTS
                    int PTS_DTS_flags = (tsBuffer[skipByte + 7] >> 6) & 0x3;
                    //（1）1表示有ESCR字段，0表示没有
                    int ESCR_flag = (tsBuffer[skipByte + 7] >> 5) & 0x1;
                    //(1) 1表示有ES_rate字段
                    int ES_rate_flag = (tsBuffer[skipByte + 7] >> 4) & 0x1;
                    //(1) 1表示有此字段
                    int DSM_trick_mode_flag = (tsBuffer[skipByte + 7] >> 3) & 0x1;
                    //(1) 1表示有此字段
                    int additional_copy_info_flag = (tsBuffer[skipByte + 7] >> 2) & 0x1;
                    //(1) 1表示有此字段
                    int PES_CRC_flag = (tsBuffer[skipByte + 7] >> 1) & 0x1;
                    //(1) 1表示有此字段
                    int PES_extension_flag = (tsBuffer[skipByte + 7]) & 0x1;

                    //(8) 该字段后属于PES包 头文件部分的字节数
                    int PES_header_data_length = (tsBuffer[skipByte + 8]) & 0xFF;
                    //计算ES的数据长度
                    int es_data_length = pes_packet_length - 3 - PES_header_data_length;
                    //LogUtils.i(TAG, "audio es_data_length=" + es_data_length + ", PES_header_data_length=" + PES_header_data_length);

                    //PTS开始解析的位置
                    skipByte = skipByte + 9;
                    //2表示PES包头文件有PTS
                    if (PTS_DTS_flags == 2) {
//                        unsigned reserved_1           : 4;  // 保留位，固定为 0010
//                        unsigned PTS_32_30            : 3;  // PTS
//                        unsigned marker_1             : 1;  // 保留位，固定为 1
//                        unsigned PTS_29_15            : 15; // PTS
//                        unsigned marker_2             : 1;  // 保留位，固定为 1
//                        unsigned PTS_14_0             : 15; // PTS
//                        unsigned marker_3             : 1;  // 保留位，固定为 1
//                        int pts_32_30 = (tsBuffer[skipByte] >> 1) & 0x7;
//                        int PTS_29_15 = ((tsBuffer[skipByte + 1] & 0xFF) << 7) | ((tsBuffer[skipByte + 2] >> 1) & 0x7F);
//                        int PTS_14_0 = ((tsBuffer[skipByte + 3] & 0xFF) << 7) | ((tsBuffer[skipByte + 4] >> 1) & 0x7F);

                        audioPts = ((long) ((tsBuffer[skipByte] >> 1) & 0x7) << 30) | ((((tsBuffer[skipByte + 1] & 0xFF) << 7) | ((tsBuffer[skipByte + 2] >> 1) & 0x7F)) << 15) | (((tsBuffer[skipByte + 3] & 0xFF) << 7) | ((tsBuffer[skipByte + 4] >> 1) & 0x7F));
                        audioPts = audioPts * 100L / 9L;
                        //LogUtils.i(TAG, "audio pts=" + audioPts + " - " + (audioPts / 1000 / 1000));

                    } else if (PTS_DTS_flags == 3) {
//                        unsigned reserved_1           : 4;  // 保留位，固定为 0011
//                        unsigned PTS_32_30            : 3;  // PTS
//                        unsigned marker_1             : 1;  // 保留位，固定为 1
//                        unsigned PTS_29_15            : 15; // PTS
//                        unsigned marker_2             : 1;  // 保留位，固定为 1
//                        unsigned PTS_14_0             : 15; // PTS
//                        unsigned marker_3             : 1;  // 保留位，固定为 1
//                        unsigned reserved_2           : 4;  // 保留位，固定为 0001
//                        unsigned DTS_32_30            : 3;  // DTS
//                        unsigned marker_1             : 1;  // 保留位，固定为 1
//                        unsigned DTS_29_15            : 15; // DTS
//                        unsigned marker_2             : 1;  // 保留位，固定为 1
//                        unsigned DTS_14_0             : 15; // DTS
//                        unsigned marker_3             : 1;  // 保留位，固定为 1
                        int pts_32_30 = (tsBuffer[skipByte] >> 1) & 0x7;
                        int PTS_29_15 = ((tsBuffer[skipByte + 1] & 0xFF) << 7) | ((tsBuffer[skipByte + 2] >> 1) & 0x7F);
                        int PTS_14_0 = ((tsBuffer[skipByte + 3] & 0xFF) << 7) | ((tsBuffer[skipByte + 4] >> 1) & 0x7F);
                        int dts_32_30 = (tsBuffer[skipByte] >> 5) & 0x7;
                        int dTS_29_15 = ((tsBuffer[skipByte + 6] & 0xFF) << 7) | ((tsBuffer[skipByte + 7] >> 1) & 0x7F);
                        int dTS_14_0 = ((tsBuffer[skipByte + 8] & 0xFF) << 7) | ((tsBuffer[skipByte + 9] >> 1) & 0x7F);

                        audioPts = ((long) ((tsBuffer[skipByte] >> 1) & 0x7) << 30) | ((((tsBuffer[skipByte + 1] & 0xFF) << 7) | ((tsBuffer[skipByte + 2] >> 1) & 0x7F)) << 15) | (((tsBuffer[skipByte + 3] & 0xFF) << 7) | ((tsBuffer[skipByte + 4] >> 1) & 0x7F));
                        audioPts = audioPts * 100L / 9L;
                        long dts = ((long) ((tsBuffer[skipByte] >> 5) & 0x7) << 30) | ((((tsBuffer[skipByte + 6] & 0xFF) << 7) | ((tsBuffer[skipByte + 7] >> 1) & 0x7F)) << 15) | (((tsBuffer[skipByte + 8] & 0xFF) << 7) | ((tsBuffer[skipByte + 9] >> 1) & 0x7F));
                        //LogUtils.i(TAG, "audio pts=" + audioPts + ", dts=" + dts);
                    }

                    skipByte = skipByte + PES_header_data_length;
                }
            }
            //把ES数据放到缓存中
            audioBuffer.write(tsBuffer, skipByte, tsPackageLen - skipByte);
            //LogUtils.i(TAG, "audio write " + (tsPackageLen - skipByte) + ", audioBuffer length: " + audioBuffer.size());
        }

    }

    //关闭socket，清空所有数据
    @WorkerThread
    private void closeSocket() {
        if (socket == null) {
            return;
        }
        LogUtils.i(TAG, "closeSocket");
        mLocalRTPPort = 0;
        mUdpDataListener = null;
        //子线程退出
        handlerThread.quit();
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            socket = null;
        }

    }

    interface OnRTPExceptionListener {
        void onIOException();
    }

}
