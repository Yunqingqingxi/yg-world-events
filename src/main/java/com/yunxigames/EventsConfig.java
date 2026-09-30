package com.yunxigames;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 事件（全局事件 / 猎杀悬赏）（yunxigames events 包）的独立配置。
 *
 * <p>文件位置：{@code <游戏目录>/config/yg-events.json}。字段全部是 public，Gson 直接读写；
 * 缺少的字段会保留默认值，所以升级后旧配置文件依然可用。每包配置相互独立。
 */
public final class EventsConfig extends YgConfig {
	public static final String FILE_NAME = "yg-events.json";

	// --------------------------------------------- 事件系统（总开关）

	/** <b>全局事件系统总开关</b>（默认 true）。 */
	public boolean enableEvents = true;

	/** 每隔多少分钟掷一次骰子决定是否触发事件（默认 10）。 */
	public int eventIntervalMinutes = 10;

	/**
	 * 这次掷骰子<b>触发事件</b>的概率（默认 0.6）。
	 *
	 * <p>剩下的 40% 就是「什么都没发生」——保留空白，让玩家有「虚惊一场」的喘息。
	 */
	public double eventChance = 0.6D;

	/** 触发后<b>暴击（双事件）</b>的概率（默认 0.15）。 */
	public double eventCritChance = 0.15D;

	/** 青蛙雨持续多少秒（默认 60）。 */
	public int frogRainDurationSeconds = 60;

	/** 天降陨石持续多少秒（默认 60）。 */
	public int meteorDurationSeconds = 60;

	/** 青蛙雨：每个在线玩家上方，每隔多少刻掉一批青蛙（默认 40 = 2 秒）。 */
	public int frogRainIntervalTicks = 40;

	/** 青蛙雨：每个玩家每次掉几只青蛙（默认 2）。 */
	public int frogRainPerPlayer = 2;

	/** 青蛙雨：以玩家为中心的水平半径（格，默认 8）。 */
	public double frogRainRadius = 8.0D;

	/** 天降陨石：每隔多少刻在随机玩家附近砸一个陨石（默认 30 = 1.5 秒）。 */
	public int meteorIntervalTicks = 50;

	/** 天降陨石：以玩家为中心的水平半径（格，默认 12）。 */
	public double meteorRadius = 8.0D;

	/** 天降陨石：陨石爆炸半径（格，默认 3）。 */
	public float meteorExplosionRadius = 2.0F;

	/** 天降陨石是否在落点点燃火焰（默认 true）。 */
	public boolean meteorFire = true;

	/** 是否启用「青蛙雨」事件（默认 true）。 */
	public boolean enableFrogRain = true;

	/** 是否启用「天降陨石」事件（默认 true）。 */
	public boolean enableMeteorShower = true;

	/** 持久 HUD 面板（Boss 血条样式的世界状态条）是否启用（默认 true）。 */
	public boolean eventHudEnabled = true;

	/** 天降陨石：陨石从玩家头顶多高处开始下坠（格，默认 28）。 */
	public double meteorSpawnHeight = 28.0D;

	/** 天降陨石：下坠加速度（越大砸得越快，默认 0.55 —— 给 2~3 秒的预警时间）。 */
	public double meteorFallSpeed = 0.55D;

	/** 天降陨石：爆炸后掉落矿物簇的概率与规模 —— 爆炸点残留 2~4 块矿物（默认 true）。 */
	public boolean meteorOreResidue = true;

	// ---------- v1.14 新事件（雷池 / 血月 / 福到） ----------

	/**
	 * <b>雷池</b>（v1.14 新事件）：局部雷暴 —— 随机暴露玩家头顶周围持续落雷。
	 * 躲进屋里 / 山洞（头顶不直通天空）就完全安全。
	 */
	public boolean enableThunderPool = true;

	/** 雷池持续秒数（默认 60）。 */
	public int thunderPoolDurationSeconds = 60;

	/** 雷池落雷间隔（刻，默认 40 = 2 秒一波；每波每玩家 1~2 道雷）。 */
	public int thunderPoolIntervalTicks = 40;

	/** 雷池散布半径（格，默认 10）。 */
	public double thunderPoolRadius = 10.0D;

	/**
	 * <b>血月</b>（v1.14 新事件）：持续在暴露玩家附近涌出敌对生物 —— 危险与掉落并存。
	 */
	public boolean enableBloodMoon = true;

	/** 血月持续秒数（默认 120）。 */
	public int bloodMoonDurationSeconds = 120;

	/** 血月刷怪间隔（刻，默认 300 = 15 秒一波；每波每玩家 2 只）。 */
	public int bloodMoonIntervalTicks = 300;

	/**
	 * <b>福到</b>（v1.14 新事件）：纯福利 —— 天上掉随机物品（礼盒式的掉落雨）。
	 */
	public boolean enableFortuneRain = true;

	/** 福到持续秒数（默认 60）。 */
	public int fortuneRainDurationSeconds = 60;

	/** 福到掉落间隔（刻，默认 100 = 5 秒一波）。 */
	public int fortuneRainIntervalTicks = 100;

	// ---------- 猎杀悬赏（支线任务） ----------

	/**
	 * <b>猎杀悬赏</b>（v1.14）：定期全服发布「猎杀 N 只某类生物」的支线任务，
	 * 玩家击杀计入进度，达成时击杀者获得宝藏奖励。用第二条 Boss 条 HUD 显示进度。
	 */
	public boolean enableBounties = true;

	/** 悬赏目标数量下限（默认 5）。 */
	public int bountyMinKills = 5;

	/** 悬赏目标数量上限（默认 12）。 */
	public int bountyMaxKills = 12;

	/** 悬赏达成时奖励的宝藏件数（默认 3）。 */
	public int bountyRewardCount = 3;

	/** 悬赏达成后的冷却下限（分钟，默认 5）。 */
	public int bountyCooldownMinMinutes = 5;

	/** 悬赏达成后的冷却上限（分钟，默认 12）。 */
	public int bountyCooldownMaxMinutes = 12;

	/** 悬赏 HUD 开关（与事件 HUD 相互独立，可同屏堆叠）。 */
	public boolean bountyHudEnabled = true;


	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Logger LOGGER = LoggerFactory.getLogger("yg-events.json");
	private static volatile EventsConfig instance;

	EventsConfig() {  // 包内可见：单元测试与 YgConfig 缺项补回需要 new 默认实例
	}

	/** 取当前配置；首次调用会从磁盘载入。 */
	public static EventsConfig get() {
		EventsConfig local = instance;
		if (local == null) {
			synchronized (EventsConfig.class) {
				local = instance;
				if (local == null) {
					local = load();
				}
			}
		}
		return local;
	}

	/** 从磁盘读取配置（文件缺失或损坏时回退到默认值），并把规范化后的结果写回。 */
	public static synchronized EventsConfig load() {
		Path path = configPath(FILE_NAME);
		EventsConfig loaded = null;
		com.google.gson.JsonObject raw = null;

		if (Files.isRegularFile(path)) {
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				// 先解析成 JsonObject 留底：merge 用它区分「json 里没写这一项」和「明确写了值」
				raw = GSON.fromJson(reader, com.google.gson.JsonObject.class);
				loaded = GSON.fromJson(raw, EventsConfig.class);
			} catch (IOException | JsonParseException e) {
				LOGGER.warn("[yg-events.json] 读取 {} 失败，改用默认配置：{}", path, e.toString());
			}
		}

		if (loaded == null) {
			loaded = new EventsConfig();
		} else {
			mergeMissingFields(loaded, raw, new EventsConfig());
		}

		loaded.validate();
		instance = loaded;
		loaded.save();
		return loaded;
	}

	/** 把当前配置写回磁盘。 */
	public synchronized void save() {
		Path path = configPath(FILE_NAME);
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			LOGGER.error("[yg-events.json] 写入 {} 失败：{}", path, e.toString());
		}
	}

	/** 修正越界 / 缺失的值，并解析各个 id 列表。 */
	void validate() {
		// min/max 钳制链对 NaN 会原样放行（Math.min/max 遇 NaN 返回 NaN），先回落默认值再钳
		frogRainRadius = orDefaultIfNaN(frogRainRadius, 8.0D);
		meteorRadius = orDefaultIfNaN(meteorRadius, 8.0D);
		meteorExplosionRadius = (float) orDefaultIfNaN(meteorExplosionRadius, 2.0D);

		if (eventIntervalMinutes < 1) eventIntervalMinutes = 10;
		if (!(eventChance >= 0.0D)) eventChance = 0.0D;
		if (eventChance > 1.0D) eventChance = 1.0D;
		if (!(eventCritChance >= 0.0D)) eventCritChance = 0.0D;
		if (eventCritChance > 1.0D) eventCritChance = 1.0D;
		frogRainDurationSeconds = Math.min(60 * 60, Math.max(1, frogRainDurationSeconds));
		meteorDurationSeconds = Math.min(60 * 60, Math.max(1, meteorDurationSeconds));
		frogRainIntervalTicks = Math.min(20 * 600, Math.max(1, frogRainIntervalTicks));
		frogRainPerPlayer = Math.min(16, Math.max(0, frogRainPerPlayer));
		frogRainRadius = Math.min(32.0D, Math.max(0.0D, frogRainRadius));
		meteorIntervalTicks = Math.min(20 * 600, Math.max(1, meteorIntervalTicks));
		meteorRadius = Math.min(64.0D, Math.max(0.0D, meteorRadius));
		meteorExplosionRadius = (float) Math.min(16.0D, Math.max(0.0D, meteorExplosionRadius));

		// 体验收敛（v1.14 反馈：范围/伤害太大，体验太差）：只压新装机的默认值，
		// 已有配置文件里写死的值保持不动（磁盘值优先），README 有迁移说明。
		meteorRadius = Math.min(64.0D, Math.max(0.0D, meteorRadius));
		meteorExplosionRadius = (float) Math.min(16.0D, Math.max(0.0D, meteorExplosionRadius));
		if (!(meteorSpawnHeight >= 8.0D)) meteorSpawnHeight = 28.0D;
		meteorSpawnHeight = Math.min(120.0D, meteorSpawnHeight);
		if (!(meteorFallSpeed >= 0.1D)) meteorFallSpeed = 0.55D;
		meteorFallSpeed = Math.min(3.0D, meteorFallSpeed);
		if (bountyMinKills < 1) bountyMinKills = 5;
		bountyMinKills = Math.min(100, bountyMinKills);
		if (bountyMaxKills < bountyMinKills) bountyMaxKills = Math.max(bountyMinKills, 12);
		bountyMaxKills = Math.min(200, bountyMaxKills);
		if (bountyRewardCount < 0) bountyRewardCount = 3;
		bountyRewardCount = Math.min(24, bountyRewardCount);
		if (bountyCooldownMinMinutes < 0) bountyCooldownMinMinutes = 5;
		if (bountyCooldownMaxMinutes < bountyCooldownMinMinutes) {
			bountyCooldownMaxMinutes = bountyCooldownMinMinutes;
		}
	}
}
