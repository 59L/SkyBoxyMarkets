package dev.skyboxy.market;

import com.sk89q.jnbt.ByteArrayTag;
import com.sk89q.jnbt.CompoundTag;
import com.sk89q.jnbt.IntTag;
import com.sk89q.jnbt.NBTInputStream;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StyleTemplateAssetTest {
    @Test
    void shouldPreserveTheNormalBoothSchematicPaletteAndDimensions() throws Exception {
        Asset asset = readAsset("mainMarket2.schem");

        assertEquals(11, asset.width());
        assertEquals(11, asset.height());
        assertEquals(11, asset.length());
        assertEquals(List.of(-6, -3, 5), asset.offset());
        assertEquals(Map.of(
                "minecraft:air", 810,
                "minecraft:gray_concrete", 401,
                "minecraft:white_concrete", 90,
                "minecraft:fire_coral_block", 11,
                "minecraft:moss_block", 10,
                "minecraft:nether_wart_block", 9), palette(asset));
    }

    @Test
    void shouldPreserveThePremiumCornerSchematicPaletteAndDimensions() throws Exception {
        Asset asset = readAsset("marketboothCorner2.schem");

        assertEquals(9, asset.width());
        assertEquals(11, asset.height());
        assertEquals(8, asset.length());
        assertEquals(List.of(-5, -1, -7), asset.offset());
        assertEquals(Map.of(
                "minecraft:air", 504,
                "minecraft:gray_concrete", 231,
                "minecraft:white_concrete", 56,
                "minecraft:dead_tube_coral_block", 1), palette(asset));
    }

    @Test
    void shouldMatchBothBundledStructuresInEveryRotationWithinEachComparisonSlice() throws Exception {
        for (Asset asset : List.of(readAsset("mainMarket2.schem"), readAsset("marketboothCorner2.schem"))) {
            TemplateMatcher matcher = matcher(asset);
            for (int rotation = 0; rotation < 4; rotation++) {
                Map<Long, String> world = place(matcher, rotation);
                TemplateMatcher.MatchCursor cursor = matcher.cursor(0, 0, 0, rotation, true);
                int comparisons = 0;
                while (!cursor.done()) {
                    int used = cursor.advance((x, y, z) -> world.getOrDefault(key(x, y, z), "minecraft:air"), 17);
                    assertTrue(used <= 17);
                    assertTrue(used > 0 || cursor.done());
                    comparisons += used;
                }
                assertTrue(cursor.matches());
                assertEquals(asset.cells().size(), comparisons);
                assertEquals(matcher.rotatedWidth(rotation), rotation % 2 == 0 ? asset.width() : asset.length());
                assertEquals(matcher.rotatedLength(rotation), rotation % 2 == 0 ? asset.length() : asset.width());
            }
        }
    }

    @Test
    void shouldRejectInvalidRotationsInsteadOfSilentlyTreatingThemAsZero() throws Exception {
        TemplateMatcher matcher = matcher(readAsset("mainMarket2.schem"));

        assertThrows(IllegalArgumentException.class, () -> matcher.cursor(0, 0, 0, 4, true));
        assertThrows(IllegalArgumentException.class, () -> matcher.isStructural(0, 0, 0, -1));
    }

    @Test
    void shouldRotateDirectionalBlockStatesBeforeMatching() {
        TemplateMatcher matcher = new TemplateMatcher(1, 1, 1, List.of(new TemplateMatcher.Cell(
                0, 0, 0, "oak_stairs", false,
                "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]")));
        TemplateMatcher.MatchCursor eastFacing = matcher.cursor(0, 0, 0, 1, true);

        eastFacing.advance((x, y, z) -> "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]", 1);

        assertTrue(eastFacing.matches());
        TemplateMatcher.MatchCursor wrongFacing = matcher.cursor(0, 0, 0, 1, true);
        wrongFacing.advance((x, y, z) -> "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]", 1);
        assertFalse(wrongFacing.matches());
        assertEquals("oak_log[axis=z]", TemplateMatcher.rotateBlockData("minecraft:oak_log[axis=x]", 1));
        assertEquals("oak_log[axis=x]", TemplateMatcher.rotateBlockData("minecraft:oak_log[axis=x]", 2));
        assertEquals("rail[shape=east_west]", TemplateMatcher.rotateBlockData("minecraft:rail[shape=north_south]", 1));
        assertEquals("oak_fence[east=up,north=none,south=none,west=up]",
                TemplateMatcher.rotateBlockData("minecraft:oak_fence[north=up,east=none,south=up,west=none]", 1));
    }

    @Test
    void shouldCountIgnoredAirCellsAgainstThePerTickTemplateBudget() {
        TemplateMatcher matcher = new TemplateMatcher(3, 1, 1, List.of(
                new TemplateMatcher.Cell(0, 0, 0, "air", false),
                new TemplateMatcher.Cell(1, 0, 0, "cave_air", false),
                new TemplateMatcher.Cell(2, 0, 0, "stone", false)));
        TemplateMatcher.MatchCursor cursor = matcher.cursor(0, 0, 0, 0, false);
        int[] worldLookups = {0};

        assertEquals(1, cursor.advance((x, y, z) -> { worldLookups[0]++; return "minecraft:stone"; }, 1));
        assertEquals(0, worldLookups[0]);
        assertFalse(cursor.done());
        assertEquals(1, cursor.advance((x, y, z) -> { worldLookups[0]++; return "minecraft:stone"; }, 1));
        assertEquals(0, worldLookups[0]);
        assertEquals(1, cursor.advance((x, y, z) -> { worldLookups[0]++; return "minecraft:stone"; }, 1));
        assertEquals(1, worldLookups[0]);
        assertTrue(cursor.matches());
    }

    @Test
    void shouldKeepEveryStructuralCellInsideItsRotatedMask() throws Exception {
        for (Asset asset : List.of(readAsset("mainMarket2.schem"), readAsset("marketboothCorner2.schem"))) {
            TemplateMatcher matcher = matcher(asset);
            for (int rotation = 0; rotation < 4; rotation++) {
                Set<Long> mappedCells = new HashSet<>();
                for (TemplateMatcher.Cell cell : matcher.cells()) {
                    int x = matcher.rotateX(cell.x(), cell.z(), rotation);
                    int z = matcher.rotateZ(cell.x(), cell.z(), rotation);
                    assertTrue(x >= 0 && x < matcher.rotatedWidth(rotation));
                    assertTrue(z >= 0 && z < matcher.rotatedLength(rotation));
                    assertTrue(mappedCells.add(key(x, cell.y(), z)));
                    assertEquals(!isAir(cell.material()), matcher.isStructural(x, cell.y(), z, rotation));
                }
                assertEquals(matcher.cells().size(), mappedCells.size());
                assertFalse(matcher.isStructural(-1, 0, 0, rotation));
            }
        }
    }

    private static Asset readAsset(String file) throws Exception {
        Path path = Path.of("src/main/resources/schematics", file);
        CompoundTag root;
        try (InputStream compressed = Files.newInputStream(path);
             InputStream gzip = new GZIPInputStream(compressed);
             NBTInputStream nbt = new NBTInputStream(gzip)) {
            root = (CompoundTag) nbt.readNamedTag().getTag();
        }
        CompoundTag schematic = (CompoundTag) root.getValue().get("Schematic");
        int width = schematic.getShort("Width");
        int height = schematic.getShort("Height");
        int length = schematic.getShort("Length");
        int[] offsetValues = schematic.getIntArray("Offset");
        List<Integer> offset = List.of(offsetValues[0], offsetValues[1], offsetValues[2]);

        CompoundTag blocks = (CompoundTag) schematic.getValue().get("Blocks");
        CompoundTag palette = (CompoundTag) blocks.getValue().get("Palette");
        Map<Integer, String> byId = new HashMap<>();
        for (Map.Entry<String, com.sk89q.jnbt.Tag<?, ?>> entry : palette.getValue().entrySet()) {
            int id = ((IntTag) entry.getValue()).getValue();
            byId.put(id, entry.getKey());
        }

        byte[] encoded = ((ByteArrayTag) blocks.getValue().get("Data")).getValue();
        List<String> cells = decodeVarInts(encoded, byId, width * height * length);
        return new Asset(width, height, length, offset, cells);
    }

    private static List<String> decodeVarInts(byte[] encoded, Map<Integer, String> palette, int expectedCells) {
        ArrayList<String> cells = new ArrayList<>(expectedCells);
        int value = 0;
        int shift = 0;
        for (byte item : encoded) {
            value |= (item & 0x7f) << shift;
            if ((item & 0x80) == 0) {
                String material = palette.get(value);
                if (material == null) throw new IllegalArgumentException("Unknown schematic palette index " + value);
                cells.add(material);
                value = 0;
                shift = 0;
            } else {
                shift += 7;
                if (shift >= 35) throw new IllegalArgumentException("Invalid schematic varint");
            }
        }
        if (shift != 0 || cells.size() != expectedCells) {
            throw new IllegalArgumentException("Schematic contains " + cells.size() + " cells; expected " + expectedCells);
        }
        return List.copyOf(cells);
    }

    private static TemplateMatcher matcher(Asset asset) {
        ArrayList<TemplateMatcher.Cell> cells = new ArrayList<>(asset.cells().size());
        for (int y = 0; y < asset.height(); y++) {
            for (int z = 0; z < asset.length(); z++) {
                for (int x = 0; x < asset.width(); x++) {
                    String material = asset.cells().get((y * asset.length() + z) * asset.width() + x);
                    cells.add(new TemplateMatcher.Cell(x, y, z, material, "minecraft:gray_concrete".equals(material)));
                }
            }
        }
        return new TemplateMatcher(asset.width(), asset.height(), asset.length(), cells);
    }

    private static Map<Long, String> place(TemplateMatcher matcher, int rotation) {
        Map<Long, String> world = new HashMap<>();
        for (TemplateMatcher.Cell cell : matcher.cells()) {
            String material = cell.wall() ? "minecraft:white_concrete" : cell.material();
            world.put(key(matcher.rotateX(cell.x(), cell.z(), rotation), cell.y(), matcher.rotateZ(cell.x(), cell.z(), rotation)), material);
        }
        return world;
    }

    private static Map<String, Integer> palette(Asset asset) {
        Map<String, Integer> palette = new HashMap<>();
        for (String material : asset.cells()) palette.merge(material, 1, Integer::sum);
        return palette;
    }

    private static boolean isAir(String material) {
        return material.equals("minecraft:air") || material.equals("minecraft:cave_air") || material.equals("minecraft:void_air");
    }

    private static long key(int x, int y, int z) {
        return ((long) x << 42) ^ ((long) y << 21) ^ z;
    }

    private record Asset(int width, int height, int length, List<Integer> offset, List<String> cells) {}
}
