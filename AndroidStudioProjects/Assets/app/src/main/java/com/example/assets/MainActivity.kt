package com.example.assets // 본인의 패키지명으로 변경하세요

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.view.MotionEvent
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

private const val GITHUB_REPOSITORY = "bluejs1004-ui/Asset-Management-App"
private const val GITHUB_LATEST_RELEASE_API = "https://api.github.com/repos/$GITHUB_REPOSITORY/releases/latest"
private const val GITHUB_PAGES_URL = "https://bluejs1004-ui.github.io/Asset-Management-App/"
private const val TRADE_NOTIFICATION_ACTION = "com.example.assets.TRADE_NOTIFICATION"

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    // 상태바/제스처바 뒤로 보이는 실제 배경 레이아웃.
    // edge-to-edge 모드에서는 이 배경색이 상태바·내비게이션바 영역에 그대로 비쳐 보이므로,
    // 라이트/다크 전환 시 이 배경도 함께 바꿔야 한다.
    private lateinit var safeAreaRoot: FrameLayout

    // 파일 업로드 콜백을 저장할 변수
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // 뒤로 가기 버튼 누른 시간을 체크하기 위한 변수
    private var backPressedTime: Long = 0

    // APK 설치 권한 설정 화면에 다녀온 뒤 이어서 설치할 파일
    private var pendingApkFile: File? = null

    // 안드로이드 파일 탐색기(파일 앱)를 열고 선택한 결과를 받아오는 런처
    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data?.data
            filePathCallback?.onReceiveValue(if (data != null) arrayOf(data) else null)
        } else {
            // 취소 시 반드시 null을 반환해야 웹뷰가 멈추지 않습니다.
            filePathCallback?.onReceiveValue(null)
        }
        filePathCallback = null
    }

    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val tradeText = intent?.getStringExtra("tradeText")
            if (tradeText != null) {
                injectJavascript(tradeText)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        // 키보드가 표시되면 WebView 영역을 키보드 위까지 줄여 입력창이 가려지지 않게 한다.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        var swipeStartX = 0f
        var swipeStartY = 0f
        var swipeGestureId = 0L
        val swipeThreshold = 72f * resources.displayMetrics.density
        webView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    swipeGestureId += 1L
                    swipeStartX = event.x
                    swipeStartY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    val deltaX = event.x - swipeStartX
                    val deltaY = event.y - swipeStartY
                    if (kotlin.math.abs(deltaX) >= swipeThreshold &&
                        kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY) * 1.25f
                    ) {
                        val direction = if (deltaX < 0f) "left" else "right"
                        val tabStep = if (direction == "left") 1 else -1
                        val gestureId = swipeGestureId
                        webView.evaluateJavascript("window.__nativeTabSwipeBlocked === true") { blocked ->
                            if (gestureId == swipeGestureId && blocked.trim() != "true") {
                                val js = """
                                    (function() {
                                        var tabs = ['home', 'assets', 'portfolio', 'trades', 'chartcmp', 'divpension', 'notes'];
                                        var current = window.mainTab || 'home';
                                        var index = tabs.indexOf(current);
                                        if (index < 0) index = 0;
                                        var nextIndex = index + $tabStep;
                                        if (nextIndex >= 0 && nextIndex < tabs.length && typeof window.setMainTab === 'function') {
                                            window.setMainTab(tabs[nextIndex]);
                                        }
                                    })();
                                    """.trimIndent()
                                webView.evaluateJavascript(js, null)
                            }
                        }
                    }
                    swipeStartX = 0f
                    swipeStartY = 0f
                }
                MotionEvent.ACTION_CANCEL -> {
                    swipeStartX = 0f
                    swipeStartY = 0f
                }
            }
            false
        }

        webView.setPadding(0, 0, 0, 0)
        safeAreaRoot = FrameLayout(this)
        safeAreaRoot.setBackgroundColor(android.graphics.Color.parseColor("#000000"))
        safeAreaRoot.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 💡 [Safe-Area 핵심 처리] 상단 노치/상태바, 하단 제스처바, 그리고 가로모드에서
        // 좌/우측에 오는 카메라 홀·제스처바 여백을 네이티브에서 자동 계산하여 적용
        ViewCompat.setOnApplyWindowInsetsListener(safeAreaRoot) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.statusBars() or
                        WindowInsetsCompat.Type.navigationBars() or
                        WindowInsetsCompat.Type.displayCutout() or
                        WindowInsetsCompat.Type.systemGestures() or
                        WindowInsetsCompat.Type.ime()
            )
            // 외부 웹페이지가 상단바/노치/좌우 제스처 영역에 가려지지 않도록 네 방향 모두
            // 실제 인셋을 그대로 반영한다. 세로모드에서는 상/하단 인셋이, 가로모드에서는
            // (이 기기 기준 좌측 카메라 홀·우측 제스처 바에 해당하는) 좌/우측 인셋이 자연히
            // 커지므로 방향을 직접 분기하지 않고 시스템이 보고하는 값을 그대로 신뢰한다.
            // 일부 WebView/기기는 상단 인셋을 0으로 전달하는 경우가 있어 최소값만 함께 보장한다.
            val density = resources.displayMetrics.density
            val minimumTopInset = (24f * density).toInt()
            view.setPadding(
                insets.left,
                maxOf(insets.top, minimumTopInset),
                insets.right,
                insets.bottom
            )
            WindowInsetsCompat.CONSUMED
        }

        // 1~2. 상태바(상단)·제스처바(하단) 배경색과 아이콘/글씨 밝기를 한 번에 처리합니다.
        //      다크 모드: 어두운 배경(#0f172a) + 흰색 아이콘/글씨
        //      라이트 모드: 흰색 배경 + 검정 아이콘/글씨
        //      HTML(대시보드)에서 라이트 모드를 켜고 끌 때마다 AndroidBridge.setLightMode()를 통해
        //      아래 applySystemBarAppearance() 함수가 다시 호출되어 즉시 갱신됩니다.
        applySystemBarAppearance(false)

        setContentView(safeAreaRoot)
        ViewCompat.requestApplyInsets(safeAreaRoot)

        // [필수 설정] 자바스크립트 및 로컬 스토리지 활성화
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
        }

        webView.webViewClient = WebViewClient()

        // HTML(JavaScript)과 Android를 연결하는 통신 브릿지 설정
        webView.addJavascriptInterface(WebAppInterface(this), "AndroidBridge")

        // WebChromeClient를 확장하여 파일 업로드 창 및 팝업 지원
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val intent = fileChooserParams?.createIntent()
                try {
                    if (intent != null) {
                        fileChooserLauncher.launch(intent)
                    }
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = null
                    return false
                }
                return true
            }
        }

        // assets 폴더에 넣은 HTML 대시보드 로드
        webView.loadUrl("file:///android_asset/dashboard.html")
        // 앱을 열 때 GitHub Releases의 최신 APK를 백그라운드에서 확인한다.
        webView.postDelayed({ checkForAppUpdate() }, 1500L)

        // 알림 리시버 등록
        val filter = IntentFilter(TRADE_NOTIFICATION_ACTION)
        registerReceiver(notificationReceiver, filter, RECEIVER_NOT_EXPORTED)

        // 스마트폰 뒤로 가기 버튼 제어
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val js = """
                    (function() {
                        // 1. 웹에 정의된 전용 뒤로가기 함수 우선 호출
                        if (typeof window.onAndroidBackPressed === 'function') {
                            var res = window.onAndroidBackPressed();
                            return (res !== undefined) ? res.toString() : "true";
                        }
                        if (typeof window.handleAndroidBack === 'function') {
                            var res = window.handleAndroidBack();
                            return (res !== undefined) ? res.toString() : "true";
                        }

                        // 2. 전용 함수가 없을 경우 기본 메뉴/모달 닫기 처리
                        var closedAny = false;
                        
                        var menus = ['rowContextMenu', 'accFilterMenu', 'globalSettingsMenu'];
                        for(var i=0; i<menus.length; i++) {
                            var m = document.getElementById(menus[i]);
                            if(m && (m.style.display === 'flex' || !m.classList.contains('hidden'))) {
                                if(m.id === 'rowContextMenu') m.style.display = 'none';
                                else m.classList.add('hidden');
                                closedAny = true;
                            }
                        }

                        var modals = ['stockModal', 'globalHistEditModal', 'tickerModal', 'accountModal', 'priceAdjustModal'];
                        for(var j=0; j<modals.length; j++) {
                            var m = document.getElementById(modals[j]);
                            if(m && !m.classList.contains('hidden')) {
                                m.classList.add('hidden');
                                closedAny = true;
                            }
                        }
                        
                        if(closedAny && window.location.hash === '#modal') {
                            history.back();
                        }
                        
                        return closedAny.toString();
                    })();
                """.trimIndent()

                webView.evaluateJavascript(js) { result ->
                    // 웹뷰 내부에서 모달/메뉴 등을 닫아 처리가 완료된 경우
                    if (result == "\"true\"" || result == "true") {
                        return@evaluateJavascript
                    }

                    // 웹뷰 히스토리가 남아있는 경우 이전 페이지로 이동
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        // 닫을 창도 없고 이전 페이지도 없을 때 2초 내 재클릭 시 앱 종료
                        if (System.currentTimeMillis() - backPressedTime <= 2000) {
                            finish()
                        } else {
                            backPressedTime = System.currentTimeMillis()
                            Toast.makeText(this@MainActivity, "'뒤로' 버튼을 한 번 더 누르면 종료됩니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        })
    }

    fun checkForAppUpdate(showCurrentStatus: Boolean = false) {
        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(GITHUB_LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 10000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "Asset-Management-App")
                }
                if (connection.responseCode !in 200..299) return@Thread

                val releaseJson = connection.inputStream.bufferedReader().use { it.readText() }
                val release = JSONObject(releaseJson)
                val latestVersion = release.optString("tag_name").removePrefix("v").trim()
                if (latestVersion.isBlank()) return@Thread

                val assets = release.optJSONArray("assets") ?: return@Thread
                var apkUrl: String? = null
                for (index in 0 until assets.length()) {
                    val asset = assets.optJSONObject(index) ?: continue
                    val assetName = asset.optString("name")
                    val browserUrl = asset.optString("browser_download_url")
                    if (assetName.endsWith(".apk", ignoreCase = true) && isAllowedGithubUrl(browserUrl)) {
                        apkUrl = browserUrl
                        break
                    }
                }
                if (apkUrl.isNullOrBlank()) {
                    if (showCurrentStatus) {
                        runOnUiThread {
                            AlertDialog.Builder(this)
                                .setTitle("앱 업데이트")
                                .setMessage("GitHub Release에서 APK 파일을 찾지 못했습니다.")
                                .setPositiveButton("확인", null)
                                .show()
                        }
                    }
                    return@Thread
                }

                val currentVersion = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
                if (!isVersionNewer(latestVersion, currentVersion)) {
                    if (showCurrentStatus) {
                        runOnUiThread {
                            AlertDialog.Builder(this)
                                .setTitle("앱 업데이트")
                                .setMessage("현재 버전 v${currentVersion}이 최신 버전입니다.")
                                .setPositiveButton("확인", null)
                                .show()
                        }
                    }
                    return@Thread
                }

                runOnUiThread {
                    showUpdateDialog(
                        latestVersion,
                        release.optString("name").ifBlank { "새 버전" },
                        release.optString("body"),
                        apkUrl
                    )
                }
            } catch (error: Exception) {
                if (showCurrentStatus) {
                    runOnUiThread {
                        Toast.makeText(this, "최신 버전을 확인하지 못했습니다: ${error.message}", Toast.LENGTH_LONG).show()
                    }
                }
                // 네트워크가 없거나 GitHub에 Release가 없으면 기존 앱을 그대로 실행한다.
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun isAllowedGithubUrl(rawUrl: String): Boolean {
        return try {
            val uri = Uri.parse(rawUrl)
            val host = uri.host ?: return false
            uri.scheme == "https" && host in setOf(
                "github.com",
                "objects.githubusercontent.com",
                "release-assets.githubusercontent.com"
            )
        } catch (_: Exception) {
            false
        }
    }

    private fun versionParts(version: String): List<Int> {
        return Regex("\\d+").findAll(version).map { it.value.toIntOrNull() ?: 0 }.toList()
    }

    private fun isVersionNewer(latest: String, current: String): Boolean {
        val latestParts = versionParts(latest)
        val currentParts = versionParts(current)
        val size = maxOf(latestParts.size, currentParts.size, 3)
        for (index in 0 until size) {
            val latestPart = latestParts.getOrNull(index) ?: 0
            val currentPart = currentParts.getOrNull(index) ?: 0
            if (latestPart != currentPart) return latestPart > currentPart
        }
        return false
    }

    private fun showUpdateDialog(
        latestVersion: String,
        releaseName: String,
        releaseNotes: String,
        apkUrl: String?
    ) {
        val message = buildString {
            append("최신 버전 ").append(latestVersion).append("을 사용할 수 있습니다.\n\n")
            if (releaseName.isNotBlank()) append(releaseName).append("\n")
            if (releaseNotes.isNotBlank()) {
                append(releaseNotes.take(500))
                if (releaseNotes.length > 500) append("…")
            }
        }
        AlertDialog.Builder(this)
            .setTitle("앱 업데이트")
            .setMessage(message)
            .setNegativeButton("나중에", null)
            .setPositiveButton("업데이트") { _, _ ->
                if (apkUrl != null) downloadAndInstallApk(apkUrl, latestVersion)
            }
            .show()
    }

    private fun downloadAndInstallApk(apkUrl: String, version: String) {
        if (!isAllowedGithubUrl(apkUrl)) return
        Toast.makeText(this, "업데이트 파일을 다운로드하는 중입니다.", Toast.LENGTH_SHORT).show()

        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Asset-Management-App")
                }
                if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP ${connection.responseCode}")

                val safeVersion = version.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val apkFile = File(cacheDir, "Asset_v$safeVersion.apk")
                connection.inputStream.use { input ->
                    FileOutputStream(apkFile).use { output -> input.copyTo(output) }
                }
                runOnUiThread { installApk(apkFile) }
            } catch (error: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "업데이트 다운로드에 실패했습니다: ${error.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun installApk(apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            pendingApkFile = apkFile
            AlertDialog.Builder(this)
                .setTitle("앱 설치 권한 필요")
                .setMessage("자동 업데이트를 위해 이 앱의 APK 설치 권한을 허용해주세요.")
                .setNegativeButton("취소", null)
                .setPositiveButton("설정 열기") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                }
                .show()
            return
        }

        try {
            val apkUri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                apkFile
            )
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            pendingApkFile = null
        } catch (error: Exception) {
            Toast.makeText(this, "APK 설치를 시작하지 못했습니다: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        val pendingFile = pendingApkFile
        if (pendingFile != null && pendingFile.exists() &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageManager.canRequestPackageInstalls()
        ) {
            pendingApkFile = null
            installApk(pendingFile)
        }
    }

    // 상태바(상단)와 제스처/내비게이션바(하단)의 배경색·아이콘(글씨) 밝기를 한 번에 적용합니다.
    // isLight = true  → 흰색 배경 + 검정 아이콘/글씨 (라이트 모드)
    // isLight = false → 어두운 배경(#000000) + 흰색 아이콘/글씨 (다크 모드)
    // WebAppInterface.setLightMode()를 통해 HTML(대시보드)의 라이트/다크 전환과 연동됩니다.
    fun applySystemBarAppearance(isLight: Boolean) {
        val barColor = if (isLight) {
            android.graphics.Color.parseColor("#ffffff")
        } else {
            android.graphics.Color.parseColor("#000000")
        }
        window.statusBarColor = barColor
        window.navigationBarColor = barColor
        // edge-to-edge 모드에서는 상태바·제스처바 영역에 window.statusBarColor가 아니라
        // 이 루트 레이아웃의 배경색이 실제로 비쳐 보이므로 반드시 함께 갱신해야 한다.
        if (::safeAreaRoot.isInitialized) {
            safeAreaRoot.setBackgroundColor(barColor)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (isLight) mask else 0, mask)
        } else {
            @Suppress("DEPRECATION")
            var flags = window.decorView.systemUiVisibility
            flags = if (isLight) {
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            } else {
                flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags = if (isLight) {
                    flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                } else {
                    flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                }
            }
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = flags
        }
    }

    private fun injectJavascript(text: String) {
        val safeText = text.replace("\n", "\\n").replace("'", "\\'")
        val js = """
            javascript:(function() {
                var input = document.getElementById('chatInput');
                if(input) {
                    input.value = '$safeText';
                    window.parseMessage();
                }
            })();
        """.trimIndent()
        runOnUiThread { webView.evaluateJavascript(js, null) }
    }

    // 화면 회전(세로↔가로) 시 safe-area(상태바/제스처바/카메라 홀 인셋)를
    // 새 방향 기준으로 즉시 다시 계산하도록 요청한다.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::safeAreaRoot.isInitialized) {
            ViewCompat.requestApplyInsets(safeAreaRoot)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(notificationReceiver)
    }
}

// HTML JavaScript와 통신하는 브릿지 인터페이스 클래스
class WebAppInterface(private val mContext: Context) {

    // 웹에서 명시적으로 즉시 앱 종료를 호출할 때 사용 (window.AndroidBridge.closeApp())
    @JavascriptInterface
    fun closeApp() {
        (mContext as? AppCompatActivity)?.runOnUiThread {
            (mContext as? AppCompatActivity)?.finish()
        }
    }

    // HTML(대시보드)의 라이트/다크 모드 전환에 맞춰 상태바·제스처바 색상도 함께 바꿀 때 사용
    // (예: window.AndroidBridge.setLightMode(true))
    @JavascriptInterface
    fun setLightMode(isLight: Boolean) {
        val activity = mContext as? MainActivity ?: return
        activity.runOnUiThread {
            activity.applySystemBarAppearance(isLight)
        }
    }

    @JavascriptInterface
    fun openGithubWeb() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_PAGES_URL))
            mContext.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(mContext, "GitHub 웹 버전을 열 수 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun getAppVersion(): String {
        return try {
            mContext.packageManager.getPackageInfo(mContext.packageName, 0).versionName.orEmpty()
        } catch (_: Exception) {
            "4.8.0"
        }
    }

    @JavascriptInterface
    fun checkForUpdates() {
        (mContext as? MainActivity)?.runOnUiThread {
            (mContext as? MainActivity)?.checkForAppUpdate(showCurrentStatus = true)
        }
    }

    @JavascriptInterface
    fun downloadJSON(jsonString: String, filename: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = mContext.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }

                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(jsonString.toByteArray())
                    }
                    (mContext as AppCompatActivity).runOnUiThread {
                        Toast.makeText(mContext, "다운로드 폴더에 백업 완료", Toast.LENGTH_LONG).show()
                    }
                } else {
                    throw Exception("파일 생성 실패 (URI null)")
                }
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(downloadsDir, filename)
                val writer = FileWriter(file)
                writer.append(jsonString)
                writer.flush()
                writer.close()

                (mContext as AppCompatActivity).runOnUiThread {
                    Toast.makeText(mContext, "다운로드 폴더에 백업 완료: ${file.name}", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            (mContext as AppCompatActivity).runOnUiThread {
                Toast.makeText(mContext, "파일 백업에 실패했습니다: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
