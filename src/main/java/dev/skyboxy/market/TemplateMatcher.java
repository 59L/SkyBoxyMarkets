package dev.skyboxy.market;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure structural matching and rotation logic shared by template loading and incremental scans. */
final class TemplateMatcher {
    record Cell(int x, int y, int z, String material, boolean wall, String blockData) {
        Cell(int x,int y,int z,String material,boolean wall){this(x,y,z,material,wall,material);}
    }

    @FunctionalInterface
    interface BlockLookup {
        String materialAt(int x, int y, int z);
    }

    private final int width;
    private final int height;
    private final int length;
    private final List<Cell> cells;

    TemplateMatcher(int width, int height, int length, List<Cell> cells) {
        long expected = (long) width * height * length;
        if (width <= 0 || height <= 0 || length <= 0 || expected != cells.size()) {
            throw new IllegalArgumentException("Template cells do not match positive dimensions");
        }
        this.width = width;
        this.height = height;
        this.length = length;
        this.cells = List.copyOf(cells);
    }

    List<Cell> cells() {
        return cells;
    }

    int rotatedWidth(int rotation) {
        checkRotation(rotation);
        return rotation % 2 == 0 ? width : length;
    }

    int rotatedLength(int rotation) {
        checkRotation(rotation);
        return rotation % 2 == 0 ? length : width;
    }

    int rotateX(int x, int z, int rotation) {
        checkRotation(rotation);
        return switch (rotation) {
            case 1 -> length - 1 - z;
            case 2 -> width - 1 - x;
            case 3 -> z;
            default -> x;
        };
    }

    int rotateZ(int x, int z, int rotation) {
        checkRotation(rotation);
        return switch (rotation) {
            case 1 -> x;
            case 2 -> length - 1 - z;
            case 3 -> width - 1 - x;
            default -> z;
        };
    }

    boolean isStructural(int rotatedX, int y, int rotatedZ, int rotation) {
        checkRotation(rotation);
        int sourceX;
        int sourceZ;
        switch (rotation) {
            case 1 -> {
                sourceX = rotatedZ;
                sourceZ = length - 1 - rotatedX;
            }
            case 2 -> {
                sourceX = width - 1 - rotatedX;
                sourceZ = length - 1 - rotatedZ;
            }
            case 3 -> {
                sourceX = width - 1 - rotatedZ;
                sourceZ = rotatedX;
            }
            default -> {
                sourceX = rotatedX;
                sourceZ = rotatedZ;
            }
        }
        if (sourceX < 0 || sourceX >= width || y < 0 || y >= height || sourceZ < 0 || sourceZ >= length) return false;
        return !isAir(cells.get((y * length + sourceZ) * width + sourceX).material());
    }

    MatchCursor cursor(int x, int y, int z, int rotation, boolean checkAir) {
        checkRotation(rotation);
        return new MatchCursor(x, y, z, rotation, checkAir);
    }

    static boolean isAir(String material) {
        return material.equals("air") || material.endsWith(":air")
                || material.equals("cave_air") || material.endsWith(":cave_air")
                || material.equals("void_air") || material.endsWith(":void_air");
    }

    private static boolean expected(String actual, Cell cell, boolean checkAir, int rotation) {
        ParsedBlockData expected = parse(rotateBlockData(cell.blockData(), rotation));
        ParsedBlockData found = parse(actual);
        if (cell.wall()) return found.material().endsWith("_concrete") && !found.material().endsWith("_concrete_powder");
        if (isAir(cell.material()) && !checkAir) return true;
        return normalizeMaterial(cell.material()).equals(found.material()) && expected.properties().equals(found.properties());
    }

    static String rotateBlockData(String blockData,int rotation){
        checkRotation(rotation);
        ParsedBlockData parsed=parse(blockData);Map<String,String> properties=new TreeMap<>(parsed.properties());
        for(int i=0;i<rotation;i++){
            String facing=properties.get("facing");if(facing!=null)properties.put("facing",rotateDirection(facing));
            String shape=properties.get("shape");if(shape!=null)properties.put("shape",rotateDirections(shape));
            Map<String,String> connections=new TreeMap<>();
            for(String direction:List.of("north","east","south","west")){
                String value=properties.get(direction);
                if(value!=null)connections.put(rotateDirection(direction),value);
            }
            for(String direction:List.of("north","east","south","west"))properties.remove(direction);
            properties.putAll(connections);
            String facingRotation=properties.get("rotation");
            if(facingRotation!=null)try{properties.put("rotation",Integer.toString((Integer.parseInt(facingRotation)+4)%16));}catch(NumberFormatException ignored){}
        }
        if(rotation%2==1){String axis=properties.get("axis");if(axis!=null)properties.put("axis",axis.equals("x")?"z":axis.equals("z")?"x":axis);}
        if(properties.isEmpty())return parsed.material();
        return parsed.material()+"["+properties.entrySet().stream().map(e->e.getKey()+"="+e.getValue()).reduce((a,b)->a+","+b).orElse("")+"]";
    }

    private record ParsedBlockData(String material,Map<String,String> properties) {}
    private static final Pattern DIRECTION=Pattern.compile("north|east|south|west");
    private static ParsedBlockData parse(String data){
        String raw=data.toLowerCase(java.util.Locale.ROOT).trim();int open=raw.indexOf('[');
        String material=normalizeMaterial(open<0?raw:raw.substring(0,open));Map<String,String> properties=new TreeMap<>();
        if(open>=0&&raw.endsWith("]")){
            String body=raw.substring(open+1,raw.length()-1);
            for(String value:body.split(",")){int equals=value.indexOf('=');if(equals>0)properties.put(value.substring(0,equals).trim(),value.substring(equals+1).trim());}
        }
        return new ParsedBlockData(material,Map.copyOf(properties));
    }
    private static String normalizeMaterial(String material){return material.startsWith("minecraft:")?material.substring("minecraft:".length()):material;}
    private static String rotateDirection(String direction){return switch(direction){case "north"->"east";case "east"->"south";case "south"->"west";case "west"->"north";default->direction;};}
    private static String rotateDirections(String shape){
        Matcher matcher=DIRECTION.matcher(shape);StringBuffer result=new StringBuffer();
        while(matcher.find())matcher.appendReplacement(result,rotateDirection(matcher.group()));
        matcher.appendTail(result);return result.toString();
    }

    private static void checkRotation(int rotation) {
        if (rotation < 0 || rotation > 3) throw new IllegalArgumentException("Rotation must be 0, 1, 2, or 3");
    }

    final class MatchCursor {
        private final int originX;
        private final int originY;
        private final int originZ;
        private final int rotation;
        private final boolean checkAir;
        private int nextCell;
        private int comparisons;
        private boolean done;
        private boolean matches = true;

        private MatchCursor(int originX, int originY, int originZ, int rotation, boolean checkAir) {
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.rotation = rotation;
            this.checkAir = checkAir;
        }

        /** Examines no more than {@code maximumComparisons} template cells and retains its place. */
        int advance(BlockLookup world, int maximumComparisons) {
            if (maximumComparisons < 0) throw new IllegalArgumentException("Comparison limit cannot be negative");
            int used = 0;
            while (!done && nextCell < cells.size()) {
                if (used == maximumComparisons) break;
                Cell cell = cells.get(nextCell++);
                used++;
                if (isAir(cell.material()) && !checkAir) continue;
                String actual = world.materialAt(
                        originX + rotateX(cell.x(), cell.z(), rotation),
                        originY + cell.y(),
                        originZ + rotateZ(cell.x(), cell.z(), rotation));
                comparisons++;
                if (!expected(actual, cell, checkAir, rotation)) {
                    matches = false;
                    done = true;
                }
            }
            if (nextCell >= cells.size()) done = true;
            return used;
        }

        boolean done() { return done; }
        boolean matches() { return done && matches; }
        int comparisons() { return comparisons; }
    }
}
