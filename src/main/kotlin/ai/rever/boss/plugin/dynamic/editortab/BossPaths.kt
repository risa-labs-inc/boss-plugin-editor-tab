package ai.rever.boss.plugin.dynamic.editortab

import java.io.File

/**
 * Where this plugin's own settings files live.
 *
 * The host splits its state directory by mode - `~/.boss` normally,
 * `~/.boss_debug` when started with `-Dboss.dev.mode=true` (or
 * `BOSS_DEV_MODE=true`), which is how `./gradlew run` launches it. The host's
 * own `ai.rever.boss.plugin.pathutils.BossDirectories` encodes that rule, but
 * it is not part of the plugin api surface, so the rule is mirrored here.
 *
 * This matters: both of this plugin's settings files were hardcoded to
 * `~/.boss/...`, so on a dev build they read a file the app never writes.
 * Editor settings silently reverted, and the tab-completion kill switch and
 * model override had no effect at all.
 */
internal object BossPaths {

    /** True when the host is running against its dev state directory. */
    val isDevMode: Boolean
        get() =
            isTruthy(System.getProperty("boss.dev.mode")) ||
                isTruthy(System.getenv("BOSS_DEV_MODE"))

    /** `~/.boss` or `~/.boss_debug`, matching the running host. */
    val root: File
        get() = File(System.getProperty("user.home"), if (isDevMode) ".boss_debug" else ".boss")

    /** A settings file under the active state directory, e.g. `editor-settings.json`. */
    fun settingsFile(name: String): File = File(root, name)

    private fun isTruthy(value: String?): Boolean = value?.trim()?.lowercase() == "true"
}
