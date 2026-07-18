# 妮努猫

Android TV 应用，用于播放指定微博博主的视频列表。纯 Android 单 APK，无需独立后端。

## 功能

- 3 列网格视频列表，遥控器浏览
- ExoPlayer 全屏播放，自动连续播放下一条
- 播放到列表末尾前自动预取下一页，无缝衔接
- 直接调用 `m.weibo.cn` 移动端接口
- 内置局域网配置页（NanoHTTPD），手机扫码或浏览器修改 UID / Cookie
- TV 设置页支持遥控器直接输入 UID / Cookie

## 构建

### 正式版（推荐安装到电视）

```bash
./gradlew assembleRelease
```

输出 APK：

```
app/build/outputs/apk/release/app-release.apk
```

> Release 版不会打印接口请求日志，设置页也不显示调试日志区。  
> 已使用 debug 证书签名，便于 sideload 到电视。若要上架应用商店，需替换为独立 release.keystore。

### 调试版

```bash
./gradlew assembleDebug
```

输出 APK：`app/build/outputs/apk/debug/app-debug.apk`  
调试版会在 Logcat（tag: `NinumaoDebug`）和设置页底部输出接口日志。

## 安装到电视

### 方式一：adb 安装

1. 电视开启「开发者选项」→「USB 调试」或「网络调试」
2. 电脑与电视在同一局域网：

```bash
adb connect <电视IP>:5555
adb install -r app/build/outputs/apk/release/app-release.apk
```

若 adb 报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，先卸载旧版再装：

```bash
adb uninstall com.example.ninumao
adb install app/build/outputs/apk/release/app-release.apk
```

### 方式二：U 盘安装

将 APK 拷贝到 U 盘，插入电视，用文件管理器打开安装。

### 方式三：Android Studio

选择 **Android TV** 设备或模拟器，Run 即可（默认 Debug 包）。

## 使用方法

### 1. 首次配置博主 UID

1. 首次打开 App，按提示进入 **设置**
2. 在 **博主 UID** 输入框填入微博用户 ID（`weibo.com/u/` 后面的纯数字）
3. 点击 **保存**，返回主页会自动加载视频列表

进入设置的方式：

- 首次启动：引导页点击「打开设置」
- 已配置后：遥控器**上键**聚焦标题旁设置按钮，按 OK 进入
- 快捷键：遥控器 **Menu** / **Search** / **Info** 键
- 设置页支持保存最近 10 个博主（显示昵称），可一键切换
- 调试日志默认关闭
- 图片缓存：磁盘上限约 150MB；超过 **7 天**未更新的磁盘缓存会在启动/退后台时清理；退后台时仅清内存缓存与日志，保留近期封面加速下次打开

### 2. 配置 Cookie（可选）

若列表为空、加载失败或播放失败，通常需要登录 Cookie：

1. 电脑 Chrome 打开 [m.weibo.cn](https://m.weibo.cn) 并登录
2. F12 → Network → 刷新 → 任意请求 → Request Headers → 复制 **Cookie** 整行
3. 粘贴到 TV 设置页的 **Cookie** 输入框 → **保存**

### 3. 手机扫码远程配置

1. 确保手机与电视连接 **同一 WiFi**
2. 打开 TV 设置页，扫描右侧二维码（或手动输入显示的地址）
3. 在手机浏览器中修改 UID / Cookie，保存后 TV 会自动刷新列表

配置服务默认端口：`8765`（可在 `AppConfig.DEFAULT_WEB_PORT` 修改）

### 4. 浏览与播放

| 操作 | 遥控器按键 |
|------|-----------|
| 移动焦点 | 方向键 ↑↓←→ |
| 进入播放 | OK / 中心键 |
| 暂停 / 继续 | OK 或 播放/暂停键 |
| 快进 10 秒 | 右键 或 快进键 |
| 快退 10 秒 | 左键 或 快退键 |
| 上一条 / 下一条视频 | PageUp / PageDown |
| 显示播放遮罩 | 返回键（按一次） |
| 退出播放 | 返回键（遮罩显示后 1.5 秒内再按一次） |
| 进入设置 | 上键到标题设置按钮 / Menu / Search / Info |

播放特性：

- 进入播放时不自动弹出控制栏，画面全屏
- 当前视频播完自动播下一条
- 剩余 3 条时后台预取下一页，列表衔接不断档
- 退出播放后，主页光标回到刚才播放的视频位置

## 获取博主 UID

浏览器打开博主主页，地址栏中 `weibo.com/u/1234567890` 的数字部分即为 UID。

也可在浏览器访问（需 Cookie 时带上）：

```
https://m.weibo.cn/api/container/getIndex?type=uid&value=你的UID
```

返回 JSON 中 `"ok":1` 表示 UID 有效。

## 常见问题

**安装时提示「应用未安装」**

1. **先卸载旧版**：电视上若已装过 Debug 版或旧 Release 版，且签名不同，会安装失败。在电视「设置 → 应用」里卸载「妮努猫」，或用 adb：`adb uninstall com.example.ninumao`
2. **确认 APK 已签名**：应安装 `app-release.apk`（不是 `app-release-unsigned.apk`）。重新执行 `./gradlew assembleRelease`
3. **U 盘安装**：部分电视需在「设置 → 安全」里允许「安装未知来源应用」
4. **系统版本**：本 App 要求 Android 7.0（API 24）及以上，过旧电视无法安装
5. **仍失败时**：可先用 Debug 包测试：`./gradlew assembleDebug`，安装 `app-debug.apk`

**关闭 VPN 后提示「无法连接微博服务器」**  
说明当前网络无法解析 `m.weibo.cn`。可尝试：开启可访问微博的网络、修改 DNS（如 223.5.5.5）、或重启路由器。

**电视图标没更新**  
卸载旧版后重新安装，系统会缓存 launcher 图标。

**播放总是第一个视频**  
请安装最新 Release 包；旧版存在 TV 端索引传递问题，已修复。

## 项目结构

```
app/src/main/java/com/example/ninumao/
  data/config/     # DataStore 配置
  data/weibo/      # 微博 API 与解析
  playback/        # 播放会话（列表传递）
  server/          # NanoHTTPD 内嵌配置服务
  ui/browse/       # 主列表（3 列网格）
  ui/settings/     # 设置与二维码
  ui/playback/     # ExoPlayer 播放
```

## 注意事项

- 微博接口非官方开放 API，仅供个人学习 / 自用
- 手机改配置需与 TV 在同一局域网
- 部分博主视频可能需要有效 Cookie 才能加载和播放
