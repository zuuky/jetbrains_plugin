@file:Suppress("unused")

package dev.sweep.assistant.data

import dev.sweep.assistant.settings.SweepMetaData
import dev.sweep.assistant.utils.getDebugInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 发送给 next-edit 服务的请求基类，携带调试信息与稳定的设备标识。
 * （debugInfo/deviceId 序列化后随 HTTP 请求发送，故不视为普通未使用属性。）
 */
@Serializable
abstract class BaseRequest {
    @SerialName("debug_info")
    val debugInfo: String = getDebugInfo()

    @SerialName("device_id")
    val deviceId: String = SweepMetaData.getInstance().getOrCreateDeviceId()
}
