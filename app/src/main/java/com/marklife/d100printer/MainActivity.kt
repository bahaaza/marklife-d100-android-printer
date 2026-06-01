package com.marklife.d100printer

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var tvFileName: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnPrint: Button
    private lateinit var btnPrintBlank: Button
    private lateinit var btnSelectPdf: Button
    private lateinit var btnPreview: Button
    private lateinit var btnSettings: Button
    private lateinit var etCopies: EditText
    private lateinit var ivPreview: ImageView
    private lateinit var progressBar: ProgressBar

    private var pendingPdfUri: Uri? = null
    private var pendingPdfUrl: String? = null
    private var pendingAction: PendingAction = PendingAction.NONE
    private var isPrinting: Boolean = false
    private var currentShareToken: String? = null
    private var lastAutoPrintToken: String? = null
    private var lastAutoPrintStartedAtMs: Long = 0
    private var currentPreviewBitmap: Bitmap? = null

    private enum class PendingAction {
        NONE,
        PRINT,
        PRINT_BLANK,
        OPEN_SETTINGS,
    }

    private data class PrintSettings(
        val widthMm: Int,
        val heightMm: Int,
        val heightCompMm: Int,
        val paperMode: Int,
        val printerAddress: String?,
        val autoPrintOnShare: Boolean,
    )

    private val pickPdfLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            persistReadPermissionIfAvailable(uri)
            pendingPdfUri = uri
            pendingPdfUrl = null
            currentShareToken = "picked:${uri}"
            tvFileName.text = resolveFileName(uri)
            btnPrint.isEnabled = true
            btnPreview.isEnabled = true
            setStatus(getString(R.string.file_selected_ready))
            previewUri(uri)
        }
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 1001
        private const val PREFS_NAME = "print_settings"
        private const val PREF_WIDTH_MM = "label_width_mm"
        private const val PREF_HEIGHT_MM = "label_height_mm"
        private const val PREF_HEIGHT_COMP_MM = "label_height_comp_mm"
        private const val PREF_PAPER_MODE = "paper_mode"
        private const val PREF_PRINTER_ADDRESS = "printer_address"
        private const val PREF_AUTO_PRINT_ON_SHARE = "auto_print_on_share"
        private const val DOTS_PER_MM = 8

        private const val DEFAULT_WIDTH_MM = 100
        private const val DEFAULT_HEIGHT_MM = 150
        private const val DEFAULT_HEIGHT_COMP_MM = 2
        private const val DEFAULT_PAPER_MODE = D100Printer.PAPER_TYPE_GAP
        private const val MIN_LABEL_MM = 1
        private const val MAX_WIDTH_MM = 120
        private const val MAX_HEIGHT_MM = 300
        private const val MAX_HEIGHT_COMP_MM = 20

        private val REQUIRED_PERMISSIONS: Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            } else {
                arrayOf(
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvFileName = findViewById(R.id.tvFileName)
        tvStatus = findViewById(R.id.tvStatus)
        btnPrint = findViewById(R.id.btnPrint)
        btnPrintBlank = findViewById(R.id.btnPrintBlank)
        btnSelectPdf = findViewById(R.id.btnSelectPdf)
        btnPreview = findViewById(R.id.btnPreview)
        btnSettings = findViewById(R.id.btnSettings)
        etCopies = findViewById(R.id.etCopies)
        ivPreview = findViewById(R.id.ivPreview)
        progressBar = findViewById(R.id.progressBar)

        btnSelectPdf.setOnClickListener { onSelectPdfClicked() }
        btnPreview.setOnClickListener { onPreviewClicked() }
        btnPrint.setOnClickListener { onPrintClicked() }
        btnPrintBlank.setOnClickListener { onPrintBlankClicked() }
        btnSettings.setOnClickListener { onSettingsClicked() }

        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onDestroy() {
        currentPreviewBitmap?.recycle()
        currentPreviewBitmap = null
        super.onDestroy()
    }

    private fun handleIncomingIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND && intent.type == "application/pdf") {
            val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (uri != null) {
                pendingPdfUri = uri
                pendingPdfUrl = null
                currentShareToken = "pdf:${uri}"
                tvFileName.text = resolveFileName(uri)
                setStatus("Ready — tap Print to send to D100.")
                btnPrint.isEnabled = true
                btnPreview.isEnabled = true
                maybeAutoPrintOnShare()
            }
        } else if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
            if (sharedText.isNotBlank()) {
                val link = extractFirstHttpUrl(sharedText)
                if (link != null && looksLikePdfLink(link)) {
                    pendingPdfUri = null
                    pendingPdfUrl = link
                    currentShareToken = "url:$link"
                    tvFileName.text = getString(R.string.link_received)
                    btnPrint.isEnabled = true
                    btnPreview.isEnabled = true
                    setStatus(getString(R.string.link_pdf_ready_hint))
                    maybeAutoPrintOnShare()
                } else {
                    pendingPdfUri = null
                    pendingPdfUrl = null
                    currentShareToken = null
                    tvFileName.text = getString(R.string.link_received)
                    btnPrint.isEnabled = false
                    btnPreview.isEnabled = false
                    setStatus(getString(R.string.link_share_hint))
                }
            }
        }
    }

    private fun onSelectPdfClicked() {
        pickPdfLauncher.launch(arrayOf("application/pdf"))
    }

    private fun onPreviewClicked() {
        val uri = pendingPdfUri
        val link = pendingPdfUrl
        when {
            uri != null -> previewUri(uri)
            link != null -> previewFromLink(link)
            else -> setStatus(getString(R.string.preview_no_source))
        }
    }

    private fun resolveFileName(uri: Uri): String {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (col >= 0 && cursor.moveToFirst()) cursor.getString(col) else null
        } ?: uri.lastPathSegment ?: "document.pdf"
    }

    private fun onPrintClicked() {
        if (pendingPdfUri == null && pendingPdfUrl == null) {
            setStatus("No PDF selected. Share a PDF file to this app first.")
            return
        }

        pendingAction = PendingAction.PRINT
        ensureBluetoothPermissions {
            startPendingPrint()
        }
    }

    private fun startPendingPrint() {
        val uri = pendingPdfUri
        val link = pendingPdfUrl
        when {
            uri != null -> startPrintJob(uri)
            link != null -> startPrintJobFromLink(link)
            else -> setStatus("No PDF selected. Share a PDF file to this app first.")
        }
    }

    private fun onSettingsClicked() {
        pendingAction = PendingAction.OPEN_SETTINGS
        ensureBluetoothPermissions {
            showSettingsDialog()
        }
    }

    private fun maybeAutoPrintOnShare() {
        if (!loadSettings().autoPrintOnShare) return
        if (isPrinting) return

        val token = currentShareToken ?: return
        val now = System.currentTimeMillis()
        if (token == lastAutoPrintToken && (now - lastAutoPrintStartedAtMs) < 15_000L) {
            return
        }

        lastAutoPrintToken = token
        lastAutoPrintStartedAtMs = now

        setStatus(getString(R.string.auto_printing_shared_pdf))
        pendingAction = PendingAction.PRINT
        ensureBluetoothPermissions {
            startPendingPrint()
        }
    }

    private fun onPrintBlankClicked() {
        pendingAction = PendingAction.PRINT_BLANK
        ensureBluetoothPermissions {
            startBlankTestPrint()
        }
    }

    private fun ensureBluetoothPermissions(onGranted: () -> Unit) {
        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_PERMISSIONS)
        } else {
            pendingAction = PendingAction.NONE
            onGranted()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                when (pendingAction) {
                    PendingAction.PRINT -> startPendingPrint()
                    PendingAction.PRINT_BLANK -> startBlankTestPrint()
                    PendingAction.OPEN_SETTINGS -> showSettingsDialog()
                    PendingAction.NONE -> Unit
                }
            } else {
                setStatus("Bluetooth permissions denied.")
            }
            pendingAction = PendingAction.NONE
        }
    }

    @SuppressLint("MissingPermission")
    private fun startPrintJob(uri: Uri) {
        startPrintJobInternal(uriProvider = { uri })
    }

    @SuppressLint("MissingPermission")
    private fun startPrintJobFromLink(link: String) {
        startPrintJobInternal(uriProvider = {
            setStatus(getString(R.string.link_downloading))
            downloadPdfFromLink(link)
        })
    }

    @SuppressLint("MissingPermission")
    private fun startPrintJobInternal(uriProvider: suspend () -> Uri) {
        if (isPrinting) {
            setStatus(getString(R.string.print_already_running))
            return
        }

        val bluetoothAdapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            setStatus("Bluetooth is off. Please enable it and try again.")
            return
        }

        isPrinting = true
        btnPrint.isEnabled = false
        btnPrintBlank.isEnabled = false
        btnSettings.isEnabled = false
        btnSelectPdf.isEnabled = false
        btnPreview.isEnabled = false
        progressBar.visibility = View.VISIBLE

        val settings = loadSettings()
        val widthDots = mmToDots(settings.widthMm)
        val effectiveHeightMm = (settings.heightMm + settings.heightCompMm).coerceAtLeast(1)
        val heightDots = mmToDots(effectiveHeightMm)
        val nativeWidthDots = widthDots + 64
        val copies = getRequestedCopies()

        lifecycleScope.launch {
            var bitmaps: List<Bitmap> = emptyList()
            try {
                val workingUri = withContext(Dispatchers.IO) { uriProvider() }
                val stagedUri = withContext(Dispatchers.IO) { stagePdfForPrint(workingUri) }
                pendingPdfUri = stagedUri
                pendingPdfUrl = null

                setStatus("Rendering PDF…")
                bitmaps = withContext(Dispatchers.IO) {
                    PdfPageRenderer(contentResolver).renderAllPages(
                        uri = stagedUri,
                        widthDots = widthDots,
                        heightDots = heightDots,
                    )
                }
                val pageCount = bitmaps.size

                setStatus("Connecting to D100…")
                val printer = D100Printer(
                    bluetoothAdapter = bluetoothAdapter,
                    preferredAddress = settings.printerAddress,
                    nativeWidthDots = nativeWidthDots,
                    paperType = settings.paperMode,
                )

                withContext(Dispatchers.IO) {
                    printer.connect()
                    try {
                        bitmaps.forEachIndexed { index, bitmap ->
                            for (copy in 1..copies) {
                                withContext(Dispatchers.Main) {
                                    setStatus("Printing page ${index + 1}/${bitmaps.size}, copy ${copy}/${copies}…")
                                }
                                printer.printBitmap(bitmap)
                            }
                        }
                    } finally {
                        printer.disconnect()
                    }
                }

                setStatus("Done! ${pageCount} page(s) printed.")
                Toast.makeText(this@MainActivity, "Printed successfully!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                setStatus("Error: ${e.message}")
                Toast.makeText(this@MainActivity, "Print failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                recycleBitmaps(bitmaps)
                isPrinting = false
                btnPrint.isEnabled = true
                btnPrintBlank.isEnabled = true
                btnSettings.isEnabled = true
                btnSelectPdf.isEnabled = true
                btnPreview.isEnabled = (pendingPdfUri != null || pendingPdfUrl != null)
                progressBar.visibility = View.GONE
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBlankTestPrint() {
        if (isPrinting) {
            setStatus(getString(R.string.print_already_running))
            return
        }

        val bluetoothAdapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            setStatus("Bluetooth is off. Please enable it and try again.")
            return
        }

        isPrinting = true
        btnPrint.isEnabled = false
        btnPrintBlank.isEnabled = false
        btnSettings.isEnabled = false
        btnSelectPdf.isEnabled = false
        btnPreview.isEnabled = false
        progressBar.visibility = View.VISIBLE

        val settings = loadSettings()
        val widthDots = mmToDots(settings.widthMm)
        val effectiveHeightMm = (settings.heightMm + settings.heightCompMm).coerceAtLeast(1)
        val heightDots = mmToDots(effectiveHeightMm)
        val nativeWidthDots = widthDots + 64
        val copies = getRequestedCopies()

        lifecycleScope.launch {
            try {
                setStatus(getString(R.string.blank_test_preparing))
                val blank = withContext(Dispatchers.IO) {
                    Bitmap.createBitmap(widthDots, heightDots, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(Color.WHITE)
                    }
                }

                setStatus("Connecting to D100…")
                val printer = D100Printer(
                    bluetoothAdapter = bluetoothAdapter,
                    preferredAddress = settings.printerAddress,
                    nativeWidthDots = nativeWidthDots,
                    paperType = settings.paperMode,
                )

                withContext(Dispatchers.IO) {
                    printer.connect()
                    try {
                        for (copy in 1..copies) {
                            withContext(Dispatchers.Main) {
                                setStatus("Printing blank test page copy ${copy}/${copies}…")
                            }
                            printer.printBitmap(blank)
                        }
                    } finally {
                        printer.disconnect()
                        blank.recycle()
                    }
                }

                setStatus(getString(R.string.blank_test_done))
                Toast.makeText(this@MainActivity, getString(R.string.blank_test_done), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                setStatus("Error: ${e.message}")
                Toast.makeText(this@MainActivity, "Print failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                isPrinting = false
                btnPrint.isEnabled = true
                btnPrintBlank.isEnabled = true
                btnSettings.isEnabled = true
                btnSelectPdf.isEnabled = true
                btnPreview.isEnabled = (pendingPdfUri != null || pendingPdfUrl != null)
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun getRequestedCopies(): Int {
        val requested = etCopies.text?.toString()?.trim()?.toIntOrNull() ?: 1
        return requested.coerceIn(1, 999)
    }

    private fun previewFromLink(link: String) {
        btnPreview.isEnabled = false
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                setStatus(getString(R.string.preview_downloading_link))
                val uri = withContext(Dispatchers.IO) { downloadPdfFromLink(link) }
                pendingPdfUri = uri
                pendingPdfUrl = null
                tvFileName.text = resolveFileName(uri)
                btnPrint.isEnabled = true
                previewUri(uri)
            } catch (e: Exception) {
                setStatus("Preview failed: ${e.message}")
            } finally {
                btnPreview.isEnabled = true
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun previewUri(uri: Uri) {
        lifecycleScope.launch {
            try {
                setStatus(getString(R.string.preview_rendering))
                val preview = withContext(Dispatchers.IO) {
                    PdfPageRenderer(contentResolver).renderFirstPagePreview(uri, 420, 700)
                }
                showPreviewBitmap(preview)
                setStatus(getString(R.string.preview_ready))
            } catch (e: Exception) {
                setStatus("Preview failed: ${e.message}")
            }
        }
    }

    private fun showPreviewBitmap(bitmap: Bitmap) {
        currentPreviewBitmap?.takeIf { !it.isRecycled }?.recycle()
        currentPreviewBitmap = bitmap
        ivPreview.setImageBitmap(bitmap)
        ivPreview.visibility = View.VISIBLE
    }

    private fun persistReadPermissionIfAvailable(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some providers grant only transient access; staging still works while the app is active.
        }
    }

    private fun recycleBitmaps(bitmaps: List<Bitmap>) {
        bitmaps.forEach { bitmap ->
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private fun extractFirstHttpUrl(text: String): String? {
        val regex = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)
        return regex.find(text)?.value
            ?.trim()
            ?.trimEnd('.', ',', ';', ')', ']', '}', '>')
    }

    private fun looksLikePdfLink(url: String): Boolean {
        val normalized = url.substringBefore('#').substringBefore('?').lowercase()
        return normalized.endsWith(".pdf")
    }

    private fun downloadPdfFromLink(link: String): Uri {
        val connection = (URL(link).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 120000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "D100Printer/1.0")
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("Could not download PDF link (HTTP $code).")
            }

            val target = File(cacheDir, "shared_${System.currentTimeMillis()}.pdf")
            val expectedLength = connection.contentLengthLong.takeIf { it > 0L }
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }

            if (expectedLength != null && target.length() < expectedLength) {
                target.delete()
                throw IllegalStateException("Downloaded PDF is incomplete (${target.length()}/$expectedLength bytes).")
            }

            if (target.length() <= 4L) {
                throw IllegalStateException("Downloaded file is empty.")
            }

            if (!isValidPdfFile(target)) {
                target.delete()
                throw IllegalStateException("Shared link did not return a PDF file.")
            }

            return Uri.fromFile(target)
        } finally {
            connection.disconnect()
        }
    }

    private fun stagePdfForPrint(uri: Uri): Uri {
        if (uri.scheme == "file") {
            return uri
        }

        val target = File(cacheDir, "staged_${System.currentTimeMillis()}.pdf")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Cannot open shared PDF stream.")

        if (!isValidPdfFile(target)) {
            target.delete()
            throw IllegalStateException("Shared PDF appears incomplete or invalid.")
        }

        return Uri.fromFile(target)
    }

    private fun isValidPdfFile(file: File): Boolean {
        if (file.length() <= 8L) return false

        val hasPdfHeader = FileInputStream(file).use { stream ->
            val header = ByteArray(4)
            val count = stream.read(header)
            count == 4 &&
                header[0] == '%'.code.toByte() &&
                header[1] == 'P'.code.toByte() &&
                header[2] == 'D'.code.toByte() &&
                header[3] == 'F'.code.toByte()
        }
        if (!hasPdfHeader) return false

        // Most valid PDFs end with %%EOF near the tail.
        return RandomAccessFile(file, "r").use { raf ->
            val tailLength = minOf(2048L, raf.length()).toInt()
            val tail = ByteArray(tailLength)
            raf.seek(raf.length() - tailLength)
            raf.readFully(tail)
            String(tail, Charsets.ISO_8859_1).contains("%%EOF")
        }
    }

    @SuppressLint("MissingPermission")
    private fun showSettingsDialog() {
        val bluetoothAdapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (bluetoothAdapter == null) {
            setStatus("Bluetooth is not available on this device.")
            return
        }

        val current = loadSettings()
        val pairedDevices = bluetoothAdapter.bondedDevices
            ?.sortedBy { it.name ?: it.address }
            ?: emptyList()

        val view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val etWidth = view.findViewById<EditText>(R.id.etWidthMm)
        val etHeight = view.findViewById<EditText>(R.id.etHeightMm)
        val etHeightComp = view.findViewById<EditText>(R.id.etHeightCompMm)
        val spinnerPaperMode = view.findViewById<Spinner>(R.id.spinnerPaperMode)
        val spinner = view.findViewById<Spinner>(R.id.spinnerPrinters)
        val cbAutoPrint = view.findViewById<CheckBox>(R.id.cbAutoPrintOnShare)
        val btnTestConnection = view.findViewById<Button>(R.id.btnTestConnection)
        val tvTestResult = view.findViewById<TextView>(R.id.tvTestResult)

        etWidth.setText(current.widthMm.toString())
        etHeight.setText(current.heightMm.toString())
        etHeightComp.setText(current.heightCompMm.toString())
        cbAutoPrint.isChecked = current.autoPrintOnShare

        val paperModeOptions = listOf(
            getString(R.string.paper_mode_gap),
            getString(R.string.paper_mode_continuous),
        )
        val paperModeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, paperModeOptions)
        paperModeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerPaperMode.adapter = paperModeAdapter
        spinnerPaperMode.setSelection(
            if (current.paperMode == D100Printer.PAPER_TYPE_CONTINUOUS) 1 else 0
        )

        val options = mutableListOf(getString(R.string.printer_auto_option))
        options.addAll(pairedDevices.map { formatPrinterLabel(it) })

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        val selectedIndex = pairedDevices.indexOfFirst {
            current.printerAddress != null && it.address.equals(current.printerAddress, ignoreCase = true)
        }
        spinner.setSelection(if (selectedIndex >= 0) selectedIndex + 1 else 0)

        btnTestConnection.setOnClickListener {
            val bluetoothAdapterForTest = getSystemService(BluetoothManager::class.java)?.adapter
            if (bluetoothAdapterForTest == null || !bluetoothAdapterForTest.isEnabled) {
                tvTestResult.text = getString(R.string.test_result_bluetooth_off)
                return@setOnClickListener
            }

            val selectedAddress = if (spinner.selectedItemPosition <= 0) {
                null
            } else {
                pairedDevices.getOrNull(spinner.selectedItemPosition - 1)?.address
            }

            btnTestConnection.isEnabled = false
            tvTestResult.text = getString(R.string.test_result_running)

            lifecycleScope.launch {
                val resultText = withContext(Dispatchers.IO) {
                    try {
                        val probe = D100Printer(
                            bluetoothAdapter = bluetoothAdapterForTest,
                            preferredAddress = selectedAddress,
                        ).testConnection()

                        buildString {
                            appendLine(getString(R.string.test_result_success))
                            appendLine("${getString(R.string.test_result_device)} ${probe.deviceName} (${probe.deviceAddress})")
                            appendLine("${getString(R.string.test_result_firmware)} ${probe.firmwareVersion ?: getString(R.string.test_result_unknown)}")
                            appendLine("${getString(R.string.test_result_serial)} ${probe.serialNumber ?: getString(R.string.test_result_unknown)}")
                            if (probe.statusPacketsHex.isNotEmpty()) {
                                appendLine(getString(R.string.test_result_status_packets))
                                probe.statusPacketsHex.forEachIndexed { index, packet ->
                                    appendLine("${index + 1}. $packet")
                                }
                            }
                        }.trim()
                    } catch (e: Exception) {
                        "${getString(R.string.test_result_failed)} ${e.message ?: "Unknown error"}"
                    }
                }

                tvTestResult.text = resultText
                btnTestConnection.isEnabled = true
            }
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.settings_save) { _, _ ->
                val widthMm = etWidth.text?.toString()?.toIntOrNull()
                val heightMm = etHeight.text?.toString()?.toIntOrNull()
                val heightCompMm = etHeightComp.text?.toString()?.toIntOrNull() ?: DEFAULT_HEIGHT_COMP_MM

                if (
                    widthMm == null ||
                    heightMm == null ||
                    widthMm !in MIN_LABEL_MM..MAX_WIDTH_MM ||
                    heightMm !in MIN_LABEL_MM..MAX_HEIGHT_MM
                ) {
                    setStatus("Label size must be ${MIN_LABEL_MM}-${MAX_WIDTH_MM}mm wide and ${MIN_LABEL_MM}-${MAX_HEIGHT_MM}mm high.")
                    return@setPositiveButton
                }

                val printerAddress = if (spinner.selectedItemPosition <= 0) {
                    null
                } else {
                    pairedDevices.getOrNull(spinner.selectedItemPosition - 1)?.address
                }

                val paperMode = if (spinnerPaperMode.selectedItemPosition == 1) {
                    D100Printer.PAPER_TYPE_CONTINUOUS
                } else {
                    D100Printer.PAPER_TYPE_GAP
                }

                saveSettings(
                    PrintSettings(
                        widthMm = widthMm,
                        heightMm = heightMm,
                        heightCompMm = heightCompMm.coerceIn(0, MAX_HEIGHT_COMP_MM),
                        paperMode = paperMode,
                        printerAddress = printerAddress,
                        autoPrintOnShare = cbAutoPrint.isChecked,
                    )
                )
                val modeLabel = if (paperMode == D100Printer.PAPER_TYPE_CONTINUOUS) {
                    getString(R.string.paper_mode_continuous)
                } else {
                    getString(R.string.paper_mode_gap)
                }
                setStatus("Settings saved: ${widthMm}mm x ${heightMm}mm (+${heightCompMm.coerceIn(0, MAX_HEIGHT_COMP_MM)}mm), $modeLabel.")
            }
            .show()
    }

    private fun loadSettings(): PrintSettings {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return PrintSettings(
            widthMm = prefs.getInt(PREF_WIDTH_MM, DEFAULT_WIDTH_MM).coerceIn(MIN_LABEL_MM, MAX_WIDTH_MM),
            heightMm = prefs.getInt(PREF_HEIGHT_MM, DEFAULT_HEIGHT_MM).coerceIn(MIN_LABEL_MM, MAX_HEIGHT_MM),
            heightCompMm = prefs.getInt(PREF_HEIGHT_COMP_MM, DEFAULT_HEIGHT_COMP_MM).coerceIn(0, MAX_HEIGHT_COMP_MM),
            paperMode = prefs.getInt(PREF_PAPER_MODE, DEFAULT_PAPER_MODE),
            printerAddress = prefs.getString(PREF_PRINTER_ADDRESS, null),
            autoPrintOnShare = prefs.getBoolean(PREF_AUTO_PRINT_ON_SHARE, false),
        )
    }

    private fun saveSettings(settings: PrintSettings) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(PREF_WIDTH_MM, settings.widthMm)
            .putInt(PREF_HEIGHT_MM, settings.heightMm)
            .putInt(PREF_HEIGHT_COMP_MM, settings.heightCompMm)
            .putInt(PREF_PAPER_MODE, settings.paperMode)
            .putString(PREF_PRINTER_ADDRESS, settings.printerAddress)
            .putBoolean(PREF_AUTO_PRINT_ON_SHARE, settings.autoPrintOnShare)
            .apply()
    }

    private fun formatPrinterLabel(device: BluetoothDevice): String {
        val name = device.name?.takeIf { it.isNotBlank() } ?: "Unknown"
        return "$name (${device.address})"
    }

    private fun mmToDots(mm: Int): Int = (mm * DOTS_PER_MM)

    private fun setStatus(msg: String) {
        runOnUiThread { tvStatus.text = msg }
    }
}
