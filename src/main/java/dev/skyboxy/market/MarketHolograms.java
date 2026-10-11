package dev.skyboxy.market;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** TextDisplay and invisible Interaction, no sign or external hologram plugin needed. */
final class MarketHolograms implements Listener {
    private final SkyBoxyMarket p;
    private final NamespacedKey key;
    MarketHolograms(SkyBoxyMarket p){this.p=p;key=new NamespacedKey(p,"booth_hologram");
        Bukkit.getScheduler().runTaskTimer(p,()->refreshText(),1200,1200);
    }
    boolean isMarketEntity(Entity e){return e.getPersistentDataContainer().has(key,PersistentDataType.STRING);}
    private String read(Entity e){return e.getPersistentDataContainer().get(key,PersistentDataType.STRING);}
    void refreshAll(){
        for(World w:Bukkit.getWorlds())for(Entity e:new ArrayList<>(w.getEntities()))if(isMarketEntity(e))e.remove();
        for(Booth b:p.booths)refresh(b);
    }
    void removeAll(){for(World w:Bukkit.getWorlds())for(Entity e:new ArrayList<>(w.getEntities()))if(isMarketEntity(e))e.remove();}
    void refresh(Booth b){
        if(p.styles==null)return;
        for(World w:Bukkit.getWorlds())for(Entity e:new ArrayList<>(w.getEntities()))if(isMarketEntity(e)&&b.id.equals(read(e)))e.remove();
        MarketStyle style=p.style(b);if(style==null)return;
        Location l=style.offset(b,style.hologram);if(l==null||!l.getWorld().isChunkLoaded(l.getBlockX()>>4,l.getBlockZ()>>4))return;
        TextDisplay display=l.getWorld().spawn(l,TextDisplay.class, e->{
            e.getPersistentDataContainer().set(key,PersistentDataType.STRING,b.id);
            e.setBillboard(Display.Billboard.CENTER);
            e.setSeeThrough(true);e.setShadowed(true);e.setPersistent(false);
            e.setText(text(b,style));
        });
        l.getWorld().spawn(l.clone().subtract(0,0.55,0),Interaction.class,e->{
            e.getPersistentDataContainer().set(key,PersistentDataType.STRING,b.id);
            e.setInteractionWidth(1.3f);e.setInteractionHeight(1.6f);e.setResponsive(true);e.setPersistent(false);
        });
    }
    private String text(Booth b,MarketStyle style){
        if(b.resetting)return SkyBoxyMarket.c("&cMarket being restored");
        if(b.suspended)return SkyBoxyMarket.c("&cMarket suspended");
        if(b.owner==null)return SkyBoxyMarket.c(style.name+"\n&aAVAILABLE\n&f$"+String.format("%,.0f",style.price)+" &7/ "+style.durationHours+"h\n&eRight-click to rent");
        String name=Bukkit.getOfflinePlayer(b.owner).getName();
        long mins=Math.max(0,TimeUnit.MILLISECONDS.toMinutes(b.expiry-System.currentTimeMillis()));
        return SkyBoxyMarket.c(style.name+"\n&f"+(name==null?"Player":name)+"\n&e"+(mins/60)+"h "+(mins%60)+"m left\n&7Right-click to view");
    }
    private void refreshText(){
        for(World w:Bukkit.getWorlds())for(TextDisplay e:w.getEntitiesByClass(TextDisplay.class)){
            String id=read(e);if(id==null)continue;
            Booth b=p.byId(id);if(b!=null&&p.style(b)!=null)e.setText(text(b,p.style(b)));
        }
    }
    @EventHandler public void chunkLoad(ChunkLoadEvent event){
        Bukkit.getScheduler().runTaskLater(p,()->{
            for(Booth b:p.booths){
                Location center=b.center();
                if(center==null||center.getChunk().getX()!=event.getChunk().getX()||center.getChunk().getZ()!=event.getChunk().getZ()
                        ||!b.world.equals(event.getWorld().getName()))continue;
                boolean has=event.getChunk().getEntities().length>0&&Arrays.stream(event.getChunk().getEntities())
                        .anyMatch(e->b.id.equals(read(e))&&e instanceof TextDisplay);
                if(!has)refresh(b);
            }
        },2);
    }
    @EventHandler public void interact(PlayerInteractEntityEvent event){
        if(!isMarketEntity(event.getRightClicked()))return;
        event.setCancelled(true);
        Booth b=p.byId(read(event.getRightClicked()));if(b==null)return;
        p.menus.detail(event.getPlayer(),b,false);
    }
}
