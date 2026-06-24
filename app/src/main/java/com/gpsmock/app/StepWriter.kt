package com.gpsmock.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import kotlinx.coroutines.*
import java.time.Instant
import java.time.ZoneId

object StepWriter {

    private const val TAG = "StepWriter"
    private const val HC_PACKAGE = "com.google.android.apps.healthdata"

    interface Callback {
        fun onSuccess(totalSteps: Long)
        fun onError(message: String)
    }

    interface PermissionCallback {
        fun onResult(granted: Boolean)
    }

    @JvmField
    val REQUIRED_PERMISSIONS: Set<String> = setOf(
        HealthPermission.createWritePermission(StepsRecord::class)
    )

    @JvmStatic
    fun isAvailable(context: Context): Boolean {
        return HealthConnectClient.isProviderAvailable(context)
    }

    @JvmStatic
    fun createPermissionContract(): ActivityResultContract<Set<String>, Set<String>> {
        return PermissionController.createRequestPermissionResultContract()
    }

    @JvmStatic
    fun getInstallIntent(): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("market://details?id=$HC_PACKAGE")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    @JvmStatic
    fun checkPermissions(context: Context, callback: PermissionCallback) {
        if (!isAvailable(context)) {
            callback.onResult(false)
            return
        }
        val client = HealthConnectClient.getOrCreate(context)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val granted = client.permissionController.getGrantedPermissions(REQUIRED_PERMISSIONS)
                withContext(Dispatchers.Main) {
                    callback.onResult(granted.containsAll(REQUIRED_PERMISSIONS))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Permission check failed: ${e.message}")
                withContext(Dispatchers.Main) { callback.onResult(false) }
            }
        }
    }

    /**
     * 將步數寫入 Health Connect。
     * 時間範圍為「現在往前推 durationHours 小時」，
     * 切成 30 分鐘區段，每段步數加上 ±15% 隨機波動。
     */
    @JvmStatic
    fun writeSteps(context: Context, totalSteps: Long, durationHours: Double, callback: Callback) {
        if (!isAvailable(context)) {
            callback.onError("Health Connect 未安裝")
            return
        }
        val client = try {
            HealthConnectClient.getOrCreate(context)
        } catch (e: Exception) {
            callback.onError("Health Connect 無法使用: ${e.message}")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val now = Instant.now()
                val totalSeconds = (durationHours * 3600).toLong()
                val start = now.minusSeconds(totalSeconds)
                val zoneId = ZoneId.systemDefault()

                // 切成 30 分鐘區段
                val chunkSeconds = 1800L
                val chunks = mutableListOf<Pair<Instant, Instant>>()
                var t = start
                while (t.isBefore(now)) {
                    val end = if (t.plusSeconds(chunkSeconds).isAfter(now)) now else t.plusSeconds(chunkSeconds)
                    if (end.isAfter(t)) chunks.add(t to end)
                    t = end
                }

                if (chunks.isEmpty()) {
                    withContext(Dispatchers.Main) { callback.onError("時間範圍無效") }
                    return@launch
                }

                val basePerChunk = totalSteps.toDouble() / chunks.size
                val records = mutableListOf<StepsRecord>()
                var remaining = totalSteps

                for (i in chunks.indices) {
                    val (chunkStart, chunkEnd) = chunks[i]
                    val steps: Long = if (i == chunks.size - 1) {
                        remaining
                    } else {
                        val variation = 1.0 + (Math.random() * 0.3 - 0.15)
                        (basePerChunk * variation).toLong().coerceIn(1, remaining)
                    }
                    remaining -= steps
                    if (steps <= 0L) continue

                    val offset = zoneId.rules.getOffset(chunkStart)
                    records.add(
                        StepsRecord(
                            count = steps,
                            startTime = chunkStart,
                            startZoneOffset = offset,
                            endTime = chunkEnd,
                            endZoneOffset = offset
                        )
                    )
                }

                client.insertRecords(records)
                Log.i(TAG, "Wrote ${records.size} records, total $totalSteps steps over ${durationHours}h")
                withContext(Dispatchers.Main) { callback.onSuccess(totalSteps) }
            } catch (e: Exception) {
                Log.e(TAG, "Write failed: ${e.message}")
                withContext(Dispatchers.Main) { callback.onError(e.message ?: "寫入失敗") }
            }
        }
    }
}
