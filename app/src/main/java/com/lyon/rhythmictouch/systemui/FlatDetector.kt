package com.lyon.rhythmictouch.systemui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.lyon.rhythmictouch.RhythmicConstants

class FlatDetector(context: Context, private val configBridge: ConfigBridge) : SensorEventListener {

    companion object {
        private const val TAG = "RhythmicFlat"

        private const val FLAT_THRESHOLD_XY = 1.5f
        private const val FLAT_THRESHOLD_Z_MIN = 8.0f
        private const val FLAT_CONFIRM_MS = 0L
        private const val PICKUP_CONFIRM_MS = 0L
        private const val SENSOR_DELAY_US = 200_000

        private const val STATIONARY_WINDOW_SIZE = 30
        private const val STATIONARY_VARIANCE_THRESHOLD = 0.01f
        private const val STATIONARY_CONFIRM_MS = 8000L
        private const val MOVING_CONFIRM_MS = 0L
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile
    var isFlat = false
        private set

    @Volatile
    var isPaused = false
        private set

    private var flatSinceMs = 0L
    private var pickupSinceMs = 0L

    private val accelHistory = FloatArray(STATIONARY_WINDOW_SIZE)
    private var historyIndex = 0
    private var historyCount = 0

    @Volatile
    var isStationary = false
        private set

    private var stationarySinceMs = 0L
    private var movingSinceMs = 0L

    var onStateChanged: ((paused: Boolean) -> Unit)? = null

    private var hasStarted = false

    fun start() {
        if (accelerometer == null) {
            RhythmicLog.x(TAG, "No accelerometer available")
            return
        }
        val cfg = configBridge.config
        if (!cfg.flatDetection && !cfg.stationaryDetection) {
            RhythmicLog.x(TAG, "Both flat and stationary detection disabled")
            return
        }
        try {
            if (handlerThread?.isAlive != true) {
                handlerThread?.quitSafely()
                handlerThread = HandlerThread("flat-detector").apply { start() }
                handler = Handler(handlerThread!!.looper)
            }
            sensorManager.registerListener(this, accelerometer, SENSOR_DELAY_US, handler)
            hasStarted = true
            RhythmicLog.x(TAG, "Started (flat=${cfg.flatDetection}, stationary=${cfg.stationaryDetection})")
        } catch (e: Throwable) {
            RhythmicLog.x(TAG, "Failed to start: ${e.message}")
            hasStarted = false
        }
    }

    fun stop() {
        try {
            sensorManager.unregisterListener(this)
        } catch (_: Throwable) {}
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        hasStarted = false
        isFlat = false
        isStationary = false
        isPaused = false
        historyCount = 0
    }

    fun refreshEnabled() {
        val cfg = configBridge.config
        val enabled = cfg.flatDetection || cfg.stationaryDetection
        if (enabled && !hasStarted) {
            start()
        } else if (!enabled && hasStarted) {
            stop()
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        val cfg = configBridge.config

        val ax = event.values[0]
        val ay = event.values[1]
        val az = event.values[2]
        val magnitude = kotlin.math.sqrt((ax * ax + ay * ay + az * az).toDouble()).toFloat()

        val now = SystemClock.elapsedRealtime()

        var changed = false

        if (cfg.flatDetection) {
            val flatNow = kotlin.math.abs(ax) < FLAT_THRESHOLD_XY &&
                kotlin.math.abs(ay) < FLAT_THRESHOLD_XY &&
                az > FLAT_THRESHOLD_Z_MIN

            if (flatNow) {
                if (!isFlat) {
                    isFlat = true
                    flatSinceMs = now
                    pickupSinceMs = 0L
                    RhythmicLog.d(TAG, "📱 Device went flat: ax=${"%.1f".format(ax)} ay=${"%.1f".format(ay)} az=${"%.1f".format(az)}")
                } else if (!isFlat) {
                }
            } else {
                if (isFlat) {
                    isFlat = false
                    pickupSinceMs = now
                    flatSinceMs = 0L
                    RhythmicLog.d(TAG, "📱 Device picked up: ax=${"%.1f".format(ax)} ay=${"%.1f".format(ay)} az=${"%.1f".format(az)}")
                }
            }
        }

        if (cfg.stationaryDetection) {
            accelHistory[historyIndex] = magnitude
            historyIndex = (historyIndex + 1) % STATIONARY_WINDOW_SIZE
            if (historyCount < STATIONARY_WINDOW_SIZE) historyCount++

            if (historyCount >= STATIONARY_WINDOW_SIZE) {
                var sum = 0f
                for (i in 0 until historyCount) sum += accelHistory[i]
                val mean = sum / historyCount
                var variance = 0f
                for (i in 0 until historyCount) {
                    val d = accelHistory[i] - mean
                    variance += d * d
                }
                variance /= historyCount

                val isStill = variance < STATIONARY_VARIANCE_THRESHOLD

                if (isStill) {
                    if (!isStationary) {
                        if (stationarySinceMs == 0L) stationarySinceMs = now
                        if (now - stationarySinceMs >= STATIONARY_CONFIRM_MS) {
                            isStationary = true
                            RhythmicLog.x(TAG, "⏸️ Stationary confirmed (var=${"%.4f".format(variance)})")
                            changed = true
                        }
                    }
                    movingSinceMs = 0L
                } else {
                    if (isStationary) {
                        if (movingSinceMs == 0L) movingSinceMs = now
                        if (now - movingSinceMs >= MOVING_CONFIRM_MS) {
                            isStationary = false
                            stationarySinceMs = 0L
                            RhythmicLog.x(TAG, "▶️ Moving confirmed, resuming (var=${"%.4f".format(variance)})")
                            changed = true
                        }
                    } else {
                        stationarySinceMs = 0L
                    }
                }
            }
        }

        val shouldBePaused = isFlat || isStationary
        if (shouldBePaused != isPaused) {
            isPaused = shouldBePaused
            changed = true
        }

        if (changed) {
            onStateChanged?.invoke(isPaused)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
