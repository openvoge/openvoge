package dev.vogy.app

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.text.Spannable
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.app.Dialog
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.SystemClock
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import kotlin.math.roundToInt
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.updatePadding
import android.graphics.Color
import android.view.HapticFeedbackConstants
import androidx.activity.OnBackPressedCallback
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class MainActivity : AppCompatActivity(), MotorcycleTelemetryController.Listener, CameraController.Listener {

    private enum class Section {
        DASHBOARD,
        CAMERA
    }

    companion object {
        private const val PREFS_UI = "ui_prefs"
        private const val KEY_DARK_THEME = "dark_theme"
        private const val KEY_CAM_AUTO_CONNECT = "cam_auto_connect"
        private const val KEY_CAM_SMART_FILTER = "cam_smart_filter"
        private const val KEY_CAM_SAVED_SSID = "cam_saved_ssid"
        private const val KEY_ACCENT_COLOR = "key_accent_color"
        private const val FPS_WINDOW_MS = 2_000L
        private const val REQUEST_NOTIFICATIONS = 4202
        private const val PLACEHOLDER_ASPECT = "358:410"
    }

    private lateinit var tvStatus: TextView
    private lateinit var tvConnectionState: TextView
    private lateinit var tvTopBarTitle: TextView
    private lateinit var btnCameraSection: ImageButton
    private lateinit var btnNavAction: View
    private lateinit var ivNavActionIcon: ImageView
    private lateinit var tvNavActionText: TextView
    private lateinit var dashboardSection: View
    private lateinit var cameraSection: View
    private lateinit var speedometerView: SpeedometerView
    private lateinit var tvRpmValue: TextView
    private lateinit var tvTelemetryStatus: TextView
    private lateinit var tvTelemetryDevice: TextView
    private lateinit var tvCommandValue: TextView
    private lateinit var tvAverageSpeedValue: TextView
    private lateinit var tvMaxSpeedValue: TextView
    private lateinit var tvMaxSpeedLabel: TextView
    private lateinit var tvFuelValue: TextView
    private lateinit var tvTemperatureValue: TextView
    private lateinit var tvTripTimeValue: TextView
    private lateinit var tvTripKmValue: TextView
    private lateinit var tvTotalKmValue: TextView
    private lateinit var gearPodView: GearPodView
    private lateinit var tvVoltageValue: TextView
    private lateinit var tvCameraStatus: TextView
    private lateinit var tvCameraHost: TextView
    private lateinit var tvCameraStream: TextView
    private lateinit var tvCameraHint: TextView
    private lateinit var cameraStreamView: ImageView
    private lateinit var cameraVideoView: PlayerView
    private var exoPlayer: ExoPlayer? = null
    private lateinit var cameraViewport: View
    private lateinit var btnCameraFullscreen: ImageButton
    private lateinit var tvResolutionUnit: TextView
    private lateinit var tvFrameRateUnit: TextView
    private var fullscreenDialog: Dialog? = null
    private var fullscreenPlayerView: PlayerView? = null
    private var fullscreenImageView: ImageView? = null
    private var mjpegFpsWindowStartMs = 0L
    private var mjpegFpsFrameCount = 0
    private lateinit var telemetryCard: View
    private lateinit var cameraCard: View
    private lateinit var btnCameraConfig: ImageButton
    private lateinit var btnToggleCameraConnection: com.google.android.material.button.MaterialButton
    private lateinit var btnCameraPhoto: ImageButton
    private lateinit var btnCameraRecord: ImageButton
    private lateinit var btnModePhoto: TextView
    private lateinit var btnModeVideo: TextView
    private lateinit var tvCameraRec: TextView
    private lateinit var tvCameraLive: TextView
    private lateinit var tvCameraSpeedValue: TextView
    private lateinit var tvCameraSpeedUnit: TextView
    private lateinit var tvResolutionVal: TextView
    private lateinit var tvFrameRateVal: TextView
    private var recTimerJob: Job? = null
    private var recSeconds = 0
    private var isRecording = false
    private var videoPausedInBackground = false
    private lateinit var badgeCameraRec: View
    private lateinit var dotCameraRec: View
    private lateinit var cameraWifiBanner: View
    private lateinit var cameraPlaceholder: View
    private lateinit var ivCameraWifiDot: ImageView
    private lateinit var tvCameraWifiBannerText: TextView
    private lateinit var cardConnectionBanner: LinearLayout
    private lateinit var viewConnectionDot: View
    private var connectionDotPulse: ObjectAnimator? = null
    private lateinit var tvConnectionDetail: TextView
    private lateinit var btnToggleConnection: androidx.appcompat.widget.AppCompatButton
    private lateinit var fuelBlocks: List<View>
    private lateinit var rpmBlocks: List<View>
    private val uiPreferences by lazy { getSharedPreferences(PREFS_UI, MODE_PRIVATE) }

    // Owned by CameraSession so a recording survives this activity
    private val cameraController: CameraController get() = CameraSession.controller(this)
    private val dashboardViewModel: DashboardViewModel by viewModels()
    private var isPermissionRequestInFlight = false
    private var isLocationPermissionRequestInFlight = false
    private var currentBikeName: String? = null
    private var currentSection: Section = Section.DASHBOARD
    private var telemetryStatusMessage: String = ""
    private var cameraStatusMessage: String = ""
    private var isCameraConnected = false
    /** True once the first MJPEG frame arrives — avoids redundant ViewModel calls on each frame. */
    private var mjpegStreamActive = false
    private var rtspForceTcp = false
    private var currentRtspUrl: String? = null
    private var rtspRetryCount = 0


    private val bluetoothPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        isPermissionRequestInFlight = false
        if (permissions.isNotEmpty() && permissions.values.all { it }) {
            dashboardViewModel.startAutoConnect()
        } else if (hasBluetoothRuntimePermissions()) {
            dashboardViewModel.startAutoConnect()
        } else {
            showBluetoothPermissionMissingDialog()
        }
    }

    private val locationPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        isLocationPermissionRequestInFlight = false
        if (permissions.values.any { it } || hasLocationPermission()) {
            lifecycleScope.launch {
                tvStatus.text = resolveLocationName()
            }
        } else {
            showLocationFallback()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applySavedThemeMode()
        super.onCreate(savedInstanceState)
        theme.applyStyle(accentOverlayFor(savedAccentColor()), true)
        setContentView(R.layout.activity_main)

        val mainRoot = findViewById<View>(R.id.mainRoot)
        ViewCompat.setOnApplyWindowInsetsListener(mainRoot) { view, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(
                top = systemBars.top,
                bottom = systemBars.bottom
            )
            insets
        }

        val dotSpacing = (24 * resources.displayMetrics.density).toInt().coerceAtLeast(16)
        val dotRadius = 1.2f * resources.displayMetrics.density
        val dotBitmap = Bitmap.createBitmap(dotSpacing, dotSpacing, Bitmap.Config.ARGB_8888)
        val dotCanvas = Canvas(dotBitmap)
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = getColor(R.color.neo_dot) }
        dotCanvas.drawCircle(dotSpacing / 2f, dotSpacing / 2f, dotRadius, dotPaint)
        val tiledDots = BitmapDrawable(resources, dotBitmap).apply {
            setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
        mainRoot.background = LayerDrawable(arrayOf(ColorDrawable(getColor(R.color.surface)), tiledDots))

        tvStatus = findViewById(R.id.tvStatus)
        tvConnectionState = findViewById(R.id.tvConnectionState)
        tvTopBarTitle = findViewById(R.id.tvTopBarTitle)
        btnCameraSection = findViewById(R.id.btnCameraSection)
        btnNavAction = findViewById(R.id.btnNavAction)
        ivNavActionIcon = findViewById(R.id.ivNavActionIcon)
        tvNavActionText = findViewById(R.id.tvNavActionText)
        dashboardSection = findViewById(R.id.dashboardSection)
        cameraSection = findViewById(R.id.cameraSection)
        speedometerView = findViewById(R.id.speedometerView)
        tvRpmValue = findViewById(R.id.tvRpmValue)
        tvTelemetryStatus = findViewById(R.id.tvTelemetryStatus)
        tvTelemetryDevice = findViewById(R.id.tvTelemetryDevice)
        tvCommandValue = findViewById(R.id.tvCommandValue)
        tvAverageSpeedValue = findViewById(R.id.tvAverageSpeedValue)
        tvMaxSpeedValue = findViewById(R.id.tvMaxSpeedValue)
        tvMaxSpeedLabel = findViewById(R.id.tvMaxSpeedLabel)
        tvFuelValue = findViewById(R.id.tvFuelValue)
        tvTemperatureValue = findViewById(R.id.tvTemperatureValue)
        tvTripTimeValue = findViewById(R.id.tvTripTimeValue)
        tvTripKmValue = findViewById(R.id.tvTripKmValue)
        tvTotalKmValue = findViewById(R.id.tvTotalKmValue)
        gearPodView = findViewById(R.id.gearPodView)
        tvVoltageValue = findViewById(R.id.tvVoltageValue)
        tvCameraStatus = findViewById(R.id.tvCameraStatus)
        tvCameraHost = findViewById(R.id.tvCameraHost)
        tvCameraStream = findViewById(R.id.tvCameraStream)
        tvCameraHint = findViewById(R.id.tvCameraHint)
        cameraStreamView = findViewById(R.id.cameraStreamView)
        cameraVideoView = findViewById(R.id.cameraVideoView)
        telemetryCard = findViewById(R.id.telemetryCard)
        cameraCard = findViewById(R.id.cameraCard)
        btnCameraConfig = findViewById(R.id.btnCameraConfig)
        btnToggleCameraConnection = findViewById(R.id.btnToggleCameraConnection)
        btnCameraPhoto = findViewById(R.id.btnCameraPhoto)
        btnCameraRecord = findViewById(R.id.btnCameraRecord)
        btnModePhoto = findViewById(R.id.btnModePhoto)
        btnModeVideo = findViewById(R.id.btnModeVideo)
        badgeCameraRec = findViewById(R.id.badgeCameraRec)
        dotCameraRec = findViewById(R.id.dotCameraRec)
        tvCameraRec = findViewById(R.id.tvCameraRec)
        tvCameraLive = findViewById(R.id.tvCameraLive)
        tvCameraSpeedValue = findViewById(R.id.tvCameraSpeedValue)
        tvCameraSpeedUnit = findViewById(R.id.tvCameraSpeedUnit)
        tvResolutionVal = findViewById(R.id.tvResolutionVal)
        tvFrameRateVal = findViewById(R.id.tvFrameRateVal)
        tvResolutionUnit = findViewById(R.id.tvResolutionUnit)
        tvFrameRateUnit = findViewById(R.id.tvFrameRateUnit)
        cameraViewport = findViewById(R.id.cameraViewport)
        btnCameraFullscreen = findViewById(R.id.btnCameraFullscreen)
        btnCameraFullscreen.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            enterCameraFullscreen()
        }
        cameraWifiBanner = findViewById(R.id.cameraWifiBanner)
        cameraPlaceholder = findViewById(R.id.cameraPlaceholder)
        ivCameraWifiDot = findViewById(R.id.ivCameraWifiDot)
        tvCameraWifiBannerText = findViewById(R.id.tvCameraWifiBannerText)

        badgeCameraRec.visibility = View.GONE
        btnCameraRecord.setImageResource(R.drawable.ic_record_btn_neo)
        setCameraCaptureAvailable(false)

        cardConnectionBanner = findViewById<LinearLayout>(R.id.cardConnectionBanner)
        viewConnectionDot = findViewById(R.id.viewConnectionDot)
        tvConnectionDetail = findViewById<TextView>(R.id.tvConnectionDetail)
        btnToggleConnection = findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.btnToggleConnection)

        btnToggleConnection.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (dashboardViewModel.uiState.value.connectionPhase == ConnectionPhase.DISCONNECTED) {
                showBikePickerSheet()
            } else {
                // CONNECTED → disconnect; CONNECTING → cancel the attempt
                dashboardViewModel.disconnectBike()
            }
        }

        fuelBlocks = listOf(
            findViewById(R.id.fuelB1),
            findViewById(R.id.fuelB2),
            findViewById(R.id.fuelB3),
            findViewById(R.id.fuelB4),
            findViewById(R.id.fuelB5),
            findViewById(R.id.fuelB6),
            findViewById(R.id.fuelB7),
            findViewById(R.id.fuelB8)
        )

        rpmBlocks = listOf(
            findViewById(R.id.rpmB1),
            findViewById(R.id.rpmB2),
            findViewById(R.id.rpmB3),
            findViewById(R.id.rpmB4),
            findViewById(R.id.rpmB5),
            findViewById(R.id.rpmB6),
            findViewById(R.id.rpmB7),
            findViewById(R.id.rpmB8),
            findViewById(R.id.rpmB9),
            findViewById(R.id.rpmB10),
            findViewById(R.id.rpmB11)
        )

        btnCameraSection.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            showSettingsSheet()
        }
        btnNavAction.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (currentSection == Section.CAMERA) {
                showDashboardSection()
            } else {
                showCameraSection()
            }
        }

        btnCameraPhoto.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (!isCameraConnected) {
                showCaptureRequiresConnection()
                return@setOnClickListener
            }
            Toast.makeText(this, R.string.camera_photo_captured, Toast.LENGTH_SHORT).show()
        }

        btnCameraRecord.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (!isCameraConnected && !isRecording) {
                showCaptureRequiresConnection()
                return@setOnClickListener
            }
            toggleRecording()
        }
        cameraPlaceholder.setOnClickListener {
            if (!isCameraConnected) {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                Toast.makeText(this, R.string.camera_connecting_toast, Toast.LENGTH_SHORT).show()
                onCameraConnectButtonClicked()
            }
        }
        btnToggleCameraConnection.setOnClickListener { onCameraConnectButtonClicked() }

        btnModePhoto.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            btnModePhoto.setBackgroundResource(R.drawable.bg_mode_segment_active_left)
            btnModePhoto.setTextColor(Color.WHITE)
            btnModeVideo.setBackgroundResource(R.drawable.bg_mode_segment_inactive)
            btnModeVideo.setTextColor(getColor(R.color.neo_ink))
        }

        btnModeVideo.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            btnModePhoto.setBackgroundResource(R.drawable.bg_mode_segment_inactive)
            btnModePhoto.setTextColor(getColor(R.color.neo_ink))
            btnModeVideo.setBackgroundResource(R.drawable.bg_mode_segment_active_right)
            btnModeVideo.setTextColor(Color.WHITE)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentSection == Section.CAMERA) {
                    showDashboardSection()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        telemetryCard.setOnClickListener {
            if (dashboardViewModel.uiState.value.connectionPhase == ConnectionPhase.DISCONNECTED) {
                showBikePickerSheet()
            }
        }

        btnCameraConfig.setOnClickListener { showCameraSettingsSheet() }

        speedometerView.onUnitClickListener = {
            dashboardViewModel.toggleSpeedUnit()
        }
        findViewById<View>(R.id.cardAvgSpeed)?.setOnClickListener {
            dashboardViewModel.toggleSpeedUnit()
        }
        // MAX SPEED: tap to flip between this session's max and the bike's all-time record
        findViewById<View>(R.id.cardMaxSpeed)?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            dashboardViewModel.toggleMaxSpeedMode()
        }

        observeDashboardState()
        showInitialState()
        updateHeaderLocation()
        startControllers()
        attachToCameraSession()
    }

    /** Re-attaches to a camera session that outlived a previous activity (e.g. recording with the app closed). */
    private fun attachToCameraSession() {
        CameraSession.listener = this
        if (CameraSession.isConnected) {
            CameraSession.lastHost?.let { onCameraConnected(it, CameraSession.lastPort) }
            CameraSession.lastRtspUrl?.let { onRtspAvailable(it) }
        }
        if (CameraSession.userWantsRecording) showRecordingUi()
    }

    /**
     * Called when the user taps Connect / Disconnect in the camera section.
     * Checks Wi-Fi type before connecting; warns if on common Wi-Fi.
     */
    private fun onCameraConnectButtonClicked() {
        if (isCameraConnected) {
            // Disconnect (explicit user action, so it also ends a recording)
            stopRecording()
            cameraController.stop()
            isCameraConnected = false
            updateCameraConnectButton(false)
            cameraPlaceholder.visibility = View.VISIBLE
            cameraStreamView.visibility = View.GONE
            cameraVideoView.visibility = View.GONE
            cameraStatusMessage = getString(R.string.main_camera_waiting)
            if (currentSection == Section.CAMERA) tvConnectionState.text = cameraStatusMessage
            dashboardViewModel.showCameraDisconnected(
                status = getString(R.string.main_camera_waiting),
                host = getString(R.string.main_camera_no_host),
                stream = getString(R.string.main_camera_hidden),
                hint = ""
            )
            return
        }

        // Connecting — check Wi-Fi type
        val savedSsid = uiPreferences.getString(KEY_CAM_SAVED_SSID, null)
        val smartFilter = uiPreferences.getBoolean(KEY_CAM_SMART_FILTER, true)
        val wifiStatus = CameraWifiDetector.getWifiStatus(this, savedSsid)

        when (wifiStatus.type) {
            CameraWifiDetector.NetworkType.NO_WIFI -> {
                Toast.makeText(this, getString(R.string.camera_no_wifi_toast), Toast.LENGTH_LONG).show()
            }
            CameraWifiDetector.NetworkType.COMMON_WIFI -> {
                if (smartFilter) {
                    // Show warning dialog instead of silently connecting
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.camera_wifi_connected_warning_dialog_title))
                        .setMessage(getString(R.string.camera_wifi_connected_warning_dialog_msg, wifiStatus.ssid ?: "Wi-Fi"))
                        .setPositiveButton(getString(R.string.camera_connect_anyway)) { _, _ ->
                            startCameraConnection()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                } else {
                    startCameraConnection()
                }
            }
            CameraWifiDetector.NetworkType.CAMERA_WIFI,
            CameraWifiDetector.NetworkType.UNKNOWN_WIFI -> {
                startCameraConnection()
            }
        }
    }

    private fun startCameraConnection() {
        updateCameraConnectButton(connecting = true)
        cameraController.start()
    }

    private fun updateCameraConnectButton(connected: Boolean = false, connecting: Boolean = false) {
        when {
            connecting -> {
                btnToggleCameraConnection.text = getString(R.string.camera_btn_connecting)
                btnToggleCameraConnection.isEnabled = false
                btnToggleCameraConnection.background = getDrawable(R.drawable.bg_openvoge_btn_connect)
                setCameraCaptureAvailable(false)
            }
            connected -> {
                btnToggleCameraConnection.text = getString(R.string.camera_btn_disconnect)
                btnToggleCameraConnection.isEnabled = true
                btnToggleCameraConnection.background = getDrawable(R.drawable.bg_openvoge_btn_disconnect)
                setCameraCaptureAvailable(true)
                btnCameraRecord.setImageResource(if (isRecording) R.drawable.ic_stop_neo else R.drawable.ic_record_btn_neo)
                tvCameraLive.visibility = View.VISIBLE
            }
            else -> {
                btnToggleCameraConnection.text = getString(R.string.camera_btn_connect)
                btnToggleCameraConnection.isEnabled = true
                btnToggleCameraConnection.background = getDrawable(R.drawable.bg_openvoge_btn_connect)
                setCameraCaptureAvailable(false)
                btnCameraRecord.setImageResource(if (isRecording) R.drawable.ic_stop_neo else R.drawable.ic_record_btn_neo)
                tvCameraLive.visibility = View.GONE
            }
        }
    }

    /**
     * Material pads the sheet for the navigation bar and leaves that strip transparent.
     * Our sheet content pads itself, so drop Material's padding to let the background reach the screen edge.
     */
    private fun dropSheetNavBarPadding(sheet: View) {
        ViewCompat.setOnApplyWindowInsetsListener(sheet) { v, insets ->
            v.updatePadding(bottom = 0)
            insets
        }
        sheet.updatePadding(bottom = 0)
        ViewCompat.requestApplyInsets(sheet)
    }

    /** Photo and record only work once the motorcycle camera is connected; dim them until then. */
    private fun setCameraCaptureAvailable(available: Boolean) {
        val alpha = if (available) 1f else 0.4f
        btnCameraPhoto.alpha = alpha
        btnCameraRecord.alpha = alpha
    }

    private fun showCaptureRequiresConnection() {
        Toast.makeText(this, R.string.camera_capture_requires_connection, Toast.LENGTH_SHORT).show()
    }

    /** Refreshes the Wi-Fi banner in the camera section with current network info. */
    private fun refreshCameraWifiBanner() {
        val savedSsid = uiPreferences.getString(KEY_CAM_SAVED_SSID, null)
        val wifiStatus = CameraWifiDetector.getWifiStatus(this, savedSsid)
        val (dotRes, textColor) = when (wifiStatus.type) {
            CameraWifiDetector.NetworkType.CAMERA_WIFI ->
                R.drawable.ic_status_dot_connected to getColor(R.color.accent_green)
            CameraWifiDetector.NetworkType.NO_WIFI ->
                R.drawable.ic_status_dot_waiting to getColor(R.color.muted_text)
            else ->
                R.drawable.ic_status_dot_waiting to getColor(R.color.secondary)
        }
        ivCameraWifiDot.setImageResource(dotRes)
        tvCameraWifiBannerText.text = wifiStatus.description
        tvCameraWifiBannerText.setTextColor(textColor)
    }

    @SuppressLint("InflateParams")
    private fun showCameraSettingsSheet() {
        val dialog = BottomSheetDialog(this)
        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.let { dropSheetNavBarPadding(it) }
        }
        val contentView = layoutInflater.inflate(R.layout.bottom_sheet_camera_settings, null)
        ViewCompat.setOnApplyWindowInsetsListener(contentView) { v, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.updatePadding(bottom = (36 * resources.displayMetrics.density).toInt() + navBars.bottom)
            insets
        }

        // Auto-connect Switch
        val switchAutoConnect = contentView.findViewById<android.widget.FrameLayout>(R.id.switchAutoConnect)
        val switchThumb = contentView.findViewById<View>(R.id.switchAutoConnectThumb)
        var isAutoConnect = uiPreferences.getBoolean(KEY_CAM_AUTO_CONNECT, true)

        fun updateSwitchState(enabled: Boolean) {
            val margin = (4 * resources.displayMetrics.density).toInt()
            if (enabled) {
                switchAutoConnect?.setBackgroundResource(R.drawable.bg_neo_switch_track_on)
                val params = switchThumb?.layoutParams as? android.widget.FrameLayout.LayoutParams
                params?.gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.END
                params?.marginEnd = margin
                params?.marginStart = 0
                switchThumb?.layoutParams = params
            } else {
                switchAutoConnect?.setBackgroundResource(R.drawable.bg_neo_switch_track_off)
                val params = switchThumb?.layoutParams as? android.widget.FrameLayout.LayoutParams
                params?.gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
                params?.marginStart = margin
                params?.marginEnd = 0
                switchThumb?.layoutParams = params
            }
        }
        updateSwitchState(isAutoConnect)

        switchAutoConnect?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            isAutoConnect = !isAutoConnect
            uiPreferences.edit { putBoolean(KEY_CAM_AUTO_CONNECT, isAutoConnect) }
            updateSwitchState(isAutoConnect)
        }

        dialog.setContentView(contentView)
        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        updateHeaderLocation()
        dashboardViewModel.startAutoConnect()
        // Refresh Wi-Fi banner and check auto-connect
        if (currentSection == Section.CAMERA && !isCameraConnected) {
            refreshCameraWifiBanner()
            // Auto-connect if enabled and camera Wi-Fi detected
            val autoConnect = uiPreferences.getBoolean(KEY_CAM_AUTO_CONNECT, true)
            if (autoConnect) {
                val savedSsid = uiPreferences.getString(KEY_CAM_SAVED_SSID, null)
                if (CameraWifiDetector.isCameraWifi(this, savedSsid)) {
                    startCameraConnection()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Resume the live preview that was paused while the app was in the background
        if (videoPausedInBackground) {
            videoPausedInBackground = false
            exoPlayer?.takeIf { isCameraConnected && !mjpegStreamActive }?.let { player ->
                player.prepare()
                player.play()
            }
        }
    }

    override fun onStop() {
        // Swap the launcher icon only once we're off screen: toggling the alias we were launched
        // from can close the task on some launchers if done while visible.
        syncLauncherIconWithAccent()
        // Nobody is watching: stop decoding video to save battery. The camera keeps recording to its SD card.
        exoPlayer?.takeIf { it.playbackState != Player.STATE_IDLE }?.let { player ->
            player.stop()
            videoPausedInBackground = true
        }
        super.onStop()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    override fun onDestroy() {
        connectionDotPulse?.cancel()
        exitCameraFullscreen()
        // Keeps the camera session running if the user is still recording
        CameraSession.onUiDestroyed(this, this)
        releasePlayer()
        super.onDestroy()
    }

    override fun onStatusChanged(message: String) {
        cameraStatusMessage = message
        runOnUiThread {
            tvCameraStatus.text = message
            if (currentSection == Section.CAMERA) {
                tvConnectionState.text = message
            }
        }
    }

    override fun onTelemetryConnected(deviceName: String, deviceAddress: String) {
        telemetryStatusMessage = getString(R.string.main_telemetry_connected)
        if (currentSection == Section.DASHBOARD) {
            runOnUiThread { tvConnectionState.text = telemetryStatusMessage }
        }
        dashboardViewModel.showTelemetryConnected(
            deviceName = deviceName,
            deviceAddress = deviceAddress,
            connected = getString(R.string.main_telemetry_connected),
            deviceFormat = { name, address -> getString(R.string.main_telemetry_device, name, address) },
            hint = getString(R.string.main_telemetry_hint)
        )
    }

    override fun onTelemetryConnecting(deviceName: String, deviceAddress: String, isActiveConnection: Boolean) {
        telemetryStatusMessage = getString(R.string.main_telemetry_connecting)
        if (currentSection == Section.DASHBOARD) {
            runOnUiThread { tvConnectionState.text = telemetryStatusMessage }
        }
        dashboardViewModel.showTelemetryConnecting(
            deviceName = deviceName,
            deviceAddress = deviceAddress,
            isActiveConnection = isActiveConnection,
            connecting = getString(R.string.main_telemetry_connecting),
            deviceFormat = { name, address -> getString(R.string.main_telemetry_device, name, address) },
            hint = getString(R.string.main_telemetry_hint)
        )
    }

    override fun onTelemetryData(snapshot: TelemetrySnapshot) {
        dashboardViewModel.showTelemetryData(
            snapshot = snapshot,
            live = getString(R.string.main_telemetry_live),
            hint = getString(R.string.main_telemetry_hint),
            neutralGear = getString(R.string.main_telemetry_gear_neutral)
        )
    }

    override fun onTelemetryDisconnected() {
        telemetryStatusMessage = getString(R.string.main_telemetry_waiting)
        if (currentSection == Section.DASHBOARD) {
            runOnUiThread { tvConnectionState.text = telemetryStatusMessage }
        }
        dashboardViewModel.showTelemetryDisconnected(
            waiting = getString(R.string.main_telemetry_waiting),
            noDevice = getString(R.string.main_telemetry_no_device),
            hint = getString(R.string.main_telemetry_hint_disconnected)
        )
    }

    override fun onCameraConnected(host: String, port: Int) {
        mjpegStreamActive = false
        isCameraConnected = true
        cameraStatusMessage = getString(R.string.main_camera_connected)
        runOnUiThread {
            if (currentSection == Section.CAMERA) tvConnectionState.text = cameraStatusMessage
            updateCameraConnectButton(connected = true)
            cameraPlaceholder.visibility = View.GONE
            cameraVideoView.visibility = View.VISIBLE
            tvCameraLive.visibility = View.VISIBLE
        }
        dashboardViewModel.showCameraConnected(
            status = getString(R.string.main_camera_connected),
            host = getString(R.string.main_camera_host_port, host, port),
            stream = getString(R.string.main_camera_streaming),
            hint = ""
        )
    }

    /**
     * Renders decoded MJPEG frames directly into the ImageView.
     * Called on the main thread (posted by CameraController).
     * Switches away from the PlayerView (RTSP) if it was previously active.
     */
    override fun onMjpegFrame(bitmap: Bitmap) {
        // Switch views if needed — only on first frame to avoid redundant layout passes
        if (!mjpegStreamActive) {
            mjpegStreamActive = true
            cameraStatusMessage = getString(R.string.main_camera_live)
            if (currentSection == Section.CAMERA) {
                runOnUiThread { tvConnectionState.text = cameraStatusMessage }
            }
            if (cameraVideoView.visibility == View.VISIBLE) {
                cameraVideoView.visibility = View.GONE
                stopPlayer()
            }
            cameraPlaceholder.visibility = View.GONE
            cameraStreamView.visibility = View.VISIBLE
            btnCameraFullscreen.visibility = View.VISIBLE
            mjpegFpsWindowStartMs = SystemClock.elapsedRealtime()
            mjpegFpsFrameCount = 0
            dashboardViewModel.showCameraPacket(
                status = getString(R.string.main_camera_live),
                host = getString(R.string.main_camera_streaming),
                stream = "",
                hint = ""
            )
        }
        cameraStreamView.setImageBitmap(bitmap)
        fullscreenImageView?.setImageBitmap(bitmap)
        if (bitmap.height > 0) {
            setViewportAspect(bitmap.width.toFloat(), bitmap.height.toFloat())
            tvResolutionVal.text = bitmap.height.toString()
            tvResolutionUnit.visibility = View.VISIBLE
        }
        mjpegFpsFrameCount++
        val elapsedMs = SystemClock.elapsedRealtime() - mjpegFpsWindowStartMs
        if (elapsedMs >= FPS_WINDOW_MS) {
            showMeasuredFps(mjpegFpsFrameCount * 1000f / elapsedMs)
            mjpegFpsWindowStartMs = SystemClock.elapsedRealtime()
            mjpegFpsFrameCount = 0
        }
    }

    @UnstableApi
    override fun onRtspAvailable(url: String) {
        // Ensure we are showing the video player, not the MJPEG ImageView
        if (cameraStreamView.visibility == View.VISIBLE) {
            cameraStreamView.visibility = View.GONE
            cameraStreamView.setImageBitmap(null)
        }

        cameraStatusMessage = getString(R.string.main_camera_streaming)
        if (currentSection == Section.CAMERA) {
            runOnUiThread { tvConnectionState.text = cameraStatusMessage }
        }
        dashboardViewModel.showCameraPacket(
            status = getString(R.string.main_camera_live),
            host = getString(R.string.main_camera_streaming),
            stream = "",
            hint = ""
        )

        // A new player is about to replace the current one; fullscreen holds a reference to the old one
        exitCameraFullscreen()

        currentRtspUrl = url
        rtspRetryCount = 0
        rtspForceTcp = false // MS8336 / AIT dashcams only support UDP transport (RTP/AVP)

        if (cameraVideoView.visibility == View.VISIBLE) {
            // Already showing — player may be in an error state. Release and rebuild.
            cameraVideoView.visibility = View.GONE
            releasePlayer()
        }

        cameraPlaceholder.visibility = View.GONE
        cameraVideoView.visibility = View.VISIBLE

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                1_500, // minBufferMs
                5_000, // maxBufferMs
                500,   // bufferForPlaybackMs
                1_000  // bufferForPlaybackAfterRebufferMs
            )
            .build()

        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val player = ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .build()
            .also { p ->
                exoPlayer = p
                cameraVideoView.player = p

                // Measure the real stream frame rate from frames about to be rendered
                var fpsWindowStartUs = -1L
                var fpsFrameCount = 0
                p.setVideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
                    if (fpsWindowStartUs < 0 || presentationTimeUs < fpsWindowStartUs) {
                        fpsWindowStartUs = presentationTimeUs
                        fpsFrameCount = 0
                    } else {
                        fpsFrameCount++
                        val windowUs = presentationTimeUs - fpsWindowStartUs
                        if (windowUs >= FPS_WINDOW_MS * 1000) {
                            val fps = fpsFrameCount * 1_000_000f / windowUs
                            runOnUiThread { if (isCameraConnected) showMeasuredFps(fps) }
                            fpsWindowStartUs = presentationTimeUs
                            fpsFrameCount = 0
                        }
                    }
                }

                p.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e("OpenVOGEVideo", "Error de reproducción RTSP: ${error.message} (retries=$rtspRetryCount)", error)
                        runOnUiThread {
                            tvCameraStatus.text =
                                getString(R.string.camera_playback_error, error.errorCodeName, error.errorCode)
                        }

                        cameraVideoView.postDelayed({
                            if (cameraVideoView.visibility == View.VISIBLE && !isDestroyed) {
                                val activePlayer = exoPlayer ?: return@postDelayed
                                rtspRetryCount++
                                if (rtspRetryCount <= 3) {
                                    Log.d("OpenVOGEVideo", "Reintentando reproducción RTSP ($rtspRetryCount/3)...")
                                    val source = buildRtspMediaSource(currentRtspUrl ?: url, forceTcp = false)
                                    activePlayer.setMediaSource(source)
                                    activePlayer.prepare()
                                    activePlayer.play()
                                } else {
                                    Log.w("OpenVOGEVideo", "Reintentos RTSP agotados, reiniciando conexión con cámara...")
                                    cameraController.restart()
                                }
                            }
                        }, 2_500L)
                    }

                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        Log.d("OpenVOGEVideo", "Resolución detectada: ${videoSize.width}x${videoSize.height}")
                        if (videoSize.height > 0) {
                            runOnUiThread {
                                tvResolutionVal.text = videoSize.height.toString()
                                tvResolutionUnit.visibility = View.VISIBLE
                                setViewportAspect(videoSize.width * videoSize.pixelWidthHeightRatio, videoSize.height.toFloat())
                            }
                        }
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        val stateStr = when (state) {
                            Player.STATE_BUFFERING -> "BUFFERING"
                            Player.STATE_READY     -> {
                                cameraStatusMessage = getString(R.string.main_camera_streaming)
                                runOnUiThread {
                                    btnCameraFullscreen.visibility = View.VISIBLE
                                    if (currentSection == Section.CAMERA) {
                                        tvConnectionState.text = cameraStatusMessage
                                    }
                                }
                                "READY"
                            }
                            Player.STATE_ENDED     -> "ENDED"
                            Player.STATE_IDLE      -> "IDLE"
                            else                   -> "UNKNOWN"
                        }
                        Log.d("OpenVOGEVideo", "Estado del reproductor: $stateStr")
                    }
                })
            }

        val mediaSource = buildRtspMediaSource(url, rtspForceTcp)
        player.setMediaSource(mediaSource)
        player.playWhenReady = true
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            player.prepare()
        } else {
            // Reconnected with the screen off: don't decode video until someone is looking (onStart resumes it)
            videoPausedInBackground = true
        }
    }

    private fun buildRtspMediaSource(url: String, forceTcp: Boolean): androidx.media3.exoplayer.source.MediaSource {
        val mediaItem = MediaItem.fromUri(url)
        return RtspMediaSource.Factory()
            .setForceUseRtpTcp(forceTcp)
            .setTimeoutMs(12_000)
            .setUserAgent("Lavf/58.29.100") // FFmpeg User-Agent for maximum dashcam compat
            .setDebugLoggingEnabled(true)
            .createMediaSource(mediaItem)
    }

    override fun onCameraDisconnected() {
        mjpegStreamActive = false
        isCameraConnected = false
        runOnUiThread {
            // Not a user stop: keep REC on; CameraSession resumes recording when the link returns
            cameraStreamView.visibility = View.GONE
            cameraStreamView.setImageBitmap(null)
            cameraVideoView.visibility = View.GONE
            cameraPlaceholder.visibility = View.VISIBLE
            tvCameraLive.visibility = View.GONE
            updateCameraConnectButton(connected = false)
            exitCameraFullscreen()
            stopPlayer()
            clearCameraSpecs()
        }

        cameraStatusMessage = getString(R.string.main_camera_waiting)
        if (currentSection == Section.CAMERA) {
            runOnUiThread { tvConnectionState.text = cameraStatusMessage }
        }

        dashboardViewModel.showCameraDisconnected(
            status = getString(R.string.main_camera_waiting),
            host = getString(R.string.main_camera_no_host),
            stream = getString(R.string.main_camera_hidden),
            hint = ""
        )
    }

    override fun onRecordingStateChanged(isRecording: Boolean) {
        // UI sync only — commands are owned by CameraSession
        runOnUiThread {
            if (isRecording) showRecordingUi() else hideRecordingUi()
        }
    }

    /** No camera, no numbers: resolution/FPS only show values reported by a live stream. */
    private fun clearCameraSpecs() {
        tvResolutionVal.text = "--"
        tvResolutionUnit.visibility = View.GONE
        tvFrameRateVal.text = "--"
        tvFrameRateUnit.visibility = View.GONE
        btnCameraFullscreen.visibility = View.GONE
        setViewportAspect(PLACEHOLDER_ASPECT)
    }

    private fun showMeasuredFps(fps: Float) {
        if (fps <= 0f) return
        tvFrameRateVal.text = Math.round(fps).toString()
        tvFrameRateUnit.visibility = View.VISIBLE
    }

    /** Resizes the camera card to the stream's aspect ratio (e.g. 16:9). */
    private fun setViewportAspect(width: Float, height: Float) {
        if (width <= 0f || height <= 0f) return
        setViewportAspect(String.format(Locale.US, "%.4f:1", width / height))
    }

    private fun setViewportAspect(ratio: String) {
        val params = cameraViewport.layoutParams as ConstraintLayout.LayoutParams
        if (params.dimensionRatio == ratio) return
        params.dimensionRatio = ratio
        cameraViewport.layoutParams = params
    }

    /** Shows the live stream in a landscape, edge-to-edge dialog without system bars. */
    private fun enterCameraFullscreen() {
        if (fullscreenDialog != null || !isCameraConnected) return
        val density = resources.displayMetrics.density
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val player = exoPlayer
        if (!mjpegStreamActive && player != null) {
            val playerView = PlayerView(this).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            root.addView(playerView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            PlayerView.switchTargetView(player, cameraVideoView, playerView)
            fullscreenPlayerView = playerView
        } else {
            val imageView = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(cameraStreamView.drawable)
            }
            root.addView(imageView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            fullscreenImageView = imageView
        }
        val exitButton = ImageButton(this).apply {
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_camera_speed_badge)
            setImageResource(R.drawable.ic_fullscreen_exit_neo)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = (9 * density).toInt()
            setPadding(pad, pad, pad, pad)
            contentDescription = getString(R.string.camera_fullscreen_exit)
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                exitCameraFullscreen()
            }
        }
        val size = (40 * density).toInt()
        root.addView(exitButton, FrameLayout.LayoutParams(size, size, android.view.Gravity.TOP or android.view.Gravity.END).apply {
            val margin = (20 * density).toInt()
            setMargins(margin, margin, margin, margin)
        })

        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            setContentView(root)
            setOnDismissListener { onCameraFullscreenDismissed() }
        }
        fullscreenDialog = dialog
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        dialog.show()
        dialog.window?.let { win ->
            WindowCompat.setDecorFitsSystemWindows(win, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                win.attributes = win.attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            WindowCompat.getInsetsController(win, win.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    private fun exitCameraFullscreen() {
        fullscreenDialog?.dismiss()
    }

    private fun onCameraFullscreenDismissed() {
        val playerView = fullscreenPlayerView
        val player = exoPlayer
        if (playerView != null && player != null) {
            PlayerView.switchTargetView(player, playerView, cameraVideoView)
        }
        fullscreenPlayerView = null
        fullscreenImageView = null
        fullscreenDialog = null
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    private fun stopPlayer() {
        exoPlayer?.stop()
    }

    private fun releasePlayer() {
        exoPlayer?.release()
        exoPlayer = null
    }

    /** Banner dot: still when disconnected, blinks while a connection is in progress, solid accent once connected. */
    private fun setConnectionDotState(phase: ConnectionPhase) {
        if (phase == ConnectionPhase.CONNECTING) {
            viewConnectionDot.backgroundTintList = null
            if (connectionDotPulse?.isRunning == true) return
            connectionDotPulse = ObjectAnimator.ofPropertyValuesHolder(
                viewConnectionDot,
                PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.2f),
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 0.8f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 0.8f)
            ).apply {
                duration = 550
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
            return
        }
        connectionDotPulse?.cancel()
        connectionDotPulse = null
        viewConnectionDot.alpha = 1f
        viewConnectionDot.scaleX = 1f
        viewConnectionDot.scaleY = 1f
        viewConnectionDot.backgroundTintList = if (phase == ConnectionPhase.CONNECTED) {
            android.content.res.ColorStateList.valueOf(MaterialColors.getColor(viewConnectionDot, R.attr.neoAccent))
        } else {
            null
        }
    }

    private fun showInitialState() {
        showLocationFallback()
        telemetryStatusMessage = getString(R.string.main_connection_disconnected)
        cameraStatusMessage = getString(R.string.main_camera_waiting)
        tvConnectionState.text = telemetryStatusMessage
        tvConnectionDetail.text = getString(R.string.main_connection_no_device)
        btnToggleConnection.text = getString(R.string.main_connection_btn_connect)
        setConnectionDotState(ConnectionPhase.DISCONNECTED)
        dashboardViewModel.showInitialState(
            waiting = getString(R.string.main_telemetry_waiting),
            noDevice = getString(R.string.main_telemetry_no_device),
            hint = getString(R.string.main_telemetry_hint_disconnected),
            cameraWaiting = getString(R.string.main_camera_waiting),
            cameraNoHost = getString(R.string.main_camera_no_host),
            cameraHidden = getString(R.string.main_camera_hidden),
            cameraHint = ""
        )

        telemetryCard.visibility = View.VISIBLE
        cameraCard.visibility = View.VISIBLE
        cameraPlaceholder.visibility = View.VISIBLE
        cameraStreamView.visibility = View.GONE
        cameraVideoView.visibility = View.GONE
        updateCameraConnectButton(connected = false)
        showDashboardSection()
    }

    private fun showDashboardSection() {
        currentSection = Section.DASHBOARD
        dashboardSection.visibility = View.VISIBLE
        cameraSection.visibility = View.GONE
        btnCameraSection.setImageResource(R.drawable.ic_asterisk_neo)
        btnCameraSection.contentDescription = getString(R.string.settings_title)
        ivNavActionIcon.setImageResource(R.drawable.ic_camera_neo)
        tvNavActionText.setText(R.string.nav_cam)
        btnNavAction.contentDescription = getString(R.string.nav_to_camera)
        updateTopBarTitle()
        tvConnectionState.text = telemetryStatusMessage.ifEmpty { getString(R.string.main_connection_disconnected) }
    }

    private fun showCameraSection() {
        currentSection = Section.CAMERA
        dashboardSection.visibility = View.GONE
        cameraSection.visibility = View.VISIBLE
        btnCameraSection.setImageResource(R.drawable.ic_asterisk_neo)
        btnCameraSection.contentDescription = getString(R.string.settings_title)
        ivNavActionIcon.setImageResource(R.drawable.ic_dashboard_bars_neo)
        tvNavActionText.setText(R.string.nav_dash)
        btnNavAction.contentDescription = getString(R.string.nav_to_dashboard)
        updateTopBarTitle()
        tvConnectionState.text = cameraStatusMessage.ifEmpty { getString(R.string.main_camera_waiting) }

        // REC badge only shown when actively recording
        badgeCameraRec.visibility = if (isRecording) View.VISIBLE else View.GONE
        btnCameraRecord.setImageResource(if (isRecording) R.drawable.ic_stop_neo else R.drawable.ic_record_btn_neo)

        // Show camera stream when connected, placeholder when disconnected
        if (isCameraConnected) {
            cameraPlaceholder.visibility = View.GONE
            if (mjpegStreamActive) {
                cameraStreamView.visibility = View.VISIBLE
                cameraVideoView.visibility = View.GONE
            } else {
                cameraVideoView.visibility = View.VISIBLE
                cameraStreamView.visibility = View.GONE
            }
            tvCameraLive.visibility = View.VISIBLE
        } else {
            cameraPlaceholder.visibility = View.VISIBLE
            cameraStreamView.visibility = View.GONE
            cameraVideoView.visibility = View.GONE
            tvCameraLive.visibility = View.GONE
            refreshCameraWifiBanner()
            val autoConnect = uiPreferences.getBoolean(KEY_CAM_AUTO_CONNECT, true)
            if (autoConnect) {
                val savedSsid = uiPreferences.getString(KEY_CAM_SAVED_SSID, null)
                if (CameraWifiDetector.isCameraWifi(this, savedSsid)) {
                    startCameraConnection()
                }
            }
        }
    }

    private fun toggleRecording() {
        if (isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    /** User tapped Record. */
    private fun startRecording() {
        if (isRecording || !isCameraConnected) return
        requestNotificationPermissionIfNeeded()
        CameraSession.startRecordingByUser(this)
        showRecordingUi()
    }

    /** User tapped Stop (or the camera link was dropped by the user). */
    private fun stopRecording() {
        if (!isRecording && !CameraSession.userWantsRecording) return
        CameraSession.stopRecordingByUser(this)
        hideRecordingUi()
    }

    private fun showRecordingUi() {
        if (isRecording) return
        isRecording = true
        badgeCameraRec.visibility = View.VISIBLE
        btnCameraRecord.setImageResource(R.drawable.ic_stop_neo)
        btnCameraRecord.contentDescription = getString(R.string.camera_btn_stop)
        val blink = AlphaAnimation(1.0f, 0.2f).apply {
            duration = 600
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
        dotCameraRec.startAnimation(blink)
        startRecTimer()
    }

    private fun hideRecordingUi() {
        if (!isRecording) return
        isRecording = false
        badgeCameraRec.visibility = View.GONE
        dotCameraRec.clearAnimation()
        stopRecTimer()
        recSeconds = 0
        btnCameraRecord.setImageResource(R.drawable.ic_record_btn_neo)
        btnCameraRecord.contentDescription = getString(R.string.camera_btn_record)
    }

    private fun startRecTimer() {
        recTimerJob?.cancel()
        recTimerJob = lifecycleScope.launch {
            while (isActive) {
                // Elapsed from the real start so the counter is right after the app was closed or asleep
                val startedAt = CameraSession.recordingStartedAtMillis
                if (startedAt > 0) recSeconds = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
                val mins = recSeconds / 60
                val secs = recSeconds % 60
                tvCameraRec.text = String.format(Locale.US, "REC %02d:%02d", mins, secs)
                delay(1000L)
            }
        }
    }

    private fun stopRecTimer() {
        recTimerJob?.cancel()
        recTimerJob = null
    }

    /** Tiled-dot neo background shared by the bottom sheets. */
    private fun applyDottedSheetBackground(contentView: View) {
        val density = resources.displayMetrics.density
        val dotSpacing = (24 * density).toInt().coerceAtLeast(16)
        val dotBitmap = Bitmap.createBitmap(dotSpacing, dotSpacing, Bitmap.Config.ARGB_8888)
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = getColor(R.color.neo_dot) }
        Canvas(dotBitmap).drawCircle(dotSpacing / 2f, dotSpacing / 2f, 1.2f * density, dotPaint)
        val tiledDots = BitmapDrawable(resources, dotBitmap).apply {
            setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
        ContextCompat.getDrawable(this, R.drawable.bg_openvoge_bottom_sheet)?.let { base ->
            contentView.background = DottedSheetDrawable(base, tiledDots, 28 * density)
        }
    }

    private fun setNeoSwitch(track: View, thumb: View, on: Boolean) {
        track.setBackgroundResource(if (on) R.drawable.bg_neo_switch_track_on else R.drawable.bg_neo_switch_track_off)
        val margin = (4 * resources.displayMetrics.density).toInt()
        val params = thumb.layoutParams as FrameLayout.LayoutParams
        params.gravity = android.view.Gravity.CENTER_VERTICAL or (if (on) android.view.Gravity.END else android.view.Gravity.START)
        params.marginStart = if (on) 0 else margin
        params.marginEnd = if (on) margin else 0
        thumb.layoutParams = params
    }

    /**
     * "Connect bike" sheet: saved history (one bike may be AUTO), a live nearby scan and a
     * remember switch. Picking a bike closes the sheet and starts the connection right away.
     */
    @SuppressLint("InflateParams")
    private fun showBikePickerSheet() {
        if (!hasBluetoothRuntimePermissions()) {
            requestBluetoothPermissionsIfNeeded()
            return
        }
        val dialog = BottomSheetDialog(this, R.style.Theme_OpenVoge_BottomSheetDialog)
        val contentView = layoutInflater.inflate(R.layout.bottom_sheet_bike_picker, null)
        applyDottedSheetBackground(contentView)
        val registry = dashboardViewModel.bikeRegistry
        val savedList = contentView.findViewById<LinearLayout>(R.id.layoutSavedBikes)
        val savedEmpty = contentView.findViewById<View>(R.id.tvSavedEmpty)
        val nearbyList = contentView.findViewById<LinearLayout>(R.id.layoutNearbyBikes)
        val nearbyEmpty = contentView.findViewById<View>(R.id.tvNearbyEmpty)
        val rememberTrack = contentView.findViewById<View>(R.id.switchRememberBike)
        val rememberThumb = contentView.findViewById<View>(R.id.switchRememberBikeThumb)
        val scanDot = contentView.findViewById<View>(R.id.viewScanDot)
        var remember = false

        contentView.findViewById<View>(R.id.btnBikePickerClose).setOnClickListener { dialog.dismiss() }
        rememberTrack.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            remember = !remember
            setNeoSwitch(rememberTrack, rememberThumb, remember)
        }

        fun pick(address: String, name: String, isAuto: Boolean) {
            dialog.dismiss()
            dashboardViewModel.connectBike(address, name, rememberForAutoConnect = remember || isAuto)
        }

        fun divider() = View(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (3 * resources.displayMetrics.density).toInt())
        }

        fun styleTag(tag: TextView, text: String, active: Boolean) {
            tag.visibility = View.VISIBLE
            tag.text = text
            tag.setBackgroundResource(if (active) R.drawable.bg_bike_tag_active else R.drawable.bg_bike_tag_inactive)
            tag.setTextColor(
                if (active) MaterialColors.getColor(tag, R.attr.neoOnAccent) else getColor(R.color.neo_ink_muted)
            )
        }

        lateinit var renderSaved: () -> Unit
        renderSaved = {
            savedList.removeAllViews()
            val bikes = registry.bikes()
            val autoAddress = registry.autoConnectAddress
            savedEmpty.visibility = if (bikes.isEmpty()) View.VISIBLE else View.GONE
            bikes.forEachIndexed { index, bike ->
                if (index > 0) savedList.addView(divider())
                val row = layoutInflater.inflate(R.layout.item_bike_row, savedList, false)
                val isAuto = bike.address.equals(autoAddress, ignoreCase = true)
                row.findViewById<TextView>(R.id.tvBikeName).text = bike.name
                row.findViewById<TextView>(R.id.tvBikeDetail).text = getString(
                    R.string.bike_saved_detail,
                    bike.address,
                    android.text.format.DateUtils.getRelativeTimeSpanString(bike.lastConnectedMillis)
                )
                // AUTO chip doubles as the toggle: only one bike can be the auto-connect bike
                row.findViewById<TextView>(R.id.tvBikeTag).apply {
                    styleTag(this, getString(R.string.bike_tag_auto), isAuto)
                    setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        registry.autoConnectAddress = if (isAuto) null else bike.address
                        Toast.makeText(
                            this@MainActivity,
                            if (isAuto) getString(R.string.bike_auto_cleared) else getString(R.string.bike_auto_set, bike.name),
                            Toast.LENGTH_SHORT
                        ).show()
                        renderSaved()
                    }
                }
                row.findViewById<View>(R.id.btnBikeForget).apply {
                    visibility = View.VISIBLE
                    setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        registry.forget(bike.address)
                        renderSaved()
                    }
                }
                row.setOnClickListener { pick(bike.address, bike.name, isAuto) }
                savedList.addView(row)
            }
        }
        renderSaved()

        // Nearby: rows appear/refresh as advertisements arrive; motorcycles sorted first
        val nearby = linkedMapOf<String, DiscoveredBike>()
        val nearbyRows = mutableMapOf<String, View>()
        fun renderNearby() {
            nearbyEmpty.visibility = if (nearby.isEmpty()) View.VISIBLE else View.GONE
            nearbyList.removeAllViews()
            val sorted = nearby.values.sortedWith(compareByDescending<DiscoveredBike> { it.isMotorcycle }.thenByDescending { it.rssi })
            sorted.forEachIndexed { index, device ->
                if (index > 0) nearbyList.addView(divider())
                val row = nearbyRows.getOrPut(device.address) {
                    layoutInflater.inflate(R.layout.item_bike_row, nearbyList, false)
                }
                row.findViewById<TextView>(R.id.tvBikeName).text = device.name
                row.findViewById<TextView>(R.id.tvBikeDetail).text = getString(R.string.bike_saved_detail, device.address, "${device.rssi} dBm")
                row.findViewById<ImageView>(R.id.ivBikeIcon).setImageResource(
                    if (device.isMotorcycle) R.drawable.ic_motorcycle_neo else R.drawable.ic_bluetooth_neo
                )
                val tag = row.findViewById<TextView>(R.id.tvBikeTag)
                if (device.isMotorcycle) styleTag(tag, getString(R.string.bike_tag_moto), true) else tag.visibility = View.GONE
                row.findViewById<View>(R.id.layoutSignalBars).visibility = View.VISIBLE
                // -55 dBm or better = 4 bars … -90 dBm = 1 bar
                val bars = ((device.rssi + 100) / 12).coerceIn(1, 4)
                listOf(R.id.signalBar1, R.id.signalBar2, R.id.signalBar3, R.id.signalBar4).forEachIndexed { i, id ->
                    row.findViewById<View>(id).alpha = if (i < bars) 1f else 0.25f
                }
                row.setOnClickListener { pick(device.address, device.name, isAuto = false) }
                (row.parent as? ViewGroup)?.removeView(row)
                nearbyList.addView(row)
            }
        }

        val scanPulse = ObjectAnimator.ofFloat(scanDot, View.ALPHA, 1f, 0.2f).apply {
            duration = 700
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
        dashboardViewModel.startBikeDiscovery { device ->
            val previous = nearby[device.address]
            nearby[device.address] = device
            // Re-render on new devices or a meaningful signal change
            if (previous == null || kotlin.math.abs(previous.rssi - device.rssi) >= 6) renderNearby()
        }
        dialog.setOnDismissListener {
            scanPulse.cancel()
            dashboardViewModel.stopBikeDiscovery()
        }

        ViewCompat.setOnApplyWindowInsetsListener(contentView) { v, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.updatePadding(bottom = (24 * resources.displayMetrics.density).toInt() + navBars.bottom)
            insets
        }
        dialog.setContentView(contentView)
        dialog.behavior.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
                sheet.background = null
                dropSheetNavBarPadding(sheet)
            }
        }
        dialog.window?.let { win ->
            WindowCompat.setDecorFitsSystemWindows(win, false)
            win.navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) win.isNavigationBarContrastEnforced = false
        }
        dialog.show()
    }

    @SuppressLint("InflateParams")
    private fun showSettingsSheet() {
        val dialog = BottomSheetDialog(this, R.style.Theme_OpenVoge_BottomSheetDialog)
        val contentView = layoutInflater.inflate(R.layout.bottom_sheet_settings, null)

        val dotSpacing = (24 * resources.displayMetrics.density).toInt().coerceAtLeast(16)
        val dotRadius = 1.2f * resources.displayMetrics.density
        val dotBitmap = Bitmap.createBitmap(dotSpacing, dotSpacing, Bitmap.Config.ARGB_8888)
        val dotCanvas = Canvas(dotBitmap)
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = getColor(R.color.neo_dot) }
        dotCanvas.drawCircle(dotSpacing / 2f, dotSpacing / 2f, dotRadius, dotPaint)
        val tiledDots = BitmapDrawable(resources, dotBitmap).apply {
            setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
        val sheetBaseDrawable = ContextCompat.getDrawable(this, R.drawable.bg_openvoge_bottom_sheet)
        if (sheetBaseDrawable != null) {
            val cornerRadius = 28 * resources.displayMetrics.density
            contentView.background = DottedSheetDrawable(sheetBaseDrawable, tiledDots, cornerRadius)
        }

        // Close button (X)
        contentView.findViewById<View>(R.id.btnSettingsClose)?.setOnClickListener {
            dialog.dismiss()
        }

        // Theme 3-segment pill (LIGHT / DARK / AUTO)
        val btnThemeLight = contentView.findViewById<TextView>(R.id.btnThemeLight)
        val btnThemeDark = contentView.findViewById<TextView>(R.id.btnThemeDark)
        val btnThemeAuto = contentView.findViewById<TextView>(R.id.btnThemeAuto)

        fun updateThemeSegments(mode: String) {
            val limeColor = MaterialColors.getColor(contentView, R.attr.neoAccent)
            val blackColor = getColor(R.color.neo_ink)

            when (mode) {
                "light" -> {
                    btnThemeLight?.setBackgroundResource(R.drawable.bg_theme_segment_active_left)
                    btnThemeLight?.setTextColor(limeColor)
                    btnThemeDark?.setBackgroundResource(R.drawable.bg_theme_segment_inactive)
                    btnThemeDark?.setTextColor(blackColor)
                    btnThemeAuto?.setBackgroundResource(R.drawable.bg_theme_segment_inactive)
                    btnThemeAuto?.setTextColor(blackColor)
                }
                "dark" -> {
                    btnThemeLight?.setBackgroundResource(R.drawable.bg_theme_segment_inactive)
                    btnThemeLight?.setTextColor(blackColor)
                    btnThemeDark?.setBackgroundResource(R.drawable.bg_theme_segment_active_center)
                    btnThemeDark?.setTextColor(limeColor)
                    btnThemeAuto?.setBackgroundResource(R.drawable.bg_theme_segment_inactive)
                    btnThemeAuto?.setTextColor(blackColor)
                }
                "auto" -> {
                    btnThemeLight?.setBackgroundResource(R.drawable.bg_theme_segment_inactive)
                    btnThemeLight?.setTextColor(blackColor)
                    btnThemeDark?.setBackgroundResource(R.drawable.bg_theme_segment_inactive)
                    btnThemeDark?.setTextColor(blackColor)
                    btnThemeAuto?.setBackgroundResource(R.drawable.bg_theme_segment_active_right)
                    btnThemeAuto?.setTextColor(limeColor)
                }
            }
        }

        val savedThemeMode = uiPreferences.getString("key_theme_mode", "light") ?: "light"
        updateThemeSegments(savedThemeMode)

        btnThemeLight?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            uiPreferences.edit { putString("key_theme_mode", "light").putBoolean(KEY_DARK_THEME, false) }
            updateThemeSegments("light")
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
        btnThemeDark?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            uiPreferences.edit { putString("key_theme_mode", "dark").putBoolean(KEY_DARK_THEME, true) }
            updateThemeSegments("dark")
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }
        btnThemeAuto?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            uiPreferences.edit { putString("key_theme_mode", "auto") }
            updateThemeSegments("auto")
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }

        // Color Swatches
        val ivCheckLime = contentView.findViewById<View>(R.id.ivCheckLime)
        val ivCheckBlue = contentView.findViewById<View>(R.id.ivCheckBlue)
        val ivCheckOrange = contentView.findViewById<View>(R.id.ivCheckOrange)
        val ivCheckGrey = contentView.findViewById<View>(R.id.ivCheckGrey)

        fun showSwatchCheck(swatch: String) {
            ivCheckLime?.visibility = if (swatch == "lime") View.VISIBLE else View.GONE
            ivCheckBlue?.visibility = if (swatch == "blue") View.VISIBLE else View.GONE
            ivCheckOrange?.visibility = if (swatch == "orange") View.VISIBLE else View.GONE
            ivCheckGrey?.visibility = if (swatch == "grey") View.VISIBLE else View.GONE
        }

        // Picking a new accent recreates the activity so every view and drawable picks up the new overlay
        fun selectSwatch(swatch: String) {
            if (swatch == savedAccentColor()) return
            showSwatchCheck(swatch)
            uiPreferences.edit { putString(KEY_ACCENT_COLOR, swatch) }
            dialog.dismiss()
            recreate()
        }

        showSwatchCheck(savedAccentColor())

        contentView.findViewById<View>(R.id.btnSwatchLime)?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            selectSwatch("lime")
        }
        contentView.findViewById<View>(R.id.btnSwatchBlue)?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            selectSwatch("blue")
        }
        contentView.findViewById<View>(R.id.btnSwatchOrange)?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            selectSwatch("orange")
        }
        contentView.findViewById<View>(R.id.btnSwatchGrey)?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            selectSwatch("grey")
        }

        // Version text
        val versionText = contentView.findViewById<TextView>(R.id.tvAppVersion)
        versionText?.text = "OPENVOGE v${resolveAppVersion()}"

        ViewCompat.setOnApplyWindowInsetsListener(contentView) { v, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.updatePadding(
                bottom = (24 * resources.displayMetrics.density).toInt() + navBars.bottom
            )
            insets
        }

        dialog.setContentView(contentView)
        dialog.behavior.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let { sheet ->
                sheet.background = null
                dropSheetNavBarPadding(sheet)
                val behavior = BottomSheetBehavior.from(sheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        dialog.window?.let { win ->
            WindowCompat.setDecorFitsSystemWindows(win, false)
            win.navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                win.isNavigationBarContrastEnforced = false
            }
            WindowCompat.getInsetsController(win, win.decorView).isAppearanceLightNavigationBars = true
        }
        dialog.show()
    }

    /** Enables the launcher alias whose icon matches the Settings accent; disables the others. */
    private fun syncLauncherIconWithAccent() {
        val desired = savedAccentColor()
        val namespace = MainActivity::class.java.name.substringBeforeLast('.')
        val aliases = mapOf("lime" to "Lime", "blue" to "Blue", "orange" to "Orange", "grey" to "Grey")
        val target = aliases[desired] ?: "Lime"
        // Enable the new one first so there is never a moment without a launcher entry
        val ordered = aliases.values.sortedByDescending { it == target }
        ordered.forEach { suffix ->
            val component = android.content.ComponentName(packageName, "$namespace.LauncherAccent$suffix")
            val state = if (suffix == target) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (packageManager.getComponentEnabledSetting(component) != state) {
                packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            }
        }
    }

    private fun savedAccentColor(): String =
        uiPreferences.getString(KEY_ACCENT_COLOR, "lime") ?: "lime"

    private fun accentOverlayFor(swatch: String): Int = when (swatch) {
        "blue" -> R.style.ThemeOverlay_OpenVoge_Accent_Blue
        "orange" -> R.style.ThemeOverlay_OpenVoge_Accent_Orange
        "grey" -> R.style.ThemeOverlay_OpenVoge_Accent_Grey
        else -> R.style.ThemeOverlay_OpenVoge_Accent_Lime
    }

    private fun applySavedThemeMode() {
        val isDarkThemeEnabled = uiPreferences.getBoolean(KEY_DARK_THEME, false)
        AppCompatDelegate.setDefaultNightMode(
            if (isDarkThemeEnabled) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    private fun isDarkThemeEnabled(): Boolean {
        return uiPreferences.getBoolean(KEY_DARK_THEME, false)
    }

    private fun resolveAppVersion(): String {
        return try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            packageInfo.versionName ?: "1.0"
        } catch (_: PackageManager.NameNotFoundException) {
            "1.0"
        }
    }

    private fun startControllers() {
        if (hasBluetoothRuntimePermissions()) {
            dashboardViewModel.startAutoConnect()
        } else {
            requestBluetoothPermissionsIfNeeded()
        }
        // Camera is NOT started automatically — only when:
        // 1. Auto-connect is enabled AND camera Wi-Fi is detected (handled in showCameraSection/onResume)
        // 2. User taps the Connect button in the camera section
    }

    private fun hasBluetoothRuntimePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestBluetoothPermissionsIfNeeded() {
        if (hasBluetoothRuntimePermissions() || isPermissionRequestInFlight) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            isPermissionRequestInFlight = true
            bluetoothPermissionsLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
                )
            )
        }
    }

    private fun showBluetoothPermissionMissingDialog() {
        telemetryStatusMessage = getString(R.string.main_telemetry_permissions)
        if (currentSection == Section.DASHBOARD) {
            tvConnectionState.text = telemetryStatusMessage
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_bluetooth_title)
            .setMessage(R.string.permission_bluetooth_message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermissionsIfNeeded() {
        if (hasLocationPermission() || isLocationPermissionRequestInFlight) return

        isLocationPermissionRequestInFlight = true
        locationPermissionsLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun showLocationFallback() {
        tvStatus.text = "EJIDO"
    }

    private fun updateHeaderLocation() {
        if (!hasLocationPermission()) {
            showLocationFallback()
            requestLocationPermissionsIfNeeded()
            return
        }

        lifecycleScope.launch {
            val loc = resolveLocationName()
            tvStatus.text = if (loc.isBlank() || loc == getString(R.string.main_location_unknown)) "EJIDO" else loc.uppercase()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun resolveLocationName(): String = withContext(Dispatchers.IO) {
        val fallback = "EJIDO"
        val locationManager = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return@withContext fallback
        val bestLocation = locationManager.getProviders(true)
            .asSequence()
            .mapNotNull { provider ->
                try {
                    locationManager.getLastKnownLocation(provider)
                } catch (_: SecurityException) {
                    null
                }
            }
            .sortedWith(compareByDescending<Location> { it.time }.thenBy { it.accuracy })
            .firstOrNull()
            ?: return@withContext fallback

        if (!Geocoder.isPresent()) {
            return@withContext fallback
        }

        val geocoder = Geocoder(this@MainActivity, Locale.getDefault())
        val address = try {
            @Suppress("DEPRECATION")
            geocoder.getFromLocation(bestLocation.latitude, bestLocation.longitude, 1)?.firstOrNull()
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

        address.toDisplayLocationName() ?: fallback
    }

    private fun Address?.toDisplayLocationName(): String? {
        val address = this ?: return null
        return sequenceOf(
            address.locality,
            address.subAdminArea,
            address.adminArea,
            address.countryName
        ).firstOrNull { !it.isNullOrBlank() }
    }

    /** Renders "42 km/h" as a big value with a small trailing unit, matching dash.png. */
    private fun withSmallUnit(value: String): CharSequence {
        val split = value.lastIndexOf(' ')
        if (split <= 0) return value
        return SpannableString(value).apply {
            setSpan(RelativeSizeSpan(0.42f), split, value.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun updateTopBarTitle() {
        tvTopBarTitle.typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        tvTopBarTitle.letterSpacing = 0.18f
        tvTopBarTitle.textSize = 14.5f
        tvTopBarTitle.text = if (currentSection == Section.CAMERA) {
            "BIKE CAM"
        } else {
            currentBikeName ?: "OPENVOGE"
        }
    }

    private fun observeDashboardState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                dashboardViewModel.uiState.collect { state ->
                    renderDashboard(state)
                }
            }
        }
    }

    private fun renderDashboard(state: DashboardUiState) {
        currentBikeName = state.bikeName
        updateTopBarTitle()
        speedometerView.setSpeedUnit(state.speedUnit)
        speedometerView.setSpeed(state.speedValue)
        tvCameraSpeedValue.text = state.speedValue.toString()
        tvCameraSpeedUnit.text = state.speedUnit.badgeText
        if (state.bikeName != null) {
            tvTelemetryStatus.text = state.telemetryStatus
            tvTelemetryDevice.visibility = View.VISIBLE
            tvTelemetryDevice.text = state.telemetryDevice
        } else {
            tvTelemetryStatus.text = getString(R.string.main_telemetry_waiting)
            tvTelemetryDevice.visibility = View.GONE
        }
        tvCommandValue.text = state.commandValue
        tvRpmValue.text = state.rpmValue
        tvAverageSpeedValue.text = withSmallUnit(state.averageSpeedValue)
        tvMaxSpeedValue.text = withSmallUnit(if (state.showAllTimeMax) state.allTimeMaxSpeedValue else state.maxSpeedValue)
        tvMaxSpeedLabel.setText(if (state.showAllTimeMax) R.string.dashboard_max_speed_all_time else R.string.dashboard_max_speed)
        tvFuelValue.text = withSmallUnit(state.fuelValue)
        tvTemperatureValue.text = withSmallUnit(state.temperatureValue)
        tvTripTimeValue.text = state.tripTimeValue
        tvTripKmValue.text = withSmallUnit(state.tripKmValue)
        tvTotalKmValue.text = withSmallUnit(state.totalKmValue)
        gearPodView.gearValue = state.gearValue
        tvVoltageValue.text = withSmallUnit(state.voltageValue)
        tvCameraStatus.text = state.cameraStatus
        tvCameraHost.text = state.cameraHost
        tvCameraStream.text = state.cameraStream
        tvCameraHint.text = state.cameraHint
        telemetryCard.visibility = View.VISIBLE
        cameraCard.visibility = View.VISIBLE

        // 1. Connection Banner state
        when (state.connectionPhase) {
            ConnectionPhase.CONNECTED -> {
                tvConnectionState.text = getString(R.string.main_connection_connected)
                tvConnectionDetail.text = state.telemetryDevice.ifEmpty { state.bikeName.orEmpty() }
                btnToggleConnection.text = getString(R.string.main_connection_btn_disconnect)
            }
            ConnectionPhase.CONNECTING -> {
                tvConnectionState.text = getString(R.string.main_connection_connecting)
                tvConnectionDetail.text = state.pendingBikeName
                    ?.let { getString(R.string.main_telemetry_connecting_to, it) }
                    ?: getString(R.string.main_connection_searching)
                btnToggleConnection.text = getString(R.string.main_connection_btn_stop)
            }
            ConnectionPhase.DISCONNECTED -> {
                tvConnectionState.text = getString(R.string.main_connection_disconnected)
                tvConnectionDetail.text = getString(R.string.main_connection_no_device)
                btnToggleConnection.text = getString(R.string.main_connection_btn_connect)
            }
        }
        setConnectionDotState(state.connectionPhase)

        // 2. Fuel Level Segments (8 blocks)
        val fuelPercent = state.rawFuelPercent
        if (fuelPercent != null) {
            val activeFuelBlocks = ((fuelPercent / 100f) * 8).toInt().coerceIn(0, 8)
            fuelBlocks.forEachIndexed { index, block ->
                block.setBackgroundResource(
                    if (index < activeFuelBlocks) R.drawable.bg_segment_black else R.drawable.bg_segment_white
                )
            }
        } else {
            fuelBlocks.forEach { it.setBackgroundResource(R.drawable.bg_segment_white) }
        }

        // 3. RPM Segments (11 blocks: 9 normal + 2 lime redline matching rmp-mock.png)
        val rpm = state.rawRpm
        if (rpm != null) {
            val activeRpmBlocks = (rpm / 1000).coerceIn(0, 11)
            rpmBlocks.forEachIndexed { index, block ->
                if (index < 9) {
                    block.setBackgroundResource(
                        if (index < activeRpmBlocks) R.drawable.bg_segment_black else R.drawable.bg_segment_white
                    )
                } else {
                    block.setBackgroundResource(
                        if (index < activeRpmBlocks) R.drawable.bg_segment_black else R.drawable.bg_segment_coral
                    )
                }
            }
        } else {
            rpmBlocks.forEachIndexed { index, block ->
                block.setBackgroundResource(
                    if (index < 9) R.drawable.bg_segment_white else R.drawable.bg_segment_coral
                )
            }
        }
    }
}

private class DottedSheetDrawable(
    private val baseDrawable: Drawable,
    private val dotDrawable: Drawable,
    private val cornerRadius: Float
) : Drawable() {
    private val clipPath = Path()
    private val boundsRect = RectF()

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        baseDrawable.bounds = bounds
        dotDrawable.bounds = bounds
        boundsRect.set(bounds)
        clipPath.reset()
        val radii = floatArrayOf(
            cornerRadius, cornerRadius,
            cornerRadius, cornerRadius,
            0f, 0f,
            0f, 0f
        )
        clipPath.addRoundRect(boundsRect, radii, Path.Direction.CW)
    }

    override fun draw(canvas: Canvas) {
        val saveCount = canvas.save()
        canvas.clipPath(clipPath)
        baseDrawable.draw(canvas)
        dotDrawable.draw(canvas)
        canvas.restoreToCount(saveCount)
    }

    override fun setAlpha(alpha: Int) {
        baseDrawable.alpha = alpha
        dotDrawable.alpha = alpha
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        baseDrawable.colorFilter = colorFilter
        dotDrawable.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

