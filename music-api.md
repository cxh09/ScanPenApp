# 云音乐 API 参考

> 本文档汇总 [NeteaseCloudMusicApi Enhanced](https://neteasecloudmusicapienhanced.js.org/) 在
> ScanPenApp 云音乐模块中涉及的全部端点。`baseUrl` 由用户在「服务器设置」中配置
> （`http://<address>:<port>`），所有示例均省略 `baseUrl` 前缀。

## 通用约定

| 项目 | 说明 |
|------|------|
| 请求方式 | 除 `/login/status` 外均为 `GET`;`/login/status` 为 `POST` |
| 鉴权 | 已登录接口需要在 `Cookie` 请求头中带上 `MUSIC_U=...; __csrf=...` |
| 响应格式 | 统一 `application/json`,业务数据在 `data` / `result` / `subCount` 等字段 |
| 业务码 | 顶层 `code: 200` 表示成功;`code: 301/401/...` 表示登录态失效 |
| 二维码状态码 | `800` 过期 / `801` 等待扫码 / `802` 已扫码 / `803` 登录成功 |
| 防缓存 | GET 请求统一追加 `?timestamp=<currentTimeMillis>`,POST body 中带 `cookie` |

---

## 1. 登录 / 登出

### 1.1 `GET /login/qr/key`

获取二维码登录用的 unikey。

**请求参数**:无。

**响应示例**:
```json
{ "code": 200, "data": { "unikey": "..." } }
```

**已实现** ✅ [`MusicApi.qrKey()`](file:///c:/Users/Administrator/AndroidStudioProjects/ScanPenApp/app/src/main/java/com/cxh09/scanpenapp/music/api/MusicApi.kt)

---

### 1.2 `GET /login/qr/create`

根据 unikey 生成二维码图片。

**请求参数**:

| 参数 | 必选 | 说明 |
|------|------|------|
| `key` | ✅ | 上一步拿到的 unikey |
| `qrimg` | ✅ | 传 `true`,返回 `data.qrimg` 为 `data:image/png;base64,...` |
| `platform` | ❌ | `web` / `iphone` / `android`,网易云官方基于此决定扫码端 UI |

**响应示例**:
```json
{ "code": 200, "data": { "qrimg": "data:image/png;base64,iVBORw0KGgo..." } }
```

**已实现** ✅ [`MusicApi.qrCreate()`](file:///c:/Users/Administrator/AndroidStudioProjects/ScanPenApp/app/src/main/java/com/cxh09/scanpenapp/music/api/MusicApi.kt)

---

### 1.3 `GET /login/qr/check`

轮询扫码状态。**每 2 秒一次**。

**请求参数**:

| 参数 | 必选 | 说明 |
|------|------|------|
| `key` | ✅ | unikey |

**响应示例**:
```json
{ "code": 803, "message": "授权登陆成功", "cookie": "MUSIC_U=...; NMTID=...; __csrf=..." }
```

**状态码**:

| code | 含义 |
|------|------|
| `800` | 二维码已过期,需要重新 `qrKey` + `qrCreate` |
| `801` | 等待用户扫码 |
| `802` | 已扫码,等待用户在手机上点「确认登录」 |
| `803` | 登录成功,响应里 `cookie` 字段为最终 cookie 字符串(**字符串,非对象**) |

**已实现** ✅ [`MusicApi.qrCheck()`](file:///c:/Users/Administrator/AndroidStudioProjects/ScanPenApp/app/src/main/java/com/cxh09/scanpenapp/music/api/MusicApi.kt)

---

### 1.4 `POST /login/status`

登录后调用,获取登录态 + 完整账号 / 资料信息。

**请求方式**:`POST`

**请求参数**(body,`application/x-www-form-urlencoded`):

| 参数 | 必选 | 说明 |
|------|------|------|
| `cookie` | ✅ | `/login/qr/check` 返回的 cookie 字符串 |

**响应示例**(已登录):
```json
{
  "code": 200,
  "data": {
    "account": {
      "id": 6472660834,
      "userName": "1_********256",
      "type": 1,
      "status": 0,
      "whitelistAuthority": 0,
      "createTime": 1647072344505,
      "tokenVersion": 3,
      "ban": 0,
      "baoyueVersion": 0,
      "donateVersion": 0,
      "vipType": 11,
      "anonimousUser": false,
      "paidFee": false
    },
    "profile": {
      "userId": 6472660834,
      "userType": 0,
      "nickname": "饿鼠小蛋糕ww",
      "avatarImgId": 109951171939933710,
      "avatarUrl": "https://p4.music.126.net/.../109951171939933719.jpg",
      "backgroundImgId": 109951169342933650,
      "backgroundUrl": "https://p4.music.126.net/.../109951169342933654.jpg",
      "signature": "~",
      "createTime": 1647072344651,
      "userName": "1_********256",
      "accountType": 1,
      "shortUserName": "********256",
      "birthday": 1244563200000,
      "authority": 0,
      "gender": 1,
      "accountStatus": 0,
      "province": 440000,
      "city": 440300,
      "authStatus": 0,
      "djStatus": 0,
      "locationStatus": 10,
      "vipType": 110,
      "lastLoginTime": 1782747436369,
      "lastLoginIP": "192.168.68.161",
      "viptypeVersion": 1771172982268,
      "authenticationTypes": 0
    }
  }
}
```

**已实现** ✅ [`MusicApi.loginStatus()`](file:///c:/Users/Administrator/AndroidStudioProjects/ScanPenApp/app/src/main/java/com/cxh09/scanpenapp/music/api/MusicApi.kt)

---

### 1.5 `GET /logout`

退出登录,服务端使 cookie 失效。

**请求参数**:无。需在 `Cookie` 请求头中带登录态 cookie。

**响应示例**:
```json
{ "code": 200 }
```

**注意**:
- 服务端调用失败不阻塞本地 `MusicSession.clear()`,保证用户能退出
- 已实现 ✅ [`MusicApi.logout()`](file:///c:/Users/Administrator/AndroidStudioProjects/ScanPenApp/app/src/main/java/com/cxh09/scanpenapp/music/api/MusicApi.kt)

---

## 2. 用户信息

### 2.1 `GET /user/account`

获取当前登录账号信息(轻量版,主要返回 `account`)。

**请求参数**:无。需 cookie。

**响应示例**(已登录):
```json
{
  "code": 200,
  "account": { "id": 6472660834, "userName": "1_********256", "vipType": 11, ... },
  "profile": { "userId": 6472660834, "nickname": "饿鼠小蛋糕ww", "avatarUrl": "...", ... }
}
```

**注意**:
- 未登录时 `code=200` 但 `account=null`,需要靠这个判断登录态
- 与 `/login/status` 区别:此接口不会因为未登录而报错,直接返回空 account

**已实现** ✅ [`MusicApi.userAccount()`](file:///c:/Users/Administrator/AndroidStudioProjects/ScanPenApp/app/src/main/java/com/cxh09/scanpenapp/music/api/MusicApi.kt)(代码已集成但暂未主动调用,留作 cookie 失效时验证)

---

### 2.2 `GET /user/subcount`

获取用户统计:歌单 / 收藏 / MV / DJ / 专辑 数量。

**请求参数**:无。需 cookie。

**响应示例**:
```json
{
  "code": 200,
  "subCount": {
    "programCount": 0,
    "djRadioCount": 0,
    "mvCount": 0,
    "artistCount": 0,
    "newProgramCount": 0,
    "createRadioCount": 0,
    "createdPlaylistCount": 1,
    "subPlaylistCount": 5,
    "cocoaPlaylistCount": 0,
    "collectPlaylistCount": 0,
    "collectMvCount": 0,
    "collectArtistCount": 10,
    "collectDjRadioCount": 0,
    "subAlbumCount": 2
  }
}
```

**字段含义**:

| 字段 | 含义 |
|------|------|
| `createdPlaylistCount` | 我创建的歌单 |
| `subPlaylistCount` | 我收藏的歌单 |
| `collectArtistCount` | 收藏的歌手 |
| `collectMvCount` | 收藏的 MV |
| `collectDjRadioCount` | 收藏的电台 |
| `subAlbumCount` | 收藏的专辑 |
| `mvCount` / `djRadioCount` / `artistCount` / `programCount` | 个人作品 / 订阅总数 |

**已实现** ✅ [`MusicApi.userSubcount()`](file:///c:/Users/Administrator/AndroidStudioProjects/ScanPenApp/app/src/main/java/com/cxh09/scanpenapp/music/api/MusicApi.kt)
+ `MusicActivity.onResume()` 自动刷新,标题显示「我的歌单 (N)」

---

### 2.3 `GET /user/playlist`

获取用户歌单列表。

**请求参数**:

| 参数 | 必选 | 说明 |
|------|------|------|
| `uid` | ✅ | 用户 id(可从 `account.id` 拿到) |
| `limit` | ❌ | 返回数量,默认 `30` |
| `offset` | ❌ | 偏移量,用于分页:`(page - 1) * limit`,默认 `0` |

**调用示例**:
```
/user/playlist?uid=32953014
/user/playlist?uid=32953014&limit=30&offset=30
```

**响应字段**(歌单对象):`id` / `name` / `coverImgUrl` / `creator` / `trackCount` / `playCount` / `createTime` / `subscribed` ...

**已实现** ❌ 待集成,接口已规划

---

## 3. 搜索

### 3.1 `GET /search`

搜索单曲(后续可扩展到歌单 / 歌手 / 专辑)。

**请求参数**:

| 参数 | 必选 | 说明 |
|------|------|------|
| `keywords` | ✅ | 关键词(URL 编码) |
| `limit` | ❌ | 返回数量,默认 `30` |
| `type` | ❌ | `1`=单曲 `10`=专辑 `100`=歌手 `1000`=歌单 `1014`=视频 |

**响应字段**(单曲):`id` / `name` / `artists[].name` / `album.name` / `duration` / `fee` ...

**已实现** ✅ [`MusicApi.search()`](file:///c:/Users/Administrator/AndroidStudioProjects/ScanPenApp/app/src/main/java/com/cxh09/scanpenapp/music/api/MusicApi.kt)(单曲)

---

## 4. 播放相关

### 4.1 `GET /song/url/v1`

获取歌曲实际可播放 URL(新版,支持多音质)。

**请求参数**:

| 参数 | 必选 | 说明 |
|------|------|------|
| `id` | ✅ | 音乐 id。**支持多个**,用英文逗号分隔:`id=1969519579,33894312` |
| `level` | ❌ | 音质等级,默认 `standard` |
| `unblock` | ❌ | 是否使用歌曲解锁(对付灰色歌曲),`true` / `false` |

**level 取值**:

| 值 | 含义 |
|----|------|
| `standard` | 标准 |
| `higher` | 较高 |
| `exhigh` | 极高 |
| `lossless` | 无损 |
| `hires` | Hi-Res |
| `jyeffect` | 高清环绕声 |
| `sky` | 沉浸环绕声 |
| `dolby` | 杜比全景声(**需要设备支持,不同设备可能返回不同码率**) |
| `jymaster` | 超清母带 |

**调用示例**:
```
/song/url/v1?id=1969519579&level=exhigh
/song/url/v1?id=1969519579,33894312&level=lossless
```

**重要提示**:
> 杜比全景声 / 超清母带等高音质需要设备支持;为了返回正常码率的 URL,`Cookie`
> 请求头中**应带上 `os=pc`**,否则可能拿到低码率。

**响应示例**:
```json
{
  "code": 200,
  "data": [
    {
      "id": 1969519579,
      "url": "https://vip.xxx.netease.com/.../music.mp3",
      "br": 320000,
      "size": 7890123,
      "md5": "...",
      "code": 200,
      "expi": 1200,
      "type": "mp3",
      "level": "exhigh",
      "encodeType": "mp3",
      "time": 235000
    }
  ]
}
```

**字段含义**:

| 字段 | 含义 |
|------|------|
| `id` | 歌曲 id |
| `url` | 实际播放 URL,**可能为 `null`(VIP / 版权 / 灰色)** |
| `br` | 码率(bps) |
| `size` | 文件字节数 |
| `time` | 时长(毫秒) |
| `level` | 实际命中的音质 |
| `encodeType` | 编码:`mp3` / `flac` / `aac` 等 |
| `expi` | 链接过期时间(秒) |
| `code` | 单曲状态,`200`=有 URL,其他 = 不可用 |
| `fee` | `0`=免费 `1`=VIP `8`=专辑购买 |

**已实现** ❌ 待集成(需要配合 MediaPlayer / ExoPlayer 实现播放)

---

### 4.2 `GET /playmode/intelligence/list`

心动模式 / 智能播放列表。

**请求参数**:

| 参数 | 必选 | 说明 |
|------|------|------|
| `id` | ✅ | 歌曲 id(作为锚点) |
| `pid` | ✅ | 歌单 id |
| `sid` | ❌ | 要开始播放的歌曲 id(用于断点续播) |

**调用示例**:
```
/playmode/intelligence/list?id=33894312&pid=24381616
/playmode/intelligence/list?id=33894312&pid=24381616&sid=36871368
```

**已实现** ❌ 待集成,接口已记录

---

## 5. 已实现状态总览

| 端点 | 方法 | 代码位置 | 用途 |
|------|------|----------|------|
| `/login/qr/key` | GET | `MusicApi.qrKey()` | 生成扫码 key |
| `/login/qr/create` | GET | `MusicApi.qrCreate()` | 生成二维码图片 |
| `/login/qr/check` | GET | `MusicApi.qrCheck()` | 轮询扫码状态 |
| `/login/status` | POST | `MusicApi.loginStatus()` | 登录后拉账号信息 |
| `/user/account` | GET | `MusicApi.userAccount()` | 轻量账号查询(已写未调) |
| `/user/subcount` | GET | `MusicApi.userSubcount()` | 用户统计(onResume 自动刷新) |
| `/user/playlist` | GET | — | 用户歌单(待集成) |
| `/logout` | GET | `MusicApi.logout()` | 服务端登出 |
| `/search` | GET | `MusicApi.search()` | 搜索单曲 |
| `/song/url/v1` | GET | — | 播放 URL(待集成) |
| `/playmode/intelligence/list` | GET | — | 智能播放(待集成) |

---

## 6. 错误码与排查

| 现象 | 可能原因 |
|------|---------|
| `HTTP 400` + `phone=...&captcha=...` | 手机号 / 短信登录被风控,网易云第三方已不再支持,本 App 不再提供此登录方式 |
| `HTTP 400` + `phone=...` | `captcha/sent` 风控,同上 |
| `/login/qr/check` 返回 `code: -460` / `-461` | 网络异常或被风控,需要重新 `qrKey` |
| `/user/subcount` 返回 `code: 301` | cookie 失效,需要重新扫码登录 |
| `data.image/png;base64,...` 解码失败 | 极小概率服务端返回空字符串,已用 `music_login_qr_decode_fail` 提示 |
| `/logout` 失败 | 网络问题,本地 session 仍会清掉,下次进入时需重新登录 |

---

## 7. 客户端持久化

[MusicSession](file:///c:/Users/Administrator/AndroidStudioProjects/ScanPenApp/app/src/main/java/com/cxh09/scanpenapp/music/api/MusicSession.kt) 把所有信息都放进 `SharedPreferences(music_session)`:

| 字段 | 用途 |
|------|------|
| `cookie` | 鉴权凭证,失效前无需重新登录 |
| `userId` / `userName` / `nickname` | 顶栏头像 / 名称展示 |
| `avatarUrl` | 顶栏圆形头像 |
| `vipType` / `accountType` / `createTime` | 后续 VIP 角标等扩展 |
| `gender` / `city` / `province` / `signature` / `backgroundUrl` | 个人资料卡(后续扩展) |
| `subCreatedPlaylist` / `subPlaylist` / ... | 「我的歌单 (N)」统计 |

**生命周期**:
1. 扫码 803 → `MusicSession.save(status, cookie)` 写入全部字段
2. `onResume` → `applyLoginState()` / `applySubcountTitle()` 从本地恢复显示
3. 顶栏头像:`loadAvatarInto(avatarUrl, ivAccount)` 用 `HttpURLConnection` + `RoundedBitmapDrawable` 渲染成圆形(不引入 Glide/Coil)
4. 退出登录 → `MusicApi.logout` 失败也无碍,本地 `MusicSession.clear()` 清空所有键
