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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.theme.CockpitIcons
import com.example.ui.theme.vwColors
import java.lang.ref.WeakReference

/**
 * Controller holding a weak reference to the persistent embedded Yandex Maps [WebView]
 * so the left VW panel («Назад», «Карта») and floating `+` / `-` / «Центр/Пробки» buttons
 * can interact with it without ever destroying or reloading the map.
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
    val vw = MaterialTheme.vwColors
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
                            databaseEnabled = true
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

            // Top-left subtle VW status pill while map loads or is active
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
                color = colorScheme.surface.copy(alpha = 0.92f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, colorScheme.outline)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = CockpitIcons.Map,
                        contentDescription = "Яндекс Карты",
                        tint = colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (isPageLoading) "Яндекс Карты • Загрузка..." else "Яндекс Карты • Пробки",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = colorScheme.onSurface
                    )
                }
            }

            // Right-edge driving-friendly map controls: +, -, «Центр/Пробки»
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.End
            ) {
                IconButton(
                    onClick = { controller.zoomIn() },
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colorScheme.surface.copy(alpha = 0.94f))
                        .border(1.dp, colorScheme.outline, RoundedCornerShape(12.dp))
                        .testTag("map_zoom_in_button")
                ) {
                    Icon(
                        imageVector = CockpitIcons.Add,
                        contentDescription = "Приблизить карту",
                        tint = colorScheme.onSurface,
                        modifier = Modifier.size(26.dp)
                    )
                }

                IconButton(
                    onClick = { controller.zoomOut() },
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colorScheme.surface.copy(alpha = 0.94f))
                        .border(1.dp, colorScheme.outline, RoundedCornerShape(12.dp))
                        .testTag("map_zoom_out_button")
                ) {
                    Icon(
                        imageVector = CockpitIcons.Remove,
                        contentDescription = "Отдалить карту",
                        tint = colorScheme.onSurface,
                        modifier = Modifier.size(26.dp)
                    )
                }

                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { controller.recenterAndShowTraffic() }
                        .testTag("map_center_traffic_button"),
                    color = colorScheme.primary,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, colorScheme.primary)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = CockpitIcons.MyLocation,
                            contentDescription = "Центр и пробки",
                            tint = colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Центр / Пробки",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = colorScheme.onPrimary
                        )
                    }
                }
            }

            // Bottom-left quick return to apps pill
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onCloseToGrid)
                    .testTag("map_back_to_apps_button"),
                color = colorScheme.surface.copy(alpha = 0.92f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, vw.accentBlue.copy(alpha = 0.55f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = CockpitIcons.AppsGrid,
                        contentDescription = "Сетка приложений",
                        tint = colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Приложения",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = colorScheme.onSurface
                    )
                }
            }
        }
    }
}
