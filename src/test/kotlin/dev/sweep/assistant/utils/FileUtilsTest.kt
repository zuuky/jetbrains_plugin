package dev.sweep.assistant.utils

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 验证 next-edit 排除模式匹配逻辑：
 * - 文件夹（含递归子文件）排除
 * - 裸名 / 路径模式
 * - gitignore 通配符（*、**、?）
 * - 大小写不敏感
 */
class FileUtilsTest {

    @Test
    fun `裸名文件夹递归排除`() {
        assertTrue(matchesExclusionPattern("node_modules/a/b/c.ts", "node_modules"))
        assertTrue(matchesExclusionPattern("node_modules/pkg/index.js", "node_modules"))
        assertTrue(matchesExclusionPattern("a/node_modules/deep/file.js", "node_modules"))
        assertTrue(matchesExclusionPattern("dist/sub/dist/lib/x.js", "dist"))
    }

    @Test
    fun `目录模式（结尾斜杠）递归排除`() {
        assertTrue(matchesExclusionPattern("node_modules/a/b/c.ts", "node_modules/"))
        assertTrue(matchesExclusionPattern("build/output/app.jar", "build/"))
    }

    @Test
    fun `路径模式匹配`() {
        assertTrue(matchesExclusionPattern("generated/code/x.kt", "generated/code"))
        assertTrue(matchesExclusionPattern("generated/code/deep/y.kt", "generated/code"))
        assertTrue(matchesExclusionPattern("generated/code/x.kt", "generated/code/"))
        assertFalse(matchesExclusionPattern("generated/other/y.kt", "generated/code"))
    }

    @Test
    fun `dir双星 匹配目录下所有文件`() {
        assertTrue(matchesExclusionPattern("build/a/b/c.txt", "build/**"))
        assertTrue(matchesExclusionPattern("build/x.txt", "build/**"))
    }

    @Test
    fun `双星斜杠name 等价于裸名`() {
        assertTrue(matchesExclusionPattern("src/node_modules/x.js", "**/node_modules"))
        assertTrue(matchesExclusionPattern("node_modules/x.js", "**/node_modules"))
    }

    @Test
    fun `裸名文件模式`() {
        assertTrue(matchesExclusionPattern("src/main/a.env", ".env"))
        assertTrue(matchesExclusionPattern(".env", ".env"))
        assertTrue(matchesExclusionPattern("config/.dev.env", ".env"))
        assertFalse(matchesExclusionPattern("src/main/application.properties", ".env"))
    }

    @Test
    fun `段名前缀双星`() {
        assertTrue(matchesExclusionPattern("scratch.kt", "scratch**"))
        assertTrue(matchesExclusionPattern("scratch_test.py", "scratch**"))
        assertTrue(matchesExclusionPattern("scratchBackup/file.txt", "scratch**"))
        assertFalse(matchesExclusionPattern("testScratch.kt", "scratch**"))
    }

    @Test
    fun `gitignore 星号通配符`() {
        assertTrue(matchesExclusionPattern("debug.log", "*.log"))
        assertTrue(matchesExclusionPattern("logs/app.min.log", "*.log"))
        assertTrue(matchesExclusionPattern("out/app.min.js", "*.min.js"))
        assertFalse(matchesExclusionPattern("app.js", "*.min.js"))
        assertTrue(matchesExclusionPattern("sub/dir/readme.md", "**/*.md"))
        assertTrue(matchesExclusionPattern("readme.md", "**/*.md"))
    }

    @Test
    fun `问号通配符`() {
        assertTrue(matchesExclusionPattern("a1b.txt", "a?b.txt"))
        assertFalse(matchesExclusionPattern("ab.txt", "a?b.txt"))
    }

    @Test
    fun `大小写不敏感`() {
        assertTrue(matchesExclusionPattern("src/NODE_MODULES/x.js", "node_modules"))
        assertTrue(matchesExclusionPattern("src/main/A.Env", ".env"))
    }

    @Test
    fun `不应误伤`() {
        assertFalse(matchesExclusionPattern("src/app/main.ts", "node_modules"))
        assertFalse(matchesExclusionPattern("src/app/main.ts", "build/"))
        assertFalse(matchesExclusionPattern("src/app/main.ts", "*.log"))
    }

    @Test
    fun `gitignore 空白与根斜杠处理`() {
        assertTrue(matchesExclusionPattern("node_modules/x.js", "/node_modules"))
        assertTrue(matchesExclusionPattern("build/a.txt", " build/** ".trim()))
        assertFalse(matchesExclusionPattern("anything/a.txt", "/"))
    }
}
