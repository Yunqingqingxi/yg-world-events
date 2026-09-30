package com.yunxigames;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * yg-events 回归测试：钉死 Gson 缺项补回与「显式 false 不可被偷改」的历史坑。
 */
class EventsRegressionTest {

	@TempDir
	Path configDir;

	@BeforeEach
	void injectConfigDir() {
		YgConfig.configDirOverride = configDir;
	}

	@AfterEach
	void resetConfigDir() {
		YgConfig.configDirOverride = null;
	}

	@Test
	void missingBooleanFieldsFallBackToCodeDefaultTrue() throws Exception {
		Files.writeString(configDir.resolve(EventsConfig.FILE_NAME),
				"{\"enableEvents\": true}");
		EventsConfig cfg = EventsConfig.load();
		assertTrue(cfg.enableThunderPool, "老配置缺 enableThunderPool 必须补回默认 true");
		assertTrue(cfg.enableFortuneRain, "缺项布尔必须补回代码默认 true");
		assertTrue(cfg.eventHudEnabled, "HUD 开关缺项同样补回");
	}

	@Test
	void explicitFalseInJsonMustNotBeOverwritten() throws Exception {
		Files.writeString(configDir.resolve(EventsConfig.FILE_NAME),
				"{\"enableEvents\": true, \"enableFrogRain\": false}");
		EventsConfig cfg = EventsConfig.load();
		assertFalse(cfg.enableFrogRain, "玩家明确写 false 必须保持 false");
	}
}
