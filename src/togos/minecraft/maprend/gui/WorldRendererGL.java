package togos.minecraft.maprend.gui;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_BGRA;
import static org.lwjgl.opengl.GL12.GL_UNSIGNED_INT_8_8_8_8_REV;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL30.glGenerateMipmap;
import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector3d;
import org.lwjgl.BufferUtils;
import togos.minecraft.maprend.core.RegionMap;
import togos.minecraft.maprend.core.RegionRenderer;
import togos.minecraft.maprend.core.io.RegionFile;

/**
 * Renders the world as a map with OpenGL. Allows for dragging and zooming if you set callbacks to call the appropriate methods in this class.
 */
public class WorldRendererGL {

	protected RegionRenderer				renderer;
	protected RegionMap						regionMap;

	protected Map<Vector2i, Integer>		textures;
	protected Map<Vector2i, BufferedImage>	toAdd		= new ConcurrentHashMap<>();
	protected ExecutorService				executor;

	protected boolean						dragging	= false;
	protected Vector3d						mousePos	= new Vector3d();
	protected Vector3d						translation	= new Vector3d();
	protected double						scale		= .1;

	protected int							vbo;

	public WorldRendererGL(RegionRenderer renderer, RegionMap regionMap) {
		this.renderer = Objects.requireNonNull(renderer);
		this.regionMap = Objects.requireNonNull(regionMap);
		textures = regionMap.regions.stream().map(r -> new Vector2i(r.rx, r.rz)).collect(Collectors.toMap(v -> v, v -> 0));

		executor = Executors.newFixedThreadPool(4);
		loadRegions();
	}

	public void init() {
		glEnable(GL_TEXTURE_2D);
		glTexEnvf(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_MODULATE);
		// glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_BASE_LEVEL, 0);
		// glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 0);
		// glColorMaterial(GL_FRONT, GL_AMBIENT_AND_DIFFUSE);
		glEnableClientState(GL_VERTEX_ARRAY);
		// glEnableClientState(GL_COLOR_ARRAY);
		// glEnableClientState(GL_INDEX_ARRAY);
		glEnableClientState(GL_TEXTURE_COORD_ARRAY);

		glEnable(GL_BLEND);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

		float[] vertices = {
				-256f, -256f, 0, 0,
				256f, -256f, 1, 0,
				256f, 256f, 1, 1,
				-256f, 256f, 0, 1 };
		vbo = glGenBuffers();
		glBindBuffer(GL_ARRAY_BUFFER, vbo);
		glBufferData(GL_ARRAY_BUFFER, (FloatBuffer) BufferUtils.createFloatBuffer(vertices.length).put(vertices).flip(), GL_STATIC_DRAW);
		glVertexPointer(2, GL_FLOAT, 4 * 4, 0L);
		glTexCoordPointer(2, GL_FLOAT, 4 * 4, 4 * 2);
	}

	/** Requires the projection to be set up to {@code GL11.glOrtho(0, width, height, 0, -1, 1);} */
	public void renderWorld() {
		glPushMatrix();
		glScaled(scale, scale, scale);
		glTranslated(translation.x, translation.y, translation.z);
		glBindBuffer(GL_ARRAY_BUFFER, vbo);

		toAdd.forEach((k, v) -> loadImage(k, v));
		toAdd.clear();
		textures.keySet().forEach((pos) -> renderRegion(pos));
		glPopMatrix();
	}

	public void renderRegion(Vector2i region) {
		glPushMatrix();
		glTranslatef(region.x * 512, region.y * 512, 0);

		int id = textures.get(region);
		glBindTexture(GL_TEXTURE_2D, 0);
		glColor4f(0.3f, 0.3f, 0.9f, 1.0f);
		glDrawArrays(GL_QUADS, 0, 4);
		if (id != 0) {
			glColor4f(1f, 1f, 1f, 1f);
			glBindTexture(GL_TEXTURE_2D, id);
			glDrawArrays(GL_QUADS, 0, 4);
		}
		glPopMatrix();
	}

	public void loadRegions() {
		regionMap.regions.forEach((r) -> loadRegion(new Vector2i(r.rx, r.rz)));
	}

	public void loadRegion(final Vector2i region) {
		// TODO proper queue
		// TODO close RegionFile resource
		executor.submit(() -> {
			try {
				toAdd.put(region, renderer.renderRegion(new RegionFile(regionMap.regionAt(region.x, region.y).regionFile)));
			} catch (Throwable e) {
				e.printStackTrace();
			}
		});
	}

	protected void loadImage(Vector2i region, BufferedImage image) {
		int id = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, id);
		int[] data = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());

		IntBuffer pixels = BufferUtils.createIntBuffer(data.length);
		pixels.put(data);
		pixels.flip(); // FOR THE LOVE OF GOD DO NOT FORGET THIS

		// glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, image.getWidth(), image.getHeight(), 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, image.getWidth(), image.getHeight(), 0, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, pixels); // read ARGB directly

		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP);

		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glGenerateMipmap(GL_TEXTURE_2D);
		textures.put(region, id);
	}

	public void mouseMove(long window, double x, double y) {
		Vector3d lastCursor = new Vector3d(mousePos);
		mousePos.set(x, y, 0);

		if (dragging) {
			// Difference in world space
			Vector3d dragDist = new Vector3d(lastCursor).sub(mousePos).negate();
			translation.add(new Vector3d(dragDist.x / scale, dragDist.y / scale, 0));
		}
	}

	public void mouseScroll(double dy) {
		// In world coordinates
		Vector3d cursorPos = new Vector3d(mousePos).div(scale).sub(translation);
		double ds = Math.pow(0.9, -dy);
		double newScale = scale * ds;
		if (newScale > 128)
			newScale = 128;
		if (newScale < 1.0 / 128)
			newScale = 1.0 / 128;
		ds = newScale / scale;
		scale = newScale;
		translation.add(cursorPos);
		translation.div(ds);
		translation.sub(cursorPos);
	}

	public void buttonPress(boolean pressed) {
		dragging = pressed;
	}

	public boolean isVisible(Vector2f point) {
		return true;
	}

	public boolean isVisible(Vector2i region) {
		return true;
	}
}
