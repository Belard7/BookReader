package com.example.bookreader

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
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

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var webView: WebView
    private lateinit var tts: TextToSpeech

    // Variables para guardar el libro actual en memoria
    private var pdfData: String = ""
    private var currentFileName: String = ""

    // El lanzador para seleccionar archivos PDF
    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            importAndOpenBook(uri)
        } else {
            Toast.makeText(this, "No se seleccionó ningún archivo", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Ocultar el botón nativo antiguo si existe en el XML
        val oldFab = findViewById<View>(R.id.btnLoadPdf)
        if (oldFab != null) {
            oldFab.visibility = View.GONE
        }

        // Inicializar motor de voz
        tts = TextToSpeech(this, this)

        // Configurar navegador
        webView = findViewById(R.id.webView)
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true

        // Habilitar depuración para ver errores en Logcat
        WebView.setWebContentsDebuggingEnabled(true)

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()

        // Conectar el puente entre HTML y Android
        webView.addJavascriptInterface(WebAppInterface(), "AndroidInterface")

        // Cargar la interfaz
        webView.loadUrl("file:///android_asset/reader.html")
    }

    // Función para copiar el libro seleccionado a la carpeta de la App
    private fun importAndOpenBook(uri: Uri) {
        try {
            Toast.makeText(this, "Importando libro...", Toast.LENGTH_SHORT).show()
            val fileName = getFileName(uri) ?: "libro_${System.currentTimeMillis()}.pdf"
            val destFile = File(filesDir, fileName)

            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            // Abrir el libro recién importado
            openBookByName(fileName)

        } catch (e: Exception) {
            Toast.makeText(this, "Error importando: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // Función para leer un libro guardado y enviarlo al HTML
    private fun openBookByName(fileName: String) {
        try {
            val file = File(filesDir, fileName)
            if (file.exists()) {
                val bytes = file.readBytes()
                // Convertir a Base64 para enviarlo por JS
                pdfData = Base64.encodeToString(bytes, Base64.NO_WRAP)
                currentFileName = fileName

                // Avisar al HTML que ya tenemos los datos listos
                runOnUiThread {
                    webView.evaluateJavascript("javascript:notifyPdfReady()", null)
                }
            }
        } catch (e: Exception) {
            Log.e("PDF", "Error abriendo libro: ${e.message}")
            Toast.makeText(this, "Error abriendo libro", Toast.LENGTH_SHORT).show()
        }
    }

    // Utilidad para obtener el nombre real del archivo
    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor.use {
                if (it != null && it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
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

    // --- CLASE PUENTE (LO QUE LLAMA EL HTML) ---
    inner class WebAppInterface {

        @JavascriptInterface
        fun speak(text: String) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }

        @JavascriptInterface
        fun getPdfData(): String = pdfData

        @JavascriptInterface
        fun getCurrentBookName(): String = currentFileName

        // Obtener lista de libros para la biblioteca
        @JavascriptInterface
        fun getLibraryList(): String {
            val files = filesDir.listFiles { _, name -> name.endsWith(".pdf", true) }
            val sb = StringBuilder("[")
            files?.forEachIndexed { index, file ->
                if (index > 0) sb.append(",")
                // Escapar comillas para evitar romper el JSON
                val safeName = file.name.replace("\"", "\\\"")
                sb.append("{\"name\":\"$safeName\"}")
            }
            sb.append("]")
            return sb.toString()
        }

        @JavascriptInterface
        fun loadBook(name: String) {
            openBookByName(name)
        }

        @JavascriptInterface
        fun resetApp() {
            // Método vacío por si el HTML lo llama
        }

        // --- ARREGLO DEL BOTÓN: Ejecutar en hilo principal ---
        @JavascriptInterface
        fun triggerFilePicker() {
            runOnUiThread {
                try {
                    filePickerLauncher.launch("application/pdf")
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Error lanzando selector: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}