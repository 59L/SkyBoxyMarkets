package dev.skyboxy.market;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import java.util.*;

/** Event-level enforcement for booth and commons areas. Marketmain should not have a conflicting WorldGuard BUILD deny. */
final class MarketProtection implements Listener {
    private final SkyBoxyMarket p;
    MarketProtection(SkyBoxyMarket p){this.p=p;}
    private boolean forbidden(Player user,Location loc,String rule){
        if(user.hasPermission("skyboxymarket.bypass"))return false;
        Booth b=p.at(loc);
        if(b!=null)return !p.allowed(user,b,rule);
        return p.getConfig().getBoolean("market.deny-unregistered-area-edits",true)&&p.scanner.inMain(loc);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void place(BlockPlaceEvent e){
        Booth b=p.at(e.getBlockPlaced().getLocation());
        if(forbidden(e.getPlayer(),e.getBlockPlaced().getLocation(),"place")){e.setCancelled(true);return;}
        if(e.getPlayer().hasPermission("skyboxymarket.bypass"))return;
        if(b!=null){
            if(!b.rented() && !e.getPlayer().hasPermission("skyboxymarket.bypass")){e.setCancelled(true);return;}
            StyleTemplates.Template t=p.templates.get(b.style);
            if(t==null || t.isStructural(e.getBlockPlaced().getX()-b.x,e.getBlockPlaced().getY()-b.y,e.getBlockPlaced().getZ()-b.z,b.rotation)) e.setCancelled(true);
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void placed(BlockPlaceEvent e){
        Booth b=p.at(e.getBlockPlaced().getLocation());
        if(b==null||!b.rented()||e.getPlayer().hasPermission("skyboxymarket.bypass"))return;
        try{
            ItemStack placed=e.getItemInHand().clone();
            placed.setAmount(1);
            p.db.place(b,e.getBlockPlaced().getX(),e.getBlockPlaced().getY(),e.getBlockPlaced().getZ(),e.getBlockPlaced().getType(),e.getPlayer().getUniqueId(),placed);
        }
        catch(Exception ex){p.getLogger().severe("Could not track placed block. Prevent more edits and inspect DB: "+ex);}
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void breakBlock(BlockBreakEvent e){
        Block block=e.getBlock();Booth b=p.at(block.getLocation());
        if(forbidden(e.getPlayer(),block.getLocation(),"break")){e.setCancelled(true);return;}
        if(e.getPlayer().hasPermission("skyboxymarket.bypass"))return;
        if(b==null)return;
        try{
            StyleTemplates.Template t=p.templates.get(b.style);
            if(t==null||t.isStructural(block.getX()-b.x,block.getY()-b.y,block.getZ()-b.z,b.rotation)||!p.db.wasPlaced(b,block.getX(),block.getY(),block.getZ())) e.setCancelled(true);
        }catch(Exception ex){e.setCancelled(true);p.getLogger().warning("Break failed DB check: "+ex);}
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void broken(BlockBreakEvent e){
        Booth b=p.at(e.getBlock().getLocation());if(b==null)return;
        try{p.db.breakPlaced(b,e.getBlock().getX(),e.getBlock().getY(),e.getBlock().getZ());}
        catch(Exception ex){p.getLogger().severe("Could not untrack broken block: "+ex);}
    }
    // LOWEST: shop plugins act on clicks early (ChestShop trades at LOW), so a denial must land before them.
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true)
    public void interact(PlayerInteractEvent e){
        Block block=e.getClickedBlock();if(block==null)return;
        Player player=e.getPlayer();Booth b=p.at(block.getLocation());
        if(b==null){if(p.scanner.inMain(block.getLocation())&&!player.hasPermission("skyboxymarket.bypass")
                && e.getAction()==Action.RIGHT_CLICK_BLOCK&&block.getState() instanceof Container)e.setCancelled(true);return;}
        if(player.hasPermission("skyboxymarket.bypass"))return;
        if(e.getAction()!=Action.RIGHT_CLICK_BLOCK)return;
        Material mat=block.getType();String rule;
        if(block.getState() instanceof Container){
            // Confirmed shop-provider containers are purchase interfaces. Ordinary private storage is not.
            rule=p.shops.shopAt(block.getLocation())?"shop-interact":"containers";
        }else if(mat.name().contains("DOOR")||mat.name().contains("TRAPDOOR")||mat.name().contains("GATE"))rule="doors";
        else if(mat.name().contains("BUTTON")||mat.name().contains("LEVER")||mat.name().contains("PRESSURE_PLATE"))rule="buttons";
        else if(mat.name().contains("SIGN"))rule="shop-interact";
        else rule="entities";
        if(!p.allowed(player,b,rule))e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void open(InventoryOpenEvent e){
        if(!(e.getPlayer() instanceof Player user))return;
        Inventory inv=e.getInventory(); Location l=inv.getLocation();if(l==null)return;
        Booth b=p.at(l);if(b==null)return;
        // The QuickShop purchase handler uses click events, not vanilla inventory opening.
        if(!p.allowed(user,b,"containers"))e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void bucket(PlayerBucketEmptyEvent e){if(forbidden(e.getPlayer(),e.getBlock().getLocation(),"fluids"))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void bucketFill(PlayerBucketFillEvent e){if(forbidden(e.getPlayer(),e.getBlock().getLocation(),"fluids"))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void entityInteract(PlayerInteractEntityEvent e){
        if(p.holograms.isMarketEntity(e.getRightClicked()))return;
        Booth b=p.at(e.getRightClicked().getLocation());if(b!=null&&!p.allowed(e.getPlayer(),b,"entities"))e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void hit(EntityDamageByEntityEvent e){
        if(e.getEntity() instanceof Player victim){
            Booth b=p.at(victim.getLocation());if(b!=null){
                Player attacker=e.getDamager() instanceof Player player?player:e.getDamager() instanceof Projectile proj&&proj.getShooter() instanceof Player p2?p2:null;
                if(attacker==null||!p.allowed(attacker,b,"pvp"))e.setCancelled(true);
            }
        }else {
            Booth b=p.at(e.getEntity().getLocation());
            Player attacker=e.getDamager() instanceof Player player?player:e.getDamager() instanceof Projectile projectile&&projectile.getShooter() instanceof Player shooter?shooter:null;
            if(b!=null&&(attacker==null||!p.allowed(attacker,b,"entities")))e.setCancelled(true);
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void manipulate(PlayerArmorStandManipulateEvent e){
        Booth b=p.at(e.getRightClicked().getLocation());if(b!=null&&!p.allowed(e.getPlayer(),b,"entities"))e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void hanging(HangingBreakByEntityEvent e){
        Booth b=p.at(e.getEntity().getLocation());if(b==null)return;
        if(!(e.getRemover() instanceof Player user)||!p.allowed(user,b,"entities"))e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void hangPlace(HangingPlaceEvent e){
        if(e.getPlayer()!=null&&forbidden(e.getPlayer(),e.getEntity().getLocation(),"entities"))e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void explode(EntityExplodeEvent e){
        e.blockList().removeIf(b->p.at(b.getLocation())!=null||p.scanner.inMain(b.getLocation()));
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void explodeBlock(BlockExplodeEvent e){
        e.blockList().removeIf(b->p.at(b.getLocation())!=null||p.scanner.inMain(b.getLocation()));
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void spread(BlockSpreadEvent e){if(p.at(e.getBlock().getLocation())!=null||p.scanner.inMain(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void burn(BlockBurnEvent e){if(p.at(e.getBlock().getLocation())!=null||p.scanner.inMain(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void ignite(BlockIgniteEvent e){if(p.at(e.getBlock().getLocation())!=null||p.scanner.inMain(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void form(BlockFormEvent e){if(p.at(e.getBlock().getLocation())!=null||p.scanner.inMain(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void fade(BlockFadeEvent e){if(p.at(e.getBlock().getLocation())!=null||p.scanner.inMain(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void changeBlock(EntityChangeBlockEvent e){if(p.at(e.getBlock().getLocation())!=null||p.scanner.inMain(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void fluid(BlockFromToEvent e){
        if(protectedMarket(e.getBlock().getLocation())||protectedMarket(e.getToBlock().getLocation()))e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void piston(BlockPistonExtendEvent e){
        for(Block b:e.getBlocks())if(protectedMarket(b.getLocation())||protectedMarket(b.getRelative(e.getDirection()).getLocation())){e.setCancelled(true);return;}
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void pistonBack(BlockPistonRetractEvent e){
        for(Block b:e.getBlocks())if(protectedMarket(b.getLocation())||protectedMarket(b.getRelative(e.getDirection()).getLocation())){e.setCancelled(true);return;}
    }
    private boolean protectedMarket(Location location){return p.at(location)!=null||p.scanner.inMain(location);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void hopper(InventoryMoveItemEvent e){
        Location source=e.getSource().getLocation(),destination=e.getDestination().getLocation();
        if(source!=null&&protectedMarket(source)||destination!=null&&protectedMarket(destination))e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void dispense(BlockDispenseEvent e){if(p.at(e.getBlock().getLocation())!=null||p.scanner.inMain(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void move(PlayerMoveEvent e){
        Location target=e.getTo();if(target==null)return;
        Booth from=p.at(e.getFrom()),to=p.at(target);
        if(to!=null&&!p.rentals.canEnter(e.getPlayer(),to)){
            e.setTo(e.getFrom());p.tellLimited(e.getPlayer(),"&cThis market is private, locked, or you are banned.");
            return;
        }
        if(from==to)return;
        if(to!=null&&to.owner!=null)try{p.db.visit(to,e.getPlayer().getUniqueId(),System.currentTimeMillis(),p.getConfig().getLong("market.visit-cooldown-seconds",30)*1000L);}catch(Exception ex){p.getLogger().warning("Visit stat failure: "+ex);}
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void teleport(PlayerTeleportEvent e){
        Booth to=p.at(e.getTo());if(to!=null&&!p.rentals.canEnter(e.getPlayer(),to))e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void armorStandTarget(EntityTargetLivingEntityEvent e){
        if(e.getTarget() instanceof Player player&&p.at(player.getLocation())!=null)e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void vehicleEnter(org.bukkit.event.vehicle.VehicleEnterEvent e){
        if(!(e.getEntered() instanceof Player player))return;
        Booth booth=p.at(e.getVehicle().getLocation());
        if(booth!=null&&!p.rentals.canEnter(player,booth))e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void mount(org.bukkit.event.entity.EntityMountEvent e){
        if(!(e.getEntity() instanceof Player player))return;
        Booth booth=p.at(e.getMount().getLocation());
        if(booth!=null&&!p.rentals.canEnter(player,booth))e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void redstone(BlockRedstoneEvent e){
        Location location=e.getBlock().getLocation();
        Booth booth=p.at(location);
        if(booth!=null){
            String role=booth.owner==null?"visitor":"renter";
            if(!p.getConfig().getBoolean("permissions."+role+".redstone",false))e.setNewCurrent(0);
        }else if(p.getConfig().getBoolean("market.deny-unregistered-area-edits",true)&&p.scanner.inMain(location))e.setNewCurrent(0);
    }
}
