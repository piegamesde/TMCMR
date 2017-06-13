package togos.minecraft.maprend.guistandalone;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.NULL;
import java.io.File;
import java.io.IOException;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.Callback;
import togos.minecraft.maprend.core.BoundingRect;
import togos.minecraft.maprend.core.RegionMap;
import togos.minecraft.maprend.core.RegionRenderer;
import togos.minecraft.maprend.core.RenderSettings;
import togos.minecraft.maprend.gui.WorldRendererGL;

public class GuiMain {

	// We need to strongly reference callback instances.
	private GLFWErrorCallback			errorCallback;
	// private GLFWKeyCallback keyCallback;
	private GLFWCursorPosCallback		mouseCallback;
	private GLFWMouseButtonCallback		buttonCallback;
	private GLFWScrollCallback			scrollCallback;
	private GLFWWindowSizeCallback		wsCallback;
	private GLFWWindowRefreshCallback	refreshCallback;
	private Callback					debugProc;

	// The window handle
	private long						window;
	private int							width, height;

	private WorldRendererGL				renderer;

	public void run(String file) {
		try {
			init(file);
			loop();

			// Release window and window callbacks
			glfwDestroyWindow(window);
			// keyCallback.free();
			wsCallback.free();
			buttonCallback.free();
			scrollCallback.free();
			mouseCallback.free();
			refreshCallback.free();
			if (debugProc != null)
				debugProc.free();
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			// Terminate GLFW and release the GLFWerrorfun
			glfwTerminate();
			errorCallback.free();
		}
	}

	private void init(String file) throws IOException {
		// Setup an error callback. The default implementation
		// will print the error message in System.err.
		glfwSetErrorCallback(errorCallback = GLFWErrorCallback.createPrint(System.err));

		// Initialize GLFW. Most GLFW functions will not work before doing this.
		if (!glfwInit())
			throw new IllegalStateException("Unable to initialize GLFW");

		// Configure our window
		glfwDefaultWindowHints(); // optional, the current window hints are already the default
		glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 1);
		glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 5);
		glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE); // the window will stay hidden after creation
		glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE); // the window will be resizable

		int WIDTH = 300;
		int HEIGHT = 300;

		// Create the window
		window = glfwCreateWindow(WIDTH, HEIGHT, "Hello World!", NULL, NULL);
		if (window == NULL)
			throw new RuntimeException("Failed to create the GLFW window");

		// // Setup a key callback. It will be called every time a key is pressed, repeated or released.
		// glfwSetKeyCallback(window, keyCallback = new GLFWKeyCallback() {
		//
		// @Override
		// public void invoke(long window, int key, int scancode, int action, int mods) {
		// if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE)
		// glfwSetWindowShouldClose(window, true); // We will detect this in our rendering loop
		// }
		// });
		glfwSetWindowRefreshCallback(window, refreshCallback = new GLFWWindowRefreshCallback() {

			@Override
			public void invoke(long window) {
				render();
			}
		});

		glfwSetWindowSizeCallback(window, wsCallback = new GLFWWindowSizeCallback() {

			@Override
			public void invoke(long window, int w, int h) {
				if (w > 0 && h > 0) {
					width = w;
					height = h;
				}
			}
		});

		glfwSetScrollCallback(window, scrollCallback = new GLFWScrollCallback() {

			@Override
			public void invoke(long window, double xoffset, double yoffset) {
				renderer.mouseScroll(yoffset);
				render();
			}
		});

		glfwSetCursorPosCallback(window, mouseCallback = new GLFWCursorPosCallback() {

			@Override
			public void invoke(long window, double xpos, double ypos) {
				renderer.mouseMove(window, xpos, ypos);
				render();
			}
		});

		glfwSetMouseButtonCallback(window, buttonCallback = new GLFWMouseButtonCallback() {

			@Override
			public void invoke(long window, int button, int action, int mods) {
				if (button == 1)
					renderer.buttonPress(action == GLFW_PRESS);
				// System.out.println(action);
				render();
			}
		});
		// glfwSetMouseButtonCallback(window, )

		GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
		glfwSetWindowPos(window, (vidmode.width() - width) / 2, (vidmode.height() - height) / 2);

		// Make the OpenGL context current
		glfwMakeContextCurrent(window);
		// Enable v-sync
		glfwSwapInterval(1);

		// Make the window visible
		glfwShowWindow(window);
		GL.createCapabilities();
		debugProc = GLUtil.setupDebugMessageCallback();

		renderer = new WorldRendererGL(new RegionRenderer(new RenderSettings()), RegionMap.load(new File(file), BoundingRect.INFINITE));
		renderer.init();

		// Set the clear color
		glClearColor(0.2f, 0.2f, 0.6f, 1.0f);
	}

	private void loop() {

		while (!glfwWindowShouldClose(window)) {
			glfwWaitEvents();
			// glfwPollEvents();
			// Thread.yield();
		}
		System.exit(0);
	}

	protected void render() {
		glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
		glViewport(0, 0, width, height);
		glMatrixMode(GL_PROJECTION);
		glLoadIdentity();
		glOrtho(0, width, height, 0, -1, 1);

		renderer.renderWorld();

		glfwSwapBuffers(window);
	}

	public static void main(String[] args) {
		new GuiMain().run(args[0]);
	}

}