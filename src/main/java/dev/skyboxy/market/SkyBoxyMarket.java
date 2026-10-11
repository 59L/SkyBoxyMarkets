package dev.skyboxy.market;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.File;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class SkyBoxyMarket extends JavaPlugin implements Listener {
    final List<Booth> booths = new ArrayList<>();
    final BoothSpatialIndex boothIndex = new BoothSpatialIndex();
    final Map<String, MarketStyle> styles = new LinkedHashMap<>();
    final StyleTemplates templates = new StyleTemplates();
    MarketDatabase db;
    MarketScanner scanner;
    MarketRentals rentals;
    MarketRecovery recovery;
    MarketHolograms holograms;
    MarketMenus menus;
    Economy economy;
    ShopProviders shops;
    final Map<UUID,Long> lastDenied = new HashMap<>();
    @Override public void onEnable(){
        saveDefaultConfig();
        File dir=new File(getDataFolder(),"schematics");dir.mkdirs();
        for(String name:List.of("mainMarket2.schem","marketboothCorner2.schem")){
            File f=new File(dir,name);
            if(!f.exists())saveResource("schematics/"+name,false);
        }
        try{
            db=new MarketDatabase(new File(getDataFolder(),"market.db"));
            booths.addAll(db.loadBooths());
            booths.forEach(boothIndex::add);
            int interruptedPayments=db.reviewInterruptedPaymentIntents();
            if(interruptedPayments>0)getLogger().severe(interruptedPayments+" payment intent(s) were interrupted and now require staff review. Check /market admin > Payment Review before retrying or refunding any transaction.");
            int pendingClaims=db.inFlightClaimCount();
            if(pendingClaims>0)getLogger().warning(pendingClaims+" recovery delivery receipt(s) will be reconciled when their owners next join.");
        }catch(Exception ex){getLogger().severe("Cannot open marketplace SQLite database: "+ex);getServer().getPluginManager().disablePlugin(this);return;}
        if(Bukkit.getPluginManager().getPlugin("WorldGuard")==null||(Bukkit.getPluginManager().getPlugin("WorldEdit")==null && Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit")==null)){
            getLogger().severe("WorldGuard and WorldEdit/FAWE are required.");getServer().getPluginManager().disablePlugin(this);return;
        }
        getServer().getPluginManager().registerEvents(this,this);
        setupEconomy();
        reloadStyles();
        scanner=new MarketScanner(this);holograms=new MarketHolograms(this);
        shops=new ShopProviders(List.of(new QuickShopBridge(this),new ChestShopProvider(this)));
        recovery=new MarketRecovery(this);rentals=new MarketRentals(this);menus=new MarketMenus(this);
        getServer().getPluginManager().registerEvents(new MarketProtection(this),this);
        getServer().getPluginManager().registerEvents(recovery,this);
        getServer().getPluginManager().registerEvents(menus,this);
        getServer().getPluginManager().registerEvents(holograms,this);
        getServer().getPluginManager().registerEvents(new MarketChatInput(this),this);
        Objects.requireNonNull(getCommand("market")).setExecutor(new MarketCommands(this));
        Objects.requireNonNull(getCommand("market")).setTabCompleter(new MarketCommands(this));
        getServer().getScheduler().runTaskLater(this,()->{
            recovery.resumeResets();holograms.refreshAll();
            if(getConfig().getBoolean("market.auto-scan-on-start",false))scanner.scan(null);
        },40);
        getServer().getScheduler().runTaskTimer(this,()->rentals.expire(),120,
                Math.max(200,getConfig().getLong("market.expiry-check-ticks",1200)));
        getLogger().info("SkyBoxy Market started: "+booths.size()+" saved booths; "+styles.size()+" styles.");
    }
    void reloadStyles(){
        styles.clear();
        ConfigurationSection s=getConfig().getConfigurationSection("styles");
        if(s!=null)for(String id:s.getKeys(false)){
            try{styles.put(id,new MarketStyle(id,s.getConfigurationSection(id)));}
            catch(Exception ex){getLogger().warning("Invalid style "+id+": "+ex.getMessage());}
        }
        templates.load(this,styles.values());
    }
    void setupEconomy(){
        RegisteredServiceProvider<Economy> provider=Bukkit.getServicesManager().getRegistration(Economy.class);
        economy=provider==null?null:provider.getProvider();
        if(economy==null)getLogger().warning("No Vault economy provider found. Rentals with nonzero cost will refuse transactions.");
    }
    @EventHandler
    public void economyServiceRegistered(ServiceRegisterEvent event){
        if(Economy.class.equals(event.getProvider().getService())){
            economy=(Economy)event.getProvider().getProvider();
            getLogger().info("Vault economy provider detected: "+economy.getName());
        }
    }
    @EventHandler
    public void economyServiceUnregistered(ServiceUnregisterEvent event){
        if(Economy.class.equals(event.getProvider().getService())
                && economy==event.getProvider().getProvider()){
            economy=null;
            // Bukkit fires ServiceUnregisterEvent before removing the provider from its registry.
            // Resolve again next tick so we can select any remaining economy implementation.
            Bukkit.getScheduler().runTask(this,this::setupEconomy);
        }
    }
    @Override public void onDisable(){
        if(scanner!=null)scanner.cancel();
        if(recovery!=null)recovery.cancelClaims();
        if(holograms!=null)holograms.removeAll();
        if(db!=null)try{db.close();}catch(SQLException e){getLogger().warning("Could not close SQLite: "+e);}
    }
    Booth at(org.bukkit.Location location){
        if(location==null||location.getWorld()==null)return null;
        return boothIndex.at(location.getWorld().getName(),location.getBlockX(),location.getBlockY(),location.getBlockZ());
    }
    void registerBooth(Booth booth){booths.add(booth);boothIndex.add(booth);}
    Booth byId(String id){for(Booth b:booths)if(b.id.equals(id))return b;return null;}
    List<Booth> own(UUID id){return booths.stream().filter(b->id.equals(b.owner)&&!b.resetting).toList();}
    MarketStyle style(Booth b){return styles.get(b.style);}
    MarketRole role(Player player,Booth b){
        if(player.hasPermission("skyboxymarket.bypass"))return MarketRole.ADMIN;
        if(player.getUniqueId().equals(b.owner))return MarketRole.RENTER;
        if(b.trusted.contains(player.getUniqueId()))return MarketRole.MEMBER;
        return MarketRole.VISITOR;
    }
    boolean allowed(Player p,Booth b,String action){
        if(p.hasPermission("skyboxymarket.bypass"))return true;
        if(b.resetting||b.suspended)return false;
        MarketRole role=role(p,b);
        return MarketPolicy.allowed(getConfig(),b.style,role,action);
    }
    static String c(String raw){return ChatColor.translateAlternateColorCodes('&',raw==null?"":raw);}
    void tell(CommandSender to,String message){if(to!=null)to.sendMessage(c("&8[&aMarket&8] &7"+message));}
    void tellLimited(Player user,String msg){
        long now=System.currentTimeMillis(),last=lastDenied.getOrDefault(user.getUniqueId(),0L);
        if(now-last>2500){tell(user,msg);lastDenied.put(user.getUniqueId(),now);}
    }
    boolean save(Booth b){try{db.save(b);holograms.refresh(b);return true;}catch(Exception e){getLogger().severe("Saving booth "+b.id+" failed: "+e);return false;}}
    void protectionEject(Booth b){
        if(rentals==null)return;
        for(Player user:Bukkit.getOnlinePlayers()) if(b.contains(user.getLocation())&&!rentals.canEnter(user,b)){
            org.bukkit.Location outside=safeExit(b,user.getLocation());
            if(outside!=null)user.teleportAsync(outside);
            else user.teleportAsync(user.getWorld().getSpawnLocation());
        }
    }
    org.bukkit.Location safeExit(Booth b,org.bukkit.Location from){
        org.bukkit.World world=Bukkit.getWorld(b.world);if(world==null)return null;
        // Choose the nearest exterior column on a cardinal side; never move into a different booth.
        for(int radius=1;radius<=8;radius++){
            int midX=b.x+b.width/2,midZ=b.z+b.length/2;
            int[][] cols={{midX,b.z-radius},{midX,b.z+b.length-1+radius},{b.x-radius,midZ},{b.x+b.width-1+radius,midZ}};
            for(int[] col:cols)for(int y=b.y;y<=b.y+Math.min(4,b.height);y++){
                org.bukkit.Location l=new org.bukkit.Location(world,col[0]+0.5,y,col[1]+0.5);
                if(at(l)!=null||!scanner.inMain(l))continue;
                if(l.getBlock().isPassable()&&l.clone().add(0,1,0).getBlock().isPassable()&&l.clone().add(0,-1,0).getBlock().getType().isSolid())return l;
            }
        }
        return null;
    }
    void offsetFromPlayer(Player user,Booth b,String kind){
        if(!user.getWorld().getName().equals(b.world)) {tell(user,"&cVisit the booth's world to set its offset.");return;}
        double x=user.getLocation().getX()-(b.x+b.width/2.0),z=user.getLocation().getZ()-(b.z+b.length/2.0);
        double rx=switch(b.rotation){case 1->z;case 2->-x;case 3->-z;default->x;};
        double rz=switch(b.rotation){case 1->-x;case 2->-z;case 3->x;default->z;};
        String base="styles."+b.style+".offsets."+kind+".";
        getConfig().set(base+"x",rx);getConfig().set(base+"y",user.getLocation().getY()-b.y);
        getConfig().set(base+"z",rz);getConfig().set(base+"yaw",user.getLocation().getYaw()-b.rotation*90);
        saveConfig();reloadStyles();holograms.refreshAll();
        db.audit(user.getUniqueId().toString(),"offset",b.style+" "+kind);
        tell(user,"&aSaved "+kind+" offset for ALL booths of style "+b.style+"."
            +" Rotate/orientation is handled automatically.");
    }
    File createDatabaseBackup()throws Exception {
        String timestamp=DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now());
        String suffix=UUID.randomUUID().toString().substring(0,8);
        File backup=new File(new File(getDataFolder(),"backups"),"market-"+timestamp+"-"+suffix+".db");
        db.backup(backup);
        db.audit("SYSTEM","database-backup",backup.getName());
        return backup;
    }
    int boothLimit(UUID id){return own(id).size();}
    double balance(Player p){return economy==null?0:economy.getBalance(p);}
    boolean charge(Player p,double amount){
        if(!Double.isFinite(amount)||amount<0)return false;
        if(amount==0)return true;
        return economy!=null&&economy.has(p,amount)&&economy.withdrawPlayer(p,amount).transactionSuccess();
    }
    boolean refund(Player p,double amount){
        if(!Double.isFinite(amount)||amount<0)return false;
        if(amount==0)return true;
        return economy!=null&&economy.depositPlayer(p,amount).transactionSuccess();
    }
}

final class MarketCommands implements org.bukkit.command.CommandExecutor, TabCompleter {
    private final SkyBoxyMarket p;
    MarketCommands(SkyBoxyMarket p){this.p=p;}
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(!(sender instanceof Player user)){
            if(args.length>0&&args[0].equalsIgnoreCase("reload")){
                p.reloadConfig();p.reloadStyles();p.getLogger().info("Market styles/config reloaded");return true;
            }
            if(args.length>0&&args[0].equalsIgnoreCase("backup")){
                try{sender.sendMessage("SkyBoxyMarket database backup created: "+p.createDatabaseBackup().getAbsolutePath());}
                catch(Exception ex){sender.sendMessage("Database backup failed: "+ex.getMessage());p.getLogger().severe("Database backup failed: "+ex);}
                return true;
            }
            sender.sendMessage("Use /market in game. Console: /market reload or /market backup");return true;
        }
        if(!user.hasPermission("skyboxymarket.use")&&!user.hasPermission("skyboxymarket.admin")){p.tell(user,"&cNo permission.");return true;}
        String op=args.length==0?"":args[0].toLowerCase(Locale.ROOT);
        switch(op){
            case "admin" -> {if(!user.hasPermission("skyboxymarket.admin")){p.tell(user,"&cAdmin only.");break;}p.menus.admin(user);}
            case "backup" -> {if(!user.hasPermission("skyboxymarket.admin")){p.tell(user,"&cAdmin only.");break;}
                try{p.tell(user,"&aDatabase backup: "+p.createDatabaseBackup().getName());}
                catch(Exception ex){p.tell(user,"&cDatabase backup failed. See console.");p.getLogger().severe("Database backup failed: "+ex);}}
            case "scan" -> {
                if(!user.hasPermission("skyboxymarket.admin")){p.tell(user,"&cAdmin only.");break;}
                String action=args.length>1?args[1].toLowerCase(Locale.ROOT):"start";
                switch(action){
                    case "status" -> p.tell(user,"&f"+p.scanner.progress());
                    case "cancel" -> {if(!p.scanner.cancel(user))p.tell(user,"&7No marketplace scan is running.");}
                    case "start" -> p.scanner.scan(user);
                    // A resume deliberately repeats the bounded pass; already registered booth IDs stay intact.
                    case "resume" -> {
                        if(p.scanner.running())p.tell(user,"&eA marketplace scan is already running. Use /market scan status or cancel.");
                        else {p.tell(user,"&7Repeating the scan pass to include chunks loaded since the previous scan.");p.scanner.scan(user);}
                    }
                    default -> p.tell(user,"&fUse /market scan [start|status|cancel|resume].");
                }
            }
            case "walls" -> p.menus.myWalls(user,0);
            case "recover" -> p.menus.recover(user,0);
            case "browse" -> p.menus.browse(user,0);
            case "tp" -> p.menus.myTeleport(user,0);
            case "help" -> p.tell(user,"&f/market, /market walls, /market recover, /market browse, /market tp, /market admin");
            default -> p.menus.player(user);
        }
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command cmd,String alias,String[] args){
        if(args.length==2&&args[0].equalsIgnoreCase("scan")&&sender.hasPermission("skyboxymarket.admin"))
            return List.of("start","status","cancel","resume").stream().filter(s->s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if(args.length!=1)return List.of();
        List<String> list=new ArrayList<>(List.of("walls","recover","browse","tp","help"));
        if(sender.hasPermission("skyboxymarket.admin")){list.add("admin");list.add("scan");list.add("backup");}
        return list.stream().filter(s->s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
    }
}
