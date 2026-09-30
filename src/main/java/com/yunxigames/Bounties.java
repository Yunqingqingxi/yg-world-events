package com.yunxigames;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.UUID;

/**
 * 猎杀悬赏（支线任务）：引导玩家去击杀特定生物。
 *
 * <p>流程：冷却结束后全服广播一条悬赏（「猎杀 8 只尸壳」），任何玩家击杀目标生物都计入进度；
 * 达成时击杀者获得宝藏奖励（走 {@link DropRandomizer#randomTreasure}），播报后进入随机冷却。
 * 进度常驻第二条 Boss 条 HUD（紫色），与全局事件 HUD 相互独立、可同屏堆叠。
 *
 * <p>目标从内置的「可猎杀池」（敌对为主 + 少量中立）随机抽取 —— 只挑常见、能找到的种类，
 * 不会抽到幻翼这种「看运气遇敌」的，也不会抽到 Boss。
 */
public final class Bounties {
	private Bounties() {
	}

	private static final RandomSource RANDOM = RandomSource.create();
	private static final int TICKS_PER_MINUTE = 20 * 60;

	/** 可猎杀目标池（id + 显示名）。刻意不放进神 / 幻翼 / 凋灵：找得到、打得着。 */
	private static final List<BountyTarget> TARGET_POOL = List.of(
			new BountyTarget("minecraft:zombie", "僵尸"),
			new BountyTarget("minecraft:husk", "尸壳"),
			new BountyTarget("minecraft:drowned", "溺尸"),
			new BountyTarget("minecraft:skeleton", "骷髅"),
			new BountyTarget("minecraft:stray", "流浪者"),
			new BountyTarget("minecraft:spider", "蜘蛛"),
			new BountyTarget("minecraft:cave_spider", "洞穴蜘蛛"),
			new BountyTarget("minecraft:creeper", "苦力怕"),
			new BountyTarget("minecraft:enderman", "末影人"),
			new BountyTarget("minecraft:witch", "女巫"),
			new BountyTarget("minecraft:pillager", "掠夺者"),
			new BountyTarget("minecraft:vindicator", "卫道士"),
			new BountyTarget("minecraft:slime", "史莱姆"),
			new BountyTarget("minecraft:magma_cube", "岩浆怪"),
			new BountyTarget("minecraft:blaze", "烈焰人"),
			new BountyTarget("minecraft:breeze", "旋风人"),
			new BountyTarget("minecraft:zombified_piglin", "僵尸猪灵")
	);

	private record BountyTarget(String id, String name) {
	}

	private static net.minecraft.server.level.ServerBossEvent bountyHud;

	private static BountyTarget currentTarget;
	private static int requiredKills;
	private static int progress;
	private static long nextBountyTick = -1;
	private static UUID lastRewardPlayer;

	/** 注册 tick 与击杀钩子。 */
	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(Bounties::tick);
		ServerLivingEntityEvents.AFTER_DEATH.register(Bounties::onDeath);
	}

	/** 关服清理。 */
	public static void reset() {
		if (bountyHud != null) {
			bountyHud.removeAllPlayers();
		}
		currentTarget = null;
		progress = 0;
		nextBountyTick = -1;
	}

	private static void tick(MinecraftServer server) {
		EventsConfig config = EventsConfig.get();
		if (!config.enableBounties) {
			return;
		}

		long now = server.getTickCount();
		if (nextBountyTick < 0) {
			// 首个悬赏：开局 3 分钟发布
			nextBountyTick = now + 3L * TICKS_PER_MINUTE;
		}

		// 无玩家时顺延（与全局事件同一套「冻结计时」策略）
		if (nextBountyTick > 0 && now >= nextBountyTick
				&& currentTarget == null) {
			if (server.getPlayerList().getPlayerCount() == 0) {
				nextBountyTick = now + 30L * 20L; // 没人就 30 秒后再看
			} else {
				publishNewBounty(server, config, now);
			}
		}

		updateHud(server, config);
	}

	/** 发布一条新悬赏。 */
	private static void publishNewBounty(MinecraftServer server, EventsConfig config, long now) {
		BountyTarget target = TARGET_POOL.get(RANDOM.nextInt(TARGET_POOL.size()));
		int min = Math.max(1, config.bountyMinKills);
		int max = Math.max(min, config.bountyMaxKills);

		currentTarget = target;
		requiredKills = min + RANDOM.nextInt(max - min + 1);
		progress = 0;

		server.getPlayerList().broadcastSystemMessage(Component.literal(
				"§d[猎杀悬赏] 全服悬赏：猎杀 " + requiredKills + " 只 §f" + target.name()
						+ "§d！完成者获得宝藏奖励！"), false);

		if (config.debugLog) {
			Yg.LOGGER.info("[yg-events] 悬赏发布：{} x{}", target.name(), requiredKills);
		}
	}

	private static void onDeath(LivingEntity entity, DamageSource source) {
		if (currentTarget == null || !(source.getEntity() instanceof ServerPlayer killer)
				|| entity instanceof Player) {
			return;
		}

		Identifier killedId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
		if (killedId == null || !currentTarget.id().equals(killedId.toString())) {
			return;
		}

		if (bumpProgress()) {
			completeBounty(killer);
		}
	}

	/** 击杀计入进度（自检可独立调用）。返回是否达成。 */
	static boolean bumpProgress() {
		if (currentTarget == null) {
			return false;
		}
		progress++;
		return progress >= requiredKills;
	}

	/** 达成结算：宝藏奖励 + 全服播报 + 随机冷却。 */
	private static void completeBounty(ServerPlayer killer) {
		MinecraftServer server = killer.level().getServer();
		EventsConfig config = EventsConfig.get();

		ServerLevel level = killer.level();
		int rewardCount = Math.max(0, config.bountyRewardCount);
		for (int i = 0; i < rewardCount; i++) {
			ItemStack reward = LootSupply.randomTreasure(level, level.getRandom());
			if (!reward.isEmpty()) {
				Block.popResource(level, killer.blockPosition(), reward);
			}
		}

		server.getPlayerList().broadcastSystemMessage(Component.literal(
				"§d[悬赏达成] §f" + killer.getName().getString() + "§d 完成了「猎杀 "
						+ requiredKills + " 只 " + currentTarget.name() + "」悬赏，"
						+ "获得 §e" + rewardCount + " 件宝藏§d！"), false);
		lastRewardPlayer = killer.getUUID();

		// 进入随机冷却
		long cooldownMinutes = config.bountyCooldownMinMinutes
				+ RANDOM.nextInt(Math.max(1, config.bountyCooldownMaxMinutes
						- config.bountyCooldownMinMinutes + 1));
		nextBountyTick = server.getTickCount() + cooldownMinutes * TICKS_PER_MINUTE;
		currentTarget = null;
		progress = 0;

		if (config.debugLog) {
			Yg.LOGGER.info("[yg-events] 悬赏达成，冷却 {} 分钟", cooldownMinutes);
		}
	}

	private static void updateHud(MinecraftServer server, EventsConfig config) {
		if (!config.bountyHudEnabled) {
			if (bountyHud != null) {
				bountyHud.setVisible(false);
			}
			return;
		}

		if (bountyHud == null) {
			bountyHud = new net.minecraft.server.level.ServerBossEvent(
					java.util.UUID.randomUUID(),
					Component.literal("猎杀悬赏"),
					net.minecraft.world.BossEvent.BossBarColor.PURPLE,
					net.minecraft.world.BossEvent.BossBarOverlay.NOTCHED_10);
		}

		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			bountyHud.addPlayer(p);
		}

		if (currentTarget == null) {
			bountyHud.setName(Component.literal("§d猎杀悬赏 §7| 下一条悬赏整理中…"));
			bountyHud.setProgress(0.0F);
		} else {
			bountyHud.setName(Component.literal("§d悬赏：猎杀 §f" + currentTarget.name()
					+ " §d" + progress + "/" + requiredKills));
			bountyHud.setProgress((float) progress / (float) Math.max(1, requiredKills));
		}
		bountyHud.setVisible(true);
	}

	// ------------------------------------------------------------ 自检与跨系统辅助

	/** 血月等系统共用：在指定位置刷一只「可猎杀池」里的敌对生物。 */
	static void spawnHostileNear(ServerLevel level, BlockPos pos) {
		BountyTarget target = TARGET_POOL.get(RANDOM.nextInt(TARGET_POOL.size()));
		EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(target.id()));
		if (type == null) {
			return;
		}
		type.spawn(level, pos, net.minecraft.world.entity.EntitySpawnReason.EVENT);
	}

	static BountyTarget currentTargetForTest() {
		return currentTarget;
	}

	static int currentProgressForTest() {
		return progress;
	}

	static int requiredKillsForTest() {
		return requiredKills;
	}

	static UUID lastRewardPlayerForTest() {
		return lastRewardPlayer;
	}

	/** 自检用：强制立即发布一条悬赏（绕过冷却与玩家检查）。 */
	static void forcePublishForTest(MinecraftServer server, EventsConfig config) {
		publishNewBounty(server, config, server.getTickCount());
	}

	/** 自检用：清空当前悬赏状态。 */
	static void clearForTest() {
		currentTarget = null;
		progress = 0;
	}
}
