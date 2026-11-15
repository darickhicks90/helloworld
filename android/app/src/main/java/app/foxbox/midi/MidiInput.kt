package app.foxbox.midi

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.widget.ArrayAdapter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * USB and Bluetooth LE MIDI input via android.media.midi.
 * Parses the byte stream and hands musical events to a [Sink].
 */
class MidiInput(private val activity: Activity, private val sink: Sink) {

    enum class Role { KEYS, MPE, PADS }

    interface Sink {
        fun noteOn(dev: Dev, ch: Int, note: Int, vel: Float)
        fun noteOff(dev: Dev, ch: Int, note: Int)
        /** kind = Engine.BEND (semitones) / PRESSURE / SLIDE (0..1); note = -1 for the whole channel. */
        fun expr(dev: Dev, ch: Int, note: Int, kind: Int, v: Float)
        fun cc(dev: Dev, ch: Int, cc: Int, v: Float)
        fun devicesChanged()
        fun message(text: String)
        /** MIDI realtime Start (0xFA), Continue (0xFB) and Stop (0xFC). */
        fun realtime(status: Int) {}
    }

    class Dev(val id: Int, val name: String, val bluetooth: Boolean) {
        var role = autoRole(name)
        val bendRange get() = if (role == Role.MPE) 48f else 2f
        var running = 0; var data = IntArray(2); var count = 0; var sysex = false
    }

    private val main = Handler(Looper.getMainLooper())
    private val manager = if (activity.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI))
        activity.getSystemService(Context.MIDI_SERVICE) as? MidiManager else null

    private class Opened(val device: MidiDevice, val ports: List<MidiOutputPort>, val dev: Dev)
    private val opened = ConcurrentHashMap<Int, Opened>()
    private val pendingBt = ConcurrentHashMap.newKeySet<String>()
    val devices: List<Dev> get() = opened.values.map { it.dev }

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) { if (!isPendingBt(info)) open(info) }
        override fun onDeviceRemoved(info: MidiDeviceInfo) { close(info.id) }
    }

    @Suppress("DEPRECATION")
    fun start() {
        val m = manager ?: return
        m.registerDeviceCallback(callback, main)
        m.devices.forEach { open(it) }
    }

    fun stop() {
        manager?.unregisterDeviceCallback(callback)
        opened.keys.toList().forEach { close(it) }
    }

    private fun nameOf(info: MidiDeviceInfo) = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
        ?: info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT) ?: "MIDI device"

    @Suppress("DEPRECATION")
    private fun isPendingBt(info: MidiDeviceInfo): Boolean {
        if (info.type != MidiDeviceInfo.TYPE_BLUETOOTH) return false
        val bt = if (Build.VERSION.SDK_INT >= 33) info.properties.getParcelable(MidiDeviceInfo.PROPERTY_BLUETOOTH_DEVICE, BluetoothDevice::class.java)
        else info.properties.getParcelable(MidiDeviceInfo.PROPERTY_BLUETOOTH_DEVICE)
        return bt != null && bt.address in pendingBt
    }

    private fun open(info: MidiDeviceInfo) {
        if (info.outputPortCount == 0 || opened.containsKey(info.id)) return
        manager?.openDevice(info, { d -> if (d != null) attach(d) }, main)
    }

    private fun attach(device: MidiDevice) {
        val info = device.info
        val dev = Dev(info.id, nameOf(info), info.type == MidiDeviceInfo.TYPE_BLUETOOTH)
        val ports = (0 until info.outputPortCount).mapNotNull { i ->
            device.openOutputPort(i)?.also { it.connect(object : MidiReceiver() {
                override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) = parse(dev, msg, offset, count)
            }) }
        }
        opened.put(info.id, Opened(device, ports, dev))?.let { closeQuietly(it) }
        sink.message("Connected ${dev.name} (${dev.role.name.lowercase()})")
        sink.devicesChanged()
    }

    private fun close(id: Int) { opened.remove(id)?.let { closeQuietly(it); sink.devicesChanged() } }
    private fun closeQuietly(o: Opened) { o.ports.forEach { runCatching { it.close() } }; runCatching { o.device.close() } }

    // ------------------------------------------------------------------ parsing

    private fun parse(d: Dev, msg: ByteArray, off: Int, count: Int) {
        for (i in off until off + count) {
            val b = msg[i].toInt() and 0xFF
            if (b >= 0xF8) { if (b == 0xFA || b == 0xFB || b == 0xFC) sink.realtime(b); continue }
            if (d.sysex) { if (b == 0xF7) d.sysex = false; continue }
            if (b and 0x80 != 0) {
                if (b == 0xF0) { d.sysex = true; continue }
                d.running = if (b >= 0xF0) 0 else b; d.count = 0; continue
            }
            if (d.running == 0) continue
            d.data[d.count++] = b
            val need = if ((d.running and 0xF0) == 0xC0 || (d.running and 0xF0) == 0xD0) 1 else 2
            if (d.count >= need) { dispatch(d, d.running, d.data[0], if (need > 1) d.data[1] else 0); d.count = 0 }
        }
    }

    private fun dispatch(d: Dev, status: Int, a: Int, b: Int) {
        val ch = status and 15
        when (status and 0xF0) {
            0x90 -> if (b > 0) sink.noteOn(d, ch, a, b / 127f) else sink.noteOff(d, ch, a)
            0x80 -> sink.noteOff(d, ch, a)
            0xA0 -> sink.expr(d, ch, a, 1, b / 127f)
            0xD0 -> sink.expr(d, ch, -1, 1, a / 127f)
            0xE0 -> {
                val range = if (d.role == Role.MPE && ch == 0) 2f else d.bendRange
                sink.expr(d, ch, -1, 0, (((b shl 7) or a) - 8192) / 8192f * range)
            }
            0xB0 -> if (a == 74 && d.role == Role.MPE) sink.expr(d, ch, -1, 2, b / 127f) else sink.cc(d, ch, a, b / 127f)
        }
    }

    // ------------------------------------------------------------------ Bluetooth LE MIDI

    private var scanCb: ScanCallback? = null

    fun bluetoothPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasBluetoothPermission() = bluetoothPermissions().all { activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Scan for BLE MIDI devices and let the player choose one. Call after permissions are granted. */
    @SuppressLint("MissingPermission")
    fun pairBluetooth() {
        val m = manager ?: return sink.message("This device has no MIDI support")
        val adapter = (activity.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return sink.message("This device has no Bluetooth")
        if (!adapter.isEnabled) return sink.message("Turn Bluetooth on first")
        val scanner = adapter.bluetoothLeScanner ?: return sink.message("Bluetooth LE is not available")
        val found = LinkedHashMap<String, BluetoothDevice>()
        val names = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1)
        val stopScan = { scanCb?.let { runCatching { scanner.stopScan(it) } }; scanCb = null }
        AlertDialog.Builder(activity)
            .setTitle("Searching for Bluetooth MIDI…")
            .setAdapter(names) { _, which ->
                stopScan()
                val dev = found.values.elementAt(which)
                pendingBt += dev.address
                sink.message("Connecting to ${dev.name ?: dev.address}…")
                m.openBluetoothDevice(dev, { d ->
                    if (d == null) sink.message("Couldn't connect. Is it connected to another app?") else attach(d)
                    pendingBt -= dev.address
                }, main)
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { stopScan() }
            .show()
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                if (found.containsKey(r.device.address)) return
                found[r.device.address] = r.device
                names.add(r.device.name ?: r.scanRecord?.deviceName ?: r.device.address)
            }
        }
        scanCb = cb
        scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(BLE_MIDI)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        main.postDelayed({ stopScan() }, 20_000)
    }

    companion object {
        private val BLE_MIDI: UUID = UUID.fromString("03b80e5a-ede8-4b33-a751-6ce34ec4c700")
        fun autoRole(name: String): Role {
            val n = name.lowercase()
            return when {
                Regex("seaboard|roli|lumi|mpe|osmose|linnstrument").containsMatchIn(n) -> Role.MPE
                Regex("tempopad|c16|synido|pad").containsMatchIn(n) -> Role.PADS
                else -> Role.KEYS
            }
        }
    }
}
