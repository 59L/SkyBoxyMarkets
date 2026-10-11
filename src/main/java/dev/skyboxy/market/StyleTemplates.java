package dev.skyboxy.market;

import com.sk89q.worldedit.bukkit.BukkitWorld;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.util.SideEffectSet;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.world.block.BaseBlock;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.inventory.Inventory;
import java.io.*;
import java.util.*;

/** WorldEdit schematics: no marker blocks; templates are tested at 0/90/180/270 degrees. */
final class StyleTemplates {
    record Cell(int x, int y, int z, Material material, boolean wall, String blockData) {
        Cell(int x,int y,int z,Material material,boolean wall){this(x,y,z,material,wall,material.name().toLowerCase(Locale.ROOT));}
    }
    record Sample(int x, int y, int z, Material material, boolean wall) {}
    record Match(MarketStyle style, Template template, int x, int y, int z, int rotation) {}
    static final class Template {
        @FunctionalInterface
        private interface MaterialSource { Material at(int x, int y, int z); }
        @FunctionalInterface
        private interface BlockDataSource { String at(int x,int y,int z); }

        final MarketStyle style;
        final Clipboard clipboard;
        final int width, height, length;
        final List<Cell> cells, walls;
        final Cell anchor;
        final TemplateMatcher matcher;
        Template(MarketStyle style, Clipboard cb) {
            this(style, cb, cb.getDimensions().x(), cb.getDimensions().y(), cb.getDimensions().z(), clipboardSource(style, cb),clipboardBlockDataSource(cb));
        }
        private Template(MarketStyle style, Clipboard clipboard, int width, int height, int length, MaterialSource source,BlockDataSource dataSource) {
            if(width<=0||height<=0||length<=0)throw new IllegalArgumentException("Template dimensions must be positive");
            this.style=style;this.clipboard=clipboard;
            this.width=width;this.height=height;this.length=length;
            ArrayList<Cell> all=new ArrayList<>(), w=new ArrayList<>();
            HashMap<Material,Integer> counts=new HashMap<>();
            for(int yy=0;yy<height;yy++) for(int zz=0;zz<length;zz++) for(int xx=0;xx<width;xx++){
                Material material=source.at(xx,yy,zz);
                if(material==null) throw new IllegalArgumentException("Unrecognized schematic block type at "+xx+","+yy+","+zz+" in "+style.schematic);
                boolean wall=style.isWall(material);
                Cell c=new Cell(xx,yy,zz,material,wall,dataSource.at(xx,yy,zz));
                all.add(c); if(wall)w.add(c);
                if(!material.isAir() && !wall) counts.merge(material,1,Integer::sum);
            }
            cells=List.copyOf(all);walls=List.copyOf(w);
            matcher=new TemplateMatcher(width,height,length,all.stream()
                    .map(c->new TemplateMatcher.Cell(c.x,c.y,c.z,c.material.name().toLowerCase(Locale.ROOT),c.wall,c.blockData)).toList());
            // An uncommon fixed block makes a much better scan anchor than air or gray walls.
            anchor=all.stream().filter(c->!c.material.isAir()&&!c.wall)
                    .min(Comparator.comparingInt(c->counts.getOrDefault(c.material,999999))).orElseThrow(()->new IllegalArgumentException("Template has no fixed anchor: "+style.id));
        }
        private static MaterialSource clipboardSource(MarketStyle style, Clipboard clipboard) {
            BlockVector3 minimum=clipboard.getMinimumPoint();
            return (x,y,z)->{
                String id=clipboard.getBlock(minimum.add(x,y,z)).getBlockType().id();
                Material material=Material.matchMaterial(id);
                if(material==null)throw new IllegalArgumentException("Unrecognized schematic block type "+id+" in "+style.schematic);
                return material;
            };
        }
        private static BlockDataSource clipboardBlockDataSource(Clipboard clipboard){
            BlockVector3 minimum=clipboard.getMinimumPoint();
            return (x,y,z)->clipboard.getBlock(minimum.add(x,y,z)).getAsString();
        }
        int rotatedWidth(int r) {return matcher.rotatedWidth(r);}
        int rotatedLength(int r) {return matcher.rotatedLength(r);}
        int rotateX(int x,int z,int r){return matcher.rotateX(x,z,r);}
        int rotateZ(int x,int z,int r){return matcher.rotateZ(x,z,r);}
        boolean matches(World w,int x,int y,int z,int r,boolean checkAir) {
            if(y<w.getMinHeight()||y+height>w.getMaxHeight())return false;
            TemplateMatcher.MatchCursor cursor=matcher.cursor(x,y,z,r,checkAir);
            cursor.advance((bx,by,bz)->w.getBlockAt(bx,by,bz).getBlockData().getAsString(),Integer.MAX_VALUE);
            return cursor.matches();
        }
        boolean isStructural(int lx,int ly,int lz,int r) {
            // The entire original shell and template non-air content is immutable to renters;
            // decorative nonstructural interiors may be placed into original air cells only.
            return matcher.isStructural(lx,ly,lz,r);
        }
        void recolor(Booth b,Material newWall) {
            World w=Bukkit.getWorld(b.world);if(w==null)return;
            for(Cell c:walls){
                Block block=w.getBlockAt(b.x+rotateX(c.x,c.z,b.rotation),b.y+c.y,b.z+rotateZ(c.x,c.z,b.rotation));
                if(block.getType().name().endsWith("_CONCRETE")) block.setType(newWall,false);
            }
        }
        void restore(Booth b) throws Exception {
            int cursor=0;
            while(cursor<cells.size())cursor=restoreBatch(b,cursor,cells.size());
        }
        int restoreBatch(Booth b,int cursor,int maxBlocks) throws Exception {
            if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Template restoration must run on the primary thread");
            if(cursor<0||cursor>cells.size()||maxBlocks<1)throw new IllegalArgumentException("Invalid restore batch bounds");
            World bw=Bukkit.getWorld(b.world);
            if(bw==null)throw new IOException("World not loaded "+b.world);
            // The installed FAWE 2.15 adapter can fail while flushing its async queue on Paper
            // 26.2. Write the already-loaded clipboard through WorldEdit's Bukkit world adapter
            // so reset remains synchronous and idempotent instead of reporting a false success.
            BukkitWorld target=BukkitAdapter.asBukkitWorld(BukkitAdapter.adapt(bw));
            if(target==null)throw new IOException("WorldEdit could not adapt world "+b.world);
            StructureRotation rotation=switch(b.rotation){
                case 1->StructureRotation.CLOCKWISE_90;
                case 2->StructureRotation.CLOCKWISE_180;
                case 3->StructureRotation.COUNTERCLOCKWISE_90;
                default->StructureRotation.NONE;
            };
            BlockVector3 min=clipboard.getMinimumPoint();
            int end=Math.min(cells.size(),cursor+maxBlocks);
            for(int index=cursor;index<end;index++){
                Cell cell=cells.get(index);
                BlockData data=Bukkit.createBlockData(cell.blockData);
                data.rotate(rotation);
                BaseBlock original=clipboard.getFullBlock(min.add(cell.x,cell.y,cell.z));
                BaseBlock transformed=BukkitAdapter.adapt(data).toBaseBlock(original.getNbtReference());
                BlockVector3 destination=BlockVector3.at(b.x+rotateX(cell.x,cell.z,b.rotation),b.y+cell.y,
                        b.z+rotateZ(cell.x,cell.z,b.rotation));
                Block targetBlock=bw.getBlockAt(destination.x(),destination.y(),destination.z());
                if(targetBlock.getState() instanceof Container oldContainer){
                    // Snapshotting has durably journaled these contents. Clear them before replacing
                    // the tile block so Bukkit/WorldEdit cannot spawn duplicate item entities.
                    Inventory oldInventory=oldContainer.getInventory();
                    if(oldInventory!=null)oldInventory.clear();
                }
                target.setBlock(destination,transformed,SideEffectSet.defaults());
                BlockData actual=targetBlock.getBlockData();
                if(!actual.getAsString().equals(data.getAsString()))
                    throw new IOException("WorldEdit did not restore template block at "+destination+
                            " (expected "+data.getAsString()+", found "+actual.getAsString()+")");
            }
            return end;
        }
    }
    final Map<String,Template> templates=new LinkedHashMap<>();
    void load(SkyBoxyMarket plugin, Collection<MarketStyle> styles){
        templates.clear();
        File dir=new File(plugin.getDataFolder(),"schematics");dir.mkdirs();
        for(MarketStyle style:styles){
            File file=new File(dir,style.schematic);
            if(!file.getParentFile().equals(dir)) {plugin.getLogger().warning("Schematic subpaths not supported: "+style.schematic);continue;}
            if(!file.exists()){plugin.getLogger().warning("Missing schematic: "+file);continue;}
            try {
                ClipboardFormat format=ClipboardFormats.findByFile(file);
                if(format==null)throw new IOException("Unrecognized schematic format");
                try(InputStream in=new FileInputStream(file); var reader=format.getReader(in)){
                    Template t=new Template(style,reader.read()); templates.put(style.id,t);
                    plugin.getLogger().info("Loaded style "+style.id+" = "+t.width+"x"+t.height+"x"+t.length+"; fixed anchor "+t.anchor.material());
                }
            }catch(Exception ex){plugin.getLogger().severe("Cannot load "+style.schematic+": "+ex.getMessage());}
        }
    }
    Template get(String style) {return templates.get(style);}
    Collection<Template> enabled(){return templates.values().stream().filter(t->t.style.enabled).sorted(Comparator.comparingInt((Template t)->t.style.priority).reversed()).toList();}
}
