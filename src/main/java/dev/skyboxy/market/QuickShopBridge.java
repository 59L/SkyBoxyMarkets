package dev.skyboxy.market;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import java.lang.reflect.Method;
import java.util.*;

/** Optional reflection-only bridge against the QuickShop-Hikari 6.3 public API. */
final class QuickShopBridge implements ShopProvider {
    private final SkyBoxyMarket p;
    private final Object shopManager;
    private final Class<?> shopInterface;
    private final Status status;
    QuickShopBridge(SkyBoxyMarket p){
        this.p=p;Object mgr=null;Class<?> clazz=null;Status st=Status.ABSENT;
        try{
            Plugin quick=Arrays.stream(Bukkit.getPluginManager().getPlugins())
                    .filter(t->t.getName().toLowerCase(Locale.ROOT).contains("quickshop")&&t.isEnabled()).findFirst().orElse(null);
            if(quick!=null){
                ClassLoader loader=quick.getClass().getClassLoader();
                Class<?> api=Class.forName("com.ghostchu.quickshop.api.QuickShopAPI",true,loader);
                Class<?> shopType=Class.forName("com.ghostchu.quickshop.api.shop.Shop",true,loader);
                Object inst=api.getMethod("getInstance").invoke(null);
                Object candidateManager=api.getMethod("getShopManager").invoke(inst);
                if(candidateManager==null)throw new NoSuchMethodException("QuickShop API returned no shop manager");
                candidateManager.getClass().getMethod("getShopIncludeAttached",Location.class);
                candidateManager.getClass().getMethod("deleteShop",shopType);
                shopType.getMethod("getLocation");
                Class<?> success=Class.forName("com.ghostchu.quickshop.api.event.economy.ShopSuccessPurchaseEvent",true,loader);
                if(!Event.class.isAssignableFrom(success))throw new NoSuchMethodException("QuickShop success-purchase type is not a Bukkit event");
                success.getMethod("getShop");success.getMethod("getTotal");
                @SuppressWarnings("unchecked") Class<? extends Event> eventClass=(Class<? extends Event>)success;
                Listener listener=new Listener() {};
                Bukkit.getPluginManager().registerEvent(eventClass,listener,EventPriority.MONITOR,(ignored,event)->recordSale(event),p,true);
                mgr=candidateManager;clazz=shopType;st=Status.AVAILABLE;
                p.getLogger().info("QuickShop-Hikari public API signatures verified; success-purchase event hooked.");
            }
        }catch(Exception ex){
            mgr=null;clazz=null;st=Status.INCOMPATIBLE;
            p.getLogger().warning("QuickShop bridge disabled because the installed API is unavailable or incompatible: "+ex.getMessage());
        }
        shopManager=mgr;shopInterface=clazz;status=st;
    }
    @Override public String name(){return "QuickShop-Hikari";}
    @Override public Status status(){return status;}
    boolean available(){return status==Status.AVAILABLE;}
    @Override public boolean shopAt(Location location){
        if(!available())return false;
        try{return shopManager.getClass().getMethod("getShopIncludeAttached",Location.class).invoke(shopManager,location)!=null;}
        catch(Exception ex){return false;}
    }
    private Object shopAtObject(Location location)throws Exception{
        return shopManager.getClass().getMethod("getShopIncludeAttached",Location.class).invoke(shopManager,location);
    }
    /** Deletion is performed via QuickShop's public ShopManager rather than manipulating its DB. */
    @Override public void detach(Booth booth)throws Exception{
        if(!available())return;
        for(int x=booth.x;x<booth.x+booth.width;x++)for(int y=booth.y;y<booth.y+booth.height;y++)for(int z=booth.z;z<booth.z+booth.length;z++){
            Block block=Bukkit.getWorld(booth.world).getBlockAt(x,y,z);
            if(!(block.getState() instanceof Container))continue;
            Object shop=shopAtObject(block.getLocation());
            if(shop==null)continue;
            Method delete=shopManager.getClass().getMethod("deleteShop",shopInterface);
            Object result=delete.invoke(shopManager,shop);
            // If QuickShop performs asynchronous deletion, reset is deferred until shop mapping disappears.
            if(result instanceof java.util.concurrent.CompletableFuture<?> future && !future.isDone()){
                throw new IllegalStateException("QuickShop deletion pending; reset will resume on next cycle.");
            }
            if(shopAtObject(block.getLocation())!=null)throw new IllegalStateException("Shop deletion not yet reflected in QuickShop manager; reset deferred.");
        }
    }
    private void recordSale(Event e){
        try{
            Object shop=e.getClass().getMethod("getShop").invoke(e);
            if(shop==null)return;
            Location location=(Location)shop.getClass().getMethod("getLocation").invoke(shop);
            Booth b=p.at(location);
            Object total=e.getClass().getMethod("getTotal").invoke(e);
            if(!(total instanceof Number number))return;
            // ponytail: QuickShop events carry no ID, so each delivery gets a fresh key; replay dedupe matters for providers with stored records.
            ShopProviders.recordSale(p.db,b,name(),UUID.randomUUID().toString(),number.doubleValue());
        }catch(Exception ex){p.getLogger().fine("Could not track a QuickShop sale: "+ex.getMessage());}
    }
}
