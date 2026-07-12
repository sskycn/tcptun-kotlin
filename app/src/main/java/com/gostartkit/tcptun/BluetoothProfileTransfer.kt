package com.tcptun.client

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class NearbyBluetoothDevice(
    val name: String,
    val address: String,
)

internal enum class BluetoothSendResult {
    Accepted,
}

internal data class EncryptedBluetoothUriFrame(
    val salt: ByteArray,
    val iv: ByteArray,
    val encrypted: ByteArray,
)

private const val AckAccepted = 1
private const val AckInvalid = 3

internal object BluetoothUriFrame {
    private const val Magic = 0x54435455 // TCTU
    private const val Version = 3
    const val MaxUriBytes = 64 * 1024
    private const val SaltBytes = 16
    private const val IvBytes = 12
    private const val GcmTagBits = 128
    private const val GcmTagBytes = GcmTagBits / 8
    private const val KeyBits = 256
    private const val Pbkdf2Iterations = 120_000
    private val AssociatedData = "TcpTun-Bluetooth-URI-v3".toByteArray(StandardCharsets.US_ASCII)
    private val random = SecureRandom()

    fun encode(code: String, uri: String): ByteArray {
        require(code.matches(Regex("\\d{4}"))) { "Bluetooth code must contain four digits" }
        val payload = uri.toByteArray(StandardCharsets.UTF_8)
        require(payload.isNotEmpty()) { "empty profile URI" }
        require(payload.size <= MaxUriBytes) { "profile URI is too large" }
        val salt = ByteArray(SaltBytes).also(random::nextBytes)
        val iv = ByteArray(IvBytes).also(random::nextBytes)
        val encrypted = cipher(Cipher.ENCRYPT_MODE, code, salt, iv).doFinal(payload)
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(Magic)
                output.writeInt(Version)
                output.write(salt)
                output.write(iv)
                output.writeInt(encrypted.size)
                output.write(encrypted)
            }
            bytes.toByteArray()
        }
    }

    fun decode(code: String, frame: ByteArray): String = DataInputStream(ByteArrayInputStream(frame)).use { input ->
        decrypt(code, read(input))
    }

    fun read(input: DataInputStream): EncryptedBluetoothUriFrame {
        if (input.readInt() != Magic) throw IOException("unsupported Bluetooth message")
        if (input.readInt() != Version) throw IOException("unsupported Bluetooth message version")
        val salt = ByteArray(SaltBytes).also(input::readFully)
        val iv = ByteArray(IvBytes).also(input::readFully)
        val length = input.readInt()
        if (length !in (GcmTagBytes + 1)..(MaxUriBytes + GcmTagBytes)) throw IOException("invalid Bluetooth message length")
        val encrypted = ByteArray(length).also(input::readFully)
        return EncryptedBluetoothUriFrame(salt, iv, encrypted)
    }

    fun decrypt(code: String, frame: EncryptedBluetoothUriFrame): String {
        require(code.matches(Regex("\\d{4}"))) { "Bluetooth code must contain four digits" }
        val payload = try {
            cipher(Cipher.DECRYPT_MODE, code, frame.salt, frame.iv).doFinal(frame.encrypted)
        } catch (error: AEADBadTagException) {
            throw BluetoothCodeMismatchException(error)
        }
        return String(payload, StandardCharsets.UTF_8)
    }

    private fun cipher(mode: Int, code: String, salt: ByteArray, iv: ByteArray): Cipher {
        val keySpec = PBEKeySpec(code.toCharArray(), salt, Pbkdf2Iterations, KeyBits)
        val key = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(keySpec).encoded
        } finally {
            keySpec.clearPassword()
        }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(GcmTagBits, iv))
            updateAAD(AssociatedData)
        }
    }
}

internal class BluetoothCodeMismatchException(cause: Throwable? = null) : IOException("Bluetooth code mismatch", cause)

internal object BluetoothProfileTransfer {
    private const val ServiceName = "TcpTun profile transfer"
    private const val ServiceLookupTimeoutMs = 6_000L
    private val ServiceUuid: UUID = UUID.fromString("91b17192-06b2-4ef2-bf69-7aab5cae1267")

    fun adapter(context: Context): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    @SuppressLint("MissingPermission")
    fun discover(
        context: Context,
        onDevice: (NearbyBluetoothDevice) -> Unit,
        onFinished: () -> Unit,
        onError: (Throwable) -> Unit,
    ): Result<BluetoothDiscoverySession> = runCatching {
        val bluetoothAdapter = checkNotNull(adapter(context)) { "Bluetooth is not supported" }
        val appContext = context.applicationContext
        val handler = Handler(Looper.getMainLooper())
        val candidates = linkedMapOf<String, BluetoothDevice>()
        val serviceLookupQueue = ArrayDeque<BluetoothDevice>()
        var activeServiceLookupAddress: String? = null
        var finished = false
        fun finishOnce() {
            if (finished) return
            finished = true
            onFinished()
        }
        val serviceLookupTimeout = Runnable {
            serviceLookupQueue.clear()
            activeServiceLookupAddress = null
            finishOnce()
        }
        lateinit var startNextServiceLookup: () -> Unit
        startNextServiceLookup = {
            if (!finished && activeServiceLookupAddress == null) {
                var started = false
                while (!started && serviceLookupQueue.isNotEmpty()) {
                    val device = serviceLookupQueue.removeFirst()
                    activeServiceLookupAddress = device.address
                    started = device.fetchUuidsWithSdp()
                    if (!started) activeServiceLookupAddress = null
                }
                if (!started) {
                    handler.removeCallbacks(serviceLookupTimeout)
                    finishOnce()
                }
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> intent.bluetoothDeviceExtra()?.let { candidates[it.address] = it }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        if (candidates.isEmpty()) {
                            finishOnce()
                            return
                        }
                        serviceLookupQueue.addAll(candidates.values)
                        handler.postDelayed(serviceLookupTimeout, ServiceLookupTimeoutMs)
                        startNextServiceLookup()
                    }
                    BluetoothDevice.ACTION_UUID -> {
                        val device = intent.bluetoothDeviceExtra() ?: return
                        if (device.address != activeServiceLookupAddress) return
                        if (intent.bluetoothUuidExtras().any { it.uuid == ServiceUuid }) onDevice(deviceInfo(device))
                        activeServiceLookupAddress = null
                        startNextServiceLookup()
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothDevice.ACTION_UUID)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val session = BluetoothDiscoverySession(
            context = appContext,
            adapter = bluetoothAdapter,
            receiver = receiver,
            onClose = { handler.removeCallbacks(serviceLookupTimeout) },
        )
        if (!bluetoothAdapter.startDiscovery()) {
            session.close()
            error("Unable to start Bluetooth discovery")
        }
        session
    }.onFailure(onError)

    @SuppressLint("MissingPermission")
    suspend fun send(context: Context, address: String, code: String, uri: String): BluetoothSendResult = withContext(Dispatchers.IO) {
        val bluetoothAdapter = checkNotNull(adapter(context)) { "Bluetooth is not supported" }
        bluetoothAdapter.cancelDiscovery()
        val device = bluetoothAdapter.getRemoteDevice(address)
        suspendCancellableCoroutine { continuation ->
            val socket = device.createRfcommSocketToServiceRecord(ServiceUuid)
            continuation.invokeOnCancellation { runCatching { socket.close() } }
            try {
                socket.use {
                    it.connect()
                    val output = it.outputStream
                    output.write(BluetoothUriFrame.encode(code, uri))
                    output.flush()
                    val result = when (it.inputStream.read()) {
                        AckAccepted -> BluetoothSendResult.Accepted
                else -> throw IOException("receiver rejected Bluetooth profile")
                    }
                    if (continuation.isActive) continuation.resume(result)
                }
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun receiveOne(
        context: Context,
        scope: CoroutineScope,
        onReceived: (EncryptedBluetoothUriFrame) -> Unit,
        onError: (Throwable) -> Unit,
        onFinished: () -> Unit,
    ): Result<BluetoothReceiveSession> = runCatching {
        val bluetoothAdapter = checkNotNull(adapter(context)) { "Bluetooth is not supported" }
        val server = bluetoothAdapter.listenUsingRfcommWithServiceRecord(ServiceName, ServiceUuid)
        BluetoothReceiveSession(server, scope, onReceived, onError, onFinished).also { it.start() }
    }

    @SuppressLint("MissingPermission")
    private fun deviceInfo(device: BluetoothDevice): NearbyBluetoothDevice = NearbyBluetoothDevice(
        name = device.name?.takeIf(String::isNotBlank) ?: device.address,
        address = device.address,
    )
}

internal class BluetoothDiscoverySession(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val receiver: BroadcastReceiver,
    private val onClose: () -> Unit,
) : AutoCloseable {
    private var closed = false

    @SuppressLint("MissingPermission")
    override fun close() {
        if (closed) return
        closed = true
        onClose()
        adapter.cancelDiscovery()
        runCatching { context.unregisterReceiver(receiver) }
    }
}

internal class BluetoothReceiveSession(
    private val server: BluetoothServerSocket,
    private val scope: CoroutineScope,
    private val onReceived: (EncryptedBluetoothUriFrame) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val onFinished: () -> Unit,
) : AutoCloseable {
    private var job: Job? = null

    fun start() {
        job = scope.launch(Dispatchers.IO) {
            try {
                while (true) {
                    server.accept().use { socket ->
                        val encryptedFrame = try {
                            BluetoothUriFrame.read(DataInputStream(socket.inputStream))
                        } catch (error: Throwable) {
                            runCatching {
                                socket.outputStream.write(AckInvalid)
                                socket.outputStream.flush()
                            }
                            withContext(Dispatchers.Main) { onError(error) }
                            return@use
                        }
                        socket.outputStream.write(AckAccepted)
                        socket.outputStream.flush()
                        withContext(Dispatchers.Main) { onReceived(encryptedFrame) }
                        return@launch
                    }
                }
            } catch (error: Throwable) {
                if (job?.isCancelled != true) withContext(Dispatchers.Main) { onError(error) }
            } finally {
                runCatching { server.close() }
                withContext(Dispatchers.Main) { onFinished() }
            }
        }
    }

    override fun close() {
        job?.cancel()
        runCatching { server.close() }
    }
}

@Suppress("DEPRECATION")
private fun Intent.bluetoothDeviceExtra(): BluetoothDevice? =
    if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    else getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

@Suppress("DEPRECATION")
private fun Intent.bluetoothUuidExtras(): Array<ParcelUuid> =
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID, ParcelUuid::class.java) ?: emptyArray()
    } else {
        getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)
            ?.filterIsInstance<ParcelUuid>()
            ?.toTypedArray()
            ?: emptyArray()
    }
