package dev.skyboxy.market;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.UUID;

/** Optional reflection-only provider for ChestShop 3.12 sign shops. A shop is a valid sign plus its container; stock lives in the container. */
final class ChestShopProvider implements ShopProvider {
    private final SkyBoxyMarket p;
    private final Status status;
    private Method isShopBlock,isValidBlock,isAdminLines,txType,txSign,txPrice,preSign,prePlayer,preLines,preOutcome;
    private Object buy,noPermission;

    ChestShopProvider(SkyBoxyMarket p){
        this.p=p;Status st=Status.ABSENT;
        Plugin chestShop=Bukkit.getPluginManager().getPlugin("ChestShop");
        if(chestShop!=null&&chestShop.isEnabled()){
            try{
                ClassLoader loader=chestShop.getClass().getClassLoader();
                Class<?> sign=Class.forName("com.Acrobot.ChestShop.Signs.ChestShopSign",true,loader);
                isShopBlock=sign.getMethod("isShopBlock",Block.class);isValidBlock=sign.getMethod("isValid",Block.class);isAdminLines=sign.getMethod("isAdminShop",String[].class);
                Class<? extends Event> tx=event(loader,"com.Acrobot.ChestShop.Events.TransactionEvent");
                txType=tx.getMethod("getTransactionType");txSign=tx.getMethod("getSign");txPrice=tx.getMethod("getExactPrice");
                buy=enumValue(loader,"com.Acrobot.ChestShop.Events.TransactionEvent$TransactionType","BUY");
                Class<? extends Event> pre=event(loader,"com.Acrobot.ChestShop.Events.PreShopCreationEvent");
                preSign=pre.getMethod("getSign");prePlayer=pre.getMethod("getPlayer");preLines=pre.getMethod("getSignLines");
                Class<?> outcome=Class.forName("com.Acrobot.ChestShop.Events.PreShopCreationEvent$CreationOutcome",true,loader);
                preOutcome=pre.getMethod("setOutcome",outcome);noPermission=enumValue(loader,outcome.getName(),"NO_PERMISSION");
                Listener listener=new Listener(){};
                Bukkit.getPluginManager().registerEvent(tx,listener,EventPriority.MONITOR,(l,e)->{if(tx.isInstance(e))recordSale(e);},p,true);
                Bukkit.getPluginManager().registerEvent(pre,listener,EventPriority.HIGH,(l,e)->{if(pre.isInstance(e))checkCreation(e);},p,true);
                st=Status.AVAILABLE;
                p.getLogger().info("ChestShop signatures verified; transaction and shop-creation events hooked.");
            }catch(Exception ex){
                st=Status.INCOMPATIBLE;
                p.getLogger().warning("ChestShop provider disabled because the installed API is unavailable or incompatible: "+ex);
            }
        }
        status=st;
    }
    @SuppressWarnings("unchecked")
    private static Class<? extends Event> event(ClassLoader loader,String name)throws Exception{
        Class<?> type=Class.forName(name,true,loader);
        if(!Event.class.isAssignableFrom(type))throw new NoSuchMethodException(name+" is not a Bukkit event");
        return (Class<? extends Event>)type;
    }
    private static Object enumValue(ClassLoader loader,String type,String name)throws Exception{
        for(Object constant:Class.forName(type,true,loader).getEnumConstants())if(((Enum<?>)constant).name().equals(name))return constant;
        throw new NoSuchFieldException(type+"."+name);
    }
    @Override public String name(){return "ChestShop";}
    @Override public Status status(){return status;}
    @Override public boolean shopAt(Location location){
        if(status!=Status.AVAILABLE)return false;
        Block block=location.getBlock();
        try{return (boolean)isShopBlock.invoke(null,block)||(boolean)isValidBlock.invoke(null,block);}
        catch(Exception ex){return false;}
    }
    /** ChestShop keeps no registry: removing every valid shop sign in the booth removes its shops. Container stock is journaled by the reset snapshot. */
    @Override public void detach(Booth booth)throws Exception{
        if(status!=Status.AVAILABLE)return;
        var world=Bukkit.getWorld(booth.world);if(world==null)throw new IllegalStateException("World not loaded: "+booth.world);
        for(int pass=0;pass<2;pass++)
            for(int x=booth.x;x<booth.x+booth.width;x++)for(int y=booth.y;y<booth.y+booth.height;y++)for(int z=booth.z;z<booth.z+booth.length;z++){
                Block block=world.getBlockAt(x,y,z);
                if(!(boolean)isValidBlock.invoke(null,block))continue;
                if(pass==1)throw new IllegalStateException("ChestShop sign at "+x+","+y+","+z+" survived removal; reset deferred.");
                block.setType(Material.AIR,false);
            }
    }
    private void recordSale(Event e){
        try{
            // BUY is a customer buying from the shop; SELL means the owner paid the customer.
            if(txType.invoke(e)!=buy)return;
            Sign sign=(Sign)txSign.invoke(e);
            if((boolean)isAdminLines.invoke(null,(Object)sign.getLines()))return;
            BigDecimal price=(BigDecimal)txPrice.invoke(e);
            // ponytail: ChestShop transactions carry no ID, so each delivery gets a fresh key.
            ShopProviders.recordSale(p.db,p.at(sign.getLocation()),name(),UUID.randomUUID().toString(),price.doubleValue());
        }catch(Exception ex){p.getLogger().fine("Could not track a ChestShop sale: "+ex.getMessage());}
    }
    /** Inside a booth only the renter and trusted members create shops, and admin shops are never allowed. */
    private void checkCreation(Event e){
        try{
            Sign sign=(Sign)preSign.invoke(e);Booth b=p.at(sign.getLocation());if(b==null)return;
            UUID creator=((Player)prePlayer.invoke(e)).getUniqueId();
            boolean member=b.owner!=null&&(b.owner.equals(creator)||b.trusted.contains(creator));
            if(!member||(boolean)isAdminLines.invoke(null,(Object)preLines.invoke(e)))preOutcome.invoke(e,noPermission);
        }catch(Exception ex){
            p.getLogger().warning("ChestShop creation check failed; denying the shop: "+ex);
            try{preOutcome.invoke(e,noPermission);}catch(Exception ignored){}
        }
    }
}
