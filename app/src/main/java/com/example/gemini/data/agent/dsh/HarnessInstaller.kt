package com.example.gemini.data.agent.dsh

import android.content.Context
import android.util.Log
import com.example.gemini.data.local.LocalEnvironmentManager
import java.io.File

/**
 * Prepares the app's own DeepSeek Harness so it can run natively on Android.
 *
 * The harness ships a native addon (`node-addon-require-builtin`) that has no
 * android-arm64 build: the npm package only contains glibc/Linux, macOS and
 * Windows binaries, so booting it on Android dies in
 * `MODULE_NOT_FOUND ... require_builtin.node`. The addon's source is MIT and
 * public, and the app's own toolchain ships clang plus the Node headers, so the
 * launcher builds it for this device once and drops it where
 * `node-addon-native-custom-loader` looks for it
 * (`build/napi/napi-v9-android-arm64/require_builtin.node`).
 *
 * The install itself goes through pnpm: the npm shipped in the app's
 * environment is incomplete (`make-fetch-happen/lib/cache` is missing), while
 * pnpm works.
 */
object HarnessInstaller {
    private const val TAG = "HarnessInstaller"

    /** The `@deepseek-ai/dsh` entry point, relative to the harness directory. */
    private const val DSH_ENTRY = "node_modules/@deepseek-ai/dsh/lib/bin.js"

    /** Where the generated launcher lives, relative to the harness directory. */
    private const val LAUNCH_SCRIPT = "launch.sh"

    private const val ASSET_ROOT = "harness"

    fun harnessDir(context: Context): File = File(context.filesDir, "harness")

    /** True once `@deepseek-ai/dsh` has been installed in the harness directory. */
    fun isInstalled(context: Context): Boolean =
        File(harnessDir(context), DSH_ENTRY).isFile

    /**
     * True when everything the launcher needs is in place: the harness package
     * and the android-arm64 native addon. The addon is built on the device, so
     * this stays false until that build finished.
     */
    fun isReady(context: Context): Boolean {
        if (!isInstalled(context)) return false
        val addon = findAddonDir(File(harnessDir(context), "node_modules")) ?: return false
        return File(addon, "build/napi/napi-v9-android-arm64/require_builtin.node").isFile
    }

    /** The `node-addon-require-builtin` package inside the install. */
    private fun findAddonDir(modules: File): File? {
        val direct = File(modules, "node-addon-require-builtin")
        if (direct.isDirectory) return direct
        // pnpm's isolated layout: node_modules/.pnpm/<pkg>@<version>/node_modules/<pkg>
        val pnpm = File(modules, ".pnpm")
        pnpm.listFiles()?.forEach { child ->
            if (!child.name.startsWith("node-addon-require-builtin@")) return@forEach
            val candidate = File(child, "node_modules/node-addon-require-builtin")
            if (candidate.isDirectory) return candidate
        }
        return null
    }

    /**
     * Extracts the addon sources/headers, writes the pnpm config and generates
     * the launcher script. Cheap after the first call; safe to call every start.
     *
     * @return the launcher script, ready to run with the port as its argument.
     */
    fun prepare(context: Context): File {
        val dir = harnessDir(context)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("Cannot create ${dir.absolutePath}")
        }

        val addonDir = File(dir, ".addon")
        copyAssetTree(context, "$ASSET_ROOT/addon-src/src", File(addonDir, "src"))
        copyAssetTree(context, "$ASSET_ROOT/node-addon-api", File(addonDir, "node-addon-api"))
        copyAsset(context, "$ASSET_ROOT/require_builtin.node", File(addonDir, "prebuilt/require_builtin.node"))

        // pnpm's default layout is a symlink farm; a hoisted tree keeps the
        // native addon at a stable path and matches what the harness expects.
        val npmrc = File(dir, ".npmrc")
        val npmrcText = "node-linker=hoisted\nregistry=https://registry.npmjs.org/\n"
        if (!npmrc.isFile || npmrc.readText() != npmrcText) {
            npmrc.writeText(npmrcText)
        }

        val script = File(dir, LAUNCH_SCRIPT)
        val text = launchScript(
            dir = dir,
            prefix = LocalEnvironmentManager.getPrefixDir(context),
            home = LocalEnvironmentManager.getHomeDir(context)
        )
        if (!script.isFile || script.readText() != text) {
            script.writeText(text)
            script.setExecutable(true, false)
        }
        return script
    }

    /**
     * Installs `@deepseek-ai/dsh` and builds the native addon, then runs
     * `dsh web`. Everything is echoed so the app can show progress and the
     * reason for a failure.
     */
    private fun launchScript(dir: File, prefix: File, home: File): String = """
        |#!/bin/sh
        |# AntiBot's DeepSeek Harness launcher. Generated on every start.
        |set -u
        |
        |DIR="${dir.absolutePath}"
        |PREFIX="${'$'}{PREFIX:-${prefix.absolutePath}}"
        |HOME="${'$'}{HOME:-${home.absolutePath}}"
        |PORT="${'$'}{1:-${DshPreferences.DEFAULT_HARNESS_PORT}}"
        |export PREFIX HOME
        |
        |cd "${'$'}DIR" || { echo "[antibot] cannot enter ${'$'}DIR"; exit 1; }
        |echo "[antibot] harness directory: ${'$'}DIR"
        |
        |# 1. Install the harness on first run.
        |if [ ! -f "${DSH_ENTRY}" ]; then
        |  echo "[antibot] first run: installing @deepseek-ai/dsh@next (this downloads several hundred MB)"
        |  if ! pnpm add @deepseek-ai/dsh@next --ignore-scripts --reporter=append-only; then
        |    echo "[antibot] pnpm add @deepseek-ai/dsh@next failed"
        |    exit 1
        |  fi
        |fi
        |
        |# 2. Build the android-arm64 require_builtin addon the harness needs.
        |ADDON_REL="${'$'}(find node_modules -maxdepth 5 -type d -name node-addon-require-builtin 2>/dev/null | head -n 1)"
        |if [ -z "${'$'}ADDON_REL" ] && [ -d node_modules/node-addon-require-builtin ]; then
        |  ADDON_REL=node_modules/node-addon-require-builtin
        |fi
        |if [ -z "${'$'}ADDON_REL" ]; then
        |  echo "[antibot] node-addon-require-builtin is missing from the install"
        |  exit 1
        |fi
        |ADDON="${'$'}DIR/${'$'}ADDON_REL"
        |TARGET="${'$'}ADDON/build/napi/napi-v9-android-arm64"
        |if [ ! -f "${'$'}TARGET/require_builtin.node" ]; then
        |  echo "[antibot] building the native addon for android-arm64 (one time)"
        |  mkdir -p "${'$'}TARGET" || exit 1
        |  SRCS="${'$'}(find "${'$'}DIR/.addon/src" -name '*.cc' 2>/dev/null)"
        |  if [ -z "${'$'}SRCS" ]; then
        |    SRCS="${'$'}(ls "${'$'}DIR/.addon/src"/*.cc "${'$'}DIR/.addon/src"/*/*.cc "${'$'}DIR/.addon/src"/*/*/*.cc 2>/dev/null)"
        |  fi
        |  if clang++ -shared -fPIC -O2 -fno-rtti -fno-exceptions -fno-strict-aliasing \
        |      -std=c++17 -fvisibility=hidden \
        |      -DNODE_GYP_MODULE_NAME=require_builtin -DUSING_UV_SHARED=1 -DUSING_V8_SHARED=1 \
        |      -DV8_DEPRECATION_WARNINGS=1 -D_GLIBCXX_USE_CXX11_ABI=1 -D_FILE_OFFSET_BITS=64 \
        |      -D_LARGEFILE_SOURCE -D__STDC_FORMAT_MACROS -DNAPI_VERSION=9 \
        |      -DNODE_ADDON_API_DISABLE_CPP_EXCEPTIONS -DNARB_BACKEND=1 -DNARB_PRODUCT=1 \
        |      -DBUILDING_NODE_EXTENSION -D_GLIBCXX_USE_C99_MATH \
        |      -I"${'$'}PREFIX/include/node" -I"${'$'}DIR/.addon/node-addon-api" \
        |      ${'$'}SRCS -llog -o "${'$'}TARGET/require_builtin.node"; then
        |    echo "[antibot] native addon built"
        |  else
        |    echo "[antibot] clang++ failed, using the bundled android-arm64 binary"
        |    cp "${'$'}DIR/.addon/prebuilt/require_builtin.node" "${'$'}TARGET/require_builtin.node" || exit 1
        |  fi
        |fi
        |if ! node -e "require('${'$'}ADDON')" >/dev/null 2>&1; then
        |  echo "[antibot] the native addon does not load:"
        |  node -e "require('${'$'}ADDON')" 2>&1 | head -n 20
        |  exit 1
        |fi
        |
        |# 3. Run the harness.
        |echo "[antibot] starting the harness on port ${'$'}PORT"
        |exec node "${DSH_ENTRY}" web --host 127.0.0.1 --port "${'$'}PORT" --no-open
        |""".trimMargin()

    private fun copyAssetTree(context: Context, assetPath: String, target: File) {
        val children = context.assets.list(assetPath) ?: return
        if (children.isEmpty()) {
            copyAsset(context, assetPath, target)
            return
        }
        if (!target.exists() && !target.mkdirs()) {
            Log.w(TAG, "Cannot create ${target.absolutePath}")
            return
        }
        for (child in children) {
            copyAssetTree(context, "$assetPath/$child", File(target, child))
        }
    }

    private fun copyAsset(context: Context, assetPath: String, target: File) {
        if (target.isFile && target.length() > 0) return
        try {
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot copy asset $assetPath", t)
        }
    }
}
