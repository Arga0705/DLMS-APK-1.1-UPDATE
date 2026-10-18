package id.ltronik.dspupdater

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32

/**
 * Update firmware L-TRONIK DSP lewat Bluetooth LE.
 * Protokol sama dengan ltronik_dsp_update.html dan firmware v1.9.6:
 *   'B' + ukuran(4B LE) + CRC32(4B LE) + sandi  -> mulai   (balasan 0x01)
 *   'D' + data                                   -> isi
 *   'E'                                          -> selesai & pasang (balasan 0x02, DSP restart)
 *   'X'                                          -> batal
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class MainActivity : Activity() {

    companion object {
        val SVC: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val OTA: UUID = UUID.fromString("6e400004-b5a3-f393-e0a9-e50e24dcca9e")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val REQ_PERM = 1
        const val REQ_FILE = 2
        const val MAX_CHUNK = 200

        val C_BG = Color.parseColor("#08051C")
        val C_PANEL = Color.parseColor("#100A34")
        val C_PANEL2 = Color.parseColor("#281868")
        val C_LINE = Color.parseColor("#5432D2")
        val C_TXT = Color.parseColor("#F4F0FF")
        val C_DIM = Color.parseColor("#9B90D6")
        val C_ACC = Color.parseColor("#FBBF24")
        val C_OK = Color.parseColor("#22E08A")
        val C_BAD = Color.parseColor("#FF3D5A")
        val C_OFF = Color.parseColor("#2A2272")
        val C_CY = Color.parseColor("#22D3EE")
    }

    private class Fail(msg: String) : Exception(msg)

    private val ui = Handler(Looper.getMainLooper())

    private var gatt: BluetoothGatt? = null
    private var otaChar: BluetoothGattCharacteristic? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private var mtu = 23

    private var bin: ByteArray? = null
    private var crc = 0L

    @Volatile private var busy = false
    @Volatile private var cancel = false
    private val writeQ = LinkedBlockingQueue<Int>()
    private val notifyQ = LinkedBlockingQueue<Int>()

    private lateinit var dot: View
    private lateinit var statusTx: TextView
    private lateinit var btnConn: Button
    private lateinit var btnFile: Button
    private lateinit var fileInfo: TextView
    private lateinit var passEd: EditText
    private lateinit var btnGo: Button
    private lateinit var progCard: LinearLayout
    private lateinit var pctTx: TextView
    private lateinit var speedTx: TextView
    private lateinit var sentTx: TextView
    private lateinit var etaTx: TextView
    private lateinit var btnCancel: Button
    private lateinit var msgTx: TextView
    private val boxes = ArrayList<View>()

    // ---------- UI ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(fill: Int, stroke: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false, mono: Boolean = false) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (mono) typeface = Typeface.MONOSPACE
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(s: String, main: Boolean) = Button(this).apply {
        text = s
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (main) Color.parseColor("#1A1200") else C_TXT)
        background = rounded(if (main) C_ACC else C_PANEL2, if (main) C_ACC else C_LINE, 10)
        minimumHeight = dp(48)
        minHeight = dp(48)
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(C_PANEL, C_LINE, 12)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        }
    }

    private fun gap(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = C_BG
        window.navigationBarColor = C_BG

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(C_BG)
            isFillViewport = true
            addView(root)
        }
        setContentView(scroll)

        root.addView(text("Update firmware L-TRONIK DSP", 20f, C_TXT, bold = true))
        root.addView(text("Kirim file .bin ke DSP lewat Bluetooth.", 14f, C_DIM))

        // Koneksi
        val cConn = card()
        val rowConn = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        dot = View(this).apply {
            background = rounded(C_OFF, 0, 5)
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(10) }
        }
        statusTx = text("Belum terhubung", 16f, C_TXT).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnConn = button("Hubungkan", true)
        rowConn.addView(dot); rowConn.addView(statusTx); rowConn.addView(btnConn)
        cConn.addView(rowConn)
        root.addView(cConn)

        // File
        val cFile = card()
        btnFile = button("Pilih file firmware (.ino.bin)", false)
        fileInfo = text("", 13f, C_DIM, mono = true).apply { visibility = View.GONE; setPadding(0, dp(10), 0, 0) }
        val passLabel = text("Sandi update", 14f, C_DIM).apply { setPadding(0, dp(12), 0, dp(4)) }
        passEd = EditText(this).apply {
            setText("ltronik")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(C_TXT)
            setSingleLine()
            background = rounded(C_BG, C_LINE, 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        btnGo = button("Mulai update", true).apply { isEnabled = false }
        cFile.addView(btnFile); cFile.addView(fileInfo); cFile.addView(passLabel); cFile.addView(passEd)
        cFile.addView(gap(12)); cFile.addView(btnGo)
        root.addView(cFile)

        // Kemajuan
        progCard = card().apply { visibility = View.GONE }
        val rowPct = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        pctTx = text("0%", 34f, C_TXT, bold = true, mono = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        speedTx = text("", 13f, C_DIM, mono = true)
        rowPct.addView(pctTx); rowPct.addView(speedTx)
        val meter = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34)).apply { topMargin = dp(10); bottomMargin = dp(8) }
        }
        for (i in 0 until 16) {
            val b = View(this).apply {
                setBackgroundColor(C_OFF)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = dp(1); rightMargin = dp(1) }
            }
            boxes.add(b); meter.addView(b)
        }
        val rowInfo = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sentTx = text("", 13f, C_DIM, mono = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        etaTx = text("", 13f, C_DIM, mono = true)
        rowInfo.addView(sentTx); rowInfo.addView(etaTx)
        btnCancel = button("Batalkan", false)
        progCard.addView(rowPct); progCard.addView(meter); progCard.addView(rowInfo)
        progCard.addView(gap(12)); progCard.addView(btnCancel)
        root.addView(progCard)

        // Pesan
        msgTx = text("", 14f, C_TXT).apply {
            visibility = View.GONE
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) }
        }
        root.addView(msgTx)

        // Petunjuk
        val cHelp = card()
        cHelp.addView(text(
            "1. Di Arduino IDE pilih Sketch, lalu Export Compiled Binary. Ambil file berakhiran .ino.bin (bukan bootloader, partitions, atau merged).\n" +
                "2. Salin file ke HP, tekan Hubungkan, lalu pilih file.\n" +
                "3. Tekan Mulai update dan biarkan layar menyala sampai DSP restart sendiri.",
            14f, C_DIM
        ))
        cHelp.addView(text("Selama update, keluaran audio dibisukan dan layar TFT menampilkan persen kemajuan.", 13f, C_DIM).apply { setPadding(0, dp(10), 0, 0) })
        root.addView(cHelp)

        btnConn.setOnClickListener { if (gatt != null || scanning) disconnect() else startScan() }
        btnFile.setOnClickListener { pickFile() }
        btnGo.setOnClickListener { startUpdate() }
        btnCancel.setOnClickListener { cancel = true }
        refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScan()
        cancel = true
        closeGatt()
    }

    private fun say(s: String, kind: Int = 0) {   // kind: 0 biasa, 1 galat, 2 berhasil
        ui.post {
            if (s.isEmpty()) { msgTx.visibility = View.GONE; return@post }
            msgTx.visibility = View.VISIBLE
            msgTx.text = s
            val edge = when (kind) { 1 -> C_BAD; 2 -> C_OK; else -> C_LINE }
            msgTx.setTextColor(when (kind) { 1 -> Color.parseColor("#FFB3C0"); 2 -> Color.parseColor("#A6F3CF"); else -> C_TXT })
            msgTx.background = rounded(C_BG, edge, 10)
        }
    }

    private fun connected() = gatt != null && otaChar != null

    private fun refresh() {
        dot.background = rounded(if (connected()) C_OK else C_OFF, 0, 5)
        btnConn.text = if (connected() || scanning) (if (scanning && !connected()) "Batal cari" else "Putuskan") else "Hubungkan"
        btnConn.isEnabled = !busy
        btnFile.isEnabled = !busy
        passEd.isEnabled = !busy
        btnGo.isEnabled = connected() && bin != null && !busy
        btnGo.alpha = if (btnGo.isEnabled) 1f else 0.45f
        btnConn.alpha = if (btnConn.isEnabled) 1f else 0.45f
        btnFile.alpha = if (btnFile.isEnabled) 1f else 0.45f
    }

    private fun draw(frac: Float) {
        val lit = Math.round(frac * 16)
        for (i in boxes.indices) boxes[i].setBackgroundColor(if (i < lit) (if (i >= 13) C_ACC else C_CY) else C_OFF)
        pctTx.text = "${(frac * 100).toInt()}%"
    }

    private fun fmt(n: Int): String =
        if (n >= 1048576) String.format(Locale.US, "%.2f MB", n / 1048576.0) else "${n / 1024} KB"

    // ---------- Izin & pindai ----------

    private fun needed(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasPerms() = needed().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERM) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startScan()
        else say("Izin Bluetooth ditolak. Izinkan lewat pengaturan aplikasi lalu coba lagi.", 1)
    }

    private val scanCb = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            stopScan()
            connect(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            say("Pindai gagal (kode $errorCode). Pastikan Bluetooth menyala.", 1)
            ui.post { refresh() }
        }
    }
    private val scanTimeout = Runnable {
        if (scanning) {
            stopScan()
            say("LTRONIK-DSP tidak ditemukan. Pastikan DSP menyala dan belum tersambung ke HP lain. Di Android 11 ke bawah, nyalakan juga Lokasi.", 1)
            refresh()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!hasPerms()) { requestPermissions(needed(), REQ_PERM); return }
        val adapter: BluetoothAdapter? = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) { say("Nyalakan Bluetooth dulu.", 1); return }
        scanner = adapter.bluetoothLeScanner
        if (scanner == null) { say("Bluetooth belum siap. Coba lagi sebentar lagi.", 1); return }
        say("")
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SVC)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanning = true
        statusTx.text = "Mencari LTRONIK-DSP..."
        scanner?.startScan(listOf(filter), settings, scanCb)
        ui.postDelayed(scanTimeout, 12000)
        refresh()
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        ui.removeCallbacks(scanTimeout)
        if (scanning) {
            scanning = false
            try { scanner?.stopScan(scanCb) } catch (_: Exception) {}
        }
    }

    // ---------- GATT ----------

    @SuppressLint("MissingPermission")
    private fun connect(dev: BluetoothDevice) {
        statusTx.text = "Menghubungkan..."
        otaChar = null
        gatt = dev.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        refresh()
    }

    @SuppressLint("MissingPermission")
    private fun disconnect() {
        stopScan()
        cancel = true
        try { gatt?.disconnect() } catch (_: Exception) {}
        if (gatt == null) { statusTx.text = "Belum terhubung"; refresh() }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        otaChar = null
    }

    private fun push(v: ByteArray?) {
        if (v != null && v.isNotEmpty()) notifyQ.offer(v[0].toInt() and 0xFF)
    }

    private val gattCb = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                if (!g.requestMtu(247)) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                writeQ.offer(-1); notifyQ.offer(-1)
                try { g.close() } catch (_: Exception) {}
                if (gatt === g) { gatt = null; otaChar = null }
                ui.post {
                    statusTx.text = "Belum terhubung"
                    refresh()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            g.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val c = g.getService(SVC)?.getCharacteristic(OTA)
            if (c == null) {
                say("DSP ini belum punya fitur update. Flash firmware v1.9.6 atau yang lebih baru lewat kabel dulu.", 1)
                g.disconnect()
                return
            }
            val d = c.getDescriptor(CCCD)
            if (d == null) {
                say("DSP tidak mengirim notifikasi update. Flash firmware v1.9.6 atau yang lebih baru lewat kabel dulu.", 1)
                g.disconnect()
                return
            }
            g.setCharacteristicNotification(c, true)
            @Suppress("DEPRECATION")
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
            otaChar = c
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            ui.post {
                statusTx.text = if (connected()) "Terhubung ke ${g.device.name ?: "LTRONIK-DSP"}" else "Belum terhubung"
                refresh()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeQ.offer(status)
        }

        @Deprecated("Dipakai Android 12 ke bawah")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            push(c.value)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            push(value)
        }
    }

    // ---------- Pilih file ----------

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_FILE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FILE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        loadFile(uri)
    }

    private fun loadFile(uri: Uri) {
        bin = null
        fileInfo.visibility = View.GONE
        say("")
        var name = "firmware.bin"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val ix = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (ix >= 0 && c.moveToFirst()) name = c.getString(ix) ?: name
            }
        } catch (_: Exception) {}
        if (Regex("bootloader|partitions|merged", RegexOption.IGNORE_CASE).containsMatchIn(name)) {
            say("File ini bukan firmware utama. Pakai file berakhiran .ino.bin.", 1); refresh(); return
        }
        val data = try {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) { null }
        if (data == null) { say("File tidak bisa dibaca.", 1); refresh(); return }
        if (data.size < 65536) { say("File terlalu kecil untuk firmware DSP.", 1); refresh(); return }
        if ((data[0].toInt() and 0xFF) != 0xE9) { say("Isi file bukan gambar firmware ESP32 (byte awal harus E9).", 1); refresh(); return }
        val c = CRC32(); c.update(data)
        bin = data; crc = c.value
        fileInfo.visibility = View.VISIBLE
        fileInfo.text = "$name\n${fmt(data.size)}   CRC ${String.format(Locale.US, "%08x", crc)}"
        refresh()
    }

    // ---------- Update ----------

    private fun errText(code: Int): String = when (code) {
        0x81 -> "Sandi update salah."
        0x82 -> "DSP menolak update. Pastikan Partition Scheme di Arduino IDE punya slot OTA dan ukuran file wajar."
        0x83 -> "Gagal menulis ke memori flash DSP."
        0x84 -> "CRC file tidak cocok setelah dikirim. Coba ulangi."
        0x85 -> "DSP menolak firmware ini. File mungkin bukan .ino.bin untuk ESP32-S3."
        0x86, -1 -> "Waktu habis atau koneksi putus."
        0x87 -> "Urutan paket salah. Ulangi dari awal."
        else -> "Balasan tidak dikenal ($code)."
    }

    private fun putLe(a: ByteArray, off: Int, v: Long) {
        for (i in 0 until 4) a[off + i] = ((v shr (8 * i)) and 0xFF).toByte()
    }

    @SuppressLint("MissingPermission")
    private fun send(g: BluetoothGatt, c: BluetoothGattCharacteristic, data: ByteArray) {
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        @Suppress("DEPRECATION")
        c.value = data
        var tries = 0
        @Suppress("DEPRECATION")
        while (!g.writeCharacteristic(c)) {
            if (++tries > 100) throw Fail("Gagal mengirim ke DSP.")
            Thread.sleep(20)
        }
        val st = writeQ.poll(10, TimeUnit.SECONDS) ?: throw Fail("Waktu habis menunggu DSP.")
        if (st == -1) throw Fail("Koneksi putus.")
        if (st != BluetoothGatt.GATT_SUCCESS) throw Fail("Gagal menulis (kode $st).")
    }

    private fun waitCode(ms: Long): Int = notifyQ.poll(ms, TimeUnit.MILLISECONDS) ?: 0x86

    private fun startUpdate() {
        val data = bin ?: return
        val g = gatt ?: return
        val c = otaChar ?: return
        if (busy) return
        val pw = passEd.text.toString().toByteArray(Charsets.UTF_8)
        val crcNow = crc
        busy = true; cancel = false
        say("")
        progCard.visibility = View.VISIBLE
        draw(0f); speedTx.text = ""; etaTx.text = ""; sentTx.text = "Menyiapkan"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refresh()

        Thread {
            var ok = false
            try {
                writeQ.clear(); notifyQ.clear()
                val head = ByteArray(9 + pw.size)
                head[0] = 0x42
                putLe(head, 1, data.size.toLong()); putLe(head, 5, crcNow)
                System.arraycopy(pw, 0, head, 9, pw.size)
                send(g, c, head)
                val first = waitCode(8000)
                if (first != 1) throw Fail(errText(first))

                val chunk = minOf(MAX_CHUNK, maxOf(16, mtu - 4))
                val t0 = SystemClock.elapsedRealtime()
                var lastUi = 0L
                var off = 0
                while (off < data.size) {
                    if (cancel) throw Fail("Update dibatalkan.")
                    val n = minOf(chunk, data.size - off)
                    val pkt = ByteArray(1 + n)
                    pkt[0] = 0x44
                    System.arraycopy(data, off, pkt, 1, n)
                    send(g, c, pkt)
                    off += n
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUi > 150 || off >= data.size) {
                        lastUi = now
                        val done = off
                        val sec = (now - t0) / 1000.0
                        ui.post {
                            draw(done.toFloat() / data.size)
                            sentTx.text = "${fmt(done)} dari ${fmt(data.size)}"
                            if (sec > 1) {
                                val bps = done / sec
                                speedTx.text = String.format(Locale.US, "%.1f KB/dtk", bps / 1024)
                                val left = Math.ceil((data.size - done) / bps).toInt()
                                etaTx.text = String.format(Locale.US, "sisa %d:%02d", left / 60, left % 60)
                            }
                        }
                    }
                }
                send(g, c, byteArrayOf(0x45))
                val end = waitCode(20000)
                if (end != 2) throw Fail(errText(end))
                ok = true
            } catch (e: Fail) {
                say(e.message ?: "Update gagal.", 1)
                try { if (gatt != null && !cancelledByDisconnect()) send(g, c, byteArrayOf(0x58)) } catch (_: Exception) {}
            } catch (e: Exception) {
                say("Update gagal: ${e.message}", 1)
            }
            ui.post {
                busy = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (ok) {
                    draw(1f); etaTx.text = ""; sentTx.text = "Terkirim"
                    say("Firmware terpasang. DSP restart sendiri dalam beberapa detik, lalu hubungkan ulang.", 2)
                } else {
                    sentTx.text = "Gagal"
                }
                refresh()
            }
        }.start()
    }

    private fun cancelledByDisconnect() = otaChar == null
}
