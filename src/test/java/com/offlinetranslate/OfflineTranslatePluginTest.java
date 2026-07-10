package com.offlinetranslate;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Launches the real RuneLite client locally with OfflineTranslatePlugin registered, so it can
 * be tested without going through the Plugin Hub. Run this class's main method (e.g. "Run" in
 * your IDE); log in with your own account, then enable "Offline Translate" in the plugin list.
 */
public class OfflineTranslatePluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(OfflineTranslatePlugin.class);
		RuneLite.main(args);
	}
}
