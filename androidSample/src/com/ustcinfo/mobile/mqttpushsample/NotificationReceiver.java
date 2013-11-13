package com.ustcinfo.mobile.mqttpushsample;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.v4.app.NotificationCompat;

/**
 * 在通知栏上显示消息，注册为一个低优先级的接收器。
 * 当用户不在UI前台时，使用Notification显示接收到的消息
 */
public class NotificationReceiver extends BroadcastReceiver{
	
	public static final int NOTIFICATION_ID  = 1;
	@Override
	public void onReceive(Context context, Intent intent) {
		Bundle data = intent.getExtras();
		String topic = data.getString(PushService.MQTT_MSG_RECEIVED_TOPIC);
		String message = data.getString(PushService.MQTT_MSG_RECEIVED_MSG);
		
        PendingIntent contentIntent = PendingIntent.getActivity(context, 0,
					        		new Intent(context, MainActivity.class),
					                PendingIntent.FLAG_UPDATE_CURRENT);
        
        NotificationManager nm = (NotificationManager) 
        				context.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context);
        builder.setContentIntent(contentIntent)
        		.setTicker("收到消息:"+message)
        		.setContentTitle(message)
		        .setDefaults(Notification.DEFAULT_ALL)
		        .setSmallIcon(R.drawable.ic_launcher)
		        .setAutoCancel(true)
		        .setWhen(System.currentTimeMillis());
        Notification notification = builder.build();
        nm.notify(NOTIFICATION_ID, notification);
	}
}
