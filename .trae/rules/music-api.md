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

### 3.1 `GET /search` / `GET /cloudsearch`

搜索音乐 / 专辑 / 歌手 / 歌单 / 用户等。**不需要登录**。

> `/cloudsearch` 是 `/search` 的**升级版,数据更全**,建议优先使用。

**请求参数**:

| 参数 | 必选 | 说明 |
|------|------|------|
| `keywords` | ✅ | 搜索关键词。**支持多个,以空格分隔**,如 `keywords=周杰伦 搁浅` |
| `limit` | ❌ | 返回数量,默认 `30` |
| `offset` | ❌ | 偏移量,用于分页:`(page - 1) * limit`,默认 `0` |
| `type` | ❌ | 搜索类型,默认 `1` 即单曲,见下表 |

**type 取值**:

| 值 | 含义 |
|----|------|
| `1` | 单曲 |
| `10` | 专辑 |
| `100` | 歌手 |
| `1000` | 歌单 |
| `1002` | 用户 |
| `1004` | MV |
| `1006` | 歌词 |
| `1009` | 电台 |
| `1014` | 视频 |
| `1018` | 综合 |
| `2000` | 声音(**返回字段格式会不一样**) |

**调用示例**:
```
/search?keywords=海阔天空
/cloudsearch?keywords=海阔天空
/cloudsearch?keywords=周杰伦 搁浅&type=1&limit=30&offset=0
```

**获取播放链接**:
> 搜索到歌曲后,可通过 [`/song/url/v1`](#41-get-songurlv1) 传入歌曲 id 获取具体的播放链接。

**响应字段**(单曲):`id` / `name` / `artists[].name` / `album.name` / `duration` / `fee` / `noCopyrightRcmd` / `st` ...

**响应结构**(以 `/search` 单曲为例):
```json
{
  "code": 200,
  "result": {
    "searchQcReminder": null,
    "songs": [
      {
        "id": 347230,
        "name": "海阔天空",
        "artists": [{ "id": 1116, "name": "Beyond" }],
        "album": { "id": 34617, "name": "海阔天空", "coverImgUrl": "..." },
        "duration": 326000,
        "fee": 0
      }
    ],
    "songCount": 1
  }
}
```

**已实现** ✅ [`MusicApi.search()`](file:///c:/Users/Administrator/AndroidStudioProjects/ScanPenApp/app/src/main/java/com/cxh09/scanpenapp/music/api/MusicApi.kt)
- 优先调 `/search`,结果为空时自动回退到 `/cloudsearch` 提高命中率(可选)
- 当前仅实现 **type=1 单曲**,其他 type 后续扩展

---

### 3.2 `GET /search/multimatch`

搜索多重匹配,一次返回**多类**(单曲 / 歌手 / 专辑 / 歌单 / MV 等)的命中结果。

**请求参数**:

| 参数 | 必选 | 说明 |
|------|------|------|
| `keywords` | ✅ | 搜索关键词 |

**调用示例**:
```
/search/multimatch?keywords=海阔天空
```

**响应示例**:
```json
{
  "code": 200,
  "result": {
    "album": [ { "id": 34617, "name": "海阔天空", "artist": { "name": "Beyond" }, "coverImgUrl": "..." } ],
    "artist": [ { "id": 1116, "name": "Beyond", "picUrl": "...", "alias": [] } ],
    "songs":  [ { "id": 347230, "name": "海阔天空", "artists": [...], "album": {...} } ],
    "playlists": [ { "id": ..., "name": "...", "coverImgUrl": "...", "trackCount": ..., "playCount": ... } ],
    "mv":       [ { "id": ..., "name": "...", "cover": "..." } ],
    "djRadios": [ { "id": ..., "name": "...", "picUrl": "..." } ],
    "videos":   [ { "vid": "...", "title": "..." } ]
  }
}
```

**返回字段(都是数组,可能为空)**:

| 字段 | 含义 |
|------|------|
| `songs` | 单曲 |
| `artist` | 歌手 |
| `album` | 专辑 |
| `playlists` | 歌单 |
| `mv` | MV |
| `djRadios` | 电台 |
| `videos` | 视频 |
| `userprofiles` | 用户(个别版本) |

**典型用法**:
> 搜索框边输边触发(防抖 300ms),一次性拿到多类候选,UI 上分组展示
> 「单曲 / 歌手 / 歌单」等多 tab。比连续请求多个 `type` 的 `/cloudsearch` 节省请求数。

**与 `/cloudsearch` 的区别**:
> `/cloudsearch` 单次只能查一类(`type` 互斥);`/search/multimatch` 一次返回多类,但**每类数量很少**(通常 3-10 条),适合做联想建议;真正要拉完整列表还是用 `/cloudsearch`。

**已实现** ❌ 待集成(适合做搜索框联想建议)

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

### 4.3 `GET /check/music`

判断音乐是否可用(版权 / 地区限制 / 灰色歌曲预检)。

**请求参数**:

| 参数 | 必选 | 说明 |
|------|------|------|
| `id` | ✅ | 歌曲 id |
| `br` | ❌ | 码率(检测在该码率下是否可用),默认 `999000` 即最大码率;`320k` 传 `320000`,以此类推 |

**调用示例**:
```
/check/music?id=1969519579
/check/music?id=1969519579&br=320000
```

**响应示例**:
```json
{ "success": true,  "message": "ok" }
{ "success": false, "message": "亲爱的,暂无版权" }
```

**字段含义**:

| 字段 | 含义 |
|------|------|
| `success` | `true`=可播放,`false`=不可用 |
| `message` | 提示文案(`ok` / `亲爱的,暂无版权` / `所在地区暂无版权` / `由于版权保护,您所在的地区暂时无法使用。` 等) |

**典型用法**:
> 在调用 [`/song/url/v1`](#41-get-songurlv1) 前先用 `/check/music` 过滤掉无法播放的歌曲,
> 避免返回 `url: null`。也可在歌单列表里**给不可用歌曲打灰色标记**。

**已实现** ❌ 待集成(可作为 `/song/url/v1` 前的预检过滤)

---

### 4.4 `GET /lyric`

获取歌曲歌词。**不需要登录**。

**请求参数**:

| 参数 | 必选 | 说明 |
|------|------|------|
| `id` | ✅ | 音乐 id |

**调用示例**:
```
/lyric?id=33894312
```

**响应示例**:
```json
{
  "code": 200,
  "sgc": false,
  "sfy": false,
  "qfy": false,
  "transUser": { "id": -1, "status": 0, "demand": 0, "userid": -1, "nickname": "", "uptime": 0 },
  "lyricUser": { "id": -1, "status": 0, "demand": 0, "userid": -1, "nickname": "", "uptime": 0 },
  "lrc": { "version": 15, "lyric": "[00:00.000] 作词: 林夕\n[00:01.000] 作曲: 林夕\n[00:02.000] 原谅我不再送花\n[00:05.000] 伤口应要结疤\n..." },
  "tlyric": { "version": 0, "lyric": "[00:00.000] 翻譯: ...\n..." },
  "romalrc": { "version": 0, "lyric": "" },
  "code": 200
}
```

**返回字段**:

| 字段 | 含义 |
|------|------|
| `lrc.lyric` | **原文歌词**(LRC 格式,带时间轴) |
| `tlyric.lyric` | 翻译歌词(没有则为 `null` 或空字符串) |
| `romalrc.lyric` | 罗马音歌词(没有则为 `null` 或空字符串) |
| `lyricUser` | 歌词贡献者 |
| `transUser` | 翻译贡献者 |
| `sfy` / `qfy` / `sgc` | 是否为原唱 / 是否为曲译 / 是否为歌曲合唱等标志 |

**LRC 时间轴格式**:
```
[00:02.000] 原谅我不再送花
[00:05.000] 伤口应要结疤
[01:23.456] ...
```

**典型用法**:
> 用户点开某首歌详情时,先调 `/lyric` 拿到 LRC 字符串,**自己用正则解析
> `\[\d{2}:\d{2}\.\d{2,3}\]` 得到 `time_ms + text` 列表**,然后跟 MediaPlayer 进度
> 对齐做卡拉 OK 式高亮 / 自动滚动。

**已实现** ❌ 待集成(配合播放进度做歌词高亮)

---

### 4.5 `GET /song/detail`

获取歌曲详情(支持批量)。**不需要登录**。

**请求参数**:

| 参数 | 必选 | 说明 |
|------|------|------|
| `ids` | ✅ | 音乐 id,**支持多个**,英文逗号分隔,如 `ids=347230,347231` |

**调用示例**:
```
/song/detail?ids=347230
/song/detail?ids=347230,347231
```

**响应结构**:
```json
{
  "code": 200,
  "songs": [ { "id": 347230, "name": "海阔天空", "dt": 326000, "ar": [...], "al": {...}, "fee": 0, ... } ],
  "privileges": [ { "id": 347230, "st": 0, "plLevel": "standard", "dlLevel": "standard", "maxBrLevel": "lossless" } ],
  "code": 200
}
```

**核心字段(常用)**:

| 字段 | 含义 |
|------|------|
| `id` | 歌曲 id |
| `name` | 歌曲标题 |
| `ar` | 歌手列表 `[{id, name, ...}]` |
| `al` | 专辑 `{id, name, coverImgUrl, publishTime, ...}` |
| `dt` | 歌曲时长(毫秒) |
| `alia` | 别名列表(第一个用作副标题) |
| `pop` | 热度(0~100,离散值) |
| `mv` | MV id,非零表示有 MV |
| `publishTime` | 发行时间(unix 毫秒) |
| `fee` | `0`=免费 `1`=VIP `4`=购买专辑 `8`=会员可高音质 |
| `mark` | 歌曲属性位掩码(见下) |
| `privilege.st` | `< 0` 表示灰色歌曲 |

**mark 位掩码**(用 `mark & mask` 判定):

| 掩码 | 含义 |
|------|------|
| `8192` | 立体声(待确认) |
| `131072` | 纯音乐 |
| `262144` | 支持杜比全景声 |
| `1048576` | 脏标 🅴(翻唱 / 盗版标记) |
| `17179869184` | 支持 Hi-Res |

**音质对象 `Quality`**:`br` 码率 / `size` 大小 / `level` 音质名 / `encodeType` 编码

| 字段 | 含义 |
|------|------|
| `hr` | Hi-Res |
| `sq` | 无损 |
| `h` | 高 |
| `m` | 中 |
| `l` | 低 |

**`t` 字段(歌曲类型)**:

| 值 | 含义 |
|----|------|
| `0` | 一般类型 |
| `1` | 云盘上传,网易云**无公开对应**;无权限时大部分信息为 `null`,不可播放(例: `song/1345937107`) |
| `2` | 云盘上传,网易云**有公开对应**(`s_id` 字段);无权限时只能看信息不能直接获取文件(例: `song/435005015`) |

**`privilege`(用户级权限)**:

| 字段 | 含义 |
|------|------|
| `cs` | 是否为云盘歌曲 |
| `st` | `< 0` 为灰色歌曲;`0` 正常 |
| `toast` | 是否「由于版权保护,您所在的地区暂时无法使用。」 |
| `flLevel` | 免费用户播放音质 |
| `plLevel` | 当前用户最高试听音质 |
| `dlLevel` | 当前用户最高下载音质 |
| `maxBrLevel` | 歌曲最高音质 |

**典型用法**:
> 1. 拿到搜索结果后,某些歌没有 `al` / `dt` / `publishTime` 等完整信息时,用 `/song/detail` 补全
> 2. 显示版权 / 音质 / 灰色 / 脏标 等图标
> 3. 播放前判断 `privilege.st < 0` 给出"版权受限"提示

**已实现** ❌ 待集成(配合搜索结果补全信息)

---

### 4.6 `GET /recommend/resource`

获取每日推荐歌单。**需要登录**。

**请求参数**:无。需 cookie。

**调用示例**:
```
/recommend/resource
```

**响应结构**:
```json
{
  "code": 200,
  "recommend": [
    {
      "id": 2829816518,
      "name": "私人雷达 | 周杰伦最懂你",
      "coverImgUrl": "https://p4.music.126.net/.../xxx.jpg",
      "playCount": 18000000,
      "creator": { "userId": -1, "nickname": "网易云音乐推荐" },
      "trackCount": 30
    },
    { "id": ..., "name": "...", "coverImgUrl": "...", "playCount": ..., "trackCount": ... }
  ]
}
```

**返回字段**(歌单对象,同 [`/user/playlist`](#23-get-userplaylist) 单条结构):

| 字段 | 含义 |
|------|------|
| `id` | 歌单 id(可配合 [`/playmode/intelligence/list`](#42-get-playmodeintelligencelist) 智能播放) |
| `name` | 歌单名 |
| `coverImgUrl` | 封面图 |
| `playCount` | 播放次数 |
| `trackCount` | 歌曲数 |
| `creator.nickname` | 创建者名(一般为「网易云音乐推荐」) |

**典型用法**:
> 在首页「我的歌单」下方追加「每日推荐」列表,点击后调
> [`/user/playlist?uid=...`](#23-get-userplaylist) 拿歌单详情(但官方接口对系统推荐歌单
> 可能需要 [`/playlist/detail`](#) 等接口取歌曲列表,视服务端实现)。

**已实现** ❌ 待集成,需登录态

---

### 4.7 `GET /recommend/songs`

获取每日推荐歌曲。**需要登录**。

**请求参数**:

| 参数 | 必选 | 说明 |
|------|------|------|
| `afresh` | ❌ | 是否刷新日推(强制重新生成),默认 `false`(返回上次的缓存) |

**调用示例**:
```
/recommend/songs
/recommend/songs?afresh=true
```

**响应结构**:
```json
{
  "code": 200,
  "data": {
    "dailySongs": [
      { "id": 347230, "name": "海阔天空", "ar": [...], "al": {...}, "dt": 326000, "reason": "听你听过" },
      { "id": 1381757185, "name": "...", "reason": "猜你喜欢" }
    ]
  },
  "code": 200
}
```

**`dailySongs[]` 单曲对象**(同 [`/song/detail`](#45-get-songdetail) 结构,带 `reason` 字段):

| 字段 | 含义 |
|------|------|
| `id` / `name` / `ar` / `al` / `dt` | 同歌曲详情 |
| `reason` | **推荐理由**,如「猜你喜欢」/「听你听过」/「私人雷达」等 |

**典型用法**:
> 首页默认展示「我的歌单」+ 「每日推荐歌曲」,日推 `reason` 字段
> 直接显示在歌曲副标题位置。`afresh=true` 每天只能调一次。

**已实现** ❌ 待集成,需登录态

---

### 4.8 `GET /personalized`

推荐歌单(非登录也能用,基于热门 / 运营推送)。**不需要登录**。

**请求参数**:

| 参数 | 必选 | 说明 |
|------|------|------|
| `limit` | ❌ | 取出数量,默认 `30`,**不支持 offset** |

**调用示例**:
```
/personalized
/personalized?limit=1
```

**响应结构**:
```json
{
  "code": 200,
  "result": [
    {
      "id": 2829816518,
      "name": "私人雷达 | 周杰伦最懂你",
      "coverImgUrl": "https://p4.music.126.net/.../xxx.jpg",
      "playCount": 18000000,
      "trackCount": 30,
      "alg": "hotServer"
    }
  ]
}
```

**返回字段**:

| 字段 | 含义 |
|------|------|
| `id` | 歌单 id |
| `name` | 歌单名 |
| `coverImgUrl` | 封面图 |
| `playCount` | 播放次数 |
| `trackCount` | 歌曲数 |
| `alg` | 算法标记(可能为空),如 `hotServer` / `recommend` 等 |

**与 `/recommend/resource` 的区别**:

| 对比项 | `/personalized` | `/recommend/resource` |
|--------|----------------|----------------------|
| 鉴权 | ❌ 不需要 | ✅ 需要登录 |
| 个性化 | ❌ 通用热门推荐 | ✅ 基于用户画像 |
| 数量控制 | ✅ `limit=1~N` | ❌ 固定数量 |
| 分页 | ❌ 不支持 offset | ❌ 固定数量 |
| 适合场景 | 未登录时的发现页 / 首页瀑布流 | 登录后的「为你推荐」 |

**典型用法**:
> 未登录用户的默认发现页 / 首页「推荐歌单」瀑布流;登录后
> 可用 `/recommend/resource` 替换为个性化版。

**已实现** ❌ 待集成(未登录可用的发现页)

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
| `/search` | GET | `MusicApi.search()` | 搜索(单曲,默认) |
| `/cloudsearch` | GET | — | 搜索(数据更全,作为回退) |
| `/search/multimatch` | GET | — | 搜索多重匹配(待集成,适合联想) |
| `/song/url/v1` | GET | — | 播放 URL(待集成) |
| `/playmode/intelligence/list` | GET | — | 智能播放(待集成) |
| `/check/music` | GET | — | 歌曲可用性预检(待集成) |
| `/lyric` | GET | — | 歌词(待集成) |
| `/song/detail` | GET | — | 歌曲详情(待集成,补全搜索信息) |
| `/recommend/resource` | GET | — | 每日推荐歌单(待集成,需登录) |
| `/recommend/songs` | GET | — | 每日推荐歌曲(待集成,需登录) |
| `/personalized` | GET | — | 推荐歌单(待集成,未登录可用) |

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
