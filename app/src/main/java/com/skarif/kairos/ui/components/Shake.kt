package com.skarif.kairos.ui.components

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlin.math.sqrt

/**
 * Calls [onShake] on a deliberate shake: two strong jolts within half a second, then a 1.5 s
 * rest so one shake counts once. Listens only while [enabled] and the app is in the foreground
 * (the sensor is released on pause), at the sensor's slowest useful rate.
 */
@Composable
fun ShakeDetector(enabled: Boolean, onShake: () -> Unit) {
    val context = LocalContext.current
    val latest = rememberUpdatedState(onShake)
    LifecycleResumeEffect(enabled) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val accel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (!enabled || sm == null || accel == null) return@LifecycleResumeEffect onPauseOrDispose { }
        var lastJolt = 0L
        var restUntil = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val (x, y, z) = Triple(e.values[0], e.values[1], e.values[2])
                val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
                if (g < 2.6f) return
                val now = SystemClock.elapsedRealtime()
                if (now < restUntil) return
                if (now - lastJolt in 80..500) {
                    restUntil = now + 1_500
                    lastJolt = 0L
                    latest.value()
                } else {
                    lastJolt = now
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_UI)
        onPauseOrDispose { sm.unregisterListener(listener) }
    }
}
