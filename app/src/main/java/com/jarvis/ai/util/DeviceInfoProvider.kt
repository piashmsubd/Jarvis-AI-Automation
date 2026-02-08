package com.jarvis.ai.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build

/**
 * Provides device sensor and health information for the AI to report.
 */
object DeviceInfoProvider {

    fun getBatteryInfo(context: Context): BatteryInfo {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percentage = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        val temperature = (batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f

        return BatteryInfo(
            percentage = percentage,
            isCharging = isCharging,
            temperatureCelsius = temperature
        )
    }

    fun getNetworkInfo(context: Context): NetworkInfo {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val capabilities = network?.let { cm.getNetworkCapabilities(it) }

        val type = when {
            capabilities == null -> "Disconnected"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Unknown"
        }

        val downMbps = capabilities?.linkDownstreamBandwidthKbps?.let { it / 1000 } ?: 0
        val upMbps = capabilities?.linkUpstreamBandwidthKbps?.let { it / 1000 } ?: 0

        return NetworkInfo(
            type = type,
            isConnected = capabilities != null,
            downstreamMbps = downMbps,
            upstreamMbps = upMbps
        )
    }

    fun getDeviceSummary(context: Context): String {
        val battery = getBatteryInfo(context)
        val network = getNetworkInfo(context)

        return buildString {
            append("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            append("\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            append("\nBattery: ${battery.percentage}%")
            if (battery.isCharging) append(" (Charging)")
            append(" | ${battery.temperatureCelsius}°C")
            append("\nNetwork: ${network.type}")
            if (network.isConnected) {
                append(" | Down: ${network.downstreamMbps} Mbps | Up: ${network.upstreamMbps} Mbps")
            }
        }
    }

    data class BatteryInfo(
        val percentage: Int,
        val isCharging: Boolean,
        val temperatureCelsius: Float
    )

    data class NetworkInfo(
        val type: String,
        val isConnected: Boolean,
        val downstreamMbps: Int,
        val upstreamMbps: Int
    )
}
