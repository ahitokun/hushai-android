package app.hushai.android

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer.Page
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

object PdfReader {

    fun extractFromUri(context: Context, uri: Uri): String {
        val mimeType = context.contentResolver.getType(uri) ?: ""
        val name = uri.lastPathSegment ?: ""
        Log.d("PdfReader", "File: $name, MIME: $mimeType")
        return when {
            mimeType.contains("wordprocessingml") || mimeType.contains("msword") || name.endsWith(".docx", true) || name.endsWith(".doc", true) -> extractDocx(context, uri)
            mimeType.startsWith("text/") || name.endsWith(".txt", true) || name.endsWith(".csv", true) || name.endsWith(".md", true) -> extractPlainText(context, uri)
            else -> extractText(context, uri) // PDF fallback
        }
    }

    private fun extractDocx(context: Context, uri: Uri): String {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return "Could not open file"
            val zipStream = java.util.zip.ZipInputStream(inputStream)
            val result = StringBuilder()
            var entry = zipStream.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    val xml = zipStream.bufferedReader().readText()
                    // Extract text between <w:t> tags
                    val pattern = Regex("<w:t[^>]*>([^<]*)</w:t>")
                    pattern.findAll(xml).forEach { match ->
                        result.append(match.groupValues[1])
                        result.append(" ")
                    }
                    break
                }
                entry = zipStream.nextEntry
            }
            zipStream.close()
            val text = result.toString().trim()
            if (text.isEmpty()) "Could not extract text from this Word document." else text
        } catch (e: Exception) {
            Log.e("PdfReader", "Error reading docx", e)
            "Error reading Word document: ${e.message}"
        }
    }

    private fun extractPlainText(context: Context, uri: Uri): String {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return "Could not open file"
            val text = inputStream.bufferedReader().readText()
            inputStream.close()
            text
        } catch (e: Exception) {
            "Error reading file: ${e.message}"
        }
    }


    /**
     * Extract text from a PDF using Android's built-in PdfRenderer.
     * Note: PdfRenderer renders pages as images, not text.
     * For actual text extraction, we use the content as-is since most
     * modern PDFs have selectable text embedded.
     *
     * For a production app, we'd use a library like Apache PDFBox Android.
     * For now, we read the raw bytes and extract text between stream markers.
     */
    fun extractText(context: Context, uri: Uri): String {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val bytes = inputStream?.readBytes() ?: return "Could not read file"
            inputStream.close()

            // Simple text extraction from PDF bytes
            // PDFs store text in various ways — this catches the most common
            val text = String(bytes, Charsets.ISO_8859_1)
            val extracted = StringBuilder()

            // Extract text between BT (begin text) and ET (end text) markers
            val btPattern = Regex("""\(([^)]*)\)""")
            val matches = btPattern.findAll(text)
            for (match in matches) {
                val chunk = match.groupValues[1]
                // Filter out binary garbage — only keep printable text
                val clean = chunk.filter { it.isLetterOrDigit() || it.isWhitespace() || it in ".,;:!?'-\"@#\$%&*()[]{}/<>+=_" }
                if (clean.length > 2) {
                    extracted.append(clean).append(" ")
                }
            }

            val result = extracted.toString().trim()
            // Check for garbage — count printable vs non-printable
            val printableRatio = if (result.isNotEmpty()) result.count { it.isLetterOrDigit() || it.isWhitespace() || it in ".,;:!?'-\"@#\$%&*()" }.toFloat() / result.length else 0f
            val avgWordLen = if (result.contains(" ")) result.split(" ").filter { it.isNotEmpty() }.map { it.length }.average() else 0.0
            if (result.length < 50 || printableRatio < 0.85f || avgWordLen > 15 || avgWordLen < 2.0) {
                "[OCR_NEEDED]"  // Signal to try OCR
            } else {
                // Clean up: remove duplicate spaces, PDF artifacts, short garbage fragments
                result.replace(Regex("""\s{2,}"""), " ")
                    .replace(Regex("""[\x00-\x1F]"""), "")
                    .trim()
            }
        } catch (e: Exception) {
            Log.e("PdfReader", "Error reading PDF", e)
            "Error reading PDF: ${e.message}"
        }
    }

    suspend fun extractWithOCR(context: Context, uri: Uri, maxPages: Int = 10, maxChars: Int = 10000): String {
        return try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return "Could not open PDF"
            val renderer = PdfRenderer(pfd)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val result = StringBuilder()
            val pages = minOf(renderer.pageCount, maxPages)

            for (i in 0 until pages) {
                val page = renderer.openPage(i)
                val scale = 150f / 72f // 150 DPI — sufficient for ML Kit, ~2x faster than 300 DPI
                val bitmap = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                val image = InputImage.fromBitmap(bitmap, 0)
                try {
                    val text = recognizer.process(image).await()
                    result.append(text.text).append("\n")
                } catch (e: Exception) {
                    Log.e("PdfReader", "OCR failed on page $i", e)
                }
                bitmap.recycle()
                if (result.length >= maxChars) {
                    Log.d("PdfReader", "Hit char budget at page ${i + 1}/$pages")
                    break
                }
            }

            renderer.close()
            pfd.close()
            recognizer.close()

            val text = result.toString().trim()
            if (text.isEmpty()) "⚠️ Could not extract text from this PDF. It may be encrypted or contain only images without readable text." else text
        } catch (e: Exception) {
            Log.e("PdfReader", "OCR extraction failed", e)
            "Error reading PDF with OCR: ${'$'}{e.message}"
        }
    }

    fun getPageCount(context: Context, uri: Uri): Int {
        return try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return 0
            val renderer = PdfRenderer(pfd)
            val count = renderer.pageCount
            renderer.close()
            pfd.close()
            count
        } catch (e: Exception) { 0 }
    }
}
