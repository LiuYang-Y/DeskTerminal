package com.qingke.desk.shell.debug

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * folia-major Web 版的**可行性预览页**（仅 debug 变体）。
 *
 * 目的：在正式界面之外，用真机 WebView 跑一遍 folia 的 Web 版，回答"这台机器能不能跑、
 * 跑到什么程度"。它不碰任何正式代码，release 构建里不存在本文件、对应的 manifest 条目
 * 与明文流量配置。
 *
 * 三件在验证里必须有的东西，缺一样就没法取证：
 *   · `setWebContentsDebuggingEnabled(true)` —— 不开这个，`chrome://inspect` 看不到页面，
 *     控制台报错、WebGL 上下文、网络失败全部拿不到，只能靠"看起来白屏"猜；
 *   · [probeFps] —— 帧率必须在**页面内部**量（rAF），从外面量只能量到整机的合成帧率，
 *     看不出是页面卡还是机器卡；
 *   · 文件选择器回调 —— 本地文件夹导入要验的就是"这条链路在 WebView 里通不通"。
 *
 * 明文 HTTP：局域网开发服务器没有证书。允许明文的配置是 **debug 专属**的
 * （`shell/src/debug/res/xml/network_security_config.xml`），release 侧根本没有这个文件。
 */
class FoliaWebViewPreviewActivity : ComponentActivity() {

    private var webView: WebView? = null

    /** WebView 的 `<input type=file>` 回调；不存下来，选完文件页面收不到结果。 */
    private var filePathCallback: ValueCallback<Array<android.net.Uri>>? = null

    private val fileChooser = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val callback = filePathCallback
        filePathCallback = null
        val uris = WebChromeClient.FileChooserParams.parseResult(
            result.resultCode,
            result.data,
        )
        // 用户取消了也要回调（传 null），否则页面会一直卡在"等待选择"状态。
        callback?.onReceiveValue(
            if (result.resultCode == Activity.RESULT_OK) uris else null,
        )
        Log.i(TAG, "file chooser result=${result.resultCode} uris=${uris?.size ?: 0}")
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterImmersive()
        // 全局开关：不开就没有远程调试。它是**进程级**的，装在 debug 里就够。
        WebView.setWebContentsDebuggingEnabled(true)

        // URL 的优先级：intent 传入（adb 一行就能改）> 上次输入的（存在 debug 专属 prefs 里）
        // > 兜底常量。存在 prefs 而不是写死，是因为 PC 的局域网 IP 会变，每次手敲太痛。
        val startUrl = intent.getStringExtra(EXTRA_URL)
            ?.also { prefs().edit().putString(KEY_URL, it).apply() }
            ?: prefs().getString(KEY_URL, null)
            ?: DEFAULT_URL
        Log.i(TAG, "WebView preview created: ${WebView.getCurrentWebViewPackage()?.versionName}")

        setContent {
            MaterialTheme {
                var urlText by remember { mutableStateOf(startUrl) }
                var status by remember { mutableStateOf("未开始") }
                // 调试条会盖住被测页面顶部约 40dp。取证截图要么看全屏、要么看这条，
                // 所以给它一个折叠开关 —— 否则每张截图顶上都被自己挡一块。
                var barVisible by remember { mutableStateOf(true) }
                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            WebView(context).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                configure(this)
                                webView = this
                                loadUrl(urlText)
                                status = "加载中"
                            }
                        },
                    )
                    if (barVisible) {
                        DebugBar(
                            urlText = urlText,
                            status = status,
                            onUrlChange = { urlText = it },
                            onLoad = {
                                prefs().edit().putString(KEY_URL, urlText).apply()
                                status = "加载中"
                                webView?.loadUrl(urlText)
                            },
                            onProbe = {
                                probeFps { fps ->
                                    status = fps
                                }
                            },
                            onProbeWebgl = {
                                probeWebgl { info ->
                                    status = info
                                }
                            },
                            onCollapse = { barVisible = false },
                            modifier = Modifier.align(Alignment.TopStart),
                        )
                    }
                    if (!barVisible) {
                        // 折叠后留一个小小的"展开"抓手，避免折叠后没法恢复。
                        Button(
                            onClick = { barVisible = true },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp),
                        ) {
                            Text("调试", fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
    private fun configure(web: WebView) {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            // 自动播放：音乐页必须能在没有用户手势的情况下开始播，否则"切后台还播不播"
            // 这条根本测不了 —— 每次回来都要先点一下播放。
            mediaPlaybackRequiresUserGesture = false
            // 页面可能混着 https 资源（封面、歌词接口）与 http 页面本身。
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            // 桌面终端是横屏固定窗口，缩放与"双击放大"只会干扰取证。
            setSupportZoom(false)
            builtInZoomControls = false
            userAgentString = null
        }
        web.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                Log.i(TAG, "page finished: $url")
            }

            override fun onReceivedError(
                view: WebView,
                request: android.webkit.WebResourceRequest,
                error: android.webkit.WebResourceError,
            ) {
                Log.w(
                    TAG,
                    "resource error: ${request.url} code=${error.errorCode} ${error.description}",
                )
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                val level = when (message.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR -> "E"
                    ConsoleMessage.MessageLevel.WARNING -> "W"
                    else -> "I"
                }
                // 控制台是唯一能解释"白屏"的地方：Vite 的依赖预构建失败、语法不被支持、
                // WebGL 拿不到上下文，都只在这里出现一次。
                Log.i(TAG, "console/$level ${message.sourceId()}:${message.lineNumber()} ${message.message()}")
                return true
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<android.net.Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                this@FoliaWebViewPreviewActivity.filePathCallback = filePathCallback
                val intent = fileChooserParams.createIntent()
                // 允许多选：文件夹导入一次就是几百个文件，不让多选等于用不了。
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                Log.i(
                    TAG,
                    "showFileChooser mode=${fileChooserParams.mode} " +
                        "accept=${fileChooserParams.acceptTypes.joinToString()}",
                )
                fileChooser.launch(intent)
                return true
            }
        }
    }

    /**
     * 在**页面内部**量帧率。
     *
     * 为什么不用 gfxinfo / SurfaceFlinger：那些量的是整机合成，页面自己 15fps 而系统
     * 60fps 时它们照样报 60。rAF 才是页面视角的帧率。
     *
     * 分两步求值（起一轮 rAF、隔 2.3s 再读结果），**不把 Promise 交给 evaluateJavascript**：
     * 第一版就是 `return new Promise(...)`，回调拿到的是 `{}` —— 那样量出来的帧率是假的。
     */
    private fun probeFps(onResult: (String) -> Unit) {
        val start = """
            (function () {
              window.__fpsProbe = { start: performance.now(), frames: 0, done: false, ms: 0 };
              function tick(now) {
                window.__fpsProbe.frames++;
                if (now - window.__fpsProbe.start < 2000) { requestAnimationFrame(tick); }
                else {
                  window.__fpsProbe.ms = now - window.__fpsProbe.start;
                  window.__fpsProbe.done = true;
                }
              }
              requestAnimationFrame(tick);
              return 'started';
            })()
        """.trimIndent()
        webView?.evaluateJavascript(start) {
            webView?.postDelayed({
                webView?.evaluateJavascript("JSON.stringify(window.__fpsProbe || {})") { value ->
                    val fps = Regex("\"frames\":(\\d+)").find(value)?.groupValues?.get(1)
                    val ms = Regex("\"ms\":([\\d.]+)").find(value)?.groupValues?.get(1)
                    val text = if (fps != null && ms != null && ms.toDouble() > 0) {
                        "FPS %.1f (%s 帧 / %s ms)".format(fps.toInt() / (ms.toDouble() / 1000), fps, ms)
                    } else {
                        "FPS 无数据 $value"
                    }
                    Log.i(TAG, "fps probe: $text")
                    onResult(text)
                }
            }, 2300)
        }
    }

    /**
     * 探环境能力：WebGL 版本 + 一批"新 API 有没有"。
     *
     * **每一类 context 必须用独立的 canvas**（第一版在这里踩过坑）：同一个 canvas 一旦
     * 拿到过一种 context，再 `getContext` 另一种类型一律返回 null —— 探针因此把
     * webgl2 报成 false，差点得出"这台机器不支持 WebGL2"的错误结论。
     *
     * API 清单是按 folia 源码里实际用到的东西挑的（`.at()` / `Object.hasOwn` /
     * `crypto.randomUUID` / File System Access），缺一个都可能让页面在运行时炸。
     */
    private fun probeWebgl(onResult: (String) -> Unit) {
        val script = """
            (function () {
              var out = { webgl1: false, webgl2: false, renderer: '', api: {} };
              try {
                // 关键：每种 context 一个 canvas，不能复用。
                var c1 = document.createElement('canvas');
                out.webgl1 = !!(c1.getContext('webgl') || c1.getContext('experimental-webgl'));
                var c2 = document.createElement('canvas');
                var g2 = c2.getContext('webgl2');
                out.webgl2 = !!g2;
                var g = g2 || c2.getContext('webgl');
                if (g) {
                  var dbg = g.getExtension('WEBGL_debug_renderer_info');
                  if (dbg) { out.renderer = g.getParameter(dbg.UNMASKED_RENDERER_WEBGL); }
                  out.maxTex = g.getParameter(g.MAX_TEXTURE_SIZE);
                }
              } catch (e) { out.glError = String(e); }
              out.api = {
                at: typeof Array.prototype.at === 'function',
                hasOwn: typeof Object.hasOwn === 'function',
                randomUUID: !!(window.crypto && crypto.randomUUID),
                structuredClone: typeof structuredClone === 'function',
                offscreenCanvas: typeof OffscreenCanvas === 'function',
                fileSystemAccess: typeof window.showOpenFilePicker === 'function',
                audioWorklet: typeof AudioWorkletNode === 'function',
                webcodecs: typeof VideoDecoder === 'function'
              };
              return JSON.stringify(out);
            })()
        """.trimIndent()
        webView?.evaluateJavascript(script) { value ->
            Log.i(TAG, "env probe: $value")
            onResult("ENV $value")
        }
    }

    /** 与 MainActivity 同一套沉浸式：不设会在横屏左侧留一条黑带，遮挡被测页面。 */
    private fun enterImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val attrs = window.attributes
            attrs.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = attrs
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() {
        webView?.let {
            it.stopLoading()
            it.destroy()
        }
        webView = null
        super.onDestroy()
    }

    private fun prefs() = getSharedPreferences("folia_preview", MODE_PRIVATE)

    private companion object {
        const val TAG = "FoliaPreview"
        const val EXTRA_URL = "folia_preview_url"
        const val KEY_URL = "last_url"
        const val DEFAULT_URL = "http://192.168.1.100:3000/"
    }
}

/**
 * 预览页顶部的调试条。它**浮在页面之上**（而不是占一行）：被测页面要拿满整屏，
 * 798×411dp 的横屏下每让出一 dp 都是在改被测对象的尺寸。
 */
@Composable
private fun DebugBar(
    urlText: String,
    status: String,
    onUrlChange: (String) -> Unit,
    onLoad: () -> Unit,
    onProbe: () -> Unit,
    onProbeWebgl: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xCC000000))
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = urlText,
                onValueChange = onUrlChange,
                modifier = Modifier.width(420.dp),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                label = { Text("URL", fontSize = 10.sp) },
            )
            Button(onClick = onLoad, modifier = Modifier.padding(start = 8.dp)) {
                Text("加载", fontSize = 11.sp)
            }
            Button(onClick = onProbe, modifier = Modifier.padding(start = 4.dp)) {
                Text("测 FPS", fontSize = 11.sp)
            }
            Button(onClick = onProbeWebgl, modifier = Modifier.padding(start = 4.dp)) {
                Text("探 WebGL", fontSize = 11.sp)
            }
            Button(onClick = onCollapse, modifier = Modifier.padding(start = 4.dp)) {
                Text("收起", fontSize = 11.sp)
            }
        }
        Text(
            text = status,
            fontSize = 11.sp,
            color = Color(0xFF9EF7A0),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
