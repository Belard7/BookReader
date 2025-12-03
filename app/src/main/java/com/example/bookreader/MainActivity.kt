package com.example.bookreader// <--- ¡IMPORTANTE! Pon aquí tu paquete real

import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

// IMPORTACIONES DE ML KIT CORREGIDAS (es 'nl', no 'nlp')
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var webView: WebView
    private lateinit var tts: TextToSpeech

    private var bookData: String = ""
    private var currentFileName: String = ""

    // Selector de archivos
    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            importAndOpenBook(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Ocultar botón nativo antiguo si existe
        val oldFab = findViewById<View>(R.id.btnLoadPdf)
        if (oldFab != null) oldFab.visibility = View.GONE

        // Inicializar TTS
        tts = TextToSpeech(this, this)

        // Configurar WebView
        webView = findViewById(R.id.webView)
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true

        // Depuración (opcional, ayuda a ver errores de JS en Logcat)
        WebView.setWebContentsDebuggingEnabled(true)

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()

        // Preparar el modelo de traducción (descarga silenciosa)
        prepareTranslationModel()

        // Conectar HTML
        webView.addJavascriptInterface(WebAppInterface(), "AndroidInterface")
        webView.loadUrl("file:///android_asset/reader.html")
    }

    // --- TRADUCCIÓN OFFLINE (ML KIT) ---
    private fun prepareTranslationModel() {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.SPANISH)
            .build()
        val translator = Translation.getClient(options)

        // Descargar si es necesario (requiere wifi la primera vez)
        val conditions = DownloadConditions.Builder()
            .requireWifi()
            .build()

        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener { Log.d("MLKit", "Modelo listo") }
            .addOnFailureListener { Log.e("MLKit", "Error modelo: $it") }
    }

    private fun translateText(text: String) {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.SPANISH)
            .build()
        val translator = Translation.getClient(options)

        translator.downloadModelIfNeeded()
            .addOnSuccessListener {
                translator.translate(text)
                    .addOnSuccessListener { translated ->
                        // Escapar comillas para JS
                        val safeText = translated.replace("'", "\\'").replace("\n", " ")
                        runOnUiThread {
                            // Llamar a la función JS que muestra el resultado
                            webView.evaluateJavascript("javascript:onTranslationReceived('$safeText')", null)
                        }
                    }
                    .addOnFailureListener {
                        sendJsError("Error al traducir")
                    }
            }
            .addOnFailureListener {
                sendJsError("Descargando idioma... espera unos segundos")
                Toast.makeText(this, "Descargando modelo Español (30MB)...", Toast.LENGTH_SHORT).show()
            }
    }

    private fun sendJsError(msg: String) {
        runOnUiThread {
            webView.evaluateJavascript("javascript:onTranslationError('$msg')", null)
        }
    }

    // --- GESTIÓN DE ARCHIVOS ---
    private fun importAndOpenBook(uri: Uri) {
        try {
            Toast.makeText(this, "Procesando...", Toast.LENGTH_SHORT).show()
            // Nombre por defecto si falla la lectura
            val fileName = getFileName(uri) ?: "libro.epub"
            val destFile = File(filesDir, fileName)

            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            openBookByName(fileName)
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openBookByName(fileName: String) {
        try {
            val file = File(filesDir, fileName)
            if (file.exists()) {
                val bytes = file.readBytes()
                bookData = Base64.encodeToString(bytes, Base64.NO_WRAP)
                currentFileName = fileName

                runOnUiThread {
                    webView.evaluateJavascript("javascript:notifyBookReady()", null)
                }
            }
        } catch (e: Exception) {
            Log.e("EPUB", "Error: ${e.message}")
        }
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor.use {
                if (it != null && it.moveToFirst()) {
                    val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if(index >= 0) result = it.getString(index)
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) result = result?.substring(cut + 1)
        }
        return result
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts.language = Locale.US
    }

    override fun onDestroy() {
        if (::tts.isInitialized) tts.shutdown()
        super.onDestroy()
    }

    // --- PUENTE JS -> ANDROID ---
    inner class WebAppInterface {
        @JavascriptInterface
        fun speak(text: String) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }

        @JavascriptInterface
        fun getBookData(): String = bookData
        @JavascriptInterface
        fun getCurrentBookName(): String = currentFileName

        @JavascriptInterface
        fun getLibraryList(): String {
            // Buscar solo EPUBs
            val files = filesDir.listFiles { _, name -> name.endsWith(".epub", true) }
            val sb = StringBuilder("[")
            files?.forEachIndexed { index, file ->
                if (index > 0) sb.append(",")
                val safeName = file.name.replace("\"", "\\\"")
                sb.append("{\"name\":\"$safeName\"}")
            }
            sb.append("]")
            return sb.toString()
        }

        @JavascriptInterface
        fun loadBook(name: String) = openBookByName(name)

        @JavascriptInterface
        fun triggerFilePicker() {
            runOnUiThread {
                // Solo permitir seleccionar EPUB
                filePickerLauncher.launch("application/epub+zip")
            }
        }

        // --- NUEVA: SOLICITUD DE TRADUCCIÓN ---
        @JavascriptInterface
        fun requestTranslation(text: String) {
            translateText(text)
        }
    }
}