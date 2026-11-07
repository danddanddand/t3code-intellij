package io.github.danddanddand.t3code

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.jcef.JBCefBrowser
import java.awt.Component
import java.awt.Container
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLConnection
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO

/**
 * Lets files dragged from Finder (or any other app) land in the T3 Code composer.
 *
 * Out-of-process JCEF never forwards native drag-and-drop to Chromium (cef_server logs
 * "TODO: implement: RemoteClientHandler::GetDragHandler"), so the page never sees the drop. Instead the
 * pane catches the drop in Swing and replays it inside the page: the files' bytes are streamed in as
 * base64 and a synthetic `drop` event carrying real `File` objects is dispatched on T3 Code's chat drop
 * target, the same handler a browser drop would reach. Folders become `[name](path)` mentions, which is
 * how the T3 Code desktop app inserts them. Images dragged from a browser or Preview arrive as PNG.
 */
internal class T3CodeFileDrop(private val project: Project, private val browser: JBCefBrowser) : DropTargetAdapter() {

    fun install() {
        // Replace JCEF's own drop target (it forwards to cef_server, which ignores drops) on the whole component tree.
        forEachComponent(browser.component) { it.dropTarget = DropTarget(it, DnDConstants.ACTION_COPY, this, true) }
    }

    override fun dragEnter(event: DropTargetDragEvent) {
        if (!canImport(event.currentDataFlavors)) {
            event.rejectDrag()
            return
        }
        event.acceptDrag(DnDConstants.ACTION_COPY)
        val names = runCatching { (event.transferable.fileList() ?: emptyList()).map { it.name } }.getOrDefault(emptyList())
        run("enter(${position(event.location)}, ${jsonArray(names.ifEmpty { listOf("image.png") })})")
    }

    override fun dragOver(event: DropTargetDragEvent) {
        if (canImport(event.currentDataFlavors)) event.acceptDrag(DnDConstants.ACTION_COPY) else event.rejectDrag()
    }

    override fun dragExit(event: DropTargetEvent) = run("leave()")

    override fun drop(event: DropTargetDropEvent) {
        if (!canImport(event.currentDataFlavors)) {
            event.rejectDrop()
            return
        }
        event.acceptDrop(DnDConstants.ACTION_COPY)
        // Transferable data is only readable inside this callback.
        val files = runCatching { event.transferable.fileList() }.getOrNull()
        val image = if (files == null) runCatching { event.transferable.getTransferData(DataFlavor.imageFlavor) as? Image }.getOrNull() else null
        val at = position(event.location)
        event.dropComplete(files != null || image != null)
        if (files == null && image == null) {
            run("leave()")
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                deliver(at, files.orEmpty(), image)
            } catch (e: Throwable) {
                thisLogger().warn("Could not hand dropped files to T3 Code", e)
                run("leave()")
                T3CodeNotifier.error(project, "T3 Code: drop failed", "${e.javaClass.simpleName}: ${e.message ?: "no details"}")
            }
        }
    }

    private fun deliver(at: String, dropped: List<File>, image: Image?) {
        val folders = dropped.filter { it.isDirectory }
        val (files, tooLarge) = dropped.filter { it.isFile }.partition { it.length() <= MAX_FILE_BYTES }
        val metas = mutableListOf<String>()
        files.forEachIndexed { index, file -> metas += send("f$index", file.name, mimeType(file.name), Files.readAllBytes(file.toPath())) }
        if (image != null) metas += send("img", "image.png", "image/png", png(image))
        run("drop($at, [${metas.joinToString(",")}], ${jsonArray(folders.map { it.absolutePath })})")
        if (tooLarge.isNotEmpty()) {
            T3CodeNotifier.warn(
                project, "T3 Code: files skipped",
                "Larger than ${MAX_FILE_BYTES / (1024 * 1024)} MB: ${tooLarge.joinToString { it.name }}",
            )
        }
    }

    /** Streams the bytes into the page in chunks and returns the JSON descriptor the drop call uses. */
    private fun send(id: String, name: String, type: String, bytes: ByteArray): String {
        run("begin(${jsString(id)})")
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + CHUNK_BYTES, bytes.size)
            run("chunk(${jsString(id)}, '${Base64.getEncoder().encodeToString(bytes.copyOfRange(offset, end))}')")
            offset = end
        }
        return "{id:${jsString(id)},name:${jsString(name)},type:${jsString(type)}}"
    }

    private fun run(call: String) {
        val cef = browser.cefBrowser
        cef.executeJavaScript("($BRIDGE)().$call;", cef.url ?: "", 0)
    }

    /** Pointer position as a fraction of the pane, so page zoom and HiDPI scaling don't matter. */
    private fun position(point: java.awt.Point): String {
        val c = browser.component
        val fx = if (c.width > 0) point.x.toDouble() / c.width else 0.5
        val fy = if (c.height > 0) point.y.toDouble() / c.height else 0.5
        return "$fx, $fy"
    }

    private fun canImport(flavors: Array<DataFlavor>) =
        flavors.any { it == DataFlavor.javaFileListFlavor || it == DataFlavor.imageFlavor }

    @Suppress("UNCHECKED_CAST")
    private fun Transferable.fileList(): List<File>? =
        if (isDataFlavorSupported(DataFlavor.javaFileListFlavor)) getTransferData(DataFlavor.javaFileListFlavor) as? List<File> else null

    private fun png(image: Image): ByteArray {
        val buffered = image as? BufferedImage
            ?: BufferedImage(image.getWidth(null).coerceAtLeast(1), image.getHeight(null).coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
                .also { it.createGraphics().apply { drawImage(image, 0, 0, null); dispose() } }
        return ByteArrayOutputStream().also { ImageIO.write(buffered, "png", it) }.toByteArray()
    }

    private fun mimeType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MIME_BY_EXTENSION[ext] ?: URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
    }

    private fun forEachComponent(root: Component, action: (Component) -> Unit) {
        action(root)
        if (root is Container) root.components.forEach { forEachComponent(it, action) }
    }

    private fun jsString(value: String) = "\"" + StringUtil.escapeStringCharacters(value)
        .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029") + "\""

    private fun jsonArray(values: List<String>) = values.joinToString(",", "[", "]") { jsString(it) }

    companion object {
        private const val MAX_FILE_BYTES = 50L * 1024 * 1024
        private const val CHUNK_BYTES = 768 * 1024

        private val MIME_BY_EXTENSION = mapOf(
            "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif",
            "webp" to "image/webp", "heic" to "image/heic", "heif" to "image/heif", "bmp" to "image/bmp",
            "tif" to "image/tiff", "tiff" to "image/tiff", "svg" to "image/svg+xml", "pdf" to "application/pdf",
            "txt" to "text/plain", "md" to "text/markdown", "json" to "application/json", "csv" to "text/csv",
        )

        /**
         * Page-side half, (re)created on demand so it survives reloads. T3 Code's chat column listens for
         * file drags on `[data-chat-workspace-drop-target]` and the composer accepts mention drags of type
         * `application/x-t3code-composer-mention`; both are plain DOM drag events, so synthetic ones work.
         */
        private val BRIDGE = """
            function () {
              if (window.__t3codeDrop) return window.__t3codeDrop;
              const parts = new Map();
              let last = null;
              const dropTarget = () => document.querySelector('[data-chat-workspace-drop-target="true"]');
              const targetAt = (fx, fy) => {
                const zone = dropTarget();
                if (!zone) return null;
                const el = document.elementFromPoint(fx * innerWidth, fy * innerHeight);
                return el && zone.contains(el) ? el : zone;
              };
              const fire = (target, type, dt, fx, fy) => target.dispatchEvent(new DragEvent(type, {
                bubbles: true, cancelable: true, composed: true, dataTransfer: dt,
                clientX: fx * innerWidth, clientY: fy * innerHeight,
              }));
              const decode = (chunks) => chunks.map((b64) => {
                const bin = atob(b64);
                const out = new Uint8Array(bin.length);
                for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
                return out;
              });
              const mention = (path) => {
                const base = path.slice(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
                const label = base.replaceAll('\\', '\\\\').replaceAll('[', '\\[').replaceAll(']', '\\]');
                const dest = encodeURI(path).replaceAll('(', '%28').replaceAll(')', '%29')
                  .replaceAll('#', '%23').replaceAll('?', '%3F').replaceAll('\\', '%5C');
                return '[' + label + '](' + dest + ')';
              };
              return window.__t3codeDrop = {
                enter(fx, fy, names) {
                  const target = targetAt(fx, fy);
                  if (!target) return;
                  const dt = new DataTransfer();
                  for (const name of names) dt.items.add(new File([], name));
                  last = target;
                  fire(target, 'dragenter', dt, fx, fy);
                  fire(target, 'dragover', dt, fx, fy);
                },
                leave() {
                  if (last) fire(last, 'dragleave', new DataTransfer(), 0.5, 0.5);
                  last = null;
                },
                begin(id) { parts.set(id, []); },
                chunk(id, b64) { parts.get(id).push(b64); },
                drop(fx, fy, files, folders) {
                  const target = targetAt(fx, fy);
                  last = null;
                  if (!target) {
                    parts.clear();
                    return;
                  }
                  if (files.length > 0) {
                    const dt = new DataTransfer();
                    for (const f of files) dt.items.add(new File(decode(parts.get(f.id) || []), f.name, { type: f.type }));
                    parts.clear();
                    fire(target, 'dragover', dt, fx, fy);
                    fire(target, 'drop', dt, fx, fy);
                  } else {
                    fire(target, 'dragleave', new DataTransfer(), fx, fy);
                  }
                  const form = document.querySelector('[data-chat-composer-form="true"]');
                  for (const path of folders) {
                    if (!form) break;
                    const dt = new DataTransfer();
                    dt.setData('application/x-t3code-composer-mention', mention(path));
                    fire(form, 'drop', dt, fx, fy);
                  }
                },
              };
            }
        """.trimIndent()
    }
}
