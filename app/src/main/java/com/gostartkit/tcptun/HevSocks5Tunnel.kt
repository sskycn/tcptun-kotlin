package com.tcptun.client

import android.os.ParcelFileDescriptor
import java.io.File

object HevSocks5Tunnel {
    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    fun start(configFile: File, tun: ParcelFileDescriptor) {
        TProxyStartService(configFile.absolutePath, tun.fd)
    }

    fun stop() {
        TProxyStopService()
    }

    fun stats(): LongArray {
        return TProxyGetStats()
    }

    fun writeConfig(
        directory: File,
        socksHost: String,
        socksPort: Int,
        mtu: Int,
    ): File {
        val file = File(directory, "hev-socks5-tunnel.yml")
        file.writeText(
            """
            tunnel:
              name: tun0
              mtu: $mtu
              multi-queue: false
              ipv4: 10.77.0.2
              ipv6: 'fd00:7777::2'

            socks5:
              port: $socksPort
              address: $socksHost
              udp: 'udp'
              pipeline: false

            misc:
              connect-timeout: 10000
              tcp-read-write-timeout: 300000
              udp-read-write-timeout: 30000
              udp-recv-buffer-size: 131072
              udp-copy-buffer-nums: 4
              log-file: stderr
              log-level: error
            """.trimIndent() + "\n",
        )
        return file
    }

    private external fun TProxyStartService(configPath: String, fd: Int)
    private external fun TProxyStopService()
    private external fun TProxyGetStats(): LongArray
}
