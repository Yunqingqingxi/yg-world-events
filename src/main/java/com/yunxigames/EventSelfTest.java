package com.yunxigames;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.entity.EntityType;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 开服自检：把这一批功能逐条跑一遍，结论直接写进日志。
 *
 * <p>为什么要在<b>真服务器</b>上跑而不是写单元测试：这些功能全都要摸到
 * {@code ServerLevel}、实体生成、掉落路径和广播，纯 mock 测不出「真的能用」。
 * 所以自检挂在 {@code SERVER_STARTED} 上，拿真实的 {@code overworld} 当实验场。
 *
 * <p>触发方式：配置里把 {@code selfTestRolls} 设成大于 0 的数（比如 300），
 * 开服时就会跑一遍；跑完改回 0 即可关闭。也可以用 {@code /yg selftest} 随时手动跑。
 *
 * <p>自检期间 {@link SessionStats} 是暂停的 —— 几千次假掉落不该污染「本局战绩」。
 */

import static com.yunxigames.SelfTest.*;

public final class EventSelfTest {
	private EventSelfTest() {
	}

static void checkGlobalEvents(MinecraftServer server, ServerLevel level, EventsConfig config) {
		boolean savedEnabled = config.enableEvents;
		boolean savedHud = config.eventHudEnabled;
		boolean savedFrog = config.enableFrogRain;
		boolean savedMeteor = config.enableMeteorShower;

		config.enableEvents = true;
		config.eventHudEnabled = true;
		config.enableFrogRain = true;
		config.enableMeteorShower = true;

		boolean hudOk = false;
		boolean triggered = false;
		boolean nameOk = false;
		boolean frogOk = false;
		boolean meteorOk = true;
		try {
			ServerBossEvent hud = GlobalEvents.ensureHudForTest();
			hudOk = hud != null;

			config.enableMeteorShower = false; // 只测青蛙雨，避免名字混淆
			GlobalEvents.forceTriggerNoPlayerCheck(server, config);
			triggered = GlobalEvents.activeEventCount() > 0;

			GlobalEvents.updateHudForTest(server, config, server.getTickCount());
			nameOk = hud.getName().getString().contains("青蛙雨");

			// 用真实地表当原点：写死 y=90 的空中点既没加载区块、青蛙落地也没着落；
			// getHeightmapPos 会强制把该区块加载出来，getEntities 才数得到实体。
			BlockPos origin = level.getHeightmapPos(
					net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, new BlockPos(0, 64, 0));
			int before = countFrogs(level, origin, 40.0D);
			GlobalEvents.spawnFrogAt(level, origin.getX() + 0.5, origin.getY() + 3.0, origin.getZ() + 0.5);
			int after = countFrogs(level, origin, 40.0D);
			frogOk = after > before;

			// 真实陨石：需要 LivingEntity owner —— 用盔甲架充当，验证生成不崩且进入追踪
			ArmorStand meteorOwner = spawnArmorStand(level, origin);
			try {
				meteorOk = meteorOwner != null
						&& GlobalEvents.spawnMeteorEntity(level, meteorOwner,
								origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5, config);
			} catch (Throwable t) {
				meteorOk = false;
			} finally {
				if (meteorOwner != null) {
					meteorOwner.discard();
				}
				GlobalEvents.processMeteorsForTest(config);
			}
		} finally {
			config.enableEvents = savedEnabled;
			config.eventHudEnabled = savedHud;
			config.enableFrogRain = savedFrog;
			config.enableMeteorShower = savedMeteor;
			GlobalEvents.reset();
		}

		check("⑳ 全局事件·青蛙雨/陨石+HUD",
				hudOk && triggered && nameOk && frogOk && meteorOk,
				"HUD创建=" + hudOk + " 触发=" + triggered + " HUD含「青蛙雨」=" + nameOk
						+ " 青蛙生成=" + frogOk + " 陨石不崩=" + meteorOk);
	}

	
static void checkEventsFix(MinecraftServer server, ServerLevel level, EventsConfig config) {
		boolean savedEnabled = config.enableEvents;
		boolean savedHud = config.eventHudEnabled;
		config.enableEvents = true;
		config.eventHudEnabled = true;
		config.enableFrogRain = true;
		config.enableMeteorShower = true;

		boolean hudOk = false;
		boolean dualShown = false;
		try {
			GlobalEvents.forceTriggerNoPlayerCheck(server, config); // 双事件场景一次到位
			GlobalEvents.updateHudForTest(server, config, server.getTickCount());

			ServerBossEvent hud = GlobalEvents.ensureHudForTest();
			hudOk = hud != null;
			String name = hud == null ? "" : hud.getName().getString();
			dualShown = name.contains("青蛙雨") && name.contains("天降陨石") && name.contains("+");
		} finally {
			config.enableEvents = savedEnabled;
			config.eventHudEnabled = savedHud;
			GlobalEvents.reset();
		}

		check("㉕ 全局事件修复·双事件 HUD",
				hudOk && dualShown,
				"HUD创建=" + hudOk + " 双事件全显示（事件名 + 事件名）=" + dualShown);
	}

	
static void checkMeteorRealism(MinecraftServer server, ServerLevel level, EventsConfig config) {
		boolean savedResidue = config.meteorOreResidue;
		config.meteorOreResidue = true;
		// 先在落点铺一层石头平台：矿物是「从地表往下找第一个非空气非液体方块」替换的，
		// 若 (0,0) 正好是海洋/沙滩，水面往下几格全是水，永远放不下矿 —— 那是测试环境问题，
		// 不是功能失效。铺平之后这项自检在任何种子的新世界上都稳定。
		int platY = level.getHeight(
				net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, 0, 0);
		for (int px = -3; px <= 3; px++) {
			for (int pz = -3; pz <= 3; pz++) {
				level.setBlock(new BlockPos(px, platY, pz),
						net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 2);
			}
		}
		try {
			ArmorStand owner = spawnArmorStand(level, new BlockPos(0, platY + 5, 0));
			boolean spawned = owner != null
					&& GlobalEvents.spawnMeteorEntity(level, owner, 0.5, 90.0, 0.5, config);
			boolean tracked = spawned && GlobalEvents.trackedMeteorCountForTest() == 1;
			if (owner != null) {
				owner.discard();
			}

			// 模拟陨石撞地爆炸：直接移除实体，驱动矿物残留结算
			if (tracked) {
				// 范围覆盖整段空域：陨石的实际生成高度取决于地表高度 + meteorSpawnHeight，
				// 写死 y 区间会漏掉它，导致后面的矿物残留结算不被触发。
				for (var e : level.getEntities((Entity) null,
						new AABB(-8, 40, -8, 8, 320, 8), x -> true)) {
					e.discard();
				}
			}
			GlobalEvents.processMeteorsForTest(config);

			// 落点附近应出现矿物（爆炸坑内嵌矿）
			boolean oreFound = false;
			// 与 GlobalEvents 的放置逻辑对齐：每个方块各自取自己的地表高度，
			// 否则地形起伏时（新世界尤其明显）会扫不到矿，误判成功能失效。
			for (int dx = -2; dx <= 2 && !oreFound; dx++) {
				for (int dz = -2; dz <= 2 && !oreFound; dz++) {
					int surfaceY = level.getHeight(
							net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, dx, dz);
					for (int dy = 0; dy < 6 && !oreFound; dy++) {
						var st = level.getBlockState(new BlockPos(dx, surfaceY - 1 - dy, dz));
						Identifier bid = BuiltInRegistries.BLOCK.getKey(st.getBlock());
						oreFound = bid != null && bid.toString().endsWith("_ore");
					}
				}
			}

			check("㉘ 陨石真实化·天降实体+矿物残留",
					spawned && tracked && oreFound,
					"真实实体生成=" + spawned + " 进入追踪=" + tracked
							+ " 爆炸后残留矿物=" + oreFound);
		} finally {
			config.meteorOreResidue = savedResidue;
		}
	}

	
static void checkBounty(MinecraftServer server, ServerLevel level, EventsConfig config) {
		Bounties.clearForTest();
		Bounties.forcePublishForTest(server, config);

		boolean published = Bounties.currentTargetForTest() != null
				&& Bounties.requiredKillsForTest() > 0;

		boolean ladder = false;
		boolean done = false;
		if (published) {
			int need = Bounties.requiredKillsForTest();
			boolean early = false;
			for (int i = 0; i < need - 1; i++) {
				early = Bounties.bumpProgress();
				if (early) {
					break; // 未满就达成 = 有 bug
				}
			}
			ladder = !early && Bounties.currentProgressForTest() == need - 1;
			// bumpProgress 只负责进度判定（奖励与清空走 onDeath→completeBounty 路径）
			done = Bounties.bumpProgress();
		}
		Bounties.clearForTest();

		check("㉚ 猎杀悬赏·发布+进度+达成",
				published && ladder && done,
				"发布=" + published + " 进度累积=" + ladder + " 达成判定=" + done);
	}

	

	
static void checkNewEvents(MinecraftServer server, ServerLevel level, EventsConfig config) {
		boolean savedEnabled = config.enableEvents;
		config.enableEvents = true;

		boolean ran = true;
		String hudName = "";
		try {
			GlobalEvents.forceRunEventForTest(GlobalEvents.EventType.THUNDER_POOL, server, config);
			GlobalEvents.forceRunEventForTest(GlobalEvents.EventType.BLOOD_MOON, server, config);
			GlobalEvents.forceRunEventForTest(GlobalEvents.EventType.FORTUNE_RAIN, server, config);

			// 各分支推进一个结算步（雷池落雷 / 血月刷怪 / 福到掉物），不抛异常即通过
			GlobalEvents.runEventsOnceForTest(server, config);

			GlobalEvents.updateHudForTest(server, config, server.getTickCount());
			hudName = GlobalEvents.ensureHudForTest().getName().getString();
		} catch (Throwable t) {
			ran = false;
		} finally {
			config.enableEvents = savedEnabled;
			GlobalEvents.reset();
		}

		boolean hudOk = hudName.contains("雷池") && hudName.contains("血月") && hudName.contains("福到");

		check("㉜ 新事件·雷池/血月/福到",
				ran && hudOk,
				"三事件结算不崩=" + ran + " HUD 全显示=" + hudOk);
	}

	
	/** 自检辅助：统计半径内的青蛙数量（青蛙雨自检用）。 */
	public static int countFrogs(ServerLevel level, BlockPos center, double r) {
		AABB box = new AABB(center.getX() - r, center.getY() - r, center.getZ() - r,
				center.getX() + r, center.getY() + r, center.getZ() + r);
		return level.getEntities((Entity) null, box, e -> e instanceof Frog).size();
	}


}
