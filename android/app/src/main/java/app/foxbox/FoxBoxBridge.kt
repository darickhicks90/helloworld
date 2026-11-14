package app.foxbox

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.ArrayAdapter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The bridge between the FoxBox web app and Android. The page sees it as
 * `window.FoxBoxAndroid`; Android calls back into the page through
 * `window.FBAndroid.onMidi / onDevices / toast` (see js/midi.js).
 *
 * MIDI goes through android.media.midi, which handles both USB controllers
 * (e.g. Synido TempoPad C16) and Bluetooth LE MIDI controllers (e.g. ROLI
 * Seaboard M).
 */
class FoxBoxBridge(private val activity: MainActivity, private val web: WebView) {

    companion object {
        /** The standard Bluetooth LE MIDI service. */
        private val BLE_MIDI_SERVICE: UUID = UUID.fromString("03b80e5a-ede8-4b33-a751-6ce34ec4c700")
        private const val SCAN_MILLIS = 20_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private val midi: MidiManager? =
        if (activity.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI))
            activity.getSystemService(Context.MIDI_SERVICE) as? MidiManager
        else null

    private class Opened(val device: MidiDevice, val ports: MutableList<MidiOutputPort>)

    private val opened = ConcurrentHashMap<Int, Opened>()
    private val opening = ConcurrentHashMap.newKeySet<Int>()
    private val pendingBluetooth = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var autoOpen = false

    // MIDI arrives on a background thread; it is queued and sent to the page
    // in batches so busy controllers (like an MPE Seaboard) stay smooth.
    private val queue = ConcurrentLinkedQueue<Pair<Int, ByteArray>>()
    private val flushScheduled = AtomicBoolean(false)

    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) {
            if (autoOpen && !isPendingBluetooth(info)) openDevice(info)
            notifyDevices()
        }

        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            closeDevice(info.id)
            notifyDevices()
        }
    }

    init {
        @Suppress("DEPRECATION")
        midi?.registerDeviceCallback(deviceCallback, main)
    }

    // ------------------------------------------------------------------
    // Called from JavaScript
    // ------------------------------------------------------------------

    /** JSON list of MIDI devices: [{id, name, kind: "usb"|"ble", open}] */
    @JavascriptInterface
    fun listMidiDevices(): String {
        val arr = JSONArray()
        allDevices().forEach { info ->
            if (info.outputPortCount == 0) return@forEach // nothing to listen to
            arr.put(
                JSONObject()
                    .put("id", info.id)
                    .put("name", nameOf(info))
                    .put("kind", if (info.type == MidiDeviceInfo.TYPE_BLUETOOTH) "ble" else "usb")
                    .put("open", opened.containsKey(info.id))
            )
        }
        return arr.toString()
    }

    /** Start listening to every MIDI controller, now and when plugged in later. */
    @JavascriptInterface
    fun openAllMidi() {
        main.post {
            if (midi == null) {
                toast("This device has no MIDI support. You can still play on the screen.")
                return@post
            }
            autoOpen = true
            allDevices().forEach { openDevice(it) }
        }
    }

    @JavascriptInterface
    fun pairBluetooth() {
        main.post { startBluetoothPairing() }
    }

    @JavascriptInterface
    fun disconnect(id: String) {
        main.post {
            id.toIntOrNull()?.let { closeDevice(it) }
            notifyDevices()
        }
    }

    /** Save a recording (base64) to Downloads/FoxBox. Returns a message for the user. */
    @JavascriptInterface
    fun saveRecording(name: String, mime: String, base64: String): String {
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                    put(MediaStore.Downloads.MIME_TYPE, mime.substringBefore(';'))
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FoxBox")
                }
                val resolver = activity.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return "Could not save the recording"
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                "Saved to Downloads/FoxBox/$safeName"
            } else {
                val dir = activity.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: activity.filesDir
                val file = File(dir, safeName)
                file.writeBytes(bytes)
                "Saved to ${file.absolutePath}"
            }
        } catch (e: Exception) {
            "Could not save the recording: ${e.message}"
        }
    }

    // ------------------------------------------------------------------
    // MIDI devices
    // ------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun allDevices(): List<MidiDeviceInfo> = midi?.devices?.toList() ?: emptyList()

    private fun nameOf(info: MidiDeviceInfo): String {
        val p = info.properties
        return p.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: p.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
            ?: "MIDI device"
    }

    private fun isPendingBluetooth(info: MidiDeviceInfo): Boolean {
        if (info.type != MidiDeviceInfo.TYPE_BLUETOOTH) return false
        val bt = info.properties.getParcelableCompat(MidiDeviceInfo.PROPERTY_BLUETOOTH_DEVICE)
        return bt != null && bt.address in pendingBluetooth
    }

    @Suppress("DEPRECATION")
    private fun android.os.Bundle.getParcelableCompat(key: String): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) getParcelable(key, BluetoothDevice::class.java) else getParcelable(key)

    private fun openDevice(info: MidiDeviceInfo) {
        val mm = midi ?: return
        if (info.outputPortCount == 0) return
        if (opened.containsKey(info.id) || !opening.add(info.id)) return
        mm.openDevice(info, { device ->
            opening.remove(info.id)
            if (device == null) {
                toast("Couldn't open ${nameOf(info)}")
            } else {
                attach(device)
            }
            notifyDevices()
        }, main)
    }

    private fun attach(device: MidiDevice) {
        val info = device.info
        val ports = mutableListOf<MidiOutputPort>()
        for (i in 0 until info.outputPortCount) {
            val port = device.openOutputPort(i) ?: continue
            port.connect(Receiver(info.id))
            ports.add(port)
        }
        opened.put(info.id, Opened(device, ports))?.let { old -> closeQuietly(old) }
    }

    private fun closeDevice(id: Int) {
        opened.remove(id)?.let { closeQuietly(it) }
    }

    private fun closeQuietly(o: Opened) {
        o.ports.forEach { runCatching { it.close() } }
        runCatching { o.device.close() }
    }

    private inner class Receiver(private val id: Int) : MidiReceiver() {
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            queue.add(id to msg.copyOfRange(offset, offset + count))
            if (flushScheduled.compareAndSet(false, true)) main.post { flush() }
        }
    }

    private fun flush() {
        flushScheduled.set(false)
        if (queue.isEmpty()) return
        val js = StringBuilder("window.FBAndroid&&FBAndroid.onMidi&&FBAndroid.onMidi([")
        var first = true
        while (true) {
            val (id, bytes) = queue.poll() ?: break
            if (!first) js.append(',')
            first = false
            js.append('[').append(id).append(",[")
            bytes.forEachIndexed { i, b ->
                if (i > 0) js.append(',')
                js.append(b.toInt() and 0xFF)
            }
            js.append("]]")
        }
        js.append("])")
        web.evaluateJavascript(js.toString(), null)
    }

    private fun notifyDevices() {
        main.post { web.evaluateJavascript("window.FBAndroid&&FBAndroid.onDevices&&FBAndroid.onDevices()", null) }
    }

    private fun toast(msg: String) {
        main.post {
            web.evaluateJavascript("window.FBAndroid&&FBAndroid.toast&&FBAndroid.toast(${JSONObject.quote(msg)})", null)
        }
    }

    // ------------------------------------------------------------------
    // Bluetooth LE MIDI: scan, let the player pick, then open via MidiManager
    // ------------------------------------------------------------------

    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var chooser: AlertDialog? = null

    private fun bluetoothPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun onBluetoothPermission(granted: Boolean) {
        if (granted) startBluetoothPairing()
        else toast("FoxBox needs Bluetooth permission to find Bluetooth MIDI controllers.")
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothPairing() {
        if (midi == null) {
            toast("This device has no MIDI support.")
            return
        }
        val missing = bluetoothPermissions().filter {
            activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            activity.requestPermissions(missing.toTypedArray(), MainActivity.REQ_BLUETOOTH)
            return
        }
        val adapter = (activity.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null) {
            toast("This device has no Bluetooth.")
            return
        }
        if (!adapter.isEnabled) {
            toast("Turn on Bluetooth first, then tap Bluetooth again.")
            return
        }
        val le = adapter.bluetoothLeScanner ?: run {
            toast("Bluetooth LE is not available.")
            return
        }
        stopScan()
        autoOpen = true

        val found = LinkedHashMap<String, BluetoothDevice>()
        val names = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1)
        chooser = AlertDialog.Builder(activity)
            .setTitle("Searching for Bluetooth MIDI…\n(wake your controller / turn on its Bluetooth)")
            .setAdapter(names) { _, which ->
                val device = found.values.elementAtOrNull(which) ?: return@setAdapter
                stopScan()
                connectBluetooth(device)
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { stopScan() }
            .show()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d = result.device
                if (found.containsKey(d.address)) return
                found[d.address] = d
                names.add(d.name ?: result.scanRecord?.deviceName ?: d.address)
                names.notifyDataSetChanged()
            }

            override fun onScanFailed(errorCode: Int) {
                toast("Bluetooth search failed (error $errorCode)")
                chooser?.dismiss()
            }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(BLE_MIDI_SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        le.startScan(listOf(filter), settings, callback)
        scanner = le
        scanCallback = callback
        main.postDelayed({ stopScan() }, SCAN_MILLIS)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        val cb = scanCallback ?: return
        scanCallback = null
        runCatching { scanner?.stopScan(cb) }
    }

    @SuppressLint("MissingPermission")
    private fun connectBluetooth(device: BluetoothDevice) {
        val mm = midi ?: return
        val label = device.name ?: device.address
        toast("Connecting to $label…")
        pendingBluetooth.add(device.address)
        mm.openBluetoothDevice(device, { midiDevice ->
            if (midiDevice == null) {
                toast("Couldn't connect to $label. Make sure it isn't connected to another app or phone.")
            } else {
                attach(midiDevice)
            }
            pendingBluetooth.remove(device.address)
            notifyDevices()
        }, main)
    }

    fun close() {
        stopScan()
        chooser?.dismiss()
        midi?.unregisterDeviceCallback(deviceCallback)
        opened.values.forEach { closeQuietly(it) }
        opened.clear()
    }
}
