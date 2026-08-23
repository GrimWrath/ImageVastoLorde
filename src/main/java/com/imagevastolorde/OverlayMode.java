package com.imagevastolorde;

public enum OverlayMode
{
	Default("Default"),
	BehindInterface("Behind Interfaces"),
	AlwaysOnTop("Always On Top");

	private final String description;

	OverlayMode(String description)
	{
		this.description = description;
	}

	@Override
	public String toString()
	{
		return this.description;
	}
}
