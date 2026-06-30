package com.cxh09.scanpenapp.music.api

import android.content.Context
import android.content.SharedPreferences

/**
 * 云音乐会话（cookie + 账号信息）的持久化与读取。
 *
 * - 存储位置：SharedPreferences 文件名 [PREFS_NAME]。
 * - 写入时机：二维码登录成功后由 [com.cxh09.scanpenapp.MusicLoginActivity] 调用
 *   [com.cxh09.scanpenapp.music.api.MusicApi.loginStatus] 拉取账号信息后写入。
 * - 读取时机：[com.cxh09.scanpenapp.MusicActivity] 启动 / onResume 时判断登录态。
 * - 只要 cookie 未失效（未主动 [clear]）就无需重新登录；`/login/status` 重新拉取也可
 *   用于刷新账号信息。
 */
object MusicSession {

    private const val PREFS_NAME = "music_session"
    private const val KEY_COOKIE = "cookie"
    // account
    private const val KEY_USER_ID = "userId"
    private const val KEY_USER_NAME = "userName"
    private const val KEY_ACCOUNT_TYPE = "accountType"
    private const val KEY_VIP_TYPE = "vipType"
    private const val KEY_CREATE_TIME = "createTime"
    // profile
    private const val KEY_NICKNAME = "nickname"
    private const val KEY_AVATAR_URL = "avatarUrl"
    private const val KEY_AVATAR_IMG_ID = "avatarImgId"
    private const val KEY_BACKGROUND_URL = "backgroundUrl"
    private const val KEY_SIGNATURE = "signature"
    private const val KEY_GENDER = "gender"
    private const val KEY_PROVINCE = "province"
    private const val KEY_CITY = "city"
    private const val KEY_LAST_LOGIN_TIME = "lastLoginTime"
    private const val KEY_LAST_LOGIN_IP = "lastLoginIp"
    // subcount
    private const val KEY_SUB_CREATED_PLAYLIST = "subCreatedPlaylist"
    private const val KEY_SUB_PLAYLIST = "subPlaylist"
    private const val KEY_SUB_COLLECT_ARTIST = "subCollectArtist"
    private const val KEY_SUB_COLLECT_MV = "subCollectMv"
    private const val KEY_SUB_COLLECT_DJRADIO = "subCollectDjRadio"
    private const val KEY_SUB_ALBUM = "subAlbum"
    private const val KEY_SUB_MV = "subMv"
    private const val KEY_SUB_DJRADIO = "subDjRadio"
    private const val KEY_SUB_ARTIST = "subArtist"
    private const val KEY_SUB_PROGRAM = "subProgram"

    /** 是否已登录（cookie 非空即视为已登录）。 */
    fun isLoggedIn(ctx: Context): Boolean = cookie(ctx).isNotBlank()

    /** 读取 cookie。 */
    fun cookie(ctx: Context): String = prefs(ctx).getString(KEY_COOKIE, "").orEmpty()

    fun userId(ctx: Context): Long = prefs(ctx).getLong(KEY_USER_ID, 0L)
    fun userName(ctx: Context): String = prefs(ctx).getString(KEY_USER_NAME, "").orEmpty()
    fun accountType(ctx: Context): Int = prefs(ctx).getInt(KEY_ACCOUNT_TYPE, 0)
    fun vipType(ctx: Context): Int = prefs(ctx).getInt(KEY_VIP_TYPE, 0)
    fun createTime(ctx: Context): Long = prefs(ctx).getLong(KEY_CREATE_TIME, 0L)

    fun nickname(ctx: Context): String = prefs(ctx).getString(KEY_NICKNAME, "").orEmpty()
    fun avatarUrl(ctx: Context): String = prefs(ctx).getString(KEY_AVATAR_URL, "").orEmpty()
    fun avatarImgId(ctx: Context): Long = prefs(ctx).getLong(KEY_AVATAR_IMG_ID, 0L)
    fun backgroundUrl(ctx: Context): String = prefs(ctx).getString(KEY_BACKGROUND_URL, "").orEmpty()
    fun signature(ctx: Context): String = prefs(ctx).getString(KEY_SIGNATURE, "").orEmpty()
    fun gender(ctx: Context): Int = prefs(ctx).getInt(KEY_GENDER, 0)
    fun province(ctx: Context): Int = prefs(ctx).getInt(KEY_PROVINCE, 0)
    fun city(ctx: Context): Int = prefs(ctx).getInt(KEY_CITY, 0)
    fun lastLoginTime(ctx: Context): Long = prefs(ctx).getLong(KEY_LAST_LOGIN_TIME, 0L)
    fun lastLoginIp(ctx: Context): String = prefs(ctx).getString(KEY_LAST_LOGIN_IP, "").orEmpty()

    // ---- subcount ----
    fun subCreatedPlaylist(ctx: Context): Int = prefs(ctx).getInt(KEY_SUB_CREATED_PLAYLIST, 0)
    fun subPlaylist(ctx: Context): Int = prefs(ctx).getInt(KEY_SUB_PLAYLIST, 0)
    fun subCollectArtist(ctx: Context): Int = prefs(ctx).getInt(KEY_SUB_COLLECT_ARTIST, 0)
    fun subCollectMv(ctx: Context): Int = prefs(ctx).getInt(KEY_SUB_COLLECT_MV, 0)
    fun subCollectDjRadio(ctx: Context): Int = prefs(ctx).getInt(KEY_SUB_COLLECT_DJRADIO, 0)
    fun subAlbum(ctx: Context): Int = prefs(ctx).getInt(KEY_SUB_ALBUM, 0)
    fun subMv(ctx: Context): Int = prefs(ctx).getInt(KEY_SUB_MV, 0)
    fun subDjRadio(ctx: Context): Int = prefs(ctx).getInt(KEY_SUB_DJRADIO, 0)
    fun subArtist(ctx: Context): Int = prefs(ctx).getInt(KEY_SUB_ARTIST, 0)
    fun subProgram(ctx: Context): Int = prefs(ctx).getInt(KEY_SUB_PROGRAM, 0)

    /** 我的歌单总数 = 我创建的 + 我收藏的。 */
    fun totalPlaylist(ctx: Context): Int = subCreatedPlaylist(ctx) + subPlaylist(ctx)

    /** 写入用户统计（`/user/subcount`）。不影响 cookie 和账号信息。 */
    fun saveSubcount(ctx: Context, sub: MusicApi.UserSubcount) {
        prefs(ctx).edit()
            .putInt(KEY_SUB_CREATED_PLAYLIST, sub.createdPlaylistCount)
            .putInt(KEY_SUB_PLAYLIST, sub.subPlaylistCount)
            .putInt(KEY_SUB_COLLECT_ARTIST, sub.collectArtistCount)
            .putInt(KEY_SUB_COLLECT_MV, sub.collectMvCount)
            .putInt(KEY_SUB_COLLECT_DJRADIO, sub.collectDjRadioCount)
            .putInt(KEY_SUB_ALBUM, sub.subAlbumCount)
            .putInt(KEY_SUB_MV, sub.mvCount)
            .putInt(KEY_SUB_DJRADIO, sub.djRadioCount)
            .putInt(KEY_SUB_ARTIST, sub.artistCount)
            .putInt(KEY_SUB_PROGRAM, sub.programCount)
            .apply()
    }

    /**
     * 写入完整登录会话。任意字段为空时使用默认值（0 / ""），已存在的 cookie 不会被覆盖为
     * 空字符串。
     */
    fun save(ctx: Context, status: MusicApi.LoginStatusResult, cookie: String) {
        if (cookie.isBlank()) return
        prefs(ctx).edit()
            .putString(KEY_COOKIE, cookie)
            // account
            .putLong(KEY_USER_ID, status.userId)
            .putString(KEY_USER_NAME, status.userName)
            .putInt(KEY_ACCOUNT_TYPE, status.accountType)
            .putInt(KEY_VIP_TYPE, status.vipType)
            .putLong(KEY_CREATE_TIME, status.createTime)
            // profile
            .putString(KEY_NICKNAME, status.nickname)
            .putString(KEY_AVATAR_URL, status.avatarUrl)
            .putLong(KEY_AVATAR_IMG_ID, status.avatarImgId)
            .putString(KEY_BACKGROUND_URL, status.backgroundUrl)
            .putString(KEY_SIGNATURE, status.signature)
            .putInt(KEY_GENDER, status.gender)
            .putInt(KEY_PROVINCE, status.province)
            .putInt(KEY_CITY, status.city)
            .putLong(KEY_LAST_LOGIN_TIME, status.lastLoginTime)
            .putString(KEY_LAST_LOGIN_IP, status.lastLoginIp)
            .apply()
    }

    /** 清除登录会话（用户主动登出时调用）。 */
    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
