package com.ustcinfo.mobile.mqttpushsample;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends Activity {

	private TextView textLog;
	private TextView textMsg;
	private StatusUpdateReceiver statusUpdateReceiver;
	private MessageReceiver messageReceiver;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.activity_main);
		textLog = (TextView) findViewById(R.id.textLog);
		textMsg = (TextView) findViewById(R.id.textMsg);

		statusUpdateReceiver = new StatusUpdateReceiver();
		messageReceiver = new MessageReceiver();

		PushService.HOST = "192.168.200.166";
		PushService.PORT = 1883;
		PushService.KEEP_ALIVE_SECONDS = 30;
		//注意APP_NAME+CLIENT_ID的对于同一个服务器必须唯一
		//且相加不操作21个字符,最好不要包含 / # ? *等特殊符号
		PushService.APP_NAME = "testMqtt";
		PushService.CLIENT_ID = "zhu";
		startService(new Intent(this, PushService.class));
	}

	// UI在前端的时候接收到消息显示在UI上
	@Override
	protected void onResume() {
		//将messageReceiver注册为一个高优先级的接收器
		IntentFilter filter = new IntentFilter(PushService.MQTT_MSG_RECEIVED_INTENT);
		filter.setPriority(1000);
		registerReceiver(messageReceiver, filter);
		registerReceiver(statusUpdateReceiver, new IntentFilter(PushService.MQTT_STATUS_INTENT));
		super.onResume();
	}

	@Override
	protected void onPause() {
		unregisterReceiver(statusUpdateReceiver);
		unregisterReceiver(messageReceiver);
		super.onPause();
	}

	public class StatusUpdateReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			Bundle notificationData = intent.getExtras();
			String newStatus = notificationData
					.getString(PushService.MQTT_STATUS_MSG);
			textLog.setText(textLog.getText() + "\n" + newStatus);
		}
	}

	/**
	 * 在UI上显示消息
	 */
	public class MessageReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			Bundle data = intent.getExtras();
			String topic = data.getString(PushService.MQTT_MSG_RECEIVED_TOPIC);
			String message = data.getString(PushService.MQTT_MSG_RECEIVED_MSG);
			textMsg.setText(textMsg.getText() + "\n" + message);
			// 中断广播,用户在前台是不需要在通知栏显示消息
			abortBroadcast();
		}
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		// stopService(new Intent(this, MqttPushService.class));
	}
}
