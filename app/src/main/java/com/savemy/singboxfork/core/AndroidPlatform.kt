package com.savemy.singboxfork.core

import android.content.Context
import android.net.*
import android.os.Build
import android.system.OsConstants
import io.nekohasekai.libbox.*
import java.net.InetSocketAddress
import java.net.NetworkInterface as JNetworkInterface

class Strings(values: Collection<String>) : StringIterator {
    private val iterator=values.iterator()
    private var remaining=values.size
    override fun hasNext()=iterator.hasNext()
    override fun next():String { remaining--; return iterator.next() }
    override fun len()=remaining
}

/** Одна реализация платформы для диагностики и VPN; сокеты ядра никогда не попадают обратно в TUN. */
class AndroidPlatform(private val context: Context) : PlatformInterface {
    @Volatile var vpn: com.savemy.singboxfork.vpn.BoxVpnService? = null
    private val cm=context.getSystemService(ConnectivityManager::class.java)
    private val callbacks=java.util.concurrent.ConcurrentHashMap<InterfaceUpdateListener,ConnectivityManager.NetworkCallback>()
    private fun physical(): Network? = cm.activeNetwork?.takeIf {
        cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)==true
    }
    override fun usePlatformAutoDetectInterfaceControl()=true
    override fun autoDetectInterfaceControl(fd: Int) { vpn?.let { check(it.protect(fd)) { "Не удалось защитить сокет VPN" } } }
    override fun openTun(options: TunOptions): Int = vpn?.openTun(options) ?: error("VPN не запущен")
    override fun localDNSTransport(): LocalDNSTransport? = null
    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        var current=physical()
        fun update() {
            val n=current; val p=n?.let(cm::getLinkProperties); val name=p?.interfaceName.orEmpty()
            val index=runCatching { JNetworkInterface.getByName(name)?.index ?: -1 }.getOrDefault(-1)
            listener.updateDefaultInterface(name,index,n?.let { cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)==false } ?: false,false)
            vpn?.setUnderlyingNetworks(n?.let { arrayOf(it) })
        }
        val cb=object: ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { current=network;update() }
            override fun onLost(network: Network) { if(current==network) { current=null;update() } }
            override fun onLinkPropertiesChanged(network: Network,linkProperties: LinkProperties) { if(current==network) update() }
            override fun onCapabilitiesChanged(network: Network,networkCapabilities: NetworkCapabilities) { if(current==network) update() }
        }
        callbacks[listener]=cb
        // requestNetwork сообщает лучшую сеть для запроса, а не все сети в случайном порядке.
        cm.requestNetwork(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),cb)
        update()
    }
    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) { callbacks.remove(listener)?.let { runCatching { cm.unregisterNetworkCallback(it) } } }
    fun close() { callbacks.keys.toList().forEach(::closeDefaultInterfaceMonitor) }
    override fun getInterfaces(): NetworkInterfaceIterator {
        val interfaces=cm.allNetworks.mapNotNull { n ->
            val lp=cm.getLinkProperties(n) ?: return@mapNotNull null
            val caps=cm.getNetworkCapabilities(n) ?: return@mapNotNull null
            if(!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return@mapNotNull null
            val name=lp.interfaceName ?: return@mapNotNull null
            val ji=runCatching { JNetworkInterface.getByName(name) }.getOrNull() ?: return@mapNotNull null
            NetworkInterface().apply {
                this.name=name; index=ji.index; mtu=ji.mtu
                addresses=Strings(lp.linkAddresses.map { "${it.address.hostAddress?.substringBefore('%')}/${it.prefixLength}" })
                dnsServer=Strings(lp.dnsServers.mapNotNull { it.hostAddress }); gateway=Strings(emptyList())
                flags=OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                type=when { caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI; caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular; else -> Libbox.InterfaceTypeOther }
                metered=!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
        }.iterator()
        return object: NetworkInterfaceIterator { override fun hasNext()=interfaces.hasNext(); override fun next()=interfaces.next() }
    }
    override fun findConnectionOwner(ipProtocol: Int,sourceAddress: String,sourcePort: Int,destinationAddress: String,destinationPort: Int): ConnectionOwner {
        if(Build.VERSION.SDK_INT<29) error("Определение владельца соединения требует Android 10")
        val uid=cm.getConnectionOwnerUid(ipProtocol,InetSocketAddress(sourceAddress,sourcePort),InetSocketAddress(destinationAddress,destinationPort))
        require(uid>=0)
        return ConnectionOwner().apply { userId=uid; setAndroidPackageNames(Strings(context.packageManager.getPackagesForUid(uid)?.toList().orEmpty())) }
    }
    override fun underNetworkExtension()=false
    override fun includeAllNetworks()=false
    override fun readWIFIState(): WIFIState?=null
    override fun clearDNSCache()=Unit
    override fun sendNotification(notification: Notification)=Unit
    override fun cancelNotification(identifier: String,typeID: Int)=Unit
    override fun startNeighborMonitor(listener: NeighborUpdateListener)=Unit
    override fun closeNeighborMonitor(listener: NeighborUpdateListener)=Unit
    override fun registerMyInterface(name: String)=Unit
    override fun usePlatformShell()=false
    override fun checkPlatformShell(): Unit=error("Оболочка недоступна")
    override fun openShellSession(user: PlatformUser,command: String,environ: StringIterator,term: String,rows: Int,cols: Int): ShellSession=error("Оболочка недоступна")
    override fun lookupUser(username: String): PlatformUser=error("Оболочка недоступна")
    override fun lookupSFTPServer(): String=error("SFTP недоступен")
    override fun readSystemSSHHostKey(): String=error("SSH недоступен")
    override fun tailscaleHostname()="SavemyBox"
    override fun usePlatformBridge()=false
    override fun createBridge(options: BridgeOptions): BridgeSession=error("Мост недоступен")
}
