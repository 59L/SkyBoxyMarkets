package dev.skyboxy.market;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Optional provider for mc-PlayerShop. It has no public API, so this reflects on internals and is pinned to exactly 1.5.1.
 * Stock lives in mc-PlayerShop's own database, not in the block, so reset journals it here before deleting the shop.
 */
final class PlayerShopProvider implements ShopProvider {
    static final String VERSION="1.5.1";
    private static final String SOURCE="mcplayershop:";
    private final SkyBoxyMarket p;
    private final Status status;
    private Object shops,storages,database,visualService;
    private Method get,all,unregister,loadStorage,contents,dropStorage,deleteShop,deleteStorage,transactions,hideVisual;
    private Method shopId,shopLocation,shopOwner,shopCurrency,txType,txAmount,txPrice,txCreated,txPlayer,menuShop;
    private Class<?> boundMenu;

    PlayerShopProvider(SkyBoxyMarket p){
        this.p=p;Status st=Status.ABSENT;
        Plugin plugin=Bukkit.getPluginManager().getPlugin("mc-PlayerShop");
        if(plugin!=null&&plugin.isEnabled()){
            try{
                if(!VERSION.equals(plugin.getPluginMeta().getVersion()))throw new IllegalStateException("version "+plugin.getPluginMeta().getVersion()+" is not the pinned "+VERSION);
                ClassLoader l=plugin.getClass().getClassLoader();String base="com.mongenscave.mcplayershop.";
                Class<?> main=Class.forName(base+"McPlayerShop",true,l),manager=Class.forName(base+"shop.manager.PlayerShopManager",true,l);
                Class<?> storageManager=Class.forName(base+"shop.manager.PlayerShopStorageManager",true,l),storage=Class.forName(base+"shop.models.PlayerShopStorage",true,l);
                Class<?> shop=Class.forName(base+"shop.models.PlayerShop",true,l),tx=Class.forName(base+"shop.models.PlayerShopTransaction",true,l);
                Class<?> db=Class.forName(base+"database.Database",true,l),visuals=Class.forName(base+"shop.service.ShopVisualService",true,l);
                boundMenu=Class.forName(base+"guis.ShopBoundMenu",true,l);
                Object instance=main.getMethod("getInstance").invoke(null);
                shops=main.getMethod("getShopManager").invoke(instance);storages=main.getMethod("getStorageManager").invoke(instance);
                database=Class.forName(base+"database.DatabaseManager",true,l).getMethod("getDatabase").invoke(null);
                Object visual=main.getMethod("getVisualService").invoke(instance);
                get=manager.getMethod("get",Location.class);all=manager.getMethod("getAll");unregister=manager.getMethod("unregister",Location.class);
                loadStorage=storageManager.getMethod("getOrLoadSync",UUID.class);dropStorage=storageManager.getMethod("remove",UUID.class);contents=storage.getMethod("getContents");
                deleteShop=db.getMethod("deleteShop",UUID.class);deleteStorage=db.getMethod("deleteStorage",UUID.class);transactions=db.getMethod("getTransactions",UUID.class,int.class);
                hideVisual=visuals.getMethod("remove",UUID.class);
                shopId=shop.getMethod("getShopId");shopLocation=shop.getMethod("getLocation");shopOwner=shop.getMethod("getOwnerUuid");shopCurrency=shop.getMethod("getCurrencyId");
                txType=tx.getMethod("type");txAmount=tx.getMethod("amount");txPrice=tx.getMethod("price");txCreated=tx.getMethod("createdAt");txPlayer=tx.getMethod("playerUuid");
                menuShop=boundMenu.getMethod("getShop");
                if(shops==null||storages==null||database==null||visual==null)throw new IllegalStateException("mc-PlayerShop services are not initialized");
                visualService=visual;
                // mc-PlayerShop opens its trade menu without checking whether the click was cancelled, so guard the menu itself.
                Bukkit.getPluginManager().registerEvent(InventoryOpenEvent.class,new Listener(){},EventPriority.LOWEST,(x,e)->guardMenu((InventoryOpenEvent)e),p,true);
                Bukkit.getScheduler().runTaskTimer(p,this::pollSales,1200L,1200L);
                st=Status.AVAILABLE;
                p.getLogger().info("mc-PlayerShop "+VERSION+" internals verified; menu guard and sales polling active.");
            }catch(Exception ex){
                st=Status.INCOMPATIBLE;
                p.getLogger().warning("mc-PlayerShop provider disabled (pinned to "+VERSION+"): "+ex);
            }
        }
        status=st;
    }

    @Override public String name(){return "mc-PlayerShop";}
    @Override public Status status(){return status;}
    @Override public boolean shopAt(Location location){
        if(status!=Status.AVAILABLE)return false;
        try{return ((Optional<?>)get.invoke(shops,location)).isPresent();}catch(Exception ex){return false;}
    }

    /**
     * For each shop in the booth: close its menus, journal its database stock under a per-shop source key, then delete it.
     * The ledger ignores a repeated (lease, source, slot), so a restart between any two steps returns stock exactly once.
     * PlayerShopService.remove is not used: it hands the stock to the acting player, which would duplicate the journal.
     */
    @Override public void detach(Booth booth)throws Exception{
        if(status!=Status.AVAILABLE)return;
        for(Object shop:shopsIn(booth)){
            if(booth.leaseId==null)throw new IllegalStateException("mc-PlayerShop stock in booth "+booth.id+" needs the booth lease to journal it");
            UUID id=(UUID)shopId.invoke(shop);Location location=(Location)shopLocation.invoke(shop);
            int journaled=journal(booth,shop);
            ((CompletableFuture<?>)deleteStorage.invoke(database,id)).get(10,TimeUnit.SECONDS);
            ((CompletableFuture<?>)deleteShop.invoke(database,id)).get(10,TimeUnit.SECONDS);
            dropStorage.invoke(storages,id);unregister.invoke(shops,location);hideVisual.invoke(visualService,id);
            p.getLogger().info("mc-PlayerShop shop "+id+" in booth "+booth.id+": journaled "+journaled+" stock entr"+(journaled==1?"y":"ies")+" and deleted it.");
        }
        if(!shopsIn(booth).isEmpty())throw new IllegalStateException("mc-PlayerShop shops remain in booth "+booth.id+"; reset deferred.");
    }
    /** Closes the shop's menus and durably journals its database stock; returns the number of stock entries. Safe to repeat. */
    int journal(Booth booth,Object shop)throws Exception{
        UUID id=(UUID)shopId.invoke(shop);
        UUID owner=Objects.requireNonNullElse((UUID)shopOwner.invoke(shop),booth.owner);
        closeMenus(id);
        List<MarketDatabase.RecoveryItem> stock=new ArrayList<>();
        List<?> items=(List<?>)contents.invoke(loadStorage.invoke(storages,id));
        for(int slot=0;slot<items.size();slot++)if(items.get(slot) instanceof ItemStack item)stock.add(new MarketDatabase.RecoveryItem(SOURCE+id,slot,item.clone()));
        p.db.recoveryBatch(owner,booth,booth.leaseId,stock);
        return stock.size();
    }
    private List<Object> shopsIn(Booth booth)throws Exception{
        List<Object> found=new ArrayList<>();
        for(Object shop:(Collection<?>)all.invoke(shops)){Location l=(Location)shopLocation.invoke(shop);if(l!=null&&p.at(l)==booth)found.add(shop);}
        return found;
    }
    private void closeMenus(UUID id){
        for(Player player:Bukkit.getOnlinePlayers()){
            Object holder=player.getOpenInventory().getTopInventory().getHolder();
            try{if(boundMenu.isInstance(holder)&&id.equals(shopId.invoke(menuShop.invoke(holder))))player.closeInventory();}catch(Exception ignored){}
        }
    }
    private void guardMenu(InventoryOpenEvent e){
        Object holder=e.getInventory().getHolder();if(!boundMenu.isInstance(holder))return;
        HumanEntity viewer=e.getPlayer();if(!(viewer instanceof Player player))return;
        try{
            Booth b=p.at((Location)shopLocation.invoke(menuShop.invoke(holder)));
            if(b!=null&&!p.allowed(player,b,"shop-interact"))e.setCancelled(true);
        }catch(Exception ex){e.setCancelled(true);p.getLogger().warning("mc-PlayerShop menu check failed; menu blocked: "+ex);}
    }

    /** No sale event exists; read mc-PlayerShop's transaction records off-thread and count them on the main thread. */
    private void pollSales(){
        try{
            for(Object shop:(Collection<?>)all.invoke(shops)){
                Booth b=p.at((Location)shopLocation.invoke(shop));
                if(b==null||b.owner==null||!b.owner.equals(shopOwner.invoke(shop))||!"vault".equalsIgnoreCase(String.valueOf(shopCurrency.invoke(shop))))continue;
                UUID id=(UUID)shopId.invoke(shop);
                ((CompletableFuture<?>)transactions.invoke(database,id,500)).thenAccept(list->Bukkit.getScheduler().runTask(p,()->countSales(b,id,(List<?>)list)));
            }
        }catch(Exception ex){p.getLogger().warning("mc-PlayerShop sales poll failed: "+ex);}
    }
    /** Test hook: the same counting path, waiting for the records. */
    int syncSales(Booth b)throws Exception{
        int counted=0;
        for(Object shop:shopsIn(b)){UUID id=(UUID)shopId.invoke(shop);counted+=countSales(b,id,(List<?>)((CompletableFuture<?>)transactions.invoke(database,id,500)).get(10,TimeUnit.SECONDS));}
        return counted;
    }
    private int countSales(Booth b,UUID id,List<?> records){
        int counted=0;
        for(Object r:records)try{
            // BUY: a customer bought from the shop and mc-PlayerShop deposited the price to the owner.
            if(!"BUY".equals(txType.invoke(r)))continue;
            String key=id+":"+txCreated.invoke(r)+":"+txPlayer.invoke(r)+":"+txAmount.invoke(r)+":"+txPrice.invoke(r);
            if(ShopProviders.recordSale(p.db,b,name(),key,((Number)txPrice.invoke(r)).doubleValue()))counted++;
        }catch(Exception ex){p.getLogger().fine("Could not count an mc-PlayerShop transaction: "+ex.getMessage());}
        return counted;
    }
}
