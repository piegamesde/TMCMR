package togos.minecraft.maprend.core;

import java.io.File;

public class Region {

	public int	rx, rz;
	public File	regionFile;
	public File	imageFile;

	public Region() {

	}

	public Region(int rx, int rz, File regionFile, File imageFile) {
		this.rx = rx;
		this.rz = rz;
		this.regionFile = regionFile;
		this.imageFile = imageFile;
	}
}