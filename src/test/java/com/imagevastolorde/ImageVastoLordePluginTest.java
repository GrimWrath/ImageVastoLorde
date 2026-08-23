package com.imagevastolorde;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class ImageVastoLordePluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(ImageVastoLordePlugin.class);
		RuneLite.main(args);
	}
}