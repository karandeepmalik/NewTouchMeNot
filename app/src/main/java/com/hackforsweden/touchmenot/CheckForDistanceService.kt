package com.hackforsweden.touchmenot


import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.wifi.WifiManager
import android.os.*
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.cueaudio.engine.CUEEngine
import com.cueaudio.engine.CUEReceiverCallbackInterface
import com.cueaudio.engine.CUETrigger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*
import kotlin.math.pow


class CheckForDistanceService : Service() {

    companion object{
        private var isIndoor = true
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var isServiceStarted = false
    private var bluetoothAdapter: BluetoothAdapter? = null
    private val notificationChannelId = "CheckForDistanceService"
    private var  firstOffset=7
    private var secondOffset:Int =0
    private var thirdOffset:Int =0
    private var uniqueId:String? =null
    private var mHandler: Handler? = null
    private var mHandlerThread: HandlerThread? = null

    private var API_KEY = "EH0GHbslb0pNWAxPf57qA6n23w4Zgu5U";
    override fun onBind(intent: Intent): IBinder? {
        // We don't provide binding, so return null
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) { val action = intent.action
            log("using an intent with action $action")
            when (action) {
                Actions.START.name -> startService()
                Actions.STOP.name -> stopService()
                else -> log("This should never happen. No action in the received intent")
            }

        } else {

        }
        // by returning this we make sure the service is restarted if the system kills the service
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        log("The service has been created".toUpperCase())
        var notification = createNotification()
        startForeground(1, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        log("The service has been destroyed".toUpperCase())
        Toast.makeText(this, "Service destroyed", Toast.LENGTH_SHORT).show()
    }

    private fun startService() {
        if (isServiceStarted) return
        log("Starting the foreground service task")
        Toast.makeText(this, "Service starting its task", Toast.LENGTH_SHORT).show()
        isServiceStarted = true
        //populateDevicePowerMap()
        // we need this lock so our service gets not affected by Doze Mode
        wakeLock =
            (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CheckForDistanceService::lock").apply {
                    acquire()
                }
            }
        setupUltrasound();

        // we're starting a loop in a coroutine
        GlobalScope.launch(Dispatchers.IO) {
            while (isServiceStarted) {

                launch(Dispatchers.IO) {
                    if(isIndoor)
                        detectBluetoothDevices()
                    else
                        triggerTransmission()

                    checkUserLocation();
                    //getRSSI
                    //calculate Distance
                    // sendNotification
                }
                delay(MainActivity.socialTimeThreshold *60*1000)
            }
            log("End of the loop for the service")
        }
        // we're starting a loop in a coroutine

    }

    private fun stopService() {
        log("Stopping the foreground service")
        Toast.makeText(this, "Service stopping", Toast.LENGTH_SHORT).show()
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            stopForeground(true)
            stopSelf()
        } catch (e: Exception) {
            log("Service stopped without being started: ${e.message}")
        }
        isServiceStarted = false
    }



    private fun createNotification(): Notification {
        // depending on the Android API that we're dealing with we will have
        // to use a specific method to create the notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager;

            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            // Creating an Audio Attribute
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build()
            val channel = NotificationChannel(
                notificationChannelId,
                "Service notifications channel",
                NotificationManager.IMPORTANCE_HIGH
            ).let {
                it.description = "Service channel"
                it.enableLights(true)
                it.lightColor = Color.RED
                it.enableVibration(true)
                it.vibrationPattern = longArrayOf(100, 200, 300, 400, 500, 400, 300, 200, 400)
                it.setSound(uri,audioAttributes)
                it.importance=NotificationManager.IMPORTANCE_HIGH
                it
            }


            notificationManager.createNotificationChannel(channel)
        }

        val pendingIntent: PendingIntent = Intent(this, MainActivity::class.java).let { notificationIntent ->
            PendingIntent.getActivity(this, 0, notificationIntent, 0)
        }

        val builder: Notification.Builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(
            this,
            notificationChannelId
        ) else Notification.Builder(this)

        return builder
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.monitoring_social_distance))
            .setContentIntent(pendingIntent)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(Notification.PRIORITY_HIGH) // for under android 26 compatibility
            .build()
    }

    fun calculateDistance(rssi:Double, txPower :Double ) :Double
    {
        /*
         * RSSI = TxPower - 10 * n * lg(d)
         * n = 2 (in free space)
         *
         * d = 10 ^ ((TxPower - RSSI) / (10 * n))
         */

        return 10.0.pow((txPower - rssi) / (10 * 2))
    }

    fun detectBluetoothDevices()
    {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (!bluetoothAdapter!!.isEnabled)
        {
            bluetoothAdapter!!.enable()
        }

        // Register for broadcasts when a device is discovered.
        val discFilter = IntentFilter(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED)
        // Register for broadcasts when discovery has finished
        this.registerReceiver(discReciever, discFilter)


        // Register for broadcasts when a device is discovered.
        var filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        // Register for broadcasts when discovery has finished
        this.registerReceiver(btReciever, filter)
        filter = IntentFilter(
            BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        this.registerReceiver(btReciever, filter)

        if (bluetoothAdapter!!.isDiscovering) {
            bluetoothAdapter!!.cancelDiscovery()
        }
        bluetoothAdapter!!.startDiscovery()

    }
    // Create a BroadcastReceiver for ACTION_FOUND.
    private val btReciever: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (BluetoothDevice.ACTION_FOUND == action) {
                Log.d("DEVICELIST", "Bluetooth device found\n")
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                // Create a new device item
                val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)

                val txPower = 4.0 //getTxPower(deviceName)
                var distance = calculateDistance(rssi.toDouble(),txPower)
                Log.d("Detected Device ", name!!)
                Log.d("Rssi",rssi.toString())
                distance /= 1700

                if (distance <MainActivity.socialDistanceThreshold)
                {
                    val builder = NotificationCompat.Builder(this@CheckForDistanceService, notificationChannelId)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setColor(resources.getColor(R.color.colorAccent))
                        .setContentTitle("Alert")
                        .setContentText("Please maintain enough social distance")
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        // Set the intent that will fire when the user taps the notification
                        .setAutoCancel(true)
                        .setVibrate(longArrayOf(1000, 1000, 1000, 1000, 1000))

                    builder.setDefaults(Notification.DEFAULT_SOUND)


                    with(NotificationManagerCompat.from(this@CheckForDistanceService)) {
                        // notificationId is a unique int for each notification that you must define
                        notify(12345, builder.build())
                    }
                    if (bluetoothAdapter!!.isDiscovering) {
                        bluetoothAdapter!!.cancelDiscovery()
                    }
                }


            }
            else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED == action)
            {
                Log.d("DEVICELIST ", "Scanning done..")
                Toast.makeText(context, "Scanning done..", Toast.LENGTH_SHORT).show()
            }

        }
    }

    private fun setupUltrasound() {

        val rand = Random();

        secondOffset = rand.nextInt(100)
        thirdOffset = rand.nextInt(999)
        uniqueId = "$firstOffset.$secondOffset.$thirdOffset"
        audioEngineSetup();
    }

    private fun audioEngineSetup(){

        CUEEngine.getInstance().setupWithAPIKey(this, API_KEY)
        CUEEngine.getInstance().setDefaultGeneration(2)
        CUEEngine.getInstance().setReceiverCallback( OutputListener())
        enableListening(true)
        CUEEngine.getInstance().isTransmittingEnabled = true
    }


    private fun triggerTransmission()
    {
        queueInput(uniqueId!!, CUETrigger.MODE_TRIGGER, false);
    }

    private fun  queueInput( input :String, mode:Int, triggerAsNumber:Boolean)
    {
        val result:Int;

        when (mode) {
            CUETrigger.MODE_TRIGGER ->
                if(triggerAsNumber)
                {
                    val number =  input.toLong();
                    result = CUEEngine.getInstance().queueTriggerAsNumber(number)
                    if( result == -110 ) {
//                        messageLayout.setError(
//                                "Triggers as number sending is unsupported for engine generation 1" )
                    } else if( result < 0 ) /* -120 */ {
//                        messageLayout.setError(
//                                "Triggers us number can not exceed 98611127" )
                    }
                }
                else
                {
                    CUEEngine.getInstance().queueTrigger(input)
                }


        else ->{}

        }
    }
    private fun enableListening(enable :Boolean)
    {
        if (enable) {
            CUEEngine.getInstance().startListening()
        } else {
            CUEEngine.getInstance().stopListening()

        }
    }



    inner class OutputListener : CUEReceiverCallbackInterface
    {
        override fun run(json :String) {
            val model:CUETrigger = CUETrigger.parse(json)

            onTriggerHeard(model)

        }
    }

    fun onTriggerHeard(model :CUETrigger)
    {
        if(uniqueId!![0]=='7')
        {
            if (uniqueId.equals(model.rawIndices))
            {


            }
            else
            {
                val builder = NotificationCompat.Builder(this@CheckForDistanceService, notificationChannelId)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setColor(resources.getColor(R.color.colorAccent))
                    .setContentTitle("Alert")
                    .setContentText("Please maintain enough social distance")
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    // Set the intent that will fire when the user taps the notification
                    .setAutoCancel(true)
                    .setVibrate(longArrayOf(1000, 1000, 1000, 1000, 1000))

                builder.setDefaults(Notification.DEFAULT_SOUND);


                with(NotificationManagerCompat.from(this@CheckForDistanceService)) {
                    // notificationId is a unique int for each notification that you must define
                    notify(12345, builder.build())
                }

            }
        }
    }

    // Create a BroadcastReceiver for ACTION_FOUND.
    private val discReciever: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {

            val action = intent.getIntExtra("BluetoothAdapter.EXTRA_SCAN_MODE", 1)
            if (BluetoothAdapter.SCAN_MODE_NONE == action || BluetoothAdapter.SCAN_MODE_CONNECTABLE == action) {
                val discoverableIntent: Intent =
                    Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                        putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 3600)
                    }
                startActivity(discoverableIntent)
            }
        }
    }

    private fun checkUserLocation()
    {
        val wifiScanReceiver: BroadcastReceiver = object : BroadcastReceiver() {
            @RequiresApi(Build.VERSION_CODES.M)
            override fun onReceive(c: Context, intent: Intent) {
                val success = intent.getBooleanExtra(
                    WifiManager.EXTRA_RESULTS_UPDATED, false
                )
                isIndoor = success
            }
        }

        val intentFilter = IntentFilter()
        intentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        this.registerReceiver(wifiScanReceiver, intentFilter)
        val wifiManager =
            this.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiManager.startScan();
    }


}
