package com.github.deadknight.ardaplatform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.arda.vehicle.admin.IArdaVehicleSourceManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal object VehicleSourceManagerClient {
    private val lock = Any()
    @Volatile private var manager: IArdaVehicleSourceManager? = null
    @Volatile private var binding = false
    private var latch = CountDownLatch(1)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            manager = IArdaVehicleSourceManager.Stub.asInterface(service)
            binding = false
            latch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            manager = null
            binding = false
            synchronized(lock) { latch = CountDownLatch(1) }
        }

        override fun onBindingDied(name: ComponentName) {
            onServiceDisconnected(name)
        }

        override fun onNullBinding(name: ComponentName) {
            onServiceDisconnected(name)
        }
    }

    fun connect(context: Context) {
        if (manager != null || binding) return
        synchronized(lock) {
            if (manager != null || binding) return
            binding = true
            latch = CountDownLatch(1)
            val intent = Intent().setComponent(
                ComponentName(
                    "com.arda.vehicle.manager",
                    "com.arda.vehicle.manager.ArdaVehicleManagerService",
                ),
            )
            if (!context.applicationContext.bindService(
                    intent, connection, Context.BIND_AUTO_CREATE)) {
                binding = false
                latch.countDown()
            }
        }
    }

    fun require(context: Context): IArdaVehicleSourceManager {
        manager?.let { return it }
        connect(context)
        latch.await(3, TimeUnit.SECONDS)
        return manager ?: error("Vehicle source manager is unavailable")
    }
}
