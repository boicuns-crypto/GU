package com.example.feature.cockpit

import android.annotation.SuppressLint
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.lang.ref.WeakReference

/**
 * Controller holding a weak reference to the persistent embedded Yandex Maps [WebView]
 * so the left VW panel («Назад», «Карта») can interact with it without ever destroying
 * or reloading the map.
 */
class EmbeddedYandexMapsController {
    private var webViewRef: WeakReference<WebView>? = null

    internal fun attach(webView: WebView) {
        webViewRef = WeakReference(webView)
    }

    internal fun detach(webView: WebView) {
        if (webViewRef?.get() === webView) {
            webViewRef = null
        }
    }

    fun canGoBack(): Boolean = webViewRef?.get()?.canGoBack() == true

    fun goBack(): Boolean {
        val wv = webViewRef?.get() ?: return false
        return if (wv.canGoBack()) {
            wv.goBack()
            true
        } else {
            false
        }
    }

    fun zoomIn() {
        val wv = webViewRef?.get() ?: return
        wv.evaluateJavascript(
            """
            (function() {
              var btn = document.querySelector('[aria-label*="Приблизить"], [class*="zoom-control__zoom-in"], [data-action="zoom-in"]');
              if (btn) { btn.click(); return true; }
              return false;
            })();
            """.trimIndent()
        ) { result ->
            if (result != "true") {
                wv.zoomIn()
            }
        }
    }

    fun zoomOut() {
        val wv = webViewRef?.get() ?: return
        wv.evaluateJavascript(
            """
            (function() {
              var btn = document.querySelector('[aria-label*="Отдалить"], [class*="zoom-control__zoom-out"], [data-action="zoom-out"]');
              if (btn) { btn.click(); return true; }
              return false;
            })();
            """.trimIndent()
        ) { result ->
            if (result != "true") {
                wv.zoomOut()
            }
        }
    }

    fun recenterAndShowTraffic() {
        val wv = webViewRef?.get() ?: return
        wv.evaluateJavascript(
            """
            (function() {
              var locBtn = document.querySelector('[aria-label*="Моё местоположение"], [aria-label*="местоположение"], [class*="geolocation-control"]');
              if (locBtn) { locBtn.click(); return true; }
              return false;
            })();
            """.trimIndent()
        ) { clicked ->
            val currentUrl = wv.url.orEmpty()
            if (clicked != "true" || !currentUrl.contains("yandex")) {
                wv.loadUrl(YANDEX_MAPS_TRAFFIC_URL)
            }
        }
    }

    companion object {
        const val YANDEX_MAPS_TRAFFIC_URL = "https://yandex.ru/maps/?l=trf"

        /**
         * Lightweight DOM/CSS cleanup hiding only Yandex's "Продолжить в приложении / Скачать"
         * smart-app banner so the embedded map uses 100% of the Cockpit viewport cleanly.
         */
        internal const val HIDE_SMART_BANNER_JS = """
            (function() {
              try {
                if (!document.getElementById('vw-jetta-clean-map-style')) {
                  var style = document.createElement('style');
                  style.id = 'vw-jetta-clean-map-style';
                  style.textContent = [
                    '[class*="smart-banner"]',
                    '[class*="smartbanner"]',
                    '[class*="app-promo"]',
                    '[class*="promo-banner"]',
                    '[class*="download-banner"]',
                    '[class*="mobile-app-banner"]',
                    '[data-testid*="smart-banner"]'
                  ].join(',') + '{ display: none !important; height: 0 !important; visibility: hidden !important; pointer-events: none !important; }';
                  (document.head || document.documentElement).appendChild(style);
                }
                function hideAppInstallBanners() {
                  var nodes = document.querySelectorAll('div, section, header, aside');
                  for (var i = 0; i < nodes.length; i++) {
                    var el = nodes[i];
                    if (!el || el.offsetHeight <= 0 || el.offsetHeight > 110) continue;
                    var txt = (el.innerText || '').trim();
                    if ((txt.indexOf('Продолжить в приложении') !== -1 || txt.indexOf('Открыть в приложении') !== -1) &&
                        (txt.indexOf('Скачать') !== -1 || txt.indexOf('Открыть') !== -1)) {
                      el.style.setProperty('display', 'none', 'important');
                    }
                  }
                }
                hideAppInstallBanners();
                if (!window.__vwBannerObserver && typeof MutationObserver !== 'undefined') {
                  window.__vwBannerObserver = new MutationObserver(hideAppInstallBanners);
                  window.__vwBannerObserver.observe(document.documentElement, { childList: true, subtree: true });
                }
              } catch (e) {}
            })();
        """

        internal fun isTrustedYandexOrigin(origin: String?): Boolean {
            if (origin.isNullOrBlank()) return false
            val lower = origin.lowercase()
            return lower.startsWith("https://yandex.ru") ||
                lower.startsWith("https://www.yandex.ru") ||
                lower.startsWith("https://maps.yandex.ru") ||
                lower.startsWith("https://yandex.com") ||
                lower.startsWith("https://ya.ru")
        }
    }
}

/**
 * Persistent embedded Yandex Maps (`https://yandex.ru/maps/?l=trf`) pane for Standard Cockpit mode.
 *
 * Kept alive in the composition tree and toggled via visibility (`isVisible`) so switching between
 * the 4×3 App Grid and Yandex Maps is instant with zero WebView recreation.
 *
 * All navigation controls («Домой», «Назад», «Карта») live exclusively on the permanent VW Left Panel,
 * while map zoom/layers/location use Yandex Maps' own native controls without duplicate floating overlays.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EmbeddedYandexMapsPane(
    isVisible: Boolean,
    controller: EmbeddedYandexMapsController,
    onCloseToGrid: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    var isPageLoading by remember { mutableStateOf(true) }
    var webViewHolder by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            webViewHolder?.let { wv ->
                controller.detach(wv)
                runCatching {
                    wv.stopLoading()
                    wv.destroy()
                }
            }
            webViewHolder = null
        }
    }

    Card(
        modifier = modifier
            .fillMaxSize()
            .testTag("embedded_yandex_maps_pane"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, colorScheme.outline)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("yandex_maps_webview"),
                factory = { ctx ->
                    WebView(ctx).apply {
                        setLayerType(View.LAYER_TYPE_HARDWARE, null)
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            setGeolocationEnabled(true)
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            builtInZoomControls = false
                            displayZoomControls = false
                            setSupportZoom(true)
                            cacheMode = WebSettings.LOAD_DEFAULT
                            mediaPlaybackRequiresUserGesture = false
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val scheme = request?.url?.scheme?.lowercase().orEmpty()
                                return scheme != "http" && scheme != "https"
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isPageLoading = false
                                view?.evaluateJavascript(
                                    EmbeddedYandexMapsController.HIDE_SMART_BANNER_JS,
                                    null
                                )
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onGeolocationPermissionsShowPrompt(
                                origin: String?,
                                callback: GeolocationPermissions.Callback?
                            ) {
                                val allow = EmbeddedYandexMapsController.isTrustedYandexOrigin(origin)
                                callback?.invoke(origin, allow, false)
                            }
                        }
                        visibility = if (isVisible) View.VISIBLE else View.INVISIBLE
                        loadUrl(EmbeddedYandexMapsController.YANDEX_MAPS_TRAFFIC_URL)
                        controller.attach(this)
                        webViewHolder = this
                    }
                },
                update = { wv ->
                    val targetVis = if (isVisible) View.VISIBLE else View.INVISIBLE
                    if (wv.visibility != targetVis) {
                        wv.visibility = targetVis
                    }
                    if (isVisible) {
                        wv.onResume()
                    }
                }
            )

            // Subtle thin VW progress bar shown ONLY during initial page load
            if (isPageLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(3.dp)
                        .testTag("yandex_maps_loading_indicator"),
                    color = colorScheme.primary,
                    trackColor = colorScheme.surfaceVariant
                )
            }
        }
    }
}
