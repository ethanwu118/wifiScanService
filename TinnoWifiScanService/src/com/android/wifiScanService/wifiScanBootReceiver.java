package com.android.wifiScanService;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import android.os.SystemProperties;
import java.io.IOException;
import android.os.UserHandle;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.DataInputStream;
import android.app.PendingIntent;
import android.app.Notification;
import android.app.NotificationManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

public class wifiScanBootReceiver extends BroadcastReceiver {
	private static final String LOG_TAG = "wifiScanBootReceiver";
	private boolean SUPPORT_RESTRICT_CAMERA = android.os.SystemProperties.getBoolean("persist.support_restrict_camera", true);

	@Override
	public void onReceive(Context context, Intent intent) 
	{
		String action = intent.getAction();
		Log.d(LOG_TAG, "onReceive " + intent);
		
		if(action.equals(Intent.ACTION_LOCKED_BOOT_COMPLETED) && SUPPORT_RESTRICT_CAMERA){
			Log.d(LOG_TAG, "wifiScanBootReceiver for sifiScanService");
			Intent service; 
			try {
				service = new Intent(context, wifiScanService.class);
				context.startService(service);
			} catch (Exception e) {
				Log.e(LOG_TAG, "Can't start service."+e);
			}
		}
	}
}
