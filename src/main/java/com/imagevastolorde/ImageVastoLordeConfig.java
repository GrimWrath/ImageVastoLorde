package com.imagevastolorde;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("ImageVastoLorde")
public interface ImageVastoLordeConfig extends Config
{
	@ConfigItem(
			keyName = "minWidth",
			name = "Min Width",
			description = "Minimum image width in pixels.",
			position = 1
	)
	default int minWidth()
	{
		return 100;
	}

	@ConfigItem(
			keyName = "minHeight",
			name = "Min Height",
			description = "Minimum image height in pixels.",
			position = 2
	)
	default int minHeight()
	{
		return 100;
	}

	@ConfigItem(
			keyName = "transparentBackground",
			name = "Transparent Background",
			description = "Use a transparent background instead of the RuneLite overlay background color for PNGs." ,
			position = 3
	)
	default boolean transparentBackground() { return false; }

	@ConfigItem(
			keyName = "overlayMode",
			name = "Overlay Mode",
			description = "Controls which overlay layer the image is displayed on.",
			position = 4
	)
	default OverlayMode overlayMode() { return OverlayMode.Default; }
}
