package com.yunxigames;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 全局事件系统 + 持久 HUD。
 *
 * <p>每 {@code eventIntervalMinutes} 分钟尝试触发一次全局事件（允许「无事件」），
 * 有 {@code eventCritChance} 概率触发「暴击」——同时来两个事件。
 * 当前事件通过一条常驻的 {@link ServerBossEvent}（Boss 血条式 HUD）展示给所有在线玩家，
 * 没事件时显示「距离下次事件」倒计时。
 *
 * <p>事件：
 * <ul>
 *   <li><b>青蛙雨</b>：事件期间每隔若干刻，在每名玩家头顶附近刷出青蛙。</li>
 *   <li><b>天降陨石</b>：事件期间每隔若干刻，在每名玩家附近引爆一枚陨石（爆炸）。</li>
 * </ul>
 */
public final class GlobalEvents {
	private GlobalEvents() {
	}

	private static final int TICKS_PER_MINUTE = 20 * 60;

	private static ServerBossEvent hud;
	private static long nextEventTick = -1;
	private static final List<ScheduledEvent> active = new ArrayList<>();

	/**
	 * 事件系统专用的随机源。
	 *
	 * <p>修「后续事件全部轮空」的第一刀：不再用 {@code overworld().getRandom()} ——
	 * 那是全服共享的序列，被生物 AI / 刷怪等每刻成千上万次消费，概率虽然数学上仍然均匀，
	 * 但行为无法独立观测与复现。事件系统用自己的序列，掷骰结果只由本系统决定。
	 */
	private static final RandomSource RANDOM = RandomSource.create();

	/**
	 * 在飞的陨石追踪：陨石是真实下落的巨型火球（撞山/落地才爆炸，躲进山洞可规避），
	 * 爆炸后（实体被移除）在落点残留矿物簇 —— 破坏同时也是机会。
	 */
	private static final List<TrackedMeteor> trackedMeteors = new ArrayList<>();

	private record TrackedMeteor(net.minecraft.world.entity.Entity meteor, int targetX, int targetZ) {
	}

	/** 陨石爆炸后残留的矿物（按稀有度排列，随机取用）。 */
	private static final String[] METEOR_ORES = {
			"minecraft:iron_ore", "minecraft:deepslate_iron_ore", "minecraft:copper_ore",
			"minecraft:gold_ore", "minecraft:deepslate_gold_ore", "minecraft:diamond_ore"
	};

	enum EventType {
		FROG_RAIN, METEOR_SHOWER, THUNDER_POOL, BLOOD_MOON, FORTUNE_RAIN;

		/** HUD 展示名。 */
		String displayName() {
			return switch (this) {
				case FROG_RAIN -> "青蛙雨";
				case METEOR_SHOWER -> "天降陨石";
				case THUNDER_POOL -> "雷池";
				case BLOOD_MOON -> "血月";
				case FORTUNE_RAIN -> "福到";
			};
		}
	}

	private static final class ScheduledEvent {
		final EventType type;
		final long startTick;
		final long endTick;

		ScheduledEvent(EventType type, long startTick, long endTick) {
			this.type = type;
			this.startTick = startTick;
			this.endTick = endTick;
		}
	}

	/** 注册每刻 tick 驱动。 */
	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(GlobalEvents::tick);
	}

	/** 关服时清掉 HUD 与进行中的事件。 */
	public static void reset() {
		if (hud != null) {
			hud.removeAllPlayers();
		}
		active.clear();
		trackedMeteors.clear();
		nextEventTick = -1;
	}

	private static void tick(MinecraftServer server) {
		EventsConfig config = EventsConfig.get();
		if (!config.enableEvents) {
			return;
		}

		long now = server.getTickCount();
		long interval = Math.max(1, config.eventIntervalMinutes) * TICKS_PER_MINUTE;

		if (nextEventTick < 0) {
			nextEventTick = now + interval;
		}

		// 到期事件清除
		active.removeIf(e -> now >= e.endTick);

		// 陨石追踪：撞地爆炸后残留矿物簇
		processMeteors(config);

		// 到点触发（修「全部轮空」第二、三刀）：
		// ① 无玩家时把计时持续顺延（冻结）—— 有人时永远从完整的间隔起算，公平且可感；
		// ② tick 跳变（空载暂停快进 / 崩溃恢复）一次性错过超过一个整周期时对齐并跳过本轮，
		//    防止跳变后的连环触发或长期卡死。
		if (now >= nextEventTick) {
			if (server.getPlayerList().getPlayerCount() == 0) {
				nextEventTick = now + interval;
			} else if (now - nextEventTick > interval) {
				if (config.debugLog) {
					Yg.LOGGER.info("[yg-events] 事件计时对齐：now={} 与预期差距超过一个周期，本轮跳过", now);
				}
				nextEventTick = now + interval;
			} else {
				trigger(server, config, now);
				nextEventTick = now + interval;
			}
		}

		// 运行进行中的事件
		for (ScheduledEvent e : active) {
			runEvent(server, e, now, config);
		}

		updateHud(server, config, now);
	}

	private static void trigger(MinecraftServer server, EventsConfig config, long now) {
		if (server.getPlayerList().getPlayerCount() == 0) {
			return; // 没人在场不触发，避免空转
		}

		// 允许无事件 —— 掷骰全过程可观测（debugLog），「为什么轮空」从此有据可查
		double roll = RANDOM.nextDouble();
		if (config.debugLog) {
			Yg.LOGGER.info("[yg-events] 事件掷骰：掷出 {} / 需 < {}（玩家 {} 人）→ {}",
					String.format("%.3f", roll), config.eventChance,
					server.getPlayerList().getPlayerCount(),
					roll < config.eventChance ? "触发" : "轮空");
		}

		if (roll >= config.eventChance) {
			return;
		}

		List<EventType> pool = new ArrayList<>();
		if (config.enableFrogRain) {
			pool.add(EventType.FROG_RAIN);
		}
		if (config.enableMeteorShower) {
			pool.add(EventType.METEOR_SHOWER);
		}
		if (config.enableThunderPool) {
			pool.add(EventType.THUNDER_POOL);
		}
		if (config.enableBloodMoon) {
			pool.add(EventType.BLOOD_MOON);
		}
		if (config.enableFortuneRain) {
			pool.add(EventType.FORTUNE_RAIN);
		}
		if (pool.isEmpty()) {
			return;
		}

		boolean crit = RANDOM.nextDouble() < config.eventCritChance;
		if (crit && pool.size() >= 2) {
			// 暴击：随机抽两个不同的事件一起来
			List<EventType> shuffled = new ArrayList<>(pool);
			for (int i = shuffled.size() - 1; i > 0; i--) {
				int j = RANDOM.nextInt(i + 1);
				EventType tmp = shuffled.get(i);
				shuffled.set(i, shuffled.get(j));
				shuffled.set(j, tmp);
			}
			active.add(new ScheduledEvent(shuffled.get(0), now, now + durationOf(shuffled.get(0), config)));
			active.add(new ScheduledEvent(shuffled.get(1), now, now + durationOf(shuffled.get(1), config)));
		} else {
			EventType t = pool.get(RANDOM.nextInt(pool.size()));
			active.add(new ScheduledEvent(t, now, now + durationOf(t, config)));
		}
	}

	/** 各事件的持续时长（秒 × 20 刻）。 */
	private static long durationOf(EventType t, EventsConfig config) {
		return switch (t) {
			case FROG_RAIN -> config.frogRainDurationSeconds * 20L;
			case METEOR_SHOWER -> config.meteorDurationSeconds * 20L;
			case THUNDER_POOL -> config.thunderPoolDurationSeconds * 20L;
			case BLOOD_MOON -> config.bloodMoonDurationSeconds * 20L;
			case FORTUNE_RAIN -> config.fortuneRainDurationSeconds * 20L;
		};
	}

	private static void runEvent(MinecraftServer server, ScheduledEvent e, long now, EventsConfig config) {
		switch (e.type) {
			case FROG_RAIN -> runFrogRain(server, now, config);
			case METEOR_SHOWER -> runMeteorShower(server, now, config);
			case THUNDER_POOL -> runThunderPool(server, now, config);
			case BLOOD_MOON -> runBloodMoon(server, now, config);
			case FORTUNE_RAIN -> runFortuneRain(server, now, config);
		}
	}

	private static void runFrogRain(MinecraftServer server, long now, EventsConfig config) {
		if (now % Math.max(1, config.frogRainIntervalTicks) != 0) {
			return;
		}
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			ServerLevel lvl = p.level();
			for (int i = 0; i < config.frogRainPerPlayer; i++) {
				double dx = (lvl.getRandom().nextDouble() * 2.0D - 1.0D) * config.frogRainRadius;
				double dz = (lvl.getRandom().nextDouble() * 2.0D - 1.0D) * config.frogRainRadius;
				BlockPos pos = p.blockPosition().offset((int) Math.floor(dx), 6, (int) Math.floor(dz));
				spawnFrogAt(lvl, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
			}
		}
	}

	private static void runMeteorShower(MinecraftServer server, long now, EventsConfig config) {
		if (now % Math.max(1, config.meteorIntervalTicks) != 0) {
			return;
		}
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			// 体验修正：躲进山洞 / 室内（头顶不直通天空）的玩家本回合不落石 —— 陨石可以规避
			if (!exposedToSky(p)) {
				continue;
			}

			ServerLevel lvl = p.level();
			double dx = (lvl.getRandom().nextDouble() * 2.0D - 1.0D) * config.meteorRadius;
			double dz = (lvl.getRandom().nextDouble() * 2.0D - 1.0D) * config.meteorRadius;
			BlockPos pos = p.blockPosition().offset((int) Math.floor(dx), 0, (int) Math.floor(dz));
			spawnMeteorEntity(lvl, p, pos.getX() + 0.5, p.getY(), pos.getZ() + 0.5, config);
		}
	}

	/** 雷池：事件期间随机暴露玩家头顶周围持续落雷 —— 局部雷暴，躲进屋里就安全。 */
	private static void runThunderPool(MinecraftServer server, long now, EventsConfig config) {
		if (now % Math.max(1, config.thunderPoolIntervalTicks) != 0) {
			return;
		}
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!exposedToSky(p)) {
				continue;
			}
			ServerLevel lvl = p.level();
			int strikes = 1 + lvl.getRandom().nextInt(2);
			for (int i = 0; i < strikes; i++) {
				double dx = (lvl.getRandom().nextDouble() * 2.0D - 1.0D) * config.thunderPoolRadius;
				double dz = (lvl.getRandom().nextDouble() * 2.0D - 1.0D) * config.thunderPoolRadius;
				BlockPos pos = p.blockPosition().offset((int) Math.floor(dx), 0, (int) Math.floor(dz));
				if (lvl.canSeeSkyFromBelowWater(pos)) {
					strikeLightning(lvl, pos.getX() + 0.5, lvl.getHeight(
							net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
							pos.getX(), pos.getZ()), pos.getZ() + 0.5);
				}
			}
		}
	}

	/** 在指定坐标落下一道真实雷击（雷池事件用；不依赖 enchants 模块，逻辑内联）。 */
	private static void strikeLightning(ServerLevel level, double x, double y, double z) {
		EntityType<?> lbType = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:lightning_bolt"));
		if (!(lbType instanceof EntityType)) {
			return;
		}

		@SuppressWarnings("unchecked")
		EntityType<? extends LightningBolt> type = (EntityType<? extends LightningBolt>) lbType;
		LightningBolt bolt = new LightningBolt(type, level);
		bolt.setPos(x, y, z);
		bolt.setVisualOnly(false);
		level.addFreshEntity(bolt);
	}

	/** 血月：事件期间持续在暴露玩家附近涌出敌对生物 —— 危险与掉落并存。 */
	private static void runBloodMoon(MinecraftServer server, long now, EventsConfig config) {
		if (now % Math.max(1, config.bloodMoonIntervalTicks) != 0) {
			return;
		}
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			ServerLevel lvl = p.level();
			for (int i = 0; i < 2; i++) {
				double dx = (lvl.getRandom().nextDouble() * 2.0D - 1.0D) * 14.0D;
				double dz = (lvl.getRandom().nextDouble() * 2.0D - 1.0D) * 14.0D;
				Bounties.spawnHostileNear(lvl, p.blockPosition().offset(
						(int) Math.floor(dx), 4, (int) Math.floor(dz)));
			}
		}
	}

	/** 福到：纯福利 —— 天上掉随机物品（礼盒式的掉落雨）。 */
	private static void runFortuneRain(MinecraftServer server, long now, EventsConfig config) {
		if (now % Math.max(1, config.fortuneRainIntervalTicks) != 0) {
			return;
		}
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			ServerLevel lvl = p.level();
			ItemStack gift = LootSupply.randomItem(lvl, p.blockPosition(), lvl.getRandom());
			if (!gift.isEmpty()) {
				BlockPos pos = p.blockPosition().offset(
						lvl.getRandom().nextInt(7) - 3, 8, lvl.getRandom().nextInt(7) - 3);
				net.minecraft.world.entity.item.ItemEntity drop = new net.minecraft.world.entity.item.ItemEntity(
						lvl, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, gift);
				drop.setDefaultPickUpDelay();
				lvl.addFreshEntity(drop);
			}
		}
	}

	/** 玩家头顶是否直通天空（山洞 / 室内 = false，可以躲陨石和雷）。 */
	private static boolean exposedToSky(ServerPlayer p) {
		return p.level().canSeeSkyFromBelowWater(p.blockPosition());
	}

	/** 在指定坐标刷出一只青蛙（供事件与自检共用）。 */
	static void spawnFrogAt(ServerLevel level, double x, double y, double z) {
		EntityType<?> frogType = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:frog"));
		if (!(frogType instanceof EntityType)) {
			return;
		}

		@SuppressWarnings("unchecked")
		EntityType<? extends Animal> ft = (EntityType<? extends Animal>) frogType;
		Frog frog = new Frog(ft, level);
		frog.setPos(x, y, z);
		level.addFreshEntity(frog);
	}

	/** 在指定坐标引爆一枚陨石的自检兼容入口已由 {@link #spawnMeteorEntity} 取代（真实下落实体）。 */

	/**
	 * v1.14 体验重做：陨石是<b>真实从天而降</b>的巨型火球 —— 从落点上空
	 * {@code meteorSpawnHeight} 格处生成、带下坠加速度，撞到山体 / 地面才爆炸。
	 *
	 * <p>这意味着：玩家躲进山洞 / 室内时，陨石会砸在山顶上而不是穿墙炸到你；
	 * 配合落点选择只砸「头顶直通天空」的玩家，事件从「无处可逃」变成「看天行事」。
	 * 爆炸后落点残留矿物簇（{@link #processMeteors}）—— 破坏同时也是机会。
	 *
	 * @return 是否成功生成了真实陨石实体
	 */
	static boolean spawnMeteorEntity(ServerLevel level, net.minecraft.world.entity.LivingEntity owner,
			double x, double y, double z, EventsConfig config) {
		EntityType<?> fbType = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:fireball"));
		if (fbType == null || owner == null) {
			return false;
		}

		@SuppressWarnings("unchecked")
		EntityType<net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball> fb =
				(EntityType<net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball>) fbType;
		net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball meteor =
				new net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball(level, owner,
						new net.minecraft.world.phys.Vec3(0, -config.meteorFallSpeed, 0),
						(int) Math.max(1, config.meteorExplosionRadius));
		meteor.setPos(x, y + config.meteorSpawnHeight, z);
		meteor.setItem(new net.minecraft.world.item.ItemStack(
				net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(Identifier.parse("minecraft:fire_charge"))));
		if (!level.addFreshEntity(meteor)) {
			return false;
		}

		trackedMeteors.add(new TrackedMeteor(meteor, (int) Math.floor(x), (int) Math.floor(z)));
		return true;
	}

	/**
	 * 陨石追踪：实体被移除（撞地 / 撞山爆炸）后，在落点残留矿物簇。
	 *
	 * <p>矿物嵌进爆炸坑附近的地表/坑壁：从落点地表向下找非空气方块替换成矿物，
	 * 数量 2~4 块，铁/铜为主、金次之、钻石小概率 —— 一次「天灾」变成一次「采矿机会」。
	 */
	private static void processMeteors(EventsConfig config) {
		if (trackedMeteors.isEmpty()) {
			return;
		}

		trackedMeteors.removeIf(t -> {
			if (t.meteor() == null || !t.meteor().isRemoved()) {
				return false; // 还在飞
			}
			if (!(t.meteor().level() instanceof ServerLevel level) || !config.meteorOreResidue) {
				return true;
			}

			int count = 2 + RANDOM.nextInt(3);
			for (int i = 0; i < count; i++) {
				int ox = t.targetX() + RANDOM.nextInt(5) - 2;
				int oz = t.targetZ() + RANDOM.nextInt(5) - 2;
				int surfaceY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, ox, oz);

				// 从地表向下找第一个非空气方块换成矿物
				for (int dy = 0; dy < 5; dy++) {
					BlockPos p = new BlockPos(ox, surfaceY - 1 - dy, oz);
					var st = level.getBlockState(p);
					if (!st.isAir() && !st.liquid()) {
						String ore = METEOR_ORES[RANDOM.nextInt(METEOR_ORES.length)];
						var oreBlock = BuiltInRegistries.BLOCK.getValue(Identifier.parse(ore));
						if (oreBlock != null) {
							level.setBlock(p, oreBlock.defaultBlockState(), 2);
						}
						break;
					}
				}
			}
			return true;
		});
	}

	private static void updateHud(MinecraftServer server, EventsConfig config, long now) {
		if (!config.eventHudEnabled) {
			if (hud != null) {
				hud.setVisible(false);
			}
			return;
		}

		ensureHud();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			hud.addPlayer(p);
		}

		long intervalTicks = Math.max(1, config.eventIntervalMinutes) * TICKS_PER_MINUTE;

		if (active.isEmpty()) {
			long remain = Math.max(0, nextEventTick - now);
			int mins = (int) (remain / TICKS_PER_MINUTE);
			hud.setName(Component.literal("§e暂无事件 §7| 下次事件 " + mins + " 分"));
			hud.setProgress(intervalTicks > 0 ? (float) remain / (float) intervalTicks : 0.0F);
		} else {
			// 显示全部进行中的事件（暴击多事件不再只显示第一个）
			java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
			for (ScheduledEvent e : active) {
				names.add(e.type.displayName());
			}
			long remain = active.stream().mapToLong(e -> Math.max(0, e.endTick - now)).min().orElse(0);
			hud.setName(Component.literal("§c当前事件：" + String.join(" + ", names)
					+ " §7| 剩余 " + (remain / 20L) + "s"));
			ScheduledEvent first = active.get(0);
			long total = Math.max(1, first.endTick - first.startTick);
			hud.setProgress((float) remain / (float) total);
		}
		hud.setVisible(true);
	}

	private static ServerBossEvent ensureHud() {
		if (hud == null) {
			hud = new ServerBossEvent(UUID.randomUUID(),
					Component.literal("暂无事件"), BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.PROGRESS);
			hud.setVisible(true);
		}
		return hud;
	}

	// ------------------------------------------------------------ 自检辅助

	/** 自检用：强制运行指定事件（入列让 tick 自然驱动其分支，验证不崩）。 */
	static void forceRunEventForTest(EventType type, MinecraftServer server, EventsConfig config) {
		long now = server.getTickCount();
		active.add(new ScheduledEvent(type, now, now + durationOf(type, config)));
	}

	/** 自检用：手动推进所有事件的一个结算步（驱动各分支不崩）。 */
	static void runEventsOnceForTest(MinecraftServer server, EventsConfig config) {
		long now = server.getTickCount();
		for (ScheduledEvent e : active) {
			runEvent(server, e, now, config);
		}
	}

	/** 自检用：当前在飞陨石数。 */
	static int trackedMeteorCountForTest() {
		return trackedMeteors.size();
	}

	/** 自检用：手动驱动陨石矿物残留结算。 */
	static void processMeteorsForTest(EventsConfig config) {
		processMeteors(config);
	}

	/** 自检用：确保 HUD 已创建并返回。 */
	static ServerBossEvent ensureHudForTest() {
		return ensureHud();
	}

	/** 自检用：直接跑一次 HUD 更新逻辑。 */
	static void updateHudForTest(MinecraftServer server, EventsConfig config, long now) {
		updateHud(server, config, now);
	}

	/** 自检用：当前进行中的事件数。 */
	static int activeEventCount() {
		return active.size();
	}

	/** 自检用：绕过「需有玩家在场」的限制，强制加入一个事件。 */
	static void forceTriggerNoPlayerCheck(MinecraftServer server, EventsConfig config) {		List<EventType> pool = new ArrayList<>();
		if (config.enableFrogRain) {
			pool.add(EventType.FROG_RAIN);
		}
		if (config.enableMeteorShower) {
			pool.add(EventType.METEOR_SHOWER);
		}
		if (pool.isEmpty()) {
			return;
		}

		long now = server.getTickCount();

		// 模拟「暴击」：两个事件同时入列（单事件配置则只加现有的那个）
		if (pool.size() >= 2) {
			active.add(new ScheduledEvent(EventType.FROG_RAIN, now,
					now + config.frogRainDurationSeconds * 20L));
			active.add(new ScheduledEvent(EventType.METEOR_SHOWER, now,
					now + config.meteorDurationSeconds * 20L));
			return;
		}

		EventType t = pool.get(0);
		long dur = (t == EventType.FROG_RAIN
				? config.frogRainDurationSeconds
				: config.meteorDurationSeconds) * 20L;
		active.add(new ScheduledEvent(t, now, now + dur));
	}
}
