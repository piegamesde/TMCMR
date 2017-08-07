package togos.minecraft.maprend.core.color;

import static togos.minecraft.maprend.core.color.IDUtil.parseInt;
import java.io.*;

public final class BiomeColors {

	public static final class Biome {

		public final int		grassColor;
		public final int		foliageColor;
		public final int		waterColor;
		public final boolean	isDefault;

		public Biome(int grassColor, int foliageColor, int waterColor, boolean isDefault) {
			this.grassColor = grassColor;
			this.foliageColor = foliageColor;
			this.waterColor = waterColor;
			this.isDefault = isDefault;
		}

		public int getMultiplier(int influence) {
			switch (influence) {
				case BlockColors.INF_GRASS:
					return grassColor;
				case BlockColors.INF_FOLIAGE:
					return foliageColor;
				case BlockColors.INF_WATER:
					return waterColor;
				default:
					return 0xFFFFFFFF;
			}
		}
	}

	public static final int	INDEX_MASK	= 0xFF;
	public static final int	SIZE		= INDEX_MASK + 1;

	public final Biome		defaultBiome;
	public final Biome[]	biomes;

	public BiomeColors(Biome[] biomes, Biome defaultBiome) {
		this.biomes = biomes;
		this.defaultBiome = defaultBiome;
	}

	protected static BiomeColors load(BufferedReader in, String filename) throws IOException {
		Biome[] biomes = new Biome[SIZE];
		Biome defaultBiome = null;
		int lineNum = 0;
		String line;
		while ((line = in.readLine()) != null) {
			++lineNum;
			if (line.trim().isEmpty())
				continue;
			if (line.trim().startsWith("#"))
				continue;

			String[] v = line.split("\t", 5);
			if (v.length < 4) {
				System.err.println("Invalid biome map line at " + filename + ":" + lineNum + ": " + line);
				continue;
			}
			int grassColor = parseInt(v[1]);
			int foliageColor = parseInt(v[2]);
			int waterColor = parseInt(v[3]);
			if ("default".equals(v[0])) {
				defaultBiome = new Biome(grassColor, foliageColor, waterColor, true);
			} else {
				int blockId = parseInt(v[0]);
				biomes[blockId] = new Biome(grassColor, foliageColor, waterColor, false);
			}
		}
		if (defaultBiome == null) {
			defaultBiome = new Biome(0xFFFF00FF, 0xFFFF00FF, 0xFFFF00FF, true);
		}
		return new BiomeColors(biomes, defaultBiome);
	}

	public static BiomeColors loadDefault() throws IOException {
		BufferedReader br = new BufferedReader(new InputStreamReader(BiomeColors.class.getResourceAsStream("biome-colors.txt")));
		try {
			return load(br, "(default biome colors)");
		} finally {
			br.close();
		}
	}

	public static BiomeColors load(File f) throws IOException {
		BufferedReader br = new BufferedReader(new FileReader(f));
		try {
			return load(br, f.getPath());
		} finally {
			br.close();
		}
	}

	public Biome getBiome(int biomeId) {
		if (biomeId >= 0 && biomeId < biomes.length) {
			if (biomes[biomeId] != null)
				return biomes[biomeId];
		}
		return defaultBiome;
	}
}