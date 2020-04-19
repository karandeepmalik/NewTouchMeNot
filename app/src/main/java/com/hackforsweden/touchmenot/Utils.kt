package com.hackforsweden.touchmenot


import android.content.Context
import android.util.Log


fun log(msg: String,context: Context) {
    Log.d("TouchMeNot", msg)
    DbHelper.getInstance(context).addLog(msg)
}