package com.ustcinfo.mobile.mqttpushsample;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.Enumeration;
import java.util.Hashtable;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;
import com.ibm.mqtt.IMqttClient;
import com.ibm.mqtt.MqttClient;
import com.ibm.mqtt.MqttException;
import com.ibm.mqtt.MqttNotConnectedException;
import com.ibm.mqtt.MqttPersistence;
import com.ibm.mqtt.MqttPersistenceException;
import com.ibm.mqtt.MqttSimpleCallback;

/*
 * 显示基于MQTT协议的Push服务
 * 核心基于http://dalelane.co.uk/blog/?p=1599修改
 */
public class PushService extends Service implements MqttSimpleCallback {
	/************************************************************************/
	/* CONSTANTS */
	/************************************************************************/

	// constants used to notify the Activity UI of received messages
	public static final String MQTT_MSG_RECEIVED_INTENT = "com.ustcinfo.mobile.push.MSGRECVD";
	public static final String MQTT_MSG_RECEIVED_TOPIC = "com.ustcinfo.mobile.push.MSGRECVD_TOPIC";
	public static final String MQTT_MSG_RECEIVED_MSG = "com.ustcinfo.mobile.push.MSGRECVD_MSGBODY";

	// constants used to tell the Activity UI the connection status
	public static final String MQTT_STATUS_INTENT = "com.ustcinfo.mobile.push.STATUS";
	public static final String MQTT_STATUS_MSG = "com.ustcinfo.mobile.push.STATUS_MSG";

	// constant used internally to schedule the next ping event
	public static final String MQTT_PING_ACTION = "com.ustcinfo.mobile.push.PING";

	// constants used to define MQTT connection status
	public enum MQTTConnectionStatus {
		INITIAL, // initial status
		CONNECTING, // attempting to connect
		CONNECTED, // connected
		NOTCONNECTED_WAITINGFORINTERNET, // can't connect because the phone does not have Internet access
		NOTCONNECTED_USERDISCONNECT, // user has explicitly requested disconnection
		NOTCONNECTED_UNKNOWNREASON // failed to connect for some reason
	}

	// MQTT constants
	public static final int MAX_MQTT_CLIENTID_LENGTH = 22;

	/************************************************************************/
	/* VARIABLES used to maintain state */
	/************************************************************************/

	// status of MQTT client connection
	private MQTTConnectionStatus connectionStatus = MQTTConnectionStatus.INITIAL;

	/************************************************************************/
	/* VARIABLES used to configure MQTT connection */
	/************************************************************************/

	//以下为可配置的几个参数
	//注意APP_NAME+CLIENT_ID的对于同一个服务器必须唯一
	//且相加不操作21个字符,最好不要包含 / # ? *等特殊符号
	public static String HOST = "127.0.0.1";
	public static int PORT = 1883;
	public static String APP_NAME = "app";
	public static String CLIENT_ID = "zhu";
	public static short KEEP_ALIVE_SECONDS = 20 * 60;
	// KEEP_ALIVE_SECONDS解释
	// how often should the app ping the server to keep the connection alive?
	//
	// too frequently - and you waste battery life
	// too infrequently - and you wont notice if you lose your connection
	// until the next unsuccessfull attempt to ping
	//
	// it's a trade-off between how time-sensitive the data is that your
	// app is handling, vs the acceptable impact on battery life
	//
	// it is perhaps also worth bearing in mind the network's support for
	// long running, idle connections. Ideally, to keep a connection open
	// you want to use a keep alive value that is less than the period of
	// time after which a network operator will kill an idle connection
	
	
	// 默认订阅主题名称，具体值为APP_NAME+"/"+CLIENT_ID
	private String topicName = "";
	// MQTT 客户端ID，必须唯一，且能操作22个字符
	// 具体值为APP_NAME+"/"+CLIENT_ID
	private String mqttClientId = null;
	
	private MqttPersistence usePersistence = null;
	private boolean cleanStart = false;
	private int[] qualitiesOfService = { 0 };


	/************************************************************************/
	/* VARIABLES - other local variables */
	/************************************************************************/
	// connection to the message broker
	private IMqttClient mqttClient = null;

	// receiver that notifies the Service when the phone gets data connection
	private NetworkConnectionIntentReceiver netConnReceiver;

	// receiver that wakes the Service up when it's time to ping the server
	private PingSender pingSender;

	/************************************************************************/
	/* METHODS - core Service lifecycle methods */
	/************************************************************************/

	// see http://developer.android.com/guide/topics/fundamentals.html#lcycles

	@Override
	public void onCreate() {
		super.onCreate();

		// reset status variable to initial state
		connectionStatus = MQTTConnectionStatus.INITIAL;

		// create a binder that will let the Activity UI send
		// commands to the Service
		mBinder = new LocalBinder<PushService>(this);

		topicName = APP_NAME + "/" + CLIENT_ID;
		mqttClientId = APP_NAME + "/" + CLIENT_ID;
		
		// define the connection to the broker
		defineConnectionToBroker(HOST);
	}

	@Override
	public void onStart(final Intent intent, final int startId) {
		// This is the old onStart method that will be called on the pre-2.0
		// platform. On 2.0 or later we override onStartCommand() so this
		// method will not be called.

		new Thread(new Runnable() {
			@Override
			public void run() {
				handleStart(intent, startId);
			}
		}, "MQTTservice").start();
	}

	@Override
	public int onStartCommand(final Intent intent, int flags, final int startId) {
		new Thread(new Runnable() {
			@Override
			public void run() {
				handleStart(intent, startId);
			}
		}, "MQTTservice").start();

		// return START_NOT_STICKY - we want this Service to be left running
		// unless explicitly stopped, and it's process is killed, we want it to
		// be restarted
		return START_STICKY;
	}

	synchronized void handleStart(Intent intent, int startId) {
		// before we start - check for a couple of reasons why we should stop

		if (mqttClient == null) {
			// we were unable to define the MQTT client connection, so we stop
			// immediately - there is nothing that we can do
			stopSelf();
			return;
		}

		// the Activity UI has started the MQTT service - this may be starting
		// the Service new for the first time, or after the Service has been
		// running for some time (multiple calls to startService don't start
		// multiple Services, but it does call this method multiple times)
		rebroadcastStatus();

		// if the Service was already running and we're already connected - we
		// don't need to do anything
		if (isAlreadyConnected() == false) {
			// set the status to show we're trying to connect
			connectionStatus = MQTTConnectionStatus.CONNECTING;

			// before we attempt to connect - we check if the phone has a
			// working data connection
			if (isOnline()) {
				// we think we have an Internet connection, so try to connect
				// to the message broker
				if (connectToBroker()) {
					// we subscribe to a topic - registering to receive push
					// notifications with a particular key
					// in a 'real' app, you might want to subscribe to multiple
					// topics - I'm just subscribing to one as an example
					// note that this topicName could include a wildcard, so
					// even just with one subscription, we could receive
					// messages for multiple topics
					subscribeToTopic(topicName);
				}
			} else {
				// we can't do anything now because we don't have a working
				// data connection
				connectionStatus = MQTTConnectionStatus.NOTCONNECTED_WAITINGFORINTERNET;

				// inform the app that we are not connected
				broadcastServiceStatus("Waiting for network connection");
			}
		}

		// changes to the phone's network - such as bouncing between WiFi
		// and mobile data networks - can break the MQTT connection
		// the MQTT connectionLost can be a bit slow to notice, so we use
		// Android's inbuilt notification system to be informed of
		// network changes - so we can reconnect immediately, without
		// haing to wait for the MQTT timeout
		if (netConnReceiver == null) {
			netConnReceiver = new NetworkConnectionIntentReceiver();
			registerReceiver(netConnReceiver, new IntentFilter(
					ConnectivityManager.CONNECTIVITY_ACTION));

		}

		// creates the intents that are used to wake up the phone when it is
		// time to ping the server
		if (pingSender == null) {
			pingSender = new PingSender();
			registerReceiver(pingSender, new IntentFilter(MQTT_PING_ACTION));
		}
	}

	@Override
	public void onDestroy() {
		super.onDestroy();

		// disconnect immediately
		disconnectFromBroker();

		// inform the app that the app has successfully disconnected
		broadcastServiceStatus("Disconnected");

		if (mBinder != null) {
			mBinder.close();
			mBinder = null;
		}
	}

	/************************************************************************/
	/* METHODS - broadcasts and notifications */
	/************************************************************************/

	// methods used to notify the Activity UI of something that has happened
	// so that it can be updated to reflect status and the data received
	// from the server

	private void broadcastServiceStatus(String statusDescription) {
		// inform the app (for times when the Activity UI is running /
		// active) of the current MQTT connection status so that it
		// can update the UI accordingly
		Intent broadcastIntent = new Intent();
		broadcastIntent.setAction(MQTT_STATUS_INTENT);
		broadcastIntent.putExtra(MQTT_STATUS_MSG, statusDescription);
		sendBroadcast(broadcastIntent);
	}

	private void broadcastReceivedMessage(String topic, String message) {
		// pass a message received from the MQTT server on to the Activity UI
		// (for times when it is running / active) so that it can be displayed
		// in the app GUI
		Intent broadcastIntent = new Intent();
		broadcastIntent.setAction(MQTT_MSG_RECEIVED_INTENT);
		broadcastIntent.putExtra(MQTT_MSG_RECEIVED_TOPIC, topic);
		broadcastIntent.putExtra(MQTT_MSG_RECEIVED_MSG, message);
		sendOrderedBroadcast(broadcastIntent,null);
		//sendBroadcast(broadcastIntent);
	}

	/************************************************************************/
	/* METHODS - binding that allows access from the Actitivy */
	/************************************************************************/

	// trying to do local binding while minimizing leaks - code thanks to
	// Geoff Bruckner - which I found at
	// http://groups.google.com/group/cw-android/browse_thread/thread/d026cfa71e48039b/c3b41c728fedd0e7?show_docid=c3b41c728fedd0e7

	private LocalBinder<PushService> mBinder;

	@Override
	public IBinder onBind(Intent intent) {
		return mBinder;
	}

	public class LocalBinder<S> extends Binder {
		private WeakReference<S> mService;

		public LocalBinder(S service) {
			mService = new WeakReference<S>(service);
		}

		public S getService() {
			return mService.get();
		}

		public void close() {
			mService = null;
		}
	}

	//
	// public methods that can be used by Activities that bind to the Service
	//
	public MQTTConnectionStatus getConnectionStatus() {
		return connectionStatus;
	}

	public void rebroadcastStatus() {
		String status = "";

		switch (connectionStatus) {
		case INITIAL:
			status = "Please wait";
			break;
		case CONNECTING:
			status = "Connecting...";
			break;
		case CONNECTED:
			status = "Connected";
			break;
		case NOTCONNECTED_UNKNOWNREASON:
			status = "Not connected - waiting for network connection";
			break;
		case NOTCONNECTED_USERDISCONNECT:
			status = "Disconnected";
			break;
		case NOTCONNECTED_WAITINGFORINTERNET:
			status = "Unable to connect";
			break;
		}

		// inform the app that the Service has successfully connected
		broadcastServiceStatus(status);
	}

	public void disconnect() {
		disconnectFromBroker();

		// set status
		connectionStatus = MQTTConnectionStatus.NOTCONNECTED_USERDISCONNECT;

		// inform the app that the app has successfully disconnected
		broadcastServiceStatus("Disconnected");
	}

	/************************************************************************/
	/* METHODS - MQTT methods inherited from MQTT classes */
	/************************************************************************/

	/*
	 * callback - method called when we no longer have a connection to the
	 * message broker server
	 */
	public void connectionLost() throws Exception {
		// we protect against the phone switching off while we're doing this
		// by requesting a wake lock - we request the minimum possible wake
		// lock - just enough to keep the CPU running until we've finished
		PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
		WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MQTT");
		wl.acquire();

		// have we lost our data connection?
		if (isOnline() == false) {
			connectionStatus = MQTTConnectionStatus.NOTCONNECTED_WAITINGFORINTERNET;

			// inform the app that we are not connected any more
			broadcastServiceStatus("Connection lost - no network connection");

			// wait until the phone has a network connection again, when we
			// the network connection receiver will fire, and attempt another
			// connection to the broker
		} else {
			//
			// we are still online
			// the most likely reason for this connectionLost is that we've
			// switched from wifi to cell, or vice versa
			// so we try to reconnect immediately
			//
			connectionStatus = MQTTConnectionStatus.NOTCONNECTED_UNKNOWNREASON;
			// inform the app that we are not connected any more, and are
			// attempting to reconnect
			broadcastServiceStatus("Connection lost - reconnecting...");

			// try to reconnect
			if (connectToBroker()) {
				subscribeToTopic(topicName);
			}
		}

		// we're finished - if the phone is switched off, it's okay for the CPU
		// to sleep now
		wl.release();
	}

	/*
	 * callback - called when we receive a message from the server
	 */
	public void publishArrived(String topic, byte[] payloadbytes, int qos,
			boolean retained) {
		// we protect against the phone switching off while we're doing this
		// by requesting a wake lock - we request the minimum possible wake
		// lock - just enough to keep the CPU running until we've finished
		PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
		WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MQTT");
		wl.acquire();

		//
		// I'm assuming that all messages I receive are being sent as strings
		// this is not an MQTT thing - just me making as assumption about what
		// data I will be receiving - your app doesn't have to send/receive
		// strings - anything that can be sent as bytes is valid
		String messageBody = new String(payloadbytes);

		broadcastReceivedMessage(topic, messageBody);

		// receiving this message will have kept the connection alive for us, so
		// we take advantage of this to postpone the next scheduled ping
		scheduleNextPing();

		// we're finished - if the phone is switched off, it's okay for the CPU
		// to sleep now
		wl.release();
	}

	/************************************************************************/
	/* METHODS - wrappers for some of the MQTT methods that we use */
	/************************************************************************/

	/*
	 * Create a client connection object that defines our connection to a
	 * message broker server
	 */
	private void defineConnectionToBroker(String brokerHostName) {
		String mqttConnSpec = "tcp://" + brokerHostName + "@"
				+ PORT;

		try {
			// define the connection to the broker
			mqttClient = MqttClient.createMqttClient(mqttConnSpec,
					usePersistence);

			// register this client app has being able to receive messages
			mqttClient.registerSimpleHandler(this);
		} catch (MqttException e) {
			// something went wrong!
			mqttClient = null;
			connectionStatus = MQTTConnectionStatus.NOTCONNECTED_UNKNOWNREASON;

			//
			// inform the app that we failed to connect so that it can update
			// the UI accordingly
			broadcastServiceStatus("Invalid connection parameters");

		}
	}

	/*
	 * (Re-)connect to the message broker
	 */
	private boolean connectToBroker() {
		try {
			// try to connect
			mqttClient.connect(mqttClientId, cleanStart, KEEP_ALIVE_SECONDS);

			// inform the app that the app has successfully connected
			broadcastServiceStatus("Connected");

			// we are connected
			connectionStatus = MQTTConnectionStatus.CONNECTED;

			// we need to wake up the phone's CPU frequently enough so that the
			// keep alive messages can be sent
			// we schedule the first one of these now
			scheduleNextPing();

			return true;
		} catch (MqttException e) {
			e.printStackTrace();
			
			connectionStatus = MQTTConnectionStatus.NOTCONNECTED_UNKNOWNREASON;
			
			// inform the app that we failed to connect so that it can update
			// the UI accordingly
			broadcastServiceStatus("Unable to connect");

			// if something has failed, we wait for one keep-alive period before
			// trying again
			// in a real implementation, you would probably want to keep count
			// of how many times you attempt this, and stop trying after a
			// certain number, or length of time - rather than keep trying
			// forever.
			// a failure is often an intermittent network issue, however, so
			// some limited retry is a good idea
			scheduleNextPing();

			return false;
		}
	}

	/*
	 * Send a request to the message broker to be sent messages published with
	 * the specified topic name. Wildcards are allowed.
	 */
	private void subscribeToTopic(String topicName) {
		boolean subscribed = false;

		if (isAlreadyConnected() == false) {
			// quick sanity check - don't try and subscribe if we
			// don't have a connection

			Log.e("mqtt", "Unable to subscribe as we are not connected");
		} else {
			try {
				String[] topics = { topicName };
				mqttClient.subscribe(topics, qualitiesOfService);

				subscribed = true;
			} catch (MqttNotConnectedException e) {
				Log.e("mqtt", "subscribe failed - MQTT not connected", e);
			} catch (IllegalArgumentException e) {
				Log.e("mqtt", "subscribe failed - illegal argument", e);
			} catch (MqttException e) {
				Log.e("mqtt", "subscribe failed - MQTT exception", e);
			}
		}

		if (subscribed == false) {
			//
			// inform the app of the failure to subscribe so that the UI can
			// display an error
			broadcastServiceStatus("Unable to subscribe");
		}
	}

	/*
	 * Terminates a connection to the message broker.
	 */
	private void disconnectFromBroker() {
		// if we've been waiting for an Internet connection, this can be
		// cancelled - we don't need to be told when we're connected now
		try {
			if (netConnReceiver != null) {
				unregisterReceiver(netConnReceiver);
				netConnReceiver = null;
			}

			if (pingSender != null) {
				unregisterReceiver(pingSender);
				pingSender = null;
			}
		} catch (Exception eee) {
			// probably because we hadn't registered it
			Log.e("mqtt", "unregister failed", eee);
		}

		try {
			if (mqttClient != null) {
				mqttClient.disconnect();
			}
		} catch (MqttPersistenceException e) {
			Log.e("mqtt", "disconnect failed - persistence exception", e);
		} finally {
			mqttClient = null;
		}
	}

	/*
	 * Checks if the MQTT client thinks it has an active connection
	 */
	private boolean isAlreadyConnected() {
		return ((mqttClient != null) && (mqttClient.isConnected() == true));
	}

	/*
	 * Called in response to a change in network connection - after losing a
	 * connection to the server, this allows us to wait until we have a usable
	 * data connection again
	 */
	private class NetworkConnectionIntentReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context ctx, Intent intent) {
			new Thread(new Runnable() {
				
				@Override
				public void run() {
					// we protect against the phone switching off while we're doing this
					// by requesting a wake lock - we request the minimum possible wake
					// lock - just enough to keep the CPU running until we've finished
					PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
					WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MQTT");
					wl.acquire();

					if (isOnline()) {
						// we have an internet connection - have another try at
						// connecting
						if (connectToBroker()) {
							// we subscribe to a topic - registering to receive push
							// notifications with a particular key
							subscribeToTopic(topicName);
						}
					}

					// we're finished - if the phone is switched off, it's okay for the
					// CPU
					// to sleep now
					wl.release();
				}
			}).start();
		}
	}

	/*
	 * Schedule the next time that you want the phone to wake up and ping the
	 * message broker server
	 */
	private void scheduleNextPing() {
		// When the phone is off, the CPU may be stopped. This means that our
		// code may stop running.
		// When connecting to the message broker, we specify a 'keep alive'
		// period - a period after which, if the client has not contacted
		// the server, even if just with a ping, the connection is considered
		// broken.
		// To make sure the CPU is woken at least once during each keep alive
		// period, we schedule a wake up to manually ping the server
		// thereby keeping the long-running connection open
		// Normally when using this Java MQTT client library, this ping would be
		// handled for us.
		// Note that this may be called multiple times before the next scheduled
		// ping has fired. This is good - the previously scheduled one will be
		// cancelled in favour of this one.
		// This means if something else happens during the keep alive period,
		// (e.g. we receive an MQTT message), then we start a new keep alive
		// period, postponing the next ping.

		PendingIntent pendingIntent = PendingIntent
				.getBroadcast(this, 0, new Intent(MQTT_PING_ACTION),
						PendingIntent.FLAG_UPDATE_CURRENT);

		// in case it takes us a little while to do this, we try and do it
		// shortly before the keep alive period expires
		// it means we're pinging slightly more frequently than necessary
		Calendar wakeUpTime = Calendar.getInstance();
		wakeUpTime.add(Calendar.SECOND, KEEP_ALIVE_SECONDS);

		AlarmManager aMgr = (AlarmManager) getSystemService(ALARM_SERVICE);
		aMgr.set(AlarmManager.RTC_WAKEUP, wakeUpTime.getTimeInMillis(),
				pendingIntent);
	}

	/*
	 * 
	 * Used to implement a keep-alive protocol at this Service level - it sends
	 * a PING message to the server, then schedules another ping after an
	 * interval defined by keepAliveSeconds
	 */
	public class PingSender extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			// Note that we don't need a wake lock for this method (even though
			// it's important that the phone doesn't switch off while we're
			// doing this).
			// According to the docs, "Alarm Manager holds a CPU wake lock as
			// long as the alarm receiver's onReceive() method is executing.
			// This guarantees that the phone will not sleep until you have
			// finished handling the broadcast."
			// This is good enough for our needs.
			new Thread(new Runnable() {
				@Override
				public void run() {
					try {
						mqttClient.ping();
					} catch (MqttException e) {
						// if something goes wrong, it should result in connectionLost
						// being called, so we will handle it there
						Log.e("mqtt", "ping failed - MQTT exception", e);

						// assume the client connection is broken - trash it
						try {
							mqttClient.disconnect();
						} catch (MqttPersistenceException e1) {
							Log.e("mqtt", "disconnect failed - persistence exception",e1);
						}

						// reconnect
						if (connectToBroker()) {
							subscribeToTopic(topicName);
						}
					}

					// start the next keep alive period
					scheduleNextPing();
				}
			}).start();

		}
	}

	/************************************************************************/
	/* METHODS - internal utility methods */
	/************************************************************************/

	private boolean isOnline() {
		ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
		if (cm.getActiveNetworkInfo() != null
				&& cm.getActiveNetworkInfo().isAvailable()
				&& cm.getActiveNetworkInfo().isConnected()) {
			return true;
		}

		return false;
	}
}
