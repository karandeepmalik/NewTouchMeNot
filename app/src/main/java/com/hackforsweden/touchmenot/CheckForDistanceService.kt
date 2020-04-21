package com.hackforsweden.touchmenot


import android.app.*
import android.app.NotificationManager.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.*
import android.graphics.Color
import android.media.AudioAttributes
import android.net.Uri
import android.os.*
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.cueaudio.engine.CUEEngine
import com.cueaudio.engine.CUEReceiverCallbackInterface
import com.cueaudio.engine.CUETrigger
import com.cueaudio.engine.CUETrigger.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.anko.runOnUiThread
import java.util.*
import kotlin.math.pow


class CheckForDistanceService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var isServiceStarted = false
    private var bluetoothAdapter: BluetoothAdapter? = null
    private val notificationChannelId = "TouchMeNot"
    private var deviceAndSocialDistanceViolationCount =  mutableMapOf<String,Int>()
    private var lastDetectedDeviceAndTimestamp =  mutableMapOf<String,Long>()
    private val kalmanFilter = KalmanFilter()

    private var  firstOffsetOfBroadcastedSignal=7
    private var secondOffsetOfBroadcastedSignal:Int =0
    private var thirdOffsetOfBroadcastedSignal:Int =0
    private var broadcastedSignal:String? =null
    private var isDetectedDeviceBeyondThreeMeters = true


    private var apiKeyOfUltraSoundLib = "EH0GHbslb0pNWAxPf57qA6n23w4Zgu5U"

    override fun onBind(intent: Intent): IBinder? {
        // We don't provide binding, so return null
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int
    {
        if (intent != null)
        {
            val action = intent.action
            log("Service Start initiated. Using an intent with action $action", this)
            when (action) {
                Actions.START.name -> startService()
                Actions.STOP.name -> stopService()
                else -> log("This should never happen. No action in the received intent", this)
            }
        }
        else
        {
            log("Service Received null intent", this)
        }
        // by returning this we make sure the service is restarted if the system kills the service
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        log("The service has been created".toUpperCase(Locale.ROOT), this)
        val notification = createNotification()
        startForeground(1, notification)
        setupUltrasound()
    }

    override fun onDestroy()
    {
        super.onDestroy()
        log("The service has been destroyed".toUpperCase(Locale.ROOT), this)
        Toast.makeText(this, "Service destroyed", Toast.LENGTH_SHORT).show()
    }

    private fun startService()
    {

        if (isServiceStarted) return
        log("Starting the foreground service task", this)
        Toast.makeText(this, "Service starting its task", Toast.LENGTH_SHORT).show()
        isServiceStarted = true
        //populateDevicePowerMap()
        // we need this lock so our service gets not affected by Doze Mode
        wakeLock =
            (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "CheckForDistanceService::lock"
                ).apply {
                    acquire()
                }
            }
        // we're starting a loop in a coroutine
        GlobalScope.launch(Dispatchers.IO)
        {

            launch(Dispatchers.IO)
            {
                while (isServiceStarted) {
                    isDetectedDeviceBeyondThreeMeters = true
                    detectBluetoothDevices()
                    delay(15000)
                }
            }

            launch(Dispatchers.IO)
            {
                while (isServiceStarted) {
                    triggerTransmission()
                    delay(2000)
                }
            }

        }
        log("Service is no more started.End of the loop for the service",this@CheckForDistanceService)

    }

    private fun stopService()
    {
        isServiceStarted = false
        log("Stop Service Triggered. Cleaning of resources will happen now", this)
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
            log("Service is being stopped without being started: ${e.message}", this)
        }


    }


    private fun createNotification(): Notification
    {
        // depending on the Android API that we're dealing with we will have
        // to use a specific method to create the notification

        // For Oreo and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val customSoundUri = Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE
                    + "://" + packageName + "/raw/alarm")
            // Creating an Audio Attribute
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build()
            val channel = NotificationChannel(
                notificationChannelId,
                "Service notifications channel",
                IMPORTANCE_HIGH
            ).let {
                it.description = "Service channel"
                it.enableLights(true)
                it.lightColor = Color.RED
                it.enableVibration(true)
                it.vibrationPattern = longArrayOf(100, 200, 300, 400, 500, 400, 300, 200, 400)
                it.importance= IMPORTANCE_HIGH
                it.setSound(customSoundUri, audioAttributes)
                it
            }

            notificationManager.createNotificationChannel(channel)
            log("Created Notification Channel", this)
        }

        val pendingIntent: PendingIntent = Intent(this, MainActivity::class.java).let { notificationIntent ->
            PendingIntent.getActivity(this, 0, notificationIntent, 0)
        }

        val builder: Notification.Builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(
            this,
            notificationChannelId
        ) else Notification.Builder(this)

       log("Returning the appropriate  Notification Builder", this)
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
        log("Calculating the distance", this)
        /*
         * RSSI = TxPower - 10 * n * lg(d)
         * n = 2 (in free space)
         *
         * d = 10 ^ ((TxPower - RSSI) / (10 * n))
         */

        return kalmanFilter.filter(10.0.pow((txPower - rssi) / (10 * 2)),0.0)
    }

    private fun detectBluetoothDevices()
    {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter!=null) {
            log("Check if Bluetooth Adapter is enabled",this)
            if (!bluetoothAdapter!!.isEnabled)
            {

                bluetoothAdapter!!.enable()
                log("Bluetooth Adapter is enabled now", this)
            }

            // Register for broadcasts when a device is discovered.
            var filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
            // Register for broadcasts when discovery has finished
            this.registerReceiver(btDeviceDiscoveryStatusReciever, filter)
            filter = IntentFilter(
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED
            )
            this.registerReceiver(btDeviceDiscoveryStatusReciever, filter)

            if (bluetoothAdapter!!.isDiscovering) {
                log("Stop Discovery of devices if already discovering", this)
                bluetoothAdapter!!.cancelDiscovery()
            }
            log("Fresh start discovery of devices", this)
            bluetoothAdapter!!.startDiscovery()
        }
        else
        {
            log("Bluetooth Adapter is null", this)
        }

    }
    // Create a BroadcastReceiver for ACTION_FOUND.
    private val btDeviceDiscoveryStatusReciever: BroadcastReceiver = object : BroadcastReceiver()
    {

        override fun onReceive(context: Context, intent: Intent) {


            val action = intent.action
            if (BluetoothDevice.ACTION_FOUND == action)
            {
                log("DEVICELIST Bluetooth device found\n", context)
                val detectedDevice =
                    intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                GlobalScope.launch(Dispatchers.IO)
                {
                   // Do not take any action if this is a device from filtered list
                   if(!DbHelper.instance.checkDeviceIdExist(detectedDevice!!.address)) {
                       log("Filtered Device Encountered :- " + detectedDevice.address, context)

                       // Create a new device item
                       val detectedDeviceRSSI = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                       val detectedDeviceName = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                       if (detectedDevice != null) {
                           if (lastDetectedDeviceAndTimestamp.containsKey(detectedDevice.address)) {
                               val timeSinceLastDetection =
                                   lastDetectedDeviceAndTimestamp[detectedDevice.address]!! - System.currentTimeMillis()
                               if (timeSinceLastDetection > 15000) {
                                   // Since the last time this device was detected is greater than 15 secs
                                   // Stop tracking the count
                                   log(
                                       "Stop tracking count of "+detectedDevice.address+" since it was lastly discovered > 15 secs before",
                                       context
                                   )
                                   if (deviceAndSocialDistanceViolationCount.containsKey(detectedDevice.address))
                                       deviceAndSocialDistanceViolationCount.remove(detectedDevice.address)
                               }
                           }

                           lastDetectedDeviceAndTimestamp[detectedDevice.address] = System.currentTimeMillis()

                       }
                       val approxTxPowerOfDetectedDevice = 4.0
                       var distanceFromCurrentDevice = calculateDistance(detectedDeviceRSSI.toDouble(), approxTxPowerOfDetectedDevice)
                       if (detectedDeviceName != null)
                           log("Detected Device $detectedDeviceName", context)
                       log("Rssi $detectedDeviceRSSI", context)
                       log("Distance Measured $distanceFromCurrentDevice", context)
                       distanceFromCurrentDevice /= 2500

                       if (detectedDevice != null) {
                           if (distanceFromCurrentDevice < MainActivity.socialDistanceThreshold) {

                               if (deviceAndSocialDistanceViolationCount.containsKey(detectedDevice.address)) {
                                   var count = deviceAndSocialDistanceViolationCount[detectedDevice.address]!!
                                   count += 1
                                   val address = detectedDevice.address
                                   log(
                                       "Increment the  count of $address  by 1. The new count is $count",
                                       context
                                   )
                                   deviceAndSocialDistanceViolationCount[detectedDevice.address] = count
                                   log("Device: " + detectedDevice.address + " Count: $count", context)
                               } else {
                                   deviceAndSocialDistanceViolationCount[detectedDevice.address] = 1
                                   log(
                                       "Newly discovered Device: " + detectedDevice.address + "Count: 1",
                                       context
                                   )
                               }

                           } else {
                               log(
                                   "This device " + detectedDevice.address + " is obeying social distancing so  dont track its count",
                                   context
                               )
                               if (deviceAndSocialDistanceViolationCount.containsKey(detectedDevice.address)) {
                                   deviceAndSocialDistanceViolationCount.remove(detectedDevice.address)
                               }
                           }
                       }

                       for (detectedDevice in deviceAndSocialDistanceViolationCount) {
                           if ((detectedDevice.value >= MainActivity.socialTimeThreshold * 4) && !isDetectedDeviceBeyondThreeMeters) {

                               log(
                                   "This device " + detectedDevice.key + " has breached social distance for social distancing time " + MainActivity.socialTimeThreshold + " minutes. Issuing notification",
                                   context
                               )
                               DbHelper.instance.addHistory(detectedDevice.key)
                               runOnUiThread()
                               {
                                   val customSoundUri = Uri.parse(
                                       ContentResolver.SCHEME_ANDROID_RESOURCE
                                               + "://" + packageName + "/raw/alarm"
                                   )
                                   val notificationCompatBuilder = NotificationCompat.Builder(
                                       this@CheckForDistanceService,
                                       notificationChannelId
                                   )
                                       .setSmallIcon(R.drawable.ic_notification)
                                       .setColor(resources.getColor(R.color.colorAccent))
                                       .setContentTitle("Alert")
                                       .setContentText("Please maintain enough social distance")
                                       .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                                       .setSound(customSoundUri)
                                       // Set the intent that will fire when the user taps the notification
                                       .setAutoCancel(true)
                                       .setVibrate(longArrayOf(1000, 1000, 1000, 1000, 1000))

                                   log("Breach of  social distance. Creating Notification", context)


                                   with(NotificationManagerCompat.from(this@CheckForDistanceService)) {
                                       // notificationId is a unique int for each notification that you must define
                                       notify(12345, notificationCompatBuilder.build())
                                   }

                                   if (bluetoothAdapter!!.isDiscovering) {
                                       log(
                                           "Stop Discovery of devices if already discovering",
                                           this@CheckForDistanceService
                                       )
                                       bluetoothAdapter!!.cancelDiscovery()
                                   }
                               }
                               break
                           }

                       }
                   }
               }


            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED == action) {
                log("Scanning done..", context)
                Toast.makeText(context, "Scanning done..", Toast.LENGTH_SHORT).show()
            }


        }

    }


    private fun setupUltrasound() {

        log("Setting up Ultrasound", this)
        val rand = Random();


        secondOffsetOfBroadcastedSignal = rand.nextInt(100)
        thirdOffsetOfBroadcastedSignal = rand.nextInt(462)
        broadcastedSignal = "$firstOffsetOfBroadcastedSignal.$secondOffsetOfBroadcastedSignal.$thirdOffsetOfBroadcastedSignal"
        log("Generated unique id for Ultrasound transmission $broadcastedSignal", this)
        audioEngineSetup();
    }

    private fun audioEngineSetup(){


        CUEEngine.getInstance().setupWithAPIKey(this, apiKeyOfUltraSoundLib)
        CUEEngine.getInstance().setDefaultGeneration(2)
        CUEEngine.getInstance().setReceiverCallback( OutputListener())
        enableListening(true)
        CUEEngine.getInstance().isTransmittingEnabled = true

        log("Audio Engine is set up", this)
    }


    private fun triggerTransmission()
    {
        log("Triggering Transmission uniqueId : $broadcastedSignal ", this)
        queueInput(broadcastedSignal!!, MODE_TRIGGER, false);
    }

    private fun  queueInput( input :String, mode:Int, triggerAsNumber:Boolean)
    {
        val result:Int;

        when (mode) {
            MODE_TRIGGER ->
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
            val receivedSignal:CUETrigger = parse(json)
            onTriggerHeard(receivedSignal)
        }
    }

    fun onTriggerHeard(receivedSignal :CUETrigger)
    {
        if(broadcastedSignal!![0]=='7')
        {
            if (broadcastedSignal.equals(receivedSignal.rawIndices))
            {
                log("We heard  the same sound we sent to the other device. Ignoring it.", this@CheckForDistanceService)

            }
            else
            {
                // Since we could hear the UltrasoundSignal of a different device, that device  is less than 3 metres away
                isDetectedDeviceBeyondThreeMeters = false;
                log("We heard a the sound sent from the other device. Distance from device is less than 3 meters", this@CheckForDistanceService)

            }
        }
    }



}
