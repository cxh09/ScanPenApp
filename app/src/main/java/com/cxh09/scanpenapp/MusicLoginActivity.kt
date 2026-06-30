package com.cxh09.scanpenapp

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.cxh09.scanpenapp.databinding.ActivityMusicLoginBinding
import com.cxh09.scanpenapp.music.api.MusicApi
import com.cxh09.scanpenapp.music.api.MusicApiException
import com.cxh09.scanpenapp.music.api.MusicSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 云音乐 → 登录页（1:4 窄屏横屏，二维码扫码登录）。
 *
 * 流程（参考 Netease API Enhanced #_3-二维码登录）
 *  1. `GET /login/qr/key`                  获取 unikey
 *  2. `GET /login/qr/create?key=...`       获取 base64 二维码并渲染
 *  3. 每 2 秒轮询 `GET /login/qr/check?key=...`
 *     - 801：等待扫码
 *     - 802：已扫码，等待确认
 *     - 803：登录成功 → 写入 [MusicSession] 并关闭
 *     - 800：已过期 → 显示「刷新二维码」
 *
 * 全部网络调用走 [Dispatchers.IO]；二维码解码为 Bitmap 也在后台，UI 切换回 [Dispatchers.Main]。
 */
class MusicLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMusicLoginBinding
    private lateinit var prefs: android.content.SharedPreferences

    private var pollJob: Job? = null
    private var currentKey: String = ""
    private var currentBaseUrl: String = ""
    private var stopped: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMusicLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyFullscreen()

        prefs = getSharedPreferences(
            MusicServerSettingsActivity.PREFS_NAME,
            Context.MODE_PRIVATE
        )

        binding.btnMusicLoginBack.setOnClickListener { finish() }
        binding.btnMusicLoginRefresh.setOnClickListener { startQrLoginFlow() }

        // 进入页面时先确保 baseUrl 已就绪
        if (!hasServerConfig()) {
            setQrStatus(getString(R.string.music_login_no_server))
        } else {
            currentBaseUrl = "http://${requireAddress()}:${requirePort()}"
        }

        startQrLoginFlow()
    }

    override fun onDestroy() {
        stopped = true
        pollJob?.cancel()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
    }

    // ---------------- 服务端地址 ----------------

    private fun hasServerConfig(): Boolean {
        val address = prefs.getString(MusicServerSettingsActivity.KEY_SERVER_ADDRESS, "")
        val port = prefs.getString(MusicServerSettingsActivity.KEY_SERVER_PORT, "")
        return !address.isNullOrBlank() && !port.isNullOrBlank()
    }

    private fun requireAddress(): String =
        prefs.getString(MusicServerSettingsActivity.KEY_SERVER_ADDRESS, "").orEmpty()

    private fun requirePort(): String =
        prefs.getString(MusicServerSettingsActivity.KEY_SERVER_PORT, "").orEmpty()

    // ---------------- 二维码扫码登录 ----------------

    private fun startQrLoginFlow() {
        pollJob?.cancel()
        binding.pbMusicLogin.visibility = View.VISIBLE
        binding.btnMusicLoginRefresh.visibility = View.GONE
        binding.ivMusicQrCode.setImageDrawable(null)
        setQrStatus(getString(R.string.music_login_status_loading))

        if (!hasServerConfig()) {
            binding.pbMusicLogin.visibility = View.GONE
            return
        }

        lifecycleScope.launch {
            try {
                val key = withContext(Dispatchers.IO) { MusicApi.qrKey(currentBaseUrl) }
                currentKey = key

                val png = withContext(Dispatchers.IO) { MusicApi.qrCreate(currentBaseUrl, key) }
                val bmp = withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(png, 0, png.size)
                }
                if (bmp == null) {
                    showQrFatal(getString(R.string.music_login_qr_decode_fail))
                    return@launch
                }
                binding.ivMusicQrCode.setImageBitmap(bmp)
                binding.pbMusicLogin.visibility = View.GONE
                setQrStatus(getString(R.string.music_login_status_wait_scan))

                // 开始轮询
                pollJob = launch { pollLoop(key) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: MusicApiException) {
                showQrFatal(e.message ?: getString(R.string.music_login_network_fail))
            } catch (_: Exception) {
                showQrFatal(getString(R.string.music_login_network_fail))
            }
        }
    }

    private suspend fun pollLoop(key: String) {
        while (!stopped) {
            try {
                val r = withContext(Dispatchers.IO) {
                    MusicApi.qrCheck(currentBaseUrl, key)
                }
                when (r.code) {
                    MusicApi.QR_STATUS_SUCCESS -> {
                        val cookie = r.cookie
                        if (cookie.isNullOrBlank()) {
                            // 极个别服务端 803 但 cookie 缺失：仍然视作登录失败，让用户刷新
                            showQrFatal(getString(R.string.music_login_no_cookie))
                            return
                        }
                        // 拉取账号信息（昵称/头像/VIP 等）；拉取失败不阻塞登录流程
                        val status = try {
                            withContext(Dispatchers.IO) {
                                MusicApi.loginStatus(currentBaseUrl, cookie)
                            }
                        } catch (e: Exception) {
                            null
                        }
                        if (status != null) {
                            MusicSession.save(this@MusicLoginActivity, status, cookie)
                        } else {
                            // /login/status 失败时仍保留 cookie，下次进入再尝试刷新账号信息
                            MusicSession.save(
                                this@MusicLoginActivity,
                                status = MusicApi.LoginStatusResult(
                                    userId = 0L,
                                    userName = "",
                                    accountType = 0,
                                    vipType = 0,
                                    createTime = 0L,
                                    nickname = "",
                                    avatarUrl = "",
                                    avatarImgId = 0L,
                                    backgroundUrl = "",
                                    signature = "",
                                    gender = 0,
                                    province = 0,
                                    city = 0,
                                    lastLoginTime = 0L,
                                    lastLoginIp = ""
                                ),
                                cookie = cookie
                            )
                        }
                        onLoginSuccess()
                        return
                    }
                    MusicApi.QR_STATUS_SCANNED -> {
                        setQrStatus(getString(R.string.music_login_status_scanned))
                    }
                    MusicApi.QR_STATUS_WAITING_SCAN -> {
                        setQrStatus(getString(R.string.music_login_status_wait_scan))
                    }
                    MusicApi.QR_STATUS_EXPIRED -> {
                        setQrStatus(getString(R.string.music_login_status_expired))
                        binding.btnMusicLoginRefresh.visibility = View.VISIBLE
                        return
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                // 单次轮询失败不打断流程，UI 继续显示「等待扫码」
            }
            delay(QR_POLL_INTERVAL_MS)
        }
    }

    private fun setQrStatus(text: String) {
        if (!stopped) binding.tvMusicLoginStatus.text = text
    }

    private fun showQrFatal(message: String) {
        if (stopped) return
        binding.pbMusicLogin.visibility = View.GONE
        binding.btnMusicLoginRefresh.visibility = View.VISIBLE
        setQrStatus(message)
    }

    // ---------------- 登录成功 ----------------

    private fun onLoginSuccess() {
        Toast.makeText(this, R.string.music_login_success, Toast.LENGTH_SHORT).show()
        finish()
    }

    // ---------------- 工具 ----------------

    private fun applyFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private companion object {
        const val QR_POLL_INTERVAL_MS = 2000L
    }
}
