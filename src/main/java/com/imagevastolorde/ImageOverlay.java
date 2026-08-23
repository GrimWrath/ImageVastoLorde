package com.imagevastolorde;

import lombok.extern.slf4j.Slf4j;

import net.runelite.client.RuneLite;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.ImageComponent;
import net.runelite.client.util.ImageUtil;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import javax.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class ImageOverlay extends OverlayPanel
{
	private static final File[] CUSTOM_IMAGE_FILES = {
			new File(RuneLite.RUNELITE_DIR, "profile.gif"),
			new File(RuneLite.RUNELITE_DIR, "profile.png"),
			new File(RuneLite.RUNELITE_DIR, "profile.png.png")
	};

	private final ImageVastoLordeConfig config;
	private final BufferedImage errorImage;

	private BufferedImage customImage;

	private ImageComponent imageComponent;
	private BufferedImage displayedImage;

	private List<BufferedImage> gifFrames;
	private List<Integer> gifFrameEndTimes;
	private long gifTotalDuration;
	private long gifStartTime;

	private boolean animated;
	private boolean reloadRequested = true;

	private File loadedFile;
	private long loadedFileLastModified = -1L;

	private int lastFrameIndex = -1;

	@Inject
	private ImageOverlay(ImageVastoLordePlugin plugin, ImageVastoLordeConfig config)
	{
		super(plugin);

		this.config = config;

		setPriority(PRIORITY_LOW);
		setPosition(OverlayPosition.TOP_LEFT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setClearChildren(false);

		errorImage = loadErrorImage();

		applyConfig();
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (reloadRequested || imageFileChanged())
		{
			loadImage();

			// Force ImageComponent to be rebuilt after a reload.
			imageComponent = null;
			displayedImage = null;
		}

		BufferedImage currentImage = getCurrentImage();

		if (currentImage == null)
		{
			currentImage = errorImage;
		}

		/*
		 * ImageComponent stores its image as a final field,
		 * so a new component is only needed when the actual
		 * displayed image changes.
		 */
		if (imageComponent == null || currentImage != displayedImage)
		{
			imageComponent = new ImageComponent(currentImage);
			displayedImage = currentImage;

			panelComponent.getChildren().clear();
			panelComponent.getChildren().add(imageComponent);
		}

		return super.render(graphics);
	}

	public void onConfigChanged()
	{
		reloadRequested = true;

		applyConfig();

		/*
		 * Restart animation when configuration changes.
		 */
		gifStartTime = System.nanoTime();
		lastFrameIndex = -1;
	}

	private void applyConfig()
	{
		if (config.transparentBackground())
		{
			panelComponent.setBackgroundColor(new Color(0, 0, 0, 0));
		}
		else
		{
			panelComponent.setBackgroundColor(
					net.runelite.client.ui.overlay.components.ComponentConstants.STANDARD_BACKGROUND_COLOR
			);
		}

		switch (config.overlayMode())
		{
			case Default:
				setLayer(OverlayLayer.ABOVE_WIDGETS);
				break;

			case BehindInterface:
				setLayer(OverlayLayer.ABOVE_SCENE);
				break;

			case AlwaysOnTop:
				setLayer(OverlayLayer.ALWAYS_ON_TOP);
				break;
		}
	}

	/**
	 * Checks whether the image on disk has changed.
	 */
	private boolean imageFileChanged()
	{
		File currentFile = findExistingFile();

		if (currentFile == null)
		{
			return loadedFile != null;
		}

		if (loadedFile == null)
		{
			return true;
		}

		return !currentFile.equals(loadedFile)
				|| currentFile.lastModified() != loadedFileLastModified;
	}

	/**
	 * Finds the first available image.
	 *
	 * GIF has priority over PNG.
	 */
	private File findExistingFile()
	{
		for (File file : CUSTOM_IMAGE_FILES)
		{
			if (file.exists() && file.isFile())
			{
				return file;
			}
		}

		return null;
	}

	/**
	 * Loads either a static image or an animated GIF.
	 *
	 * This method is only called when the image actually needs to be loaded.
	 */
	private void loadImage()
	{
		reloadRequested = false;

		File file = findExistingFile();

		if (file == null)
		{
			customImage = null;
			gifFrames = null;
			gifFrameEndTimes = null;
			gifTotalDuration = 0;
			animated = false;

			loadedFile = null;
			loadedFileLastModified = -1L;

			return;
		}

		try
		{
			if (file.getName().toLowerCase().endsWith(".gif"))
			{
				loadGif(file);
			}
			else
			{
				loadStaticImage(file);
			}

			loadedFile = file;
			loadedFileLastModified = file.lastModified();

			gifStartTime = System.nanoTime();
			lastFrameIndex = -1;
		}
		catch (Exception e)
		{
			log.error("Failed to load image: {}", file.getAbsolutePath(), e);

			customImage = null;
			gifFrames = null;
			gifFrameEndTimes = null;
			gifTotalDuration = 0;
			animated = false;

			loadedFile = file;
			loadedFileLastModified = file.lastModified();
		}
	}

	private void loadStaticImage(File file) throws IOException
	{
		BufferedImage image = ImageIO.read(file);

		if (image == null)
		{
			throw new IOException("Invalid image: " + file);
		}

		customImage = resizeImage(image);

		gifFrames = null;
		gifFrameEndTimes = null;
		gifTotalDuration = 0;
		animated = false;
	}

	private static class GifFrame
	{
		BufferedImage image;
		int x;
		int y;
		int width;
		int height;
		int delay;
		int disposalMethod;

	}

	/**
	 * Decode an entire GIF once.
	 */
	private void loadGif(File gifFile) throws IOException
	{
		List<GifFrame> decodedFrames = new ArrayList<>();

		int canvasWidth;
		int canvasHeight;

		try (ImageInputStream stream = ImageIO.createImageInputStream(gifFile))
		{
			if (stream == null)
			{
				throw new IOException("Could not open GIF: " + gifFile);
			}

			ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();

			try
			{
				reader.setInput(stream, false, false);

				canvasWidth = reader.getWidth(0);
				canvasHeight = reader.getHeight(0);

				int frameCount = reader.getNumImages(true);

				for (int i = 0; i < frameCount; i++)
				{
					BufferedImage rawFrame;

					try
					{
						/*
						 * Decode this frame.
						 *
						 * Some GIFs can cause Java's GIF reader to throw
						 * an ArrayIndexOutOfBoundsException. Don't let one
						 * bad frame destroy the entire overlay.
						 */
						rawFrame = reader.read(i);
					}
					catch (RuntimeException e)
					{
						log.warn("Could not decode GIF frame {} from {}", i, gifFile, e);
						continue;
					}

					if (rawFrame == null)
					{
						continue;
					}

					/*
					 * Force the decoded frame into a normal ARGB image.
					 *
					 * This prevents unusual GIF color/alpha models from
					 * leaking into the compositing code.
					 */
					BufferedImage safeFrame = new BufferedImage(
							rawFrame.getWidth(),
							rawFrame.getHeight(),
							BufferedImage.TYPE_INT_ARGB
					);

					Graphics2D frameGraphics = safeFrame.createGraphics();

					frameGraphics.setComposite(
							java.awt.AlphaComposite.Src
					);

					frameGraphics.drawImage(
							rawFrame,
							0,
							0,
							null
					);

					frameGraphics.dispose();

					GifFrame frame = new GifFrame();
					frame.image = safeFrame;

					readGifFrameMetadata(reader, i, frame);

					decodedFrames.add(frame);
				}
			}
			finally
			{
				reader.dispose();
			}
		}

		if (decodedFrames.isEmpty())
		{
			throw new IOException("GIF contains no readable frames");
		}

		/*
		 * Persistent GIF canvas.
		 */
		BufferedImage canvas = new BufferedImage(
				canvasWidth,
				canvasHeight,
				BufferedImage.TYPE_INT_ARGB
		);

		Graphics2D canvasGraphics = canvas.createGraphics();

		List<BufferedImage> finalFrames = new ArrayList<>();
		List<Integer> frameEndTimes = new ArrayList<>();

		int totalDuration = 0;

		for (GifFrame frame : decodedFrames)
		{
			BufferedImage previousCanvas = null;

			if (frame.disposalMethod == 3)
			{
				previousCanvas = copyImage(canvas);
			}

			/*
			 * Draw the frame over the existing canvas.
			 */
			canvasGraphics.setComposite(
					java.awt.AlphaComposite.SrcOver
			);

			canvasGraphics.drawImage(
					frame.image,
					frame.x,
					frame.y,
					null
			);

			/*
			 * Capture the complete composited frame.
			 */
			finalFrames.add(resizeImage(canvas));

			int delay = Math.max(frame.delay, 1);

			totalDuration += delay;
			frameEndTimes.add(totalDuration);

			/*
			 * Apply disposal AFTER capturing the frame.
			 */
			switch (frame.disposalMethod)
			{
				case 2:
					/*
					 * Restore the frame area to transparent.
					 */
					canvasGraphics.setComposite(
							java.awt.AlphaComposite.Clear
					);

					canvasGraphics.fillRect(
							frame.x,
							frame.y,
							frame.width,
							frame.height
					);

					canvasGraphics.setComposite(
							java.awt.AlphaComposite.SrcOver
					);
					break;

				case 3:
					/*
					 * Restore previous canvas.
					 */
					if (previousCanvas != null)
					{
						canvasGraphics.setComposite(
								java.awt.AlphaComposite.Src
						);

						canvasGraphics.drawImage(
								previousCanvas,
								0,
								0,
								null
						);

						canvasGraphics.setComposite(
								java.awt.AlphaComposite.SrcOver
						);
					}
					break;

				default:
					/*
					 * Disposal 0 / 1:
					 * leave the frame on the canvas.
					 */
					break;
			}
		}

		canvasGraphics.dispose();

		customImage = finalFrames.get(0);

		gifFrames = finalFrames;
		gifFrameEndTimes = frameEndTimes;

		gifTotalDuration =
				frameEndTimes.get(frameEndTimes.size() - 1);

		animated = finalFrames.size() > 1;
	}

	private BufferedImage copyImage(BufferedImage source)
	{
		BufferedImage copy = new BufferedImage(
				source.getWidth(),
				source.getHeight(),
				BufferedImage.TYPE_INT_ARGB
		);

		Graphics2D graphics = copy.createGraphics();

		graphics.drawImage(
				source,
				0,
				0,
				null
		);

		graphics.dispose();

		return copy;
	}

	private void readGifFrameMetadata(
			ImageReader reader,
			int frameIndex,
			GifFrame frame) throws IOException
	{
		IIOMetadata metadata =
				reader.getImageMetadata(frameIndex);

		IIOMetadataNode root =
				(IIOMetadataNode) metadata.getAsTree(
						"javax_imageio_gif_image_1.0"
				);

		/*
		 * Graphic Control Extension
		 */
		IIOMetadataNode gce =
				(IIOMetadataNode) root
						.getElementsByTagName(
								"GraphicControlExtension"
						)
						.item(0);

		frame.delay = 100;
		frame.disposalMethod = 0;


		if (gce != null)
		{
			String delayString =
					gce.getAttribute("delayTime");

			if (delayString != null && !delayString.isEmpty())
			{
				try
				{
					frame.delay =
							Integer.parseInt(delayString) * 10;
				}
				catch (NumberFormatException ignored)
				{
					frame.delay = 100;
				}
			}

			if (frame.delay < 10)
			{
				frame.delay = 100;
			}

			String disposal =
					gce.getAttribute("disposalMethod");

			frame.disposalMethod =
					parseDisposalMethod(disposal);
		}

		/*
		 * Image Descriptor
		 */
		IIOMetadataNode descriptor =
				(IIOMetadataNode) root
						.getElementsByTagName(
								"ImageDescriptor"
						)
						.item(0);

		if (descriptor != null)
		{
			frame.x = parseIntAttribute(
					descriptor,
					"imageLeftPosition",
					0
			);

			frame.y = parseIntAttribute(
					descriptor,
					"imageTopPosition",
					0
			);

			frame.width = parseIntAttribute(
					descriptor,
					"imageWidth",
					frame.image.getWidth()
			);

			frame.height = parseIntAttribute(
					descriptor,
					"imageHeight",
					frame.image.getHeight()
			);
		}
		else
		{
			frame.x = 0;
			frame.y = 0;
			frame.width = frame.image.getWidth();
			frame.height = frame.image.getHeight();
		}
	}

	private int parseDisposalMethod(String disposal)
	{
		if (disposal == null)
		{
			return 0;
		}

		switch (disposal)
		{
			case "none":
				return 1;

			case "doNotDispose":
				return 1;

			case "restoreToBackgroundColor":
				return 2;

			case "restoreToPrevious":
				return 3;

			default:
				return 0;
		}
	}

	private int parseIntAttribute(
			IIOMetadataNode node,
			String attribute,
			int defaultValue)
	{
		String value = node.getAttribute(attribute);

		if (value == null || value.isEmpty())
		{
			return defaultValue;
		}

		try
		{
			return Integer.parseInt(value);
		}
		catch (NumberFormatException e)
		{
			return defaultValue;
		}
	}


	/**
	 * Determines which GIF frame should currently be displayed.
	 */
	private BufferedImage getCurrentImage()
	{
		if (!animated || gifFrames == null || gifFrames.isEmpty())
		{
			return customImage;
		}

		long elapsedMillis =
				(System.nanoTime() - gifStartTime) / 1_000_000L;

		long animationTime =
				gifTotalDuration > 0
						? elapsedMillis % gifTotalDuration
						: 0;

		/*
		 * If the current frame is still valid, reuse it.
		 *
		 * This avoids searching the frame list on every render.
		 */
		if (lastFrameIndex >= 0
				&& animationTime < gifFrameEndTimes.get(lastFrameIndex)
				&& (lastFrameIndex == 0
				|| animationTime >= gifFrameEndTimes.get(lastFrameIndex - 1)))
		{
			return gifFrames.get(lastFrameIndex);
		}

		/*
		 * The animation crossed a frame boundary.
		 * Find the new frame.
		 */
		lastFrameIndex = findFrame(animationTime);

		return gifFrames.get(lastFrameIndex);
	}

	private int findFrame(long animationTime)
	{
		int low = 0;
		int high = gifFrameEndTimes.size() - 1;

		while (low < high)
		{
			int mid = (low + high) >>> 1;

			if (animationTime < gifFrameEndTimes.get(mid))
			{
				high = mid;
			}
			else
			{
				low = mid + 1;
			}
		}

		return low;
	}

	private BufferedImage loadErrorImage()
	{
		return ImageUtil.loadImageResource(
				ImageVastoLordePlugin.class,
				"/no_image.png"
		);
	}

	private BufferedImage resizeImage(BufferedImage image)
	{
		if (image == null)
		{
			return null;
		}

		return ImageUtil.resizeImage(
				image,
				config.minWidth(),
				config.minHeight(),
				true
		);
	}
}