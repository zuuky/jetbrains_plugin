package dev.sweep.assistant.utils

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.sweep.assistant.settings.SweepSettings
import java.io.File

private val logger = Logger.getInstance("dev.sweep.assistant.utils.FileUtils")

// URL prefixes that should be blocked from file operations
val BLOCKED_URL_PREFIXES = listOf("gitlabmr:")

/**
 * 判断一个（相对）路径是否命中排除模式（gitignore 风格的简化实现）。
 *
 * 模式规则：
 * - 模式不含 '/'（裸名模式）：匹配路径中的【任意一段】（文件或文件夹名）
 *   - 因此文件夹名会递归命中其下所有子文件（node_modules → node_modules/a/b/c.ts）
 *   - 以 "双星" 结尾：段名前缀匹配（scratch 双星 → scratch.kt、scratch_test.py、scratchDir/ 等）
 *   - 其余：段名等于模式；若模式以 '.' 开头，也接受 "段名以模式结尾"（.env 匹配 a.env，向后兼容）
 * - 模式含 '/'（路径模式）：
 *   - 结尾 '/'（目录模式）：路径位于该目录下即命中（node_modules/ 匹配 node_modules/a/b.ts）
 *   - "双星/name" 形式：等价于裸名模式 name
 *   - "dir/name"：路径等于或在 "dir/name/" 之下即命中
 * - 通配符：单个段内支持 '*'（任意非路径分隔符字符）与 '?'（单个字符）；'**' 匹配任意层目录
 * - 匹配大小写不敏感。
 *
 * @param relativePath 相对路径，如 src/main/a.env 或 node_modules/pkg/a.js
 * @param pattern 排除模式，如 .env、node_modules、node_modules/、*.log、build/双星
 */
fun matchesExclusionPattern(
    relativePath: String,
    pattern: String,
): Boolean {
    if (pattern.isBlank()) return false
    val p = pattern.trim().replace('\\', '/').trimStart('/')
    if (p.isEmpty() || p == "/") return false

    val path = relativePath.replace('\\', '/').trimStart('/')
    if (path.isEmpty()) return false

    val core = p.trimEnd('/')

    // ---- 裸名模式（不含 '/'）：匹配路径中的任意一段（文件或文件夹名） ----
    if (!core.contains('/')) {
        val segments = path.split('/').filter { it.isNotEmpty() }
        return segments.any { seg -> matchSegment(seg, core) }
    }

    // ---- 含 '/' 的路径模式（gitignore 简化）----
    // "**/name" 等价于裸名匹配
    if (core.startsWith("**/")) {
        return matchesExclusionPattern(path, core.substringAfter("**/"))
    }
    if (core.endsWith("/**")) {
        // "dir/**"：路径位于 dir 之下
        val dir = core.removeSuffix("/**").trimEnd('/')
        return path == dir || path.startsWith("$dir/")
    }
    // "dir/name" 或含通配符的完整路径模式
    return path == core || path.startsWith("$core/") || globMatch(path, core)
}

/**
 * 匹配单个路径段（文件名或文件夹名）。
 */
private fun matchSegment(
    segment: String,
    pattern: String,
): Boolean {
    val hasDoubleStar = pattern.endsWith("**")
    val name = if (hasDoubleStar) pattern.removeSuffix("**") else pattern
    return when {
        hasDoubleStar -> segment.startsWith(name, ignoreCase = true)
        name.contains("*") || name.contains("?") -> globMatch(segment, name)
        name.startsWith(".") -> segment.equals(name, ignoreCase = true) || segment.endsWith(name, ignoreCase = true)
        else -> segment.equals(name, ignoreCase = true)
    }
}

/**
 * 将 gitignore 风格通配符模式（* 任意非分隔符字符、** 任意层、? 单字符）转为正则并整体匹配。
 */
private fun globMatch(
    text: String,
    pattern: String,
): Boolean {
    val regex = buildString {
        append('^')
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when (c) {
                '*' -> {
                    if (i + 1 < pattern.length && pattern[i + 1] == '*') {
                        append(".*")
                        i += 2
                    } else {
                        append("[^/]*")
                        i++
                    }
                }

                '?' -> {
                    append("[^/]")
                    i++
                }

                else -> {
                    if (c in ".+(){}[]\\^$|") append('\\')
                    append(c)
                    i++
                }
            }
        }
        append('$')
    }
    return Regex(regex, RegexOption.IGNORE_CASE).matches(text)
}

/**
 * 读取项目根目录 .gitignore 中的排除模式（正数行，忽略注释/空行/取反行）。
 */
fun readGitignorePatterns(project: Project): Set<String> {
    val basePath = project.basePath ?: return emptySet()
    val gitignore = File(basePath, ".gitignore")
    if (!gitignore.isFile || !gitignore.canRead()) return emptySet()
    return try {
        gitignore.bufferedReader().useLines { lines ->
            lines
                .map { it.trim() }
                .filter { line ->
                    line.isNotEmpty() &&
                            !line.startsWith("#") &&
                            !line.startsWith("!") // 取反（不排除）行不参与
                }
                .map { it.replace('\\', '/').trimStart('/') }
                .filter { it.isNotEmpty() && it != "/" && it.length <= 200 }
                .toSet()
        }
    } catch (e: Exception) {
        logger.warn("Failed to read .gitignore: ${e.message}")
        emptySet()
    }
}

/**
 * 判断文件是否应被排除在 next-edit 自动补全之外。
 * 有效模式 = 用户配置的排除模式 +（可选）项目 .gitignore 模式，支持文件与文件夹递归匹配。
 *
 * @param project 当前项目（用于读取 .gitignore 与计算相对路径）
 * @param filePath 文件路径（绝对或相对项目根均可）
 */
fun shouldExcludeFromAutocomplete(
    project: Project,
    filePath: String,
): Boolean {
    val settings = SweepSettings.getInstance()
    val userPatterns = settings.allAutocompleteExclusionPatterns()
    // 仅当“自动排除 .gitignore”开启时并入 .gitignore 模式
    val gitignorePatterns = if (settings.excludeGitignorePatterns) readGitignorePatterns(project) else emptySet()
    val allPatterns = userPatterns + gitignorePatterns
    if (allPatterns.isEmpty()) return false

    val relative = relativePath(project, filePath) ?: filePath
    return allPatterns.any { pattern ->
        matchesExclusionPattern(relative, pattern)
    }
}
