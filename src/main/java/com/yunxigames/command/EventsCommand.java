package com.yunxigames.command;

import com.mojang.brigadier.CommandDispatcher;
import com.yunxigames.EventsConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.server.permissions.Permissions;

/**
 * {@code /yg events ...}：世界事件玩法（全局事件 + 猎杀悬赏）的游戏内启停与状态。
 *
 * <p>各玩法包统一往 {@code /yg} 根下挂以玩法名命名的子树（Brigadier 会把各包注册的
 * 同名根节点合并成一棵命令树），与 drops 包的 {@code /yg drops} 同一布局。
 * off 同时关闭事件系统与悬赏两条线（各自实时读配置，立即生效，进行中的事件自然结束）。
 */
public final class EventsCommand {
	/** 与 drops 包同一权限档（等价旧「权限等级 2」，OP 可用）。 */
	private static final PermissionCheck PERMISSION = new PermissionCheck.Require(Permissions.COMMANDS_GAMEMASTER);

	private EventsCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("yg")
				.requires(Commands.hasPermission(PERMISSION))
				.then(Commands.literal("events")
						.executes(context -> status(context.getSource()))
						.then(Commands.literal("on")
								.executes(context -> toggle(context.getSource(), true)))
						.then(Commands.literal("off")
								.executes(context -> toggle(context.getSource(), false)))));
	}

	private static int toggle(CommandSourceStack source, boolean enabled) {
		EventsConfig config = EventsConfig.get();
		config.enableEvents = enabled;
		config.enableBounties = enabled;
		config.save();
		source.sendSuccess(() -> Component.literal("[yg] 世界事件玩法整体：" + (enabled ? "开启" : "关闭")
				+ "（全局事件 + 猎杀悬赏；各事件的子开关保持不变）"), false);
		return status(source);
	}

	private static int status(CommandSourceStack source) {
		EventsConfig config = EventsConfig.get();
		source.sendSuccess(() -> Component.literal(String.format(
				"[yg] 世界事件玩法=%s | 事件系统=%s 悬赏=%s 掷骰间隔=%d分钟 触发率=%.2f 暴击(双事件)=%.2f",
				config.enableEvents || config.enableBounties ? "开" : "关",
				config.enableEvents ? "开" : "关",
				config.enableBounties ? "开" : "关",
				config.eventIntervalMinutes,
				config.eventChance,
				config.eventCritChance)), false);
		return 1;
	}
}
