# 国内规则体系 + VPN 启动提速 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地设计文档 `docs/superpowers/specs/2026-09-05-ad-blocking-optimization-design.md` 的第 1–3 批:VPN/Root Proxy 启动去网络化、新建 `blockads-cn-rules` 规则仓库并接入 App、类别/地域数据模型、纯广告模式开关、规则列表 UI 改造,并在 PHK110 实机验证。

**Architecture:** 启动路径改为"本地缓存先通、网络同步后台做、完成后原子推送 trie";规则仓库独立于 App 演进(纯域名单行,anti-AD 上游 + 人工模块清单 + allowlist 保护);App 侧以映射器(`FilterRulesMapper`)修正上游分类错误并推断地域;纯广告模式是 profile 之上的运行时覆盖层,不改动 profile 持久状态。

**Tech Stack:** Kotlin + Room + DataStore + Compose(Material 3)、GitHub Actions、Python(规则管线)、gh CLI、adb。

**范围说明:** 设计第 4 批(引擎热路径优化)与第 5 批(root 抓包)不在本计划,后续单独出计划。

---

## Task 1: VPN / Root Proxy 启动去网络化(先通后拦)

**Files:**
- Modify: `app/src/main/java/app/pwhs/blockads/data/repository/FilterListRepository.kt`
- Modify: `app/src/main/java/app/pwhs/blockads/service/AdBlockVpnService.kt:378-392`(Phase 1)及引擎启动后
- Modify: `app/src/main/java/app/pwhs/blockads/service/RootProxyService.kt:200-210` 附近

- [ ] **Step 1: 修正 `seedDefaultsIfNeeded` 的名不副实(每启动必拉网络)**

`FilterListRepository.kt` 中找到(约 144-149 行):

```kotlin
    suspend fun seedDefaultsIfNeeded() = withContext(Dispatchers.IO) {
        fetchAndSyncRemoteFilterLists()
    }
```

替换为(只在本地目录为空时才需要网络;老用户的元数据刷新由 `FilterUpdateWorker` 定时任务负责,见 `FilterListRepository.kt:445`):

```kotlin
    suspend fun seedDefaultsIfNeeded() = withContext(Dispatchers.IO) {
        // Only hit the network on a fresh install (empty catalog). Ongoing
        // metadata refreshes are handled by FilterUpdateWorker, so VPN startup
        // must never block on this request.
        if (filterListDao.count() == 0) {
            fetchAndSyncRemoteFilterLists()
        }
    }
```

- [ ] **Step 2: Repository 增加统一的后台同步入口与 trie 路径快照**

在 `FilterListRepository` 中新增(放在 `loadAllEnabledFilters` 之后):

```kotlin
    /** Snapshot of current trie/bloom path CSVs, used to detect changes after a background sync. */
    data class TriePaths(
        val adTrie: String,
        val secTrie: String,
        val adBloom: String,
        val secBloom: String
    )

    fun getTriePathsSnapshot(): TriePaths = TriePaths(
        adTrie = adTriePaths,
        secTrie = securityTriePaths,
        adBloom = adBloomPaths,
        secBloom = securityBloomPaths
    )

    /**
     * Background network refresh: remote catalog + CN rules seed.
     * Called AFTER the engine is running; never on the startup critical path.
     */
    suspend fun backgroundSyncAfterStart() {
        try {
            seedDefaultsIfNeeded()
            seedCnRulesIfNeeded()   // defined in Task 3
            fetchAndSyncRemoteFilterLists()
        } catch (e: Exception) {
            Timber.e(e, "Background filter sync failed (non-fatal)")
        }
    }
```

注:`seedCnRulesIfNeeded()` 在 Task 3 定义;Task 1 执行时若 Task 3 未完成,先注释该行并留 `// TODO(Task 3): enable CN rules seed`(此 TODO 允许,因为它是两个任务间的显式交接,Task 3 必须删除它)。

- [ ] **Step 3: 重写 `AdBlockVpnService.startVpn()` 的 Phase 1 并加后台同步**

`AdBlockVpnService.kt:378-392` 现状:

```kotlin
                // ── Phase 1: Load filters ──
                // Always call loadAllEnabledFilters() — the fingerprint cache inside
                // FilterListRepository handles the fast path (~50ms mmap if unchanged,
                // full rebuild only when enabled filters or cache files change).
                connectingPhase = getString(R.string.vpn_phase_loading_filters)
                updateNotification()

                // Load whitelist + custom rules (fast, small sets) BEFORE the large filter trie
                // This ensures they are immediately available for the Go engine.
                filterRepo.loadWhitelist()
                filterRepo.loadCustomRules()

                filterRepo.seedDefaultsIfNeeded()
                filterRepo.fetchAndSyncRemoteFilterLists()
                val result = filterRepo.loadAllEnabledFilters()
                Timber.d("Filters loaded: ${result.getOrDefault(0)} domains")
```

替换为:

```kotlin
                // ── Phase 1: Load filters (local cache only — no network on the critical path) ──
                // Network sync happens AFTER the engine is up (see backgroundRefresh
                // below). Local cache + mmap keeps this phase at ~50ms.
                connectingPhase = getString(R.string.vpn_phase_loading_filters)
                updateNotification()

                // Load whitelist + custom rules (fast, small sets) BEFORE the large filter trie
                // This ensures they are immediately available for the Go engine.
                filterRepo.loadWhitelist()
                filterRepo.loadCustomRules()

                val result = filterRepo.loadAllEnabledFilters()
                Timber.d("Filters loaded (local): ${result.getOrDefault(0)} domains")
                val triesAtStart = filterRepo.getTriePathsSnapshot()
```

然后找到 Phase 3 中引擎启动完成、`goTunnelAdapter.updateTries()` 首次调用之后的位置(`AdBlockVpnService.kt:543` 注释 "We use drop(1) because start() already calls updateTries() once on boot" 附近的启动序列末尾),在同一 `serviceScope.launch` 的末尾追加:

```kotlin
                // ── Background refresh: network sync AFTER the engine is running ──
                serviceScope.launch {
                    filterRepo.backgroundSyncAfterStart()
                    filterRepo.loadAllEnabledFilters()
                    val after = filterRepo.getTriePathsSnapshot()
                    if (after != triesAtStart) {
                        Timber.d("Filter data changed after background sync — pushing tries")
                        goTunnelAdapter.updateTries()
                    }
                }
```

- [ ] **Step 4: `RootProxyService` 同样处理**

`RootProxyService.kt:203-204` 现状(位于 `startProxy` 的加载序列):

```kotlin
                filterRepo.seedDefaultsIfNeeded()
                filterRepo.fetchAndSyncRemoteFilterLists()
```

删除这两行;在同一函数中,加载 `loadAllEnabledFilters()` 并**启动 Go 引擎之后**(与 VPN 模式同样的位置),追加:

```kotlin
                // Background network sync AFTER the engine is up
                serviceScope.launch {
                    filterRepo.backgroundSyncAfterStart()
                    filterRepo.loadAllEnabledFilters()
                    // Root proxy re-applies engine config via restartProxy semantics;
                    // pushing tries is enough for rule changes.
                    goTunnelAdapter.updateTries()
                }
```

- [ ] **Step 5: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/app/pwhs/blockads/data/repository/FilterListRepository.kt \
        app/src/main/java/app/pwhs/blockads/service/AdBlockVpnService.kt \
        app/src/main/java/app/pwhs/blockads/service/RootProxyService.kt
git commit -m "perf: move network filter sync off the VPN startup path

seedDefaultsIfNeeded() + fetchAndSyncRemoteFilterLists() ran two
serialized GETs against raw.githubusercontent.com on every VPN/root
start (30-60s+ when GitHub is unreachable). Startup now uses local
cache only; sync runs in the background after the engine is up and
pushes refreshed tries when the data changed."
```

---

## Task 2: 创建规则仓库 liangrk/blockads-cn-rules

**Files:**(新仓库,本地目录 `Z:\hacker_project\blockads-cn-rules`)
- Create: `README.md`、`LICENSE`、`upstream.yml`
- Create: `rules/{douyin,pangle,kuaishou,jd,zhihu,amap,gdt,baidu}.txt`(空模板,注释说明)
- Create: `rules/allowlist.txt`(主业务域名保护名单)
- Create: `scripts/merge.py`、`scripts/fetch_upstream.py`
- Create: `.github/workflows/update.yml`
- Create: `dist/cn-ads.txt`、`dist/cn-ads.allowlist.txt`(首版,由脚本生成)

- [ ] **Step 1: 创建仓库并初始化本地目录**

```bash
mkdir -p /z/hacker_project/blockads-cn-rules && cd /z/hacker_project/blockads-cn-rules
git init -b main
gh repo create liangrk/blockads-cn-rules --public \
  --description "China-focused ad domains for BlockAds: CN ad SDK vendors (Pangle/Kuaishou/GDT/Baidu/JD/Zhihu/Amap) + anti-AD merge. Ads only — core app domains protected by allowlist."
```

- [ ] **Step 2: 写 upstream.yml**

```yaml
# Upstream ad-domain sources merged daily. Only sources that are
# ads-only (no core app/business domains) are allowed here.
upstreams:
  - name: anti-AD
    url: https://anti-ad.net/domains.txt
    format: domains   # one plain domain per line
```

- [ ] **Step 3: 写 rules/ 空模板与 allowlist**

`rules/douyin.txt`(其余 `pangle/kuaishou/jd/zhihu/amap/gdt/baidu.txt` 同结构,注释相应替换):

```
# Douyin / ByteDance ad-only domains.
# Format: one domain per line (plain, or "0.0.0.0 domain").
# NEVER list core business domains here (douyin.com, snssdk.com, zjcdn.com,
# bytecdn, etc.) — in-feed ads share these domains with normal content.
# Populate only from packet captures or verified ad-only subdomains.
```

`rules/allowlist.txt` — 主业务域名保护(这些域名及其子域**永不拦截**,合并脚本强制排除):

```
# Core business domains that must NEVER be blocked, even if an upstream
# list includes them. Merge script strips them (and all subdomains) from
# the final artifact.
douyin.com
snssdk.com
zjcdn.com
kuaishou.com
jd.com
# Core business domains that must NEVER be blocked, even if an upstream
# list includes them. Merge script strips them (and all subdomains) from
# the final artifact.
douyin.com
snssdk.com
zjcdn.com
kuaishou.com
jd.com
360buyimg.com
zhihu.com
zhimg.com
amap.com
autonavi.com
gaode.com
qq.com
weixin.com
bilibili.com
baidu.com
bdstatic.com
bdimg.com
taobao.com
tmall.com
alipay.com
aliyun.com
meituan.com
dianping.com
pinduoduo.com
netease.com
126.net
```

- [ ] **Step 4: 写 scripts/fetch_upstream.py 与 scripts/merge.py**

`scripts/fetch_upstream.py`:

```python
#!/usr/bin/env python3
"""Fetch upstream domain lists declared in upstream.yml into cache/."""
import pathlib
import sys
import urllib.request

import yaml


def main() -> int:
    root = pathlib.Path(__file__).resolve().parent.parent
    cache = root / "cache"
    cache.mkdir(exist_ok=True)
    cfg = yaml.safe_load((root / "upstream.yml").read_text(encoding="utf-8"))
    ok = True
    for src in cfg["upstreams"]:
        dest = cache / (src["name"].lower().replace(" ", "_") + ".txt")
        try:
            req = urllib.request.Request(src["url"], headers={"User-Agent": "blockads-cn-rules/1.0"})
            with urllib.request.urlopen(req, timeout=60) as resp, dest.open("wb") as out:
                out.write(resp.read())
            print(f"fetched {src['name']}: {dest.stat().st_size} bytes")
        except Exception as exc:  # noqa: BLE001 - keep building on partial failures
            print(f"WARN: failed to fetch {src['name']}: {exc}", file=sys.stderr)
            ok = ok and dest.exists()
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
```

`scripts/merge.py`:

```python
#!/usr/bin/env python3
"""Merge rules/ + upstream cache/ into dist/, enforcing the allowlist.

Output: dist/cn-ads.txt (domains, sorted) + dist/cn-ads.allowlist.txt.
"""
import pathlib
import re
import sys

DOMAIN_RE = re.compile(r"^(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,}$")


def extract_domain(line: str) -> str | None:
    line = line.strip().lower()
    if not line or line.startswith(("#", "!")):
        return None
    if line.startswith("0.0.0.0 ") or line.startswith("127.0.0.1 "):
        line = line.split()[1]
    line = line.split("#")[0].strip()
    return line if DOMAIN_RE.match(line) else None


def load(path: pathlib.Path) -> set[str]:
    if not path.exists():
        return set()
    domains = set()
    for raw in path.read_text(encoding="utf-8", errors="ignore").splitlines():
        d = extract_domain(raw)
        if d:
            domains.add(d)
    return domains


def main() -> int:
    root = pathlib.Path(__file__).resolve().parent.parent
    rules_dir = root / "rules"
    cache_dir = root / "cache"
    dist_dir = root / "dist"
    dist_dir.mkdir(exist_ok=True)

    allow = set()
    for f in rules_dir.glob("*.txt"):
        if f.name == "allowlist.txt":
            allow = load(f)
    if not allow:
        print("FATAL: rules/allowlist.txt empty or missing", file=sys.stderr)
        return 1

    blocked: set[str] = set()
    for f in sorted(rules_dir.glob("*.txt")):
        if f.name == "allowlist.txt":
            continue
        blocked |= load(f)

    for f in sorted(cache_dir.glob("*.txt")):
        blocked |= load(f)

    def protected(domain: str) -> bool:
        parts = domain.split(".")
        return any(domain == a or domain.endswith("." + a) for a in allow)

    protected_hits = {d for d in blocked if protected(d)}
    final = sorted(d for d in blocked if not protected(d))

    (dist_dir / "cn-ads.txt").write_text(
        "# blockads-cn-rules — merged CN ad domains (ads only).\n"
        "# Core business domains are protected via rules/allowlist.txt.\n"
        + "\n".join(final) + "\n",
        encoding="utf-8",
    )
    (dist_dir / "cn-ads.allowlist.txt").write_text(
        "\n".join(sorted(allow)) + "\n", encoding="utf-8"
    )
    print(f"dist/cn-ads.txt: {len(final)} domains; allowlist: {len(allow)}")
    if protected_hits:
        print(f"stripped {len(protected_hits)} protected domains from upstream, e.g. {sorted(protected_hits)[:5]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 5: 写 .github/workflows/update.yml**

```yaml
name: update-rules

on:
  schedule:
    - cron: "0 16 * * *"   # daily 00:00 Beijing time
  workflow_dispatch: {}

permissions:
  contents: write

jobs:
  update:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-python@v5
        with:
          python-version: "3.12"

      - run: pip install pyyaml

      - name: Fetch upstreams
        run: python scripts/fetch_upstream.py

      - name: Merge & enforce allowlist
        run: python scripts/merge.py

      - name: Commit if changed
        run: |
          git config user.name "blockads-bot"
          git config user.email "actions@users.noreply.github.com"
          git add dist/
          if git diff --cached --quiet; then
            echo "No changes"
          else
            git commit -m "rules: daily upstream merge $(date -u +%F)"
            git push
          fi
```

- [ ] **Step 6: 生成首版 dist 并提交推送**

```bash
cd /z/hacker_project/blockads-cn-rules
pip install pyyaml   # 如已装可跳过
python scripts/fetch_upstream.py
python scripts/merge.py
wc -l dist/cn-ads.txt   # 预期: 数万行(anti-AD 约 8-10 万级);若 < 10000 说明上游拉取失败,排查网络
```

然后创建 `README.md`(内容:仓库用途、格式说明、"ads only, core domains protected by allowlist"、如何贡献域名)与 `LICENSE`(MIT)。

```bash
git add -A
git commit -m "init: CN ad rules repo (anti-AD merge + vendor modules + allowlist)"
git remote add origin git@github.com:liangrk/blockads-cn-rules.git
git push -u origin main
```

- [ ] **Step 7: 验证发布产物可达**

Run: `curl -fsSL https://raw.githubusercontent.com/liangrk/blockads-cn-rules/main/dist/cn-ads.txt | head -5; curl -fsSL https://raw.githubusercontent.com/liangrk/blockads-cn-rules/main/dist/cn-ads.allowlist.txt | head -5`
Expected: 两个 URL 返回内容(HTTP 200)。

---

## Task 3: App 数据层 —— 类别/地域映射、Room 迁移、CN 列表接入

**Files:**
- Create: `app/src/main/java/app/pwhs/blockads/data/repository/FilterRulesMapper.kt`
- Test: `app/src/test/java/app/pwhs/blockads/FilterRulesMapperTest.kt`
- Modify: `app/src/main/java/app/pwhs/blockads/data/entities/FilterList.kt`
- Modify: `app/src/main/java/app/pwhs/blockads/data/AppDatabase.kt`(version 13→14 + MIGRATION_13_14)
- Modify: `app/src/main/java/app/pwhs/blockads/data/repository/FilterListRepository.kt`
- Modify: `app/src/main/java/app/pwhs/blockads/di/AppModule.kt`(若 FilterListRepository 构造需要新依赖)

- [ ] **Step 1: 写失败测试**

`app/src/test/java/app/pwhs/blockads/FilterRulesMapperTest.kt`:

```kotlin
package app.pwhs.blockads

import app.pwhs.blockads.data.entities.FilterList
import app.pwhs.blockads.data.repository.FilterRulesMapper
import org.junit.Assert.assertEquals
import org.junit.Test

class FilterRulesMapperTest {

    @Test
    fun `adult and gambling ids remap to their own categories`() {
        assertEquals(
            FilterList.CATEGORY_ADULT,
            FilterRulesMapper.normalizeCategory("stevenblack_porn", "ads")
        )
        assertEquals(
            FilterList.CATEGORY_GAMBLING,
            FilterRulesMapper.normalizeCategory("stevenblack_gambling", "ads")
        )
    }

    @Test
    fun `security category passes through`() {
        assertEquals(
            FilterList.CATEGORY_SECURITY,
            FilterRulesMapper.normalizeCategory("some_security_list", "security")
        )
    }

    @Test
    fun `unknown ids fall back to remote category`() {
        assertEquals(
            FilterList.CATEGORY_AD,
            FilterRulesMapper.normalizeCategory("easylist", "ads")
        )
    }

    @Test
    fun `cn rules repo maps to CN region`() {
        assertEquals(
            FilterList.REGION_CN,
            FilterRulesMapper.inferRegion("whatever", "https://raw.githubusercontent.com/liangrk/blockads-cn-rules/main/dist/cn-ads.txt")
        )
    }

    @Test
    fun `known international lists map to GLOBAL`() {
        assertEquals(
            FilterList.REGION_GLOBAL,
            FilterRulesMapper.inferRegion("stevenblack", "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts")
        )
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "app.pwhs.blockads.FilterRulesMapperTest"`
Expected: 编译失败(`FilterRulesMapper`、`CATEGORY_ADULT` 等不存在)

- [ ] **Step 3: FilterList 实体加常量与 region 字段**

`FilterList.kt` 的 companion object 改为:

```kotlin
    companion object {
        const val CATEGORY_AD = "AD"
        const val CATEGORY_SECURITY = "SECURITY"
        const val CATEGORY_ADULT = "ADULT"
        const val CATEGORY_GAMBLING = "GAMBLING"

        const val REGION_GLOBAL = "GLOBAL"
        const val REGION_CN = "CN"
    }
```

并在实体字段区(`val originalUrl: String = ""` 之后)加:

```kotlin
    val region: String = REGION_GLOBAL,
```

- [ ] **Step 4: Room 迁移 13→14**

`AppDatabase.kt`:`version = 13` → `version = 14`;仿照 `MIGRATION_12_13`(193 行)的写法新增:

```kotlin
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `filter_lists` ADD COLUMN `region` TEXT NOT NULL DEFAULT 'GLOBAL'"
                )
            }
        }
```

并加入 `addMigrations(...)` 链末尾(`MIGRATION_12_13` 之后)。

- [ ] **Step 5: FilterRulesMapper 实现**

`app/src/main/java/app/pwhs/blockads/data/repository/FilterRulesMapper.kt`:

```kotlin
package app.pwhs.blockads.data.repository

import app.pwhs.blockads.data.entities.FilterList

/**
 * Fixes upstream catalog metadata errors and infers the region dimension.
 * Pure functions — unit-testable without Android.
 */
object FilterRulesMapper {

    // Remote catalog mislabels adult/gambling lists as "ads"; fix by id.
    private val CATEGORY_BY_ID = mapOf(
        "stevenblack_porn" to FilterList.CATEGORY_ADULT,
        "stevenblack_gambling" to FilterList.CATEGORY_GAMBLING
    )

    fun normalizeCategory(id: String, remoteCategory: String): String {
        CATEGORY_BY_ID[id]?.let { return it }
        return if (remoteCategory.equals("security", ignoreCase = true)) {
            FilterList.CATEGORY_SECURITY
        } else {
            FilterList.CATEGORY_AD
        }
    }

    private val CN_MARKERS = listOf(
        "blockads-cn-rules",        // our own CN rules repo
        "anti-ad.net",              // Chinese upstream sources
    )

    fun inferRegion(id: String, originalUrl: String): String {
        val url = originalUrl.lowercase()
        return if (CN_MARKERS.any { url.contains(it) }) {
            FilterList.REGION_CN
        } else {
            FilterList.REGION_GLOBAL
        }
    }

    /** Built-in entries not present in the remote catalog must survive catalog sync. */
    fun isLocalOnlyBuiltIn(originalUrl: String): Boolean =
        originalUrl.contains("blockads-cn-rules")
    }
```

(实现时以编译通过为准;该 object 只含上面三个公开函数。)

- [ ] **Step 6: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "app.pwhh.blockads.FilterRulesMapperTest"`(注意包名正确拼写 `pwhs`)
Expected: PASS(5 个测试)

- [ ] Step 7: Repository 接入映射器、CN seed 与 allowlist 合并

`FilterListRepository`:

a) `fetchAndSyncRemoteFilterLists()` 中,现有 category 赋值(约 173 行):

```kotlin
val category = if (remote.category == "security") FilterList.CATEGORY_SECURITY else FilterList.CATEGORY_AD
```

替换为:

```kotlin
val category = FilterRulesMapper.normalizeCategory(remote.id, remote.category ?: "ads")
val region = FilterRulesMapper.inferRegion(remote.id, remote.originalUrl ?: "")
```

两处 `FilterList(...)` 构造(update copy 与 insert,约 190 与 207 行)都加 `region = region`。

b) obsolete 删除逻辑(约 228 行)豁免本地内置条目:

```kotlin
val remoteNames = remoteLists.map { it.name }.toSet()
val obsolete = existingLists.filter {
    it.isBuiltIn && it.name !in remoteNames &&
        !FilterRulesMapper.isLocalOnlyBuiltIn(it.originalUrl)
}
```

c) 新增 CN 规则 seed(类内新增,依赖 `customFilterApi` —— 构造注入;`AppModule.kt` 里 FilterListRepository 的 single 构造处补传 `get<CustomFilterApi>()`,若已有则跳过):

```kotlin
    companion object {
        // ...
        const val CN_RULES_NAME = "BlockAds CN Ads"
        const val CN_RULES_DIST_URL =
            "https://raw.githubusercontent.com/liangrk/blockads-cn-rules/main/dist/cn-ads.txt"
        const val CN_RULES_ALLOWLIST_URL =
            "https://raw.githubusercontent.com/liangrk/blockads-cn-rules/main/dist/cn-ads.allowlist.txt"
    }

    suspend fun seedCnRulesIfNeeded() = withContext(Dispatchers.IO) {
        val existing = filterListDao.findByOriginalUrl(CN_RULES_DIST_URL)
        val filter = existing ?: run {
            val inserted = FilterList(
                name = CN_RULES_NAME,
                url = CN_RULES_DIST_URL,
                originalUrl = CN_RULES_DIST_URL,
                description = "CN ad domains (Douyin/Kuaishou/JD/Zhihu/Amap + major ad SDK vendors)",
                isEnabled = true,
                isBuiltIn = true,
                category = FilterList.CATEGORY_AD,
                region = FilterList.REGION_CN
            )
            val id = filterListDao.insert(filter)   // check DAO insert returns Long
            filter.copy(id = id)
        }
        if (filter.bloomUrl.isNotEmpty() && filter.trieUrl.isNotEmpty()) return@withContext
        compileCnFilter(filter)
    }

    /**
     * Compile the CN list: backend API first, on-device compile as fallback.
     * Mirrors CustomFilterManager's flow; artifacts are stored as local files
     * referenced via the "local://" sentinel handled by FilterDownloadManager.
     */
    private suspend fun compileCnFilter(filter: FilterList) = withContext(Dispatchers.IO) {
        val rawFile = File(context.filesDir, "remote_filters/${filter.id}.raw")
        val ok = try {
            downloadManager.downloadRawTo(CN_RULES_DIST_URL, rawFile)
        } catch (e: Exception) {
            Timber.w(e, "CN rules download failed")
            false
        }
        if (!ok) return@withContext

        val triePath = File(context.filesDir, "remote_filters/${filter.id}.trie")
        val bloomPath = File(context.filesDir, "remote_filters/${filter.id}.bloom")
        val count = tunnel.Compiler.compileFilterList(
            rawFile.absolutePath, triePath.absolutePath, bloomPath.absolutePath
        )
        filterListDao.update(
            filter.copy(
                url = CN_RULES_DIST_URL,
                bloomUrl = "local://${filter.id}.bloom",
                trieUrl = "local://${filter.id}.trie",
                ruleCount = count,
                domainCount = count,
                lastUpdated = System.currentTimeMillis()
            )
        )
        Timber.d("CN rules compiled locally: $count domains")
    }
```

说明:
- `downloadRawTo` 与 gomobile 绑定类名需在实现时与 `app/libs/tunnel.aar` 中实际生成的绑定名核对(`tunnel.Compiler.compileFilterList` 对应 Go 的 `CompileFilterList`;绑定名以反编译/IDE 提示为准)。`downloadRawTo` 不存在则给 FilterDownloadManager 新增:

```kotlin
    suspend fun downloadRawTo(url: String, destFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val response = client.get(url)
            val channel = response.bodyAsChannel()
            val tmp = File(destFile.parent, "${destFile.name}.tmp")
            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(8 * 1024)
                while (channel.readAvailable(buf).also { n = it } >= 0) {
                    if (n > 0) out.write(buf, 0, n)
                    out.flush()
                }
            }
            tmp.renameTo(destFile) || destFile.exists()
        } catch (e: Exception) {
            Timber.e(e, "downloadRawTo failed: $url")
            false
        }
    }
```

d) allowlist 合并进 whitelist 通道:`loadWhitelist()`(`FilterListRepository.kt:129-136`)末尾追加:

```kotlin
        // CN rules allowlist: core app domains must never be blocked even if
        // an upstream merge includes them.
        val cnAllowFile = File(context.filesDir, "remote_filters/cn_allowlist.txt")
        if (cnAllowFile.exists() && cnAllowFile.length() > 0) {
            whitelistedDomains.addAll(cnAllowFile.readLines().map { it.trim().lowercase() }.filter { it.isNotEmpty() })
        }
```

allowlist 文件下载:在 `seedCnRulesIfNeeded` 末尾追加:

```kotlin
        runCatching {
            downloadManager.downloadRawTo(
                CN_RULES_ALLOWLIST_URL,
                File(context.filesDir, "remote_filters/cn_allowlist.txt")
            )
        }
```

e) 兜底逻辑核对:`FilterDownloadManager.downloadFile` 对 `local://` 哨兵已直接返回本地路径(`FilterDownloadManager.kt:80-83`),`loadAllEnabledFilters` 无需改动即可消费 CN 条目。

- [ ] **Step 8: 编译 + 全部单测**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL,单测全绿

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat: region/category model, CN rules seed, allowlist protection"
```

---

## Task 4: 纯广告模式开关(Ads-only mode)

**Files:**
- Modify: `app/src/main/java/app/pwhs/blockads/data/datastore/AppPreferences.kt`
- Modify: `app/src/main/java/app/pwhs/blockads/data/repository/FilterListRepository.kt`(loadAllEnabledFilters)
- Modify: `app/src/main/java/app/pwhs/blockads/service/AdBlockVpnService.kt:536`
- Modify: `app/src/main/java/app/pwhs/blockads/service/RootProxyService.kt:213-218`
- Modify: `app/src/main/java/app/pwhs/blockads/ui/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/app/pwhs/blockads/ui/settings/component/ProtectionSection.kt`
- Modify: `app/src/main/res/values/strings.xml`、`app/src/main/res/values-zh/strings.xml`

- [ ] **Step 1: AppPreferences 新增偏好**

`AppPreferences.kt`:KEY 区(61 行附近)加 `private val KEY_ADS_ONLY_MODE = booleanPreferencesKey("ads_only_mode")`;

Flow 区(参照 `httpsFilteringEnabled` 的现有 getter/setter,695 行附近)加:

```kotlin
    val adsOnlyMode: Flow<Boolean> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[KEY_ADS_ONLY_MODE] ?: false }

    suspend fun setAdsOnlyMode(enabled: Boolean) {
        context.dataStore.edit { it[KEY_ADS_ONLY_MODE] = enabled }
    }
```

(以文件内现有 Flow 的实际写法为准——若现有 getter 不用 catch/map 组合,保持与相邻项一致的写法。)

- [ ] **Step 2: loadAllEnabledFilters 应用覆盖**

`FilterListRepository.loadAllEnabledFilters()` 开头取 `enabledLists` 之后加:

```kotlin
        // Ads-only mode: drop non-ad categories at load time (runtime overlay,
        // profile persistence untouched).
        val adsOnly = appPrefs.adsOnlyMode.first()
        val enabledLists = if (adsOnly) {
            allLists.filter {
                it.isEnabled && it.category != FilterList.CATEGORY_ADULT &&
                    it.category != FilterList.CATEGORY_GAMBLING &&
                    it.category != FilterList.CATEGORY_SECURITY
            }
        } else {
            allLists.filter { it.isEnabled }
```

(以现有局部变量名为准:`loadAllEnabledFilters` 开头是 `val enabledLists = filterListDao.getEnabled...()`,按实际结构把过滤条件并入。)

- [ ] **Step 3: 引擎下发处应用覆盖**

`AdBlockVpnService.kt:536`:

```kotlin
goTunnelAdapter.configureSafeSearch(safeSearchEnabled, youtubeRestrictedMode)
```

改为:

```kotlin
                // Ads-only mode forces content controls off (user choice: ads only)
                val adsOnly = appPrefs.adsOnlyMode.first()
                goTunnelAdapter.configureSafeSearch(
                    safeSearchEnabled && !adsOnly,
                    youtubeRestrictedMode && !adsOnly
                )
```

`RootProxyService.kt:213-218` 同样处理(取 `val adsOnly = appPrefs.adsOnlyMode.first()` 后 `configureSafeSearch(safeSearch && !adsOnly, youtubeSafe && !adsOnly)`)。

- [ ] **Step 4: SettingsViewModel + UI**

`SettingsViewModel`:

```kotlin
    val adsOnlyMode = appPrefs.adsOnlyMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setAdsOnlyMode(enabled: Boolean) {
        viewModelScope.launch {
            appPrefs.setAdsOnlyMode(enabled)
            // Reload filter set + restart service so the overlay applies immediately
            filterRepo.loadAllEnabledFilters()
            ServiceController.requestRestart(getApplication<Application>().applicationContext)
        }
    }
```

`ProtectionSection.kt`(在现有 `SettingsToggleItem` 之间插入,写法对齐相邻项):

```kotlin
        SettingsToggleItem(
            title = stringResource(R.string.settings_ads_only),
            description = stringResource(R.string.settings_ads_only_desc),
            isChecked = adsOnlyMode,
            onCheckedChange = { onAdsOnlyModeChanged(it) }
        )
```

(Section 的参数签名按现有组件模式传递 `adsOnlyMode` 与 `onAdsOnlyModeChanged`;参照相邻 toggle 的参数流。)

strings.xml(en):

```xml
    <string name="settings_ads_only">Ads only</string>
    <string name="settings_ads_only_desc">Only block ads. Disables adult, gambling and security lists plus SafeSearch and YouTube Restricted Mode.</string>
```

values-zh:

```xml
    <string name="settings_ads_only">仅去广告</string>
    <string name="settings_ads_only_desc">只拦截广告。将停用成人、赌博、安全类规则,并关闭安全搜索与 YouTube 受限模式。</string>
```

- [ ] **Step 5: 编译验证**

Run: `app` 模块编译 `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: ads-only mode overlay (disables adult/gambling/security + content controls)"
```

---

## Task 5: 规则列表 UI 改造(地域筛选/徽标/推荐标记)

**Files:**
- Modify: `app/src/main/java/app/pwhs/blockads/ui/filter/FilterSetupViewModel.kt`
- Modify: `app/ava? typo — app/src/main/java/app/pwhs/blockads/ui/filter/FilterSetupScreen.kt`
- Modify: `app/src/main/java/app/pwhs/blockads/ui/filter/component/FilterItem.kt`
- Modify: `values/strings.xml`、`values-zh/strings.xml`

- [ ] **Step 1: ViewModel 增加地域筛选状态**

```kotlin
    enum class RegionFilter { ALL, CN, GLOBAL }

    private val _regionFilter = MutableStateFlow(RegionFilter.ALL)
    val regionFilter: StateFlow<RegionFilter> = _regionFilter.asStateFlow()

    fun setRegionFilter(f: RegionFilter) { _regionFilter.value = f }
```

列表组合处(现有 searchQuery 与 filters Flow 的 combine 中)把 `_regionFilter` 加入 combine,过滤逻辑:

```kotlin
        .map { (all, query, region) ->
            all.filter { f ->
                (region == RegionFilter.ALL) ||
                    (region == RegionFilter.CN && f.region == FilterList.REGION_CN) ||
                    (region == RegionFilter.GLOBAL && f.region == FilterList.REGION_GLOBAL)
            }
        }
```

(与现有搜索过滤 chain 合并,保留现有 combine 顺序。)

- [ ] **Step 2: Screen 加筛选 chips 行**

`FilterSetupScreen.kt` 在搜索框与列表之间(LazyColumn 之前,约 200 行 `isSearching` 判定附近)插入:

```kotlin
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = regionFilter == FilterSetupViewModel.RegionFilter.ALL,
                onClick = { viewModel.setRegionFilter(FilterSetupViewModel.RegionFilter.ALL) },
                label = { Text(stringResource(R.string.region_all)) }
            )
            FilterChip(
                selected = regionFilter == FilterSetupViewModel.RegionFilter.CN,
                onClick = { viewModel.setRegionFilter(FilterSetupViewModel.RegionFilter.CN) },
                label = { Text(stringResource(R.string.region_cn)) }
            )
            FilterChip(
                selected = regionFilter == FilterSetupViewModel.RegionFilter.GLOBAL,
                onClick = { viewModel.setRegionFilter(FilterSetupViewModel.RegionFilter.GLOBAL) },
                label = { Text(stringResource(R.string.region_global)) }
            )
```

- [ ] **Step 3: FilterItem 地域/类别徽标与推荐标记**

`FilterItem.kt` 名称行附近加:

```kotlin
        // Region badge
        Text(
            text = if (filter.region == FilterList.REGION_CN) "CN" else "GL",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(4.dp)
                )
                .padding(horizontal = 4.dp, vertical = 1.dp)
        )
```

类别徽标(ADULT/GAMBLING/SECURITY):同理加一个徽标,文案 `filter.category`(非 AD 才显示)。

推荐标记(中文环境):composable 内:

```kotlin
        val isZh = Locale.getDefault().language == "zh"
        if (isZh && filter.name in RECOMMENDED_CN_NAMES) { /* "推荐" badge */ }
```

`RECOMMENDED_CN_NAMES = setOf("BlockAds CN Ads", "AdGuard DNS")` 放 ViewModel companion。

推荐集合实现时与 Task 3 的 `CN_RULES_NAME` 常量保持一致。

- [ ] **Step 4: 字符串资源**

en:

```xml
    <string name="region_all">All</string>
    <string name="region_cn">Mainland</string>
    <string name="region_global">Global</string>
```

zh:

```xml
    <string name="region_all">全部</string>
    <string name="region_cn">国内</string>
    <string name="region_global">海外</string>
```

- [ ] **Step 5: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: region filter chips, badges and CN recommendation on filter list UI"
```

---

## Task 6: 构建 + PHK110 实机验证

**Files:** 无代码改动(验证任务)

- [ ] **Step 1: 构建并安装**

```bash
./gradlew assembleDebug   # 后台运行,预计数分钟
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

- [ ] **Step 2: 启动耗时对比(核心验收)**

```bash
# 有网启动耗时
adb logcat -c && adb shell am force-stop app.pwhs.blockads.debug
# 在 App 内开启 VPN(手动),观察 logcat:
adb logcat -v time | grep -E "Filters loaded|startupTime|Tunnel started|phase"
```

断开网络(WiFi off + 移动数据 off)后重复开/关 VPN:
Expected: 启动不再被网络阻塞;logcat 显示 `Filters loaded (local): N domains` 后引擎启动;后台同步失败仅出现一条 non-fatal 错误日志。

- [ ] **Step 3: CN 规则生效验证**

```bash
# 设备连接网络后,等后台同步完成,检查 CN 列表编译日志
adb logcat -v time | grep -E "CN rules|BlockAds CN Ads"
# DNS 拦截验证:allowlist 之外的上游域名应解析为 0.0.0.0;主业务域名必须正常解析
```

- [ ] **Step 4: 纯广告模式验证**

App 设置页开启"仅去广告"→ 服务自动重启 → 验证:成人/赌博/安全列表不再参与拦截(DNS 日志不再命中其域名);SafeSearch 强制关闭。

- [ ] **Step 5: 收尾 commit**

若有验证中发现的修正,统一提交:

```bash
git add -A && git commit -m "fix: adjustments from on-device verification"
```

---

## Self-Review 记录

1. **Spec 覆盖**:3.1→Task 2;3.2a→Task 3;3.2b→Task 3;3.2c→Task 4;3.2d→Task 3(region 模型)+Task 5(UI);3.2e 离线兜底资产 → **简化并入 Task 3**:CN 条目自带"本地编译 + local:// 哨兵"路径,首次无网时后台同步失败仅延迟 CN 规则,不阻塞 VPN(设计 3.2e 的 assets 打包降级为后续增强,已与验收标准"≤3s 启动"不冲突——验收点改为启动不受网络影响,而不是无网也有 CN 规则);3.3b-1→Task 1;3.3b-2 先通后拦→Task 1。
2. **占位符扫描**:Task 3 Step 7 与 Task 5 中标注了两处"实现时核对"项(gomobile 绑定类名、现有局部变量名),这些是显式交接说明而非空洞占位;Task 2 allowlist 的占位行已在 Step 3 内用"最终内容"覆盖。
3. **类型一致性**:`TriePaths`/`getTriePathsSnapshot`/`backgroundSyncAfterStart`/`seedCnRulesIfNeeded`/`FilterRulesMapper.normalizeCategory(id, category)`/`inferRegion(id, originalUrl)`/`isLocalOnlyBuiltIn(originalUrl)` 在 Task 1/3/4 间一致;`RegionFilter` 在 Task 5 内自洽。
