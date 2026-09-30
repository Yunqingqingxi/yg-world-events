package com.yunxigames;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * yg-events 单元测试：validate() 钳制的纯逻辑验证。
 * 特别覆盖 min/max 钳制链的 NaN 盲区与悬赏区间的边界联动。
 */
class EventsUnitTest {

	@Test
	void nanRadiiFallBackToCodeDefault() {
		EventsConfig cfg = new EventsConfig();
		cfg.frogRainRadius = Double.NaN;
		cfg.meteorRadius = Double.NaN;
		cfg.meteorExplosionRadius = Float.NaN;
		cfg.validate();
		assertEquals(8.0D, cfg.frogRainRadius);
		assertEquals(8.0D, cfg.meteorRadius);
		assertEquals(2.0F, cfg.meteorExplosionRadius);
	}

	@Test
	void nanProbabilityFallsToZero() {
		EventsConfig cfg = new EventsConfig();
		cfg.eventChance = Double.NaN;
		cfg.validate();
		assertEquals(0.0D, cfg.eventChance, "概率类字段 NaN 落下界 0（等效关闭）");
	}

	@Test
	void intervalAndBountyBoundsAreClamped() {
		EventsConfig cfg = new EventsConfig();
		cfg.eventIntervalMinutes = 0;
		cfg.bountyMinKills = 0;
		cfg.bountyMaxKills = 1;
		cfg.bountyRewardCount = -3;
		cfg.validate();
		assertEquals(10, cfg.eventIntervalMinutes);
		assertEquals(5, cfg.bountyMinKills);
		assertEquals(12, cfg.bountyMaxKills, "bountyMaxKills 低于 min 时抬到有效下限");
		assertEquals(3, cfg.bountyRewardCount, "负数奖励数回落默认 3");
	}

	@Test
	void legalValuesPassThroughUnchanged() {
		EventsConfig cfg = new EventsConfig();
		cfg.eventChance = 0.37D;
		cfg.meteorRadius = 20.0D;
		cfg.validate();
		assertEquals(0.37D, cfg.eventChance, "合法值必须原样保留");
		assertEquals(20.0D, cfg.meteorRadius);
	}
}
