package app.gamenative.utils

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Turns off Android's child-process limit from the device itself through Wireless debugging,
 * so users without a PC (Android 12/13, no Developer-options switch) can fix "game closes by
 * itself". Ported from DroidDeck's core/WirelessAdbFix.kt (GPL-3.0); scope is only this setting.
 *
 * Flow: user enables Wireless debugging → "Pair device with pairing code" → enters the pairing
 * port + code here → we pair once, then find the connect port via mDNS and run the commands.
 */
object WirelessAdbFix {
    private const val CERT_FILE = "wireless-adb-cert.pem"
    private const val KEY_FILE = "wireless-adb-key.pem"
    private const val LOOPBACK = "127.0.0.1"

    @Synchronized
    private fun loadOrCreateIdentity(context: Context) {
        val dir = context.noBackupFilesDir
        val cert = File(dir, CERT_FILE)
        val key = File(dir, KEY_FILE)
        if (cert.isFile && key.isFile) {
            KadbCert.set(cert.readBytes(), key.readBytes())
            return
        }
        val identity = KadbCert.get(notAfter = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(3650))
        KadbCert.set(identity.first, identity.second)
        cert.writeBytes(identity.first)
        key.writeBytes(identity.second)
    }

    fun hasPairing(context: Context): Boolean =
        File(context.noBackupFilesDir, CERT_FILE).isFile && File(context.noBackupFilesDir, KEY_FILE).isFile

    /** One-time pairing with the code shown in "Pair device with pairing code". */
    suspend fun pair(context: Context, pairingPort: Int, pairingCode: String) {
        loadOrCreateIdentity(context.applicationContext)
        Kadb.pair(LOOPBACK, pairingPort, pairingCode.trim(), "GameNative")
    }

    /** Connects to this device's own ADB and disables the limit. Blocking: call off the main thread. */
    fun disableChildProcessLimit(context: Context, connectPort: Int? = null) {
        val app = context.applicationContext
        loadOrCreateIdentity(app)
        val port = connectPort ?: tlsPortProperty() ?: findConnectPort(app)
            ?: error("Wireless debugging port not found. Keep Wireless debugging on and try again.")
        Kadb.create(LOOPBACK, port).use { adb ->
            PhantomProcessLimit.disableShellCommands().forEach { cmd ->
                val r = adb.shell(cmd)
                check(r.exitCode == 0) { r.allOutput.ifBlank { "ADB command failed (${r.exitCode})" } }
            }
            val v = adb.shell(PhantomProcessLimit.verifyCommand())
            check(v.exitCode == 0 && PhantomProcessLimit.verifiedDisabled(v.output)) {
                "Android did not confirm the change: ${v.allOutput.trim()}"
            }
        }
    }

    private fun tlsPortProperty(): Int? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, "service.adb.tls.port") as? String
    }.getOrNull()?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }

    /** Finds the TLS connect port from Android's `_adb-tls-connect._tcp` mDNS record. */
    private fun findConnectPort(context: Context, timeoutSeconds: Long = 12): Int? {
        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return null
        val port = AtomicInteger(-1)
        val latch = CountDownLatch(1)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType?.contains("_adb-tls-connect._tcp") != true) return
                runCatching {
                    @Suppress("DEPRECATION")
                    manager.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(s: NsdServiceInfo, e: Int) = Unit
                        override fun onServiceResolved(s: NsdServiceInfo) {
                            if (port.compareAndSet(-1, s.port)) latch.countDown()
                        }
                    })
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) { latch.countDown() }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { latch.countDown() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        return try {
            manager.discoverServices("_adb-tls-connect._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            latch.await(timeoutSeconds, TimeUnit.SECONDS)
            port.get().takeIf { it in 1..65535 }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { manager.stopServiceDiscovery(listener) }
        }
    }
}
