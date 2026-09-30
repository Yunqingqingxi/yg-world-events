package com.yunxigames;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 全局事件模块入口：青蛙雨 / 天降陨石 / 雷池 / 血月 / 福到，以及猎杀悬赏。（Bingo 已拆分为独立的 yg-bingo 包）
 *
 * <p>{@link GlobalEvents}、{@link Bounties} 各自在 {@code register()} 内部自注册
 * 每刻结算钩子，这里只负责触发注册与关服清理，并把自检步骤挂进统一流程。
 */
public class YunxiGamesEvents implements ModInitializer {
	public static final String MOD_ID = "yg_events";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		Bounties.register();
		GlobalEvents.register();

		// Bingo 已拆分为独立的 yg-bingo 包（YunxiGamesBingo）

		SelfTest.register(() -> EventsConfig.get().selfTestRolls);

		// 关服清掉悬赏 / Bingo / 全局事件的缓存（下次开服就是新的一局）
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			Bounties.reset();
			GlobalEvents.reset();
		});

		// 把事件相关的自检步骤挂进统一自检流程
		SelfTest.registerStep("⑳ 全局事件·青蛙雨/陨石+HUD",
				ctx -> EventSelfTest.checkGlobalEvents(ctx.server, ctx.level, EventsConfig.get()));
		SelfTest.registerStep("㉕ 全局事件修复·双事件 HUD",
				ctx -> EventSelfTest.checkEventsFix(ctx.server, ctx.level, EventsConfig.get()));
		SelfTest.registerStep("㉘ 陨石真实化·天降实体+矿物残留",
				ctx -> EventSelfTest.checkMeteorRealism(ctx.server, ctx.level, EventsConfig.get()));
		SelfTest.registerStep("㉚ 猎杀悬赏·发布+进度+达成",
				ctx -> EventSelfTest.checkBounty(ctx.server, ctx.level, EventsConfig.get()));
		SelfTest.registerStep("㉜ 新事件·雷池/血月/福到",
				ctx -> EventSelfTest.checkNewEvents(ctx.server, ctx.level, EventsConfig.get()));

		LOGGER.info("[yg-events] 事件模块已加载");
	}
}
