package dev.skyboxy.market;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.ShulkerBox;
import org.bukkit.block.BlockFace;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import java.io.File;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import org.bukkit.scheduler.BukkitRunnable;

public final class PaperAcceptanceHarness extends JavaPlugin {
  @Override public void onEnable() {
    Bukkit.getScheduler().runTaskLater(this,this::unloadPendingResetChunks,2L);
    monitorDurableRestoreForCrash();
    Bukkit.getScheduler().runTaskLater(this, this::runAcceptance, 80L);
  }
  private void monitorDurableRestoreForCrash(){
    File marker=new File(getDataFolder(),"acceptance-reset-phase.properties");
    if(!marker.isFile())return;
    try{
      Properties staged=new Properties();try(var input=Files.newInputStream(marker.toPath())){staged.load(input);}
      if(!"HARD_CRASH_RESTORE".equals(staged.getProperty("phase")))return;
    }catch(Exception ex){getLogger().severe("Could not read hard-crash acceptance marker: "+ex);return;}
    new BukkitRunnable(){
      @Override public void run(){
        try{
          Properties current=new Properties();try(var input=Files.newInputStream(marker.toPath())){current.load(input);}
          if(!"HARD_CRASH_RESTORE".equals(current.getProperty("phase"))){cancel();return;}
          Object market=Bukkit.getPluginManager().getPlugin("SkyBoxyMarket"),db=field(market,"db");
          String boothId=current.getProperty("booth");List<?> resets=(List<?>)call(db,"resets",new Class<?>[0]);
          Object reset=resets.stream().filter(row->{try{return boothId.equals(call(row,"booth",new Class<?>[0]));}catch(Exception ex){return false;}}).findFirst().orElse(null);
          int stagedCursor=Integer.parseInt(current.getProperty("cursor","0"));
          if(reset!=null&&"RESTORING".equals(call(reset,"phase",new Class<?>[0]))
                  &&(int)call(reset,"restoreCursor",new Class<?>[0])>stagedCursor){
            current.setProperty("phase","VERIFY_HARD_CRASH");
            try(var output=Files.newOutputStream(marker.toPath())){current.store(output,"Crash acceptance will verify the durable restore batch after restart");}
            System.out.flush();System.err.flush();
            Runtime.getRuntime().halt(73);
          }
        }catch(Throwable ex){cancel();fail("HARD CRASH TEST SETUP FAIL",ex);}
      }
    }.runTaskTimer(this,1L,1L);
  }
  private void unloadPendingResetChunks(){
    File marker=new File(getDataFolder(),"acceptance-reset-phase.properties");if(!marker.isFile())return;
    try{
      Properties properties=new Properties();try(var input=Files.newInputStream(marker.toPath())){properties.load(input);}
      Object market=Bukkit.getPluginManager().getPlugin("SkyBoxyMarket");Object booth=call(market,"byId",new Class<?>[]{String.class},properties.getProperty("booth"));
      if(booth==null)return;World world=Bukkit.getWorld((String)field(booth,"world"));
      int x=(int)field(booth,"x"),z=(int)field(booth,"z"),width=(int)field(booth,"width"),length=(int)field(booth,"length");
      for(int chunkX=x>>4;chunkX<=(x+width-1)>>4;chunkX++)for(int chunkZ=z>>4;chunkZ<=(z+length-1)>>4;chunkZ++){
        world.setChunkForceLoaded(chunkX,chunkZ,false);world.unloadChunk(chunkX,chunkZ,true);
      }
      getLogger().info("RESTART TEST unloaded pending booth chunks before production reset recovery");
    }catch(Exception ex){getLogger().severe("Could not unload acceptance reset chunks: "+ex);}
  }
  private void runAcceptance() {
    File restartMarker=new File(getDataFolder(),"acceptance-reset-phase.properties");
    try {
      Object market=Bukkit.getPluginManager().getPlugin("SkyBoxyMarket");
      if(market==null) throw new IllegalStateException("SkyBoxyMarket did not enable");
      File setupFlag=new File(getDataFolder(),"setup-world.flag");
      if(setupFlag.isFile()){setupAcceptanceWorld(market,setupFlag);return;}
      if(new File(getDataFolder(),"claims-only.flag").isFile()){
        testMergedReceiptDeath(market);testPartialRecoveryClaims(market);testDeathRecoveryReceipt(market);testPartialClaimFaults(market);testRecoveryClaimMenus(market);
        testWholeRecoveryClaims(market,()->{getLogger().info("CLAIM ACCEPTANCE PASS");Bukkit.getScheduler().runTaskLater(this,Bukkit::shutdown,10L);});return;
      }
      if(restartMarker.isFile()){
        continueRestartAcceptance((SkyBoxyMarket)market,restartMarker);
        return;
      }
      List<?> booths=(List<?>)field(market,"booths");
      Map<?,?> templates=(Map<?,?>)field(field(market,"templates"),"templates");
      if(booths.size()!=8) throw new IllegalStateException("Expected eight scanned booths, got "+booths.size());
      for(Object booth:booths) {
        String style=(String)field(booth,"style"); int rotation=(int)field(booth,"rotation");
        Object template=templates.get(style); if(template==null) throw new IllegalStateException("Missing template "+style);
        World world=Bukkit.getWorld((String)field(booth,"world"));
        List<?> cells=(List<?>)field(template,"cells");
        Object airCell=cells.stream().filter(c->{try{return field(c,"material")==Material.AIR;}catch(Exception ex){return false;}}).findFirst().orElseThrow();
        Method rotateX=template.getClass().getDeclaredMethod("rotateX",int.class,int.class,int.class);rotateX.setAccessible(true);
        Method rotateZ=template.getClass().getDeclaredMethod("rotateZ",int.class,int.class,int.class);rotateZ.setAccessible(true);
        int damagedX=(int)rotateX.invoke(template,(int)field(airCell,"x"),(int)field(airCell,"z"),rotation);
        int damagedZ=(int)rotateZ.invoke(template,(int)field(airCell,"x"),(int)field(airCell,"z"),rotation);
        world.getBlockAt((int)field(booth,"x")+damagedX,(int)field(booth,"y")+(int)field(airCell,"y"),(int)field(booth,"z")+damagedZ).setType(Material.DIAMOND_BLOCK,false);
        Method restore=template.getClass().getDeclaredMethod("restore",booth.getClass()); restore.setAccessible(true); restore.invoke(template,booth);
        Method matches=template.getClass().getDeclaredMethod("matches",World.class,int.class,int.class,int.class,int.class,boolean.class);matches.setAccessible(true);
        boolean ok=(boolean)matches.invoke(template,world,(int)field(booth,"x"),(int)field(booth,"y"),(int)field(booth,"z"),rotation,true);
        if(!ok) throw new IllegalStateException("Clipboard restore did not recreate exact "+style+" rotation "+rotation+" at "+field(booth,"x")+","+field(booth,"z"));
        getLogger().info("CLIPBOARD RESTORE PASS style="+style+" rotation="+rotation);
      }
      testSpatialLookupBudget(market);
      testWallMasks(booths,templates);
      testHologramRefresh(market,booths);
      testOptionalShopFailsClosed(market,booths);
      testProtectionAndMenus(market,booths,templates);
      testDeathRecoveryReceipt(market);
      testPartialRecoveryClaims(market);
      testMergedReceiptDeath(market);
      testPartialClaimFaults(market);
      testRecoveryClaimMenus(market);
      testEssentialsEconomy(market,booths);
      testVaultEconomy(market,booths);
      testWholeRecoveryClaims(market,()->{
        try{testResetAndRecovery(market,booths,templates,restartMarker);}catch(Throwable ex){fail("ACCEPTANCE FAIL",ex);}
      });
    } catch(Throwable t) { fail("ACCEPTANCE FAIL",t); }
  }
  /** Test-fixture only: builds each bundled style at rotations 0–3 inside marketmain, then lets the production scanner register them. */
  private void setupAcceptanceWorld(Object market,File flag)throws Exception{
    Map<?,?> templates=(Map<?,?>)field(field(market,"templates"),"templates");
    World world=Bukkit.getWorld("world");int y=-60,spacing=48,row=0;
    java.lang.reflect.Constructor<?> boothType=Class.forName("dev.skyboxy.market.Booth",true,market.getClass().getClassLoader())
            .getDeclaredConstructor(String.class,String.class,String.class,int.class,int.class,int.class,int.class,int.class,int.class,int.class);
    boothType.setAccessible(true);
    for(int cx=-2;cx<=(4*spacing)>>4;cx++)for(int cz=-2;cz<=(2*spacing)>>4;cz++)world.setChunkForceLoaded(cx,cz,true);
    for(String style:new TreeSet<>(templates.keySet().stream().map(String::valueOf).toList())){
      Object template=templates.get(style);
      for(int rotation=0;rotation<4;rotation++){
        Object booth=boothType.newInstance("fixture-"+style+"-"+rotation,"world",style,rotation*spacing,y,row*spacing,
                (int)field(template,"width"),(int)field(template,"height"),(int)field(template,"length"),rotation);
        Method restore=template.getClass().getDeclaredMethod("restore",booth.getClass());restore.setAccessible(true);restore.invoke(template,booth);
      }
      row++;
    }
    world.save();
    Object scanner=field(market,"scanner");call(scanner,"scan",new Class<?>[]{org.bukkit.entity.Player.class},(Object)null);
    new BukkitRunnable(){int ticks=0;@Override public void run(){
      try{
        int found=((List<?>)field(market,"booths")).size();
        if(found>=8&&!(boolean)call(scanner,"running",new Class<?>[0])){
          cancel();for(int cx=-2;cx<=(4*spacing)>>4;cx++)for(int cz=-2;cz<=(2*spacing)>>4;cz++)world.setChunkForceLoaded(cx,cz,false);
          if(found!=8)throw new IllegalStateException("Fixture scan registered "+found+" booths, expected 8");
          if(!flag.delete())throw new IllegalStateException("Could not remove setup flag");
          getLogger().info("WORLD SETUP PASS scanner registered 8 fixture booths");Bukkit.getScheduler().runTaskLater(PaperAcceptanceHarness.this,Bukkit::shutdown,20L);
        }else if(++ticks>20*90){cancel();throw new IllegalStateException("Fixture scan found "+found+" booths after 90 s; scanner: "+field(scanner,"lastResult")+"; block at origin "+world.getBlockAt(0,-60,0).getType()+"/"+world.getBlockAt(5,-55,5).getType());}
      }catch(Throwable ex){cancel();fail("WORLD SETUP FAIL",ex);}
    }}.runTaskTimer(this,20L,1L);
  }
  private void continueRestartAcceptance(SkyBoxyMarket market,File marker)throws Exception {
    Properties properties=new Properties();try(var input=Files.newInputStream(marker.toPath())){properties.load(input);}
    String phase=properties.getProperty("phase"),boothId=properties.getProperty("booth"),label=properties.getProperty("label");
    UUID owner=UUID.fromString(properties.getProperty("owner"));Object booth=call(market,"byId",new Class<?>[]{String.class},boothId);Object db=field(market,"db");
    if(booth==null||field(booth,"owner")!=null||Boolean.TRUE.equals(field(booth,"resetting"))||(int)call(db,"resetCount",new Class<?>[0])!=0)
      throw new IllegalStateException("Paper restart did not finish the interrupted "+phase+" reset");
    @SuppressWarnings("unchecked") List<?> recovered=(List<?>)call(db,"recovered",new Class<?>[]{UUID.class,int.class,int.class},owner,0,45);
    if(recovered.size()!=1)throw new IllegalStateException("Restarted reset should have exactly one durable item, found "+recovered.size());
    ItemStack recoveredItem=(ItemStack)call(recovered.get(0),"item",new Class<?>[0]);
    String recoveredLabel=recoveredItem.getItemMeta().getPersistentDataContainer().get(new NamespacedKey(this,"case"),PersistentDataType.STRING);
    if(!label.equals(recoveredLabel))throw new IllegalStateException("Restarted reset lost its item metadata: "+recoveredLabel);
    String style=(String)field(booth,"style");Object template=((Map<?,?>)field(field(market,"templates"),"templates")).get(style);
    Method matches=template.getClass().getDeclaredMethod("matches",World.class,int.class,int.class,int.class,int.class,boolean.class);matches.setAccessible(true);
    World world=Bukkit.getWorld((String)field(booth,"world"));
    if(!(boolean)matches.invoke(template,world,(int)field(booth,"x"),(int)field(booth,"y"),(int)field(booth,"z"),(int)field(booth,"rotation"),true))throw new IllegalStateException("Restarted reset did not restore the schematic");
    getLogger().info("RESET RESTART PASS phase="+phase+" booth="+field(booth,"id")+" recoveryLabel="+label+"; journal retained once and schematic restored");
    if("SNAPSHOTTING".equals(phase)){
      stageInterruptedReset(market,marker,"RESTORING");
      Bukkit.getScheduler().runTaskLater(this,Bukkit::shutdown,20L);
    }else if("RESTORING".equals(phase)){
      stageInterruptedReset(market,marker,"HARD_CRASH_RESTORE");
      getLogger().info("RESET HARD-CRASH TEST PREPARED from a durable RESTORING journal");
      Bukkit.getScheduler().runTaskLater(this,Bukkit::shutdown,20L);
    }else if("VERIFY_HARD_CRASH".equals(phase)){
      if(!marker.delete())throw new IllegalStateException("Could not remove hard-crash acceptance marker");
      getLogger().info("ACCEPTANCE PASS: reset recovered after abrupt JVM termination from a durably flushed restore batch");
      Bukkit.getScheduler().runTaskLater(this,Bukkit::shutdown,20L);
    }else if("HARD_CRASH_RESTORE".equals(phase)){
      throw new IllegalStateException("Hard-crash monitor did not observe a committed restore batch before acceptance timeout");
    }else throw new IllegalStateException("Unknown restart acceptance phase: "+phase);
  }
  private void stageInterruptedReset(SkyBoxyMarket market,File marker,String phase)throws Exception {
    int rotation="SNAPSHOTTING".equals(phase)?2:"RESTORING".equals(phase)?3:2;
    List<?> booths=(List<?>)field(market,"booths");
    Object booth=booths.stream().filter(b->{try{return field(b,"style").equals("normal")&&(int)field(b,"rotation")==rotation&&field(b,"owner")==null&&!Boolean.TRUE.equals(field(b,"resetting"));}catch(Exception ex){return false;}}).findFirst()
            .orElseThrow(()->new IllegalStateException("No free Normal rotation "+rotation+" booth for restart test"));
    UUID owner=UUID.randomUUID();String lease="restart-"+phase.toLowerCase(Locale.ROOT)+"-"+UUID.randomUUID();
    String label="restart-"+phase.toLowerCase(Locale.ROOT)+"-"+UUID.randomUUID();World world=Bukkit.getWorld((String)field(booth,"world"));
    int x=(int)field(booth,"x"),y=(int)field(booth,"y"),z=(int)field(booth,"z");
    world.dropItem(new org.bukkit.Location(world,x+3,y+2,z+3),tagged(Material.EMERALD,label,3));
    setField(booth,"owner",owner);setField(booth,"leaseId",lease);setField(booth,"expiry",System.currentTimeMillis()+3600000L);
    Object db=field(market,"db");call(db,"save",new Class<?>[]{booth.getClass()},booth);call(db,"beginReset",new Class<?>[]{booth.getClass()},booth);
    if("RESTORING".equals(phase)||"HARD_CRASH_RESTORE".equals(phase)){
      Object recovery=field(market,"recovery");Method snapshot=recovery.getClass().getDeclaredMethod("snapshot",booth.getClass(),UUID.class,String.class,World.class);snapshot.setAccessible(true);
      snapshot.invoke(recovery,booth,owner,lease,world);call(db,"resetPhase",new Class<?>[]{booth.getClass(),String.class},booth,"RESTORING");
      if("RESTORING".equals(phase)){
        Object template=((Map<?,?>)field(field(market,"templates"),"templates")).get(field(booth,"style"));
        Method restoreBatch=template.getClass().getDeclaredMethod("restoreBatch",booth.getClass(),int.class,int.class);restoreBatch.setAccessible(true);
        int restored=(int)restoreBatch.invoke(template,booth,0,32);
        world.save(true);
        call(db,"resetCursor",new Class<?>[]{booth.getClass(),int.class},booth,restored);
        getLogger().info("RESET RESTART TEST staged a partially restored schematic at durable cursor="+restored);
      }
    }
    world.save();Properties properties=new Properties();properties.setProperty("phase",phase);properties.setProperty("booth",(String)field(booth,"id"));
    properties.setProperty("owner",owner.toString());properties.setProperty("label",label);
    File parent=marker.getParentFile();if(!parent.exists()&&!parent.mkdirs())throw new IllegalStateException("Could not create smoke data folder");
    if("HARD_CRASH_RESTORE".equals(phase)){
      int cursor=0;for(Object reset:(List<?>)call(db,"resets",new Class<?>[0]))
        if(((String)call(reset,"booth",new Class<?>[0])).equals((String)field(booth,"id")))cursor=(int)call(reset,"restoreCursor",new Class<?>[0]);
      properties.setProperty("cursor",Integer.toString(cursor));
    }
    try(var output=Files.newOutputStream(marker.toPath())){properties.store(output,"Paper reset restart acceptance");}
    getLogger().info("RESET RESTART PREPARED phase="+phase+" booth="+field(booth,"id")+" label="+label);
  }
  private void testProtectionAndMenus(Object market,List<?> booths,Map<?,?> templates) throws Exception {
    JavaPlugin plugin=(JavaPlugin)market;
    // Keep this runtime acceptance deterministic across retries after an interrupted test run.
    plugin.getConfig().set("permissions.visitor.place",false);
    plugin.saveConfig();
    Object booth=booths.stream().filter(b->{try{return field(b,"style").equals("normal")&&field(b,"rotation").equals(1);}catch(Exception e){return false;}}).findFirst().orElseThrow();
    World world=Bukkit.getWorld((String)field(booth,"world"));Object template=templates.get("normal");
    Inventory[] opened={null};Player visitor=fakePlayer(UUID.randomUUID(),Set.of(),opened);Object menus=field(market,"menus");
    invoke(menus,"player",new Class<?>[]{Player.class},visitor);assertMenuActions(opened[0],(Plugin)market,Set.of("browse:0","recover:0"));
    invoke(menus,"browse",new Class<?>[]{Player.class,int.class},visitor,0);assertMenuActions(opened[0],(Plugin)market,Set.of("player"));
    Player admin=fakePlayer(UUID.randomUUID(),Set.of("skyboxymarket.admin"),opened);
    invoke(menus,"admin",new Class<?>[]{Player.class},admin);assertMenuActions(opened[0],(Plugin)market,Set.of("scan","styles:0","permissions:VISITOR","adminbooths:0","diagnostics","backup","paymentreviews:0","ownersearch"));
    invoke(menus,"route",new Class<?>[]{Player.class,String.class},admin,"diagnostics");assertMenuActions(opened[0],(Plugin)market,Set.of("admin"));
    Object db=field(market,"db");String paymentId=(String)call(db,"createPaymentIntent",new Class<?>[]{UUID.class,String.class,String.class,double.class,String.class},UUID.randomUUID(),"smoke-booth","RENT",12.5,"acceptance provider");
    call(db,"transitionPaymentIntent",new Class<?>[]{String.class,String.class,String.class,String.class},paymentId,"PREPARED","CHARGED","Injected interrupted provider boundary");
    call(db,"reviewInterruptedPaymentIntents",new Class<?>[0]);
    invoke(menus,"route",new Class<?>[]{Player.class,String.class},admin,"paymentreviews:0");assertMenuActions(opened[0],(Plugin)market,Set.of("paymentdetail:"+paymentId));
    invoke(menus,"route",new Class<?>[]{Player.class,String.class},admin,"paymentdetail:"+paymentId);assertMenuActions(opened[0],(Plugin)market,Set.of("paymentresolveconfirm:"+paymentId+":NO_CHARGE_VERIFIED"));
    invoke(menus,"route",new Class<?>[]{Player.class,String.class},admin,"paymentresolveconfirm:"+paymentId+":NO_CHARGE_VERIFIED");assertMenuActions(opened[0],(Plugin)market,Set.of("paymentresolve:"+paymentId+":NO_CHARGE_VERIFIED"));
    invoke(menus,"route",new Class<?>[]{Player.class,String.class},admin,"paymentresolve:"+paymentId+":NO_CHARGE_VERIFIED");
    Object reviewed=call(db,"paymentIntent",new Class<?>[]{String.class},paymentId);
    if(!"NO_CHARGE_VERIFIED".equals(call(reviewed,"status",new Class<?>[0])))throw new IllegalStateException("Admin payment review did not persist its verified outcome");
    File backupDirectory=new File(((JavaPlugin)market).getDataFolder(),"backups");int priorBackups=backupDirectory.listFiles((directory,name)->name.endsWith(".db"))==null?0:backupDirectory.listFiles((directory,name)->name.endsWith(".db")).length;
    invoke(menus,"route",new Class<?>[]{Player.class,String.class},admin,"backup");
    int backupCount=backupDirectory.listFiles((directory,name)->name.endsWith(".db")).length;
    if(backupCount!=priorBackups+1)throw new IllegalStateException("Admin GUI did not create one consistent database backup");
    if(HandlerList.getRegisteredListeners((Plugin)market).stream().noneMatch(reg->reg.getListener().getClass().getName().endsWith("MarketProtection")))
      throw new IllegalStateException("MarketProtection listener was not registered");

    UUID owner=UUID.randomUUID();setField(booth,"owner",owner);setField(booth,"leaseId","protection-"+UUID.randomUUID());setField(booth,"expiry",System.currentTimeMillis()+3600000L);
    Player renter=fakePlayer(owner,Set.of(),opened);
    Method save=db.getClass().getDeclaredMethod("save",booth.getClass());save.setAccessible(true);save.invoke(db,booth);
    invoke(menus,"route",new Class<?>[]{Player.class,String.class},admin,"ownersearch");
    Class<?> chatInputType=Class.forName("dev.skyboxy.market.MarketChatInput",true,market.getClass().getClassLoader());
    Constructor<?> chatInputConstructor=chatInputType.getDeclaredConstructor(market.getClass());chatInputConstructor.setAccessible(true);Object chatInput=chatInputConstructor.newInstance(market);
    invoke(chatInput,"process",new Class<?>[]{Player.class,String.class,String.class},admin,"ownersearch",owner.toString());
    assertMenuActions(opened[0],(Plugin)market,Set.of("edit:"+field(booth,"id")));
    String access=(String)field(booth,"access");invoke(menus,"route",new Class<?>[]{Player.class,String.class},visitor,"access:"+field(booth,"id"));
    if(!access.equals(field(booth,"access")))throw new IllegalStateException("A visitor changed another renter's booth access from a stale GUI action");
    Object secondOwned=booths.stream().filter(candidate->{try{return !field(candidate,"id").equals(field(booth,"id"))&&field(candidate,"owner")==null&&!Boolean.TRUE.equals(field(candidate,"resetting"));}catch(Exception e){return false;}}).findFirst().orElseThrow();
    Object previousLease=field(secondOwned,"leaseId");long previousExpiry=(long)field(secondOwned,"expiry");
    setField(secondOwned,"owner",owner);setField(secondOwned,"leaseId","multi-booth-selector-"+UUID.randomUUID());setField(secondOwned,"expiry",System.currentTimeMillis()+3600000L);
    Method saveBooth=db.getClass().getDeclaredMethod("save",secondOwned.getClass());saveBooth.setAccessible(true);saveBooth.invoke(db,secondOwned);
    invoke(menus,"player",new Class<?>[]{Player.class,int.class},renter,0);
    assertMenuActions(opened[0],(Plugin)market,Set.of("detail:"+field(booth,"id"),"detail:"+field(secondOwned,"id")));
    invoke(menus,"myWalls",new Class<?>[]{Player.class,int.class},renter,0);
    assertMenuActions(opened[0],(Plugin)market,Set.of("walls:"+field(booth,"id"),"walls:"+field(secondOwned,"id")));
    invoke(menus,"myTeleport",new Class<?>[]{Player.class,int.class},renter,0);
    assertMenuActions(opened[0],(Plugin)market,Set.of("tp:"+field(booth,"id"),"tp:"+field(secondOwned,"id")));
    setField(secondOwned,"owner",null);setField(secondOwned,"leaseId",previousLease);setField(secondOwned,"expiry",previousExpiry);saveBooth.invoke(db,secondOwned);

    List<?> cells=(List<?>)field(template,"cells");Object airCell=cells.stream().filter(c->{try{return field(c,"material")==Material.AIR;}catch(Exception e){return false;}}).findFirst().orElseThrow();
    Method rotateX=template.getClass().getDeclaredMethod("rotateX",int.class,int.class,int.class);rotateX.setAccessible(true);
    Method rotateZ=template.getClass().getDeclaredMethod("rotateZ",int.class,int.class,int.class);rotateZ.setAccessible(true);
    int rotation=(int)field(booth,"rotation"),ax=(int)rotateX.invoke(template,(int)field(airCell,"x"),(int)field(airCell,"z"),rotation),az=(int)rotateZ.invoke(template,(int)field(airCell,"x"),(int)field(airCell,"z"),rotation);
    Block decoration=world.getBlockAt((int)field(booth,"x")+ax,(int)field(booth,"y")+(int)field(airCell,"y"),(int)field(booth,"z")+az);
    Method clearStalePlacement=db.getClass().getDeclaredMethod("breakPlaced",booth.getClass(),int.class,int.class,int.class);clearStalePlacement.setAccessible(true);
    clearStalePlacement.invoke(db,booth,decoration.getX(),decoration.getY(),decoration.getZ());
    decoration.setType(Material.GLOWSTONE,false);
    Object protection=HandlerList.getRegisteredListeners((Plugin)market).stream().map(reg->reg.getListener())
            .filter(listener->listener.getClass().getName().endsWith("MarketProtection")).findFirst().orElseThrow();
    BlockPlaceEvent renterPlace=placeEvent(decoration,renter);invoke(protection,"place",new Class<?>[]{BlockPlaceEvent.class},renterPlace);
    if(renterPlace.isCancelled())throw new IllegalStateException("Renter could not place decor in an air cell");
    BlockPlaceEvent visitorPlace=placeEvent(decoration,visitor);invoke(protection,"place",new Class<?>[]{BlockPlaceEvent.class},visitorPlace);
    if(!visitorPlace.isCancelled())throw new IllegalStateException("Visitor could place decor in a rented booth");

    UUID visitorId=visitor.getUniqueId();
    @SuppressWarnings("unchecked") Set<UUID> banned=(Set<UUID>)field(booth,"banned");banned.add(visitorId);
    Location outside=new Location(world,(int)field(booth,"x")-1.5,(int)field(booth,"y")+1,(int)field(booth,"z")+1.5);
    Location inside=new Location(world,(int)field(booth,"x")+1.5,(int)field(booth,"y")+1,(int)field(booth,"z")+1.5);
    org.bukkit.event.player.PlayerMoveEvent moveEvent=new org.bukkit.event.player.PlayerMoveEvent(visitor,outside,inside);
    invoke(protection,"move",new Class<?>[]{org.bukkit.event.player.PlayerMoveEvent.class},moveEvent);
    if(moveEvent.getTo()==null||moveEvent.getTo().getBlockX()!=outside.getBlockX())throw new IllegalStateException("Banned visitor crossed the booth boundary by walking");
    org.bukkit.event.player.PlayerTeleportEvent teleportEvent=new org.bukkit.event.player.PlayerTeleportEvent(visitor,outside,inside);
    invoke(protection,"teleport",new Class<?>[]{org.bukkit.event.player.PlayerTeleportEvent.class},teleportEvent);
    if(!teleportEvent.isCancelled())throw new IllegalStateException("Banned visitor teleported into the booth");
    banned.remove(visitorId);
    @SuppressWarnings("unchecked") Set<UUID> trusted=(Set<UUID>)field(booth,"trusted");String priorAccess=(String)field(booth,"access");
    setField(booth,"access","PRIVATE");trusted.add(visitorId);
    org.bukkit.event.player.PlayerMoveEvent trustedEntry=new org.bukkit.event.player.PlayerMoveEvent(visitor,outside,inside);
    invoke(protection,"move",new Class<?>[]{org.bukkit.event.player.PlayerMoveEvent.class},trustedEntry);
    if(trustedEntry.getTo()==null||trustedEntry.getTo().getBlockX()!=inside.getBlockX())throw new IllegalStateException("Trusted member could not enter a private booth");
    trusted.remove(visitorId);
    org.bukkit.event.player.PlayerMoveEvent privateVisitor=new org.bukkit.event.player.PlayerMoveEvent(visitor,outside,inside);
    invoke(protection,"move",new Class<?>[]{org.bukkit.event.player.PlayerMoveEvent.class},privateVisitor);
    if(privateVisitor.getTo()==null||privateVisitor.getTo().getBlockX()!=outside.getBlockX())throw new IllegalStateException("Visitor entered a private booth");
    setField(booth,"access","LOCKED");
    org.bukkit.event.player.PlayerMoveEvent lockedRenter=new org.bukkit.event.player.PlayerMoveEvent(renter,outside,inside);
    invoke(protection,"move",new Class<?>[]{org.bukkit.event.player.PlayerMoveEvent.class},lockedRenter);
    if(lockedRenter.getTo()==null||lockedRenter.getTo().getBlockX()!=inside.getBlockX())throw new IllegalStateException("Owner could not enter a locked booth");
    banned.add(visitorId);Player bypassEntry=fakePlayer(UUID.randomUUID(),Set.of("skyboxymarket.bypass"),opened);
    org.bukkit.event.player.PlayerMoveEvent bypassVisitor=new org.bukkit.event.player.PlayerMoveEvent(bypassEntry,outside,inside);
    invoke(protection,"move",new Class<?>[]{org.bukkit.event.player.PlayerMoveEvent.class},bypassVisitor);
    if(bypassVisitor.getTo()==null||bypassVisitor.getTo().getBlockX()!=inside.getBlockX())throw new IllegalStateException("Protection bypass could not enter a banned booth");
    banned.remove(visitorId);setField(booth,"access",priorAccess);

    Object secondAir=cells.stream().filter(c->{try{return field(c,"material")==Material.AIR&&c!=airCell;}catch(Exception e){return false;}}).findFirst().orElseThrow();
    int chestX=(int)rotateX.invoke(template,(int)field(secondAir,"x"),(int)field(secondAir,"z"),rotation);
    int chestZ=(int)rotateZ.invoke(template,(int)field(secondAir,"x"),(int)field(secondAir,"z"),rotation);
    Block chestBlock=world.getBlockAt((int)field(booth,"x")+chestX,(int)field(booth,"y")+(int)field(secondAir,"y"),(int)field(booth,"z")+chestZ);
    chestBlock.setType(Material.CHEST,false);
    org.bukkit.event.player.PlayerInteractEvent openChest=new org.bukkit.event.player.PlayerInteractEvent(visitor,org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK,
            new ItemStack(Material.AIR),chestBlock,BlockFace.NORTH,EquipmentSlot.HAND);
    invoke(protection,"interact",new Class<?>[]{org.bukkit.event.player.PlayerInteractEvent.class},openChest);
    if(!openChest.isCancelled())throw new IllegalStateException("Visitor opened ordinary private storage in a rented booth");
    org.bukkit.event.block.BlockFromToEvent fluid=new org.bukkit.event.block.BlockFromToEvent(decoration,BlockFace.EAST);
    invoke(protection,"fluid",new Class<?>[]{org.bukkit.event.block.BlockFromToEvent.class},fluid);
    if(!fluid.isCancelled())throw new IllegalStateException("Fluid crossed into a protected market booth");
    org.bukkit.event.block.BlockPistonExtendEvent piston=new org.bukkit.event.block.BlockPistonExtendEvent(decoration,List.of(decoration),BlockFace.EAST);
    invoke(protection,"piston",new Class<?>[]{org.bukkit.event.block.BlockPistonExtendEvent.class},piston);
    if(!piston.isCancelled())throw new IllegalStateException("Piston moved a protected market block");
    org.bukkit.event.block.BlockDispenseEvent dispense=new org.bukkit.event.block.BlockDispenseEvent(decoration,new ItemStack(Material.ARROW),new org.bukkit.util.Vector(0,1,0));
    invoke(protection,"dispense",new Class<?>[]{org.bukkit.event.block.BlockDispenseEvent.class},dispense);
    if(!dispense.isCancelled())throw new IllegalStateException("A dispenser activated inside a protected market booth");
    org.bukkit.event.inventory.InventoryMoveItemEvent hopperFlow=new org.bukkit.event.inventory.InventoryMoveItemEvent(locationInventory(chestBlock.getLocation()),new ItemStack(Material.DIAMOND),
            locationInventory(new Location(world,10000,64,10000)),true);
    invoke(protection,"hopper",new Class<?>[]{org.bukkit.event.inventory.InventoryMoveItemEvent.class},hopperFlow);
    if(!hopperFlow.isCancelled())throw new IllegalStateException("A hopper moved items across the booth boundary");
    Entity explosive=world.spawnEntity(inside,org.bukkit.entity.EntityType.CREEPER);
    List<Block> explosionBlocks=new ArrayList<>(List.of(decoration));
    org.bukkit.event.entity.EntityExplodeEvent explosion=new org.bukkit.event.entity.EntityExplodeEvent(explosive,inside,explosionBlocks,2.0f,org.bukkit.ExplosionResult.DESTROY);
    invoke(protection,"explode",new Class<?>[]{org.bukkit.event.entity.EntityExplodeEvent.class},explosion);
    explosive.remove();
    if(!explosionBlocks.isEmpty())throw new IllegalStateException("An explosion retained protected booth blocks");
    setField(booth,"access","LOCKED");
    org.bukkit.entity.Vehicle vehicle=(org.bukkit.entity.Vehicle)world.spawnEntity(inside,org.bukkit.entity.EntityType.MINECART);
    org.bukkit.event.vehicle.VehicleEnterEvent vehicleEnter=new org.bukkit.event.vehicle.VehicleEnterEvent(vehicle,visitor);
    invoke(protection,"vehicleEnter",new Class<?>[]{org.bukkit.event.vehicle.VehicleEnterEvent.class},vehicleEnter);
    vehicle.remove();setField(booth,"access",priorAccess);
    if(!vehicleEnter.isCancelled())throw new IllegalStateException("A banned visitor entered a locked booth by vehicle");

    Object structuralCell=cells.stream().filter(c->{try{return field(c,"material")!=Material.AIR;}catch(Exception e){return false;}}).findFirst().orElseThrow();
    int sx=(int)rotateX.invoke(template,(int)field(structuralCell,"x"),(int)field(structuralCell,"z"),rotation),sz=(int)rotateZ.invoke(template,(int)field(structuralCell,"x"),(int)field(structuralCell,"z"),rotation);
    Block structural=world.getBlockAt((int)field(booth,"x")+sx,(int)field(booth,"y")+(int)field(structuralCell,"y"),(int)field(booth,"z")+sz);
    BlockPlaceEvent renterShell=placeEvent(structural,renter);invoke(protection,"place",new Class<?>[]{BlockPlaceEvent.class},renterShell);
    if(!renterShell.isCancelled())throw new IllegalStateException("Renter could modify immutable template structure");
    Player bypass=fakePlayer(UUID.randomUUID(),Set.of("skyboxymarket.bypass"),opened);
    BlockPlaceEvent staffShell=placeEvent(structural,bypass);invoke(protection,"place",new Class<?>[]{BlockPlaceEvent.class},staffShell);
    if(staffShell.isCancelled())throw new IllegalStateException("Staff bypass could not edit immutable template structure");
    invoke(protection,"placed",new Class<?>[]{BlockPlaceEvent.class},staffShell);
    Method wasPlaced=db.getClass().getDeclaredMethod("wasPlaced",booth.getClass(),int.class,int.class,int.class);wasPlaced.setAccessible(true);
    if((boolean)wasPlaced.invoke(db,booth,structural.getX(),structural.getY(),structural.getZ()))throw new IllegalStateException("Staff bypass placement was incorrectly assigned to the renter");

    BlockBreakEvent renterUntracked=new BlockBreakEvent(decoration,renter);invoke(protection,"breakBlock",new Class<?>[]{BlockBreakEvent.class},renterUntracked);
    if(!renterUntracked.isCancelled())throw new IllegalStateException("Renter could break an untracked decorative block");
    Method recordPlaced=db.getClass().getDeclaredMethod("place",booth.getClass(),int.class,int.class,int.class,Material.class,UUID.class,ItemStack.class);recordPlaced.setAccessible(true);
    recordPlaced.invoke(db,booth,decoration.getX(),decoration.getY(),decoration.getZ(),Material.GLOWSTONE,owner,new ItemStack(Material.GLOWSTONE));
    BlockBreakEvent renterTracked=new BlockBreakEvent(decoration,renter);invoke(protection,"breakBlock",new Class<?>[]{BlockBreakEvent.class},renterTracked);
    if(renterTracked.isCancelled())throw new IllegalStateException("Renter could not break a tracked decorative block");
    BlockBreakEvent staffBreak=new BlockBreakEvent(structural,bypass);invoke(protection,"breakBlock",new Class<?>[]{BlockBreakEvent.class},staffBreak);
    if(staffBreak.isCancelled())throw new IllegalStateException("Staff bypass could not break immutable template structure");

    boolean visitorPlaceRule=plugin.getConfig().getBoolean("permissions.visitor.place",false);
    invoke(menus,"route",new Class<?>[]{Player.class,String.class},admin,"perm:VISITOR:place");
    BlockPlaceEvent toggledVisitorPlace=placeEvent(decoration,visitor);invoke(protection,"place",new Class<?>[]{BlockPlaceEvent.class},toggledVisitorPlace);
    boolean toggledRule=plugin.getConfig().getBoolean("permissions.visitor.place",false);
    if(toggledRule==visitorPlaceRule||toggledVisitorPlace.isCancelled()==toggledRule)throw new IllegalStateException("GUI visitor.place toggle did not change event enforcement");
    invoke(menus,"route",new Class<?>[]{Player.class,String.class},admin,"perm:VISITOR:place");
    if(plugin.getConfig().getBoolean("permissions.visitor.place",false)!=visitorPlaceRule)throw new IllegalStateException("GUI permission toggle failed to restore its original setting");

    getLogger().info("GUI/PROTECTION PASS native inventories, multi-booth selection, listener registration, access authority, renter decor, structural mask, staff bypass, and live permission toggles");
  }
  private void testWallMasks(List<?> booths,Map<?,?> templates)throws Exception{
    List<Material> colors=Arrays.stream(Material.values()).filter(material->material.name().endsWith("_CONCRETE")).toList();
    if(colors.size()!=16)throw new IllegalStateException("Expected all 16 concrete colors, found "+colors.size());
    for(int rotation=0;rotation<4;rotation++){
      final int boothRotation=rotation;
      Object booth=booths.stream().filter(candidate->{try{return "normal".equals(field(candidate,"style"))&&(int)field(candidate,"rotation")==boothRotation;}catch(Exception ex){return false;}}).findFirst().orElseThrow();
      World world=Bukkit.getWorld((String)field(booth,"world"));Object template=templates.get("normal");List<?> cells=(List<?>)field(template,"cells");
      Method recolor=template.getClass().getDeclaredMethod("recolor",booth.getClass(),Material.class);recolor.setAccessible(true);
      Method rotateX=template.getClass().getDeclaredMethod("rotateX",int.class,int.class,int.class);rotateX.setAccessible(true);
      Method rotateZ=template.getClass().getDeclaredMethod("rotateZ",int.class,int.class,int.class);rotateZ.setAccessible(true);
      for(Material color:colors){
        recolor.invoke(template,booth,color);
        for(Object cell:cells){
          boolean wall=(boolean)field(cell,"wall");Material original=(Material)field(cell,"material");
          if(original.isAir())continue;
          int x=(int)rotateX.invoke(template,(int)field(cell,"x"),(int)field(cell,"z"),rotation);
          int z=(int)rotateZ.invoke(template,(int)field(cell,"x"),(int)field(cell,"z"),rotation);
          Material expected=wall?color:original;Material actual=world.getBlockAt((int)field(booth,"x")+x,(int)field(booth,"y")+(int)field(cell,"y"),(int)field(booth,"z")+z).getType();
          if(actual!=expected)throw new IllegalStateException("Wall mask changed the wrong block for rotation "+rotation+" color "+color+" at local "+field(cell,"x")+","+field(cell,"y")+","+field(cell,"z")+": expected "+expected+", found "+actual);
        }
      }
      recolor.invoke(template,booth,Material.GRAY_CONCRETE);
    }
    getLogger().info("WALL MASK PASS 16 concrete colors across four rotations; non-wall schematic blocks remained unchanged");
  }
  private void testSpatialLookupBudget(Object market)throws Exception{
    ClassLoader loader=market.getClass().getClassLoader();Class<?> boothType=Class.forName("dev.skyboxy.market.Booth",true,loader);
    Constructor<?> boothConstructor=boothType.getDeclaredConstructor(String.class,String.class,String.class,int.class,int.class,int.class,int.class,int.class,int.class,int.class);boothConstructor.setAccessible(true);
    Class<?> indexType=Class.forName("dev.skyboxy.market.BoothSpatialIndex",true,loader);Constructor<?> indexConstructor=indexType.getDeclaredConstructor();indexConstructor.setAccessible(true);
    Object index=indexConstructor.newInstance();Method add=indexType.getDeclaredMethod("add",boothType);add.setAccessible(true);
    Method lookup=indexType.getDeclaredMethod("at",String.class,int.class,int.class,int.class);lookup.setAccessible(true);ArrayList<Object> sample=new ArrayList<>(1000);int[][] coordinates=new int[1000][3];
    for(int i=0;i<1000;i++){coordinates[i]=new int[]{i*32,64,-i*32};Object booth=boothConstructor.newInstance("benchmark-"+i,"benchmark-world","normal",coordinates[i][0],coordinates[i][1],coordinates[i][2],11,11,11,0);sample.add(booth);add.invoke(index,booth);}
    for(int i=0;i<10_000;i++){int indexNumber=i%sample.size();Object booth=sample.get(indexNumber);int[] xyz=coordinates[indexNumber];if(lookup.invoke(index,"benchmark-world",xyz[0]+5,xyz[1]+2,xyz[2]+5)!=booth)throw new IllegalStateException("Spatial lookup warm-up missed a booth");}
    long started=System.nanoTime();
    for(int i=0;i<100_000;i++){int indexNumber=i%sample.size();Object booth=sample.get(indexNumber);int[] xyz=coordinates[indexNumber];if(lookup.invoke(index,"benchmark-world",xyz[0]+5,xyz[1]+2,xyz[2]+5)!=booth)throw new IllegalStateException("Spatial lookup missed a booth");}
    long millis=(System.nanoTime()-started)/1_000_000L;
    if(millis>5000)throw new IllegalStateException("100,000 lookups across 1,000 indexed booths exceeded the 5,000 ms smoke budget: "+millis+" ms");
    getLogger().info("SPATIAL LOOKUP PASS 1,000 booths; 100,000 queries in "+millis+" ms (5,000 ms budget)");
  }
  private void testHologramRefresh(Object market,List<?> booths)throws Exception{
    Object holograms=field(market,"holograms");
    call(holograms,"refreshAll",new Class<?>[0]);call(holograms,"refreshAll",new Class<?>[0]);
    NamespacedKey key=new NamespacedKey((Plugin)market,"booth_hologram");int verified=0;
    for(Object booth:booths){
      World world=Bukkit.getWorld((String)field(booth,"world"));Location center=(Location)call(booth,"center",new Class<?>[0]);
      if(world==null||center==null||!center.isChunkLoaded())continue;
      String boothId=(String)field(booth,"id");Entity[] tagged=world.getEntities().stream()
              .filter(entity->boothId.equals(entity.getPersistentDataContainer().get(key,PersistentDataType.STRING))).toArray(Entity[]::new);
      long displays=Arrays.stream(tagged).filter(entity->entity instanceof org.bukkit.entity.TextDisplay).count();
      long interactions=Arrays.stream(tagged).filter(entity->entity instanceof org.bukkit.entity.Interaction).count();
      if(displays!=1||interactions!=1||tagged.length!=2)throw new IllegalStateException("Repeated hologram refresh duplicated or lost a booth display/action for "+boothId);
      verified++;
    }
    if(verified==0)throw new IllegalStateException("Hologram refresh had no loaded booth chunks to verify");
    getLogger().info("HOLOGRAM PASS repeated refresh kept one TextDisplay and one Interaction for each of "+verified+" loaded booths");
  }
  private void testOptionalShopFailsClosed(Object market,List<?> booths)throws Exception{
    // Shop protection matrix shared by every provider: all of them route clicks and storage through the same two rules.
    Object matrixBooth=booths.getLast();Inventory[] none={null};
    UUID renterId=UUID.randomUUID(),memberId=UUID.randomUUID(),visitorId=UUID.randomUUID();
    Object priorOwner=field(matrixBooth,"owner"),priorAccess=field(matrixBooth,"access");setField(matrixBooth,"owner",renterId);
    @SuppressWarnings("unchecked") Set<UUID> trustedSet=(Set<UUID>)field(matrixBooth,"trusted"),bannedSet=(Set<UUID>)field(matrixBooth,"banned");trustedSet.add(memberId);
    Map<String,Player> roles=Map.of("renter",fakePlayer(renterId,Set.of(),none),"member",fakePlayer(memberId,Set.of(),none),"visitor",fakePlayer(visitorId,Set.of(),none));
    Method allowed=market.getClass().getDeclaredMethod("allowed",Player.class,matrixBooth.getClass(),String.class);allowed.setAccessible(true);
    for(String access:List.of("PUBLIC","PRIVATE","LOCKED")){setField(matrixBooth,"access",access);
      for(Map.Entry<String,Player> role:roles.entrySet()){
        boolean buy=(boolean)allowed.invoke(market,role.getValue(),matrixBooth,"shop-interact"),open=(boolean)allowed.invoke(market,role.getValue(),matrixBooth,"containers");
        if(!buy||open!=!role.getKey().equals("visitor"))throw new IllegalStateException("Shop matrix "+access+"/"+role.getKey()+": shop-interact="+buy+" containers="+open);
      }}
    bannedSet.add(visitorId);boolean bannedBuy=(boolean)allowed.invoke(market,roles.get("visitor"),matrixBooth,"shop-interact");bannedSet.remove(visitorId);
    trustedSet.remove(memberId);setField(matrixBooth,"owner",priorOwner);setField(matrixBooth,"access",priorAccess);
    getLogger().info("SHOP MATRIX PASS PUBLIC/PRIVATE/LOCKED x renter/member/visitor: everyone may use shops, only renter and members open storage; banned visitor shop-interact="+bannedBuy+" (bans gate entry only)");
    Object shops=field(market,"shops");
    for(Object provider:(List<?>)call(shops,"all",new Class<?>[0])){
      String name=(String)call(provider,"name",new Class<?>[0]);String status=String.valueOf(call(provider,"status",new Class<?>[0]));
      boolean installed=Bukkit.getPluginManager().getPlugin(name)!=null;
      if(!installed&&"AVAILABLE".equals(status))throw new IllegalStateException(name+" is not installed, but its provider reported itself available");
      if(installed&&!"AVAILABLE".equals(status))throw new IllegalStateException(name+" is installed, but its provider is "+status);
      getLogger().info("SHOP PROVIDER "+name+" installed="+installed+" status="+status);
    }
    if(Bukkit.getPluginManager().getPlugin("QuickShop-Hikari")==null){
      Object booth=booths.getFirst();call(shops,"detach",new Class<?>[]{booth.getClass()},booth);
      getLogger().info("QUICKSHOP FAIL-CLOSED PASS unavailable optional API did not enable shop deletion or sale accounting");
    }else testQuickShop(market,booths);
    if(Bukkit.getPluginManager().getPlugin("ChestShop")!=null)testChestShop(market,booths);
    if(Bukkit.getPluginManager().getPlugin("mc-PlayerShop")!=null)testPlayerShop(market,booths);
  }
  /** mc-PlayerShop keeps stock in its own database: reset must journal it once, even if repeated after a simulated crash. */
  @SuppressWarnings({"rawtypes","unchecked"})
  private void testPlayerShop(Object market,List<?> booths)throws Exception{
    Plugin plugin=Bukkit.getPluginManager().getPlugin("mc-PlayerShop");ClassLoader pl=plugin.getClass().getClassLoader();String base="com.mongenscave.mcplayershop.";
    Class<?> main=Class.forName(base+"McPlayerShop",true,pl),shopType=Class.forName(base+"shop.models.PlayerShop",true,pl);
    Object instance=main.getMethod("getInstance").invoke(null),service=main.getMethod("getShopService").invoke(instance);
    Object shopManager=main.getMethod("getShopManager").invoke(instance),storageManager=main.getMethod("getStorageManager").invoke(instance);
    Object db=field(market,"db"),shops=field(market,"shops");
    Object provider=((List<?>)call(shops,"all",new Class<?>[0])).stream().filter(x->{try{return "mc-PlayerShop".equals(call(x,"name",new Class<?>[0]));}catch(Exception e){return false;}}).findFirst().orElseThrow();
    Class<?> economyType=market.getClass().getClassLoader().loadClass("net.milkbowl.vault.economy.Economy");
    Object economy=Bukkit.getServicesManager().getRegistration((Class)economyType).getProvider();
    Method balance=economyType.getMethod("getBalance",org.bukkit.OfflinePlayer.class),deposit=economyType.getMethod("depositPlayer",org.bukkit.OfflinePlayer.class,double.class);

    Object booth=booths.stream().filter(b->{try{return field(b,"style").equals("premium_corner")&&(int)field(b,"rotation")==2&&field(b,"owner")==null;}catch(Exception e){return false;}}).findFirst().orElseThrow();
    UUID ownerId=UUID.randomUUID(),visitorId=UUID.randomUUID();Inventory[] opened={null};
    Player owner=fakePlayer(ownerId,Set.of(),opened,realBackedInventory()),visitor=fakePlayer(visitorId,Set.of(),opened,realBackedInventory());
    setField(booth,"owner",ownerId);setField(booth,"leaseId","smoke-ps-"+UUID.randomUUID());setField(booth,"expiry",System.currentTimeMillis()+3600000L);
    call(db,"save",new Class<?>[]{booth.getClass()},booth);
    World world=Bukkit.getWorld((String)field(booth,"world"));Object template=((Map<?,?>)field(field(market,"templates"),"templates")).get("premium_corner");
    int[] cell=null;for(Object c:(List<?>)field(template,"cells"))if(field(c,"material")==Material.AIR&&(int)field(c,"y")==1){cell=new int[]{(int)field(c,"x"),1,(int)field(c,"z")};break;}
    if(cell==null)throw new IllegalStateException("No floor-level air cell for the mc-PlayerShop barrel");
    Method rotateX=template.getClass().getDeclaredMethod("rotateX",int.class,int.class,int.class),rotateZ=template.getClass().getDeclaredMethod("rotateZ",int.class,int.class,int.class);
    rotateX.setAccessible(true);rotateZ.setAccessible(true);
    org.bukkit.block.Block barrel=world.getBlockAt((int)field(booth,"x")+(int)rotateX.invoke(template,cell[0],cell[2],2),(int)field(booth,"y")+1,(int)field(booth,"z")+(int)rotateZ.invoke(template,cell[0],cell[2],2));
    barrel.setType(Material.BARREL,false);

    // Create a 10-per-diamond selling shop the way mc-PlayerShop does, and stock ten diamonds in its database storage.
    service.getClass().getMethod("create",Player.class,Location.class,ItemStack.class).invoke(service,owner,barrel.getLocation(),new ItemStack(Material.DIAMOND));
    Object shop=((Optional<?>)shopManager.getClass().getMethod("get",Location.class).invoke(shopManager,barrel.getLocation())).orElseThrow(()->new IllegalStateException("mc-PlayerShop did not register the shop"));
    shopType.getMethod("setPrice",double.class).invoke(shop,10.0);service.getClass().getMethod("update",shopType).invoke(service,shop);
    UUID shopId=(UUID)shopType.getMethod("getShopId").invoke(shop);
    Object storage=storageManager.getClass().getMethod("getOrLoadSync",UUID.class).invoke(storageManager,shopId);
    storage.getClass().getMethod("add",ItemStack.class).invoke(storage,new ItemStack(Material.DIAMOND,10));
    storageManager.getClass().getMethod("saveAsync",storage.getClass()).invoke(storageManager,storage);
    Object owning=call(shops,"ownerOf",new Class<?>[]{Location.class},barrel.getLocation());
    if(owning==null||!"mc-PlayerShop".equals(call(owning,"name",new Class<?>[0])))throw new IllegalStateException("Registry did not attribute the barrel to mc-PlayerShop");

    // mc-PlayerShop ignores cancelled clicks, so its menu must be blocked when the booth denies shop-interact.
    Class<?> boundMenu=Class.forName(base+"guis.ShopBoundMenu",true,pl);Inventory[] menu={null};
    Object holder=Proxy.newProxyInstance(pl,new Class<?>[]{boundMenu,org.bukkit.inventory.InventoryHolder.class},(proxy,method,args)->switch(method.getName()){
      case "getShop"->shop;case "getInventory"->menu[0];case "isStale"->false;case "hashCode"->System.identityHashCode(proxy);case "equals"->proxy==args[0];default->null;});
    menu[0]=Bukkit.createInventory((org.bukkit.inventory.InventoryHolder)holder,9);
    org.bukkit.inventory.InventoryView view=(org.bukkit.inventory.InventoryView)Proxy.newProxyInstance(org.bukkit.inventory.InventoryView.class.getClassLoader(),new Class<?>[]{org.bukkit.inventory.InventoryView.class},
            (proxy,method,args)->switch(method.getName()){case "getTopInventory"->menu[0];case "getPlayer"->visitor;case "hashCode"->System.identityHashCode(proxy);case "equals"->proxy==args[0];default->null;});
    setField(booth,"suspended",true);
    org.bukkit.event.inventory.InventoryOpenEvent denied=new org.bukkit.event.inventory.InventoryOpenEvent(view);Bukkit.getPluginManager().callEvent(denied);
    setField(booth,"suspended",false);
    org.bukkit.event.inventory.InventoryOpenEvent permitted=new org.bukkit.event.inventory.InventoryOpenEvent(view);Bukkit.getPluginManager().callEvent(permitted);
    if(!denied.isCancelled()||permitted.isCancelled())throw new IllegalStateException("mc-PlayerShop menu guard: suspended="+denied.isCancelled()+" open="+permitted.isCancelled());

    // A real purchase through mc-PlayerShop's service; it pays the owner by UUID and records a BUY transaction.
    org.bukkit.OfflinePlayer ownerAccount=Bukkit.getOfflinePlayer(ownerId);
    try{economyType.getMethod("createPlayerAccount",org.bukkit.OfflinePlayer.class).invoke(economy,ownerAccount);}catch(Exception ignored){}
    // Essentials keeps proxy players and UUID lookups as separate accounts here, so fund both views of the buyer.
    deposit.invoke(economy,visitor,1000.0);deposit.invoke(economy,Bukkit.getOfflinePlayer(visitorId),1000.0);
    double ownerStart=(double)balance.invoke(economy,ownerAccount),salesStart=(double)field(booth,"sales");
    Object hook=main.getMethod("getCurrencyManager").invoke(instance);hook=hook.getClass().getMethod("get",String.class).invoke(hook,"vault");
    Object hookEconomy=hook.getClass().getMethod("economy").invoke(hook);
    economyType.getMethod("createPlayerAccount",org.bukkit.OfflinePlayer.class).invoke(economy,visitor); // Essentials refuses deposits to unknown users
    Object fund=deposit.invoke(economy,visitor,1000.0);
    getLogger().info("PLAYERSHOP FUND success="+fund.getClass().getMethod("transactionSuccess").invoke(fund)+" error="+fund.getClass().getField("errorMessage").get(fund)+" balance="+balance.invoke(economy,visitor)+" has="+economyType.getMethod("has",org.bukkit.OfflinePlayer.class,double.class).invoke(economy,visitor,20.0));
    getLogger().info("PLAYERSHOP ECONOMY hook="+hookEconomy.getClass().getName()+" sameAsServices="+(hookEconomy==economy)+" buyerHas20="+hook.getClass().getMethod("has",org.bukkit.OfflinePlayer.class,double.class).invoke(hook,visitor,20.0));
    Object bought=service.getClass().getMethod("buy",shopType,Player.class,int.class).invoke(service,shop,visitor,2);
    if(!"SUCCESS".equals(String.valueOf(bought)))throw new IllegalStateException("mc-PlayerShop purchase returned "+bought);
    double ownerGain=(double)balance.invoke(economy,ownerAccount)-ownerStart;int counted=0;
    for(int attempt=0;attempt<40&&counted==0;attempt++){counted=(int)call(provider,"syncSales",new Class<?>[]{booth.getClass()},booth);if(counted==0)Thread.sleep(50);}
    int recount=(int)call(provider,"syncSales",new Class<?>[]{booth.getClass()},booth);
    double salesGain=(double)field(booth,"sales")-salesStart;
    getLogger().info("PLAYERSHOP SALE OBSERVED result="+bought+" ownerGain="+ownerGain+" counted="+counted+" recount="+recount+" boothSalesGain="+salesGain);
    if(counted!=1||recount!=0||ownerGain<=0||Math.abs(salesGain-ownerGain)>0.001)throw new IllegalStateException("mc-PlayerShop sale not counted exactly once as owner revenue");

    // Simulated crash after journaling but before deletion, then the real reset: stock must be journaled once in total.
    call(provider,"journal",new Class<?>[]{booth.getClass(),Object.class},booth,shop);
    Object recovery=field(market,"recovery");invoke(recovery,"reset",new Class<?>[]{booth.getClass(),String.class},booth,"SMOKE_PLAYERSHOP");
    call(provider,"detach",new Class<?>[]{booth.getClass()},booth);
    if((boolean)call(shops,"shopAt",new Class<?>[]{Location.class},barrel.getLocation()))throw new IllegalStateException("Booth reset left the mc-PlayerShop shop registered");
    Object reloaded=storageManager.getClass().getMethod("getOrLoadSync",UUID.class).invoke(storageManager,shopId);
    if(!((List<?>)reloaded.getClass().getMethod("getContents").invoke(reloaded)).isEmpty())throw new IllegalStateException("mc-PlayerShop storage still holds stock after reset");
    int recovered=0;for(Object entry:(List<?>)call(db,"recovered",new Class<?>[]{UUID.class,int.class,int.class},ownerId,0,45))
      if(String.valueOf(call(entry,"source",new Class<?>[0])).startsWith("mcplayershop:"))recovered+=((ItemStack)call(entry,"item",new Class<?>[0])).getAmount();
    if(recovered!=8)throw new IllegalStateException("Expected eight database-held diamonds journaled once, found "+recovered);
    getLogger().info("PLAYERSHOP PASS menu blocked in suspended booth; sale counted once ("+salesGain+"); journal repeated after simulated crash and reset returned 8 stock items exactly once");
  }
  /** Drives ChestShop through real Bukkit event dispatch, so listener priorities apply exactly as on a live server. */
  @SuppressWarnings({"rawtypes","unchecked"})
  private void testChestShop(Object market,List<?> booths)throws Exception{
    ClassLoader cl=Bukkit.getPluginManager().getPlugin("ChestShop").getClass().getClassLoader();
    Class<?> signType=Class.forName("com.Acrobot.ChestShop.Signs.ChestShopSign",true,cl);
    Method isValid=signType.getMethod("isValid",org.bukkit.block.Block.class),isShopBlock=signType.getMethod("isShopBlock",org.bukkit.block.Block.class);
    Object db=field(market,"db"),shops=field(market,"shops");
    Class<?> economyType=market.getClass().getClassLoader().loadClass("net.milkbowl.vault.economy.Economy");
    Object economy=Bukkit.getServicesManager().getRegistration((Class)economyType).getProvider();
    Method balance=economyType.getMethod("getBalance",org.bukkit.OfflinePlayer.class),deposit=economyType.getMethod("depositPlayer",org.bukkit.OfflinePlayer.class,double.class);

    Object booth=booths.stream().filter(b->{try{return field(b,"style").equals("premium_corner")&&(int)field(b,"rotation")==1&&field(b,"owner")==null;}catch(Exception e){return false;}}).findFirst().orElseThrow();
    UUID ownerId=UUID.randomUUID(),visitorId=UUID.randomUUID();Inventory[] opened={null};
    Set<String> chestShopUser=new AbstractSet<>(){@Override public boolean contains(Object o){return String.valueOf(o).toLowerCase(Locale.ROOT).startsWith("chestshop.shop.");}@Override public Iterator<String> iterator(){return Collections.emptyIterator();}@Override public int size(){return 0;}};
    // The renter holds every ChestShop node, including admin, so the no-admin-shop refusal is SkyBoxyMarket's rule, not ChestShop's.
    Set<String> chestShopAdmin=new AbstractSet<>(){@Override public boolean contains(Object o){return String.valueOf(o).toLowerCase(Locale.ROOT).startsWith("chestshop.");}@Override public Iterator<String> iterator(){return Collections.emptyIterator();}@Override public int size(){return 0;}};
    Player owner=fakePlayer(ownerId,chestShopAdmin,opened,fakeInventory(41)),visitor=fakePlayer(visitorId,chestShopUser,opened,realBackedInventory());
    setField(booth,"owner",ownerId);setField(booth,"leaseId","smoke-cs-"+UUID.randomUUID());setField(booth,"expiry",System.currentTimeMillis()+3600000L);
    call(db,"save",new Class<?>[]{booth.getClass()},booth);
    World world=Bukkit.getWorld((String)field(booth,"world"));Object template=((Map<?,?>)field(field(market,"templates"),"templates")).get("premium_corner");
    Method structural=template.getClass().getDeclaredMethod("isStructural",int.class,int.class,int.class,int.class);structural.setAccessible(true);
    int bx=(int)field(booth,"x"),by=(int)field(booth,"y"),bz=(int)field(booth,"z"),rot=(int)field(booth,"rotation");
    org.bukkit.block.Block left=null;
    search:for(int y=1;y<=4;y++)for(int x=bx;x<bx+(int)field(booth,"width")-1;x++)for(int z=bz;z<bz+(int)field(booth,"length")-1;z++){
      boolean free=true;
      for(int[] d:new int[][]{{0,0},{1,0},{0,1}})free&=world.getBlockAt(x+d[0],by+y,z+d[1]).getType()==Material.AIR&&!(boolean)structural.invoke(template,x+d[0]-bx,y,z+d[1]-bz,rot);
      if(free){left=world.getBlockAt(x,by+y,z);break search;}
    }
    if(left==null)throw new IllegalStateException("No free 2x2 interior floor space for the ChestShop double chest");
    org.bukkit.block.Block right=left.getRelative(BlockFace.EAST),signBlock=left.getRelative(BlockFace.SOUTH);
    org.bukkit.block.data.type.Chest leftData=(org.bukkit.block.data.type.Chest)Material.CHEST.createBlockData();leftData.setFacing(BlockFace.NORTH);leftData.setType(org.bukkit.block.data.type.Chest.Type.LEFT);
    org.bukkit.block.data.type.Chest rightData=(org.bukkit.block.data.type.Chest)Material.CHEST.createBlockData();rightData.setFacing(BlockFace.NORTH);rightData.setType(org.bukkit.block.data.type.Chest.Type.RIGHT);
    left.setBlockData(leftData,false);right.setBlockData(rightData,false);
    Inventory stock=((Chest)left.getState()).getInventory();
    if(stock.getSize()!=54)throw new IllegalStateException("ChestShop fixture did not form a double chest");
    stock.setItem(0,new ItemStack(Material.DIAMOND,5));stock.setItem(27,new ItemStack(Material.DIAMOND,5));

    // Creation rules: a visitor and an admin shop are refused inside the booth; the renter's own shop is created.
    if(placeChestShopSign(signBlock,visitor,"",isValid))throw new IllegalStateException("A visitor created a ChestShop shop inside a rented booth");
    if(placeChestShopSign(signBlock,owner,"Admin Shop",isValid))throw new IllegalStateException("An admin shop was created inside a rented booth");
    if(!placeChestShopSign(signBlock,owner,"",isValid))throw new IllegalStateException("The renter could not create a ChestShop shop in their booth");
    if(!(boolean)isShopBlock.invoke(null,left)||!(boolean)call(shops,"shopAt",new Class<?>[]{Location.class},left.getLocation()))throw new IllegalStateException("ChestShop sign did not attach to the double chest");
    Object provider=call(shops,"ownerOf",new Class<?>[]{Location.class},left.getLocation());
    if(provider==null||!"ChestShop".equals(call(provider,"name",new Class<?>[0])))throw new IllegalStateException("Registry did not attribute the double chest to ChestShop");
    if((boolean)call(market,"allowed",new Class<?>[]{Player.class,booth.getClass(),String.class},visitor,booth,"containers"))throw new IllegalStateException("Visitor may open ChestShop storage");

    // Accounts by UUID, the way ChestShop's Vault bridge resolves them.
    org.bukkit.OfflinePlayer visitorAccount=Bukkit.getOfflinePlayer(visitorId),ownerAccount=Bukkit.getOfflinePlayer(ownerId);
    for(org.bukkit.OfflinePlayer account:List.of(visitorAccount,ownerAccount))try{economyType.getMethod("createPlayerAccount",org.bukkit.OfflinePlayer.class).invoke(economy,account);}catch(Exception ignored){}
    deposit.invoke(economy,visitorAccount,1000.0);

    // A suspended booth denies shop-interact; the click must be cancelled before ChestShop (LOW priority) trades.
    setField(booth,"suspended",true);
    double ownerStart=(double)balance.invoke(economy,ownerAccount);
    org.bukkit.event.player.PlayerInteractEvent deniedClick=new org.bukkit.event.player.PlayerInteractEvent(visitor,org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK,null,signBlock,BlockFace.NORTH);
    Bukkit.getPluginManager().callEvent(deniedClick);
    int afterDenied=stock.all(Material.DIAMOND).values().stream().mapToInt(ItemStack::getAmount).sum();
    if(afterDenied!=10||(double)balance.invoke(economy,ownerAccount)!=ownerStart)
      throw new IllegalStateException("Visitor traded with a ChestShop shop in a suspended booth: stock="+afterDenied+" ownerGain="+((double)balance.invoke(economy,ownerAccount)-ownerStart)+" cancelled="+deniedClick.isCancelled());
    setField(booth,"suspended",false);

    // A permitted visitor buys one diamond by right-clicking the sign.
    double salesBefore=(double)field(booth,"sales");playerMessages.clear();
    Bukkit.getPluginManager().callEvent(new org.bukkit.event.player.PlayerInteractEvent(visitor,org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK,null,signBlock,BlockFace.NORTH));
    int afterBuy=stock.all(Material.DIAMOND).values().stream().mapToInt(ItemStack::getAmount).sum();
    double ownerGain=(double)balance.invoke(economy,ownerAccount)-ownerStart,salesGain=(double)field(booth,"sales")-salesBefore;
    getLogger().info("CHESTSHOP SALE OBSERVED stock 10->"+afterBuy+" ownerGain="+ownerGain+" boothSalesGain="+salesGain+" messages="+playerMessages);
    if(afterBuy!=9)throw new IllegalStateException("ChestShop purchase did not take exactly one diamond (stock="+afterBuy+")");
    if(ownerGain<=0||Math.abs(salesGain-ownerGain)>0.001)throw new IllegalStateException("Booth sales grew by "+salesGain+" but the owner received "+ownerGain);

    // Reset removes the shop sign and journals the nine remaining diamonds once.
    Object recovery=field(market,"recovery");invoke(recovery,"reset",new Class<?>[]{booth.getClass(),String.class},booth,"SMOKE_CHESTSHOP");
    if((boolean)isValid.invoke(null,signBlock)||(boolean)call(shops,"shopAt",new Class<?>[]{Location.class},left.getLocation()))throw new IllegalStateException("Booth reset left the ChestShop shop in place");
    int recovered=0;for(Object entry:(List<?>)call(db,"recovered",new Class<?>[]{UUID.class,int.class,int.class},ownerId,0,45)){
      ItemStack item=(ItemStack)call(entry,"item",new Class<?>[0]);
      if(item.getType()==Material.DIAMOND)recovered+=item.getAmount();
    }
    if(recovered!=9)throw new IllegalStateException("Expected nine diamonds journaled once after ChestShop reset, found "+recovered);
    getLogger().info("CHESTSHOP PASS visitor/admin creation refused, renter double-chest shop created; suspended-booth click cancelled before trade; sale counted once ("+salesGain+"); reset removed the sign and journaled 9 stock items once");
  }
  /** Places a sign and runs a real SignChangeEvent; returns whether ChestShop accepted it as a valid shop sign. */
  private boolean placeChestShopSign(org.bukkit.block.Block block,Player creator,String nameLine,Method isValid)throws Exception{
    block.setType(Material.OAK_SIGN,false);
    List<net.kyori.adventure.text.Component> lines=new ArrayList<>(List.of(net.kyori.adventure.text.Component.text(nameLine),net.kyori.adventure.text.Component.text("1"),
            net.kyori.adventure.text.Component.text("B 10"),net.kyori.adventure.text.Component.text("Diamond")));
    org.bukkit.event.block.SignChangeEvent change=new org.bukkit.event.block.SignChangeEvent(block,creator,lines,org.bukkit.block.sign.Side.FRONT);
    ClassLoader cl=Bukkit.getPluginManager().getPlugin("ChestShop").getClass().getClassLoader();Object[] outcome={null};
    @SuppressWarnings("unchecked") Class<? extends org.bukkit.event.Event> pre=(Class<? extends org.bukkit.event.Event>)Class.forName("com.Acrobot.ChestShop.Events.PreShopCreationEvent",true,cl);
    org.bukkit.event.Listener capture=new org.bukkit.event.Listener(){};
    Bukkit.getPluginManager().registerEvent(pre,capture,org.bukkit.event.EventPriority.MONITOR,(l,ev)->{if(pre.isInstance(ev))try{outcome[0]=ev.getClass().getMethod("getOutcome").invoke(ev);}catch(Exception ex){outcome[0]=ex;}},this,false);
    playerMessages.clear();
    Bukkit.getPluginManager().callEvent(change);HandlerList.unregisterAll(capture);
    getLogger().info("CHESTSHOP SIGN creator="+creator.getName()+" name='"+nameLine+"' outcome="+outcome[0]+" cancelled="+change.isCancelled()+" messages="+playerMessages);
    if(change.isCancelled()||block.getType()!=Material.OAK_SIGN){block.setType(Material.AIR,false);return false;}
    org.bukkit.block.Sign sign=(org.bukkit.block.Sign)block.getState();
    for(int i=0;i<4;i++)sign.getSide(org.bukkit.block.sign.Side.FRONT).line(i,change.line(i));
    sign.update(true,false);
    boolean valid=(boolean)isValid.invoke(null,block);
    if(!valid)getLogger().info("CHESTSHOP SIGN rejected after write: eventLines="+java.util.Arrays.toString(change.getLines())+" stored="+java.util.Arrays.toString(((org.bukkit.block.Sign)block.getState()).getLines()));
    if(!valid)block.setType(Material.AIR,false);
    return valid;
  }
  @SuppressWarnings({"rawtypes","unchecked"})
  private void testQuickShop(Object market,List<?> booths)throws Exception{
    ClassLoader ql=Bukkit.getPluginManager().getPlugin("QuickShop-Hikari").getClass().getClassLoader();
    Class<?> apiType=Class.forName("com.ghostchu.quickshop.api.QuickShopAPI",true,ql),managerType=Class.forName("com.ghostchu.quickshop.api.shop.ShopManager",true,ql);
    Class<?> shopType=Class.forName("com.ghostchu.quickshop.api.shop.Shop",true,ql),qUserType=Class.forName("com.ghostchu.quickshop.api.obj.QUser",true,ql);
    Class<?> wrapperType=Class.forName("com.ghostchu.quickshop.api.inventory.InventoryWrapper",true,ql);
    Class<?> bukkitWrapper=Class.forName("com.ghostchu.quickshop.shop.inventory.BukkitInventoryWrapper",true,ql);
    Class<?> qsType=Class.forName("com.ghostchu.quickshop.QuickShop",true,ql),simpleManager=Class.forName("com.ghostchu.quickshop.shop.SimpleShopManager",true,ql);
    Class<?> userImpl=Class.forName("com.ghostchu.quickshop.obj.QUserImpl",true,ql);
    Object api=apiType.getMethod("getInstance").invoke(null),manager=apiType.getMethod("getShopManager").invoke(api);
    Object qs=api; // QuickShop implements QuickShopAPI; avoid Class.getMethod on it, which resolves its optional PlaceholderAPI signatures
    Object db=field(market,"db"),shops=field(market,"shops");
    Class<?> economyType=market.getClass().getClassLoader().loadClass("net.milkbowl.vault.economy.Economy");
    Object economy=Bukkit.getServicesManager().getRegistration((Class)economyType).getProvider();
    Method balance=economyType.getMethod("getBalance",org.bukkit.OfflinePlayer.class),deposit=economyType.getMethod("depositPlayer",org.bukkit.OfflinePlayer.class,double.class);

    // A rented premium booth with a renter-placed chest holding ten diamonds.
    Object booth=booths.stream().filter(b->{try{return field(b,"style").equals("premium_corner")&&(int)field(b,"rotation")==0&&field(b,"owner")==null;}catch(Exception e){return false;}}).findFirst().orElseThrow();
    UUID ownerId=UUID.randomUUID(),buyerId=UUID.randomUUID();Inventory[] opened={null};
    // QuickShop checks its own quickshop.* nodes for ordinary use; the visitor has no SkyBoxyMarket permissions.
    Set<String> quickShopUser=new AbstractSet<>(){@Override public boolean contains(Object o){return String.valueOf(o).startsWith("quickshop.");}@Override public Iterator<String> iterator(){return Collections.emptyIterator();}@Override public int size(){return 0;}};
    Player owner=fakePlayer(ownerId,Set.of(),opened),visitor=fakePlayer(buyerId,quickShopUser,opened);
    setField(booth,"owner",ownerId);setField(booth,"leaseId","smoke-qs-"+UUID.randomUUID());setField(booth,"expiry",System.currentTimeMillis()+3600000L);
    call(db,"save",new Class<?>[]{booth.getClass()},booth);
    World world=Bukkit.getWorld((String)field(booth,"world"));Object template=((Map<?,?>)field(field(market,"templates"),"templates")).get("premium_corner");
    int[] cell=null;for(Object c:(List<?>)field(template,"cells"))if(field(c,"material")==Material.AIR&&(int)field(c,"y")==1){cell=new int[]{(int)field(c,"x"),1,(int)field(c,"z")};break;}
    if(cell==null)throw new IllegalStateException("No floor-level air cell for the QuickShop chest");
    org.bukkit.block.Block chestBlock=world.getBlockAt((int)field(booth,"x")+cell[0],(int)field(booth,"y")+1,(int)field(booth,"z")+cell[2]);
    chestBlock.setType(Material.CHEST,false);Inventory stock=((Chest)chestBlock.getState()).getInventory();stock.addItem(new ItemStack(Material.DIAMOND,10));

    // Create the shop exactly as QuickShop's own actionCreate builds it.
    Object qOwner=userImpl.getMethod("createFullFilled",UUID.class,String.class,boolean.class).invoke(null,ownerId,"qsowner",true);
    Class<?> wrapperManagerType=Class.forName("com.ghostchu.quickshop.api.inventory.InventoryWrapperManager",true,ql);
    Object wrapperManager;try{wrapperManager=java.lang.invoke.MethodHandles.publicLookup().findVirtual(qsType,"getInventoryWrapperManager",java.lang.invoke.MethodType.methodType(wrapperManagerType)).invoke(qs);}catch(Throwable t){throw new Exception(t);}
    Object link=wrapperManagerType.getMethod("mklink",wrapperType)
            .invoke(wrapperManager,bukkitWrapper.getConstructor(Inventory.class).newInstance(stock));
    Field selling=simpleManager.getDeclaredField("SELLING_TYPE"),active=simpleManager.getDeclaredField("ACTIVE_STATE");selling.setAccessible(true);active.setAccessible(true);
    Object shop=Class.forName("com.ghostchu.quickshop.shop.ContainerShop",true,ql).getConstructors()[0].newInstance(qs,-1L,chestBlock.getLocation(),10.0,new ItemStack(Material.DIAMOND),qOwner,false,
            selling.get(null),active.get(null),new java.util.concurrent.ConcurrentHashMap<>(),null,true,null,
            Bukkit.getPluginManager().getPlugin("QuickShop-Hikari").getName(),link,null,Collections.emptyMap(),
            Class.forName("com.ghostchu.quickshop.economy.QSBenefitProvider",true,ql).getConstructor().newInstance(),
            Class.forName("com.ghostchu.quickshop.shop.cache.SimpleShopInventoryCountCache",true,ql).getConstructor().newInstance());
    // createShop() insists on an online owner; register and load the shop the way it does after its player checks.
    ((java.util.concurrent.CompletableFuture<?>)managerType.getMethod("registerShop",shopType,boolean.class).invoke(manager,shop,true)).get(10,java.util.concurrent.TimeUnit.SECONDS);
    managerType.getMethod("loadShop",shopType).invoke(manager,shop);
    Object provider=call(shops,"ownerOf",new Class<?>[]{Location.class},chestBlock.getLocation());
    if(provider==null||!"QuickShop-Hikari".equals(call(provider,"name",new Class<?>[0])))throw new IllegalStateException("Registry did not attribute the QuickShop chest to QuickShop-Hikari");

    // Visitors may use the shop but never open its storage.
    Object protection=HandlerList.getRegisteredListeners((Plugin)market).stream().map(reg->reg.getListener()).filter(l->l.getClass().getName().endsWith("MarketProtection")).findFirst().orElseThrow();
    org.bukkit.event.player.PlayerInteractEvent click=new org.bukkit.event.player.PlayerInteractEvent(visitor,org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK,null,chestBlock,BlockFace.UP);
    invoke(protection,"interact",new Class<?>[]{org.bukkit.event.player.PlayerInteractEvent.class},click);
    if(click.isCancelled())throw new IllegalStateException("Visitor could not interact with a QuickShop shop chest");
    if((boolean)call(market,"allowed",new Class<?>[]{Player.class,booth.getClass(),String.class},visitor,booth,"containers"))throw new IllegalStateException("Visitor may open QuickShop storage");

    // A real purchase through QuickShop's trade service; compare every event amount with the owner's actual Vault change.
    // Each fake player has a unique name, so Essentials keeps one account per player for QuickShop to resolve.
    org.bukkit.OfflinePlayer buyerAccount=Bukkit.getOfflinePlayer(buyerId),ownerAccount=Bukkit.getOfflinePlayer(ownerId);
    for(org.bukkit.OfflinePlayer account:List.of(buyerAccount,ownerAccount))
      try{economyType.getMethod("createPlayerAccount",org.bukkit.OfflinePlayer.class).invoke(economy,account);}catch(Exception ignored){}
    Object depositResponse=deposit.invoke(economy,buyerAccount,1000.0);
    getLogger().info("QUICKSHOP ACCOUNT deposit="+depositResponse.getClass().getMethod("transactionSuccess").invoke(depositResponse)+" error="+depositResponse.getClass().getField("errorMessage").get(depositResponse)
            +" buyerBalance="+balance.invoke(economy,buyerAccount)+" worldBalance="+economyType.getMethod("getBalance",org.bukkit.OfflinePlayer.class,String.class).invoke(economy,buyerAccount,"world"));
    double ownerBefore=(double)balance.invoke(economy,ownerAccount),salesBefore=(double)field(booth,"sales");
    List<double[]> events=new ArrayList<>();org.bukkit.event.Listener capture=new org.bukkit.event.Listener(){};
    Class<? extends org.bukkit.event.Event> successType=(Class)Class.forName("com.ghostchu.quickshop.api.event.economy.ShopSuccessPurchaseEvent",true,ql);
    Bukkit.getPluginManager().registerEvent(successType,capture,org.bukkit.event.EventPriority.MONITOR,(l,ev)->{if(!successType.isInstance(ev))return;try{
      events.add(new double[]{(double)ev.getClass().getMethod("getBalance").invoke(ev),(double)ev.getClass().getMethod("getBalanceWithoutTax").invoke(ev),(double)ev.getClass().getMethod("getTax").invoke(ev)});
    }catch(Exception ex){throw new RuntimeException(ex);}},this,true);
    Object buyer=userImpl.getMethod("createFullFilled",UUID.class,String.class,boolean.class).invoke(null,buyerId,"qsbuyer",true);
    // The path a real player takes: ShopUtil maps a click on a selling shop to PURCHASE_BUY -> ShopManager.actionSelling.
    // (The newer TradeService API does not fire ShopSuccessPurchaseEvent in 6.3.0.3, so it is not what players use.)
    Class<?> actionType=Class.forName("com.ghostchu.quickshop.api.shop.ShopAction",true,ql),infoType=Class.forName("com.ghostchu.quickshop.api.shop.Info",true,ql);
    Class<?> economyProviderType=Class.forName("com.ghostchu.quickshop.api.economy.EconomyProvider",true,ql);
    Object info=Class.forName("com.ghostchu.quickshop.shop.SimpleInfo",true,ql).getConstructor(Location.class,actionType,ItemStack.class,org.bukkit.block.Block.class,shopType,boolean.class)
            .newInstance(chestBlock.getLocation(),actionType.getField("PURCHASE_BUY").get(null),new ItemStack(Material.DIAMOND),null,shop,false);
    Object qsEconomy=Class.forName("com.ghostchu.quickshop.api.economy.EconomyManager",true,ql).getMethod("provider").invoke(apiType.getMethod("getEconomyManager").invoke(api));
    Inventory buyerStock=Bukkit.createInventory(null,36);
    boolean bought=(boolean)managerType.getMethod("actionSelling",Player.class,wrapperType,economyProviderType,infoType,shopType,int.class)
            .invoke(manager,visitor,bukkitWrapper.getConstructor(Inventory.class).newInstance(buyerStock),qsEconomy,info,shop,2);
    HandlerList.unregisterAll(capture);
    Object result="actionSelling="+bought+" buyerDiamonds="+buyerStock.all(Material.DIAMOND).values().stream().mapToInt(ItemStack::getAmount).sum();
    if(!bought)throw new IllegalStateException("QuickShop purchase failed: "+result+" messages="+playerMessages);
    double ownerGain=(double)balance.invoke(economy,ownerAccount)-ownerBefore,salesGain=(double)field(booth,"sales")-salesBefore;
    if(events.size()!=1)throw new IllegalStateException("Expected one QuickShop success event, saw "+events.size());
    double[] ev=events.getFirst();
    getLogger().info("QUICKSHOP SALE OBSERVED ownerGain="+ownerGain+" getBalance="+ev[0]+" getBalanceWithoutTax="+ev[1]+" getTax="+ev[2]+" boothSalesGain="+salesGain+" result="+result);
    if(Math.abs(salesGain-ownerGain)>0.001)throw new IllegalStateException("Booth sales grew by "+salesGain+" but the owner received "+ownerGain);
    if(stock.all(Material.DIAMOND).values().stream().mapToInt(ItemStack::getAmount).sum()!=8)throw new IllegalStateException("QuickShop purchase did not take two diamonds from the shop chest");

    // Reset: the shop is deleted and the eight remaining diamonds are journaled exactly once.
    Object recovery=field(market,"recovery");invoke(recovery,"reset",new Class<?>[]{booth.getClass(),String.class},booth,"SMOKE_QUICKSHOP");
    if((boolean)call(shops,"shopAt",new Class<?>[]{Location.class},chestBlock.getLocation())||managerType.getMethod("getShopIncludeAttached",Location.class).invoke(manager,chestBlock.getLocation())!=null)
      throw new IllegalStateException("Booth reset left the QuickShop shop registered");
    int recovered=0;for(Object entry:(List<?>)call(db,"recovered",new Class<?>[]{UUID.class,int.class,int.class},ownerId,0,45)){
      ItemStack item=(ItemStack)call(entry,"item",new Class<?>[0]);
      if(item.getType()!=Material.DIAMOND)throw new IllegalStateException("Reset journaled a non-stock item from the QuickShop booth: "+item);
      recovered+=item.getAmount();
    }
    if(recovered!=8)throw new IllegalStateException("Expected eight diamonds journaled once after QuickShop reset, found "+recovered);
    getLogger().info("QUICKSHOP PASS shop created in booth; visitor purchase allowed, storage closed; sale counted once ("+salesGain+"); reset deleted the shop and journaled 8 stock items once");
  }
  private void testResetAndRecovery(Object market,List<?> booths,Map<?,?> templates,File restartMarker) throws Exception {
    Object booth=booths.stream().filter(b -> {try{return field(b,"style").equals("normal")&&field(b,"rotation").equals(0);}catch(Exception e){return false;}}).findFirst().orElseThrow();
    World world=Bukkit.getWorld((String)field(booth,"world")); int ox=(int)field(booth,"x"),oy=(int)field(booth,"y"),oz=(int)field(booth,"z");
    int width=(int)field(booth,"width"),height=(int)field(booth,"height"),length=(int)field(booth,"length");
    for(Entity entity:world.getEntities())if(entity instanceof Item&&entity.getLocation().getBlockX()>=ox&&entity.getLocation().getBlockX()<ox+width
            &&entity.getLocation().getBlockY()>=oy&&entity.getLocation().getBlockY()<oy+height
            &&entity.getLocation().getBlockZ()>=oz&&entity.getLocation().getBlockZ()<oz+length)entity.remove();
    Object template=templates.get("normal"); List<?> cells=(List<?>)field(template,"cells");
    List<int[]> air=new ArrayList<>();
    for(Object c:cells) if(field(c,"material")==Material.AIR) air.add(new int[]{(int)field(c,"x"),(int)field(c,"y"),(int)field(c,"z")});
    int[][] pair=null;
    Set<String> airSet=new HashSet<>(); for(int[] q:air)airSet.add(q[0]+":"+q[1]+":"+q[2]);
    for(int[] q:air){String key=(q[0]+1)+":"+q[1]+":"+q[2];if(airSet.contains(key)){pair=new int[][]{q,{q[0]+1,q[1],q[2]}};break;}}
    if(pair==null) throw new IllegalStateException("No adjacent east-west air cells for double-chest reset test");
    org.bukkit.block.data.type.Chest leftData=(org.bukkit.block.data.type.Chest)Material.CHEST.createBlockData();leftData.setFacing(BlockFace.NORTH);leftData.setType(org.bukkit.block.data.type.Chest.Type.LEFT);
    org.bukkit.block.data.type.Chest rightData=(org.bukkit.block.data.type.Chest)Material.CHEST.createBlockData();rightData.setFacing(BlockFace.NORTH);rightData.setType(org.bukkit.block.data.type.Chest.Type.RIGHT);
    world.getBlockAt(ox+pair[0][0],oy+pair[0][1],oz+pair[0][2]).setBlockData(leftData,false);
    world.getBlockAt(ox+pair[1][0],oy+pair[1][1],oz+pair[1][2]).setBlockData(rightData,false);
    ItemStack a=tagged(Material.DIAMOND_SWORD,"metadata-chest-left",2), b=tagged(Material.ENCHANTED_GOLDEN_APPLE,"metadata-chest-right",1);
    Chest left=(Chest)world.getBlockAt(ox+pair[0][0],oy+pair[0][1],oz+pair[0][2]).getState();
    Chest right=(Chest)world.getBlockAt(ox+pair[1][0],oy+pair[1][1],oz+pair[1][2]).getState();
    if(left.getInventory().getSize()!=54||!(left.getInventory().getHolder() instanceof DoubleChest))throw new IllegalStateException("Acceptance setup did not produce a real double chest");
    left.getInventory().setItem(0,a);left.getInventory().setItem(27,b);
    int[] placeCell=null; for(int[] q:air)if(!same(q,pair[0])&&!same(q,pair[1])){placeCell=q;break;} if(placeCell==null)throw new IllegalStateException("No second air cell for placed-block test");
    Block placed=world.getBlockAt(ox+placeCell[0],oy+placeCell[1],oz+placeCell[2]); placed.setType(Material.EMERALD_BLOCK,false);
    ItemStack source=tagged(Material.GOLD_BLOCK,"metadata-placed-block",1);
    Object db=field(market,"db");Method recordPlaced=db.getClass().getDeclaredMethod("place",booth.getClass(),int.class,int.class,int.class,Material.class,UUID.class,ItemStack.class);recordPlaced.setAccessible(true);
    UUID owner=UUID.randomUUID();recordPlaced.invoke(db,booth,placed.getX(),placed.getY(),placed.getZ(),Material.EMERALD_BLOCK,owner,source);
    int[] shulkerCell=null;for(int[] q:air)if(!same(q,pair[0])&&!same(q,pair[1])&&!same(q,placeCell)){shulkerCell=q;break;}
    if(shulkerCell==null)throw new IllegalStateException("No fourth air cell for nested-container recovery test");
    Block placedShulker=world.getBlockAt(ox+shulkerCell[0],oy+shulkerCell[1],oz+shulkerCell[2]);placedShulker.setType(Material.SHULKER_BOX,false);
    ItemStack nested=tagged(Material.EMERALD,"metadata-shulker-content",4);ShulkerBox liveShulker=(ShulkerBox)placedShulker.getState();liveShulker.getInventory().setItem(4,nested);
    if(((ShulkerBox)placedShulker.getState()).getInventory().getItem(4)==null)throw new IllegalStateException("Could not populate placed shulker inventory for recovery test");
    ItemStack shell=tagged(Material.SHULKER_BOX,"metadata-shulker-shell",1);BlockStateMeta shellMeta=(BlockStateMeta)shell.getItemMeta();
    ShulkerBox shellState=(ShulkerBox)shellMeta.getBlockState();shellState.getInventory().setItem(4,nested.clone());shellMeta.setBlockState(shellState);shell.setItemMeta(shellMeta);
    recordPlaced.invoke(db,booth,placedShulker.getX(),placedShulker.getY(),placedShulker.getZ(),Material.SHULKER_BOX,owner,shell);
    ItemStack drop=tagged(Material.PAPER,"metadata-dropped-item",7);world.dropItem(new org.bukkit.Location(world,ox+3,oy+2,oz+3),drop);
    setField(booth,"owner",owner);setField(booth,"leaseId","smoke-"+UUID.randomUUID());setField(booth,"expiry",System.currentTimeMillis()+3600000L);
    Method save=db.getClass().getDeclaredMethod("save",booth.getClass());save.setAccessible(true);save.invoke(db,booth);
    ((JavaPlugin)market).getConfig().set("market.reset-blocks-per-tick",17);
    Object recovery=field(market,"recovery");Method reset=recovery.getClass().getDeclaredMethod("reset",booth.getClass(),String.class);reset.setAccessible(true);reset.invoke(recovery,booth,"SMOKE_ACCEPTANCE");
    new BukkitRunnable(){int waited,lastCursor,batches;
      @Override public void run(){
        try{
          List<?> pending=(List<?>)call(db,"resets",new Class<?>[0]);
          String boothId=(String)field(booth,"id");
          Object progress=pending.stream().filter(row->{try{return boothId.equals(call(row,"booth",new Class<?>[0]));}catch(Exception ex){return false;}}).findFirst().orElse(null);
          if(progress!=null){
            int current=(int)call(progress,"restoreCursor",new Class<?>[0]);
            String phase=(String)call(progress,"phase",new Class<?>[0]);
            if("RESTORING".equals(phase)){
              int advanced=current-lastCursor;
              if(advanced<0||advanced>17)throw new IllegalStateException("Reset cursor advanced "+advanced+" blocks in one observed tick (budget 17)");
              if(advanced>0)batches++;
              lastCursor=current;
            }
          }
          if((int)call(db,"resetCount",new Class<?>[0])!=0||field(booth,"owner")!=null||Boolean.TRUE.equals(field(booth,"resetting"))){
            if(++waited>1200)throw new IllegalStateException("Batched reset did not finish within 1200 ticks");
            return;
          }
          if(batches<2)throw new IllegalStateException("Reset did not demonstrate multiple persisted restore batches");
          cancel();verifyResetRecovery(market,booth,template,world,owner,ox,oy,oz,db,restartMarker);
        }catch(Throwable ex){cancel();fail("BATCHED RESET ACCEPTANCE FAIL",ex);}
      }
    }.runTaskTimer(this,1L,1L);
  }
  private void verifyResetRecovery(Object market,Object booth,Object template,World world,UUID owner,int ox,int oy,int oz,Object db,File restartMarker)throws Exception{
    Method recovered=db.getClass().getDeclaredMethod("recovered",UUID.class,int.class,int.class);recovered.setAccessible(true);List<?> entries=(List<?>)recovered.invoke(db,owner,0,45);
    for(Object entry:entries){Method sourceAccessor=entry.getClass().getDeclaredMethod("source");sourceAccessor.setAccessible(true);Method itemAccessor=entry.getClass().getDeclaredMethod("item");itemAccessor.setAccessible(true);ItemStack item=(ItemStack)itemAccessor.invoke(entry);String label=item.getItemMeta().getPersistentDataContainer().get(new NamespacedKey(this,"case"),PersistentDataType.STRING);getLogger().info("RECOVERY ENTRY source="+sourceAccessor.invoke(entry)+" label="+label+" amount="+item.getAmount());}
    if(entries.size()!=6)throw new IllegalStateException("Expected six reset items from double chest + placed block + nested shulker + drop; found "+entries.size());
    Set<String> labels=new HashSet<>();Map<String,Integer> labelCounts=new HashMap<>();Map<String,ItemStack> itemsByLabel=new HashMap<>();
    for(Object entry:entries){Method itemAccessor=entry.getClass().getDeclaredMethod("item");itemAccessor.setAccessible(true);ItemStack item=(ItemStack)itemAccessor.invoke(entry);String label=item.getItemMeta().getPersistentDataContainer().get(new NamespacedKey(this,"case"),PersistentDataType.STRING);if(label!=null){labels.add(label);labelCounts.merge(label,1,Integer::sum);itemsByLabel.put(label,item);}}
    Set<String> expectedLabels=Set.of("metadata-chest-left","metadata-chest-right","metadata-placed-block","metadata-dropped-item","metadata-shulker-shell","metadata-shulker-content");
    if(!labels.containsAll(expectedLabels))throw new IllegalStateException("Recovery lost metadata: "+labels);
    if(labelCounts.getOrDefault("metadata-shulker-content",0)!=1)throw new IllegalStateException("Filled shulker content was recovered more than once: "+labelCounts);
    ItemStack recoveredShell=itemsByLabel.get("metadata-shulker-shell");
    if(!(recoveredShell.getItemMeta() instanceof BlockStateMeta recoveredMeta)||!(recoveredMeta.getBlockState() instanceof ShulkerBox recoveredBox)||!recoveredBox.getInventory().isEmpty())
      throw new IllegalStateException("Recovered shulker shell retained separately journaled contents");
    Method matches=template.getClass().getDeclaredMethod("matches",World.class,int.class,int.class,int.class,int.class,boolean.class);matches.setAccessible(true);
    if(!(boolean)matches.invoke(template,world,ox,oy,oz,(int)field(booth,"rotation"),true))throw new IllegalStateException("Reset did not restore the original Normal template");
    getLogger().info("RESET RECOVERY PASS entries="+entries.size()+" labels="+String.join(",",new TreeSet<>(labels))+"; shulker contents captured once");
    getLogger().info("ACCEPTANCE PASS: both styles/four rotations restored after mutations; menus/protection, real Essentials economy, Vault callback de-duplication, durable reset cursor, double-chest, nested shulker, placed-block, dropped-item, PDC and death-receipt recovery verified");
    stageInterruptedReset((SkyBoxyMarket)market,restartMarker,"SNAPSHOTTING");
    Bukkit.getScheduler().runTaskLater(this,Bukkit::shutdown,20L);
  }
  private ItemStack tagged(Material type,String label,int amount){ItemStack item=new ItemStack(type,amount);ItemMeta meta=item.getItemMeta();meta.setDisplayName("\u00a7d"+label);meta.setLore(List.of("\u00a77custom item metadata", "\u00a77survives recovery"));meta.getPersistentDataContainer().set(new NamespacedKey(this,"case"),PersistentDataType.STRING,label);if(type==Material.DIAMOND_SWORD)meta.addEnchant(Enchantment.SHARPNESS,4,true);item.setItemMeta(meta);return item;}
  private Inventory locationInventory(Location location){
    return (Inventory)Proxy.newProxyInstance(Inventory.class.getClassLoader(),new Class<?>[]{Inventory.class},(proxy,method,args)->{
      if(method.getName().equals("getLocation"))return location;
      if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
      if(method.getName().equals("equals"))return proxy==args[0];
      if(method.getName().equals("toString"))return "MarketProtectionTestInventory";
      Class<?> result=method.getReturnType();
      if(result==boolean.class)return false;if(result==int.class)return 0;if(result==long.class)return 0L;
      if(result==double.class)return 0D;if(result==float.class)return 0F;if(result==short.class)return (short)0;if(result==byte.class)return (byte)0;
      if(result==List.class)return List.of();if(result==ItemStack[].class)return new ItemStack[0];return null;
    });
  }
  private static BlockPlaceEvent placeEvent(Block block,Player player){return new BlockPlaceEvent(block,block.getState(),block.getRelative(BlockFace.DOWN),new ItemStack(Material.GLOWSTONE),player,true,EquipmentSlot.HAND);}
  private void testDeathRecoveryReceipt(Object market) throws Exception {
    Object recovery=field(market,"recovery"),db=field(market,"db");NamespacedKey receiptKey=(NamespacedKey)field(recovery,"receiptKey");UUID owner=UUID.randomUUID();String token=UUID.randomUUID().toString();
    Method store=db.getClass().getDeclaredMethod("storeRecovery",UUID.class,String.class,String.class,String.class,int.class,byte[].class);store.setAccessible(true);
    Method begin=db.getClass().getDeclaredMethod("beginClaim",long.class,UUID.class);begin.setAccessible(true);
    Method complete=db.getClass().getDeclaredMethod("completeClaim",long.class,UUID.class);complete.setAccessible(true);
    Method count=db.getClass().getDeclaredMethod("recoveryCount",UUID.class);count.setAccessible(true);
    Method death=recovery.getClass().getDeclaredMethod("deathReceipt",PlayerDeathEvent.class);death.setAccessible(true);
    PlayerInventory inventory=fakeInventory(41);Inventory[] opened={null};Player player=fakePlayer(owner,Set.of(),opened,inventory);
    ItemStack pending=tagged(Material.DIAMOND,"pending-death",3);ItemMeta pendingMeta=pending.getItemMeta();pendingMeta.getPersistentDataContainer().set(receiptKey,PersistentDataType.LONG,0L);pending.setItemMeta(pendingMeta);
    long pendingId=(long)store.invoke(db,owner,"smoke","death-pending-"+token,"entity:pending:"+token,0,pending.serializeAsBytes());
    if(!(boolean)begin.invoke(db,pendingId,owner))throw new IllegalStateException("Could not prepare in-flight death receipt");
    pendingMeta=pending.getItemMeta();pendingMeta.getPersistentDataContainer().set(receiptKey,PersistentDataType.LONG,pendingId);pending.setItemMeta(pendingMeta);inventory.setItem(0,pending.clone());
    List<ItemStack> pendingDrops=new ArrayList<>(List.of(pending.clone()));death.invoke(recovery,new PlayerDeathEvent(player,null,pendingDrops,0,"pending receipt death"));
    if(!pendingDrops.isEmpty()||inventory.getItem(0)!=null||(int)count.invoke(db,owner)!=1)throw new IllegalStateException("Uncommitted death receipt was not reopened without leaving a duplicate copy");

    ItemStack delivered=tagged(Material.DIAMOND,"delivered-death",2);long deliveredId=(long)store.invoke(db,owner,"smoke","death-delivered-"+token,"entity:delivered:"+token,0,delivered.serializeAsBytes());
    if(!(boolean)begin.invoke(db,deliveredId,owner)||!(boolean)complete.invoke(db,deliveredId,owner))throw new IllegalStateException("Could not prepare completed death receipt");
    ItemMeta deliveredMeta=delivered.getItemMeta();deliveredMeta.getPersistentDataContainer().set(receiptKey,PersistentDataType.LONG,deliveredId);delivered.setItemMeta(deliveredMeta);inventory.setItem(1,delivered.clone());
    List<ItemStack> deliveredDrops=new ArrayList<>(List.of(delivered.clone()));death.invoke(recovery,new PlayerDeathEvent(player,null,deliveredDrops,0,"delivered receipt death"));
    ItemStack dropped=deliveredDrops.get(0);Long dropReceipt=dropped.getItemMeta().getPersistentDataContainer().get(receiptKey,PersistentDataType.LONG);
    Long inventoryReceipt=inventory.getItem(1).getItemMeta().getPersistentDataContainer().get(receiptKey,PersistentDataType.LONG);
    if(dropReceipt!=null||inventoryReceipt!=null||(int)count.invoke(db,owner)!=1)throw new IllegalStateException("Completed death receipt lost its item or retained a stale token");
    getLogger().info("DEATH RECEIPT PASS uncommitted claims reopen without copies; completed claims retain normal death behavior");
  }
  private void testPartialRecoveryClaims(Object market)throws Exception {
    Object recovery=field(market,"recovery"),db=field(market,"db");UUID owner=UUID.randomUUID();
    PlayerInventory inventory=fakeInventory(41);Player player=fakePlayer(owner,Set.of(),new Inventory[1],inventory);
    ItemStack stock=tagged(Material.DIAMOND,"partial-claim-"+UUID.randomUUID(),64);
    for(int slot=0;slot<36;slot++)inventory.setItem(slot,new ItemStack(Material.STONE,64));
    ItemStack existing=stock.clone();existing.setAmount(60);inventory.setItem(0,existing);
    long entry=(long)call(db,"storeRecovery",new Class<?>[]{UUID.class,String.class,String.class,String.class,int.class,byte[].class},owner,"smoke",UUID.randomUUID().toString(),"partial",0,stock.serializeAsBytes());
    if(!(boolean)call(recovery,"claim",new Class<?>[]{Player.class,long.class,ItemStack.class},player,entry,stock))
      throw new IllegalStateException("Recovery refused the four items that fit in an existing stack");
    if(inventory.getItem(0).getAmount()!=64||!inventory.getItem(0).isSimilar(stock))throw new IllegalStateException("Partial claim changed existing metadata or lost its four items");
    List<?> remaining=(List<?>)call(db,"recovered",new Class<?>[]{UUID.class,int.class,int.class},owner,0,45);
    if(remaining.size()!=1)throw new IllegalStateException("Partial claim did not retain exactly one remainder");
    ItemStack remainder=(ItemStack)call(remaining.getFirst(),"item",new Class<?>[0]);long remainderId=(long)call(remaining.getFirst(),"id",new Class<?>[0]);
    if(remainder.getAmount()!=60||!remainder.isSimilar(stock))throw new IllegalStateException("Partial remainder lost quantity or metadata");
    if((boolean)call(recovery,"claim",new Class<?>[]{Player.class,long.class,ItemStack.class},player,entry,stock))throw new IllegalStateException("Stale claim delivered twice");
    if((boolean)call(recovery,"claim",new Class<?>[]{Player.class,long.class,ItemStack.class},player,remainderId,remainder))throw new IllegalStateException("Full inventory consumed the remainder");
    inventory.setItem(1,null);
    if(!(boolean)call(recovery,"claim",new Class<?>[]{Player.class,long.class,ItemStack.class},player,remainderId,remainder))throw new IllegalStateException("The saved remainder could not be reclaimed");
    if(inventory.getItem(1)==null||inventory.getItem(1).getAmount()!=60||!inventory.getItem(1).isSimilar(stock)||(int)call(db,"recoveryCount",new Class<?>[]{UUID.class},owner)!=0)
      throw new IllegalStateException("Remainder claim did not conserve items");
    getLogger().info("PARTIAL CLAIM PASS merged four items into an existing stack; preserved sixty with metadata; stale/full-inventory requests caused no loss or duplication");
  }
  private void testMergedReceiptDeath(Object market)throws Exception {
    Object recovery=field(market,"recovery"),db=field(market,"db");UUID owner=UUID.randomUUID();
    NamespacedKey receipt=(NamespacedKey)field(recovery,"receiptKey"),quantity=new NamespacedKey((Plugin)market,"recovery_receipt_amount");
    PlayerInventory inventory=fakeInventory(41);Player player=fakePlayer(owner,Set.of(),new Inventory[1],inventory);
    ItemStack recovered=tagged(Material.DIAMOND,"merged-death-"+UUID.randomUUID(),7);
    long entry=(long)call(db,"storeRecovery",new Class<?>[]{UUID.class,String.class,String.class,String.class,int.class,byte[].class},owner,"smoke",UUID.randomUUID().toString(),"merged-death",0,recovered.serializeAsBytes());
    call(db,"beginClaim",new Class<?>[]{long.class,UUID.class},entry,owner);
    List<ItemStack> drops=new ArrayList<>();
    for(int slot=0;slot<2;slot++){
      ItemStack merged=recovered.clone();merged.setAmount(64);ItemMeta meta=merged.getItemMeta();
      meta.getPersistentDataContainer().set(receipt,PersistentDataType.LONG,entry);meta.getPersistentDataContainer().set(quantity,PersistentDataType.INTEGER,slot==0?4:3);merged.setItemMeta(meta);
      inventory.setItem(slot,merged.clone());drops.add(merged);
    }
    call(recovery,"deathReceipt",new Class<?>[]{PlayerDeathEvent.class},new PlayerDeathEvent(player,null,drops,0,"merged pending claim"));
    if(drops.size()!=2||drops.get(0).getAmount()!=60||drops.get(1).getAmount()!=61||inventory.getItem(0).getAmount()!=60||inventory.getItem(1).getAmount()!=61)
      throw new IllegalStateException("Pending multi-slot receipt lost pre-existing player items or left claimed copies on death");
    if((int)call(db,"recoveryCount",new Class<?>[]{UUID.class},owner)!=1||!drops.getFirst().isSimilar(recovered))throw new IllegalStateException("Pending death did not preserve recovery and original metadata");
    ItemStack kept=recovered.clone();kept.setAmount(5);
    long keptId=(long)call(db,"storeRecovery",new Class<?>[]{UUID.class,String.class,String.class,String.class,int.class,byte[].class},owner,"smoke",UUID.randomUUID().toString(),"keep-inventory",0,kept.serializeAsBytes());
    call(db,"beginClaim",new Class<?>[]{long.class,UUID.class},keptId,owner);kept.setAmount(64);ItemMeta meta=kept.getItemMeta();
    meta.getPersistentDataContainer().set(receipt,PersistentDataType.LONG,keptId);meta.getPersistentDataContainer().set(quantity,PersistentDataType.INTEGER,5);kept.setItemMeta(meta);inventory.setItem(2,kept);
    PlayerDeathEvent keepEvent=new PlayerDeathEvent(player,null,new ArrayList<>(),0,"kept pending claim");keepEvent.setKeepInventory(true);
    call(recovery,"deathReceipt",new Class<?>[]{PlayerDeathEvent.class},keepEvent);
    if(inventory.getItem(2).getAmount()!=59||(int)call(db,"recoveryCount",new Class<?>[]{UUID.class},owner)!=2)
      throw new IllegalStateException("Keep-inventory death did not reopen the pending portion while preserving prior stock");
    ItemStack soulbound=recovered.clone();soulbound.setAmount(2);
    long soulboundId=(long)call(db,"storeRecovery",new Class<?>[]{UUID.class,String.class,String.class,String.class,int.class,byte[].class},owner,"smoke",UUID.randomUUID().toString(),"items-to-keep",0,soulbound.serializeAsBytes());
    call(db,"beginClaim",new Class<?>[]{long.class,UUID.class},soulboundId,owner);soulbound.setAmount(12);meta=soulbound.getItemMeta();
    meta.getPersistentDataContainer().set(receipt,PersistentDataType.LONG,soulboundId);meta.getPersistentDataContainer().set(quantity,PersistentDataType.INTEGER,2);soulbound.setItemMeta(meta);
    PlayerDeathEvent selectedKeep=new PlayerDeathEvent(player,null,new ArrayList<>(),0,"selected keep");selectedKeep.getItemsToKeep().add(soulbound);
    call(recovery,"deathReceipt",new Class<?>[]{PlayerDeathEvent.class},selectedKeep);
    if(selectedKeep.getItemsToKeep().getFirst().getAmount()!=10||(int)call(db,"recoveryCount",new Class<?>[]{UUID.class},owner)!=3)
      throw new IllegalStateException("Selected kept receipt did not preserve baseline stock and reopen its pending portion");
    getLogger().info("MERGED RECEIPT DEATH PASS multi-slot, keep-inventory and selected-kept deaths retain prior stock and reopen only the delivered portion");
  }
  private void testPartialClaimFaults(Object market)throws Exception {
    Object recovery=field(market,"recovery"),db=field(market,"db");UUID owner=UUID.randomUUID();
    PlayerInventory inventory=fakeInventory(41);List<ItemStack[]> saved=new ArrayList<>();boolean[] refuseSave={false};
    Player player=fakePlayer(owner,Set.of(),new Inventory[1],inventory,()->{
      if(refuseSave[0])throw new IllegalStateException("Injected player-save failure");
      saved.add(Arrays.stream(inventory.getStorageContents()).map(item->item==null?null:item.clone()).toArray(ItemStack[]::new));
    });
    ItemStack stock=tagged(Material.DIAMOND,"partial-fault-"+UUID.randomUUID(),64),existing=stock.clone();existing.setAmount(60);
    for(int slot=0;slot<36;slot++)inventory.setItem(slot,new ItemStack(Material.STONE,64));inventory.setItem(0,existing);
    long entry=(long)call(db,"storeRecovery",new Class<?>[]{UUID.class,String.class,String.class,String.class,int.class,byte[].class},owner,"smoke",UUID.randomUUID().toString(),"partial-fault",0,stock.serializeAsBytes());
    java.sql.Connection connection=(java.sql.Connection)field(db,"conn");String trigger="claim_fault_"+UUID.randomUUID().toString().replace("-","");
    try(java.sql.Statement statement=connection.createStatement()){
      statement.execute("CREATE TRIGGER "+trigger+" BEFORE UPDATE OF claim_state ON recovery WHEN NEW.id="+entry+" AND NEW.claim_state=2 BEGIN SELECT RAISE(ABORT,'injected claim finalization failure'); END");
    }
    Object fresh;
    try{
      if((boolean)call(recovery,"claim",new Class<?>[]{Player.class,long.class,ItemStack.class},player,entry,stock))throw new IllegalStateException("Injected finalization failure unexpectedly completed");
      if(saved.size()!=1||inventory.getItem(0).getAmount()!=64||((List<?>)call(db,"inFlightClaims",new Class<?>[]{UUID.class},owner)).size()!=1)
        throw new IllegalStateException("Finalization failure rolled back a saved receipt or failed to retain its intent");
      Constructor<?> constructor=recovery.getClass().getDeclaredConstructor(market.getClass());constructor.setAccessible(true);fresh=constructor.newInstance(market);
      call(fresh,"joined",new Class<?>[]{PlayerJoinEvent.class},new PlayerJoinEvent(player,"claim restart"));
      PlayerSwapHandItemsEvent swap=new PlayerSwapHandItemsEvent(player,inventory.getItem(0),new ItemStack(Material.AIR));
      call(fresh,"swapPendingReceipt",new Class<?>[]{PlayerSwapHandItemsEvent.class},swap);
      if(!swap.isCancelled())throw new IllegalStateException("A failed ledger reconciliation unlocked the saved receipt after restart");
    }finally{try(java.sql.Statement statement=connection.createStatement()){statement.execute("DROP TRIGGER IF EXISTS "+trigger);}}
    call(fresh,"joined",new Class<?>[]{PlayerJoinEvent.class},new PlayerJoinEvent(player,"claim retry"));
    if(!inventory.getItem(0).isSimilar(stock)||(int)call(db,"recoveryCount",new Class<?>[]{UUID.class},owner)!=1||!((List<?>)call(db,"inFlightClaims",new Class<?>[]{UUID.class},owner)).isEmpty())
      throw new IllegalStateException("Saved partial claim did not finalize once while retaining its remainder");
    ItemStack pending=stock.clone();pending.setAmount(4);
    long pendingId=(long)call(db,"storeRecovery",new Class<?>[]{UUID.class,String.class,String.class,String.class,int.class,byte[].class},owner,"smoke",UUID.randomUUID().toString(),"death-save-fault",0,pending.serializeAsBytes());
    call(db,"beginClaim",new Class<?>[]{long.class,UUID.class},pendingId,owner);
    pending.setAmount(64);ItemMeta meta=pending.getItemMeta();meta.getPersistentDataContainer().set((NamespacedKey)field(recovery,"receiptKey"),PersistentDataType.LONG,pendingId);
    meta.getPersistentDataContainer().set(new NamespacedKey((Plugin)market,"recovery_receipt_amount"),PersistentDataType.INTEGER,4);pending.setItemMeta(meta);inventory.setItem(0,pending);
    refuseSave[0]=true;List<ItemStack> drops=new ArrayList<>(List.of(pending.clone()));
    call(fresh,"deathReceipt",new Class<?>[]{PlayerDeathEvent.class},new PlayerDeathEvent(player,null,drops,0,"death save failure"));refuseSave[0]=false;
    if(!((List<?>)call(db,"inFlightClaims",new Class<?>[]{UUID.class},owner)).contains(pendingId)||inventory.getItem(0).getAmount()!=60||drops.getFirst().getAmount()!=60)
      throw new IllegalStateException("Failed death save reopened the ledger before inventory cleanup was durable");
    call(fresh,"joined",new Class<?>[]{PlayerJoinEvent.class},new PlayerJoinEvent(player,"death save retry"));
    if((int)call(db,"recoveryCount",new Class<?>[]{UUID.class},owner)!=2)throw new IllegalStateException("Missing-token restart did not reopen the pending portion");
    getLogger().info("PARTIAL CLAIM FAULT PASS saved receipt stays locked after ledger failure; reconciliation completes once; failed death save retains pending ledger until reconnect");
  }
  private void testRecoveryClaimMenus(Object market)throws Exception {
    Object db=field(market,"db"),menus=field(market,"menus");UUID owner=UUID.randomUUID();Inventory[] opened=new Inventory[1];
    PlayerInventory inventory=fakeInventory(41);Player player=fakePlayer(owner,Set.of("skyboxymarket.use"),opened,inventory);
    ItemStack stock=tagged(Material.EMERALD,"recovery-menu-"+UUID.randomUUID(),64),existing=stock.clone();existing.setAmount(60);
    for(int slot=0;slot<36;slot++)inventory.setItem(slot,new ItemStack(Material.STONE,64));inventory.setItem(0,existing);
    long entry=(long)call(db,"storeRecovery",new Class<?>[]{UUID.class,String.class,String.class,String.class,int.class,byte[].class},owner,UUID.randomUUID().toString(),UUID.randomUUID().toString(),"menu-claim",0,stock.serializeAsBytes());
    call(menus,"recover",new Class<?>[]{Player.class,int.class},player,0);assertMenuActions(opened[0],(Plugin)market,Set.of("claim:"+entry+":0","claimpage:0","claimall","player"));
    call(menus,"route",new Class<?>[]{Player.class,String.class},player,"claimpage:0");
    List<?> remainder=(List<?>)call(db,"recovered",new Class<?>[]{UUID.class,int.class,int.class},owner,0,45);
    if(remainder.size()!=1||inventory.getItem(0).getAmount()!=64||!inventory.getItem(0).isSimilar(stock))throw new IllegalStateException("Page claim lost metadata or bypassed partial capacity");
    long remainderId=(long)call(remainder.getFirst(),"id",new Class<?>[0]);assertMenuActions(opened[0],(Plugin)market,Set.of("claim:"+remainderId+":0"));
    inventory.setItem(1,null);call(menus,"route",new Class<?>[]{Player.class,String.class},player,"claim:"+remainderId+":0");
    if(inventory.getItem(1)==null||inventory.getItem(1).getAmount()!=60||!inventory.getItem(1).isSimilar(stock)||(int)call(db,"recoveryCount",new Class<?>[]{UUID.class},owner)!=0)
      throw new IllegalStateException("Native recovery item action did not deliver the remainder without GUI lore/tags");
    getLogger().info("RECOVERY GUI PASS native page action partially merged items; item action claimed the remainder; display lore/action tags never entered player stock");
  }
  private long recoveryEntry(Object db,UUID owner,String source,ItemStack item)throws Exception {
    return (long)call(db,"storeRecovery",new Class<?>[]{UUID.class,String.class,String.class,String.class,int.class,byte[].class},
            owner,UUID.randomUUID().toString(),UUID.randomUUID().toString(),source,0,item.serializeAsBytes());
  }
  private void testWholeRecoveryClaims(Object market,Runnable finished)throws Exception {
    Object db=field(market,"db"),menus=field(market,"menus"),recovery=field(market,"recovery");UUID owner=UUID.randomUUID();
    Inventory[] opened=new Inventory[1];PlayerInventory inventory=fakeInventory(41);int[] saves={0};
    Player player=fakePlayer(owner,Set.of("skyboxymarket.use"),opened,inventory,()->saves[0]++);
    ItemStack stock=tagged(Material.EMERALD,"claim-all-"+UUID.randomUUID(),1);long first=0;
    for(int n=0;n<94;n++){long id=recoveryEntry(db,owner,"whole-"+n,stock);if(n==0)first=id;}
    call(menus,"recover",new Class<?>[]{Player.class,int.class},player,2);
    call(menus,"route",new Class<?>[]{Player.class,String.class},player,"claimall");
    if((boolean)call(recovery,"claimAll",new Class<?>[]{Player.class,Runnable.class},player,(Runnable)()->{throw new IllegalStateException("Duplicate callback ran");}))
      throw new IllegalStateException("Duplicate all-claim request started another pass");
    if((boolean)call(recovery,"claim",new Class<?>[]{Player.class,long.class,ItemStack.class},player,first,stock)||saves[0]!=0)
      throw new IllegalStateException("All-claim did synchronous delivery or allowed concurrent item action");
    recoveryEntry(db,owner,"new-after-pass-start",stock);
    awaitAllClaims(market,owner,saves,()->{
      try{
        int quantity=Arrays.stream(inventory.getStorageContents()).filter(Objects::nonNull).mapToInt(ItemStack::getAmount).sum();
        if(quantity!=94||(int)call(db,"recoveryCount",new Class<?>[]{UUID.class},owner)!=1||!((List<?>)call(db,"inFlightClaims",new Class<?>[]{UUID.class},owner)).isEmpty())
          throw new IllegalStateException("All-claim skipped entries, duplicated items or crossed its starting cutoff");
        assertMenuActions(opened[0],(Plugin)market,Set.of("claimall","claimpage:0"));
        getLogger().info("CLAIM ALL PAGES PASS 94 entries/multiple leases across three pages, fixed cutoff, duplicate/concurrent request guard and bounded saves");
        testWholeRecoveryPartial(market,finished);
      }catch(Throwable ex){fail("CLAIM ALL ACCEPTANCE FAIL",ex);}
    });
  }
  private void testWholeRecoveryPartial(Object market,Runnable finished)throws Exception {
    Object db=field(market,"db"),menus=field(market,"menus");UUID owner=UUID.randomUUID();Inventory[] opened=new Inventory[1];int[] saves={0};
    PlayerInventory inventory=fakeInventory(41);Player player=fakePlayer(owner,Set.of("skyboxymarket.use"),opened,inventory,()->saves[0]++);
    for(int slot=0;slot<36;slot++)inventory.setItem(slot,new ItemStack(Material.DIRT,64));
    ItemStack emerald=tagged(Material.EMERALD,"partial-all-"+UUID.randomUUID(),4),diamond=tagged(Material.DIAMOND,"partial-all-diamond-"+UUID.randomUUID(),64);
    ItemStack base=emerald.clone();base.setAmount(60);inventory.setItem(0,base);base=diamond.clone();base.setAmount(63);inventory.setItem(1,base);
    for(int n=0;n<50;n++)recoveryEntry(db,owner,"no-fit-"+n,new ItemStack(Material.STONE,64));
    recoveryEntry(db,owner,"fits-after-page",emerald);recoveryEntry(db,owner,"partly-fits-after-page",diamond);
    call(menus,"recover",new Class<?>[]{Player.class,int.class},player,0);call(menus,"route",new Class<?>[]{Player.class,String.class},player,"claimall");
    awaitAllClaims(market,owner,saves,()->{
      try{
        if(inventory.getItem(0).getAmount()!=64||!inventory.getItem(0).isSimilar(emerald)||inventory.getItem(1).getAmount()!=64||!inventory.getItem(1).isSimilar(diamond)
                ||(int)call(db,"recoveryCount",new Class<?>[]{UUID.class},owner)!=51)
          throw new IllegalStateException("All-claim stopped at a no-fit page or lost a partial remainder");
        List<?> last=(List<?>)call(db,"recovered",new Class<?>[]{UUID.class,int.class,int.class},owner,1,45);
        ItemStack remainder=(ItemStack)call(last.getLast(),"item",new Class<?>[0]);
        if(remainder.getAmount()!=63||!remainder.isSimilar(diamond))throw new IllegalStateException("All-claim remainder metadata was altered");
        getLogger().info("CLAIM ALL CAPACITY PASS skipped fifty no-fit entries to merge later stock; partial remainder kept with metadata");
        testWholeRecoveryDisconnect(market,finished);
      }catch(Throwable ex){fail("CLAIM ALL ACCEPTANCE FAIL",ex);}
    });
  }
  private void testWholeRecoveryDisconnect(Object market,Runnable finished)throws Exception {
    Object db=field(market,"db"),menus=field(market,"menus"),recovery=field(market,"recovery");UUID owner=UUID.randomUUID();
    Inventory[] opened=new Inventory[1];PlayerInventory inventory=fakeInventory(41);int[] saves={0};boolean[] online={true};
    Player player=fakePlayer(owner,Set.of("skyboxymarket.use"),opened,inventory,()->saves[0]++,()->online[0]);
    for(int n=0;n<6;n++)recoveryEntry(db,owner,"disconnect-"+n,new ItemStack(Material.EMERALD,1));
    call(menus,"recover",new Class<?>[]{Player.class,int.class},player,0);call(menus,"route",new Class<?>[]{Player.class,String.class},player,"claimall");online[0]=false;
    awaitAllClaims(market,owner,saves,()->{
      try{
        call(menus,"route",new Class<?>[]{Player.class,String.class},player,"claimpage:0");
        if(saves[0]!=0||(int)call(db,"recoveryCount",new Class<?>[]{UUID.class},owner)!=6)throw new IllegalStateException("Queued page claim awarded items after disconnect");
        List<?> entries=(List<?>)call(db,"recovered",new Class<?>[]{UUID.class,int.class,int.class},owner,0,45);long first=(long)call(entries.getFirst(),"id",new Class<?>[0]);
        if((boolean)call(recovery,"claim",new Class<?>[]{Player.class,long.class,ItemStack.class},player,first,new ItemStack(Material.EMERALD,1))
                ||(boolean)call(recovery,"claimAll",new Class<?>[]{Player.class,Runnable.class},player,(Runnable)()->{}))
          throw new IllegalStateException("Offline player started direct recovery delivery");
        online[0]=true;call(menus,"route",new Class<?>[]{Player.class,String.class},player,"claimall");
        call(menus,"route",new Class<?>[]{Player.class,String.class},player,"player");Inventory otherScreen=opened[0];
        awaitAllClaims(market,owner,saves,()->{
          try{
            if((int)call(db,"recoveryCount",new Class<?>[]{UUID.class},owner)!=0||inventory.getItem(0).getAmount()!=6||opened[0]!=otherScreen)
              throw new IllegalStateException("Resumed claim was lost or completion replaced a different menu");
            getLogger().info("CLAIM ALL DISCONNECT PASS canceled queued/page/direct offline delivery; resumed once; completion preserved later menu navigation");testWholeRecoveryFailures(market,finished);
          }catch(Throwable ex){fail("CLAIM ALL ACCEPTANCE FAIL",ex);}
        });
      }catch(Throwable ex){fail("CLAIM ALL ACCEPTANCE FAIL",ex);}
    });
  }
  private void awaitAllClaims(Object market,UUID owner,int[] saves,Runnable verified){
    new BukkitRunnable(){int ticks,previousSaves;
      @Override public void run(){
        try{
          if(saves[0]-previousSaves>2)throw new IllegalStateException("All-claim performed more than one receipt delivery per tick");previousSaves=saves[0];
          if(!(boolean)call(field(market,"recovery"),"isClaimingAll",new Class<?>[]{UUID.class},owner)){cancel();verified.run();}
          else if(++ticks>250)throw new IllegalStateException("All-claim did not finish its bounded pass");
        }catch(Throwable ex){cancel();fail("CLAIM ALL ACCEPTANCE FAIL",ex);}
      }
    }.runTaskTimer(this,1L,1L);
  }
  private void testWholeRecoveryFailures(Object market,Runnable finished)throws Exception {
    Object db=field(market,"db"),menus=field(market,"menus"),recovery=field(market,"recovery");UUID owner=UUID.randomUUID();Inventory[] opened=new Inventory[1];
    PlayerInventory inventory=fakeInventory(41);int[] saves={0};Player player=fakePlayer(owner,Set.of("skyboxymarket.use"),opened,inventory,()->saves[0]++);
    long first=0;for(int n=0;n<3;n++){long entry=recoveryEntry(db,owner,"all-fault-"+n,new ItemStack(Material.EMERALD,1));if(n==0)first=entry;}
    File databaseFile=new File(((JavaPlugin)market).getDataFolder(),"market.db");String trigger="reject_all_finalization";
    try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:"+databaseFile);var statement=connection.createStatement()){
      statement.execute("CREATE TRIGGER "+trigger+" BEFORE UPDATE OF claim_state ON recovery WHEN NEW.id="+first+" AND NEW.claim_state=2 BEGIN SELECT RAISE(ABORT,'injected all-claim failure'); END");
    }
    call(menus,"recover",new Class<?>[]{Player.class,int.class},player,0);call(menus,"route",new Class<?>[]{Player.class,String.class},player,"claimall");
    awaitAllClaims(market,owner,saves,()->{
      try{
        try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:"+databaseFile);var statement=connection.createStatement()){statement.execute("DROP TRIGGER "+trigger);}
        if(((List<?>)call(db,"inFlightClaims",new Class<?>[]{UUID.class},owner)).size()!=1||(int)call(db,"recoveryCount",new Class<?>[]{UUID.class},owner)!=2
                ||inventory.getItem(0).getAmount()!=1||saves[0]!=1)throw new IllegalStateException("All-claim continued after a saved receipt failed to finalize");
        PlayerSwapHandItemsEvent swap=new PlayerSwapHandItemsEvent(player,inventory.getItem(0),null);call(recovery,"swapPendingReceipt",new Class<?>[]{PlayerSwapHandItemsEvent.class},swap);
        if(!swap.isCancelled())throw new IllegalStateException("Stopped all-claim unlocked a pending receipt");
        call(recovery,"joined",new Class<?>[]{PlayerJoinEvent.class},new PlayerJoinEvent(player,"all-claim retry"));saves[0]=0;
        call(menus,"route",new Class<?>[]{Player.class,String.class},player,"claimall");
        awaitAllClaims(market,owner,saves,()->{
          try{
            if(inventory.getItem(0).getAmount()!=3||(int)call(db,"recoveryCount",new Class<?>[]{UUID.class},owner)!=0)throw new IllegalStateException("All-claim ledger retry duplicated or omitted items");
            getLogger().info("CLAIM ALL FAULT PASS stopped on saved receipt failure, kept it locked, reconciled once and resumed remaining entries");testWholeRecoveryCancellation(market,finished);
          }catch(Throwable ex){fail("CLAIM ALL ACCEPTANCE FAIL",ex);}
        });
      }catch(Throwable ex){fail("CLAIM ALL ACCEPTANCE FAIL",ex);}
    });
  }
  private void testWholeRecoveryCancellation(Object market,Runnable finished)throws Exception {
    Object db=field(market,"db"),menus=field(market,"menus"),recovery=field(market,"recovery");UUID owner=UUID.randomUUID();Inventory[] opened=new Inventory[1];int[] saves={0};
    Set<String> permissions=new HashSet<>(Set.of("skyboxymarket.use"));PlayerInventory inventory=fakeInventory(41);
    Player player=fakePlayer(owner,permissions,opened,inventory,()->saves[0]++);for(int n=0;n<3;n++)recoveryEntry(db,owner,"all-cancel-"+n,new ItemStack(Material.EMERALD,1));
    call(menus,"recover",new Class<?>[]{Player.class,int.class},player,0);call(menus,"route",new Class<?>[]{Player.class,String.class},player,"claimall");permissions.clear();
    awaitAllClaims(market,owner,saves,()->{
      try{
        call(menus,"route",new Class<?>[]{Player.class,String.class},player,"claimpage:0");
        if(saves[0]!=0||(int)call(db,"recoveryCount",new Class<?>[]{UUID.class},owner)!=3)throw new IllegalStateException("Revoked permission allowed a queued or stale recovery action");
        permissions.add("skyboxymarket.use");call(menus,"route",new Class<?>[]{Player.class,String.class},player,"claimall");call(recovery,"cancelClaims",new Class<?>[0]);
        if((boolean)call(recovery,"isClaimingAll",new Class<?>[]{UUID.class},owner)||saves[0]!=0)throw new IllegalStateException("Shutdown cleanup left an active all-claim");
        call(menus,"route",new Class<?>[]{Player.class,String.class},player,"claimall");
        awaitAllClaims(market,owner,saves,()->{
          try{
            if((int)call(db,"recoveryCount",new Class<?>[]{UUID.class},owner)!=0||inventory.getItem(0).getAmount()!=3)throw new IllegalStateException("Canceled all-claim could not resume safely");
            getLogger().info("CLAIM ALL CANCELLATION PASS permission revocation/stale page guard, shutdown cleanup and safe restart of pass");finished.run();
          }catch(Throwable ex){fail("CLAIM ALL ACCEPTANCE FAIL",ex);}
        });
      }catch(Throwable ex){fail("CLAIM ALL ACCEPTANCE FAIL",ex);}
    });
  }
  @SuppressWarnings({"rawtypes","unchecked"})
  private void testEssentialsEconomy(Object market,List<?> booths)throws Exception {
    Class<?> economyType=market.getClass().getClassLoader().loadClass("net.milkbowl.vault.economy.Economy");
    RegisteredServiceProvider<?> service=Bukkit.getServicesManager().getRegistration((Class)economyType);
    if(service==null)throw new IllegalStateException("No registered Vault provider was available for the real-provider acceptance test");
    Object provider=service.getProvider();Plugin owner=service.getPlugin();Object priority=service.getPriority();
    if(!provider.getClass().getName().toLowerCase(Locale.ROOT).contains("essentials"))
      throw new IllegalStateException("Expected the supplied Essentials economy implementation; got "+provider.getClass().getName());
    Object oldPrice=((JavaPlugin)market).getConfig().get("styles.normal.rental.price");
    Object oldRenewal=((JavaPlugin)market).getConfig().get("styles.normal.rental.renewal-price");
    Object oldWall=((JavaPlugin)market).getConfig().get("styles.normal.walls.cost");
    Object booth=null;Player renter=null;boolean registered=true;
    Method balance=economyType.getMethod("getBalance",org.bukkit.OfflinePlayer.class);
    Method deposit=economyType.getMethod("depositPlayer",org.bukkit.OfflinePlayer.class,double.class);
    try{
      if(field(market,"economy")!=provider)throw new IllegalStateException("SkyBoxyMarket did not select the real Essentials provider at startup");
      Bukkit.getServicesManager().unregister((Class)economyType,provider);registered=false;
      if(field(market,"economy")!=null)throw new IllegalStateException("Vault unregister event left a stale economy provider active");
      Bukkit.getServicesManager().register((Class)economyType,provider,owner,(org.bukkit.plugin.ServicePriority)priority);registered=true;
      if(field(market,"economy")!=provider)throw new IllegalStateException("Vault register event did not restore the Essentials provider");

      JavaPlugin plugin=(JavaPlugin)market;plugin.getConfig().set("styles.normal.rental.price",100.0);
      plugin.getConfig().set("styles.normal.rental.renewal-price",50.0);plugin.getConfig().set("styles.normal.walls.cost",25.0);
      call(market,"reloadStyles",new Class<?>[0]);
      booth=booths.stream().filter(b->{try{return field(b,"style").equals("normal")&&(int)field(b,"rotation")==2&&field(b,"owner")==null&&!Boolean.TRUE.equals(field(b,"resetting"));}catch(Exception e){return false;}}).findFirst().orElseThrow();
      UUID id=UUID.randomUUID();Inventory[] opened={null};renter=fakePlayer(id,Set.of(),opened);
      double initial=(double)balance.invoke(provider,renter);
      Object depositResponse=deposit.invoke(provider,renter,1000.0);
      if(!((Boolean)depositResponse.getClass().getMethod("transactionSuccess").invoke(depositResponse)))
        throw new IllegalStateException("Essentials refused the test-account deposit");
      Object rentals=field(market,"rentals");
      invoke(rentals,"rent",new Class<?>[]{Player.class,booth.getClass()},renter,booth);
      double afterRent=(double)balance.invoke(provider,renter);
      if(!id.equals(field(booth,"owner"))||Math.abs(afterRent-(initial+900.0))>0.001)
        throw new IllegalStateException("Essentials did not debit the real Vault account once for rent: "+afterRent+" starting from "+initial);
      invoke(rentals,"renew",new Class<?>[]{Player.class,booth.getClass()},renter,booth);
      invoke(rentals,"renew",new Class<?>[]{Player.class,booth.getClass()},renter,booth);
      double afterRenewal=(double)balance.invoke(provider,renter);
      if(Math.abs(afterRenewal-(initial+850.0))>0.001)throw new IllegalStateException("Essentials renewal charge was missing or duplicated: "+afterRenewal);
      invoke(rentals,"walls",new Class<?>[]{Player.class,booth.getClass(),String.class},renter,booth,"BLUE");
      double afterWalls=(double)balance.invoke(provider,renter);
      if(Math.abs(afterWalls-(initial+825.0))>0.001||!"BLUE".equals(field(booth,"wallColor")))
        throw new IllegalStateException("Essentials wall charge or style update did not commit: "+afterWalls);
      Object recovery=field(market,"recovery");invoke(recovery,"reset",new Class<?>[]{booth.getClass(),String.class},booth,"SMOKE_ESSENTIALS");
      getLogger().info("ESSENTIALS ECONOMY PASS provider="+provider.getClass().getName()+"; startup discovery, service register/unregister, rent, renewal and wall charge");
    }finally{
      if(booth!=null&&renter!=null&&field(booth,"owner")!=null){
        Object recovery=field(market,"recovery");invoke(recovery,"reset",new Class<?>[]{booth.getClass(),String.class},booth,"SMOKE_ESSENTIALS_CLEANUP");
      }
      JavaPlugin plugin=(JavaPlugin)market;plugin.getConfig().set("styles.normal.rental.price",oldPrice);
      plugin.getConfig().set("styles.normal.rental.renewal-price",oldRenewal);plugin.getConfig().set("styles.normal.walls.cost",oldWall);
      call(market,"reloadStyles",new Class<?>[0]);
      if(!registered)Bukkit.getServicesManager().register((Class)economyType,provider,owner,(org.bukkit.plugin.ServicePriority)priority);
    }
  }
  @SuppressWarnings({"rawtypes","unchecked"})
  private void testVaultEconomy(Object market,List<?> booths)throws Exception {
    JavaPlugin plugin=(JavaPlugin)market;String pricePath="styles.normal.rental.price",renewalPath="styles.normal.rental.renewal-price",wallPath="styles.normal.walls.cost";
    Object oldPrice=plugin.getConfig().get(pricePath),oldRenewal=plugin.getConfig().get(renewalPath),oldWall=plugin.getConfig().get(wallPath);
    Class<?> economyType=market.getClass().getClassLoader().loadClass("net.milkbowl.vault.economy.Economy");
    ClassLoader loader=economyType.getClassLoader();Class<?> responseType=loader.loadClass("net.milkbowl.vault.economy.EconomyResponse");
    Class<?> responseKind=loader.loadClass("net.milkbowl.vault.economy.EconomyResponse$ResponseType");
    Object success=Enum.valueOf((Class<Enum>)responseKind,"SUCCESS"),failure=Enum.valueOf((Class<Enum>)responseKind,"FAILURE");double[] balance={1000.0};int[] withdrawals={0},deposits={0};boolean[] rejectRefund={false};
    var responseConstructor=responseType.getConstructor(double.class,double.class,responseKind,String.class);
    Object provider=Proxy.newProxyInstance(loader,new Class<?>[]{economyType},(proxy,method,args)->{
      String name=method.getName();
      if(name.equals("isEnabled"))return true;if(name.equals("getName"))return "SkyBoxy Smoke Economy";
      if(name.equals("has"))return balance[0]>=(double)args[args.length-1];
      if(name.equals("getBalance"))return balance[0];
      if(name.equals("withdrawPlayer")){double amount=(double)args[args.length-1];balance[0]-=amount;withdrawals[0]++;return responseConstructor.newInstance(amount,balance[0],success,null);}
      if(name.equals("depositPlayer")){double amount=(double)args[args.length-1];if(rejectRefund[0])return responseConstructor.newInstance(0.0,balance[0],failure,"Injected refund rejection");balance[0]+=amount;deposits[0]++;return responseConstructor.newInstance(amount,balance[0],success,null);}
      if(name.equals("hashCode"))return System.identityHashCode(proxy);if(name.equals("equals"))return proxy==args[0];if(name.equals("toString"))return "SkyBoxySmokeEconomy";
      Class<?> result=method.getReturnType();if(result==boolean.class)return false;if(result==int.class)return 0;if(result==long.class)return 0L;
      if(result==double.class)return 0D;if(result==float.class)return 0F;if(result==short.class)return (short)0;if(result==byte.class)return (byte)0;
      if(result==char.class)return (char)0;if(result==List.class)return List.of();return null;
    });
    Bukkit.getServicesManager().register((Class)economyType,provider,this,org.bukkit.plugin.ServicePriority.Highest);
    try{
      call(market,"setupEconomy",new Class<?>[0]);if(field(market,"economy")!=provider)throw new IllegalStateException("Vault provider was not discovered by the plugin");
      plugin.getConfig().set(pricePath,100.0);plugin.getConfig().set(renewalPath,50.0);plugin.getConfig().set(wallPath,25.0);call(market,"reloadStyles",new Class<?>[0]);
      Object booth=booths.stream().filter(b->{try{return field(b,"style").equals("normal")&&(int)field(b,"rotation")==3&&field(b,"owner")==null&&!Boolean.TRUE.equals(field(b,"resetting"));}catch(Exception e){return false;}}).findFirst().orElseThrow();
      UUID owner=UUID.randomUUID();Inventory[] opened={null};Player renter=fakePlayer(owner,Set.of(),opened);Object rentals=field(market,"rentals");
      invoke(rentals,"rent",new Class<?>[]{Player.class,booth.getClass()},renter,booth);
      if(!owner.equals(field(booth,"owner"))||withdrawals[0]!=1||Math.abs(balance[0]-900.0)>0.001)throw new IllegalStateException("Vault rent did not debit exactly once and commit the lease");
      invoke(rentals,"rent",new Class<?>[]{Player.class,booth.getClass()},renter,booth);
      if(withdrawals[0]!=1)throw new IllegalStateException("Repeated rent action charged an additional payment");
      invoke(rentals,"renew",new Class<?>[]{Player.class,booth.getClass()},renter,booth);
      invoke(rentals,"renew",new Class<?>[]{Player.class,booth.getClass()},renter,booth);
      if(withdrawals[0]!=2||Math.abs(balance[0]-850.0)>0.001)throw new IllegalStateException("Vault renewal payment did not de-duplicate a repeated click");
      invoke(rentals,"walls",new Class<?>[]{Player.class,booth.getClass(),String.class},renter,booth,"BLUE");
      if(withdrawals[0]!=3||Math.abs(balance[0]-825.0)>0.001||!"BLUE".equals(field(booth,"wallColor")))throw new IllegalStateException("Vault wall charge did not commit its style change");
      Object recovery=field(market,"recovery");invoke(recovery,"reset",new Class<?>[]{booth.getClass(),String.class},booth,"SMOKE_VAULT");
      if(field(booth,"owner")==null||!Boolean.TRUE.equals(field(booth,"resetting")))throw new IllegalStateException("Vault reset did not retain the lease while the resumable restore is pending");
      Object repairBooth=booths.stream().filter(b->{try{return field(b,"style").equals("normal")&&(int)field(b,"rotation")==0&&field(b,"owner")==null&&!Boolean.TRUE.equals(field(b,"resetting"));}catch(Exception e){return false;}}).findFirst().orElseThrow();
      String repairBoothId=(String)field(repairBooth,"id");Object db=field(market,"db");java.sql.Connection connection=(java.sql.Connection)field(db,"conn");
      String trigger="fail_paid_market_update";
      try(java.sql.Statement statement=connection.createStatement()){
        statement.execute("CREATE TRIGGER "+trigger+" BEFORE UPDATE ON booths WHEN NEW.id='"+repairBoothId+"' BEGIN SELECT RAISE(ABORT,'injected paid rental failure'); END");
      }
      try{
        UUID repairOwner=UUID.randomUUID();Player repairRenter=fakePlayer(repairOwner,Set.of(),opened);double beforeCompensation=balance[0];
        invoke(rentals,"rent",new Class<?>[]{Player.class,repairBooth.getClass()},repairRenter,repairBooth);
        Object compensated=call(db,"latestPaymentIntent",new Class<?>[]{UUID.class,String.class,String.class},repairOwner,repairBoothId,"RENT");
        if(!"REFUNDED".equals(call(compensated,"status",new Class<?>[0]))||Math.abs(balance[0]-beforeCompensation)>0.001||deposits[0]!=1)
          throw new IllegalStateException("Successful provider compensation did not durably refund a failed rental commit");

        rejectRefund[0]=true;beforeCompensation=balance[0];
        invoke(rentals,"rent",new Class<?>[]{Player.class,repairBooth.getClass()},repairRenter,repairBooth);
        Object pending=call(db,"latestPaymentIntent",new Class<?>[]{UUID.class,String.class,String.class},repairOwner,repairBoothId,"RENT");
        String pendingId=(String)call(pending,"id",new Class<?>[0]);
        if(!"REVIEW_REQUIRED".equals(call(pending,"status",new Class<?>[0]))||Math.abs(balance[0]-(beforeCompensation-100.0))>0.001)
          throw new IllegalStateException("A refused refund was not retained for manual payment review");
        Object menus=field(market,"menus");Player admin=fakePlayer(UUID.randomUUID(),Set.of("skyboxymarket.admin"),opened);
        invoke(menus,"route",new Class<?>[]{Player.class,String.class},admin,"paymentreviews:0");assertMenuActions(opened[0],(Plugin)market,Set.of("paymentdetail:"+pendingId));
        invoke(menus,"route",new Class<?>[]{Player.class,String.class},admin,"paymentdetail:"+pendingId);
        invoke(menus,"route",new Class<?>[]{Player.class,String.class},admin,"paymentresolveconfirm:"+pendingId+":REFUND_VERIFIED");
        assertMenuActions(opened[0],(Plugin)market,Set.of("paymentresolve:"+pendingId+":REFUND_VERIFIED"));
        rejectRefund[0]=false;
        Object manualResponse=economyType.getMethod("depositPlayer",org.bukkit.OfflinePlayer.class,double.class).invoke(provider,repairRenter,100.0);
        if(!((Boolean)manualResponse.getClass().getMethod("transactionSuccess").invoke(manualResponse)))throw new IllegalStateException("Test provider could not record the staff-verified refund");
        invoke(menus,"route",new Class<?>[]{Player.class,String.class},admin,"paymentresolve:"+pendingId+":REFUND_VERIFIED");
        Object resolved=call(db,"latestPaymentIntent",new Class<?>[]{UUID.class,String.class,String.class},repairOwner,repairBoothId,"RENT");
        if(!"REFUND_VERIFIED".equals(call(resolved,"status",new Class<?>[0]))||((Number)call(db,"unresolvedPaymentIntentCount",new Class<?>[0])).intValue()!=0)
          throw new IllegalStateException("Admin did not durably close the manually verified refund review");
        getLogger().info("VAULT COMPENSATION PASS SQLite failure/refund success, refused refund retained, provider refund manually verified and audited");
      }finally{try(java.sql.Statement statement=connection.createStatement()){statement.execute("DROP TRIGGER IF EXISTS "+trigger);}}
      getLogger().info("VAULT ECONOMY PASS provider discovery, one rent debit, repeat-rent guard, renewal and wall charges");
    }finally{
      plugin.getConfig().set(pricePath,oldPrice);plugin.getConfig().set(renewalPath,oldRenewal);plugin.getConfig().set(wallPath,oldWall);call(market,"reloadStyles",new Class<?>[0]);
      Bukkit.getServicesManager().unregister((Class)economyType,provider);
      call(market,"setupEconomy",new Class<?>[0]);
    }
  }
  /** A PlayerInventory backed by a real Bukkit inventory, for plugins that move items with addItem/removeItem. */
  private static PlayerInventory realBackedInventory(){
    Inventory real=Bukkit.createInventory(null,36);
    return (PlayerInventory)Proxy.newProxyInstance(PlayerInventory.class.getClassLoader(),new Class<?>[]{PlayerInventory.class},(proxy,method,args)->{
      String name=method.getName();
      if(name.equals("getItemInMainHand")||name.equals("getItemInOffHand"))return new ItemStack(Material.AIR);
      if(name.equals("getStorageContents"))return real.getContents();
      if(name.equals("hashCode"))return System.identityHashCode(proxy);
      if(name.equals("equals"))return proxy==args[0];
      if(name.equals("toString"))return "SkyBoxySmokeRealInventory";
      if(method.getDeclaringClass().isAssignableFrom(Inventory.class))return method.invoke(real,args);
      Class<?> result=method.getReturnType();if(result==boolean.class)return false;if(result==int.class)return 0;return null;
    });
  }
  private static PlayerInventory fakeInventory(int size){
    ItemStack[] contents=new ItemStack[size];
    return (PlayerInventory)Proxy.newProxyInstance(PlayerInventory.class.getClassLoader(),new Class<?>[]{PlayerInventory.class},(proxy,method,args)->{
      String name=method.getName();
      if(name.equals("getSize")||name.equals("size"))return contents.length;
      if(name.equals("getMaxStackSize"))return 64;
      if(name.equals("getItem"))return contents[(int)args[0]];
      if(name.equals("setItem")){contents[(int)args[0]]=(ItemStack)args[1];return null;}
      if(name.equals("getContents"))return contents.clone();
      if(name.equals("getStorageContents"))return Arrays.copyOf(contents,Math.min(36,contents.length));
      if(name.equals("setContents")||name.equals("setStorageContents")){ItemStack[] supplied=(ItemStack[])args[0];System.arraycopy(supplied,0,contents,0,Math.min(supplied.length,contents.length));return null;}
      if(name.equals("getItemInMainHand"))return contents[0]==null?new ItemStack(Material.AIR):contents[0]; // Bukkit never returns null here
      if(name.equals("getItemInOffHand"))return contents.length>40?contents[40]:null;
      if(name.equals("hashCode"))return System.identityHashCode(proxy);
      if(name.equals("equals"))return proxy==args[0];
      if(name.equals("toString"))return "SkyBoxySmokeInventory";
      Class<?> result=method.getReturnType();if(result==boolean.class)return false;if(result==int.class)return 0;if(result==long.class)return 0L;
      if(result==double.class)return 0D;if(result==float.class)return 0F;if(result==short.class)return (short)0;if(result==byte.class)return (byte)0;
      if(result==char.class)return (char)0;if(result==Optional.class)return Optional.empty();if(result==Set.class)return Set.of();if(result==List.class)return List.of();return null;
    });
  }
  private static void invoke(Object target,String name,Class<?>[] parameterTypes,Object... arguments)throws Exception{Method method=target.getClass().getDeclaredMethod(name,parameterTypes);method.setAccessible(true);method.invoke(target,arguments);}
  private static Object call(Object target,String name,Class<?>[] parameterTypes,Object... arguments)throws Exception{Method method=target.getClass().getDeclaredMethod(name,parameterTypes);method.setAccessible(true);return method.invoke(target,arguments);}
  private final List<String> playerMessages=Collections.synchronizedList(new ArrayList<>());
  private Player fakePlayer(UUID id,Set<String> permissions,Inventory[] opened){return fakePlayer(id,permissions,opened,null);}
  private Player fakePlayer(UUID id,Set<String> permissions,Inventory[] opened,PlayerInventory inventory){
    return fakePlayer(id,permissions,opened,inventory,()->{});
  }
  private Player fakePlayer(UUID id,Set<String> permissions,Inventory[] opened,PlayerInventory inventory,Runnable save){
    return fakePlayer(id,permissions,opened,inventory,save,()->true);
  }
  private Player fakePlayer(UUID id,Set<String> permissions,Inventory[] opened,PlayerInventory inventory,Runnable save,java.util.function.BooleanSupplier online){
    return (Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},(proxy,method,args)->{
      if(method.getName().equals("getUniqueId"))return id;
      if(method.getName().equals("getInventory")&&inventory!=null)return inventory;
      if(method.getName().equals("saveData")){save.run();return null;}
      if(method.getName().equals("getName"))return "smoke_"+id.toString().substring(0,8); // unique, valid Minecraft name: economies and ChestShop map names to accounts
      if(method.getName().equals("hasPermission"))return args!=null&&args.length>0&&permissions.contains(args[0].toString());
      if(method.getName().equals("openInventory")){opened[0]=(Inventory)args[0];return null;}
      if(method.getName().equals("getOpenInventory"))return Proxy.newProxyInstance(org.bukkit.inventory.InventoryView.class.getClassLoader(),new Class<?>[]{org.bukkit.inventory.InventoryView.class},
              (view,viewMethod,viewArgs)->viewMethod.getName().equals("getTopInventory")?opened[0]:null);
      if(method.getName().equals("sendMessage")){if(args!=null&&args.length>0)playerMessages.add(String.valueOf(args[0]));return null;}
      if(method.getName().equals("closeInventory"))return null;
      if(method.getName().equals("getWorld"))return Bukkit.getWorld("world");
      if(method.getName().equals("isOnline"))return online.getAsBoolean();
      if(method.getName().equals("isOp"))return false;
      if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
      if(method.getName().equals("equals"))return proxy==args[0];
      if(method.getName().equals("toString"))return "SkyBoxyMarketSmokePlayer["+id+"]";
      Class<?> result=method.getReturnType();
      if(result==boolean.class)return false;if(result==int.class)return 0;if(result==long.class)return 0L;
      if(result==double.class)return 0D;if(result==float.class)return 0F;if(result==short.class)return (short)0;
      if(result==byte.class)return (byte)0;if(result==char.class)return (char)0;
      if(result==Optional.class)return Optional.empty();if(result==Set.class)return Set.of();if(result==List.class)return List.of();
      return null;
    });
  }
  private static void assertMenuActions(Inventory inventory,Plugin plugin,Set<String> expected){
    if(inventory==null||inventory.getSize()!=54||inventory.getHolder()==null)throw new IllegalStateException("Marketplace menu was not a native 54-slot inventory");
    NamespacedKey key=new NamespacedKey(plugin,"menu_action");Set<String> actions=new HashSet<>();
    for(ItemStack item:inventory.getContents())if(item!=null&&item.hasItemMeta()){
      String action=item.getItemMeta().getPersistentDataContainer().get(key,PersistentDataType.STRING);if(action!=null)actions.add(action);
    }
    if(!actions.containsAll(expected))throw new IllegalStateException("Menu is missing actions "+expected+"; found "+actions);
  }
  private static boolean same(int[] a,int[] b){return a[0]==b[0]&&a[1]==b[1]&&a[2]==b[2];}
  private static Object field(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
  private static void setField(Object o,String n,Object v)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
  private void fail(String message,Throwable t){getLogger().severe(message+": "+t);t.printStackTrace();Bukkit.getScheduler().runTaskLater(this,Bukkit::shutdown,10L);}
}



