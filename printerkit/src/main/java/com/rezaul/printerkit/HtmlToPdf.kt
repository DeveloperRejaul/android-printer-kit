package com.rezaul.printerkit

import android.content.Context
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import java.io.FileOutputStream

/**
 * Converts an HTML string into a PDF file by rendering it in an off-screen WebView
 * and drawing that WebView straight onto a [PdfDocument] page - no external library.
 *
 * (Android's own `android.print` framework can't be driven headlessly for this: the
 * `PrintDocumentAdapter.LayoutResultCallback` / `WriteResultCallback` types it requires
 * have package-private constructors that app code isn't allowed to call, so this uses
 * `View.draw(Canvas)` instead, which is public.)
 *
 * One WebView is created and reused for two passes:
 * 1. [measureThenRender] loads the HTML at a generously tall layout, neutralizes any
 *    viewport-relative sizing (e.g. `min-height: 100vh` used to center a receipt-style
 *    card on a full screen - left as-is, that reports the *whole artificial viewport*
 *    as the content height, printing as a real page with the actual content just a
 *    small island surrounded by blank space), and reads the true natural content
 *    height via JS `scrollHeight`.
 * 2. [renderFinal] resizes the *same* WebView to that final height and reloads the
 *    HTML (a fresh navigation forces a full, correct layout/paint pass at the new
 *    bounds - simply resizing then drawing without reloading raced with Chromium's
 *    software-layer repaint in an earlier version and produced a solid black page).
 *    [drawToPdf] then draws it, once settled.
 *
 * WebView requires the main thread, so [convert] can be called from any thread - it
 * hops to the main thread internally - and [onResult] is always delivered on the
 * main thread.
 */
object HtmlToPdf {

    private const val TAG = "HtmlToPdf"

    // Generous temporary layout height for the measurement pass only, so nothing is
    // viewport-clipped while reading the natural content height.
    private const val MEASURE_HEIGHT_PX = 20000

    // Safety net for a stuck WebView (see convert()) - generous enough for slow devices
    // or remote font loading, short enough not to look hung to the caller.
    private const val CONVERT_TIMEOUT_MS = 15000L

    // Resets any viewport-relative height (e.g. `min-height: 100vh`) back to fitting
    // its own content, without touching layout/display properties that might change
    // horizontal centering or other intended styling.
    private const val NEUTRALIZE_VIEWPORT_HEIGHT_JS = """
        document.documentElement.style.height = 'auto';
        document.documentElement.style.minHeight = 'auto';
        document.body.style.height = 'auto';
        document.body.style.minHeight = 'auto';
    """

    /**
     * @param pageWidthDp layout width in dp (CSS px) the HTML is rendered at - controls
     *   how large text/content look relative to the page, not the final print size
     *   (that's `printerWidthDots` on [BluetoothPrinter.printHtml]/[BluetoothPrinter.printPdf],
     *   which downscales this).
     * @param heightDp if set, forces the page to exactly this height (dp) and skips
     *   auto-measuring the content - use this if the caller already knows the right
     *   height, or wants to force a fixed one.
     * @param minPageHeightDp ignored if [heightDp] is set. Otherwise a height floor in
     *   dp for auto-measured content - the page normally just fits the real content, so
     *   this only matters for very short/empty HTML.
     */
    fun convert(
        context: Context,
        html: String,
        outputPath: String,
        pageWidthDp: Int = 412,
        heightDp: Int? = null,
        minPageHeightDp: Int = 50,
        onResult: (File?) -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        val pageWidthPx = (pageWidthDp * density).toInt()

        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post {
            var webView: WebView? = null
            try {
                webView = WebView(context)
                // Off-screen/unattached WebViews using the default hardware layer can
                // cause a brief black flash when their GPU surface is (re)allocated.
                // Software rendering avoids that, and is plenty fast for a single print render.
                webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

                // Safety net: if onPageFinished/evaluateJavascript never fires (e.g. the
                // WebView's renderer process dies), the WebView would otherwise never be
                // destroy()'d - a leaked live WebView instance left running in the
                // background is a known source of unrelated rendering glitches elsewhere
                // in the app (it keeps compositing). Force cleanup after a timeout.
                val timeoutRunnable = Runnable {
                    Log.e(TAG, "convert timed out - destroying WebView and giving up")
                    webView?.destroy()
                    onResult(null)
                }
                mainHandler.postDelayed(timeoutRunnable, CONVERT_TIMEOUT_MS)
                val guardedOnResult: (File?) -> Unit = { file ->
                    mainHandler.removeCallbacks(timeoutRunnable)
                    onResult(file)
                }

                if (heightDp != null) {
                    renderFinal(webView, html, outputPath, pageWidthPx, (heightDp * density).toInt(), mainHandler, guardedOnResult)
                } else {
                    val minPageHeightPx = (minPageHeightDp * density).toInt()
                    measureThenRender(webView, context, html, outputPath, pageWidthPx, minPageHeightPx, mainHandler, guardedOnResult)
                }
            } catch (e: Exception) {
                Log.e(TAG, "convert failed", e)
                webView?.destroy()
                mainHandler.post { onResult(null) }
            }
        }
    }

    private fun measureThenRender(
        webView: WebView,
        context: Context,
        html: String,
        outputPath: String,
        pageWidthPx: Int,
        minPageHeightPx: Int,
        mainHandler: Handler,
        onResult: (File?) -> Unit
    ) {
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                webView.evaluateJavascript(
                    "$NEUTRALIZE_VIEWPORT_HEIGHT_JS document.documentElement.scrollHeight;"
                ) { result ->
                    val cssHeight = result?.trim('"')?.toDoubleOrNull()
                    val density = context.resources.displayMetrics.density
                    val measuredPx = cssHeight?.let { (it * density).toInt() }
                    val pageHeightPx = maxOf(minPageHeightPx, measuredPx ?: minPageHeightPx)
                    renderFinal(webView, html, outputPath, pageWidthPx, pageHeightPx, mainHandler, onResult)
                }
            }
        }
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(pageWidthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(MEASURE_HEIGHT_PX, View.MeasureSpec.EXACTLY)
        )
        webView.layout(0, 0, pageWidthPx, MEASURE_HEIGHT_PX)
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    private fun renderFinal(
        webView: WebView,
        html: String,
        outputPath: String,
        pageWidthPx: Int,
        pageHeightPx: Int,
        mainHandler: Handler,
        onResult: (File?) -> Unit
    ) {
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                // Also neutralize here in case this exact height still triggers
                // viewport-relative CSS, and the JS round-trip itself gives
                // Chromium's compositor an extra tick to settle before we draw.
                webView.evaluateJavascript(NEUTRALIZE_VIEWPORT_HEIGHT_JS) {
                    drawToPdf(webView, outputPath, pageWidthPx, pageHeightPx, mainHandler, onResult)
                }
            }
        }
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(pageWidthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(pageHeightPx, View.MeasureSpec.EXACTLY)
        )
        webView.layout(0, 0, pageWidthPx, pageHeightPx)
        // A full reload (not just a resize) forces Chromium to do a fresh, correct
        // layout/paint pass at the new bounds - see class doc.
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    private fun drawToPdf(
        webView: WebView,
        outputPath: String,
        pageWidthPx: Int,
        pageHeightPx: Int,
        mainHandler: Handler,
        onResult: (File?) -> Unit
    ) {
        try {
            val document = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidthPx, pageHeightPx, 1).create()
            val page = document.startPage(pageInfo)
            // PdfDocument's page canvas isn't pre-filled - unpainted pixels default to
            // black in the resulting PDF (it has no real alpha channel), so anything the
            // WebView doesn't explicitly paint (e.g. a transparent CSS background) would
            // otherwise print as a solid black background.
            page.canvas.drawColor(Color.WHITE)
            webView.draw(page.canvas)
            document.finishPage(page)

            val file = File(outputPath)
            FileOutputStream(file).use { document.writeTo(it) }
            document.close()

            mainHandler.post { onResult(file) }
        } catch (e: Exception) {
            Log.e(TAG, "drawToPdf failed", e)
            mainHandler.post { onResult(null) }
        } finally {
            webView.destroy()
        }
    }
}
