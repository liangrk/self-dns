# 广告拦截优化设计:国内规则体系、纯广告模式、性能与启动提速

- 日期:2026-09-05
- 状态:已确认(用户逐节确认)
- 仓库:liangrk/self-dns(fork 自 pass-with-high-score/blockads-android)
- 相关:docs/plans/2026-09-05-bug-fix-plan.md(既有 bug 修复计划,与本设计正交)

## 1. 背景与问题

用户反馈五个问题:

| # | 问题 | 表现 |
|---|------|------|
| P1 | 抖音视频合集播放出现广告 | 现有规则无法拦截 |
| P2 | "强力拦截"(Strict profile)下部分 App 加载严重卡顿 | 影响正常使用 |
| P3 | 广告去除不彻底 | 规则覆盖不足 |
| P4 | 规则列表混杂成人/赌博等非广告规则,且分不清海外/国内,用户不知道该开哪些;担心全开慢 | 无法正确选择 |
| P5 | 开启 VPN 模式异常慢 | 每次启动都像在重新拉取规则 |

## 2. 根因分析(代码证据)

### R1 抖音广告拦不住(P1、P3 主因)

- 抖音信息流广告与正常内容走相同 API 域名(`*.douyin.com`/`*.snssdk.com`/`*.zjcdn.com`),DNS 层无法区分,整域名拦截会打断 App。
- HTTPS 过滤(MITM)仅对用户勾选的**浏览器 UID 白名单**生效(`tunnel/mitm_filter.go:128-146`),抖音等原生 App 从不被解密;cosmetic CSS / scriptlets 仅注入网页,对原生 App 无效。
- 内置列表全是国际/越南列表,**国内广告域名覆盖为零**(远程目录 `blockads-default-filter/output/filter_lists.json` 中无任何中文源)。

### R2 "强力拦截"卡顿(P2)

`serveDNS` 每查询热路径(`tunnel/engine.go:661-834`):

1. `e.mu.Lock()` 全局锁(`engine.go:782`):所有查询串行竞争一把锁,列表多、并发高时恶化。
2. 每查询 JNI 往返 `domainChecker.HasCustomRule(domain)`(`engine.go:754`)。
3. 每启用列表独立 bloom+trie 逐个遍历(`engine.go:789-815`),成本随列表数线性增长。
4. Strict 误拦国内 App 的 API/遥测域名 → App 超时重试,放大卡顿体感。

### R3 分类混乱、地域不可辨(P4)

- 上游 `filter_lists.json` 把 "StevenBlack Adult"(7.6 万条)和 "StevenBlack Gambling" 错标为 `category: "ads"`;App 侧 `FilterList.category` 只有 `AD`/`SECURITY` 两类,无地域维度;列表 UI 无筛选、无地域标识。

### R4 VPN 启动慢(P5)

- `AdBlockVpnService.startVpn()` Phase 1(`AdBlockVpnService.kt:389-390`)**串行调用** `seedDefaultsIfNeeded()`(内部即 `fetchAndSyncRemoteFilterLists()`,`FilterListRepository.kt:148`)+ 又一次 `fetchAndSyncRemoteFilterLists()` → **每次启动对 `raw.githubusercontent.com` 发起两次相同 GET**。
- HttpClient 全局超时 60s 请求 / 30s 连接(`di/AppModule.kt:49-51`);国内访问 GitHub raw 超时/被墙时,每次开 VPN 白等 30~60s+。
- `RootProxyService.kt:203-204` 同样问题;HomeViewModel/SettingsViewModel/FilterSetupViewModel 另有 4 处 `seedDefaultsIfNeeded()` 散落调用。
- trie/bloom/css 本身有本地缓存跳过(`FilterDownloadManager.kt:87-90`),规则文件不是慢的主因。

## 3. 设计

### 3.1 新建开源规则仓库 `liangrk/blockads-cn-rules`

```
blockads-cn-rules/
├── rules/                      # 人工维护的源清单(模块化)
│   ├── douyin.txt              # 抖音/字节系广告域名
│   ├── pangle.txt              # 穿山甲 SDK 域名
│   ├── cn-app-ads.txt          # 其他国内 App 广告域名
│   └── allowlist.txt           # 误拦保护名单
├── upstream.yml                # 上游中文源清单(anti-AD 等)
├── scripts/fetch_upstream.py   # 拉取上游、清洗
├── scripts/merge.py            # 合并去重、格式校验 → dist/
├── dist/cn-ads.txt             # 产出:合并后的单文件清单(域名单行)
├── dist/cn-ads.allowlist       # 误拦保护名单
┷
└── .github/workflows/update.yml  # 每日定时:拉上游→合并→校验→提交→发布
```

- 格式:**纯域名单行**(host-per-line)。App 本地编译器(`tunnel/compiler.go` parseDomainLine)与后端编译 API 都直接支持。
- 维护方式:后续抓包/反馈的新域名直接提交进 `rules/*.txt`;Actions 每日合并上游并发布 `dist/`。
- 许可:代码 MIT,清单数据 CC0(便于第三方引用)。

### 3.2 App 侧改动(当前 fork)

#### a. 类别体系修正

- `FilterList.CATEGORY_*` 扩展:`AD / SECURITY / ADULT / GAMBLING`。
- 解析远程 JSON 时兜底重映射:已知 id(`stevenblack_porn`、`stevenblack_gambling` 等)修正类别;App 侧映射不依赖上游改正(上游若改正则映射退化为直通)。

#### b. 接入新规则仓库

- seed 一条内置列表 `BlockAds CN Ads`:`originalUrl` 指向 `https://raw.githubusercontent.com/liangrk/blockads-cn-rules/main/dist/cn-ads.txt`,`category=AD`、`region=CN`。
- 编译链:优先后端编译 API(`complier.pwhs.app/api/build`);不可达时本地编译兜底(`compiler.go`)。
- 注意:内置目录 JSON 不会包含该条目,`fetchAndSyncRemoteFilterLists` 同步时必须**保留非目录来源的内置条目**(现有逻辑会删除 `isBuiltIn && name 不在 remoteNames` 的条目,需要豁免)。

#### c. "仅去广告"开关(纯广告模式)

- 设置页新增开关,存 DataStore。
- 开启后全局覆盖(在 profile 之上,切 profile 不重置):
  - 强制禁用 ADULT/GAMBLING/SECURITY 类列表
  - 强制关闭 SafeSearch、YouTube 受限模式
- 生效点:过滤列表加载(`loadAllEnabledFilters` 的启用集合)、SafeSearch/YouTube 配置下发引擎前(`AdBlockVpnService`/`RootProxyService` 读取偏好处)。

#### d. 海外/国内地域维度 + 规则可理解性

- **数据模型**:`FilterList` 增加 `region` 字段(`GLOBAL` / `CN`),Room schema v4→v5 迁移。
- **地域映射**:App 侧按已知 id 兜底(stevenblack/easylist/adguard/yoyo/abpvn/hostsvn → GLOBAL;`blockads-cn-rules` 来源 → CN)。
- **UI**(`FilterSetupScreen`):
  - 顶部"全部 / 国内 / 海外"筛选;
  - 每条规则显示地域徽标 + 通俗一句话说明;
  - **推荐标记**:系统语言为中文时,推荐集 = 国内列表 + 基础海外列表(EasyList 基础版),并提示"全开非必要"。

#### e. 新增离线兜底规则资产

- APK `assets/` 打包一份 `blockads-cn-rules` 的 `dist/cn-ads.txt` 快照(以及精简的海外基础清单),首次安装无网络(或 GitHub raw 不可达)时先用内置清单 + 本地编译跑起来。

### 3.3 引擎与启动性能

#### a. DNS 热路径优化(`tunnel/`)

按实测定主因后修复,候选按预期收益排序:

1. `serveDNS` 全局锁改 atomic 快照(`engine.go:782` 的 `e.mu.Lock()` → `atomic.Pointer` 或 RCU 风格指针替换)。
2. 热路径去 JNI:`HasCustomRule`/`IsBlocked` 改为 Go 侧本地数据直查(Kotlin 在规则变化时把自定义规则/白名单**推**给 Go,`Blocker.LoadCustomRules/LoadWhitelist` 通道已存在),不再每查询回调 Kotlin。
3. 合并 bloom:后端产物或 App 端加载时把多列表 bloom 合并为单 bloom 预筛,bloom 未命中直接放行,只对命中项查对应 trie。
4. 误拦治理:从 DNS 日志统计高频被拦的正常域名(Strict 下),提供一键加白;`https_passthrough.txt` 同思路维护。
5. DNS 日志写 Room 批量化(内存缓冲 + 定时/定量 flush)。

#### b. VPN 启动提速(P5 修复)

1. **启动路径去网络化**:
   - `startVpn()`/`startProxy()` 删除 `seedDefaultsIfNeeded()` 与 `fetchAndSyncRemoteFilterLists()` 调用;
   - 远程目录同步移到:App 进程启动时 fire-and-forget(`BlockAdsApplication`)+ FilterUpdateWorker 定时任务;
   - 清理 HomeViewModel/SettingsViewModel/FilterSetupViewModel 中散落的 `seedDefaultsIfNeeded()` 调用(收敛为仓库内单一入口)。
2. **先通后拦(可选增强)**:引擎先启动(DNS 转发先通),规则就绪后 `SetTries` 原子切换;启动体感从"等网络"变为"1-2s 通,规则随后就绪"。
3. **离线兜底**:配合 3.2e,首次安装无网络也能立即提供基础拦截。

## 4. 实施顺序

1. **第 1 批(可独立落地、立刻见效)**:VPN 启动去网络化(3.3b-1)——改动小、收益立竿见影。
2. **第 2 批**:新建 `blockads-cn-rules` 仓库 + 首版规则(先用公开的穿山甲/字节系广告域名 + anti-AD 合并)→ App 接入(3.2a/3.2b/3.2d)。
3. **第 3 批**:纯广告模式开关(3.2c)+ 规则列表 UI 改造(3.2d)。
4. **第 4 批**:引擎热路径优化(3.3a,PHK110 实测定主因)。
5. **第 5 批**:root PHK110 抓包(SSL pinning bypass),产出抖音/字节系深度域名清单回流 `rules/douyin.txt`。

## 5. 验收标准

- VPN/Root Proxy 冷启动在无外网(或 GitHub raw 被墙)时 ≤ 3s 进入拦截状态(有本地缓存时)。
- Strict + 全列表启用下,抖音/微信/淘宝冷启动与使用无可感卡顿;DNS P99 延迟与单列表基线差距 < 20%。
- 纯广告模式开启后:成人/赌博/安全类列表被禁用、SafeSearch/YouTube 受限关闭,刷抖音、浏览网页无成人/赌博域名放行投诉(即功能生效且不误伤广告拦截)。
- 抖音信息流广告:方案 A 范围内(独立广告域名)可拦截;抓包后扩充清单持续改进。
- 规则列表页:能按国内/海外筛选,每条规则有地域徽标与通俗说明,中文环境下有推荐标记。

## 6. 测试资源

- root 一加 PHK110(USB 连接,adb d8316e16),用于:Strict 卡顿打点、全开性能基线、抖音抓包、方案验证。
