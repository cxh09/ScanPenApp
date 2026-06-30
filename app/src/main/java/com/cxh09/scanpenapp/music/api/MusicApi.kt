package com.cxh09.scanpenapp.music.api

import android.util.Base64
import com.cxh09.scanpenapp.music.MusicDailySong
import com.cxh09.scanpenapp.music.MusicPlaylist
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 网易云音乐第三方 API（NeteaseCloudMusicApi Enhanced）客户端。
 *
 * 端点基础地址由用户在 [com.cxh09.scanpenapp.MusicServerSettingsActivity] 配置的
 * 「服务器地址 + 端口」拼装，形如 `http://192.168.1.100:3000`。
 *
 * 涉及接口（参考 https://neteasecloudmusicapienhanced.js.org/ ）：
 *  - `GET /search?keywords=...&limit=...`  搜索单曲
 *  - `GET /login/qr/key`                   生成二维码登录 key
 *  - `GET /login/qr/create?key=...&qrimg=true`  生成二维码图片（返回 `qrimg` 为 base64 data URI）
 *  - `GET /login/qr/check?key=...[&cookie=...]`  轮询扫码状态
 *
 * 设计要点：
 *  - 全部使用平台 API（HttpURLConnection + org.json），不引入 Retrofit/OkHttp，遵循项目
 *    「控制 APK 体积 + 避免大型库」规则。
 *  - 全部为阻塞调用，**调用方必须在 `Dispatchers.IO` 协程中执行**。
 *  - Cookie 与 baseUrl 由调用方通过参数传入，便于测试和切换。
 */
object MusicApi {

    // 二维码扫码状态码（来自网易云接口 `/login/qr/check` 响应的 `code` 字段）
    const val QR_STATUS_EXPIRED = 800        // 二维码已过期
    const val QR_STATUS_WAITING_SCAN = 801   // 等待扫码
    const val QR_STATUS_SCANNED = 802        // 已扫码，等待确认
    const val QR_STATUS_SUCCESS = 803        // 登录成功

    /**
     * 搜索单曲（`/search?keywords=...`）。
     *
     * 响应示例：
     * ```
     * { "result": { "songs": [ { "id":..., "name":..., "artists":[...], "album":{...}, "duration":... } ] },
     *   "code": 200 }
     * ```
     */
    fun search(
        baseUrl: String,
        keywords: String,
        cookie: String? = null,
        limit: Int = 30,
    ): List<MusicDailySong> {
        val encoded = URLEncoder.encode(keywords, "UTF-8")
        val url = buildUrl(
            baseUrl,
            path = "/search",
            params = mapOf(
                "keywords" to encoded,
                "limit" to limit.toString()
            )
        )
        val body = httpGet(url, cookie)
        val root = JSONObject(body)
        if (root.optInt("code", -1) != 200) {
            throw MusicApiException("搜索失败 (code=${root.optInt("code")})")
        }
        val result = root.optJSONObject("result") ?: return emptyList()
        val arr = result.optJSONArray("songs") ?: return emptyList()
        val out = ArrayList<MusicDailySong>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val id = s.optLong("id", 0L)
            val name = s.optString("name", "")
            val artist = joinArtists(s.optJSONArray("artists"))
            val album = s.optJSONObject("album")?.optString("name", "").orEmpty()
            val duration = s.optLong("duration", 0L)
            if (id <= 0L || name.isEmpty()) continue
            out.add(
                MusicDailySong(
                    id = id.toString(),
                    name = name,
                    artist = artist,
                    album = album,
                    durationMs = duration
                )
            )
        }
        return out
    }

    /**
     * 二维码 key 生成接口（`/login/qr/key`）。
     * @return 用于后续 create / check 的 unikey。
     */
    fun qrKey(baseUrl: String): String {
        val url = buildUrl(
            baseUrl,
            path = "/login/qr/key",
            params = mapOf("timestamp" to System.currentTimeMillis().toString())
        )
        val body = httpGet(url, null)
        val root = JSONObject(body)
        if (root.optInt("code", -1) != 200) {
            throw MusicApiException("生成二维码 key 失败 (code=${root.optInt("code")})")
        }
        val data = root.optJSONObject("data")
            ?: throw MusicApiException("生成二维码 key 失败：响应缺少 data")
        return data.optString("unikey", "").also {
            if (it.isEmpty()) throw MusicApiException("生成二维码 key 失败：unikey 为空")
        }
    }

    /**
     * 二维码生成接口（`/login/qr/create?key=...&qrimg=true&platform=web`）。
     *
     * 响应中 `data.qrimg` 形如 `data:image/png;base64,iVBORw0KGgo...`。
     * @return 解码后的 PNG 字节流（供 `BitmapFactory.decodeByteArray` 使用）。
     */
    fun qrCreate(baseUrl: String, key: String): ByteArray {
        val url = buildUrl(
            baseUrl,
            path = "/login/qr/create",
            params = mapOf(
                "key" to key,
                "qrimg" to "true",
                "platform" to "web",
                "timestamp" to System.currentTimeMillis().toString()
            )
        )
        val body = httpGet(url, null)
        val root = JSONObject(body)
        if (root.optInt("code", -1) != 200) {
            throw MusicApiException("生成二维码失败 (code=${root.optInt("code")})")
        }
        val qrimg = root.optJSONObject("data")?.optString("qrimg", "").orEmpty()
        if (qrimg.isEmpty()) throw MusicApiException("生成二维码失败：qrimg 为空")
        val commaIdx = qrimg.indexOf(',')
        val b64 = if (commaIdx >= 0) qrimg.substring(commaIdx + 1) else qrimg
        return Base64.decode(b64, Base64.DEFAULT)
    }

    /**
     * 二维码扫码状态检测（`/login/qr/check?key=...`）。
     *
     * 响应中 `cookie` 字段为字符串（如 `MUSIC_U=xxx; __csrf=...`），非 JSON 对象。
     * @return [QrCheckResult] 封装 status code + 登录成功后的 cookie。
     */
    fun qrCheck(baseUrl: String, key: String, cookie: String? = null): QrCheckResult {
        val url = buildUrl(
            baseUrl,
            path = "/login/qr/check",
            params = mapOf("key" to key, "timestamp" to System.currentTimeMillis().toString())
        )
        val body = httpGet(url, cookie)
        val root = JSONObject(body)
        val code = root.optInt("code", -1)
        // 关键：cookie 是字符串（"MUSIC_U=xxx; NMTID=xxx; ..."），不是 JSON object
        val cookieOut = root.optString("cookie", "").ifEmpty { null }
        return QrCheckResult(code = code, cookie = cookieOut)
    }

    /** 轮询结果：code 为 800/801/802/803，登录成功（803）时 [cookie] 非空。 */
    data class QrCheckResult(val code: Int, val cookie: String?)

    /**
     * 获取当前登录账号信息（`GET /user/account`）。
     *
     * 与 [/login/status] 类似但更轻量：仅返回 `account`，未登录时 `profile` 为 `null`。
     * 已登录时也会同时返回 `profile` 字段。
     *
     * 响应示例（已登录）：
     * ```
     * { "code":200,
     *   "account":{ "id":..., "userName":"1_xxx", "vipType":11, "createTime":... },
     *   "profile":{ "userId":..., "nickname":"...", "avatarUrl":"...",
     *               "signature":"~", "gender":1, "city":..., "province":... } }
     * ```
     */
    fun userAccount(baseUrl: String, cookie: String): LoginStatusResult {
        val url = buildUrl(
            baseUrl,
            path = "/user/account",
            params = mapOf("timestamp" to System.currentTimeMillis().toString())
        )
        val body = httpGet(url, cookie)
        val root = JSONObject(body)
        if (root.optInt("code", -1) != 200) {
            throw MusicApiException("获取账号信息失败 (code=${root.optInt("code")})")
        }
        val account = root.optJSONObject("account")
        if (account == null) {
            // 未登录：服务端 code=200 但 account 缺失
            throw MusicApiException("未登录或 cookie 已失效")
        }
        val profile = root.optJSONObject("profile")
        return LoginStatusResult(
            userId = account.optLong("id", 0L),
            userName = account.optString("userName", ""),
            accountType = account.optInt("type", 0),
            vipType = account.optInt("vipType", 0),
            createTime = account.optLong("createTime", 0L),
            nickname = profile?.optString("nickname", "").orEmpty(),
            avatarUrl = profile?.optString("avatarUrl", "").orEmpty(),
            avatarImgId = profile?.optLong("avatarImgId", 0L) ?: 0L,
            backgroundUrl = profile?.optString("backgroundUrl", "").orEmpty(),
            signature = profile?.optString("signature", "").orEmpty(),
            gender = profile?.optInt("gender", 0) ?: 0,
            province = profile?.optInt("province", 0) ?: 0,
            city = profile?.optInt("city", 0) ?: 0,
            lastLoginTime = profile?.optLong("lastLoginTime", 0L) ?: 0L,
            lastLoginIp = profile?.optString("lastLoginIP", "").orEmpty()
        )
    }

    /**
     * 获取用户信息 / 歌单 / 收藏 / MV / DJ 数量（`GET /user/subcount`）。
     *
     * 响应示例：
     * ```
     * { "code":200, "subCount": {
     *     "createdPlaylistCount":1, "subPlaylistCount":5,
     *     "collectArtistCount":10, "collectMvCount":0, "collectDjRadioCount":0,
     *     "subAlbumCount":2, "mvCount":0, "djRadioCount":0, "artistCount":10,
     *     "programCount":0, "newProgramCount":0, "createRadioCount":0,
     *     "cocoaPlaylistCount":0 } }
     * ```
     */
    fun userSubcount(baseUrl: String, cookie: String): UserSubcount {
        val url = buildUrl(
            baseUrl,
            path = "/user/subcount",
            params = mapOf("timestamp" to System.currentTimeMillis().toString())
        )
        val body = httpGet(url, cookie)
        val root = JSONObject(body)
        if (root.optInt("code", -1) != 200) {
            throw MusicApiException("获取用户统计失败 (code=${root.optInt("code")})")
        }
        val s = root.optJSONObject("subCount")
        fun jget(name: String) = s?.optInt(name, 0) ?: 0
        return UserSubcount(
            createdPlaylistCount = jget("createdPlaylistCount"),
            subPlaylistCount = jget("subPlaylistCount"),
            collectArtistCount = jget("collectArtistCount"),
            collectMvCount = jget("collectMvCount"),
            collectDjRadioCount = jget("collectDjRadioCount"),
            subAlbumCount = jget("subAlbumCount"),
            mvCount = jget("mvCount"),
            djRadioCount = jget("djRadioCount"),
            artistCount = jget("artistCount"),
            programCount = jget("programCount")
        )
    }

    /** `/user/subcount` 响应中我们关心的字段。 */
    data class UserSubcount(
        /** 我创建的歌单数量。 */
        val createdPlaylistCount: Int,
        /** 我收藏（订阅）的歌单数量。 */
        val subPlaylistCount: Int,
        /** 收藏的歌手数量。 */
        val collectArtistCount: Int,
        /** 收藏的 MV 数量。 */
        val collectMvCount: Int,
        /** 收藏的电台数量。 */
        val collectDjRadioCount: Int,
        /** 收藏的专辑数量。 */
        val subAlbumCount: Int,
        /** MV 总数（含上传）。 */
        val mvCount: Int,
        /** 电台总数。 */
        val djRadioCount: Int,
        /** 订阅歌手数。 */
        val artistCount: Int,
        /** 电台节目数。 */
        val programCount: Int,
    ) {
        /** 我的歌单 = 我创建的 + 我收藏的。 */
        val totalPlaylistCount: Int get() = createdPlaylistCount + subPlaylistCount
    }

    /**
     * 服务端退出登录（`GET /logout`），使 cookie 失效。
     *
     * 注意：服务端失败也不影响本地 [com.cxh09.scanpenapp.music.api.MusicSession.clear]，
     * 调用方应自行处理。
     */
    fun logout(baseUrl: String, cookie: String) {
        val url = buildUrl(
            baseUrl,
            path = "/logout",
            params = mapOf("timestamp" to System.currentTimeMillis().toString())
        )
        val body = httpGet(url, cookie)
        val root = JSONObject(body)
        if (root.optInt("code", -1) != 200) {
            throw MusicApiException("退出登录失败 (code=${root.optInt("code")})")
        }
    }

    /**
     * 获取当前登录账号信息（`POST /login/status`，body 含 cookie）。
     *
     * 响应示例：
     * ```
     * { "code":200,
     *   "data":{ "account":{ "id":..., "userName":"1_xxx", "vipType":11, "createTime":... },
     *            "profile":{ "userId":..., "nickname":"...", "avatarUrl":"...",
     *                        "signature":"~", "gender":1, "city":..., "province":... } } }
     * ```
     * @return [LoginStatusResult] 完整账号 / 资料信息。
     */
    fun loginStatus(baseUrl: String, cookie: String): LoginStatusResult {
        val url = buildUrl(
            baseUrl,
            path = "/login/status",
            params = mapOf(
                "timestamp" to System.currentTimeMillis().toString(),
                "ua" to "pc"
            )
        )
        // 按 HTML 参考：POST，body 为 urlencoded `cookie=<cookie>`
        val body = httpPost(
            urlStr = url,
            cookie = cookie,
            formBody = "cookie=" + URLEncoder.encode(cookie, "UTF-8")
        )
        val root = JSONObject(body)
        if (root.optInt("code", -1) != 200) {
            throw MusicApiException("获取登录信息失败 (code=${root.optInt("code")})")
        }
        val data = root.optJSONObject("data")
            ?: throw MusicApiException("获取登录信息失败：响应缺少 data")
        val account = data.optJSONObject("account")
        val profile = data.optJSONObject("profile")

        return LoginStatusResult(
            // account
            userId = account?.optLong("id", 0L) ?: 0L,
            userName = account?.optString("userName", "").orEmpty(),
            accountType = account?.optInt("type", 0) ?: 0,
            vipType = account?.optInt("vipType", 0) ?: 0,
            createTime = account?.optLong("createTime", 0L) ?: 0L,
            // profile
            nickname = profile?.optString("nickname", "").orEmpty(),
            avatarUrl = profile?.optString("avatarUrl", "").orEmpty(),
            avatarImgId = profile?.optLong("avatarImgId", 0L) ?: 0L,
            backgroundUrl = profile?.optString("backgroundUrl", "").orEmpty(),
            signature = profile?.optString("signature", "").orEmpty(),
            gender = profile?.optInt("gender", 0) ?: 0,
            province = profile?.optInt("province", 0) ?: 0,
            city = profile?.optInt("city", 0) ?: 0,
            lastLoginTime = profile?.optLong("lastLoginTime", 0L) ?: 0L,
            lastLoginIp = profile?.optString("lastLoginIP", "").orEmpty()
        )
    }

    /**
     * `/login/status` 响应中我们关心的全部字段。
     * 字段都允许为空（0 / ""），调用方需自行判空。
     */
    data class LoginStatusResult(
        // ---- account ----
        /** 账号 UID。 */
        val userId: Long,
        /** 登录账号（如 `1_xxxxxxxxx`）。 */
        val userName: String,
        /** 账号类型：1=普通, 2=... */
        val accountType: Int,
        /** 会员类型：0/10/11/110/... */
        val vipType: Int,
        /** 账号注册时间（毫秒）。 */
        val createTime: Long,
        // ---- profile ----
        /** 昵称（展示用）。 */
        val nickname: String,
        /** 头像 URL（200x200 JPG/PNG）。 */
        val avatarUrl: String,
        /** 头像资源 ID（备用标识）。 */
        val avatarImgId: Long,
        /** 个人主页背景图 URL。 */
        val backgroundUrl: String,
        /** 个性签名。 */
        val signature: String,
        /** 性别：0=未设置 1=男 2=女。 */
        val gender: Int,
        /** 省份编码。 */
        val province: Int,
        /** 城市编码。 */
        val city: Int,
        /** 上次登录时间（毫秒）。 */
        val lastLoginTime: Long,
        /** 上次登录 IP。 */
        val lastLoginIp: String,
    )

    // ---------------- 播放 / 歌词 / 详情相关 ----------------

    /**
     * 获取歌曲实际可播放 URL（`GET /song/url/v1?id=...&level=standard`）。
     *
     * 响应示例：
     * ```
     * { "code":200, "data":[ { "id":..., "url":"https://.../music.mp3", "br":320000,
     *                         "size":..., "level":"exhigh", "type":"mp3", "time":235000 } ] }
     * ```
     *
     * @return [SongUrl]；若服务端返回的 `url` 字段为 `null`（版权 / 灰色歌曲），返回 `null`；
     *         异常路径仍走 [MusicApiException]。
     */
    fun songUrl(
        baseUrl: String,
        cookie: String?,
        id: String,
        level: String = "standard",
    ): SongUrl? {
        val url = buildUrl(
            baseUrl,
            path = "/song/url/v1",
            params = mapOf(
                "id" to id,
                "level" to level,
                "timestamp" to System.currentTimeMillis().toString(),
                "ua" to "pc"
            )
        )
        val body = httpGet(url, cookie)
        val root = JSONObject(body)
        if (root.optInt("code", -1) != 200) {
            throw MusicApiException("获取播放链接失败 (code=${root.optInt("code")})")
        }
        val arr = root.optJSONArray("data") ?: return null
        if (arr.length() == 0) return null
        val s = arr.optJSONObject(0) ?: return null
        val playUrl = s.optString("url", "").ifEmpty { null }
        return SongUrl(
            id = s.optLong("id", 0L).toString().ifEmpty { id },
            url = playUrl,
            br = s.optInt("br", 0),
            size = s.optLong("size", 0L),
            level = s.optString("level", level),
            type = s.optString("type", ""),
            timeMs = s.optLong("time", 0L)
        )
    }

    /** `/song/url/v1` 单条响应。`url==null` 表示版权受限 / 灰色歌曲。 */
    data class SongUrl(
        val id: String,
        val url: String?,
        val br: Int,
        val size: Long,
        val level: String,
        val type: String,
        val timeMs: Long,
    )

    /**
     * 判断歌曲是否可用（`GET /check/music?id=...&br=...`）。
     *
     * 响应示例：
     * ```
     * { "success": true,  "message": "ok" }
     * { "success": false, "message": "亲爱的,暂无版权" }
     * ```
     *
     * 服务端可能 `code != 200` 但 `success=true`，以 `success` 字段为准。
     */
    fun checkMusic(
        baseUrl: String,
        cookie: String?,
        id: String,
        br: Int = 999000,
    ): CheckMusicResult {
        val url = buildUrl(
            baseUrl,
            path = "/check/music",
            params = mapOf(
                "id" to id,
                "br" to br.toString(),
                "timestamp" to System.currentTimeMillis().toString(),
                "ua" to "pc"
            )
        )
        val body = httpGet(url, cookie)
        val root = JSONObject(body)
        val success = root.optBoolean("success", false)
        val message = root.optString("message", "")
        return CheckMusicResult(success = success, message = message)
    }

    /** `/check/music` 响应。`success=false` 时 `message` 透出给用户。 */
    data class CheckMusicResult(
        val success: Boolean,
        val message: String,
    )

    /**
     * 批量获取歌曲详情（`GET /song/detail?ids=id1,id2,...`）。
     *
     * 用于补全搜索结果里缺 `album` / `durationMs` / `fee` / `mark` 等字段的歌曲。
     *
     * 响应示例：
     * ```
     * { "code":200, "songs":[ { "id":..., "name":"...", "ar":[...], "al":{...},
     *                          "dt":..., "fee":0, "mark":0, "mv":0,
     *                          "publishTime":... } ],
     *   "privileges":[...], "code":200 }
     * ```
     */
    fun songDetail(
        baseUrl: String,
        cookie: String?,
        ids: List<String>,
    ): List<SongDetail> {
        if (ids.isEmpty()) return emptyList()
        val url = buildUrl(
            baseUrl,
            path = "/song/detail",
            params = mapOf(
                "ids" to ids.joinToString(","),
                "timestamp" to System.currentTimeMillis().toString(),
                "ua" to "pc"
            )
        )
        val body = httpGet(url, cookie)
        val root = JSONObject(body)
        if (root.optInt("code", -1) != 200) {
            throw MusicApiException("获取歌曲详情失败 (code=${root.optInt("code")})")
        }
        val arr = root.optJSONArray("songs") ?: return emptyList()
        val out = ArrayList<SongDetail>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val id = s.optLong("id", 0L)
            if (id <= 0L) continue
            out.add(
                SongDetail(
                    id = id.toString(),
                    name = s.optString("name", ""),
                    artists = joinArtists(s.optJSONArray("ar")),
                    album = s.optJSONObject("al")?.optString("name", "").orEmpty(),
                    albumCover = s.optJSONObject("al")?.optString("picUrl", "").orEmpty().ifEmpty { null },
                    durationMs = s.optLong("dt", 0L),
                    publishTime = s.optLong("publishTime", 0L),
                    fee = s.optInt("fee", 0),
                    mark = s.optLong("mark", 0L),
                    hasMv = s.optLong("mv", 0L) > 0L
                )
            )
        }
        return out
    }

    /** `/song/detail` 单条响应中我们关心的字段。 */
    data class SongDetail(
        val id: String,
        val name: String,
        val artists: String,
        val album: String,
        val albumCover: String?,
        val durationMs: Long,
        val publishTime: Long,
        val fee: Int,
        val mark: Long,
        val hasMv: Boolean,
    )

    /**
     * 获取歌词（`GET /lyric?id=...`）。
     *
     * 响应中 `lrc.lyric` / `tlyric.lyric` / `romalrc.lyric` 三个字段都可能为 `null` 或空字符串。
     * 解析失败 / 响应异常时返回 `LyricResult(null, null, null)`，不抛。
     */
    fun lyric(
        baseUrl: String,
        cookie: String?,
        id: String,
    ): LyricResult {
        return try {
            val url = buildUrl(
                baseUrl,
                path = "/lyric",
                params = mapOf(
                    "id" to id,
                    "timestamp" to System.currentTimeMillis().toString(),
                    "ua" to "pc"
                )
            )
            val body = httpGet(url, cookie)
            val root = JSONObject(body)
            fun grab(key: String): String? {
                val o = root.optJSONObject(key) ?: return null
                val text = o.optString("lyric", "")
                return text.ifEmpty { null }
            }
            LyricResult(
                lrc = grab("lrc"),
                tlyric = grab("tlyric"),
                romalrc = grab("romalrc")
            )
        } catch (_: Exception) {
            LyricResult(null, null, null)
        }
    }

    /** `/lyric` 响应中我们关心的三个歌词字段（可能都为 `null`）。 */
    data class LyricResult(
        val lrc: String?,
        val tlyric: String?,
        val romalrc: String?,
    )

    // ---------------- 歌单 / 日推 / 个性化推荐 ----------------

    /**
     * 每日推荐歌曲（`GET /recommend/songs`），需要登录。
     *
     * 响应示例：
     * ```
     * { "code":200, "data":{ "dailySongs":[ { "id":..., "name":..., "ar":[...], "al":{...},
     *                                          "dt":..., "reason":"听你听过" } ] } }
     * ```
     */
    fun recommendSongs(baseUrl: String, cookie: String): List<MusicDailySong> {
        val url = buildUrl(
            baseUrl,
            path = "/recommend/songs",
            params = mapOf("timestamp" to System.currentTimeMillis().toString())
        )
        val body = httpGet(url, cookie)
        val root = JSONObject(body)
        if (root.optInt("code", -1) != 200) {
            throw MusicApiException("获取每日推荐失败 (code=${root.optInt("code")})")
        }
        val data = root.optJSONObject("data") ?: return emptyList()
        val arr = data.optJSONArray("dailySongs") ?: return emptyList()
        val out = ArrayList<MusicDailySong>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val id = s.optLong("id", 0L)
            if (id <= 0L) continue
            out.add(
                MusicDailySong(
                    id = id.toString(),
                    name = s.optString("name", ""),
                    artist = joinArtists(s.optJSONArray("ar")),
                    album = s.optJSONObject("al")?.optString("name", "").orEmpty(),
                    durationMs = s.optLong("dt", 0L)
                )
            )
        }
        return out
    }

    /**
     * 通用推荐歌单（`GET /personalized`），**不需要登录**。
     *
     * 响应示例：
     * ```
     * { "code":200, "result":[ { "id":..., "name":"...", "coverImgUrl":"...",
     *                            "playCount":..., "trackCount":..., "alg":... } ] }
     * ```
     */
    fun personalized(baseUrl: String, limit: Int = 30): List<MusicPlaylist> {
        val url = buildUrl(
            baseUrl,
            path = "/personalized",
            params = mapOf("limit" to limit.toString())
        )
        val body = httpGet(url, null)
        val root = JSONObject(body)
        if (root.optInt("code", -1) != 200) {
            throw MusicApiException("获取推荐歌单失败 (code=${root.optInt("code")})")
        }
        val arr = root.optJSONArray("result") ?: return emptyList()
        val out = ArrayList<MusicPlaylist>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val id = s.optLong("id", 0L)
            if (id <= 0L) continue
            out.add(
                MusicPlaylist(
                    id = id.toString(),
                    name = s.optString("name", ""),
                    creator = "网易云音乐推荐",
                    trackCount = s.optInt("trackCount", 0),
                    coverImgUrl = s.optString("coverImgUrl", ""),
                    playCount = s.optLong("playCount", 0L)
                )
            )
        }
        return out
    }

    /**
     * 用户歌单列表（`GET /user/playlist?uid=...`），需要登录。
     *
     * 响应示例：
     * ```
     * { "code":200, "playlist":[ { "id":..., "name":"...", "coverImgUrl":"...",
     *                              "creator":{ "userId":..., "nickname":"..." },
     *                              "trackCount":..., "playCount":...,
     *                              "createTime":..., "subscribed":false } ] }
     * ```
     */
    fun userPlaylist(baseUrl: String, cookie: String, uid: Long): List<MusicPlaylist> {
        val url = buildUrl(
            baseUrl,
            path = "/user/playlist",
            params = mapOf(
                "uid" to uid.toString(),
                "limit" to "30",
                "offset" to "0",
                "timestamp" to System.currentTimeMillis().toString()
            )
        )
        val body = httpGet(url, cookie)
        val root = JSONObject(body)
        if (root.optInt("code", -1) != 200) {
            throw MusicApiException("获取用户歌单失败 (code=${root.optInt("code")})")
        }
        val arr = root.optJSONArray("playlist") ?: return emptyList()
        val out = ArrayList<MusicPlaylist>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val id = s.optLong("id", 0L)
            if (id <= 0L) continue
            val creator = s.optJSONObject("creator")?.optString("nickname", "").orEmpty()
            out.add(
                MusicPlaylist(
                    id = id.toString(),
                    name = s.optString("name", ""),
                    creator = creator,
                    trackCount = s.optInt("trackCount", 0),
                    coverImgUrl = s.optString("coverImgUrl", ""),
                    playCount = s.optLong("playCount", 0L)
                )
            )
        }
        return out
    }

    // ---------------- 私有工具 ----------------

    private fun joinArtists(arr: org.json.JSONArray?): String {
        if (arr == null || arr.length() == 0) return ""
        val names = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val n = arr.optJSONObject(i)?.optString("name", "").orEmpty()
            if (n.isNotEmpty()) names.add(n)
        }
        return names.joinToString(" / ")
    }

    private fun buildUrl(
        baseUrl: String,
        path: String,
        params: Map<String, String>,
    ): String {
        val base = baseUrl.trim().trimEnd('/')
        val sb = StringBuilder(base).append(path)
        if (params.isNotEmpty()) {
            sb.append('?')
            var first = true
            for ((k, v) in params) {
                if (!first) sb.append('&')
                sb.append(k).append('=').append(v)
                first = false
            }
        }
        return sb.toString()
    }

    /**
     * 一次同步 HTTP GET，超时 8s。返回原始响应体（UTF-8）。
     *
     * - 不抛 IOException 之外的异常；非 2xx / body 解析失败时抛 [MusicApiException]。
     * - 失败原因包含完整 URL，便于排查服务器地址/端口配置错误。
     */
    private fun httpGet(urlStr: String, cookie: String?): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ScanPenApp/1.0 (Android)")
            if (!cookie.isNullOrBlank()) {
                setRequestProperty("Cookie", cookie)
            }
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
            if (code !in 200..299) {
                throw MusicApiException("HTTP $code: $urlStr")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 一次同步 HTTP POST（`application/x-www-form-urlencoded`），超时 8s。返回原始响应体。
     * 用于 `/login/status` 等需要把 cookie 放在 body 的接口。
     */
    private fun httpPost(urlStr: String, cookie: String?, formBody: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 8000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("User-Agent", "ScanPenApp/1.0 (Android)")
            if (!cookie.isNullOrBlank()) {
                setRequestProperty("Cookie", cookie)
            }
        }
        try {
            conn.outputStream.use { it.write(formBody.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
            if (code !in 200..299) {
                throw MusicApiException("HTTP $code: $urlStr")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }
}

/** API 调用统一异常。 */
class MusicApiException(message: String) : RuntimeException(message)
