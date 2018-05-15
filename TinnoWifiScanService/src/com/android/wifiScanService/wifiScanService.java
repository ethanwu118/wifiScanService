package com.android.wifiScanService;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.util.Log;
import android.os.SystemProperties;
import java.io.IOException;
import java.math.BigInteger;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import android.os.Handler;
import android.os.Message;
import android.net.wifi.ScanResult;  
import android.net.wifi.WifiConfiguration;  
import android.net.wifi.WifiInfo;  
import android.net.wifi.WifiManager;  
import android.net.wifi.WifiManager.WifiLock; 

import android.os.RemoteException;
import android.provider.Settings;
import android.database.ContentObserver;
import com.tinno.feature.FeatureMBA;

public class wifiScanService extends Service{
	private static final String TAG = "wifiScanService";
	private Context mContext;
	private ScreenBroadcastReceiver mScreenReceiver;
	private WifiManager mWifiManager;

	private boolean scanning;
	private boolean mIsScreenOn;
	private boolean isWifiEnableByAirplane;
	private Handler handler;
	
	private int mWifiSettingValue;
	private String DeviceName = android.os.SystemProperties.get("persist.specific_wifissid_name", "Tinno-Guest,Tinno-2.4G");
	private long SCAN_TIMEOUT_MS = android.os.SystemProperties.getInt("persist.check.timeout", 2000);
	private long PERIOD_SCAN_TIME_MS = android.os.SystemProperties.getInt("persist.check.period_time", 120000);//default 3 min
	private long AIRPLANE_ON_SCAN_DELAY_TIME_MS =1500 ;
	private long SCAN_FAIL_RETRY_DELAY_TIME_MS =200 ;
	private long TURN_ON_BLE_FAIL_DELAY_TIME_MS =3000 ;

	private static final int MSG_TIMEOUT_STOP_SCAN= 101;
	private static final int MSG_BOOTUP_DELAY_SCAN= 102;
	private static final int MSG_PERIOD_SCAN= 103;

	static final int WIFI_DISABLED                   = 0;
    	static final int WIFI_ENABLED                      = 1;
    	static final int WIFI_ENABLED_AIRPLANE_OVERRIDE     = 2;
    	static final int WIFI_DISABLED_AIRPLANE_ON          = 3;

	public void onCreate() {
		super.onCreate();
		Log.d(TAG, "onCreate");
		mContext = getBaseContext();
		setBleProperty(false);
		mScreenReceiver = new ScreenBroadcastReceiver();
		startObserver();
		mWifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
		scanning = false;
		mIsScreenOn=true;
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		Log.d(TAG, "onStartCommand");
		mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_BOOTUP_DELAY_SCAN), 3000);
		mContext.getContentResolver().registerContentObserver(
			Settings.Global.getUriFor(Settings.Global.WIFI_ON), true,mWifiSettingStateObserver);
		return START_STICKY;
	}
	
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	public void onDestroy() {
		Log.d(TAG, "onDestroy");
		super.onDestroy();
	}

	public void startObserver() {
		registerListener();
	}

	private void registerListener() {
		if(mContext != null){
			IntentFilter filter = new IntentFilter();
			filter.addAction(Intent.ACTION_SCREEN_ON);
			filter.addAction(Intent.ACTION_SCREEN_OFF);
			filter.addAction(Intent.ACTION_USER_PRESENT);
			filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
			mContext.registerReceiver(mScreenReceiver, filter);
		}
	}

	private void startScanning() {
		Log.d(TAG, "startScanning()");
		
		if(!isSupportRestrictCamera()) {
			Log.d(TAG, "No support to restrict camera");
			return;
		}
		if(mWifiManager ==null) {
			Log.d(TAG, "No wifi Manager");
	 		mWifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
	 	}
		int wifivalue = Settings.Global.getInt(mContext.getContentResolver(),
									Settings.Global.WIFI_ON, -1);
		Log.d(TAG, "before-wifi setting state : " +wifivalue );

		if (mWifiManager.isWifiEnabled() == false)
		{
			if(isAirplaneTurnOn()&& (wifivalue ==WIFI_DISABLED_AIRPLANE_ON ||wifivalue ==WIFI_DISABLED)){
				isWifiEnableByAirplane = true;				
			}
			
			Log.d(TAG, "wifi is disabled, try to enable");
			mWifiManager.setWifiEnabled(true);
		}else {
			Log.d(TAG, "wifi already enabled, start to scan");
		}
		
		while( !(mWifiManager.getWifiState()==mWifiManager.WIFI_STATE_ENABLED) )
		{
			try{
				Thread.currentThread();
				Thread.sleep(100);
			}
			catch(InterruptedException e){
				Log.e(TAG, "fail to enable wifi " + e.toString());
			}
		}
			
		Log.d(TAG, "---BeginScan-SSID---");

		if (scanning == false) {
			mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_TIMEOUT_STOP_SCAN), SCAN_TIMEOUT_MS);

			IntentFilter filter = new IntentFilter(mWifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
			mContext.registerReceiver(wifi_receiver, filter);

			Log.d(TAG, "ready to scan...");
			try {
				scanning = true;
				mWifiManager.startScan();
			} catch (Exception e) {
				scanning = false;
				Log.e(TAG, "fail to start wifi scan: " + e.toString());
			}
		} else {
			Log.d(TAG, "has already scanned");
		}
	}

	private void stopScanning() {
		if(scanning){
			Log.d(TAG, "stopScanning");
			scanning = false;
		}
	}

	private void setBleProperty(boolean value){
		if(value){
			SystemProperties.set("persist.sys.restrict.camera", "true");
		}else {
			SystemProperties.set("persist.sys.restrict.camera", "false");
		}
	}

	private boolean isSupportRestrictCamera(){
		//boolean isSupport = FeatureMBA.MBA_FTR_BeaconChurchSouthAfrica_REQC8838Temp;
		boolean isSupport = android.os.SystemProperties.getBoolean("persist.support_restrict_camera", true);
		Log.d(TAG, "isSupportRestrictCamera : " + isSupport);
		return isSupport;
	}

	private boolean isAirplaneTurnOn(){
		return Settings.Global.getInt(mContext.getContentResolver(),
						Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
	}
	
	private void handleAirplaneStateChange(boolean mAirplaneState){
		Log.d(TAG, "handleAirplaneStateChange(), state = " + mAirplaneState);
		mHandler.removeMessages(MSG_PERIOD_SCAN);
		mHandler.removeMessages(MSG_BOOTUP_DELAY_SCAN);
		mHandler.removeMessages(MSG_TIMEOUT_STOP_SCAN);
		stopScanning();
		try {
			if(mContext!=null){
				mContext.unregisterReceiver(wifi_receiver);
			}
		} catch (IllegalArgumentException e) {
			Log.e(TAG, "wifi Receiver not registered.");
		}

		if(mAirplaneState){
			Log.d(TAG, "hanble airplane on");
			while( !(mWifiManager.getWifiState()==mWifiManager.WIFI_STATE_DISABLED) )
			{
				try{
					Thread.currentThread();
					Thread.sleep(100);
				}
				catch(InterruptedException e){
					Log.e(TAG, "fail to enable wifi " + e.toString());
				}
			}
			mWifiSettingValue = Settings.Global.getInt(mContext.getContentResolver(),
												   Settings.Global.WIFI_ON, -1);
			Log.d(TAG, "current wifi settings state: " + mWifiSettingValue);
			startScanning();
		}else {
			Log.d(TAG, "hanble airplane off");
			if(mWifiSettingValue ==WIFI_DISABLED){
				Log.d(TAG, "airplane off, need to disable wifi, previous setting: " +mWifiSettingValue);
				if (mWifiManager.isWifiEnabled()) {  
            				mWifiManager.setWifiEnabled(false);  
        			} 
			}else{
				Log.d(TAG, "airplane off, need to enable wifi, previous setting: " +mWifiSettingValue);
				if (!mWifiManager.isWifiEnabled()) {  
            				mWifiManager.setWifiEnabled(true);  
        			}
			}
		}
	}

	private ContentObserver mWifiSettingStateObserver = new ContentObserver(new Handler()) {
		@Override
		public void onChange(boolean selfChange) {
			if(isAirplaneTurnOn()){
				int value = Settings.Global.getInt(mContext.getContentResolver(),
							Settings.Global.WIFI_ON, -1);
				if(isWifiEnableByAirplane){
					return;
				}
				Log.d(TAG, "airplane turn on and change wifi setting state : " +value );
				if(value==WIFI_ENABLED_AIRPLANE_OVERRIDE && 
					( mWifiSettingValue ==WIFI_DISABLED_AIRPLANE_ON|| mWifiSettingValue ==WIFI_DISABLED)){
					mWifiSettingValue =WIFI_ENABLED;
				}
			}
		}
	};

	private class ScreenBroadcastReceiver extends BroadcastReceiver {
		private String mAction = null;

		@Override
		public void onReceive(Context context, Intent intent) {
			mAction = intent.getAction();
			if (Intent.ACTION_SCREEN_ON.equals(mAction)) {
				Log.d(TAG, "ACTION_SCREEN_ON");
				mIsScreenOn = true;
				startScanning();
			} else if (Intent.ACTION_SCREEN_OFF.equals(mAction)) {
				Log.d(TAG, "ACTION_SCREEN_OFF");
				mIsScreenOn = false;
				mHandler.removeMessages(MSG_TIMEOUT_STOP_SCAN);
				mHandler.removeMessages(MSG_BOOTUP_DELAY_SCAN);
				mHandler.removeMessages(MSG_PERIOD_SCAN);
				setBleProperty(false);
				stopScanning();
			} else if (Intent.ACTION_USER_PRESENT.equals(mAction)) {
				Log.d(TAG, "ACTION_USER_PRESENT");
			}else if (Intent.ACTION_AIRPLANE_MODE_CHANGED.equals(mAction)) {
				boolean enabled = intent.getBooleanExtra("state", false);
				Log.d(TAG, "ACTION_AIRPLANE_MODE_CHANGED enabled =" + enabled);
				handleAirplaneStateChange(enabled);
			}
		}
	}

	// Handle wifi state changes
	private final BroadcastReceiver wifi_receiver = new BroadcastReceiver() {
		public void onReceive(Context context, Intent intent) {
			Log.d("WifScanner", "onReceive");
			mContext.unregisterReceiver(this);
			getScanResultsBySSID();
		}
	};

	private void getScanResultsBySSID(){ 
		mHandler.removeMessages(MSG_TIMEOUT_STOP_SCAN);
		stopScanning();
		List<ScanResult> list = mWifiManager.getScanResults();
		int N = list.size();
		Log.v(TAG, "Wi-Fi Scan Results ... Count:" + N);
		for(int i=0; i < N; ++i) {
			Log.v(TAG, "  BSSID 	  =" + list.get(i).BSSID);
			Log.v(TAG, "  SSID		  =" + list.get(i).SSID);
			Log.v(TAG, "---------------");
		}
		mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_PERIOD_SCAN), PERIOD_SCAN_TIME_MS);
		
		if(isWifiEnableByAirplane &&isAirplaneTurnOn()&&mWifiSettingValue!=WIFI_ENABLED){
			Log.v(TAG, "wifi is enabled by airplane, since should force to disable it");
			if (mWifiManager.isWifiEnabled()) {  
            			mWifiManager.setWifiEnabled(false);  
				isWifiEnableByAirplane=false;
        		} 
		}
		if(!mIsScreenOn){
			return;
		}
		String[] AfterSplit = DeviceName.split(",");
		for (ScanResult result : list){
			for (int i=0; i<AfterSplit.length; i++) {
				if (TextUtils.equals(result.SSID, AfterSplit[i])){
					Log.d(TAG, "found wifi: "+result.SSID);
					setBleProperty(true);
					return;
				}
			}
		}
	}

	private final Handler mHandler = new Handler() {
			@Override
			public void handleMessage(Message msg) {
				switch (msg.what) {
					case MSG_TIMEOUT_STOP_SCAN:
						Log.d(TAG, "MSG_TIMEOUT_STOP_SCAN, not find wifi device");
						setBleProperty(false);
						stopScanning();
						mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_PERIOD_SCAN), PERIOD_SCAN_TIME_MS);
						break;
					case MSG_BOOTUP_DELAY_SCAN:
						Log.d(TAG, "MSG_BOOTUP_DELAY_SCAN");
						startScanning();
						break;
					case MSG_PERIOD_SCAN:
						Log.d(TAG, "MSG_PERIOD_SCAN");
						if(mIsScreenOn){
							Log.d(TAG, "MSG_PERIOD_SCAN, start scan");
							startScanning();
						}else{
							Log.d(TAG, "MSG_PERIOD_SCAN, stop scan because screen off");
						}
						break;
				}
			}
	};
}
