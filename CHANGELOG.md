# Changelog

All notable changes to this project will be documented in this file.

The format is based on Keep a Changelog (https://keepachangelog.com/en/1.0.0/)
and this project adheres to Semantic Versioning (https://semver.org/).

## [v1.5.44] - 2026-10-07

### Fixed
- **PC 端静音时「每 20 秒自动重启」的根治（本次 20:48~20:50 事故）**：PC 端静音后
  Opus 进入 DTX 帧率暴跌（50/s→16/s），此时 PC 若重连会在推流中途重发一次 8 字节
  AURL 包头，手机端 `readFrame` 把这 4 字节魔数 `41 55 52 4c` 误当「帧长度」
  （0x4155524c=1095914060 超上限）判为非法、结束会话；上层消费循环再用同一根管道
  重扫时，包头前 4 字节已被读走，`scanRelayMagic` 扫满 64KB 也拼不回完整魔数 →
  触发 `forceReconnect` → 服务端清 S → PC 重连又重发包头 → 再次截断，每 20 秒震荡
  一轮。修复：
  - `readFrame` 检测到长度字段恰为 AURL 魔数时，判定为「PC 重连重发的新包头」，
    读完后 4 字节包头参数并跳过，继续解析后续同样格式的 Opus 帧（不再中断会话、
    不再重连）。
  - `scanRelayMagic` 读到 EOF（对端已断开、管道已关闭）时置 `relayHeaderEof`，
    `onRelayHeaderMissed` 据此静默返回、不再 `forceReconnect`——对端断开会由
    onClosed/onFailure 自动重连，主动关连接只会「自己关自己」加剧震荡。

## [v1.5.43] - 2026-10-07

### Fixed
- **中转链路「扫描魔数失败 → 死循环重扫」的根治（本次 20:12~20:15 事故）**：PC 端每次
  连接只发一次 AURL 包头，手机若因残留帧/时序错过包头，后续纯 Opus 帧里再也不出现
  魔数，`scanRelayMagic` 扫满 64KB 后放弃；但 `startRelayLink` 的消费循环拿到的仍是
  同一根管道（WebSocket 未断），于是陷入「重扫→失败→再重扫」死循环（实测每 5.6 秒
  一轮，会话代次不断 +1，手机全程无声音）。修复：`handleStream` 检测到中转链路
  包头缺失（`headerIncomplete`）时，调用新增的 `onRelayHeaderMissed()` 触发一次受控
  的 `relayClient.forceReconnect()`，让 PC 端重新发包头；用 5 秒最短间隔限频，避免与
  「残留帧」短暂错过叠加成高频重连（历史教训 18:45 三方共振死循环）。成功定位包头时
  清零计数，防止残留计数导致误重连。

## [v1.5.42] - 2026-10-07

### Fixed
- **蜂窝→WiFi 切换后 PC 空推流约 20 秒才重连的根治（本次 19:42 事故）**：
  手机切回 WiFi 停中转时，`RelayClient.stop()` 用 `webSocket.cancel()` 硬断 TCP
  （不发 WebSocket close 帧），服务端 R 连接不能立即感知下线，且与「R 被新连接取代」
  竞态叠加，`finally` 里的「清 S」延迟约 20 秒才执行，期间 PC 一直往已无接收方的
  房间空推流。修复：`closeWebSocket()` 改为优雅 `close(1000)` 发 close 帧，服务端
  立即进 finally 清 S（配合服务端「转发兜底」，把切换空窗压到毫秒级）。

## [v1.5.41] - 2026-10-07

### Fixed
- **切蜂窝后手机「网络类型」检测失效、中转 R 从不启动的根治（本次 19:18 事故）**：
  OnePlus LE2120 / Android 14 上 WiFi→蜂窝切换时，`onLost(WiFi)` 触发瞬间蜂窝网络
  尚未就绪，`detect()` 拿到的 `activeNetwork` 仍是旧 WiFi，返回 WIFI——与缓存的
  WIFI 相同，被去重逻辑直接 return；等蜂窝真正就绪后又未必再触发一次 `onAvailable`，
  于是 CELLULAR 永远检测不到，链路永远停在「局域网直连」，中转 R 从不启动。后果是
  服务器房间里只有 PC 的 S，PC 等 ready 8 秒超时断开、无限重连（服务器日志 19:18:34
  ~19:20:20 每 8 秒一次「发送方已接入→已下线」）。三处修复：
  1. `NetworkWatcher.onSystemNetworkChanged` 改「延迟重探测」：回调后延迟 800ms 等
     网络尘埃落定再 `detect()`，拿到真实的最终类型，不再被过渡态欺骗；
  2. `ResourceHolder.registerNetworkCallback` 补 `onCapabilitiesChanged` 回调（与
     AddressReporter 对齐），网络能力变化（WiFi→蜂窝）也能触发链路决策；
  3. `AudioRelayService` 新增「周期链路自检」兜底线程：每 10 秒主动 `detect()` 一次
     真实网络类型，与已保存链路模式比对，不一致自动纠正（即使网络回调完全失灵也能在
     10 秒内切到中转）。

## [v1.5.40] - 2026-10-07

### Fixed
- **中转「残留帧 → forceReconnect → 重连 → 又残留」死循环根治（扫描魔数）**：服务端
  「R 重接入/下线清 S」异步 close 旧 S 时，旧 S 已排队的 Opus 帧仍会被转发给手机，
  手机 `readHeader` 读满 8 字节读到的却是旧会话残留（如 `01 01 02 01 00 00 00 f7`，
  缺前 4 字节魔数 `AURL`）。旧代码据此「判定旧会话残留 → forceReconnect」→ R 重连 →
  服务端又清 S → PC 又重连 → 又发包头 → 又被残留帧抢先 → 又误判，三方共振死循环
  （实测 18:45 每秒重连 1~2 次、永远凑不齐稳定窗口、完全无声音）。现在改为**扫描魔数**：
  中转链路读到非 `AURL` 开头时，逐字节跳过残留、滑动窗口定位到 `41 55 52 4c` 魔数
  （最多扫 64KB + 超时兜底），接上真正的包头，不再 forceReconnect，死循环彻底打破。
- **网络切换去重**：Android 切网络时 `NetworkCallback` 会连续触发多次，导致服务层在
  同一网络类型下反复「重新选择链路」（实测 18:44:14 切蜂窝后 18:44:28 又误触发一次，
  打断中转链路、诱发死循环）。现在 `NetworkWatcher` 只在网络类型真正变化时通知订阅者。
- **切链路前清对端信息**：蜂窝→WiFi 切换后若新直连客户端迟迟未接入，界面会一直卡在
  旧的「已连接：中转服务器:5000」。现在切链路前先 `clearPeerInfo` 清空对端并广播断开，
  界面立即回到「等待连接」。

## [v1.5.39] - 2026-10-07

### Fixed
- **切蜂窝后中转「已连上」却立即「流包头未收全」放弃的竞态根治**：`RelayClient.start()`
  原先会先 `rebuildPipe()` 建一条管道，紧接着 `connectOnce()` 的 `onOpen` 回调又
  `rebuildPipe()` 把这条管道关掉——而 `startRelayLink` 的消费线程此刻正阻塞在这条
  管道上读，于是读到 EOF、误判「流包头未收全」而放弃（实测切蜂窝后「已连上中转服务器」
  与「流包头未收全」落在同一毫秒）。现在去掉 `start()` 里的 `rebuildPipe()`，管道统一
  由 `onOpen` 回调负责重建：首次连接在 `onOpen` 前 `audioInput()` 返回 null，消费线程
  会 sleep 等待，`onOpen` 建好管道后自然开始消费，干净无竞态。（配合 PC 端「直连优先、
  中转串行 + 409 短退避」一起打破切网络后的震荡死循环。）

## [v1.5.38] - 2026-10-07

### Fixed
- **切网络后「音轨已暂停、播放头=0、全程无声」根治**：`handleStream` 的 `finally`
  块原先**无条件 `audioTrack.pause()`**，切网络会快速重建多路流（旧直连流 + 新中转流
  并发），旧会话的 finally 把新会话刚 play 起来的音轨又停掉。实测 `16:07:20~16:08:23`
  切换蜂窝后音轨 `playState=已暂停、播放头=0、欠载=146` 纹丝不动长达 81 秒，数据持续
  解码写入（帧率 50/s、峰值 0.0dBFS）却不出声。现在引入「会话代次」（`sessionGeneration`），
  每路流开始领取自己的代次，`finally` 里的暂停/恢复无声锚点/清对端信息/断开广播只在
  「本会话仍是当前最新一代」时执行，旧会话收尾不再污染新会话状态。
- **切网络时未关已建立的直连连接**：`stopLanServer()` 原先只关监听 socket，已 accept 的
  客户端连接仍在读、与新中转流并发写同一音轨。现在一并关闭 `currentClientSocket`。
- **上报模式与链路切换判定不一致**：`AddressReporter.reportOnce()` 原先用
  `NetworkWatcher.detect()` 实时探测网络，会受系统回调延迟/抖动影响，与
  `AudioRelayService` 链路切换（同一回调里 `refresh()` 缓存的类型）矛盾——实测
  服务已切中转，上报却仍写「直连模式」。改用 `NetworkWatcher.lastKnown()` 缓存值
  （缓存未初始化时才退化实时探测），保证「上报的 mode == 实际选的链路」。

## [v1.5.37] - 2026-10-07

### Fixed
- **蜂窝↔WiFi 切换后高频闪屏、无法播放根治**：手机日志 `15:11:50~58` 共 510 次
  「中转链路读到无包头的音频数据，判定为旧会话残留」，约 60 次/秒死循环。根因是
  `readHeader` 读到残留帧只「放弃本次会话」却没断连接，而上层 `relayThread` 的
  `while(isRunning)` 会立刻再次消费**同一条还没读到底的管道**，又读到下一段残留帧
  → 再放弃 → 无限循环，每次还触发 `markSessionEstablished` 广播，界面状态文字
  「连接成功/失败」高频闪屏。现在检测到残留帧时调用新增的
  `RelayClient.forceReconnect()`：主动断开当前 WebSocket、丢弃旧管道，重连成功后
  重建干净管道，新数据才是正经 AURL 包头，死循环随之消除。

## [v1.5.36] - 2026-10-07

### Fixed
- **蜂窝中转「手动开始后完全没声音」根治**：手机日志 `12:56 切蜂窝 → 已选择服务器中转 →
  0.0 秒就「接收结束」` 暴露了直接根因——切蜂窝时 `NetworkWatcher` 先 `stopLanServer()`
  把 `isServerRunning` 置 false，而 `startRelayLink()` 没把它置回 true，导致中转链路的
  `handleStream` 一进来就因 `while(isServerRunning)` / `if(!isServerRunning)` 立即退出，
  一个字节都收不到。现在 `startRelayLink()` 复位 `isServerRunning = true`。
- **中转断线重连后无人消费**：中转客户端断线会自动重连，但接收线程只消费一次
  `handleStream` 就退出，重连成功后新数据无人读管道 → 无声。现在接收线程改为循环消费，
  中转客户端每次重连成功（onOpen）都重建管道，保证新流总有人读。
- **「假连接」被误判为裸 PCM**：`readHeader` 读到 EOF / 服务停止 / 中转残留帧时，
  此前都返回 null 却没标记 `headerIncomplete`，导致上层把它们当成 44.1kHz 裸 PCM，
  建立音轨又立即结束（「电脑端已连接 → 0 秒结束 → 无声音」）。现在这三类情况统一
  标记 `headerIncomplete = true`，上层正确放弃会话而非硬播。

## [v1.5.35] - 2026-10-07

### Fixed
- **失真根治（服务端中转残留帧竞态）**：手机日志铁证 `01 01 02 01 00 00 00 c4`
  是「包头前 4 字节魔数被吞」。真正根因在服务端：R 重接入 / 同角色顶替时
  `close()` 是异步的，被顶掉的旧连接协程仍可能在 `receive()` 上拿到关闭前已送达的
  音频帧并转发给新对端。服务端转发前现在校验「发送方仍是房间当前连接」，
  残留帧直接丢弃。（App 端 v1.5.33 的 `isRelayPeer` 兜底已能挡住裸 PCM 误判，
  但会频繁重连；服务端修复后彻底不再产生残留帧。⚠️ 需重新部署 center-server 生效。）

### Changed
- **常驻通知与保活拆分为独立 `KeepAliveService`（无条件启动）**：
  通知 + 保活资源 + 地址上报归 KeepAliveService，接收音频归 AudioRelayService。
  打开 App 与开机自启都无条件拉起保活服务（常驻通知永远存在），
  不再依赖接收服务。
- **「自动启动服务」开关语义恢复**：只管「是否自动启动接收服务」——
  开 = 打开 App / 开机后自动开始接收；关 = 必须手动点「开始」。
  与常驻通知彻底解耦（两层独立）。开机自启本身（保活+通知）硬编码无条件。
- **设置弹窗修复**：「全程保活 / 一像素锚点 / 无声播放」三个开关此前被错放在
  弹窗之外（孤儿 Composable），导致弹窗里看不到。已移回弹窗正文。

## [v1.5.34] - 2026-10-07

### Fixed
- **失真（v1.5.33 的修复没打中：这次拿到的是包头被「截断」而非「缺失」）**
  手机日志 10:59:46 读到的是 `01 01 02 01 00 00 00 c4` —— 完整的 AURL 包头是
  `41 55 52 4c | 01 01 02 01`，也就是说**前 4 字节魔数被吞掉了**，
  不是整个包头没送到（此前 v1.5.32/33 的判断都是错的）。
  - **PC 端**：`ws.send_binary()` 的返回值是**实际写入字节数**，连接拥塞或帧过大时
    会小于 `len(data)`，甚至返回 0。旧代码忽略返回值、无条件当成功，
    于是 8 字节包头被部分发送/丢弃。现在校验 `sent == len(data)`，否则判失败并重连。
- **关掉「自动启动」后仍自动启动服务**：`KeepAliveReceiver`（AlarmManager 兜底）与
  `KeepAliveJobService`（JobScheduler 兜底）都只检查「全程保活」开关，
  **完全绕过了「自动启动」开关**，于是每15 分钟照旧把服务拉起来。
  两条兜底现在都同时检查 `KEY_AUTO_START`。
  语义（用户确认）：自动启动 = 允许服务自启；关掉后 App 运行期间的通知与保活不受影响，
  只是进程真被杀后不再自动复活。
- **通知栏常驻出现「"Aurelay声音中继"正在其他应用的上层运行 / 显示内容…」系统提示**：
  来自「一像素锚点」——它用 `TYPE_APPLICATION_OVERLAY` 悬浮窗实现保活，属于
  「在其他应用上层显示」，系统因此弹出该提示并引导用户去设置里关闭。
  该提示是系统文案，改不了，只能去掉悬浮窗。一像素锚点**默认改为关闭**
  （`KEY_KEEP_ALIVE_ONE_PIXEL` 默认值 `true` → `false`，设置页同步），
  需要时可在设置里手动开启。其余保活锚点不触发这类提示。

## [v1.5.33] - 2026-10-07

### Fixed
- **「停止 → 开始」后严重失真（真根因在服务端时序，上一版修错了地方）**：
  手机点「停止」再点「开始」后会在中转房间重新注册为 R，而 PC 端那条连接还活着
  （它要到下一次发送失败才会重连，实测滞后约 10 秒）。这 10 秒内 PC 继续把
  **旧会话的音频帧**发给房间，服务端照单转发给刚上线的手机，于是手机读到的流
  开头是 `00 00 00 d8 fc 61 3c 7a` —— 一帧裸 Opus 数据，压根没有 AURL 包头
  （`41 55 52 4c`），只能被误判成 44.1kHz 裸 PCM 播放。
  - **服务端**：接收方（R）重新接入时主动清理房间里残留的发送方（S）连接，
    PC 会立刻收到 409 并重连，重连后才重新发 AURL 包头，顺序恢复正常。
  - **App 端兜底**：`readHeader()` 增加 `isRelayPeer` 参数，中转链路上读到无包头
    的数据一律放弃本次会话等待重连（PC 与手机之间只有 AURL 一种协议，
    无包头必然是旧会话残留），不再按裸 PCM 硬播。
- **常驻通知与手机放音通知仍可被划走、文案看起来没改**：
  根因是两条通知都挂了 `NotificationCompat.MediaStyle()`。MediaStyle 会把通知
  折叠成「媒体播放器」样式——两行文案被压成一行小字，而且部分国产 ROM 上
  不遵守 ongoing 语义，于是就能随手划走。
  现在两条通知都去掉 MediaStyle，并去掉 `setShowWhen`/`setUsesChronometer`
  （右侧那列时间会把两行式挤歪；运行时长已由 App 界面的「已连续运行 X」承担）。

## [v1.5.32] - 2026-10-07

### Fixed
- **停止/开始后声音严重失真，且电脑静音时音量条仍起伏**（本条是重点）：
  `readHeader()` 返回 `null` 原本混用了两种含义——「等包头超时/连接中断」与
  「确实是官方裸 PCM」——上层一律按裸 PCM 处理。经中转时首帧常只到几个字节
  就遇上抖动，等满 `IDLE_TIMEOUT_MS` 后旧代码退回已读字节并返回 null，
  于是 **48kHz 的 Opus 被按 44.1kHz 裸 PCM 播放**，听感即严重失真；
  电脑没声音时，噪声底也会被一起放大成起伏的嗡嗡声。
  现在新增 `headerIncomplete` 标志区分这两条路径，包头没读够就放弃本次会话，
  绝不按裸 PCM 处理。
- **常驻通知可被划走**：`startForeground` 只在 `onCreate` 调一次，之后一律用
  `notify()` 刷新——那是**普通通知**，服务一旦被系统降级就不再受前台服务保护。
  现在每次刷新都重新走 `startForeground`，并在低版本追加 `FLAG_ONGOING_EVENT`
  （部分国产 ROM 不完全理会 `setOngoing`）。

### Changed
- **电脑放音通知（`AudioCaptureService`，通知 ID 1002）也改成两行式**：
  「正在推送电脑声音 / 麦克风已推送到电脑 · 点此管理」，去掉长句与
  `setUsesChronometer` 时间戳列（那列会把两行式挤歪）。
  此前只有 `AudioRelayService` 的两条通知被改，电脑放音这条一直是旧文案。
- **中转包头误判时打印十六进制原文**，便于事后定位。

### 注意
- 本版包含 v1.5.31 的全部改动（中转重连、立即上报、中转回显已连接、运行时长显示）。
  若此前安装的仍是 v1.5.30，通知文案与不可划走的效果都还没生效。

## [v1.5.31] - 2026-10-07

### Fixed
- **PC 端「停止后无法重新推送」（蜂窝）**：中转握手在手机端尚未就绪时
  （`ready=False`）也返回成功，于是 PC 带着一条「服务器还不认识对端」的连接开始推流，
  音频被丢弃；重连时又复用这条死连接，表现为「点开始后一直推送失败、30 秒后重试」，
  服务端则不断返回 `409 已被新的连接取代`（PC 自己顶掉自己）。
  现在握手未收到 `ready` 即判定失败并主动关闭连接，走正常的退避重试。
- **手机端「停止 → 开始」后中转起不来**：`RelayClient` 复用单例，上一轮的
  WebSocket 引用与管道残骸还在，新一轮 `start()` 会与旧连接抢同一个 OkHttp 管道。
  现在重启前先彻底 `stop()` 一次。
- **「写入音频管道失败 | Read end dead」刷屏**（实测一次刷出 838 行日志）：
  `stop()` 关掉管道后，OkHttp 线程里已排队的 WebSocket 数据帧仍会回调 `onMessage`，
  继续往已关闭的管道写。现在先判 `running` 与管道空引用，并对 `IOException`
  按正常收尾处理，不再逐帧刷错误。
- **常驻通知可以被划走**：`startForeground` 只在 `onCreate` 调一次，之后一律用
  `notify()` 刷新——那是**普通通知**，服务一旦被系统降级到后台就不再受保护。
  现在每次刷新都重新走 `startForeground`，把通知拉回「前台服务通知」不可划走。

### Changed
- **常驻通知文案**改为「标题=状态 / 正文=极短动作提示」两行式（借鉴 MicYou），
  去掉原来会被系统提示挤成一团的「后台常驻运行 · 等待接收音频」长句；
  会话通知同步改为「$peer · 点此管理」，并加 `setOnlyAlertOnce` / 隐藏时间戳。
- **点「开始」立即上报一次地址与链路状态**，PC 端不用再等一个上报周期（最长 60 秒）。
- **中转会话回显已连接状态**：中转此前不走 `handleClient`，音频明明在播、界面却一直
  显示「等待连接」。现在抽出 `markSessionEstablished`，两条链路统一设置对端信息、
  广播连接状态并张贴会话通知。

### Added
- **运行时长显示**：界面状态区新增「已连续运行 X 小时 Y 分 Z 秒」，
  自本次打开 App 起累计、每秒刷新。保活失效时该数字会停住或归零，
  是判断「到底有没有被系统留着」最直接的证据。

## [v1.5.30] - 2026-10-07

### Fixed
- **「停止」终于能停下了**：上一版的「停止」只结束当前音频会话，但接收监听还开着，
  电脑端自动重连在几十毫秒内就重新建链（实测日志 09:05:40：点停止 → 35ms 后电脑重连 →
  新会话继续放音，循环往复）。现在停止会**一并关闭监听端口与中转出站长连接**，
  再点「开始」时重新拉起。服务与常驻通知照旧保留（保活不受影响）。
- **音量滑块被停止按钮压住**：底部控制按钮 64dp → 48dp，把高度让给中间内容区。

## [v1.5.29] - 2026-10-07

### Fixed
- **常驻通知这次真正常驻了**：上一版改了「打开 App 即拉起服务」，但**切到「电脑放音」时
  `MainActivity` 会 `stopService(AudioRelayService)`**，把保活服务和它挂的常驻通知一起干掉，
  所以用户仍然只在「手机放音 → 点开始」之后才看到通知。现已改为：切模式**只**停电脑放音
  自己的 `AudioCaptureService`，接收服务全程保留。
- **中转链路一次都没连上过**：`RelayClient` 的 `url` 字段**声明后从未赋值**，
  而 `connectOnce()` 直接拿它建 WebSocket，于是蜂窝下每次都抛
  `IllegalArgumentException: Expected URL scheme 'http' or 'https' but no scheme was found for ""`。
  已在 `start()` 里按 `host/port` 拼出 `ws://...`。
- **蜂窝网络下 PC 端查不到手机**（`AddressReporter`）：本地早已改成「蜂窝也上报、只是不带地址、
  并带上 `mode=relay`」，但这份改动**从未推送到仓库**，云端编译用的仍是旧的
  「跳过地址上报」版本。本次随包推送，PC 端据此可知该走中转。
- **PC 端切换「链接方式」保存失败**：`main.py` 调了 `get_viewer_settings` 却**从未 import**，
  每次切换都抛 `NameError`，被外层 except 记成一条警告，实际根本没落盘。改用已有的
  `set_param`（settings_store 通道）。
- **音量环尺寸两处统一收小**：手机放音与电脑放音的音量环都由 120dp 减到 100dp；
  手机放音的可视化条 140→100dp；顶部图标 56→48dp、两处留白收窄，把高度让给中间区，
  使「可视化条 + 音量环 + 音量滑块」能在一屏内完整显示（中间区另加滚动兜底）。

### Changed
- **三通知方案**（用户定稿）：① 常驻通知（App 运行全程在、`setOngoing(true)` 不可滑走）；
  ② 手机放音有客户端连入时才出现的会话通知（新 ID 1003，会话结束即撤销）；
  ③ 电脑放音的 `AudioCaptureService` 通知（ID 1002）。三条互不干扰。
- **PC 端优先内网 IPv4**：手机与电脑多半在同一局域网，内网直连无 NAT/运营商策略障碍，
  实测比公网 IPv6 更容易连上。候选排序把「内网 IPv4」提到公网地址之前
  （仍低于「探测可达」与「上次连通」），界面提示行也改为优先显示内网 IPv4，
  不再固定显示 `public_ipv6`，避免「显示的地址和实际连的对不上」。

## [v1.5.28] - 2026-10-06

### Fixed
- **常驻通知真正常驻了**（设计文档 8.3 第 4 项）：此前两处违背文档——
  ① `MainActivity.onCreate` 只在「自动启动服务」开关打开时才拉起接收服务，
  绝大多数情况下**打开 App 根本没有通知**；② 点「停止」走 `ACTION_STOP_SERVICE`
  → `stopSelf()`，**服务一销毁通知就消失**，紧接着国产 ROM 的后台清理就把进程收走，
  表现为「手机不再上报地址，PC 端随之查不到设备」。
  现在：**打开 App 即无条件拉起接收服务**，通知全程常驻；
  「停止」改为 `stopAudioSession()`——只 pause+flush 音轨并广播断开，
  **服务不销毁、通知不消失**，回到「后台常驻运行 · 等待接收音频」。
  「自动启动服务」开关语义收窄为纯粹的**开机自启**（`BootReceiver` 仍在用它）。
- **音量滑块被「停止」按钮遮挡**：手机放音时中间区内容是「可视化条 140dp +
  音量环 120dp + 标签 + 滑块」合计约 372dp，而 `weight(1f)` 的中间区在中小屏上
  往往只有 250dp 左右。该分支的 Column **没有 `verticalScroll`**（相邻分支有），
  超出部分就溢出去被底部 64dp 的按钮盖住，用户看到的就是「滑块不见了」。
  已补上滚动兜底，并把可视化条 140→100dp、音量环 120→100dp、两处间距收窄。
  `RealAudioVisualizer` 新增可选 `heightDp` 参数（默认仍为 140，不影响既有调用）。

## [v1.5.26] - 2026-10-06

### Added
- **音量环可视化（仿 MicYou `VolumeRingVisualizer`）**：新增 `VolumeRingVisualizer` 组件，
  样式与 MicYou 一致——从 12 点方向顺时针填充的圆环进度、圆弧末端高亮圆点、
  外圈 60 个刻度（每 5 个一个长刻度、按音量点亮）、中心随音量泛光。
  常量沿用 MicYou 的 `VisualizerConstants` 取值（半径系数 0.85、线宽 8dp、
  刻度 60、内发光系数 0.6、100ms 线性过渡）。
  - **手机放音**：加在原有条形可视化下方，音量取各频段平均值（比单取峰值平稳）。
  - **电脑放音**：推流中在连接信息卡上方显示，音量由采集服务实时广播驱动。
- **电脑放音的实时音量广播**：`AudioCaptureService` 新增 `ACTION_CAPTURE_LEVEL`，
  每批采样算一次**归一化 RMS**（`sample / 32768` 后求均方根，与 MicYou 算法一致），
  广播 0~1 音量给界面；同时保留原有的 5 秒采样峰值日志用于排查静音。

### Changed
- **通知栏仿 MicYou 重做**（`AudioCaptureService`）：低优先级、常驻不可滑走、
  只提醒一次、不显示时间戳，**点击通知即停止推送**（对应 MicYou 的「点击断开」）。
  差异仅在图标（用 Aurelay 自己的 `R.mipmap.ic_launcher`）与名称（Aurelay）。
- **接收端通知统一风格**（`AudioRelayService`）：图标改 `R.mipmap.ic_launcher`，
  补 `PRIORITY_LOW` / `setOngoing(true)` / `setOnlyAlertOnce(true)` / `setShowWhen(false)`，
  点击回 App，与采集端保持一致。

## [v1.5.23] - 2026-10-06

### Fixed
- **「我的电脑」列表昵称只显示 1 个字**：`IconButton` 默认最小触摸目标 48dp，
  编辑 + 删除两个就吃掉近 100dp，昵称几乎无空间。已把两个图标按钮强制压到 30dp、
  图标 24→20dp、图标与昵称间距 8→6dp、删掉按钮组之间的冗余 Spacer，
  「连接」按钮内边距压到 `horizontal = 10.dp` 且最小宽度 48dp —— 昵称可用宽度显著增加。
- **电脑放音文案不准确**：原文案「可以开始广播 / 点击「开始」以推送音频」描述的是
  旧流程。实际设计是**点列表里的「连接」成功后自动推送麦克风声音**，文案已改为
  「未连接电脑 / 正在连接… / 正在推送麦克风声音」，副文案改为
  「点下方「我的电脑」里的「连接」即可开始」。
- **底部「开始」按钮在电脑放音下已无意义**：现在仅在**正在推送**时显示「停止推送」，
  未连接时整个按钮隐藏（启动入口统一为列表里的「连接」）。

### Changed
- **PC 端「手机音频播放」去掉「来源」下拉框**（原 `MicYou 手机（8556）` /
  `Aurelay 手机（5000）` 二选一）。理由：两条链路都是手机**主动推送**给 PC，
  PC 只负责监听，**端口号与手机无关**，括号里的端口会误导用户以为要去连 PC 的端口；
  且实测两链路互不冲突。现改为**同时接收两条链路**，谁连进来就播放谁的，
  用户无需再判断该选哪一个。
- 播放器 `MicYouPlayback` 改为双链路：启停同时管理 MicYou 与 Aurelay 桥接器，
  播放循环依次探测两个 PCM 队列，取到第一块有数据的即播放。
- `aurelay_bridge` 新增 `get_devices()` / `set_state_changed_callback()`，
  与 `micyou_bridge` 接口对齐；「已连接的手机」列表现合并展示两条链路的连接
  （此前只显示 MicYou，APK 连上了也不显示）。

## 🎉 [v1.5.22] - 2026-10-06 —— 电脑放音链路打通（阶段性成功标志）

> **实测通过**：手机装 v1.5.22 后，「电脑放音」模式下点列表里的「连接」，
> **电脑端成功播放出手机麦克风的声音**；且**不开 PC 端播放**时点「连接」，
> 手机如实弹出「连不上电脑」提示，不再谎报「正在广播」。

### Fixed
- **电脑放音死寂无声（压轴根因）**：PC 端 PyAV 的 Opus 解码器输出的是
  **float32（值域 −1.0~1.0）**，而解码代码直接 `astype(np.int32)…astype(np.int16)`，
  把 0.5 截断成 **0** → 整段 PCM 全零。表现为「帧数、每帧字节数全对，却 RMS 恒为 0」。
  已改为按 `dtype.kind == "f"` 判定后 `× 32767` 再转 int16。
  本地端到端自测 `_tmp/test_aurelay_e2e.py`：修复前 `RMS=0` → 修复后 `RMS=5621`（理论 5657）。
- **界面谎报「正在广播」**：`startForegroundService()` 只是请求启动服务，
  原代码在其后无条件置「正在广播」。现由服务广播真实连接结果
  （`connected` / `failed` / `stopped`），TCP 连上才算数，连不上弹 Toast 说明原因。
- **首次点「连接」总是失败（IP 为空）**：Compose 状态需重组才生效，
  而权限回调几乎立即触发，读到空值。改用同步字段 `MainActivity.pendingCaptureIp` 传递。

## [v1.5.11] - 2026-10-06

### Changed
- **电脑放音的 PC 端接收不再单开程序**：原有的独立 `receiver.py` + `启动接收.bat`
  已删除，能力**并入现有的「手机音频播放」工具**。该工具新增「来源」下拉框，
  可二选一：`MicYou 手机（8556）`（原功能，第三方 MicYou App）
  / `Aurelay 手机（5000）`（本项目改造版 App 的「电脑放音」模式）。
  两条链路**互斥**，切换会自动停止当前播放；共用同一套输出设备选择、
  音量、自动开启与设备别名管理，不重复实现播放逻辑。
- 新增 `pc-client/core/mic_share/aurelay_bridge.py`：Aurelay 协议接收桥接器
  （监听 5000 → AURL 包头 → 分帧 → PyAV 解码 Opus → 混降单声道 → PCM 队列），
  对外接口（`pcm_queue` + `start` / `stop`）与 MicYou 桥接器一致，可互换。
  同时修正了原实现里 planar 音频被误当交错数据处理的声道问题。

### Docs
- 设计文档新增**第 15 章「服务端部署（由用户手动执行）」**：明确助手不代为
  部署远程服务器；列出哪些改动需要动服务器、逐条部署命令、生效验证方法、防火墙说明。
- 设计文档新增**第 16 章「APK 下载（固定走代理）」**：记录 `github.com` 本机
  直连不可达，下载 Release APK 一律走用户提供的公共代理
  `https://v4.gh-proxy.org/<原始 github.com 地址>`，并给出拼接示例与 md5 交叉校验要求。

## [v1.5.0] - 2026-10-06

### Added
- **服务器中转链路（蜂窝网络可用）**：蜂窝下手机没有公网入站能力，无法被动等待电脑连接；
  现改为手机**主动出站**连到中转服务器（默认 `039039.xyz:15152`），PC 同样出站，
  由服务器把 AURL 音频字节**原样转发**（不解包、不转码、不落盘）。
  新增 `RelayProtocol.kt` / `RelayClient.kt`（ARLY 帧、握手、30 秒心跳、退避重连）。
- **链路自动选择**：WiFi 走局域网直连（监听 `:5000`），蜂窝走服务器中转；
  网络切换时自动切换，无需用户干预（新增 `NetworkWatcher.kt`）。
- **全程保活**：新增「全程保活」开关（默认开），App 运行即持有 20 项受控资源
  （唤醒锁、WiFi 锁、前台服务、AlarmManager / JobScheduler 兜底、中转长连接、
  本地监听、上报定时器、网络回调、位置 / 传感器 / 蓝牙 / 相机监听等）。
  新增 `ResourceHolder.kt`、`BootReceiver.kt`、`KeepAliveReceiver.kt`、`KeepAliveJobService.kt`。
- **两个独立保活锚点**：
  「一像素锚点」（默认开）——常驻 1×1、`alpha 0.01` 的悬浮窗，提升进程优先级；
  「无声播放」（默认开）——空闲时用独立 AudioTrack 循环播放 ±1 LSB 的近静音 PCM，
  播放声音或录音时自动让位。新增 `OnePixelOverlay.kt`、`SilentPlayer.kt`。
- **电脑放音改为手动 IPv4 连接**：新增「我的电脑」列表（最多 10 条，可增改删），
  新增 `DeviceStore.kt`；保存即入列表。
- **电脑放音改推 Opus**：裸 PCM 691MB/小时 → Opus 128kbps 约 57MB/小时（11:1）。
  新增 `OpusEncoder.kt`（48kHz / 立体声 / 20ms / 128kbps），
  帧格式与 PC 端现推流逐字节一致；设备无 Opus 编码器时显式回退裸 PCM。
- **全量权限申请**：按「能申请的都申请」要求，Manifest 声明危险权限、普通权限、
  特殊权限与签名级权限共 100+ 项；运行时批量申请，任何一条被拒都不阻断主流程。
- 开机自启动（`BootReceiver` 接收 `BOOT_COMPLETED`，受「自动启动服务」开关控制）。

### Removed
- **彻底移除 UDP**：删除 5002 端口的广播发现与应答（`DISCOVERY_REQUEST/RESPONSE`、
  `CONNECT_REQUEST`、`DISCONNECT_REQUEST`），删除 `MainActivity` 的三个 UDP 通知函数。
  电脑端改从中心服务器查询手机地址。
- 删除设置项：连接需要确认（固定不确认）、应用主题（固定浅色）、动态取色（固定关）、
  显示音频可视化（固定常驻）、显示音量滑块（固定常驻）、音频输出（固定「远端」）；
  删除「关于」对话框（右上角齿轮直接进设置）。
- 删除 `AudioRelayService` 的 `android:permission="TODO"`，`exported` 改为 `false`。

### Changed
- 术语改为「手机放音 / 电脑放音」（原「接收端 / 发送端」）。
- 地址上报：蜂窝网络**不上报**（此时走中转），仅 WiFi 上报。
- 设置页精简为「连接 / 本机」两组 + 地址上报 + 诊断日志。

## [v1.4.5] - 2026-10-01

### Fixed
- **修复手机端 Opus 解码永久不出声（根因）**：Android 10 起系统 Opus 解码器为
  `c2.android.opus.decoder`（AOSP `C2SoftOpusDec`），它把**第一个输入缓冲**同时当作
  OpusHead、codec delay、seek pre-roll 三样初始化数据来解析，并用内部计数
  `mInputBufferCount` 判定「三样都齐了（=3）才真正配置解码器开始解码」。
  此前只往 `csd-0` 放了 19 字节裸 OpusHead（旧式写法），另两项取不到，计数只加到 1，
  于是解码器始终不产出数据；更严重的是**紧接着到达的前两个真实 Opus 包被当成
  codec delay / pre-roll 解析**——把 Opus 包头 8 字节直接读成 int64 纳秒数，
  换算后高达 1e14 量级（约几十天），写入 `mSamplesToDiscard` 后
  **此后每一帧解码结果都被整体丢弃**，表现为「收包几百个、写入音轨 0 块、无任何报错」。
  现按 AOSP `OpusHeader.cpp` 的 `WriteOpusHeaders()` 构造「统一 CSD」（83 字节）整体放进
  `csd-0`：`"AOPUSHDR"+u64LE(19)+OpusHead`、`"AOPUSDLY"+u64LE(8)+延迟纳秒`、
  `"AOPUSPRL"+u64LE(8)+预滚纳秒`，一次给齐三项，计数直接到 3，第一帧即可正常解码。
- 移除 `KEY_MAX_INPUT_SIZE`（AOSP 该参数是 const 值 5760，由调用方另行指定存在冲突风险）。
- 取输出缓冲的超时由 0 改为 2ms，避免软件解码器上频繁取不到已解出的数据。
- 解码异常改为按 `MediaCodec.CodecException` 单独捕获并记录
  `errorCode / isTransient / isRecoverable`，不再被静默吞掉。

### Changed
- 诊断日志新增：统一 CSD 的十六进制内容、解码输出格式（含 PCM 编码位深）、
  `dequeueOutputBuffer` 的未知负返回值；「无解码输出」告警补充成因提示。

### Verified

- **[阶段性里程碑] WiFi 局域网实测通过**：在 OnePlus LE2120 上以 WiFi 连接电脑端，
  手机端**可正常连接并正常播放声音**。这确认 v1.4.5 的 Opus 统一 CSD 修复有效——
  长期存在的「连上了但完全没声音（Opus 解码永久静音）」核心故障已解除。
  本版本（v1.4.5 / versionCode 12）作为后续问题排查的**可用基线**保留。

## [v1.4.4] - 2026-10-01

### Added
- **诊断日志**（设置页新增「诊断日志」区）：没有声音等异常时打开开关、复现一次问题，
  再点「上传日志」即可把日志发到中心服务器，无需连电脑抓 Logcat。
  - 关键事件（连接、协议分支、音轨状态、首帧、会话汇总、异常）**始终**记入内存缓冲，
    因此「先出问题、后开开关」也能拿到上半段现场；
  - 打开开关后额外写入 `diag/aurelay-diag.log`（超出 800KB 自动轮转，保留 3 份）；
  - 支持「查看日志 / 复制 / 分享」三种导出方式，作为上传失败时的兜底。
- 日志内容包含：App 版本与**安装包签名 SHA-1**（用于确认手机上装的是哪个包）、
  机型与系统、**系统媒体音量与输出设备**、AURL 包头逐字节、AudioTrack 的
  `state/playState/缓冲/音量/欠载次数/播放头`、每 5 秒的帧率与码率、输出峰值 dBFS、
  Opus 解码器的收包数与解出块数、以及每次会话结束的汇总行。

### Changed
- 地址上报的 JSON 解析工具改为包内可见，供日志上传复用（不引入 JSON 库）。

## [v1.4.3] - 2026-10-01

### Fixed
- **重连后完全没有声音**：`ensureAudioTrack()` 复用已有 `AudioTrack` 时只校验
  `state == STATE_INITIALIZED`，而 `AudioTrack.stop()` 并不会改变 `state`
  （只把 `playState` 置为 `STOPPED`）。于是上一路客户端断开后再连进来，
  复用到的是「已停止」的音轨 —— 数据照写但不被播放。表现：手机端显示已连接、
  音量也能调，但一点声音都没有。现在复用前会确认 `playState`，
  非播放态则先 `flush()` 再 `play()`；会话结束也改为 `pause()` 而非 `stop()`。
- 新建 `AudioTrack` 时恢复用户设定过的音量（原先重建后会重置为 1.0）。

### Changed
- 界面与通知文案全部改为中文。

## [v1.4.2] - 2026-10-01

### Fixed
- 地址上报结果误判：中心服务器所有端点都返回「HTTP 200 + body 内 `code`」，
  而上报器只判断 HTTP 状态码，导致**令牌错误时界面仍显示「成功」**，实际并未注册。
  现在同时解析响应体的 `code`，非 0 即按失败处理，并把服务器返回的中文 message
  直接显示在「Last result」中。

## [v1.4.1] - 2026-10-01

### Fixed
- 地址上报在明文 HTTP（`http://`）服务器上必然失败：Android 9+ 默认禁止明文流量，
  而应用清单未放行，上报会抛出 `IOException: Cleartext HTTP traffic ... not permitted`。
  现已显式开启 `usesCleartextTraffic`。
- 设置页「Last result」此前只显示异常类型（如 `异常: IOException`）无法定位；
  现改为显示异常原因摘要，并在 Logcat（标签 `AurelayReport`）输出完整堆栈。

## [v1.4.0] - 2026-10-01

### Added
- 外网地址上报：在「服务启动 / 每 60 秒 / 网络切换」三种时机，把本机 IPv4、IPv6 地址
  上报到自建中心服务器，供 PC 发送端在公网直连（蜂窝网络下地址会随时变化）。
- 自研流协议 `AURL`：在官方「裸 PCM」之上扩展「分帧 + 可选 Opus 编码」，
  蜂窝网络下可把流量降到裸 PCM 的十几分之一；未带包头的官方发送端保持兼容。
- 设置页新增「Public Address Reporting」配置（上报地址、令牌、开关与最近一次结果）。
- 主界面「Connection Details」新增本机 IPv6 地址显示，便于外网直连时人工核对。

### Changed
- 音频接收链路重构：按流协商出的采样率与声道数动态创建 AudioTrack；
  Opus 流按系统解码器的 48kHz 输出对齐，避免变速。
- 发布流程：仅构建 APK，移除 Play Store 上传步骤与 AAB 构建。

## [v1.2.0-rc1] - 2025-12-07

### Added
- CI: improved release workflow with non-interactive SDK installation and signing.

### Changed
- Release process: use GitHub-generated release notes and upload assets on tag.

### Fixed
- Robust APK verification and build-tools selection in CI.

## [v1.2.0] - 2025-12-07

### Added
- Rebrand changes and package updates.
- In-app runtime permission checks (POST_NOTIFICATIONS) and related lint fixes.
- Fastlane lanes for uploading mapping files to Play Store.

### Changed
- Signing: added keystore handling and `key.properties` usage for CI builds.
- CI/CD: tag-triggered release workflow, Android cmdline-tools/build-tools installation, and apksigner usage.

### Fixed
- Multiple Android Lint issues uncovered during CI (MissingPermission, NewApi guards, null-safety fixes).
- Release signing race conditions and `apksigner` availability in GitHub Actions.
## [v1.1.0] - 2025-06-01

### Added
- **Device Pairing**: Remember trusted devices with one tap; separate paired and nearby device lists; unpair button; auto-connect to saved devices.

### Changed
- **Audio Streaming**: Eliminated audio pauses from packet loss and lowered latency with optimized buffers; improved error recovery and smoother playback.
- **UX Improvements**: Cleaner settings, enhanced device name display, improved about dialog and connection management.

### Fixed
- Various minor fixes and polish.

## [v1.0.0] - 2025-12-03

### Added
- **Docs & Desktop Script** - Added documentation and a Python desktop script.
- **Secure TCP over TLS** - Implemented secure TCP connections over TLS for transport security.
- **Audio Improvements (Refactor)** - Improved audio sync and streaming internals.
- **Initial Commit** - Project initial import and base setup.



<!--
Guidance for maintainers:
## [vX.Y.Z] - YYYY-MM-DD
- Add new release sections at the top under "Unreleased" when preparing work.
- When cutting a release, move the Unreleased changes into a new versioned section and add the release date.
- Keep entries concise and grouped by Added/Changed/Fixed/Deprecated/Removed.
-->