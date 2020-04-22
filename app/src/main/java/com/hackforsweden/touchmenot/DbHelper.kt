package com.hackforsweden.touchmenot;

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class DbHelper(factory: SQLiteDatabase.CursorFactory?) :
    SQLiteOpenHelper(MyApplication.appContext, DATABASE_NAME,
        factory, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        val createLogTable = ("CREATE TABLE " +
                LOG_TABLE_NAME + "("
                + LOG_COLUMN_TIME + " INTEGER," +
                 LOG_COLUMN_TIMEFORMAT + " TEXT," +

                LOG_COLUMN_LOG_VALUE
                + " TEXT" + ")")
        db.execSQL(createLogTable)

        val historyTable = ("CREATE TABLE " +
                HISTORY_TABLE_NAME + "("
                + HISTORY_COLUMN_MAC + " TEXT," +
                HISTORY_COLUMN_DAY + " INTEGER," +
                HISTORY_COLUMN_MONTH + " TEXT " +  ")")
        db.execSQL(historyTable)

        val deviceExceptionTable = ("CREATE TABLE " +
                DEVICES_TABLE_NAME + "("
                + DEVICE_COLUMN_NAME + " TEXT," +

                DEVICE_COLUMN_ID + " TEXT " + ")")
        db.execSQL(deviceExceptionTable)

        val uniqueDeviceId = ("CREATE UNIQUE INDEX id_deviceid ON $DEVICES_TABLE_NAME ($DEVICE_COLUMN_ID)")

        db.execSQL(uniqueDeviceId)

    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $LOG_TABLE_NAME")
        db.execSQL("DROP TABLE IF EXISTS $HISTORY_TABLE_NAME")
        db.execSQL("DROP TABLE IF EXISTS $DEVICES_TABLE_NAME")

        onCreate(db)
    }

    fun addLog(logValue: String) {
        val values = ContentValues()
        values.put(LOG_COLUMN_TIME, System.currentTimeMillis())
        values.put(LOG_COLUMN_TIMEFORMAT, SimpleDateFormat("yyyy-MM-dd HH:mm a").format(Date()))

        values.put(LOG_COLUMN_LOG_VALUE, logValue)

        val db = this.writableDatabase
        db.insert(LOG_TABLE_NAME, null, values)
    }

    fun addHistory(macAddress: String) {
        val values = ContentValues()
        val currentDate: String = SimpleDateFormat("dd", Locale.getDefault()).format(Date())
        val currentMonth: String = SimpleDateFormat("MMM", Locale.getDefault()).format(Date())
        values.put(HISTORY_COLUMN_MAC, macAddress)
        values.put(HISTORY_COLUMN_DAY,currentDate.toInt())
        values.put(HISTORY_COLUMN_MONTH,currentMonth)
        val db = this.writableDatabase
        db.insert(HISTORY_TABLE_NAME, null, values)

    }

    fun addDeviceId(deviceId: String?,deviceName:String?) {
        val values = ContentValues()

        values.put(DEVICE_COLUMN_NAME, deviceName)

        values.put(DEVICE_COLUMN_ID, deviceId)

        val db = this.writableDatabase
        db.insert(DEVICES_TABLE_NAME, null, values)

    }

    fun getAllDevices(listitem:ArrayList<DeviceListItem>?,deviceIdSet:MutableSet<String?>?) {
        val db = this.readableDatabase
        val cursor =db.rawQuery("SELECT * FROM $DEVICES_TABLE_NAME", null)


        cursor.moveToFirst()
        while (!cursor.isAfterLast)
        {
            deviceIdSet?.add(
                cursor.getString(
                    cursor.getColumnIndex(
                        DEVICE_COLUMN_ID
                    )
                )
            )
            listitem?.add(
                DeviceListItem(
                    cursor.getString(cursor.getColumnIndex(DEVICE_COLUMN_NAME)),
                    cursor.getString(
                        cursor.getColumnIndex(
                            DEVICE_COLUMN_ID
                        )
                    ),
                    true
                )
            )

            cursor.moveToNext()
        }

        cursor.close()

    }

     fun deleteDeviceId(deviceId:String?){
        val db = this.writableDatabase
        val deleteQuery = "DELETE from $DEVICES_TABLE_NAME where $DEVICE_COLUMN_ID ='$deviceId'"
        try {
            db.execSQL(deleteQuery)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

     fun deleteAllDeviceId(){
        val db = this.writableDatabase
        val deleteQuery = "DELETE from $DEVICES_TABLE_NAME"
        try {
            db.execSQL(deleteQuery)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun deviceIdExistsInFilteredDeviceList(deviceId: String):Boolean {
        val db = this.readableDatabase
        val cursor =db.rawQuery("SELECT EXISTS(SELECT 1 FROM $DEVICES_TABLE_NAME WHERE $DEVICE_COLUMN_ID = '$deviceId' LIMIT 1)", null)
         cursor.moveToFirst()
        return if (cursor.getInt(0) == 1) {
            cursor.close()
            true
        } else {
            cursor.close()
            false
        }

    }

    fun getLogFile(logFile: File) {
        val db = this.readableDatabase
         val cursor =db.rawQuery("SELECT * FROM $LOG_TABLE_NAME", null)
        val writer = FileWriter(logFile)
        val myDeviceModel = Build.MODEL
        val myDeviceManufacturer = Build.MANUFACTURER
        val myDeviceBrand =Build.BRAND
        val myOS = Build.VERSION.RELEASE
        val mySDK = Build.VERSION.SDK_INT
        writer.append("Device Model : $myDeviceModel \n")
        writer.append("Device Brand : $myDeviceBrand \n")
        writer.append("Device Manufacturer  : $myDeviceManufacturer \n")
        writer.append("Device OS  : $myOS \n")
        writer.append("Device SDK  : $mySDK \n")
        cursor!!.moveToFirst()
         while (cursor.moveToNext()) {
             writer.append((cursor.getString(cursor.getColumnIndex(LOG_COLUMN_TIMEFORMAT))+" : "+cursor.getString(cursor.getColumnIndex(
                 LOG_COLUMN_LOG_VALUE
             ))))
              writer.append("\n\r")

         }
        writer.flush()
        writer.close()
        cursor.close()

        deleteAllLogs()
     }


    fun getHistoryFile(historyFile: File) {
        val db = this.readableDatabase
        val cursor =db.rawQuery("SELECT * FROM $HISTORY_TABLE_NAME", null)
        val writer = FileWriter(historyFile)
        val myDeviceModel = Build.MODEL
        val myDeviceManufacturer = Build.MANUFACTURER
        val myOS = Build.VERSION.RELEASE
        writer.append("Device Model : $myDeviceModel \n")
        writer.append("Device Manufacturer  : $myDeviceManufacturer \n")
        writer.append("Device OS  : $myOS \n")
        cursor!!.moveToFirst()
        while (!cursor.isAfterLast)
        {
            writer.append(cursor.getString(cursor.getColumnIndex(HISTORY_COLUMN_MAC))+" : "+cursor.getString(cursor.getColumnIndex(
                        HISTORY_COLUMN_DAY))+"-"+cursor.getString(cursor.getColumnIndex( HISTORY_COLUMN_MONTH)))
            writer.append("\n\r")
            cursor.moveToNext()
        }
        writer.flush()
        writer.close()
        cursor.close()
    }

    private fun deleteAllLogs(){
        val db = this.writableDatabase
        val deleteQuery = "DELETE from $LOG_TABLE_NAME"
        try {
            db.execSQL(deleteQuery)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }



    private object Holder { val INSTANCE = DbHelper(null) }




    companion object {

        val instance: DbHelper by lazy { Holder.INSTANCE }

        private const val DATABASE_VERSION = 1
        private const val DATABASE_NAME = "socialdistancelogs.db"
        const val LOG_TABLE_NAME = "logtable"
        const val HISTORY_TABLE_NAME = "historytable"
        const val DEVICES_TABLE_NAME = "devicestable"

        const val LOG_COLUMN_TIME = "time"
        const val LOG_COLUMN_TIMEFORMAT = "timeformatted"
        const val LOG_COLUMN_LOG_VALUE = "logvalue"
        const val HISTORY_COLUMN_MAC = "macaddress"
        const val HISTORY_COLUMN_DAY = "day"
        const val HISTORY_COLUMN_MONTH = "month"
        const val DEVICE_COLUMN_ID = "deviceid"
        const val DEVICE_COLUMN_NAME = "devicename"

    }

}

