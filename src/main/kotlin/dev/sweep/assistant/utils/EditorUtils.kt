@file:Suppress("unused")

package dev.sweep.assistant.utils

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.plugins.terminal.TerminalProjectOptionsProvider
import java.io.File
import java.nio.file.InvalidPathException
import kotlin.math.min

/**
 * Efficiently extracts text from a Document with line and character limits.
 * Uses Document's native range-based APIs to avoid loading unnecessary content into memory.
 * @param maxLines Maximum lines to extract, or -1 for no limit
 * @param maxChars Maximum characters to extract, or -1 for no limit
 */
private fun extractTextFromDocument(
    document: Document,
    maxLines: Int,
    maxChars: Int,
): String {
    // If no limits, return full document text
    if (maxLines == -1 && maxChars == -1) {
        return document.text
    }

    val totalLines = document.lineCount
    val linesToExtract = if (maxLines == -1) totalLines else min(totalLines, maxLines)

    // If no lines to extract, return empty
    if (linesToExtract == 0) return ""

    // Calculate the range we need using Document's built-in methods
    val startOffset = 0
    val endLineOffset = document.getLineEndOffset(linesToExtract - 1)

    // Extract only the text range we need (memory efficient!)
    val textRange = TextRange(startOffset, min(endLineOffset, document.textLength))
    var extractedText = document.charsSequence.subSequence(textRange.startOffset, textRange.endOffset).toString()

    // Apply character limit if specified
    val truncatedByChars = maxChars != -1 && extractedText.length > maxChars
    if (truncatedByChars) {
        extractedText = extractedText.substring(0, maxChars)
    }

    // Add truncation message if needed
    val truncatedByLines = maxLines != -1 && totalLines > maxLines
    return if (truncatedByLines || truncatedByChars) {
        buildString {
            append(extractedText)
            append("\n\n[File contents truncated: ")
            if (truncatedByLines) {
                append("showing first $linesToExtract of $totalLines lines")
            }
            if (truncatedByChars) {
                if (truncatedByLines) append(", ")
                append("limited to $maxChars characters")
            }
            append("]")
        }
    } else {
        extractedText
    }
}

/**
 * Simple text truncation for plain strings (not Documents).
 * Used when reading from files directly.
 * @param maxLines Maximum lines to return, or -1 for no limit
 * @param maxChars Maximum characters to return, or -1 for no limit
 */
private fun truncateText(
    text: String,
    maxLines: Int,
    maxChars: Int,
): String {
    // If no limits, return full text
    if (maxLines == -1 && maxChars == -1) {
        return text
    }

    if (text.isEmpty()) return text

    val lines = text.lines()
    val totalLines = lines.size
    val linesToTake = if (maxLines == -1) totalLines else min(totalLines, maxLines)

    // Take up to maxLines
    val linesTruncated = lines.take(linesToTake)
    var joinedText = linesTruncated.joinToString("\n")

    // Apply character limit if specified
    val truncatedByChars = maxChars != -1 && joinedText.length > maxChars
    if (truncatedByChars) {
        joinedText = joinedText.substring(0, maxChars)
    }

    // Add truncation message if needed
    val truncatedByLines = maxLines != -1 && totalLines > maxLines
    return if (truncatedByLines || truncatedByChars) {
        buildString {
            append(joinedText)
            append("\n\n[File contents truncated: ")
            if (truncatedByLines) {
                append("showing first $linesToTake of $totalLines lines")
            }
            if (truncatedByChars) {
                if (truncatedByLines) append(", ")
                append("limited to $maxChars characters")
            }
            append("]")
        }
    } else {
        joinedText
    }
}

/**
 * Memory-efficiently reads a file with size limits.
 * Avoids loading huge files into memory by reading line-by-line when necessary.
 * @param maxLines Maximum lines to read, or -1 for no limit
 * @param maxChars Maximum characters to read, or -1 for no limit
 */
private fun readFileWithLimits(
    file: File,
    maxLines: Int,
    maxChars: Int,
): String? {
    if (!file.exists() || !file.canRead()) return null

    // If no limits, read entire file
    if (maxLines == -1 && maxChars == -1) {
        return file.readText()
    }

    // Only maxChars is limited: read whole file then truncate by characters
    if (maxLines == -1) {
        return truncateText(file.readText(), maxLines, maxChars)
    }

    val fileSize = file.length()
    val estimatedSafeSize = maxLines * 100L // Rough estimate: 100 chars per line

    // If file is small enough, read it all at once and truncate
    return if (fileSize <= estimatedSafeSize) {
        truncateText(file.readText(), maxLines, maxChars)
    } else {
        // File is large, read line by line to avoid memory issues
        val lines = mutableListOf<String>()
        var totalChars = 0
        var reachedLimit = false

        file.bufferedReader().use { reader ->
            var lineCount = 0
            while (lineCount < maxLines) {
                val line = reader.readLine() ?: break

                // Check if adding this line would exceed char limit
                if (maxChars != -1 && totalChars + line.length + 1 > maxChars) {
                    // Take partial line to reach exactly maxChars
                    val remainingChars = maxChars - totalChars - 1
                    if (remainingChars > 0) {
                        lines.add(line.substring(0, min(line.length, remainingChars)))
                    }
                    reachedLimit = true
                    break
                }

                lines.add(line)
                totalChars += line.length + 1 // +1 for newline
                lineCount++
            }
        }

        val result = lines.joinToString("\n")
        if (reachedLimit || lines.size >= maxLines) {
            result + "\n\n[File contents truncated: showing first ${lines.size} lines, limited to $maxChars characters]"
        } else {
            result
        }
    }
}

fun readFile(
    project: Project,
    filePath: String,
    maxLines: Int = -1,
    maxChars: Int = -1,
): String? {
    val application = ApplicationManager.getApplication()
    val maxFileSize = SweepConstants.MAX_FILE_SIZE_BYTES
    val filePath = FileUtil.toSystemIndependentName(filePath)

    fun readFromEditor(): String? {
        // Add project disposal guard to prevent ContainerDisposedException
        if (project.isDisposed) {
            return null
        }

        return FileEditorManager
            .getInstance(project)
            .allEditors
            .mapNotNull { it.file }
            .find { it.path.endsWith(filePath) }
            ?.let { file ->
                if (file.length > maxFileSize) {
                    null
                } else {
                    FileDocumentManager.getInstance().getDocument(file)?.let { document ->
                        extractTextFromDocument(document, maxLines, maxChars)
                    }
                }
            }
    }

    val textFromEditor =
        if (application.isReadAccessAllowed) {
            readFromEditor()
        } else {
            application.runReadAction<String?> { readFromEditor() }
        }

    return textFromEditor
        ?: runCatching {
            val file = File(project.osBasePath, filePath).takeIf { it.exists() && it.canRead() }
            if (file != null && file.length() > maxFileSize) {
                null
            } else {
                file?.let { readFileWithLimits(it, maxLines, maxChars) }
            }
        }.getOrNull()
}

fun readFile(
    project: Project,
    vFile: VirtualFile?,
    maxLines: Int = -1,
    maxChars: Int = -1,
): String? {
    val filePath = relativePath(project, vFile) ?: return null
    return readFile(project, filePath, maxLines, maxChars)
}

fun getVirtualFile(
    project: Project,
    path: String,
    refresh: Boolean = false,
): VirtualFile? {
    val absolutePath = absolutePath(project, path)
    return if (refresh) {
        LocalFileSystem.getInstance().refreshAndFindFileByPath(absolutePath)
    } else {
        LocalFileSystem.getInstance().findFileByPath(absolutePath)
    }
}

fun absolutePath(
    project: Project,
    relativePath: String,
): String {
    if (File(relativePath).isAbsolute) return relativePath
    return File(project.osBasePath ?: ".", relativePath).path
}

fun relativePath(
    project: Project,
    vf: VirtualFile?,
): String? =
    runCatching {
        vf?.path?.takeIf { project.osBasePath != null }?.let {
            File(it).relativeTo(File(project.osBasePath!!)).toString()
        }
    }.getOrNull()?.takeUnless { it.isBlank() || it.startsWith("..") }

fun relativePath(
    basePath: String,
    fullPath: String,
): String? {
    if (BLOCKED_URL_PREFIXES.any { fullPath.startsWith(it) }) {
        return null
    }
    return try {
        val basePathNorm = File(basePath).toPath().normalize().toString()
        val fullPathNorm = File(fullPath).toPath().normalize().toString()
        if (fullPathNorm.startsWith(basePathNorm)) {
            fullPathNorm.substring(basePathNorm.length).trimStart(File.separatorChar)
        } else {
            null
        }
    } catch (e: InvalidPathException) {
        null
    }
}

fun relativePath(
    project: Project,
    fullPath: String,
): String? {
    // Add disposal check before accessing project service
    if (project.isDisposed) {
        return project.osBasePath?.let { basePath -> relativePath(basePath, fullPath) }
    }
    return project.osBasePath?.let { basePath -> relativePath(basePath, fullPath) }
}

fun getCurrentSelectedFile(project: Project): VirtualFile? {
    // Add disposal check before accessing project service
    if (project.isDisposed) {
        return null
    }

    return FileEditorManager
        .getInstance(project)
        .selectedFiles
        .filterNot {
            SweepConstants.diffFiles.contains(it.name)
        }.firstOrNull {
            it.isInLocalFileSystem &&
                    try {
                        VfsUtil.isAncestor(File(project.osBasePath!!).toPath().toFile(), it.toNioPath().toFile(), false)
                    } catch (e: UnsupportedOperationException) {
                        false
                    }
        }
}

/**
 * Detects the shell type for the project by checking the terminal settings shellPath.
 * Returns a simple shell name like "bash", "zsh", "powershell", "fish", etc.
 * Returns empty string if unable to detect.
 */
fun detectShellName(project: Project): String {
    return try {
        // Get shell from terminal settings
        val shellPath =
            TerminalProjectOptionsProvider
                .getInstance(project)
                .shellPath
        extractShellName(shellPath)
    } catch (e: Exception) {
        // If anything fails, return empty string
        ""
    }
}

/**
 * Detects the full shell path configured for the project.
 * Returns null if no shell path is configured.
 */
fun detectShellPath(project: Project): String? {
    return try {
        val shellPath =
            TerminalProjectOptionsProvider
                .getInstance(project)
                .shellPath
        if (shellPath.isNotBlank()) stripQuotes(shellPath) else null
    } catch (e: Exception) {
        null
    }
}

/**
 * Strips surrounding quotes from a path string.
 * Handles both single and double quotes.
 */
private fun stripQuotes(path: String): String {
    val trimmed = path.trim()
    return when {
        trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"') ->
            trimmed.substring(1, trimmed.length - 1)
        trimmed.length >= 2 && trimmed.startsWith('\'') && trimmed.endsWith('\'') ->
            trimmed.substring(1, trimmed.length - 1)
        else -> trimmed
    }
}

/**
 * Extracts a simple shell name from a full shell path.
 */
fun extractShellName(shellPath: String): String {
    if (shellPath.isBlank()) return ""

    // Strip surrounding quotes first (e.g., "C:\Program Files\Git\bin\bash.exe")
    val unquotedPath = stripQuotes(shellPath)

    // Get the filename from the path
    val fileName =
        unquotedPath
            .replace('\\', '/')
            .substringAfterLast('/')
            .lowercase()

    // Remove common extensions
    val baseName =
        fileName
            .removeSuffix(".exe")
            .removeSuffix(".cmd")
            .removeSuffix(".bat")

    // Map common shell names to canonical names
    return when {
        baseName == "pwsh" -> "powershell"
        baseName.contains("powershell") -> "powershell"
        baseName == "cmd" -> "cmd"
        baseName == "bash" -> "bash"
        baseName == "zsh" -> "zsh"
        baseName == "fish" -> "fish"
        baseName == "sh" -> "sh"
        baseName == "dash" -> "dash"
        baseName == "ksh" -> "ksh"
        baseName == "csh" -> "csh"
        baseName == "tcsh" -> "tcsh"
        baseName.startsWith("wsl") -> "wsl"
        else -> baseName
    }
}
