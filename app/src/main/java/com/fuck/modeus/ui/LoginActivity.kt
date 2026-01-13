package com.fuck.modeus.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fuck.modeus.data.TokenManager

class LoginActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var targetId: String? = null
    private val TAG = "FuckModeus_DEBUG"
    private var pendingCourseId: String? = null
    private var pendingProtoId: String? = null

    // Флаг, чтобы не сохранять токен 100 раз подряд и остановить сканер
    private var isTokenCaptured = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        targetId = intent.getStringExtra("TARGET_ID")

        pendingCourseId = intent.getStringExtra("PENDING_COURSE_ID")
        pendingProtoId = intent.getStringExtra("PENDING_PROTO_ID")

        // Сбрасываем флаг при новом создании
        isTokenCaptured = false

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(cm: ConsoleMessage): Boolean {
                // Фильтруем спам, оставляем только наши логи
                if (cm.message().startsWith("JS_SCANNER")) {
                    Log.d(TAG, "WebView: ${cm.message()}")
                }
                return true
            }
        }

        webView.addJavascriptInterface(WebAppInterface(this), "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (isTokenCaptured) return

                Log.d(TAG, "PageFinished: $url")

                // 1. Пытаемся вытащить из URL (самый быстрый способ)
                checkUrlForToken(url)

                // 2. Если это сайт Модеуса, запускаем JS-сканер как резерв
                if (url != null && url.contains("modeus.org")) {
                    startTokenHunting()
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString()
                if (checkUrlForToken(url)) {
                    return true // Прерываем загрузку, если нашли токен
                }
                return super.shouldOverrideUrlLoading(view, request)
            }
        }

        val url = "https://sfedu.modeus.org"
        webView.loadUrl(url)
    }

    private fun checkUrlForToken(url: String?): Boolean {
        if (url == null || isTokenCaptured) return false

        // ВАЖНО: Сначала ищем id_token, так как он JWT
        var token = extractParamFromUrl(url, "id_token")

        // Если нет, пробуем access_token, но с проверкой на JWT
        if (token == null) {
            token = extractParamFromUrl(url, "access_token")
        }

        if (token != null && isValidJWT(token)) {
            Log.d(TAG, "URL_SCANNER: Найден валидный JWT в URL!")
            saveTokenAndExit(token)
            return true
        }

        return false
    }

    private fun extractParamFromUrl(url: String, param: String): String? {
        try {
            val key = "$param="
            val start = url.indexOf(key)
            if (start == -1) return null

            var end = url.indexOf("&", start)
            if (end == -1) end = url.indexOf("#", start) // На случай если фрагменты перепутаны
            if (end == -1) end = url.length

            return url.substring(start + key.length, end)
        } catch (e: Exception) {
            return null
        }
    }

    // ВАЖНЕЙШАЯ ПРОВЕРКА: Токен должен содержать 2 точки (Header.Payload.Signature)
    private fun isValidJWT(token: String): Boolean {
        val dots = token.count { it == '.' }
        if (dots != 2) {
            Log.w(TAG, "VALIDATOR: Найден токен, но это НЕ JWT (точек: $dots). Игнорируем. Токен: ${token.take(10)}...")
            return false
        }
        return true
    }

    private fun startTokenHunting() {
        val js = """
            (function() {
                if (window.tokenCheckInterval) return;
                console.log("JS_SCANNER: Старт охоты...");

                function check(storage) {
                    for (var i = 0; i < storage.length; i++) {
                        var key = storage.key(i);
                        // Ищем только ключи OIDC
                        if (key.indexOf('oidc.user') === 0) {
                            try {
                                var json = JSON.parse(storage.getItem(key));
                                // Приоритет 1: id_token
                                if (json.id_token) return json.id_token;
                                // Приоритет 2: access_token
                                if (json.access_token) return json.access_token;
                            } catch (e) {}
                        }
                    }
                    return null;
                }

                window.tokenCheckInterval = setInterval(function() {
                    var token = check(sessionStorage) || check(localStorage);
                    if (token) {
                        // Простая валидация на JS (есть ли точки)
                        if ((token.match(/\./g) || []).length === 2) {
                            console.log("JS_SCANNER: Валидный JWT найден!");
                            clearInterval(window.tokenCheckInterval);
                            Android.saveToken(token);
                        } else {
                            console.log("JS_SCANNER: Нашел токен, но это не JWT. Ищем дальше...");
                        }
                    }
                }, 500);
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    fun saveTokenAndExit(token: String) {
        if (isTokenCaptured) return // Защита от двойного вызова
        isTokenCaptured = true

        val cleanToken = token.replace("\"", "").trim()

        Log.d(TAG, "SUCCESS: Сохраняем токен (длина: ${cleanToken.length}) и выходим.")
        // Дополнительная проверка на всякий случай перед записью
        if (!isValidJWT(cleanToken)) {
            Log.e(TAG, "FATAL: Попытка сохранить невалидный токен! Отмена.")
            isTokenCaptured = false
            return
        }

        TokenManager.saveToken(this, cleanToken)

        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK

        targetId?.let {
            intent.putExtra("RESTART_WITH_ID", it)
        }

        pendingCourseId?.let {
            intent.putExtra("PENDING_COURSE_ID", it)
            // prototypeId может быть null, передаем только если есть курс
            intent.putExtra("PENDING_PROTO_ID", pendingProtoId)
        }

        startActivity(intent)
        finish() // Уничтожаем LoginActivity
    }

    class WebAppInterface(private val activity: LoginActivity) {
        @JavascriptInterface
        fun saveToken(token: String) {
            activity.runOnUiThread {
                activity.saveTokenAndExit(token)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Останавливаем загрузку, если активити убивается
        webView.stopLoading()
        webView.destroy()
    }
}