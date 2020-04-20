package com.hackforsweden.touchmenot

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.ListView
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AppCompatActivity


class BluetoothScannedDevices : AppCompatActivity() {


    var mBluetoothAdapter: BluetoothAdapter? = null
    var scannedDevicesListView: ListView? = null
    var filteredDevicesListView: ListView? = null
    var detectedDevices: ArrayList<DeviceListItem>? = null
    var filteredDevices: ArrayList<DeviceListItem>? = null
    var filteredDevicesSet:MutableSet<String?>? = null

    companion object {
        var filteredDevicesListAdapter: FilteredDeviceListAdapter? = null
        var scannedDeviceListAdapter: ScannedDeviceListAdapter? = null

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bluetooth_scanned_devices)

        val ab: ActionBar? = supportActionBar
        ab!!.setDisplayHomeAsUpEnabled(true)
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

        scannedDevicesListView = findViewById(R.id.lv_scan_devices)
        filteredDevicesListView = findViewById(R.id.lv_filtered_devices)

        detectedDevices = ArrayList<DeviceListItem>()
        filteredDevices= ArrayList<DeviceListItem>()

         setUpFilteredDevices()

    }


    override fun onDestroy() {
        super.onDestroy()

    }

    private fun setUpScanDevices() {
        var filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        // Register for broadcasts when discovery has finished
        this.registerReceiver(btReciever, filter)

        detectedDevices?.clear()

        scannedDeviceListAdapter = ScannedDeviceListAdapter(filteredDevicesSet,
             this,
            detectedDevices!!
        )
        scannedDevicesListView?.adapter = scannedDeviceListAdapter
        mBluetoothAdapter!!.startDiscovery()

    }


    private fun setUpPairedDevices() {

        detectedDevices?.clear()

        getArrayOfAlreadyPairedBluetoothDevices()

    }

    private fun setUpRemoveDevices() {

        filteredDevices?.clear()
        filteredDevicesSet?.clear()
        DbHelper.instance.deleteAllDeviceId()
        filteredDevicesListAdapter?.notifyDataSetChanged()
        scannedDeviceListAdapter?.notifyDataSetChanged()

    }

    private fun setUpFilteredDevices() {
        filteredDevices?.clear()
         filteredDevicesSet = mutableSetOf<String?>();

         DbHelper.instance.getAllDevices(filteredDevices,filteredDevicesSet)
        filteredDevicesListAdapter = FilteredDeviceListAdapter(filteredDevicesSet,
            this,
            filteredDevices!!
        )
        filteredDevicesListView?.adapter = filteredDevicesListAdapter

    }

    private fun getArrayOfAlreadyPairedBluetoothDevices() {
        val pairedDevices: MutableSet<BluetoothDevice> =
            mBluetoothAdapter!!.bondedDevices

        // If there are any paired devices
        if (pairedDevices.size > 0) {
            // Loop through paired devices
            for (device in pairedDevices) { // Create the device object and add it to the arrayList of devices

                detectedDevices?.add(DeviceListItem(device.name, device.address, false))
            }

        }

        scannedDeviceListAdapter = ScannedDeviceListAdapter(filteredDevicesSet,
             this,
            detectedDevices!!
        )
        scannedDevicesListView?.adapter = scannedDeviceListAdapter
    }


    private val btReciever: BroadcastReceiver = object : BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent) {
            try {

                val action = intent.action
                if (BluetoothDevice.ACTION_FOUND == action) {

                    val device =
                        intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)

                    // Create a new device item
                    var name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                     if(name ==null)
                     {
                         name ="unknown"
                     }
                    detectedDevices?.add(DeviceListItem(name, device!!.address, false))
                    scannedDeviceListAdapter?.notifyDataSetChanged()

                }

            } catch (ex: Exception) {

            }

        }

    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.action_scan_devices -> { setUpScanDevices()
            // User chose the "Settings" item, show the app settings UI...
            true
        }

        R.id.action_remove_all_devices-> {
            setUpRemoveDevices()

            // User chose the "Favorite" action, mark the current item
            // as a favorite...
            true
        }
        R.id.action_list_paired_devices-> {

            setUpPairedDevices()

            // User chose the "Favorite" action, mark the current item
            // as a favorite...
            true
        }

        else -> {
            // If we got here, the user's action was not recognized.
            // Invoke the superclass to handle it.
            super.onOptionsItemSelected(item)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        val inflater = menuInflater
        inflater.inflate(R.menu.menu_scan, menu)
        return super.onCreateOptionsMenu(menu)
    }

}
