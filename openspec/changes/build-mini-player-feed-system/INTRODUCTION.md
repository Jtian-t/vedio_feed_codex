# 自研短视频 Feed 执行介绍

## 1. 项目定位

本项目要实现一个抖音风格的上下滑短视频 Feed，但重点不是接入成熟播放器，而是自研一个小型播放器内核和 Feed 调度系统。

核心约束：

- 必须使用 Kotlin。
- UI 使用传统 View 方案，MVP 采用 `RecyclerView + PagerSnapHelper + TextureView`。
- 播放链路使用 `MediaExtractor + MediaCodec + Surface`。
- 禁用 ExoPlayer、IjkPlayer、Android `MediaPlayer`、AndroidVideoCache 等成熟播放器或缓存封装。
- 网络使用裸 OkHttp，并自写 interceptor。
- 必须产出 Android Studio Profiler、Perfetto、Systrace 证据。

## 2. 总体结论

当前设计已经覆盖项目要求 A1-A11、工具约束、验收指标和交付材料。三轮子智能体审查结论一致：

- 没有 P0 级设计缺失。
- 方案已经达到可执行粒度。
- 后续可以进入 `/opsx:apply build-mini-player-feed-system` 开始实现。
- 仍需在实现阶段重点关注真实视频源、音频范围、proxy 稳定性和性能证据。

## 3. 架构总览

```text
VideoFeedFragment
  |
  v
FeedController / RecyclerView + PagerSnapHelper
  |
  v
PlayerCoordinator
  |-- current slot
  |-- next slot
  |-- previous slot
  |
  v
MiniPlayer
  |
  | MediaExtractor
  v
LocalProxyServer  <--- optional direct MP4 fallback for debugging
  |
  v
Cache Resolver
  |-- Memory Cache
  |-- Disk Cache + Segment Index + LRU
  |-- OkHttp RangeFetcher
  |
  v
MediaCodec -> TextureView/SurfaceView Surface
```

模块职责：

| 模块 | 职责 |
| --- | --- |
| `data` | mock JSON、视频元数据、variant、10k 数据生成 |
| `feed` | 全屏上下滑、ViewHolder、手势、点赞评论本地状态 |
| `scheduler` | 当前/下一个/上一个播放器槽位，页面切换和预热 |
| `player` | `MediaExtractor`、`MediaCodec`、状态机、seek、clock、Surface |
| `proxy` | 本地 HTTP URL、请求解析、响应头、Range 转发 |
| `cache` | 内存/磁盘/网络三级缓存、segment index、LRU、并发锁 |
| `network` | OkHttp、Range fetch、带宽采样、自定义 interceptor |
| `perf` | 首帧、FPS、内存、缓存命中、错误和 trace 事件 |

## 4. 需求覆盖映射

| 要求 | 设计覆盖 |
| --- | --- |
| A1 上下滑无限视频流 | `RecyclerView + PagerSnapHelper`、10k 数据、三槽调度、首帧指标 |
| A2 播放/暂停/进度/倍速 | `MiniPlayer` 控制 API、tap、progress seek、long-press speed |
| A3 解码与渲染 | `MediaExtractor + MediaCodec + TextureView/SurfaceView` |
| A4 点赞/评论 UI | 本地 like/comment 状态，ViewHolder 状态恢复 |
| A5 mock JSON | 至少 100 条 progressive MP4，variant、Range hint、HLS fallback |
| A6 智能预加载 | current 优先、next 1、快滑 next 2、取消 stale job |
| A7 三级缓存 | memory + disk + network、LRU、segment index、断点续传 |
| A8 边下边播 | 本地 HTTP proxy、Range、206/200/416、buffer watermark |
| A9 1 万条滑动 | 10k 生成规则、轻量绑定、Perfetto/Systrace 证据 |
| A10 弱网降级 | OkHttp throughput、rolling window、hysteresis、未来 item 降级 |
| A11 后台释放 | background release、foreground restore、surface/codec/proxy/cache 顺序 |

## 5. 播放器内核方案

播放器主线：

```text
PlaybackSource
  -> MediaExtractor selects video track
  -> MediaCodec configured by MediaFormat
  -> decode thread queues input buffers
  -> output buffers released to Surface
  -> TextureView displays video
```

关键设计：

- 播放状态机包含 `Idle`、`Preparing`、`Prepared`、`FirstFrameRendered`、`Playing`、`Paused`、`Buffering`、`Ended`、`Error`、`Released`。
- 解码循环在非主线程运行，UI 不直接接触 `MediaCodec`。
- `release()` 必须幂等，避免页面切换和生命周期重复释放崩溃。
- `seekTo()` 通过 extractor seek 到最近 sync point，并 flush codec。
- `Surface` 销毁时暂停输出；新 surface 可用后重绑或重建 codec。

音频范围：

- MVP 以 video-first 为主。
- 已设计 `MediaClock`，支持 video-only 单调时钟，也支持未来接入 `AudioTrack` 音频主时钟。
- 如果实现阶段不做音频，需要在 README 和 Demo 中明确“当前为 video-only MVP，A/V sync 架构已预留”。

## 6. 本地代理与网络传输

不直接让播放器读远端 URL，而是走本地代理：

```text
MediaExtractor
  -> http://127.0.0.1:{port}/video/{cacheKey}
  -> LocalProxyServer
  -> CacheStore
  -> RangeFetcher / OkHttp
```

这样做的原因：

- 可控 Range 请求和响应头。
- 可实现边下边播。
- 预加载数据可被播放器复用。
- 可以统计 cache hit 和网络字节。
- 网络异常不会直接污染解码器状态。

proxy/cache 设计：

- 支持 `206 Partial Content`、`200 OK fallback`、`416`、`Content-Length`、`Content-Range`。
- 使用 cache key + segment index 管理部分缓存。
- 当前播放请求优先级最高，预加载可暂停或取消。
- 缓冲水位包含 startup、low-water、high-water。
- 网络超时、断流、Range 不一致时进入 buffering、重试、标记 unhealthy 或切换 fallback。

## 7. Mock 数据与视频源

base catalog 位于未来实现的 `assets/mock/videos.json`，schema 设计见 `docs/mock-catalog-schema.md`。

每条数据至少包含：

- `id`
- `title`
- `author`
- `durationMs`
- `width` / `height`
- `coverUri`
- `mimeType`
- `bitrateKbps`
- `rangeSupport`
- `fastStart`
- 至少一个 progressive MP4 variant URL

注意：

- MVP 必须保证至少 100 条可播 MP4。
- HLS 可以作为可选 metadata，但 HLS-only 不进入 MVP 播放器。
- 10k Feed 数据通过 base catalog 重复生成，但必须生成稳定唯一 ID。
- URL 必须验证 Range、Content-Length、fast-start 风险。

## 8. 预加载与弱网策略

预加载策略：

- 当前播放优先。
- 首帧出现或播放达到阈值后预加载 next 1。
- 快速连续前滑时预加载 next 2。
- 方向变化、seek、离屏、后台时取消 stale preload。

弱网策略：

- OkHttp interceptor 采样吞吐。
- rolling window + hysteresis 防止频繁升降级。
- 降级作用于后续 prepare/preload，不做高风险的播放中无缝切换。
- 当前播放缺字节时进入 buffering，不给 codec 喂不完整 sample。

## 9. 性能与验收证据

验收指标：

| 指标 | 目标 | 证据 |
| --- | --- | --- |
| 首帧 | `<800ms` | metrics JSON、录屏、设备信息 |
| 滑动 | 95% frames `>=55fps`，工程目标接近 60fps | Perfetto + Systrace |
| 内存 | 单视频 `<200MB` | Android Studio Profiler |
| 稳定性 | 200 次自动滑动 0 崩溃 | 测试日志 |
| 缓存 | 重复观看 cache hit `>=90%` | cache metrics summary |
| 弱网 | 自动选择低清晰度 | network simulation notes + metrics |

必须提交：

- Android Studio Profiler
- Perfetto
- Systrace
- README
- AI_USAGE
- DECISIONS
- BUGFIX
- Demo 视频
- 30+ meaningful commits 或环境限制说明

## 10. 推荐实现顺序

按 `tasks.md` 执行：

1. Project Foundation：工程结构、接口骨架、参数表、文档占位。
2. Feed Shell and Data：mock schema、10k 数据、全屏 Feed、点赞评论。
3. Mini Player Kernel：直连 MP4 播放、状态机、seek、surface、metrics。
4. Feed Scheduling：三槽播放器、页面切换、后台释放和恢复。
5. Network / Proxy / Cache：RangeFetcher、cache index、本地 proxy、buffering。
6. Preload / Weak Network：cache preload、吞吐估计、多码率选择。
7. Observability / Performance：首帧、FPS、内存、缓存、滑动测试证据。
8. Delivery Materials：README、AI_USAGE、DECISIONS、BUGFIX、性能报告、Demo。
9. Final Acceptance Review：逐项核对 A1-A11 和禁用依赖。

实现建议：

- 先跑通 direct MP4，再接 proxy。
- 先 video-only 稳定，再考虑 `AudioTrack`。
- 先当前 slot 播放，再扩展 next/previous。
- 先磁盘 segment cache，再做复杂预加载策略。

## 11. 已知风险与答辩口径

| 风险 | 口径 / 控制 |
| --- | --- |
| HLS 不作为 MVP 播放主线 | 需求允许 HLS/MP4 URL，MVP 保证 MP4 可播；HLS 有 fallback/unsupported 策略 |
| 音频可选 | 已设计 MediaClock 和 AudioTrack 扩展；若 video-only，要明确说明 MVP 边界 |
| 本地 proxy 引入错误 | 先 direct MP4 baseline，再 feature flag 接 proxy；Range/header/cache 都有校验 |
| 非 fast-start MP4 首帧慢 | URL 验证时标记风险，优先使用 fast-start 源 |
| 弱网切换不是无缝中途切换 | 降级作用于后续 prepare/preload，避免破坏当前解码状态 |
| 设备差异 | 记录设备、SoC、Android 版本和 build type；MediaCodec 错误分类恢复 |
| 性能证据难拿 | perf-validation-plan 已定义工具、目录、指标和报告模板 |

## 12. 文档导航

| 文档 | 用途 |
| --- | --- |
| `proposal.md` | 为什么做这个 change |
| `design.md` | 总体技术设计和关键取舍 |
| `tasks.md` | 78 个执行任务 |
| `specs/mini-player-kernel/spec.md` | 播放器内核要求 |
| `specs/short-video-feed/spec.md` | Feed 和 mock 数据要求 |
| `specs/video-preload-cache/spec.md` | 预加载、缓存、proxy、弱网要求 |
| `specs/performance-delivery/spec.md` | 性能和交付要求 |
| `docs/architecture-interfaces.md` | 接口、状态机、线程、错误、参数 |
| `docs/proxy-cache-design.md` | 本地代理和缓存实现细节 |
| `docs/mock-catalog-schema.md` | mock 数据 schema 和 URL 验证 |
| `docs/feed-player-sequence.md` | Feed、Surface、播放器槽位时序 |
| `docs/perf-validation-plan.md` | 性能采集和报告计划 |
| `docs/execution-decisions.md` | SDK、音频、URL、FPS、设备执行决策 |

## 13. 当前状态

- OpenSpec artifacts：complete。
- Strict validation：通过。
- 子智能体审查：无 P0 阻塞。
- 建议下一步：进入 `/opsx:apply build-mini-player-feed-system`。
