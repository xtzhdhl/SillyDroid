# using

SillyDroid（SillyTavern 安卓直装版）的个人构建镜像：从上游 `jialmaster/SillyDroid` fork，把
SillyTavern 官方 release 打包成可直接安装的 APK，并在 App 内提供自动更新。

- 上游宿主：`jialmaster/SillyDroid`
- 上游酒馆：`SillyTavern/SillyTavern`
- 本仓库只服务一个目标：**手机端能稳定检测到新版本并完成覆盖升级，且升级不丢数据**。

## 更新链路是怎么跑通的

```
SillyTavern 发新版 / 本仓库代码有改动
        │
        ├─ track-upstream-tavern.yml（每天 UTC 02:00 = 北京 10:00 巡检）
        │     └─ 发现官方 tag 与本 fork 已构建 tag 不一致 → 触发构建
        │
        ├─ sillydroid-upstream-apk.yml（构建 APK 并发布 GitHub Release）
        │     └─ 产出 *.apk + *.apk.sha256 + *.update.json
        │
        ├─ publish-fork-meta.yml（构建成功后组装 latest.json 并提交到 master）
        │     └─ 同时清理 jsDelivr 缓存并校验 CDN 已刷新
        │
        └─ App 启动 / 点击“检查更新”
              └─ 读取 latest.json → 比对 versionName → 提示下载 → 校验 SHA256 → 覆盖安装
```

### App 读取的地址

```
https://cdn.jsdelivr.net/gh/xtzhdhl/using@master/.github/fork-meta/latest.json
```

这个地址在 APK 里是编译期常量（`SILLYDROID_LATEST_RELEASE_METADATA_URL`）。App 端还内置了多镜像兜底，
按顺序尝试同一份文件的其它副本，任一个可用就不会“检查更新失败”：

1. `cdn.jsdelivr.net`（带 10 分钟粒度的 cache-buster，规避 12 小时边缘缓存）
2. `fastly.jsdelivr.net`
3. `gcore.jsdelivr.net`
4. `raw.githubusercontent.com`（不走 CDN，用来复核“已是最新”的结论）

### 手动操作

- **立刻检查上游新版本并构建**：Actions → `track-upstream-tavern` → Run workflow。
- **只想重建 APK**：Actions → `tavern-upstream-apk` → Run workflow（`tavern_tag` 留 `auto` 即取官方最新）。
- **latest.json 没更新**：Actions → `publish-fork-meta` → Run workflow（`release_tag` 留空取最新 `sillydroid-v*`）。
- **改了本仓库代码**：直接推 `master`，`android-tavern/**`、`scripts/**`、`sillydroid-build-config.json`、
  `.github/workflows/sillydroid-upstream-apk.yml` 的改动会自动触发构建。

## 数据保护（data / extensions 不会被覆盖）

用户数据的存放原则是**目录分离**：

| 内容 | 路径 | 升级时 |
| --- | --- | --- |
| 角色、聊天、世界书、设置（`data/`） | `files/android-tavern/data/server/data` | 不动 |
| 第三方扩展（`extensions/`） | `files/android-tavern/data/server/extensions` | 不动 |
| 后端插件（`plugins/`） | `files/android-tavern/data/server/plugins` | 不动 |
| 根配置 `config.yaml`（含 API Key） | `files/android-tavern/bootstrap/server/config.yaml` | 解包时跳过覆盖 |
| Tavern 源码 / node_modules | `files/android-tavern/bootstrap/server` | 覆盖解包 |

SillyTavern 运行时通过 `--dataRoot` 指到持久目录，并把 `public/scripts/extensions/third-party`、`plugins`
软链接回持久目录；解包逻辑只删链接本身，不会顺着软链接删到用户数据。

在此基础上还加了两道保险（`TavernUserDataGuard.kt`、`HostRuntime.assertUserDataOutsideServerDirectory`）：

1. **解包前快照**：每次要覆盖 server 资产前，先把 `data/`、`extensions/`、`plugins/` 打成硬链接快照，
   放在 `files/android-tavern/data/server/backups/<时间戳>/`，保留最近 2 份。硬链接不占额外空间，
   删除快照也只是删链接，原数据依然完好。
2. **解包前断言**：如果哪天配置被改坏，导致数据目录落在解包目录内部，解包会直接中止并报错，
   而不是静默把数据盖掉。

真正会丢数据的操作只有三种，都不在“覆盖安装”范围内：卸载重装、清除应用数据、
换成不同签名的安装包（系统会当成另一个应用，例如 debug 包 `com.jm.sillydroid.debug`
和 release 包 `com.jm.sillydroid` 就是两个独立应用）。

## 排查：手机上没有更新提示怎么办

1. 设置 → 关于，看这几行信息：
   - `APK版本` / `构建类型`：必须和 latest.json 里的 `versionName` / `buildType` 同一类型
     （本仓库当前只出 **debug** 包，安装包 id 是 `com.jm.sillydroid.debug`）。
   - `检查更新失败：…` 会直接显示失败原因（HTTP 状态、超时等），不会静默。
2. 若显示“当前已是最新版本”，先确认线上元数据是不是真的更新了：

   ```bash
   curl -s "https://cdn.jsdelivr.net/gh/xtzhdhl/using@master/.github/fork-meta/latest.json?ts=$(date +%s)"
   ```

   对比 `release.versionName` 与本机版本；如果线上还是旧版本，去 Actions 看
   `track-upstream-tavern` / `tavern-upstream-apk` / `publish-fork-meta` 三个工作流哪一环没跑成功。
3. 只有本仓库**构建产出过**的版本才会出现在 latest.json 里。上游发了新版但还没构建时，
   手机端只能等下一轮巡检（每天一次）+ 一次 APK 构建（约 1~2 小时，APK 约 330 MB）。
4. **jsDelivr 的 `@master` 会缓存「分支指向哪个提交」（最长 12 小时）**：新版本刚发布时，部分网络仍可能读到
   旧 latest.json，表现就是“检查更新说已是最新”。`purge.jsdelivr.net` 能清文件缓存，但清不掉每个边缘节点
   的分支解析缓存。此时的应对：
   - 直接从 Release 页下载新 APK 覆盖安装（不会丢数据，见下文数据保护一节）；
   - 装过一次带多源兜底的版本后，App 会用 `raw.githubusercontent.com` 复核结论，这类问题不会再出现。

## 隐私与安全

### App 会主动访问的地址

| 用途 | 目标 | 发出内容 |
| --- | --- | --- |
| 检查更新 | `cdn.jsdelivr.net` / `fastly.jsdelivr.net` / `gcore.jsdelivr.net` / `raw.githubusercontent.com` | 只有 HTTP 请求头（UA、IP），不带任何聊天或配置 |
| 下载更新包 | `github.com` 的 Release 资产（HTTPS，下载后校验 SHA256） | 同上 |
| 安装/更新扩展、插件 | `github.com` / `gitlab.com`（你自己填的仓库地址） | 同上 |
| 聊天推理 | 你在 SillyTavern 里配置的 API（OpenAI / Claude / 本地等） | 聊天内容，这是 SillyTavern 自身行为 |
| 崩溃 / 日志上传 | **无**：本构建的 `SILLYDROID_CRASH_LOG_UPLOAD_URL` 为空，上传器直接短路 | 无 |

没有埋点、没有设备指纹、没有启动上报。

### 数据存在哪

- `files/android-tavern/data/server/{data,extensions,plugins}`：角色、聊天、世界书、第三方扩展。
- `files/android-tavern/bootstrap/server/config.yaml`：含 API Key，**明文**（SillyTavern 原生行为）。
- 清单里 `android:allowBackup="false"`：不参与系统云备份，也不能用 `adb backup` 抽走。
- 应用私有目录在 Android 10+ 对其它应用不可见；系统「文件」App 通过 SAF 入口访问时需要你手动授权。

### 真实风险清单（按严重度）

1. **第三方扩展 / 后端插件**：它们是完整的 JS / Node 程序，能读 data 目录、能任意联网。只装你信得过的仓库，
   装完最好看一眼它在 `extensions/` 里做了什么。
2. **debug 包本身是 debuggable 的**：开着 USB 调试并连到不可信电脑时，`adb` / `run-as` 可以读到应用私有数据。
   用完关掉开发者选项。
3. **「允许外部访问」= 把酒馆暴露到局域网**：同网段设备可直接访问。需要开就配合 SillyTavern 的 basicAuth，
   不需要就别开。
4. **仓库里的 debug keystore 是公开的**：签名不构成信任边界。谁能往本仓库 push，谁就能发布一个同签名、
   能被手机直接覆盖安装的包——所以不要给仓库加协作者，务必开 2FA。
5. **明文流量**：`usesCleartextTraffic=true` 是为了连局域网 LLM（`http://192.168.x.x`），代价是同类 http 请求
   可被同网段嗅探；走公网的 API 请确认是 https。
6. **公开的构建日志**：release 签名材料只从 Actions secrets 注入（debug 构建根本不读），日志里只有变量名。

### 建议

- 别把 `config.yaml` 截图/发群，里面有明文 key；泄露了就去后台吊销重建。
- 分享日志前自己过一眼。需要「导出/上传日志自动打码 api key / Authorization」可以再打包一版。
- 手机加锁屏密码，平时关闭 USB 调试。

## 和上游仓库的关系

- 这是 GitHub 上的公开 fork，上游作者能在「Forks」列表里看到它存在（以及提交和 Release 名），
  除此之外**没有任何东西推给他**：没有 PR、没有 issue、没有往上游写任何东西。
- `.github/workflows/sillydroid-site-release-state.yml` 在 fork 里是 no-op，不会把发布状态 POST 到
  `sd.jlmaster.online`；构建过程对上游仓库零改动。
- 想更低调可以把仓库改成 private（代价：Actions 会消耗私有仓库分钟数，330 MB 的构建很吃配额）。

## 目录速览

- `android-tavern/`：Android 宿主（Kotlin + View/Compose）、Tavern 运行时封装、更新器。
- `scripts/`：构建计划、rootfs / 依赖包 / server source / APK 的打包脚本。
- `.github/workflows/`：巡检、构建、发布、站点状态同步。
- `.github/fork-meta/latest.json`：App 内更新读取的元数据（由工作流自动写入，勿手改）。

## 许可

上游项目许可见 `LICENSE` 与 `THIRD_PARTY_NOTICES.md`。
