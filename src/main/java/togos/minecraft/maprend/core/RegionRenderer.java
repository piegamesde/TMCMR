package togos.minecraft.maprend.core;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import javax.imageio.ImageIO;
import org.joml.Vector2i;
import com.flowpowered.nbt.CompoundTag;
import togos.minecraft.maprend.core.color.BiomeColors;
import togos.minecraft.maprend.core.color.BiomeColors.Biome;
import togos.minecraft.maprend.core.color.BlockColors;
import togos.minecraft.maprend.core.color.BlockColors.Block;
import togos.minecraft.maprend.core.color.Color;
import togos.minecraft.maprend.core.io.ContentStore;
import togos.minecraft.maprend.core.io.RegionFile;

public class RegionRenderer {

	public static class Timer {

		public long	regionLoading;
		public long	preRendering;
		public long	postProcessing;
		public long	imageSaving;
		public long	total;

		public int	regionCount;
		public int	sectionCount;

		public String formatTime(String name, long millis) {
			return String.format("%20s: % 8d   % 8.2f   % 8.4f", name, millis, millis / (double) regionCount, millis / (double) sectionCount);
		}
	}

	class RenderThread extends Thread {

		public ArrayList<Region>	regions;
		public int					startIndex;
		public int					endIndex;
		public File					outputDir;
		public boolean				force;

		RenderThread() {

		}

		RenderThread(ArrayList<Region> regions, int startIndex, int endIndex, File outputDir, boolean force) throws IOException {
			this.regions = regions;
			this.startIndex = startIndex;
			this.endIndex = endIndex;
			this.outputDir = outputDir;
			this.force = force;
		}

		@Override
		public void run() {
			try {
				renderRegions(regions, startIndex, endIndex, outputDir, force);
			} catch (IOException e) {
				System.err.println("Error in threaded renderer!");
				e.printStackTrace(System.err);
			}
		}
	}

	public final Set<Integer>	defaultedBlockIds			= new HashSet<Integer>();
	public final Set<Integer>	defaultedBlockIdDataValues	= new HashSet<Integer>();
	public final Set<Integer>	defaultedBiomeIds			= new HashSet<Integer>();
	/** Color of 16 air blocks stacked */
	public int					air16Color;
	/**
	 * Alpha below which blocks are considered transparent for purposes of shading (i.e. blocks with alpha < this will not be shaded, but blocks below them will
	 * be)
	 */
	private int					shadeOpacityCutoff			= 0x20;
	public final BlockColors	blockColors;
	public final BiomeColors	biomeColors;

	public final RenderSettings	settings;

	public RegionRenderer(RenderSettings settings) throws IOException {
		this.settings = settings;

		blockColors = settings.colorMapFile == null ? BlockColors.loadDefault() : BlockColors.load(settings.colorMapFile);
		biomeColors = settings.biomeMapFile == null ? BiomeColors.loadDefault() : BiomeColors.load(settings.biomeMapFile);

		this.air16Color = Color.overlay(0, getColor(0, 0, 0), 16);
	}

	//// Color look-up ////

	protected void defaultedBlockColor(int blockId) {
		defaultedBlockIds.add(blockId);
	}

	protected void defaultedSubBlockColor(int blockId, int blockDatum) {
		defaultedBlockIdDataValues.add(blockId | blockDatum << 16);
	}

	protected void defaultedBiomeColor(int biomeId) {
		defaultedBiomeIds.add(biomeId);
	}

	protected int getColor(int blockId, int blockDatum, int biomeId) {
		assert blockId >= 0 && blockId < blockColors.blocks.length;
		assert blockDatum >= 0;

		int blockColor;
		int biomeInfluence;

		Block bc = blockColors.blocks[blockId];
		if (bc.hasSubColors.length > blockDatum && bc.hasSubColors[blockDatum]) {
			blockColor = bc.subColors[blockDatum];
			biomeInfluence = bc.subColorInfluences[blockDatum];
		} else {
			if (blockDatum != 0) {
				defaultedSubBlockColor(blockId, blockDatum);
			}
			blockColor = bc.baseColor;
			biomeInfluence = bc.baseInfluence;
		}
		if (bc.isDefault) {
			defaultedBlockColor(blockId);
		}

		Biome biome = biomeColors.getBiome(biomeId);
		int biomeColor = biome.getMultiplier(biomeInfluence);
		if (biome.isDefault)
			defaultedBiomeColor(biomeId);

		return Color.multiplySolid(blockColor, biomeColor);
	}

	//// Rendering ////

	public final Timer	timer	= new Timer();
	protected long		startTime;

	protected void resetInterval() {
		startTime = System.currentTimeMillis();
	}

	protected long getInterval() {
		return System.currentTimeMillis() - startTime;
	}

	public BufferedImage renderRegion(RegionFile rf) {
		resetInterval();
		int width = 512, depth = 512;

		int[] surfaceColor = new int[width * depth];
		short[] surfaceHeight = new short[width * depth];

		preRender(rf, surfaceColor, surfaceHeight);
		// Color.demultiplyAlpha(surfaceColor);
		shade(surfaceHeight, surfaceColor);

		BufferedImage bi = new BufferedImage(width, depth, BufferedImage.TYPE_INT_ARGB);

		for (int z = 0; z < depth; ++z) {
			bi.setRGB(0, z, width, 1, surfaceColor, width * z, width);
		}
		timer.postProcessing += getInterval();

		return bi;
	}

	/**
	 * Load color and height data from a region.
	 * 
	 * @param rf
	 * @param colors color data will be written here
	 * @param heights height data (height of top of topmost non-transparent block) will be written here
	 */
	protected void preRender(RegionFile rf, int[] colors, short[] heights) {
		int maxSectionCount = 16;
		short[][] sectionBlockIds = new short[maxSectionCount][16 * 16 * 16];
		byte[][] sectionBlockData = new byte[maxSectionCount][16 * 16 * 16];
		boolean[] usedSections = new boolean[maxSectionCount];
		byte[] biomeIds = new byte[16 * 16];

		for (int cz = 0; cz < 32; ++cz) {
			for (int cx = 0; cx < 32; ++cx) {
				resetInterval();
				try {
					CompoundTag levelTag = rf.loadChunk(new Vector2i(cx, cz));
					if (levelTag == null)
						continue;// Chunk does not exist
					RegionFile.loadChunkData(levelTag, maxSectionCount, sectionBlockIds, sectionBlockData, usedSections, biomeIds);
					timer.regionLoading += getInterval();

					for (int s = 0; s < maxSectionCount; ++s) {
						if (usedSections[s]) {
							++timer.sectionCount;
						}
					}

					resetInterval();
					for (int z = 0; z < 16; ++z) {
						for (int x = 0; x < 16; ++x) {
							int pixelColor = 0;
							short pixelHeight = 0;
							int biomeId = biomeIds[z * 16 + x] & 0xFF;

							for (int s = 0; s < maxSectionCount; ++s) {
								int absY = s * 16;

								if (absY >= settings.maxHeight)
									continue;
								if (absY + 16 <= settings.minHeight)
									continue;

								if (usedSections[s]) {
									short[] blockIds = sectionBlockIds[s];
									byte[] blockData = sectionBlockData[s];

									for (int idx = z * 16 + x, y = 0; y < 16; ++y, idx += 256, ++absY) {
										if (absY < settings.minHeight || absY >= settings.maxHeight)
											continue;

										final short blockId = blockIds[idx];
										final byte blockDatum = blockData[idx];
										int blockColor = getColor(blockId & 0xFFFF, blockDatum, biomeId);
										pixelColor = Color.overlay(pixelColor, blockColor);
										if (Color.alpha(blockColor) >= shadeOpacityCutoff) {
											pixelHeight = (short) absY;
										}
									}
								} else {
									if (settings.minHeight <= absY && settings.maxHeight >= absY + 16) {
										// Optimize the 16-blocks-of-air case:
										pixelColor = Color.overlay(pixelColor, air16Color);
									} else {
										// TODO: mix
									}
								}
							}

							final int dIdx = 512 * (cz * 16 + z) + 16 * cx + x;
							colors[dIdx] = pixelColor;
							heights[dIdx] = pixelHeight;
						}
					}
					timer.preRendering += getInterval();
				} catch (IOException e) {
					System.err.println("Error reading chunk from " + rf.getFile() + " at " + cx + "," + cz);
					e.printStackTrace();
				}
			}
		}
	}

	//// Handy color-manipulation functions ////

	/** Will make some pixels darker or brighter depending on the height differences on the map to create the illusion of depth. */
	protected void shade(short[] height, int[] color) {
		int width = 512, depth = 512;

		int idx = 0;
		for (int z = 0; z < depth; ++z) {
			for (int x = 0; x < width; ++x, ++idx) {

				if (color[idx] == 0)
					continue;

				// Gradient of height along x and z axis, between 0 and 255
				float dyx, dyz;
				if (x == 0)
					dyx = (height[idx + 1] - height[idx]) / 2;
				else if (x == width - 1)
					dyx = (height[idx] - height[idx - 1]) / 2;
				else
					dyx = (height[idx + 1] - height[idx - 1]);

				if (z == 0)
					dyz = (height[idx + width] - height[idx]) / 2;
				else if (z == depth - 1)
					dyz = (height[idx] - height[idx - width]) / 2;
				else
					dyz = (height[idx + width] - height[idx - width]);

				// Addd gradients (0..512), normalize (0..1) and mix depending on the balance factor with the altitute shading (also 0..1)
				float shade = (dyx + dyz) * (1 - settings.shadeBalanceFactor) / 512f + (height[idx] - settings.shadingReferenceAltitude) * settings.shadeBalanceFactor / 255f;
				// if (shade > 10)
				// shade = 10;
				// if (shade < -10)
				// shade = -10;
				//
				// int altShade = settings.altitudeShadingFactor * (height[idx] - settings.shadingReferenceAltitude) / 255;
				// if (altShade < settings.minAltitudeShading)
				// altShade = settings.minAltitudeShading;
				// if (altShade > settings.maxAltitudeShading)
				// altShade = settings.maxAltitudeShading;

				// shade += altShade;

				// Take tanh(shade * 2) for a better interpolation, multiply with shading factor
				color[idx] = Color.shade(color[idx], (int) (settings.altitudeShadingFactor * Math.tanh(shade * 60)));
				// color[idx] = Color.color(255, (int) (settings.altitudeShadingFactor * shade), (int) (settings.altitudeShadingFactor * shade), (int)
				// (settings.altitudeShadingFactor * shade));
			}
		}
	}

	public void renderAll(RegionMap rm, File outputDir, boolean force, int numThreads) throws IOException, InterruptedException {
		long startTime = System.currentTimeMillis();

		if (!outputDir.exists())
			outputDir.mkdirs();

		if (rm.regions.size() == 0) {
			System.err.println("Warning: no regions found!");
		}

		int numRegions = rm.regions.size();

		if (numRegions < numThreads) {
			numThreads = numRegions;
		}

		int regionInterval = numRegions / numThreads;
		RenderThread[] renderThreads = new RenderThread[numThreads];

		for (int i = 0; i < numThreads; i++) {
			renderThreads[i] = new RenderThread(rm.regions, 0, 0, outputDir, force);
			renderThreads[i].startIndex = regionInterval * i;
			renderThreads[i].endIndex = regionInterval * (i + 1) - 1;
			if (i == numThreads - 1) {
				renderThreads[i].endIndex = numRegions - 1;
			}
			renderThreads[i].start();
		}

		for (int i = 0; i < numThreads; i++) {
			renderThreads[i].join();
		}

		timer.total += System.currentTimeMillis() - startTime;
	}

	public void renderRegions(ArrayList<Region> regions, int startIndex, int endIndex, File outputDir, boolean force) throws IOException {
		for (int i = startIndex; i <= endIndex; i++) {
			renderRegion(regions.get(i), outputDir, force);
		}
	}

	public void renderRegion(Region r, File outputDir, boolean force) throws IOException {
		if (r == null)
			return;

		if (settings.debug)
			System.err.print("Region " + pad(r.rx, 4) + ", " + pad(r.rz, 4) + "...");

		String imageFilename = "tile." + r.rx + "." + r.rz + ".png";
		File fullSizeImageFile = r.imageFile = new File(outputDir, imageFilename);

		boolean fullSizeNeedsReRender = false;
		if (force || !fullSizeImageFile.exists() || fullSizeImageFile.lastModified() < r.regionFile.lastModified()) {
			fullSizeNeedsReRender = true;
		} else {
			if (settings.debug)
				System.err.println("image already up-to-date");
		}

		boolean anyScalesNeedReRender = false;
		for (int scale : settings.mapScales) {
			if (scale == 1)
				continue;
			File f = new File(outputDir, "tile." + r.rx + "." + r.rz + ".1-" + scale + ".png");
			if (force || !f.exists() || f.lastModified() < r.regionFile.lastModified()) {
				anyScalesNeedReRender = true;
			}
		}

		BufferedImage fullSize;
		if (fullSizeNeedsReRender) {
			fullSizeImageFile.delete();
			if (settings.debug)
				System.err.println("generating " + imageFilename + "...");

			RegionFile rf = new RegionFile(r.regionFile);
			try {
				fullSize = renderRegion(rf);
			} finally {
				rf.close();
			}

			try {
				resetInterval();
				ImageIO.write(fullSize, "png", fullSizeImageFile);
				timer.imageSaving += getInterval();
			} catch (IOException e) {
				System.err.println("Error writing PNG to " + fullSizeImageFile);
				e.printStackTrace();
			}
			++timer.regionCount;
		} else if (anyScalesNeedReRender) {
			fullSize = ImageIO.read(fullSizeImageFile);
		} else {
			return;
		}

		for (int scale : settings.mapScales) {
			if (scale == 1)
				continue; // Already wrote!
			File f = new File(outputDir, "tile." + r.rx + "." + r.rz + ".1-" + scale + ".png");
			if (settings.debug)
				System.err.println("generating " + f + "...");
			int size = 512 / scale;
			BufferedImage rescaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = rescaled.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(fullSize, 0, 0, size, size, 0, 0, 512, 512, null);
			g.dispose();
			ImageIO.write(rescaled, "png", f);
		}
	}

	/**
	 * Create a "tiles.html" file containing a table with all region images (tile.<x>.<z>.png) that exist in outDir within the given bounds (inclusive)
	 */
	public void createTileHtml(int minX, int minZ, int maxX, int maxZ, File outputDir) {
		if (settings.debug)
			System.err.println("Writing HTML tiles...");
		for (int scale : settings.mapScales) {
			int regionSize = 512 / scale;

			try {
				File cssFile = new File(outputDir, "tiles.css");
				if (!cssFile.exists()) {
					InputStream cssInputStream = getClass().getResourceAsStream("tiles.css");
					byte[] buffer = new byte[1024 * 1024];
					try {
						FileOutputStream cssOutputStream = new FileOutputStream(cssFile);
						try {
							int r;
							while ((r = cssInputStream.read(buffer)) > 0) {
								cssOutputStream.write(buffer, 0, r);
							}
						} finally {
							cssOutputStream.close();
						}
					} finally {
						cssInputStream.close();
					}
				}

				Writer w = new OutputStreamWriter(new FileOutputStream(new File(
						outputDir,
						scale == 1 ? "tiles.html" : "tiles.1-" + scale + ".html")));
				try {
					w.write("<html><head>\n");
					w.write("<title>" + settings.mapTitle + " - 1:" + scale + "</title>\n");
					w.write("<link rel=\"stylesheet\" type=\"text/css\" href=\"tiles.css\"/>\n");
					w.write("</head><body>\n");
					w.write("<div style=\"height: " + (maxZ - minZ + 1) * regionSize + "px\">");

					for (int z = minZ; z <= maxZ; ++z) {
						for (int x = minX; x <= maxX; ++x) {
							String fullSizeImageFilename = "tile." + x + "." + z + ".png";
							File imageFile = new File(outputDir + "/" + fullSizeImageFilename);
							String scaledImageFilename = scale == 1 ? fullSizeImageFilename : "tile." + x + "." + z + ".1-" + scale + ".png";
							if (imageFile.exists()) {
								int top = (z - minZ) * regionSize, left = (x - minX) * regionSize;
								String title = "Region " + x + ", " + z;
								String name = "r." + x + "." + z;
								String style = "width: " + regionSize + "px; height: " + regionSize + "px; " +
										"position: absolute; top: " + top + "px; left: " + left + "px; " +
										"background-image: url(" + scaledImageFilename + ")";
								w.write("<a\n" +
										"\tclass=\"tile\"\n" +
										"\tstyle=\"" + style + "\"\n" +
										"\ttitle=\"" + title + "\"\n" +
										"\tname=\"" + name + "\"\n" +
										"\thref=\"" + fullSizeImageFilename + "\"\n" +
										">&nbsp;</a>");
							}
						}
					}

					w.write("</div>\n");
					if (settings.mapScales.length > 1) {
						w.write("<div class=\"scales-nav\">");
						w.write("<p>Scales:</p>");
						w.write("<ul>");
						for (int otherScale : settings.mapScales) {
							if (otherScale == scale) {
								w.write("<li>1:" + scale + "</li>");
							} else {
								String otherFilename = otherScale == 1 ? "tiles.html" : "tiles.1-" + otherScale + ".html";
								w.write("<li><a href=\"" + otherFilename + "\">1:" + otherScale + "</a></li>");
							}
						}
						w.write("</ul>");
						w.write("</div>");
					}
					w.write("<p class=\"notes\">");
					w.write("Page rendered at " + new Date().toString());
					w.write("</p>\n");
					w.write("</body></html>");
				} finally {
					w.close();
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	}

	public void createImageTree(RegionMap rm) {
		if (settings.debug)
			System.err.println("Composing image tree...");
		ImageTreeComposer itc = new ImageTreeComposer(new ContentStore());
		System.out.println(itc.compose(rm));
	}

	public void createBigImage(RegionMap rm, File outputDir) {
		if (settings.debug)
			System.err.println("Creating big image...");
		BigImageMerger bic = new BigImageMerger();
		bic.createBigImage(rm, outputDir, settings.debug);
	}

	protected static String pad(String v, int targetLength) {
		while (v.length() < targetLength)
			v = " " + v;
		return v;
	}

	protected static String pad(int v, int targetLength) {
		return pad("" + v, targetLength);
	}
}
