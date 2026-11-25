package dev.caraalink.link

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class EmulatorWifiTest {
    private fun ip(value: String) = InetAddress.getByName(value)
    @Test fun subnet24() {
        assertTrue(EmulatorWifi.sameSubnet(ip("192.168.10.2"), ip("192.168.10.200"), 24))
        assertFalse(EmulatorWifi.sameSubnet(ip("192.168.10.2"), ip("192.168.11.200"), 24))
    }
    @Test fun subnet25() {
        assertTrue(EmulatorWifi.sameSubnet(ip("10.0.0.130"), ip("10.0.0.250"), 25))
        assertFalse(EmulatorWifi.sameSubnet(ip("10.0.0.130"), ip("10.0.0.10"), 25))
    }
    @Test fun rejectsWildcardAndIpv6() {
        assertFalse(EmulatorWifi.sameSubnet(ip("10.0.0.2"), ip("10.0.0.3"), 0))
        assertFalse(EmulatorWifi.sameSubnet(ip("10.0.0.2"), ip("::1"), 24))
    }
}
