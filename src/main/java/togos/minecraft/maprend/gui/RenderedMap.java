package togos.minecraft.maprend.gui;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import org.joml.*;
import org.mapdb.*;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import togos.minecraft.maprend.core.Region;

public class RenderedMap {

	/** https://github.com/jankotek/mapdb/issues/839 */
	protected Set<Vector3ic>								unloaded			= ConcurrentHashMap.newKeySet();
	public final Serializer<Vector3ic>						VECTOR_SERIALIZER	= new Serializer<Vector3ic>() {

																					@SuppressWarnings("unchecked")
																					@Override
																					public void serialize(DataOutput2 out, Vector3ic value) throws IOException {
																						unloaded.add(value);
																						Serializer.JAVA.serialize(out, value);
																					}

																					@Override
																					public Vector3ic deserialize(DataInput2 input, int available) throws IOException {
																						Vector3ic value = (Vector3ic) Serializer.JAVA.deserialize(input, available);
																						unloaded.remove(value);
																						return value;
																					}

																				};
	/**
	 * Thank the JavaFX guys who a) Made WriteableImage not Serializable b) Didn't include any serialization except by converting to BufferedImage for this ugly
	 * mess.
	 */
	public final Serializer<WritableImage>					IMAGE_SERIALIZER	= new Serializer<WritableImage>() {

																					// @Override
																					// public int compare(WritableImage o1, WritableImage o2) {
																					// return 0;
																					// }

																					@Override
																					public void serialize(DataOutput2 out, WritableImage value) throws IOException {
																						// System.out.println("Serializing");
																						ImageIO.write(SwingFXUtils.fromFXImage(value, null), "png", out);
																					}

																					@Override
																					public WritableImage deserialize(DataInput2 input, int available) throws IOException {
																						// java.awt.image.RenderedImage
																						byte[] data = new byte[available];
																						// System.out.println(available >> 10);
																						input.readFully(data);
																						ByteArrayInputStream bain = new ByteArrayInputStream(data);
																						return SwingFXUtils.toFXImage(ImageIO.read(bain), null);
																					}
																				};

	// Disk for overflow
	private static final DB									cacheDBDisk			= DBMaker.tempFileDB().fileDeleteAfterClose().closeOnJvmShutdown().make();
	// Fast memory cache
	private static final DB									cacheDBMem			= DBMaker.heapDB().closeOnJvmShutdown().make();

	private final HTreeMap<Vector3ic, WritableImage>		cacheMapDisk, cacheMapDiskMem, cacheMapMem;

	private Map<Vector2ic, RenderedRegion>					plainRegions		= new HashMap<>();
	private Map<Integer, Map<Vector2ic, RenderedRegion>>	regions				= new HashMap<>();

	@SuppressWarnings("unchecked")
	public RenderedMap(ScheduledExecutorService executor) {
		cacheMapDisk = cacheDBDisk.hashMap("OnDisk" + System.identityHashCode(this), VECTOR_SERIALIZER, IMAGE_SERIALIZER).create();
		cacheMapDisk.clear();
		cacheMapDiskMem = cacheDBMem
				.hashMap("RenderedRegionCache" + System.identityHashCode(this), Serializer.JAVA, Serializer.JAVA)
				// .expireStoreSize(1)
				.expireMaxSize(1024)
				.expireAfterCreate()
				.expireAfterUpdate()
				.expireAfterGet()
				.expireAfterGet(1, TimeUnit.MINUTES)
				.expireOverflow(cacheMapDisk)
				.expireExecutor(executor)
				.expireExecutorPeriod(10000)
				.create();
		// cacheMapMem = cacheMapDiskMem;
		cacheMapMem = cacheDBMem.hashMap("ScaledRegionCache" + System.identityHashCode(this), Serializer.JAVA, Serializer.JAVA)
				// .expireStoreSize(1)
				.expireMaxSize(512)
				.expireAfterCreate()
				.expireAfterUpdate()
				.expireAfterGet()
				.expireAfterGet(1, TimeUnit.MINUTES)
				// .expireOverflow(cacheMapDisk)
				.expireExecutor(executor)
				.expireExecutorPeriod(10000)
				.create();

		cacheMapDisk.checkThreadSafe();
		cacheMapDiskMem.checkThreadSafe();
		cacheMapMem.checkThreadSafe();
		clearReload(Collections.emptyList());
	}

	public RenderedMap(ScheduledExecutorService executor, Collection<Region> positions) {
		this(executor);
		clearReload(positions);
	}

	public void close() {
		clearReload(Collections.emptyList());
		cacheMapDiskMem.close();
		cacheMapMem.close();
		cacheMapDisk.close();
		cacheDBMem.close();
		cacheDBDisk.close();
	}

	public void clearReload(Collection<Region> positions) {
		cacheMapDisk.clear();
		cacheMapDiskMem.clear();
		cacheMapMem.clear();
		regions.clear();
		plainRegions.clear();
		positions.stream().map(r -> new RenderedRegion(this, r)).forEach(r -> plainRegions.put(r.position, r));
		regions.put(0, plainRegions);
	}

	public void invalidateAll() {
		// System.out.println("Invalidate all");
		plainRegions.values().forEach(r -> r.invalidateTree(true));
	}

	public boolean isNothingLoaded() {
		return get(0).isEmpty();
	}

	public void draw(GraphicsContext gc, int level, AABBd frustum, double scale) {
		Map<Vector2ic, RenderedRegion> map = get(level > 0 ? 0 : level);
		plainRegions.values()
				.stream()
				.filter(r -> r.isVisible(frustum))
				.forEach(r -> r.drawBackground(gc));
		map
				.entrySet()
				.stream()
				.filter(e -> RenderedRegion.isVisible(e.getKey(), level > 0 ? 0 : level, frustum))
				.map(e -> {
					RenderedRegion r = e.getValue();
					if (e.getValue() == null)
						r = get(level, e.getKey(), true);
					return r;
				})
				.forEach(r -> r.draw(gc, level, frustum));
		plainRegions.values()
				.stream()
				.filter(r -> r.isVisible(frustum))
				.forEach(r -> r.drawForeground(gc, frustum, scale));
	}

	public boolean updateImage(int level, AABBd frustum) {
		try {
			// TODO FIXME this won't return (quickly/at all?), causing the application to continue running in background (forever?) after closing.
			return get(level)
					.entrySet()
					.stream()
					.map(e -> e.getValue())
					.filter(r -> r != null)
					// .filter(e -> e.getValue().needsUpdate())
					.filter(r -> r.isVisible(frustum))
					// .sorted(comp)
					// .limit(10)
					.filter(r -> r.updateImage())
					.collect(Collectors.toList())
					.size() > 0;
		} catch (ConcurrentModificationException e) {
			// System.out.println(e);
			return true;
		}
	}

	public Map<Vector2ic, RenderedRegion> get(int level) {
		if (level > 20 || level < -20)
			throw new StackOverflowError("Internal error");
		Map<Vector2ic, RenderedRegion> ret = regions.get(level);
		if (ret == null) {
			Map<Vector2ic, RenderedRegion> ret2 = new HashMap<Vector2ic, RenderedRegion>();
			ret = ret2;
			get(level < 0 ? level + 1 : level - 1).keySet().stream().map(RenderedMap::abovePos).distinct().forEach(v -> ret2.put(v, null));

			regions.put(level, ret);
		}
		return ret;
	}

	public void putImage(Vector2ic pos, WritableImage image) {
		if (!plainRegions.containsKey(pos))
			throw new IllegalArgumentException("Position out of bounds");
		plainRegions.get(pos).setImage(image);
	}

	public RenderedImage createImage(RenderedRegion r) {
		return new RenderedImage(this, r.level <= 0 ? cacheMapDiskMem : cacheMapMem, new Vector3i(r.position.x(), r.position.y(), r.level).toImmutable());
	}

	protected boolean isImageLoaded(Vector3ic key) {
		return !unloaded.contains(key);
	}

	public RenderedRegion get(int level, Vector2ic position, boolean create) {
		Map<Vector2ic, RenderedRegion> map = get(level);
		RenderedRegion r = map.get(position);
		if (create && r == null && level != 0 && ((level > 0 /* && plainRegions.containsKey(new Vector2i(position.x() >> level, position.y() >>
																 * level).toImmutable()) */) || map.containsKey(position))) {
			r = new RenderedRegion(this, level, position);
			if (level < 0)
				Arrays.stream(belowPos(position)).forEach(pos -> get(level + 1, position, true));
			if (level > 0)
				get(level - 1, abovePos(position), true);
			map.put(position, r);
		}
		return r;
	}

	public RenderedRegion[] get(int level, Vector2ic[] belowPos, boolean create) {
		RenderedRegion[] ret = new RenderedRegion[belowPos.length];
		for (int i = 0; i < belowPos.length; i++)
			ret[i] = get(level, belowPos[i], create);
		return ret;
	}

	public static Vector2i abovePos(Vector2ic pos) {
		return groundPos(pos, 1);
	}

	public static Vector2i groundPos(Vector2ic pos, int levelDiff) {
		return new Vector2i(pos.x() >> levelDiff, pos.y() >> levelDiff);
	}

	public static Vector2i[] belowPos(Vector2ic pos) {
		Vector2i belowPos = new Vector2i(pos.x() << 1, pos.y() << 1);
		return new Vector2i[] {
				new Vector2i(0, 0).add(belowPos),
				new Vector2i(1, 0).add(belowPos),
				new Vector2i(0, 1).add(belowPos),
				new Vector2i(1, 1).add(belowPos)
		};
	}

	public static WritableImage halfSize(WritableImage old, WritableImage... corners) {
		return halfSize(corners[0], corners[1], corners[2], corners[3]);
	}

	public static WritableImage halfSize(WritableImage old, WritableImage topLeft, WritableImage topRight, WritableImage bottomLeft, WritableImage bottomRight) {
		WritableImage output = old != null ? old : new WritableImage(512, 512);

		PixelReader topLeftReader = topLeft != null ? topLeft.getPixelReader() : null;
		PixelReader topRightReader = topRight != null ? topRight.getPixelReader() : null;
		PixelReader bottomLeftReader = bottomLeft != null ? bottomLeft.getPixelReader() : null;
		PixelReader bottomRightReader = bottomRight != null ? bottomRight.getPixelReader() : null;
		PixelWriter writer = output.getPixelWriter();

		// TODO optimize with buffers
		for (int y = 0; y < 256; y++) {
			for (int x = 0; x < 256; x++) {
				int rx = x * 2;
				int ry = y * 2;
				writer.setArgb(x, y, topLeftReader != null ? sampleColor(rx, ry, topLeftReader) : 0);
				writer.setArgb(x + 256, y, topRightReader != null ? sampleColor(rx, ry, topRightReader) : 0);
				writer.setArgb(x, y + 256, bottomLeftReader != null ? sampleColor(rx, ry, bottomLeftReader) : 0);
				writer.setArgb(x + 256, y + 256, bottomRightReader != null ? sampleColor(rx, ry, bottomRightReader) : 0);
			}
		}
		return output;
	}

	private static int sampleColor(int x, int y, PixelReader reader) {
		long a = reader.getArgb(x, y);
		long b = reader.getArgb(x + 1, y);
		long c = reader.getArgb(x, y + 1);
		long d = reader.getArgb(x + 1, y + 1);
		// TODO premultiply alpha to avoid dark edges
		long ret = 0;
		// alpha
		ret |= (((a & 0xFF000000) + (b & 0xFF000000) + (c & 0xFF000000) + (d & 0xFF000000)) >> 2) & 0xFF000000;
		// red
		ret |= (((a & 0x00FF0000) + (b & 0x00FF0000) + (c & 0x00FF0000) + (d & 0x00FF0000)) >> 2) & 0x00FF0000;
		// green
		ret |= (((a & 0x0000FF00) + (b & 0x0000FF00) + (c & 0x0000FF00) + (d & 0x0000FF00)) >> 2) & 0x0000FF00;
		// blue
		ret |= (((a & 0x000000FF) + (b & 0x000000FF) + (c & 0x000000FF) + (d & 0x000000FF)) >> 2) & 0x000000FF;
		return (int) ret;
	}

	public static WritableImage doubleSize(WritableImage old, WritableImage input, int levelDiff, Vector2i subTile) {
		WritableImage output = old != null ? old : new WritableImage(512, 512);

		PixelReader reader = input.getPixelReader();
		PixelWriter writer = output.getPixelWriter();

		int tileSize = 512 >> levelDiff;
		int scaleFactor = 1 << levelDiff;

		for (int y = 0; y < tileSize; y++) {
			for (int x = 0; x < tileSize; x++) {
				int rx = x + subTile.x * tileSize;
				int ry = y + subTile.y * tileSize;
				final int argb = reader.getArgb(rx, ry);

				// TODO optimize with buffers
				for (int dx = 0; dx < scaleFactor; dx++)
					for (int dy = 0; dy < scaleFactor; dy++) {
						writer.setArgb(x * scaleFactor + dx, y * scaleFactor + dy, argb);
					}
			}
		}
		return output;
	}
}