package com.offlinetranslate;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(OfflineTranslateConfig.GROUP)
public interface OfflineTranslateConfig extends Config
{
	String GROUP = "offlinetranslate";

	@ConfigItem(
		keyName = "preferredLanguage",
		name = "Preferred language",
		description = "The language you speak. Incoming chat in a different language gets flagged; outgoing /t messages get translated out of this language.",
		position = 0
	)
	default Language preferredLanguage()
	{
		return Language.ENGLISH;
	}

	@ConfigItem(
		keyName = "autoDetect",
		name = "Auto-detect chat language",
		description = "Scan incoming chat messages and detect their language automatically.",
		position = 1
	)
	default boolean autoDetect()
	{
		return true;
	}

	@ConfigItem(
		keyName = "outputLanguage",
		name = "Output language",
		description = "The language your /t messages get translated into. When auto-detect is on, this updates automatically to match whoever last spoke a different language.",
		position = 2
	)
	default Language outputLanguage()
	{
		return Language.SPANISH;
	}

	@ConfigItem(
		keyName = "autoUpdateOutputLanguage",
		name = "Auto-update output language",
		description = "When auto-detect identifies a message in a new language, automatically switch the output language to match it.",
		position = 3
	)
	default boolean autoUpdateOutputLanguage()
	{
		return true;
	}

	@ConfigItem(
		keyName = "translateCommand",
		name = "Translate command",
		description = "Chat prefix that translates the rest of your typed message from your preferred language into the output language before sending. Must NOT start with '/' - OSRS's own client reserves a leading '/' to mean 'switch to a PM with this player name', so a '/'-prefixed command gets swallowed by that before this plugin ever sees it. Experimental - see README.",
		position = 4
	)
	default String translateCommand()
	{
		return "!t ";
	}

	@ConfigItem(
		keyName = "showFlagsInChat",
		name = "Show flags in chat",
		description = "Show a flag icon next to a player's name when their message is detected in a different language than your preferred one.",
		position = 5
	)
	default boolean showFlagsInChat()
	{
		return true;
	}

	@ConfigItem(
		keyName = "promptToDownloadMissingPacks",
		name = "Prompt to download missing packs",
		description = "When auto-detect finds a language you don't have a pack for, prompt to download it.",
		position = 6
	)
	default boolean promptToDownloadMissingPacks()
	{
		return true;
	}
}
