package dev.caraalink.link

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress

/** Only a real Wi-Fi link's assigned IPv4 address, never cellular or a wildcard. */
internal object EmulatorWifi {
    data class Endpoint(val address: Inet4Address, val prefixLength: Int) {
        fun contains(peer: InetAddress): Boolean = sameSubnet(address, peer, prefixLength)
    }

    fun current(context: Context): Endpoint? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val networks = listOfNotNull(manager.activeNetwork) + manager.allNetworks.toList()
        return networks.distinct().firstNotNullOfOrNull { network ->
            val caps = manager.getNetworkCapabilities(network)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@firstNotNullOfOrNull null
            manager.getLinkProperties(network)?.linkAddresses?.firstNotNullOfOrNull { link ->
                val ip = link.address as? Inet4Address ?: return@firstNotNullOfOrNull null
                if (ip.isLoopbackAddress || ip.isLinkLocalAddress || ip.isAnyLocalAddress ||
                    link.prefixLength !in 1..32) null else Endpoint(ip, link.prefixLength)
            }
        }
    }

    fun sameSubnet(local: InetAddress, remote: InetAddress, prefix: Int): Boolean {
        if (local !is Inet4Address || remote !is Inet4Address || prefix !in 1..32) return false
        val a = local.address
        val b = remote.address
        for (i in 0 until prefix) {
            val mask = 1 shl (7 - i % 8)
            if ((a[i / 8].toInt() and mask) != (b[i / 8].toInt() and mask)) return false
        }
        return true
    }
}
