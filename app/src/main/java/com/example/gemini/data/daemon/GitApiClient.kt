package com.example.gemini.data.daemon

import android.util.Log
import com.example.gemini.data.preferences.AuthPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class GitFileStatus(
    val path: String,
    val oldPath: String? = null,
    val status: String, // "M", "A", "D", "U", "R"
    val staged: Boolean
)

data class GitStatusResponse(
    val isGitRepo: Boolean = true,
    val branch: String = "HEAD",
    val tracking: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
    val unpushedCount: Int = 0,
    val stagedFiles: List<GitFileStatus> = emptyList(),
    val unstagedFiles: List<GitFileStatus> = emptyList(),
    val untrackedFiles: List<GitFileStatus> = emptyList(),
    val hasStash: Boolean = false,
    val error: String? = null
) {
    val totalChangesCount: Int
        get() = stagedFiles.size + unstagedFiles.size + untrackedFiles.size

    val hasChanges: Boolean
        get() = totalChangesCount > 0
}

data class GitBranchInfo(
    val name: String,
    val isCurrent: Boolean,
    val isRemote: Boolean
)

data class GitCommitRef(
    val name: String,
    val isHead: Boolean = false,
    val isRemote: Boolean = false,
    val isTag: Boolean = false
)

data class GitGraphSegment(
    val fromCol: Int,
    val toCol: Int,
    val type: String, // "direct", "branch", "merge", "pass_through"
    val colorIndex: Int
)

data class GitCommitLog(
    val hash: String,
    val shortHash: String,
    val author: String,
    val date: String,
    val message: String,
    val isUnpushed: Boolean = false,
    val graphColumn: Int = 0,
    val colorIndex: Int = 0,
    val parents: List<String> = emptyList(),
    val isMerge: Boolean = false,
    val refs: List<GitCommitRef> = emptyList(),
    val segments: List<GitGraphSegment> = emptyList()
)

data class GitCommitFileChange(
    val path: String,
    val status: String
)

data class GitCommitDetails(
    val hash: String,
    val shortHash: String,
    val author: String,
    val date: String,
    val subject: String,
    val body: String,
    val changedFiles: List<GitCommitFileChange> = emptyList()
)

data class GitConfig(
    val userName: String = "",
    val userEmail: String = "",
    val pullRebase: String = "",
    val remoteUrl: String = ""
)

data class GitActionResult(
    val success: Boolean,
    val output: String = "",
    val error: String = "",
    val commit: GitCommitLog? = null
)

data class GitDiffResponse(
    val path: String,
    val staged: Boolean,
    val diff: String,
    val additions: Int,
    val deletions: Int
)

object GitApiClient {
    private const val TAG = "GitApiClient"
    var baseUrl: String
        get() = AuthPreferences.currentBridgeHttpUrl
        set(value) { AuthPreferences.currentBridgeHttpUrl = value }

    private val client: OkHttpClient get() = com.example.gemini.data.remote.AgyBridgeService.instance.client

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    suspend fun getStatus(projectPath: String): GitStatusResponse? = withContext(Dispatchers.IO) {
        try {
            val enc = URLEncoder.encode(projectPath, "UTF-8")
            val request = Request.Builder()
                .url("$baseUrl/api/git/status?project=$enc")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bodyStr = response.body?.string() ?: return@withContext null
                val json = JSONObject(bodyStr)

                val isGitRepo = json.optBoolean("isGitRepo", true)
                val staged = parseFileList(json.optJSONArray("stagedFiles"), staged = true)
                val unstaged = parseFileList(json.optJSONArray("unstagedFiles"), staged = false)
                val untracked = parseFileList(json.optJSONArray("untrackedFiles"), staged = false)

                val ahead = json.optInt("ahead", 0)
                val unpushed = json.optInt("unpushedCount", ahead)

                GitStatusResponse(
                    isGitRepo = isGitRepo,
                    branch = json.optString("branch", "HEAD"),
                    tracking = json.optString("tracking").takeIf { it.isNotBlank() },
                    ahead = ahead,
                    behind = json.optInt("behind", 0),
                    unpushedCount = unpushed,
                    stagedFiles = staged,
                    unstagedFiles = unstaged,
                    untrackedFiles = untracked,
                    hasStash = json.optBoolean("hasStash", false),
                    error = json.optString("error").takeIf { it.isNotBlank() }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "getStatus failed: ${e.message}")
            null
        }
    }

    suspend fun initRepo(projectPath: String): GitActionResult = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("project", projectPath)
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/api/git/init")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                val json = try { JSONObject(bodyStr) } catch (_: Exception) { JSONObject() }
                GitActionResult(
                    success = response.isSuccessful && json.optBoolean("success", true),
                    output = json.optString("output", "Initialized Git repository"),
                    error = json.optString("error", "")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "initRepo failed: ${e.message}")
            GitActionResult(success = false, error = e.message ?: "Init failed")
        }
    }

    suspend fun getConfig(projectPath: String): GitConfig? = withContext(Dispatchers.IO) {
        try {
            val enc = URLEncoder.encode(projectPath, "UTF-8")
            val request = Request.Builder()
                .url("$baseUrl/api/git/config?project=$enc")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bodyStr = response.body?.string() ?: return@withContext null
                val json = JSONObject(bodyStr)
                GitConfig(
                    userName = json.optString("userName", ""),
                    userEmail = json.optString("userEmail", ""),
                    pullRebase = json.optString("pullRebase", ""),
                    remoteUrl = json.optString("remoteUrl", "")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "getConfig failed: ${e.message}")
            null
        }
    }

    suspend fun setConfig(
        projectPath: String,
        userName: String? = null,
        userEmail: String? = null,
        pullRebase: String? = null,
        remoteUrl: String? = null,
        isGlobal: Boolean = false
    ): GitActionResult = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("project", projectPath)
                if (userName != null) put("userName", userName)
                if (userEmail != null) put("userEmail", userEmail)
                if (pullRebase != null) put("pullRebase", pullRebase)
                if (remoteUrl != null) put("remoteUrl", remoteUrl)
                put("isGlobal", isGlobal)
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/api/git/config")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                val json = try { JSONObject(bodyStr) } catch (_: Exception) { JSONObject() }
                GitActionResult(
                    success = response.isSuccessful && json.optBoolean("success", true),
                    output = json.optString("output", "Configuration updated"),
                    error = json.optString("error", "")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "setConfig failed: ${e.message}")
            GitActionResult(success = false, error = e.message ?: "Failed to set config")
        }
    }

    private fun parseFileList(arr: JSONArray?, staged: Boolean): List<GitFileStatus> {
        if (arr == null) return emptyList()
        val list = mutableListOf<GitFileStatus>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(
                GitFileStatus(
                    path = obj.getString("path"),
                    oldPath = obj.optString("oldPath").takeIf { it.isNotBlank() },
                    status = obj.getString("status"),
                    staged = staged
                )
            )
        }
        return list
    }

    suspend fun getBranches(projectPath: String): List<GitBranchInfo> = withContext(Dispatchers.IO) {
        try {
            val enc = URLEncoder.encode(projectPath, "UTF-8")
            val request = Request.Builder()
                .url("$baseUrl/api/git/branches?project=$enc")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val bodyStr = response.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(bodyStr)
                val arr = json.optJSONArray("branches") ?: return@withContext emptyList()
                val list = mutableListOf<GitBranchInfo>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        GitBranchInfo(
                            name = obj.getString("name"),
                            isCurrent = obj.optBoolean("isCurrent", false),
                            isRemote = obj.optBoolean("isRemote", false)
                        )
                    )
                }
                list
            }
        } catch (e: Exception) {
            Log.e(TAG, "getBranches failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun checkoutBranch(projectPath: String, branch: String, create: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("project", projectPath)
                put("branch", branch)
                put("create", create)
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/api/git/checkout")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                val json = try { JSONObject(bodyStr) } catch (_: Exception) { JSONObject() }
                response.isSuccessful && json.optBoolean("success", false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkoutBranch failed: ${e.message}")
            false
        }
    }

    suspend fun stage(projectPath: String, paths: List<String> = emptyList()): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("project", projectPath)
                put("paths", JSONArray(paths))
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/api/git/stage")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                val json = try { JSONObject(bodyStr) } catch (_: Exception) { JSONObject() }
                response.isSuccessful && json.optBoolean("success", false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "stage failed: ${e.message}")
            false
        }
    }

    suspend fun unstage(projectPath: String, paths: List<String> = emptyList()): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("project", projectPath)
                put("paths", JSONArray(paths))
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/api/git/unstage")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                val json = try { JSONObject(bodyStr) } catch (_: Exception) { JSONObject() }
                response.isSuccessful && json.optBoolean("success", false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "unstage failed: ${e.message}")
            false
        }
    }

    suspend fun discard(projectPath: String, paths: List<String> = emptyList()): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("project", projectPath)
                put("paths", JSONArray(paths))
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/api/git/discard")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                val json = try { JSONObject(bodyStr) } catch (_: Exception) { JSONObject() }
                response.isSuccessful && json.optBoolean("success", false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "discard failed: ${e.message}")
            false
        }
    }

    suspend fun commit(projectPath: String, message: String): Boolean {
        return commitDetailed(projectPath, message).success
    }

    suspend fun commitDetailed(projectPath: String, message: String): GitActionResult = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("project", projectPath)
                put("message", message)
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/api/git/commit")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                val json = try { JSONObject(bodyStr) } catch (_: Exception) { JSONObject() }
                val isSuccess = response.isSuccessful && json.optBoolean("success", false)
                val errorMsg = json.optString("error", if (!isSuccess) "Commit failed with HTTP ${response.code}" else "")
                GitActionResult(
                    success = isSuccess,
                    output = json.optString("output", ""),
                    error = errorMsg
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "commit failed: ${e.message}")
            GitActionResult(success = false, error = e.message ?: "Commit network error")
        }
    }

    suspend fun push(projectPath: String): Boolean {
        return pushDetailed(projectPath).success
    }

    suspend fun pushDetailed(projectPath: String): GitActionResult = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("project", projectPath)
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/api/git/push")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                val json = try { JSONObject(bodyStr) } catch (_: Exception) { JSONObject() }
                GitActionResult(
                    success = response.isSuccessful && json.optBoolean("success", true),
                    output = json.optString("output", ""),
                    error = json.optString("error", if (!response.isSuccessful) "Push failed with code ${response.code}" else "")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "push failed: ${e.message}")
            GitActionResult(success = false, error = e.message ?: "Push network error")
        }
    }

    suspend fun pull(projectPath: String): Boolean {
        return pullDetailed(projectPath).success
    }

    suspend fun pullDetailed(projectPath: String): GitActionResult = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("project", projectPath)
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/api/git/pull")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                val json = try { JSONObject(bodyStr) } catch (_: Exception) { JSONObject() }
                GitActionResult(
                    success = response.isSuccessful && json.optBoolean("success", true),
                    output = json.optString("output", ""),
                    error = json.optString("error", if (!response.isSuccessful) "Pull failed with code ${response.code}" else "")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "pull failed: ${e.message}")
            GitActionResult(success = false, error = e.message ?: "Pull network error")
        }
    }

    suspend fun stash(projectPath: String, message: String = ""): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("project", projectPath)
                put("message", message)
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/api/git/stash")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                val json = try { JSONObject(bodyStr) } catch (_: Exception) { JSONObject() }
                response.isSuccessful && json.optBoolean("success", false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "stash failed: ${e.message}")
            false
        }
    }

    suspend fun stashPop(projectPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("project", projectPath)
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/api/git/stash/pop")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                val json = try { JSONObject(bodyStr) } catch (_: Exception) { JSONObject() }
                response.isSuccessful && json.optBoolean("success", false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "stashPop failed: ${e.message}")
            false
        }
    }

    suspend fun getDiff(projectPath: String, filePath: String, staged: Boolean = false): GitDiffResponse? = withContext(Dispatchers.IO) {
        try {
            val encProj = URLEncoder.encode(projectPath, "UTF-8")
            val encFile = URLEncoder.encode(filePath, "UTF-8")
            val url = "$baseUrl/api/git/diff?project=$encProj&file=$encFile&staged=$staged"

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bodyStr = response.body?.string() ?: return@withContext null
                val json = JSONObject(bodyStr)

                GitDiffResponse(
                    path = json.optString("path", filePath),
                    staged = json.optBoolean("staged", staged),
                    diff = json.optString("diff", ""),
                    additions = json.optInt("additions", 0),
                    deletions = json.optInt("deletions", 0)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "getDiff failed: ${e.message}")
            null
        }
    }

    suspend fun getLog(projectPath: String, limit: Int = 20, skip: Int = 0): List<GitCommitLog> = withContext(Dispatchers.IO) {
        try {
            val enc = URLEncoder.encode(projectPath, "UTF-8")
            val request = Request.Builder()
                .url("$baseUrl/api/git/log?project=$enc&limit=$limit&skip=$skip")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val bodyStr = response.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(bodyStr)
                val arr = json.optJSONArray("commits") ?: return@withContext emptyList()
                val list = mutableListOf<GitCommitLog>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)

                    val refsList = mutableListOf<GitCommitRef>()
                    val refsArr = obj.optJSONArray("refs")
                    if (refsArr != null) {
                        for (r in 0 until refsArr.length()) {
                            val ro = refsArr.getJSONObject(r)
                            refsList.add(
                                GitCommitRef(
                                    name = ro.getString("name"),
                                    isHead = ro.optBoolean("isHead", false),
                                    isRemote = ro.optBoolean("isRemote", false),
                                    isTag = ro.optBoolean("isTag", false)
                                )
                            )
                        }
                    }

                    val segsList = mutableListOf<GitGraphSegment>()
                    val segsArr = obj.optJSONArray("segments")
                    if (segsArr != null) {
                        for (s in 0 until segsArr.length()) {
                            val so = segsArr.getJSONObject(s)
                            segsList.add(
                                GitGraphSegment(
                                    fromCol = so.optInt("fromCol", 0),
                                    toCol = so.optInt("toCol", 0),
                                    type = so.optString("type", "direct"),
                                    colorIndex = so.optInt("colorIndex", 0)
                                )
                            )
                        }
                    }

                    val parentsList = mutableListOf<String>()
                    val parentsArr = obj.optJSONArray("parents")
                    if (parentsArr != null) {
                        for (p in 0 until parentsArr.length()) {
                            parentsList.add(parentsArr.getString(p))
                        }
                    }

                    list.add(
                        GitCommitLog(
                            hash = obj.getString("hash"),
                            shortHash = obj.getString("shortHash"),
                            author = obj.getString("author"),
                            date = obj.getString("date"),
                            message = obj.getString("message"),
                            isUnpushed = obj.optBoolean("isUnpushed", false),
                            graphColumn = obj.optInt("graphColumn", 0),
                            colorIndex = obj.optInt("colorIndex", 0),
                            parents = parentsList,
                            isMerge = obj.optBoolean("isMerge", false),
                            refs = refsList,
                            segments = segsList
                        )
                    )
                }
                list
            }
        } catch (e: Exception) {
            Log.e(TAG, "getLog failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun getCommitDetails(projectPath: String, hash: String): GitCommitDetails? = withContext(Dispatchers.IO) {
        try {
            val encProj = URLEncoder.encode(projectPath, "UTF-8")
            val encHash = URLEncoder.encode(hash, "UTF-8")
            val request = Request.Builder()
                .url("$baseUrl/api/git/commit/details?project=$encProj&hash=$encHash")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bodyStr = response.body?.string() ?: return@withContext null
                val json = JSONObject(bodyStr)
                val filesArr = json.optJSONArray("changedFiles") ?: JSONArray()
                val changedFiles = mutableListOf<GitCommitFileChange>()
                for (i in 0 until filesArr.length()) {
                    val fo = filesArr.getJSONObject(i)
                    changedFiles.add(
                        GitCommitFileChange(
                            path = fo.getString("path"),
                            status = fo.optString("status", "M")
                        )
                    )
                }
                GitCommitDetails(
                    hash = json.getString("hash"),
                    shortHash = json.optString("shortHash", hash.take(7)),
                    author = json.optString("author", ""),
                    date = json.optString("date", ""),
                    subject = json.optString("subject", ""),
                    body = json.optString("body", ""),
                    changedFiles = changedFiles
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "getCommitDetails failed: ${e.message}")
            null
        }
    }

    suspend fun getCommitFileDiff(projectPath: String, hash: String, filePath: String): String? = withContext(Dispatchers.IO) {
        try {
            val encProj = URLEncoder.encode(projectPath, "UTF-8")
            val encHash = URLEncoder.encode(hash, "UTF-8")
            val encFile = URLEncoder.encode(filePath, "UTF-8")
            val request = Request.Builder()
                .url("$baseUrl/api/git/commit/diff?project=$encProj&hash=$encHash&file=$encFile")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bodyStr = response.body?.string() ?: return@withContext null
                val json = JSONObject(bodyStr)
                json.optString("diff", "")
            }
        } catch (e: Exception) {
            Log.e(TAG, "getCommitFileDiff failed: ${e.message}")
            null
        }
    }

    suspend fun getCommitFileContent(projectPath: String, hash: String, filePath: String): String? = withContext(Dispatchers.IO) {
        try {
            val encProj = URLEncoder.encode(projectPath, "UTF-8")
            val encHash = URLEncoder.encode(hash, "UTF-8")
            val encFile = URLEncoder.encode(filePath, "UTF-8")
            val request = Request.Builder()
                .url("$baseUrl/api/git/commit/content?project=$encProj&hash=$encHash&file=$encFile")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bodyStr = response.body?.string() ?: return@withContext null
                val json = JSONObject(bodyStr)
                json.optString("content", "")
            }
        } catch (e: Exception) {
            Log.e(TAG, "getCommitFileContent failed: ${e.message}")
            null
        }
    }

    suspend fun rollback(projectPath: String, hash: String, hard: Boolean = true): GitActionResult = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("project", projectPath)
                put("hash", hash)
                put("hard", hard)
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/api/git/rollback")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                val json = try { JSONObject(bodyStr) } catch (_: Exception) { JSONObject() }
                if (response.isSuccessful && json.optBoolean("success", false)) {
                    GitActionResult(
                        success = true,
                        output = json.optString("output", "Rolled back to $hash")
                    )
                } else {
                    val err = json.optString("error", if (!response.isSuccessful) "HTTP ${response.code}" else "Rollback failed")
                    fallbackLocalRollback(projectPath, hash, hard, err)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "rollback API failed, falling back to local git: ${e.message}")
            fallbackLocalRollback(projectPath, hash, hard, e.message ?: "")
        }
    }

    suspend fun restoreFile(projectPath: String, hash: String, filePath: String): GitActionResult = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("project", projectPath)
                put("hash", hash)
                put("file", filePath)
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/api/git/restore")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                val json = try { JSONObject(bodyStr) } catch (_: Exception) { JSONObject() }
                if (response.isSuccessful && json.optBoolean("success", false)) {
                    GitActionResult(
                        success = true,
                        output = json.optString("output", "Restored $filePath from $hash")
                    )
                } else {
                    val err = json.optString("error", if (!response.isSuccessful) "HTTP ${response.code}" else "Restore failed")
                    fallbackLocalRestore(projectPath, hash, filePath, err)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "restore API failed, falling back to local git: ${e.message}")
            fallbackLocalRestore(projectPath, hash, filePath, e.message ?: "")
        }
    }

    suspend fun revert(projectPath: String, hash: String): GitActionResult = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("project", projectPath)
                put("hash", hash)
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/api/git/revert")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                val json = try { JSONObject(bodyStr) } catch (_: Exception) { JSONObject() }
                if (response.isSuccessful && json.optBoolean("success", false)) {
                    GitActionResult(
                        success = true,
                        output = json.optString("output", "Reverted $hash")
                    )
                } else {
                    val err = json.optString("error", if (!response.isSuccessful) "HTTP ${response.code}" else "Revert failed")
                    fallbackLocalRevert(projectPath, hash, err)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "revert API failed, falling back to local git: ${e.message}")
            fallbackLocalRevert(projectPath, hash, e.message ?: "")
        }
    }

    suspend fun saveCheckpoint(projectPath: String, message: String): GitActionResult = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("project", projectPath)
                put("message", message)
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/api/git/checkpoint")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                val json = try { JSONObject(bodyStr) } catch (_: Exception) { JSONObject() }
                if (response.isSuccessful && json.optBoolean("success", false)) {
                    GitActionResult(
                        success = true,
                        output = json.optString("output", "Checkpoint saved"),
                        commit = parseCommitLogObject(json.optJSONObject("commit"))
                    )
                } else {
                    val err = json.optString("error", if (!response.isSuccessful) "HTTP ${response.code}" else "Checkpoint failed")
                    fallbackLocalCheckpoint(projectPath, message, err)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "checkpoint API failed, falling back to local git: ${e.message}")
            fallbackLocalCheckpoint(projectPath, message, e.message ?: "")
        }
    }

    suspend fun getFileLog(projectPath: String, filePath: String, limit: Int = 25): List<GitCommitLog> = withContext(Dispatchers.IO) {
        try {
            val encProj = URLEncoder.encode(projectPath, "UTF-8")
            val encFile = URLEncoder.encode(filePath, "UTF-8")
            val request = Request.Builder()
                .url("$baseUrl/api/git/file/log?project=$encProj&file=$encFile&limit=$limit")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val bodyStr = response.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(bodyStr)
                val arr = json.optJSONArray("commits") ?: return@withContext emptyList()
                val list = mutableListOf<GitCommitLog>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        GitCommitLog(
                            hash = obj.getString("hash"),
                            shortHash = obj.optString("shortHash", obj.getString("hash").take(7)),
                            author = obj.optString("author", ""),
                            date = obj.optString("date", ""),
                            message = obj.optString("message", "")
                        )
                    )
                }
                list
            }
        } catch (e: Exception) {
            Log.e(TAG, "getFileLog failed: ${e.message}")
            emptyList()
        }
    }

    private fun parseCommitLogObject(obj: JSONObject?): GitCommitLog? {
        if (obj == null) return null
        return GitCommitLog(
            hash = obj.optString("hash", ""),
            shortHash = obj.optString("shortHash", ""),
            author = obj.optString("author", ""),
            date = obj.optString("date", ""),
            message = obj.optString("message", "")
        )
    }

    private fun runLocalGit(projectPath: String, args: List<String>): Pair<Boolean, String> {
        return try {
            val dir = java.io.File(projectPath)
            if (!dir.exists()) return Pair(false, "Directory does not exist: $projectPath")

            val envList = arrayOf(
                "PATH=/data/data/com.termux/files/usr/bin:/data/data/com.termux/files/home/.local/bin:/usr/bin:/bin",
                "HOME=/data/data/com.termux/files/home"
            )
            val cmd = arrayOf("sh", "-c", "exec git \"\$@\"", "git") + args.toTypedArray()
            val proc = Runtime.getRuntime().exec(cmd, envList, dir)
            val stdout = proc.inputStream.bufferedReader().use { it.readText() }
            val stderr = proc.errorStream.bufferedReader().use { it.readText() }
            val exit = proc.waitFor()
            if (exit == 0) {
                Pair(true, stdout.ifBlank { "OK" })
            } else {
                Pair(false, stderr.ifBlank { stdout }.ifBlank { "Process exited with $exit" })
            }
        } catch (e: Exception) {
            Pair(false, e.message ?: "Unknown local git error")
        }
    }

    private fun fallbackLocalRollback(projectPath: String, hash: String, hard: Boolean, prevErr: String): GitActionResult {
        val mode = if (hard) "--hard" else "--mixed"
        val (ok, out) = runLocalGit(projectPath, listOf("reset", mode, hash))
        return GitActionResult(success = ok, output = out, error = if (ok) "" else out.ifBlank { prevErr })
    }

    private fun fallbackLocalRestore(projectPath: String, hash: String, filePath: String, prevErr: String): GitActionResult {
        val (ok, out) = runLocalGit(projectPath, listOf("checkout", hash, "--", filePath))
        return GitActionResult(success = ok, output = out, error = if (ok) "" else out.ifBlank { prevErr })
    }

    private fun fallbackLocalRevert(projectPath: String, hash: String, prevErr: String): GitActionResult {
        val (ok, out) = runLocalGit(projectPath, listOf("revert", "--no-edit", hash))
        return GitActionResult(success = ok, output = out, error = if (ok) "" else out.ifBlank { prevErr })
    }

    private fun fallbackLocalCheckpoint(projectPath: String, message: String, prevErr: String): GitActionResult {
        runLocalGit(projectPath, listOf("rev-parse", "--is-inside-work-tree")).let { (isRepo, _) ->
            if (!isRepo) {
                runLocalGit(projectPath, listOf("init"))
                runLocalGit(projectPath, listOf("config", "user.name", "AntiBot Local"))
                runLocalGit(projectPath, listOf("config", "user.email", "antibot@local"))
            }
        }
        val (stagedOk, stageOut) = runLocalGit(projectPath, listOf("add", "-A"))
        if (!stagedOk) return GitActionResult(success = false, error = stageOut.ifBlank { prevErr })

        val msg = message.ifBlank { "Checkpoint " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()) }
        val (commOk, commOut) = runLocalGit(projectPath, listOf("commit", "-m", msg))
        return if (commOk) {
            GitActionResult(success = true, output = commOut)
        } else {
            if (commOut.contains("nothing to commit", ignoreCase = true)) {
                GitActionResult(success = true, output = "Working tree clean, no changes to save")
            } else {
                GitActionResult(success = false, error = commOut.ifBlank { prevErr })
            }
        }
    }
}

