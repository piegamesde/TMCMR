
package togos.minecraft.maprend.core.io;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.InflaterInputStream;
import org.joml.Vector2i;
import com.flowpowered.nbt.*;
import com.flowpowered.nbt.stream.NBTInputStream;

public class RegionFile {

	private RandomAccessFile	raf;
	private Map<Vector2i, Long>	chunkPositions;

	public RegionFile(File file)
			throws IOException {
		raf = new RandomAccessFile(file, "r");

		// TODO optimize by buffering reading
		chunkPositions = new HashMap<Vector2i, Long>();
		for (int z = 0; z < 32; z++)
			for (int x = 0; x < 32; x++) {
				// byte 1-3: chunk position in 4kb sectors from file start
				long chunkPos = (raf.read() << 16 | raf.read() << 8 | raf.read());
				Vector2i v = new Vector2i(x, z);
				if (chunkPos > 0)
					chunkPositions.put(v, chunkPos * 4096);
				// byte 4: chunk length in sectors
				raf.skipBytes(1);// skip it
			}
	}

	// public void open() throws IOException {
	//
	// }

	// Relative coords in region file
	public CompoundTag loadChunk(Vector2i chunkCoordsRegion) throws IOException {
		if (!chunkPositions.containsKey(chunkCoordsRegion))
			return null;
		raf.seek(chunkPositions.get(chunkCoordsRegion));
		int chunkLength = (raf.read() << 24 | raf.read() << 16 | raf.read() << 8 | raf.read()) - 1;
		int compression = raf.read();// skip compression
		byte[] buf = new byte[chunkLength];
		raf.read(buf);
		try (NBTInputStream nbtIn = new NBTInputStream(new InflaterInputStream(new ByteArrayInputStream(buf)),
				false)) {// NBT section here
			CompoundTag tag = (CompoundTag) ((CompoundTag) nbtIn.readTag()).getValue().get("Level");
			nbtIn.close();
			return tag;
		}
	}

	// TODO make this less ugly. Maybe inline this, since it's only called from one place?
	/**
	 * @param levelTag
	 * @param maxSectionCount
	 * @param sectionBlockIds block IDs for non-empty sections will be written to sectionBlockIds[sectionIndex][blockIndex]
	 * @param sectionBlockData block data for non-empty sections will be written to sectionBlockData[sectionIndex][blockIndex]
	 * @param sectionsUsed sectionsUsed[sectionIndex] will be set to true for non-empty sections
	 */
	@SuppressWarnings("unchecked")
	public static void loadChunkData(CompoundTag levelTag, int maxSectionCount, short[][] sectionBlockIds, byte[][] sectionBlockData, boolean[] sectionsUsed, byte[] biomeIds) {
		for (int i = 0; i < maxSectionCount; ++i)
			sectionsUsed[i] = false;
		CompoundMap tagMap = levelTag.getValue();

		ByteArrayTag biomesTag = (ByteArrayTag) tagMap.get("Biomes");
		if (biomesTag != null)
			System.arraycopy(biomesTag.getValue(), 0, biomeIds, 0, 16 * 16);
		else
			for (int i = 0; i < 16 * 16; i++)
				biomeIds[i] = -1;

		if (tagMap.containsKey("Sections"))
			for (CompoundTag sectionInfo : ((ListTag<CompoundTag>) tagMap.get("Sections")).getValue()) {
				int sectionIndex = ((ByteTag) sectionInfo.getValue().get("Y")).getValue().intValue();
				byte[] blockIdsLow = ((ByteArrayTag) sectionInfo.getValue().get("Blocks")).getValue();
				byte[] blockData = ((ByteArrayTag) sectionInfo.getValue().get("Data")).getValue();
				ByteArrayTag addTag = (ByteArrayTag) sectionInfo.getValue().get("Add");
				byte[] blockAdd = null;
				if (addTag != null)
					blockAdd = addTag.getValue();
				short[] destSectionBlockIds = sectionBlockIds[sectionIndex];
				byte[] destSectionData = sectionBlockData[sectionIndex];
				sectionsUsed[sectionIndex] = true;
				for (int y = 0; y < 16; ++y) {
					for (int z = 0; z < 16; ++z) {
						for (int x = 0; x < 16; ++x) {
							int index = y * 256 + z * 16 + x;
							short blockType = (short) (blockIdsLow[index] & 0xFF);
							if (blockAdd != null) {
								blockType |= nybble(blockAdd, index) << 8;
							}
							destSectionBlockIds[index] = blockType;
							destSectionData[index] = nybble(blockData, index);
						}
					}
				}
			}
	}

	/**
	 * Extract a 4-bit integer from a byte in an array, where the first nybble in each byte (even nybble indexes) occupies the lower 4 bits and the second (odd
	 * nybble indexes) occupies the high bits.
	 * 
	 * @param arr the source array
	 * @param index the index (in nybbles) of the desired 4 bits
	 * @return the desired 4 bits as the lower bits of a byte
	 */
	protected static final byte nybble(byte[] arr, int index) {
		return (byte) ((index % 2 == 0 ? arr[index / 2] : (arr[index / 2] >> 4)) & 0x0F);
	}

	public void close() throws IOException {
		raf.close();
		chunkPositions.clear();
	}

	public RandomAccessFile getFile() {
		return raf;
	}
}