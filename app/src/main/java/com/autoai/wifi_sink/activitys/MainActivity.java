package com.autoai.wifi_sink.activitys;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pWfdInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;

import com.autoai.wifi_sink.LogUtils;
import com.autoai.wifi_sink.R;
import com.autoai.wifi_sink.display.RarpImpl;
import com.autoai.wifi_sink.display.RtspSink;

public class MainActivity extends AppCompatActivity {

    private final String TAG = MainActivity.class.getSimpleName();

    public final static int WFD_DEFAULT_PORT = 7236;
    public final static int WFD_MAX_THROUGHPUT = 50;

    private WifiManager mWifiManager;
    private WifiP2pManager mWifiP2pManager;
    private WifiP2pManager.Channel mChannel;

    //Source端的ip地址
    private String mSourceP2PAddress;
    //获取Source端的端口号
    private int mSourceP2pPort;
    //GO中Owner的这个值，能找到对应的ip地址
    private String mP2pInterfaceName;

    private WiFiDirectBroadcastReceiver mWiFiDirectReceiver;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        findViewById(R.id.bt_disconnect).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
//                if (TextUtils.isEmpty(mSourceP2PAddress) || mSourceP2pPort == 0) {
//                    return;
//                }
//                if (mRtspSink != null) {
//                    mRtspSink.closeRtspSession();
//                }
                RtspSink.getInstance().closeRtspSession();

            }
        });

        mWifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        mWifiP2pManager = (WifiP2pManager) getApplicationContext().getSystemService(Context.WIFI_P2P_SERVICE);

        //检查p2p环境，wifi是否打开，wifi直连是否能用
        checkP2pFeature();

    }

    //获取android_id
    private String getAndroidID() {
        return Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
    }

    //获取mac地址
    private String getMACAddress() {
        WifiInfo wifiInfo = mWifiManager.getConnectionInfo();
        String mac = wifiInfo.getMacAddress();
        return mac;
    }

    //是否支持p2p
    private boolean isSupportedP2P() {
        return getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT);
    }

    //检查p2p环境，wifi是否打开，wifi直连是否能用
    private void checkP2pFeature() {
        LogUtils.d(TAG, "ANDROID_ID: " + getAndroidID());
        if (mWifiManager == null) {
            LogUtils.e(TAG, "we'll exit because, mWifiManager is null");
            finish();
        }
        if (!mWifiManager.isWifiEnabled()) {
            if (!mWifiManager.setWifiEnabled(true)) {
                LogUtils.d(TAG, "Cannot enable wifi");
            }
        }
        LogUtils.d(TAG, "MAC: " + getMACAddress());
        if (!isSupportedP2P()) {
            LogUtils.d(TAG, "This Package Does Not Supports P2P Feature!!");
        }
    }

    //检查P2P是否连接
    public boolean isP2PDeviceIsConnected(WifiP2pDevice device) {
        if (device == null) {
            return false;
        }
        return device.status == WifiP2pDevice.CONNECTED;
    }

    @Override
    protected void onResume() {
        super.onResume();
        //注册wifi直连的广播
        if (mWiFiDirectReceiver == null) {
            mWiFiDirectReceiver = new WiFiDirectBroadcastReceiver();
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
            intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
            intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
            intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
            getApplication().registerReceiver(mWiFiDirectReceiver, intentFilter);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        //取消wifi直连的广播注册
        if (mWiFiDirectReceiver != null) {
            getApplication().unregisterReceiver(mWiFiDirectReceiver);
            mWiFiDirectReceiver = null;
        }
    }

    //初始化
    public void p2pInitialize() {
        if (mChannel != null) {
            return;
        }
        //创建连接p2p的通道，后面所有对p2p做操作，都得用到这个通道
        mChannel = mWifiP2pManager.initialize(this, getMainLooper(), new WifiP2pManager.ChannelListener() {
            public void onChannelDisconnected() {
                LogUtils.d(TAG, "ChannelListener: onChannelDisconnected()");
            }
        });
        LogUtils.d(TAG, "P2P Channel: " + mChannel);

        //给wifi直连重新命名
        mWifiP2pManager.setDeviceName(mChannel,
                this.getString(R.string.p2p_device_name),
                new WifiP2pManager.ActionListener() {
                    public void onSuccess() {
                        LogUtils.d(TAG, " device rename success");
                    }

                    public void onFailure(int reason) {
                        LogUtils.d(TAG, " Failed to set device name");
                    }
                });

        //设置当前是sink模式
        mWifiP2pManager.setMiracastMode(WifiP2pManager.MIRACAST_SINK);

        WifiP2pWfdInfo wfdInfo = new WifiP2pWfdInfo();
        wfdInfo.setWfdEnabled(true);
        wfdInfo.setDeviceType(WifiP2pWfdInfo.PRIMARY_SINK);
        wfdInfo.setSessionAvailable(true);
        wfdInfo.setControlPort(WFD_DEFAULT_PORT);
        wfdInfo.setMaxThroughput(WFD_MAX_THROUGHPUT);

        //设置sink模式下，设置WFDInfo可以让发送端搜索到此设备
        mWifiP2pManager.setWFDInfo(mChannel, wfdInfo, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                LogUtils.d(TAG, "Successfully set WFD info.");
            }

            @Override
            public void onFailure(int reason) {
                LogUtils.d(TAG, "Failed to set WFD info with reason " + reason + ".");
            }
        });
    }

    private Runnable mDiscoverPeersChecker = new Runnable() {
        @Override
        public void run() {
            discoverPeers();
            mHandler.postDelayed(mDiscoverPeersChecker, 15 * 1000);
        }
    };

    //扫描设备
    private void discoverPeers() {
        LogUtils.d(TAG, "discoverPeers");
        //扫描可用Wi-Fi peers，以便让source端发现
        mWifiP2pManager.discoverPeers(mChannel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
            }

            @Override
            public void onFailure(int reason) {
                LogUtils.d(TAG, "Failed to discoverPeers with reason " + reason + ".");
            }
        });
    }

    //停止扫描
    private void stopPeerDiscovery() {
        LogUtils.d(TAG, "stopPeerDiscovery");
        //移除扫描
        mHandler.removeCallbacks(mDiscoverPeersChecker);
        mWifiP2pManager.stopPeerDiscovery(mChannel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
            }

            @Override
            public void onFailure(int reason) {
                LogUtils.d(TAG, "onFailure: stopSearchDevice" + reason);
            }
        });
    }

    private Runnable mRarpChecker = new Runnable() {

        @Override
        public void run() {
            RarpImpl rarp = new RarpImpl();
            //根据字段找对应的ip地址
            String sourceIp = rarp.execRarp(mP2pInterfaceName);
            if (TextUtils.isEmpty(sourceIp)) {
                mHandler.postDelayed(mRarpChecker, 1000);
            } else {
                mSourceP2PAddress = sourceIp;
                //准备投屏
                startWifiDisplay(mSourceP2PAddress, mSourceP2pPort);
            }
        }
    };

    //开始投屏
    private void startWifiDisplay(String mSourceAddress, int mSourcePort) {
        LogUtils.i(TAG, "startWifiDisplay mSourceAddress: " + mSourceAddress + ", mSourcePort: " + mSourcePort);
        if (TextUtils.isEmpty(mSourceAddress) || mSourcePort == 0) {
            return;
        }
//        RtspSink mRtspSink = RtspSink.getInstance();
//        mRtspSink.createRTSPClient(mSourceAddress, mSourcePort);
        Intent intent = new Intent(this, WifiDisplayActivity.class);
        intent.putExtra("address", mSourceAddress);
        intent.putExtra("port", mSourcePort);
        startActivity(intent);
//        finish();
    }

    public class WiFiDirectBroadcastReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || TextUtils.isEmpty(intent.getAction())) {
                return;
            }
            String action = intent.getAction();
            LogUtils.d(TAG, "WiFiDirectBroadcastReceiver action: " + action);
            if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                //wifi直连 是否启用或禁用
                onP2PStateChanged(intent);
            } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                //P2P设备列表发生变化
            } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                //当设备的WifiP2P连接发生变化
                onConnectionChanged(intent);
            } else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
                // 当前的Wifi P2P设备信息发生变化
                onDeviceChanged(intent);
            }

        }

        //wifi直连 是否启用或禁用
        private void onP2PStateChanged(Intent intent) {
            LogUtils.d(TAG, "***onStateChanged");
            int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
            switch (state) {
                case WifiP2pManager.WIFI_P2P_STATE_ENABLED:
                    LogUtils.d(TAG, "state: WIFI_P2P_STATE_ENABLED");
                    //p2p初始化
                    p2pInitialize();
                    break;
                case WifiP2pManager.WIFI_P2P_STATE_DISABLED:
                    LogUtils.d(TAG, "state: WIFI_P2P_STATE_DISABLED");
                    break;
                default:
                    LogUtils.d(TAG, "state: " + state);
                    break;
            }
        }

        //当设备的WifiP2P连接发生变化
        private void onConnectionChanged(Intent intent) {
            LogUtils.d(TAG, "***onConnectionChanged");
            NetworkInfo networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
            LogUtils.d(TAG, "NetworkInfo: " + networkInfo.toString());
            if (!networkInfo.isConnected()) {
                LogUtils.i(TAG, "wifi_P2P disConnected");
                return;
            }
            LogUtils.i(TAG, "wifi_P2P connected");
            //停止扫描
            stopPeerDiscovery();
            //WifiP2pInfo wifiP2pInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO);
            //LogUtils.d(TAG, "WifiP2pInfo: " + wifiP2pInfo.toString());
            WifiP2pGroup wifiP2pGroupInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP);

            //获取Source端的端口号
            mWifiP2pManager.requestPeers(mChannel, new WifiP2pManager.PeerListListener() {
                @Override
                public void onPeersAvailable(WifiP2pDeviceList peers) {
                    for (WifiP2pDevice device : peers.getDeviceList()) {
                        boolean isConnected = (WifiP2pDevice.CONNECTED == device.status);
                        LogUtils.i(TAG, "isConnected: " + isConnected);
                        if (isConnected) {
                            LogUtils.i(TAG, "Source WifiP2pDevice: " + device);
                            WifiP2pWfdInfo wfd = device.wfdInfo;
                            if (wfd != null && wfd.isWfdEnabled()) {
                                int type = wfd.getDeviceType();
                                if (type == WifiP2pWfdInfo.WFD_SOURCE || type == WifiP2pWfdInfo.SOURCE_OR_PRIMARY_SINK) {
                                    mSourceP2pPort = wfd.getControlPort();
                                    LogUtils.d(TAG, "get source port: " + mSourceP2pPort);
                                }
                            }
                            break;
                        }
                    }
                }
            });

            //获取Source端的IP地址
            mWifiP2pManager.requestConnectionInfo(mChannel, new WifiP2pManager.ConnectionInfoListener() {
                @Override
                public void onConnectionInfoAvailable(WifiP2pInfo info) {
                    LogUtils.d(TAG, "WifiP2pInfo: " + info);
                    if (info.groupFormed && info.isGroupOwner) {
                        LogUtils.d(TAG, "sink is groupOwner");
                        mP2pInterfaceName = wifiP2pGroupInfo.getInterface();
                        mHandler.postDelayed(mRarpChecker, 1000);
                    } else if (info.groupFormed) {
                        mSourceP2PAddress = info.groupOwnerAddress.getHostAddress();
                        LogUtils.d(TAG, "sink is peer, ownerIP = " + mSourceP2PAddress);
                        //准备投屏
                        startWifiDisplay(mSourceP2PAddress, mSourceP2pPort);
                    }

                }
            });

        }

        // 当前的Wifi P2P设备信息发生变化
        private void onDeviceChanged(Intent intent) {
            LogUtils.d(TAG, "***onDeviceChanged");
            WifiP2pDevice device = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
            if (mChannel != null && !isP2PDeviceIsConnected(device)) {
                //扫描设备
                mHandler.removeCallbacks(mDiscoverPeersChecker);
                mHandler.postDelayed(mDiscoverPeersChecker, 200);
            }
        }

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mChannel != null) {
            mWifiP2pManager.cancelConnect(mChannel, null);
        }
        System.exit(0);
    }
}
