package togos.minecraft.maprend.commandstandalone;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import togos.minecraft.maprend.core.*;
import togos.minecraft.maprend.core.RegionRenderer.Timer;
import togos.minecraft.maprend.core.color.IDUtil;

public class RegionRendererCommand {

	public static final String USAGE = "Usage: TMCMR [options] -o <output-dir> <input-files>\n" +
			"  -h, -? ; print usage instructions and exit\n" +
			"  -f     ; force re-render even when images are newer than regions\n" +
			"  -debug ; be chatty\n" +
			"  -color-map <file>  ; load a custom color map from the specified file\n" +
			"  -biome-map <file>  ; load a custom biome color map from the specified file\n" +
			"  -create-tile-html  ; generate tiles.html in the output directory\n" +
			"  -create-image-tree ; generate a PicGrid-compatible image tree\n" +
			"  -create-big-image  ; merges all rendered images into a single file\n" +
			"  -min-height <y>    ; only draw blocks above this height\n" +
			"  -max-height <y>    ; only draw blocks below this height\n" +
			"  -region-limit-rect <x0> <y0> <x1> <y1> ; limit which regions are rendered\n" +
			"                     ; to those between the given region coordinates, e.g.\n" +
			"                     ; 0 0 2 2 to render the 4 regions southeast of the origin.\n" +
			"  -altitude-shading-factor <f>    ; how much altitude affects shading [36]\n" +
			"  -shading-reference-altitude <y> ; reference altitude for shading [64]\n" +
			"  -min-altitude-shading <x>       ; lowest altitude shading modifier [-20]\n" +
			"  -max-altitude-shading <x>       ; highest altitude shading modifier [20]\n" +
			"  -title <title>     ; title to include with maps\n" +
			"  -scales 1:<n>,...  ; list scales at which to render\n" +
			"\n" +
			"Input files may be 'region/' directories or individual '.mca' files.\n" +
			"\n" +
			"tiles.html will always be generated if a single directory is given as input.\n" +
			"\n" +
			"Compound image tree blobs will be written to ~/.ccouch/data/tmcmr/\n" +
			"Compound images can then be rendered with PicGrid.";

	protected static final boolean booleanValue(Boolean b, boolean defalt) {
		return b == null ? defalt : b.booleanValue();
	}

	protected static boolean singleDirectoryGiven(List<File> files) {
		return files.size() == 1 && files.get(0).isDirectory();
	}

	public static RegionRendererCommand fromArguments(String... args) {
		RegionRendererCommand m = new RegionRendererCommand();
		for (int i = 0; i < args.length; ++i) {
			if (args[i].charAt(0) != '-') {
				m.regionFiles.add(new File(args[i]));
			} else if ("-o".equals(args[i])) {
				m.outputDir = new File(args[++i]);
			} else if ("-f".equals(args[i])) {
				m.forceReRender = true;
			} else if ("-debug".equals(args[i])) {
				m.debug = true;
			} else if ("-min-height".equals(args[i])) {
				m.minHeight = Integer.parseInt(args[++i]);
			} else if ("-max-height".equals(args[i])) {
				m.maxHeight = Integer.parseInt(args[++i]);
			} else if ("-create-tile-html".equals(args[i])) {
				m.createTileHtml = Boolean.TRUE;
			} else if ("-create-image-tree".equals(args[i])) {
				m.createImageTree = Boolean.TRUE;
			} else if ("-region-limit-rect".equals(args[i])) {
				int minX = Integer.parseInt(args[++i]);
				int minY = Integer.parseInt(args[++i]);
				int maxX = Integer.parseInt(args[++i]);
				int maxY = Integer.parseInt(args[++i]);
				m.regionLimitRect = new BoundingRect(minX, minY, maxX, maxY);
			} else if ("-create-big-image".equals(args[i])) {
				m.createBigImage = true;
			} else if ("-color-map".equals(args[i])) {
				m.colorMapFile = new File(args[++i]);
			} else if ("-biome-map".equals(args[i])) {
				m.biomeMapFile = new File(args[++i]);
			} else if ("-altitude-shading-factor".equals(args[i])) {
				m.altitudeShadingFactor = Integer.parseInt(args[++i]);
			} else if ("-shading-reference-altitude".equals(args[i])) {
				m.shadingReferenceAltitude = Integer.parseInt(args[++i]);
			} else if ("-min-altitude-shading".equals(args[i])) {
				m.minAltitudeShading = Integer.parseInt(args[++i]);
			} else if ("-max-altitude-shading".equals(args[i])) {
				m.maxAltitudeShading = Integer.parseInt(args[++i]);
			} else if ("-h".equals(args[i]) || "-?".equals(args[i]) || "--help".equals(args[i]) || "-help".equals(args[i])) {
				m.printHelpAndExit = true;
			} else if ("-title".equals(args[i])) {
				m.mapTitle = args[++i];
			} else if ("-scales".equals(args[i])) {
				String[] scales = args[++i].split(",");
				int[] invScales = new int[scales.length];
				for (int j = 0; j < scales.length; ++j) {
					if (scales[j].equals("1"))
						invScales[j] = 1;
					else if (scales[j].startsWith("1:")) {
						invScales[j] = Integer.parseInt(scales[j].substring(2));
					} else {
						m.errorMessage = "Invalid scale: '" + scales[j] + "'; must be of the form '1:n'";
						return m;
					}
				}
				m.mapScales = invScales;
			} else {
				m.errorMessage = "Unrecognised argument: " + args[i];
				return m;
			}
		}
		m.errorMessage = validateSettings(m);
		return m;
	}

	private static String validateSettings(RegionRendererCommand m) {
		if (m.regionFiles.size() == 0)
			return "No regions or directories specified.";
		else if (m.outputDir == null)
			return "Output directory unspecified.";
		else
			return null;
	}

	public File				outputDir					= null;
	public boolean			forceReRender				= false;
	public boolean			debug						= false;
	public boolean			printHelpAndExit			= false;
	public File				colorMapFile				= null;
	public File				biomeMapFile				= null;
	public ArrayList<File>	regionFiles					= new ArrayList<File>();
	public Boolean			createTileHtml				= null;
	public Boolean			createImageTree				= null;
	public boolean			createBigImage				= false;
	public BoundingRect		regionLimitRect				= BoundingRect.INFINITE;
	public int				minHeight					= Integer.MIN_VALUE;
	public int				maxHeight					= Integer.MAX_VALUE;
	public int				shadingReferenceAltitude	= 64;
	public int				minAltitudeShading			= -20;
	public int				maxAltitudeShading			= +20;
	public int				altitudeShadingFactor		= 36;
	public int[]			mapScales					= { 1 };
	public String			mapTitle					= "Regions";

	public String			errorMessage				= null;

	static boolean getDefault(Boolean b, boolean defaultValue) {
		return b != null ? b.booleanValue() : defaultValue;
	}

	public boolean shouldCreateTileHtml() {
		return getDefault(this.createTileHtml, singleDirectoryGiven(regionFiles));
	}

	public boolean shouldCreateImageTree() {
		return getDefault(this.createImageTree, false);
	}

	public int run() throws IOException {
		if (errorMessage != null) {
			System.err.println("Error: " + errorMessage);
			System.err.println(USAGE);
			return 1;
		}
		if (printHelpAndExit) {
			System.out.println(USAGE);
			return 0;
		}

		RegionMap rm = RegionMap.load(regionFiles, regionLimitRect);
		RenderSettings settings = new RenderSettings(
				colorMapFile, biomeMapFile, debug, minHeight, maxHeight,
				shadingReferenceAltitude, minAltitudeShading, maxAltitudeShading, altitudeShadingFactor,
				mapTitle, mapScales);
		RegionRenderer rr = new RegionRenderer(settings);

		rr.renderAll(rm, outputDir, forceReRender);

		if (debug) {
			final Timer tim = rr.timer;
			System.err.println("Rendered " + tim.regionCount + " regions, " + tim.sectionCount + " sections in " + (tim.total) + "ms");
			System.err.println("The following times lines indicate milliseconds total, per region, and per section");
			System.err.println(tim.formatTime("Loading", tim.regionLoading));
			System.err.println(tim.formatTime("Pre-rendering", tim.preRendering));
			System.err.println(tim.formatTime("Post-processing", tim.postProcessing));
			System.err.println(tim.formatTime("Image saving", tim.imageSaving));
			System.err.println(tim.formatTime("Total", tim.total));
			System.err.println();

			if (rr.defaultedBlockIds.size() > 0) {
				System.err.println("The following block IDs were not explicitly mapped to colors:");
				int z = 0;
				for (int blockId : rr.defaultedBlockIds) {
					System.err.print(z == 0 ? "  " : z % 10 == 0 ? ",\n  " : ", ");
					System.err.print(IDUtil.blockIdString(blockId));
					++z;
				}
				System.err.println();
			} else {
				System.err.println("All block IDs encountered were accounted for in the block color map.");
			}
			System.err.println();

			if (rr.defaultedBlockIdDataValues.size() > 0) {
				System.err.println("The following block ID + data value pairs were not explicitly mapped to colors");
				System.err.println("(this is not necessarily a problem, as the base IDs were mapped to a color):");
				int z = 0;
				for (int blockId : rr.defaultedBlockIdDataValues) {
					System.err.print(z == 0 ? "  " : z % 10 == 0 ? ",\n  " : ", ");
					System.err.print(IDUtil.blockIdString(blockId));
					++z;
				}
				System.err.println();
			} else {
				System.err.println("All block ID + data value pairs encountered were accounted for in the block color map.");
			}
			System.err.println();

			if (rr.defaultedBiomeIds.size() > 0) {
				System.err.println("The following biome IDs were not explicitly mapped to colors:");
				int z = 0;
				for (int biomeId : rr.defaultedBiomeIds) {
					System.err.print(z == 0 ? "  " : z % 10 == 0 ? ",\n  " : ", ");
					System.err.print(String.format("0x%02X", biomeId));
					++z;
				}
				System.err.println();
			} else {
				System.err.println("All biome IDs encountered were accounted for in the biome color map.");
			}
			System.err.println();
		}

		if (shouldCreateTileHtml())
			rr.createTileHtml(rm.minX, rm.minZ, rm.maxX, rm.maxZ, outputDir);
		if (shouldCreateImageTree())
			rr.createImageTree(rm);
		if (createBigImage)
			rr.createBigImage(rm, outputDir);

		return 0;
	}

	//// Command-line processing ////

	public static void main(String[] args) throws Exception {
		System.exit(RegionRendererCommand.fromArguments(args).run());
	}
}