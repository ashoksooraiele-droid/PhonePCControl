package com.ashok.phonepccontrol

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient()
    private var isConnected = false

    private lateinit var tvStatus: TextView
    private lateinit var tabKeyboard: Button
    private lateinit var tabMouse: Button
    private lateinit var tabMic: Button
    private lateinit var tabJoystick: Button

    private lateinit var screenKeyboard: View
    private lateinit var screenMouse: View
    private lateinit var screenMic: View
    private lateinit var screenJoystick: View

    private val activeModifiers = mutableSetOf<String>()
    private val modButtons = mutableMapOf<String, MutableList<Button>>()

    private var leftHoldActive = false
    private var lastRightTapTime = 0L

    private var isMicOn = false
    private var audioRecord: AudioRecord? = null
    private var micThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvConnectionStatus)
        tabKeyboard = findViewById(R.id.tabKeyboard)
        tabMouse = findViewById(R.id.tabMouse)
        tabMic = findViewById(R.id.tabMic)
        tabJoystick = findViewById(R.id.tabJoystick)

        screenKeyboard = findViewById(R.id.screenKeyboard)
        screenMouse = findViewById(R.id.screenMouse)
        screenMic = findViewById(R.id.screenMic)
        screenJoystick = findViewById(R.id.screenJoystick)

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener { showSettingsDialog() }

        tabKeyboard.setOnClickListener { switchTab(0) }
        tabMouse.setOnClickListener { switchTab(1) }
        tabMic.setOnClickListener { switchTab(2) }
        tabJoystick.setOnClickListener { switchTab(3) }

        setupModifiers()
        setupKeyboardScreen()
        setupMouseScreen()
        setupMicScreen()
        setupJoystickScreen()

        connectToPc()
    }

    private fun switchTab(index: Int) {
        val tabs = listOf(tabKeyboard, tabMouse, tabMic, tabJoystick)
        val screens = listOf(screenKeyboard, screenMouse, screenMic, screenJoystick)
        val blue = ContextCompat.getColor(this, R.color.accent_blue)
        val card = ContextCompat.getColor(this, R.color.bg_card)

        for (i in tabs.indices) {
            tabs[i].backgroundTintList = ColorStateList.valueOf(if (i == index) blue else card)
            screens[i].visibility = if (i == index) View.VISIBLE else View.GONE
        }
    }

    private fun setupModifiers() {
        val pairs = listOf(
            "SHIFT" to listOf(findViewById<Button>(R.id.kbModShift), findViewById(R.id.msModShift)),
            "CTRL" to listOf(findViewById<Button>(R.id.kbModCtrl), findViewById(R.id.msModCtrl)),
            "FN" to listOf(findViewById<Button>(R.id.kbModFn), findViewById(R.id.msModFn)),
            "WIN" to listOf(findViewById<Button>(R.id.kbModWin), findViewById(R.id.msModWin))
        )
        for ((key, btnList) in pairs) {
            modButtons[key] = btnList.toMutableList()
            for (btn in btnList) {
                btn.setOnClickListener { toggleModifier(key) }
            }
        }
    }

    private fun toggleModifier(key: String) {
        val nowActive = !activeModifiers.contains(key)
        if (nowActive) activeModifiers.add(key) else activeModifiers.remove(key)
        updateModifierColors()
        sendJson(JSONObject().put("type", "modifier").put("key", key).put("active", nowActive))
    }

    private fun clearAllModifiersLocal() {
        if (activeModifiers.isNotEmpty()) {
            activeModifiers.clear()
            updateModifierColors()
        }
    }

    private fun updateModifierColors() {
        val blue = ContextCompat.getColor(this, R.color.accent_blue)
        val surface = ContextCompat.getColor(this, R.color.bg_surface)
        for ((key, btnList) in modButtons) {
            val active = activeModifiers.contains(key)
            for (b in btnList) {
                b.backgroundTintList = ColorStateList.valueOf(if (active) blue else surface)
            }
        }
    }

    private fun setupKeyboardScreen() {
        val specialMap = mapOf(
            R.id.keyEsc to "ESC",
            R.id.keyTab to "TAB",
            R.id.keyUp to "UP",
            R.id.keyDown to "DOWN",
            R.id.keyLeft to "LEFT",
            R.id.keyRight to "RIGHT"
        )
        for ((id, code) in specialMap) {
            findViewById<Button>(id).setOnClickListener {
                sendKey(code)
            }
        }

        val container = findViewById<LinearLayout>(R.id.qwertyContainer)
        val rows = listOf(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
            listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"),
            listOf("A", "S", "D", "F", "G", "H", "J", "K", "L"),
            listOf("Z", "X", "C", "V", "B", "N", "M", "⌫"),
            listOf("SPACE", "ENTER")
        )

        val keyBg = ContextCompat.getColor(this, R.color.bg_surface)
        val blueBg = ContextCompat.getColor(this, R.color.accent_blue)
        val white = ContextCompat.getColor(this, R.color.text_white)

        for (row in rows) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
            }
            for (label in row) {
                val weight = when (label) {
                    "SPACE" -> 3.5f
                    "ENTER" -> 1.8f
                    "⌫" -> 1.5f
                    else -> 1f
                }
                val btn = Button(this).apply {
                    text = if (label == "SPACE") "Space" else if (label == "ENTER") "Enter" else label
                    isAllCaps = false
                    setTextColor(white)
                    textSize = 13f
                    setPadding(0, 0, 0, 0)
                    backgroundTintList = ColorStateList.valueOf(if (label == "ENTER") blueBg else keyBg)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight).apply {
                        setMargins(3, 3, 3, 3)
                    }
                    setOnClickListener {
                        when (label) {
                            "⌫" -> sendKey("BACKSPACE")
                            "SPACE" -> sendKey("SPACE")
                            "ENTER" -> sendKey("ENTER")
                            else -> sendKey(label)
                        }
                    }
                }
                rowLayout.addView(btn)
            }
            container.addView(rowLayout)
        }
    }

    private fun sendKey(key: String) {
        sendJson(JSONObject().put("type", "key").put("key", key))
        clearAllModifiersLocal()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMouseScreen() {
        val touchpad = findViewById<FrameLayout>(R.id.touchpadView)
        var lastX = 0f
        var lastY = 0f
        var downX = 0f
        var downY = 0f
        var moved = false

        touchpad.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    downX = event.x
                    downY = event.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (abs(event.x - downX) > 8 || abs(event.y - downY) > 8) {
                        moved = true
                    }
                    if (event.pointerCount >= 2) {
                        sendJson(JSONObject().put("type", "mouse_scroll").put("dy", (dy * 0.25f).toInt()))
                    } else {
                        sendJson(JSONObject().put("type", "mouse_move").put("dx", dx * 1.6f).put("dy", dy * 1.6f))
                    }
                    lastX = event.x
                    lastY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved && event.pointerCount == 1) {
                        sendJson(JSONObject().put("type", "mouse_click").put("button", "left").put("action", "click"))
                        clearAllModifiersLocal()
                    }
                }
            }
            true
        }

        val btnLeft = findViewById<Button>(R.id.btnLeftClick)
        val btnRight = findViewById<Button>(R.id.btnRightClick)
        val blue = ContextCompat.getColor(this, R.color.accent_blue)
        val card = ContextCompat.getColor(this, R.color.bg_card)

        btnLeft.setOnClickListener {
            leftHoldActive = !leftHoldActive
            btnLeft.backgroundTintList = ColorStateList.valueOf(if (leftHoldActive) blue else card)
            btnLeft.text = if (leftHoldActive) "Left Holding (Tap to Release)" else "Left Click (Hold)"
            sendJson(
                JSONObject().put("type", "mouse_click")
                    .put("button", "left")
                    .put("action", if (leftHoldActive) "hold_on" else "hold_off")
            )
            if (!leftHoldActive) clearAllModifiersLocal()
        }

        btnRight.setOnClickListener {
            val now = System.currentTimeMillis()
            val isDouble = (now - lastRightTapTime) < 320
            lastRightTapTime = now
            sendJson(
                JSONObject().put("type", "mouse_click")
                    .put("button", "right")
                    .put("action", if (isDouble) "double" else "click")
            )
            clearAllModifiersLocal()
        }
    }

    private fun setupMicScreen() {
        val btnMic = findViewById<Button>(R.id.btnMicToggle)
        val tvHint = findViewById<TextView>(R.id.tvMicHint)
        val blue = ContextCompat.getColor(this, R.color.accent_blue)
        val surface = ContextCompat.getColor(this, R.color.bg_surface)

        btnMic.setOnClickListener {
            if (!isMicOn) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
                    return@setOnClickListener
                }
                startMicStreaming()
                isMicOn = true
                btnMic.text = "MIC ON"
                btnMic.backgroundTintList = ColorStateList.valueOf(blue)
                tvHint.text = "Phone mic is LIVE on your PC"
            } else {
                stopMicStreaming()
                isMicOn = false
                btnMic.text = "MIC OFF"
                btnMic.backgroundTintList = ColorStateList.valueOf(surface)
                tvHint.text = "Tap to turn ON phone microphone for PC"
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startMicStreaming() {
        val sampleRate = 16000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf
        )
        audioRecord?.startRecording()
        micThread = Thread {
            val buffer = ByteArray(1024)
            while (isMicOn && audioRecord != null) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    webSocket?.send(buffer.copyOf(read).toByteString())
                }
            }
        }
        micThread?.start()
    }

    private fun stopMicStreaming() {
        isMicOn = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupJoystickScreen() {
        val gpButtons = mapOf(
            R.id.gpA to "A", R.id.gpB to "B", R.id.gpX to "X", R.id.gpY to "Y",
            R.id.gpLB to "LB", R.id.gpRB to "RB", R.id.gpSTART to "START", R.id.gpBACK to "BACK",
            R.id.gpDpadUp to "DPAD_UP", R.id.gpDpadDown to "DPAD_DOWN",
            R.id.gpDpadLeft to "DPAD_LEFT", R.id.gpDpadRight to "DPAD_RIGHT"
        )
        for ((id, code) in gpButtons) {
            findViewById<Button>(id).setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        sendJson(JSONObject().put("type", "gamepad").put("sub", "button").put("button", code).put("pressed", true))
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        sendJson(JSONObject().put("type", "gamepad").put("sub", "button").put("button", code).put("pressed", false))
                    }
                }
                true
            }
        }

        bindStickPad(findViewById(R.id.leftStickPad), "left")
        bindStickPad(findViewById(R.id.rightStickPad), "right")
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindStickPad(pad: FrameLayout, stickName: String) {
        pad.setOnTouchListener { v, event ->
            val cx = v.width / 2f
            val cy = v.height / 2f
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val nx = ((event.x - cx) / cx).coerceIn(-1f, 1f)
                    val ny = ((event.y - cy) / cy).coerceIn(-1f, 1f)
                    sendJson(JSONObject().put("type", "gamepad").put("sub", "stick").put("stick", stickName).put("x", nx).put("y", ny))
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    sendJson(JSONObject().put("type", "gamepad").put("sub", "stick").put("stick", stickName).put("x", 0.0).put("y", 0.0))
                }
            }
            true
        }
    }

    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val prefs = getSharedPreferences("pc_control_prefs", Context.MODE_PRIVATE)
        val mode = prefs.getString("mode", "WIFI")
        val savedIp = prefs.getString("ip", "192.168.1.5")

        val rbWifi = view.findViewById<RadioButton>(R.id.rbWifi)
        val rbUsb = view.findViewById<RadioButton>(R.id.rbUsb)
        val rbBt = view.findViewById<RadioButton>(R.id.rbBluetooth)
        val etIp = view.findViewById<EditText>(R.id.etPcIp)

        etIp.setText(savedIp)
        when (mode) {
            "USB" -> rbUsb.isChecked = true
            "BT" -> rbBt.isChecked = true
            else -> rbWifi.isChecked = true
        }

        val dialog = AlertDialog.Builder(this).setView(view).create()
        view.findViewById<Button>(R.id.btnSaveConnect).setOnClickListener {
            val selectedMode = when {
                rbUsb.isChecked -> "USB"
                rbBt.isChecked -> "BT"
                else -> "WIFI"
            }
            val ip = etIp.text.toString().trim().ifEmpty { "127.0.0.1" }
            prefs.edit().putString("mode", selectedMode).putString("ip", ip).apply()
            dialog.dismiss()
            connectToPc()
        }
        dialog.show()
    }

    private fun connectToPc() {
        webSocket?.close(1000, "Reconnecting")
        val prefs = getSharedPreferences("pc_control_prefs", Context.MODE_PRIVATE)
        val mode = prefs.getString("mode", "WIFI")
        val ip = prefs.getString("ip", "192.168.1.5")
        val host = if (mode == "USB") "127.0.0.1" else ip
        val url = "ws://$host:8765"

        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                runOnUiThread {
                    tvStatus.text = "Connected"
                    tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_blue))
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                setDisconnectedUi()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                setDisconnectedUi()
            }
        })
    }

    private fun setDisconnectedUi() {
        isConnected = false
        runOnUiThread {
            tvStatus.text = "Not Connected"
            tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_purple))
        }
    }

    private fun sendJson(obj: JSONObject) {
        webSocket?.send(obj.toString())
    }

    override fun onDestroy() {
        stopMicStreaming()
        webSocket?.close(1000, "Closed")
        super.onDestroy()
    }
}