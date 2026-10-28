# 局域影库 · LAN Cinema

电脑用网页，安卓用 App。在同一局域网内，直接浏览、播放和下载彼此共享的视频。
视频不经过云端，也不需要先上传到电脑。MIT 开源。

[下载安卓 APK](https://github.com/jixn-hu/lan-cinema/releases/latest) · [源码](https://github.com/jixn-hu/lan-cinema)

## 支持什么

- **自动发现设备**：电脑和安卓使用同一个 mDNS / DNS-SD 协议，支持手动输入地址补充连接。
- **选择性共享**：电脑添加共享目录；安卓使用系统文件选择器选择单个或多个文件，随时停止共享。无需授予整个存储空间的访问权。
- **设备直接传输**：电脑 → 手机、手机 → 手机、手机 → 电脑网页。HTTP Range 随机读取，64 位文件位置，适合 1–3GB 及更大的视频。
- **安卓原生播放**：Media3 / ExoPlayer，边播边缓存、拖动快进、0.5–3 倍速、长按临时加速、双指缩放、画面适应 / 填满、横竖屏切换。双击左右区域快退 / 快进 10 秒。
- **网页播放**：倍速、长按加速、画面缩放、展开、全屏和键盘控制。空格暂停，左右方向键快进退。
- **续播**：网页与安卓向源设备同步播放位置；安卓另外保存本地位置，离线时仍可续播。
- **缓存上限可调**：默认 **20GB**，设置中可调整为 1–1024GB。按最近使用时间淘汰临时视频片段；剩余空间不足 256MB 时提前清理临时缓存。
- **完整下载与离线播放**：原生后台下载、暂停 / 继续、失败重试、App 重启后继续，最多同时下载两个文件。完整下载与播放共用磁盘缓存，已缓存的片段可以复用。下载项（含未完成项）受到保护，不计入临时缓存上限；清缓存会保留下载项。
- **密码解锁**：安卓设置中启用、修改或关闭。冷启动或离开 App 超过 30 秒需要密码；PBKDF2-SHA256 加随机盐保存校验值，连续输错 5 次锁定 30 秒。

## 怎么用

1. 电脑启动服务，浏览器打开本机地址并添加共享目录。
2. 手机安装 Releases 中的 APK，连接同一 Wi-Fi，打开 App。允许通知，可在后台保持共享与下载。
3. 在“附近设备”选择电脑或其他安卓手机，点视频立即播放，点下载按钮保存离线观看。
4. 手机想共享视频时，点“选择文件共享”，选择已经保存在本机的文件。其他设备的列表中即可看到。
5. App 的“设置与共享”中修改缓存上限或设置解锁密码。“下载与缓存”中管理下载、离线观看。

安卓支持 Android 8.0 及以上。保持共享 App 运行即可直接访问；后台共享会显示常驻通知。
手机后台限制较强时，可在系统电池设置中允许局域影库后台运行。

## 电脑启动

Windows 安装 Python 3.10+ 后，双击 `run.cmd`，自动创建虚拟环境、安装依赖并启动。

手动启动：

```powershell
python -m venv .venv
.venv\Scripts\python -m pip install -r requirements.txt
.venv\Scripts\python serve.py 8000
```

macOS / Linux：

```sh
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
FB_PORT=8000 .venv/bin/uvicorn app:app --host 0.0.0.0 --port 8000
```

电脑打开 `http://127.0.0.1:8000`。同一局域网的设备可以直接打开
`http://电脑IP:8000`，或使用 App 自动发现。安卓共享服务端口是 **8765**。
使用其他端口启动 Uvicorn 时请同步设置 `FB_PORT`，让设备广播正确端口。

共享目录可通过页面管理，也可修改本机的 `config.json`：

```json
{"dirs": ["D:\\视频", "E:\\电影"]}
```

可选安装 FFmpeg，用于生成电脑视频封面；没有 FFmpeg 仍可浏览、播放和下载。
安卓封面由系统生成。

## 连接、速度与格式

建议电脑接网线、手机使用 5GHz / 6GHz Wi-Fi。实际速度取决于 Wi-Fi、磁盘和设备性能；
程序直接流式传输，不将整部视频装入内存，不做转码。浏览器下载使用浏览器自身的下载器；
同一个下载链接也支持 IDM、Aria2 等分段下载工具。

第一次读取某个视频片段仍需要网络。安卓缓存保存在 App 私有目录，不会把几 GB 文件
通过 JavaScript 或 WebView 搬运。未命中缓存时立即走 HTTP，跳转不要求先下载前面的内容。
完整离线视频位于 App 的缓存数据库中，由“下载与缓存”管理，也可通过“导出文件”保存到自己选择的目录，供其他播放器使用或重新共享。卸载 App / 清除数据会删除 App 内下载与缓存。
浏览器的 HTTP 缓存由浏览器管理，20GB 设置只作用于安卓磁盘缓存。

自动发现需要放行 **UDP 5353**，数据传输需放行电脑的 **TCP 8000**（或自定端口）。
路由器的 AP / 访客隔离、不同子网、部分 VPN 可能阻止自动发现或直连；可在设置中手动输入地址。
网页读取附近设备的列表来自正在运行的电脑服务，浏览器本身不会扫描局域网。

网页的 MP4 / WebM 支持以及 App 的 MKV / HEVC 等格式支持，都取决于设备的解码能力。
本项目不承诺所有视频编码都可播放；不兼容时可下载到支持该格式的播放器观看。
文件内容变化后会拒绝旧版本的续传请求，避免把新旧数据拼在一起，请刷新列表重新下载。

## 密码与共享范围

解锁密码保护**安卓 App 的本机界面**。已经主动共享的文件在 App 锁定时仍可被局域网设备访问，
这使关屏传输可以继续。需要收回文件时点击“停止共享”。它不等同于网络访问密码或加密传输。
首次安装请在设置中自行设置解锁密码；没有预设密码。

电脑服务可选设置环境变量 `FB_TOKEN`，再用 `serve.py` 启动，设备首次连接的地址使用
`http://IP:8000/?t=你的令牌`。请仅在可信局域网使用，不要直接向公网开放。
密码忘记后没有后门，可通过系统清除 App 数据恢复，但会同时删除下载、配置和缓存。

## 构建安卓

需要 JDK 17+、Android SDK Platform 36 和 Build Tools 36。源码自带 Gradle Wrapper。
`android/local.properties` 写入本机 SDK 地址，例如 `sdk.dir=C\:/Android/Sdk`，或设置 `ANDROID_HOME`。

```sh
cd android
./gradlew testDebugUnitTest assembleDebug   # Windows: gradlew.bat
```

调试 APK：`android/app/build/outputs/apk/debug/app-debug.apk`。
构建会自动把 `static/` 的页面同步到 App assets，不依赖在线 CDN。
Windows 中文路径可能触发部分 JDK 测试进程的路径问题，可设置
`LAN_CINEMA_BUILD_DIR=D:/tmp/lancinema-build` 将构建输出放在英文路径。

正式签名使用环境变量，不把密钥写入仓库：

```sh
export LAN_CINEMA_KEYSTORE=/private/path/release.jks
export LAN_CINEMA_STORE_PASSWORD=...
export LAN_CINEMA_KEY_ALIAS=lancinema
export LAN_CINEMA_KEY_PASSWORD=...
./gradlew assembleRelease
```

请自行安全备份密钥，后续更新需要同一个签名。GitHub Actions 自动运行 Python / 安卓单元测试并提供调试 APK artifact；
正式安装包在 Releases 中发布。自行构建的调试 APK 与正式包签名不同，不能直接覆盖安装。

## 验证

```sh
python -m pytest -q
cd android && ./gradlew testDebugUnitTest
```

后端测试覆盖 3GB 以上偏移、HEAD、后缀 Range、非法范围、四路拼接、跨设备 CORS、目录隔离、
源文件变更、设备发现与续播。安卓 JVM 测试覆盖 64 位 Range、密码哈希、缓存保护与淘汰。
`tests/check_ui.py` 使用本机 Chrome + Playwright，配合 `tests/ui_server.py` 与隔离的测试媒体检查桌面 / 手机布局及播放控制。

协议：`_lanvideo._tcp.local.`，TXT 属性 `id/name/platform/version`。
主要接口：`/api/device`、`/api/devices`、`/api/files`、`/api/file/{id}/stream`、`/api/progress`。

## License

[MIT](LICENSE)。第三方依赖保留各自许可证，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
