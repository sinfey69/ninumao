package com.example.ninumao.util

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface

// NetworkUtils 提供局域网 IP 等网络工具方法。
object NetworkUtils {

    // getLanIpAddress 获取当前设备的 IPv4 地址，供手机扫码访问配置页。
    fun getLanIpAddress(context: Context): String? {
        val addresses = collectIpv4Addresses()
        return addresses.firstOrNull { isPrivateLanIp(it) }
            ?: addresses.firstOrNull { isEmulatorIp(it) }
            ?: addresses.firstOrNull()
    }

    // isEmulatorIp 判断是否为 Android 模拟器常用网段。
    fun isEmulatorIp(ip: String): Boolean {
        return ip.startsWith("10.0.2.")
    }

    // isPrivateLanIp 判断是否为常见局域网私网地址。
    private fun isPrivateLanIp(ip: String): Boolean {
        return ip.startsWith("192.168.") ||
            ip.startsWith("10.") ||
            ip.startsWith("172.")
    }

    // collectIpv4Addresses 收集所有非回环 IPv4 地址。
    private fun collectIpv4Addresses(): List<String> {
        return NetworkInterface.getNetworkInterfaces()?.toList()?.flatMap { networkInterface ->
            networkInterface.inetAddresses.toList().mapNotNull { address ->
                if (!address.isLoopbackAddress && address is Inet4Address) {
                    address.hostAddress
                } else {
                    null
                }
            }
        }.orEmpty().distinct()
    }

    // buildConfigUrl 拼接手机可访问的配置页 URL。
    fun buildConfigUrl(ip: String, port: Int, pin: String): String {
        return "http://$ip:$port/?pin=$pin"
    }
}
