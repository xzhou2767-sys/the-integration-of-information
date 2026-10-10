package com.trae.info.client.android

import android.content.Context

/** 持有 Application Context，供平台层使用 SharedPreferences。 */
object AppContext {
    lateinit var app: Context
        private set

    fun init(context: Context) {
        app = context.applicationContext
    }
}
