# AGENTS.md — 事件 / 悬赏（yg-world-events）开发规范

> 本包是 yunxigames 系列的玩法包之一。系列总览、公共约定与全系列踩坑速查见
> [yunxigames 文档仓库](https://github.com/Yunqingqingxi/yunxigames) 的 AGENTS.md（必读）。
> 本文件是本仓库开发者（人类与 AI）的入口，开工前通读。

## 1. 本包是什么

**事件 / 悬赏**：全局事件（青蛙雨 / 天降陨石 / 雷池 / 血月 / 福到）调度、猎杀悬赏、Boss 条 HUD。

- mod id：`yg_events`，jar：`yg-events-<版本>.jar`，配置：`config/yg-events.json`，入口 `YunxiGamesEvents`

### 类地图

| 类 | 职责 |
| --- | --- |
| `EventsConfig` | 本包全部配置项 + `validate()` 钳制 |
| `GlobalEvents` | 全局事件调度（五种事件的触发 / 持续 / 结束） |
| `Bounties` | 猎杀悬赏（目标抽取 / 进度跟踪 / 发奖） |
| `EventSelfTest` | 本包自检 |

### 三条设计底线 / 向后兼容承诺

1. 只在服务端做判定；2. 一局制、零持久化（事件 / 悬赏状态全在内存，重启即清零）；
3. 物品不凭空消失。
mod id / jar 名 / 配置文件名 / lang key 永不改；配置字段只增不删；删字段 / 改默认行为升 major；
语义化版本 + GitHub Release 附 jar。

## 2. 环境（硬性）

| 组件 | 版本 |
| --- | --- |
| Minecraft | 26.2 |
| Fabric Loader | 0.19.5 |
| Fabric API | 0.159.0+26.2 |
| **JDK** | **25**（本机 `D:\Java\jdk-25`，runServer/build 必须显式指定） |

一切 gradle 命令加 `--offline`。

## 3. 常用命令

```bash
./gradlew compileJava --offline            # 开发期每个功能写完就跑
./gradlew test --offline                   # 三层 JUnit 测试
./gradlew smokeTest --offline              # 只跑冒烟
JAVA_HOME='D:\Java\jdk-25' ./gradlew runServer --offline > selftest-<版本>.log 2>&1
JAVA_HOME='D:\Java\jdk-25' ./gradlew build --offline
```

- runServer 工作目录是本仓库自己的 `run/`（首次跑改 `run/eula.txt` 为 `eula=true`）；
- 自检前把 `run/config/yg-events.json` 的 `selfTestRolls` 改成 `200`，跑完**改回 `0`**；
- 自检完 runServer 不自退，手动结束 java 进程，否则 `run/` 被锁。

## 4. 代码规范

1. 一个功能一个类，类头 javadoc 写「是什么 + 为什么」；
2. 一切数值进本包 `EventsConfig`，带中文注释，每个功能独立开关（「爽但不劝退」）；
3. 新配置项必须在 `validate()` 钳制：`!(x >= lo && x <= hi)` 顺带治 NaN；
4. 面向 `ServerLevel` / `LivingEntity` 写逻辑，泛化签名（自检要在无玩家服务器复用）；
5. 中文注释 / 文案（§ 颜色码）/ lang 键值；
6. 26.2 API 不确定：**先查反混淆 jar，别猜**。

## 5. 测试节奏

- 三层 JUnit（Smoke / Unit / Regression）+ runServer 自检；批量开发期只跑 `compileJava`；
- **新增事件 / 悬赏功能必须同步新增自检项**并更新本包 README 的自检表；
- 配置测试基建：`YgConfig.configDirOverride`、`mergeMissingFields`（`raw.has` 判缺项补回）、
  `orDefaultIfNaN`；构造器与 `validate()` 包内可见是测试前提，别改回 private。

## 6. 本包专属坑（全系列公共坑见系列仓库 AGENTS §7）

- **Boss 条**：`ServerBossEvent` 在 `net.minecraft.server.level`（不在 world.level），Boss 条 HUD
  挂玩家进退事件加 / 减 `ServerPlayer`；
- **实体标签存在性**：`EntityType` 静态常量（`LIGHTNING_BOLT` 等）在 26.2 不存在，
  用 `BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:…"))`；
- **实体生成**：`EntityType.create(level, EntitySpawnReason.<原因>)`，别漏 SpawnReason 参数；
- **实体标签判断**（如亡灵 / 天气免疫）：`Registry.getTagOrEmpty(tag)` 遍历比 `holder.value()`；
- 悬赏目标抽取复用本包 `LootSupply`（动态扫 `BuiltInRegistries`，mod 生物自动进池）；
- 事件调度全部走服务器 tick + 内存状态，**不要写任何存档**（一局制底线）。
