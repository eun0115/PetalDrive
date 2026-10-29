package com.eun0115.petaldrive

import android.app.Application
import com.huawei.hms.maps.MapsInitializer

class DriveApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Map Kit 5.0+ needs the API key set before any MapView is created.
        if (BuildConfig.PETAL_API_KEY.isNotBlank()) {
            MapsInitializer.setApiKey(BuildConfig.PETAL_API_KEY)
        }
    }
}
