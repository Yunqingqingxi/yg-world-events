# yg-events — 事件 / 悬赏

| | |
| --- | --- |
| **jar** | `yg-events-1.15.0.jar` |
| **mod id** | `yg_events` |
| **配置文件** | `config/yg-events.json` |
| **自检项** | ⑳ ㉕ ㉘ ㉚ ㉜（本包自己的编号，装本包才跑） |
| **环境** | 只在服务端做判定，玩家用原版客户端可直连 |

定时随机全局事件（青蛙雨 / 天降陨石 / 雷池 / 血月 / 福到）+ 猎杀悬赏 + 常驻 Boss 条 HUD。

---

## 一、全局事件

每 `eventIntervalMinutes`（默认 **10 分钟**）掷一次骰：允许空过，`eventChance`（默认 0.6）
决定这次有没有事件，`eventCritChance`（默认 0.15）触发**双事件**。

| 事件 | 内容 |
| --- | --- |
| **青蛙雨** | 在玩家附近持续刷青蛙 |
| **天降陨石** | 玩家附近落下巨型火球 |
| **雷池** | 局部雷暴 —— 随机在**暴露的**玩家头顶周围持续落雷，躲进屋里就安全 |
| **血月** | 持续在暴露玩家附近涌出敌对生物 —— 危险与掉落并存 |
| **福到** | 纯福利 —— 天上掉随机物品（礼盒式的掉落雨） |

### 陨石是「真实」的

v1.14 起陨石不再是「玩家附近直接爆炸」，而是**从天而降的实体火球**：

- 从 `meteorSpawnHeight`（默认 28 格）高处生成，以 `meteorFallSpeed` 下落；
- **撞山 / 落地才炸** —— 躲在洞里可以规避（`canSeeSkyFromBelowWater` 判定）；
- 保留 `meteorOreResidue`（爆炸残留矿物坑）；
- 雷击只劈「头顶露天」的目标。

### 事件轮空 bug（v1.14 已修）

修法是：专用随机源 + 无玩家时冻结计时 + tick 跳变对齐 + 掷骰日志。
如果你想确认修好了，看日志里每次掷骰的记录即可。

## 二、猎杀悬赏（Bounties）

第二条 HUD：发布一个「杀满 N 只」的目标（默认 5~12 只），杀满就发宝藏
（`bountyRewardCount` 默认 3 件）。完成后进入冷却（默认 5~12 分钟）再发下一条。

## 三、Boss 条 HUD

用 `ServerBossEvent` 常驻显示事件状态或下次事件倒计时；猎杀悬赏占用第二条。

> ⚠️ Boss 条和打龙 / 凋灵等原版 Boss 条挤在同一屏幕位置，
> 同屏出现多个 Boss 条时由客户端自行堆叠。不想要就 `eventHudEnabled` / `bountyHudEnabled`。

## 游戏内命令（`/yg events`）

需要管理员权限（权限等级 2）。`/yg events` 查看状态；`/yg events off` 同时停掉
全局事件与猎杀悬赏两条线（进行中的事件自然结束，各子事件开关保持不变），`on` 恢复。

## 四、配置（`config/yg-events.json`）

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `enableEvents` | `true` | 全局事件总开关 |
| `eventIntervalMinutes` | `10` | 事件掷骰间隔（分钟） |
| `eventChance` | `0.6` | 这次有没有事件的概率 |
| `eventCritChance` | `0.15` | 双事件（暴击）概率 |
| `frogRainDurationSeconds` | `60` | 青蛙雨持续（秒） |
| `meteorDurationSeconds` | `60` | 陨石雨持续（秒） |
| `frogRainIntervalTicks` | `40` | 青蛙雨刷怪间隔（刻） |
| `frogRainPerPlayer` | `2` | 每次每个玩家刷几只 |
| `frogRainRadius` | `8.0` | 青蛙雨半径 |
| `meteorIntervalTicks` | `50` | 陨石生成间隔（刻） |
| `meteorRadius` | `8.0` | 陨石散布半径 |
| `meteorExplosionRadius` | `2.0` | 爆炸威力 |
| `meteorFire` | `true` | 是否留火 |
| `enableFrogRain` | `true` | 青蛙雨开关 |
| `enableMeteorShower` | `true` | 陨石开关 |
| `eventHudEnabled` | `true` | 事件 HUD 开关 |
| `meteorSpawnHeight` | `28.0` | 陨石生成高度（格） |
| `meteorFallSpeed` | `0.55` | 陨石下落速度 |
| `meteorOreResidue` | `true` | 爆炸残留矿物 |
| `enableThunderPool` | `true` | 雷池开关 |
| `thunderPoolDurationSeconds` | `60` | 雷池持续（秒） |
| `thunderPoolIntervalTicks` | `40` | 落雷间隔（刻） |
| `thunderPoolRadius` | `10.0` | 雷池半径 |
| `enableBloodMoon` | `true` | 血月开关 |
| `bloodMoonDurationSeconds` | `120` | 血月持续（秒） |
| `bloodMoonIntervalTicks` | `300` | 血月强化间隔（刻） |
| `enableFortuneRain` | `true` | 福到开关 |
| `fortuneRainDurationSeconds` | `60` | 福到持续（秒） |
| `fortuneRainIntervalTicks` | `100` | 福到发放间隔（刻） |
| `enableBounties` | `true` | 猎杀悬赏总开关 |
| `bountyMinKills` / `bountyMaxKills` | `5` / `12` | 悬赏目标击杀数范围 |
| `bountyRewardCount` | `3` | 达成奖励件数 |
| `bountyCooldownMinMinutes` / `MaxMinutes` | `5` / `12` | 悬赏冷却范围（分钟） |
| `bountyHudEnabled` | `true` | 悬赏 HUD 开关 |
| `debugLog` | `false` | 调试日志（基类字段） |
| `selfTestRolls` | `0` | 开服自检掷骰次数 |

## 五、自检

`selfTestRolls > 0` 时开服跑这 5 项：

| 编号 | 检查内容 |
| --- | --- |
| ⑳ | 全局事件·青蛙雨 / 陨石 + HUD（HUD 能创建、强制触发后事件入列、青蛙真实生成、陨石爆炸不崩服） |
| ㉕ | 全局事件修复·双事件 HUD |
| ㉘ | 陨石真实化·天降实体 + 矿物残留 |
| ㉚ | 猎杀悬赏·发布 + 进度 + 达成 |
| ㉜ | 新事件·雷池 / 血月 / 福到 |

> **编号是包内局部的**：装了多个包时可能出现重复编号，按步骤名读日志即可。

## 六、已知限制

- **全局事件不做持久化**：重启后计时清零、进行中的事件直接消失。
- **陨石是真实爆炸，会炸毁地形**。建筑党不想要就 `enableMeteorShower: false`
  或整个 `enableEvents: false`。
- Boss 条 HUD 会与其它 Boss 条（打龙 / 凋灵）挤在同一屏幕位置，由客户端自行堆叠。

---

## 相关链接

- 系列总览与公共开发规范：[yunxigames](https://github.com/Yunqingqingxi/yunxigames)
- 归档（1.15.0 之前的历史）：[random-drops](https://github.com/Yunqingqingxi/random-drops)
