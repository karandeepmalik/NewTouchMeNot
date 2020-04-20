package com.hackforsweden.touchmenot

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.TextView

class ScannedDeviceListAdapter (var filteredDeviceIdSet:MutableSet<String?>?,private val context: Context,
                                private val dataList: ArrayList<DeviceListItem>) : BaseAdapter() {

    private val inflater: LayoutInflater = this.context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
    override fun getCount(): Int { return dataList.size }
    override fun getItem(position: Int): Int { return position }
    override fun getItemId(position: Int): Long { return position.toLong() }

    fun updateScannedAdapter(){
        this.notifyDataSetChanged()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        var dataitem = dataList[position]

        val rowView = inflater.inflate(R.layout.device_row, parent, false)
        rowView.findViewById<TextView>(R.id.tvDeviceName).text = dataitem.deviceName

        if(dataitem.showRemove){
            rowView.findViewById<Button>(R.id.remove).visibility = View.VISIBLE
            rowView.findViewById<Button>(R.id.Add).visibility = View.GONE

        }else{
            rowView.findViewById<Button>(R.id.remove).visibility = View.GONE
        }

        if(filteredDeviceIdSet!!.contains(dataitem.address)){
            rowView.findViewById<TextView>(R.id.tvAdded) .visibility = View.VISIBLE

        }else{
            rowView.findViewById<TextView>(R.id.tvAdded) .visibility = View.GONE

        }

        rowView.findViewById<Button>(R.id.Add).setOnClickListener {

            if (!filteredDeviceIdSet!!.contains(dataitem.address)) {

            DbHelper.instance.addDeviceId(dataitem.address,dataitem.deviceName)
            rowView.findViewById<TextView>(R.id.tvAdded) .visibility = View.VISIBLE

            filteredDeviceIdSet?.add(dataitem.address)
                BluetoothScannedDevices.filteredDevicesListAdapter?.updateAdapter(DeviceListItem(dataitem.deviceName,dataitem.address,true))
           // filteredDevicesListAdapter?.updateAdapter(DeviceListItem(dataitem.deviceName,dataitem.address,true))

            }
        }

        rowView.findViewById<Button>(R.id.remove).setOnClickListener{

            DbHelper.instance.deleteDeviceId(dataitem.address)

            dataList.removeAt(position)
            this.notifyDataSetChanged()
        }


        rowView.tag = position
        return rowView
    }


}