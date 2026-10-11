package dev.skyboxy.market;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** All marketplace administration and player management is handled by Minecraft inventory GUIs. */
final class MarketMenus implements Listener {
    private final SkyBoxyMarket p;
    private final NamespacedKey actionKey;
    private final int PAGE_SIZE=45;
    MarketMenus(SkyBoxyMarket p){this.p=p;actionKey=new NamespacedKey(p,"menu_action");}
    static final class Screen implements InventoryHolder {
        private Inventory inventory;
        private int recoveryPage=-1;
        Screen(String name){inventory=Bukkit.createInventory(this,54,SkyBoxyMarket.c("&8SkyBoxy &7| &f"+name));}
        @Override public Inventory getInventory(){return inventory;}
    }
    private Screen screen(String title){return new Screen(title);}
    private void put(Screen s,int slot,Material mat,String name,String description,String action){
        if(slot<0||slot>=54)return;
        ItemStack stack=new ItemStack(mat);ItemMeta m=stack.getItemMeta();
        m.setDisplayName(SkyBoxyMarket.c(name));
        if(description!=null)m.setLore(Arrays.stream(description.split("\n",-1)).map(SkyBoxyMarket::c).toList());
        m.getPersistentDataContainer().set(actionKey,PersistentDataType.STRING,action);
        stack.setItemMeta(m);s.inventory.setItem(slot,stack);
    }
    private void controls(Screen s,String previous,String next,String back){
        if(previous!=null)put(s,45,Material.ARROW,"&ePrevious page",null,previous);
        if(back!=null)put(s,49,Material.BARRIER,"&cBack",null,back);
        if(next!=null)put(s,53,Material.ARROW,"&eNext page",null,next);
    }
    private void show(Player player,Screen s){player.openInventory(s.inventory);}
    void player(Player user){player(user,0);}
    void player(Player user,int page){
        Screen s=screen("My Market");
        List<Booth> mine=p.own(user.getUniqueId());
        if(mine.isEmpty())put(s,13,Material.BARRIER,"&eYou don't have a booth", "&7Use Browse Markets to rent one.","browse:0");
        else {
            int start=page*PAGE_SIZE;
            for(int i=start;i<Math.min(start+PAGE_SIZE,mine.size());i++){
                Booth b=mine.get(i);int slot=i-start;
                MarketStyle style=p.style(b);
                put(s,slot++,style==null?Material.CHEST:style.icon,"&a"+(style==null?b.style:style.name),"&7ID: "+b.id.substring(0,8)+"\n&7Status: "+b.status()+"\n&7Left: "+timeLeft(b),"detail:"+b.id);
            }
        }
        put(s,46,Material.COMPASS,"&aBrowse Markets","&7Available booths and public shops","browse:0");
        put(s,48,Material.CHEST,"&eItem Recovery","&7Claim items from expired/deleted booths","recover:0");
        put(s,50,Material.PAINTING,"&bChange Wall Colors","&7Customize your rented booth","mywalls");
        if(user.hasPermission("skyboxymarket.admin"))put(s,52,Material.COMMAND_BLOCK,"&cAdministration","&7Manage global marketplace","admin");
        controls(s,page>0?"player:"+(page-1):null,(page+1)*PAGE_SIZE<mine.size()?"player:"+(page+1):null,null);
        show(user,s);
    }
    void myWalls(Player user,int page){
        List<Booth> mine=p.own(user.getUniqueId());
        if(mine.isEmpty()){p.tell(user,"&cYou don't have a booth.");return;}
        if(mine.size()==1){walls(user,mine.getFirst());return;}
        Screen s=screen("Choose a Booth");int start=page*PAGE_SIZE;
        for(int i=start;i<Math.min(start+PAGE_SIZE,mine.size());i++){
            Booth booth=mine.get(i);MarketStyle style=p.style(booth);int slot=i-start;
            put(s,slot,style==null?Material.CHEST:style.icon,"&b"+(style==null?booth.style:style.name),
                    "&7ID: "+booth.id.substring(0,8)+"\n&7Current walls: "+booth.wallColor,"walls:"+booth.id);
        }
        controls(s,page>0?"ownedwalls:"+(page-1):null,(page+1)*PAGE_SIZE<mine.size()?"ownedwalls:"+(page+1):null,"player");
        show(user,s);
    }
    void myTeleport(Player user,int page){
        List<Booth> mine=p.own(user.getUniqueId());
        if(mine.isEmpty()){p.tell(user,"&cYou don't have a booth.");return;}
        if(mine.size()==1){p.rentals.teleport(user,mine.getFirst());return;}
        Screen s=screen("Choose a Booth");int start=page*PAGE_SIZE;
        for(int i=start;i<Math.min(start+PAGE_SIZE,mine.size());i++){
            Booth booth=mine.get(i);MarketStyle style=p.style(booth);int slot=i-start;
            put(s,slot,style==null?Material.CHEST:style.icon,"&b"+(style==null?booth.style:style.name),
                    "&7ID: "+booth.id.substring(0,8)+"\n&7Teleport to this booth","tp:"+booth.id);
        }
        controls(s,page>0?"ownedtp:"+(page-1):null,(page+1)*PAGE_SIZE<mine.size()?"ownedtp:"+(page+1):null,"player");
        show(user,s);
    }
    void browse(Player user,int page){
        List<Booth> all=p.booths.stream().filter(b->!b.resetting).sorted(Comparator.comparing((Booth b)->b.owner!=null).thenComparing(b->b.style)).toList();
        Screen s=screen("Market Directory");int start=page*PAGE_SIZE;
        for(int i=start;i<Math.min(start+PAGE_SIZE,all.size());i++){
            Booth b=all.get(i);MarketStyle style=p.style(b);
            put(s,i-start,style==null?Material.CHEST:style.icon,b.owner==null?"&aAvailable: "+(style==null?b.style:style.name):"&e"+(style==null?b.style:style.name),
                "&7ID: "+b.id.substring(0,8)+"\n&7Status: "+b.status()+"\n&7Click to view", "detail:"+b.id);
        }
        controls(s,page>0?"browse:"+(page-1):null,start+PAGE_SIZE<all.size()?"browse:"+(page+1):null,"player");show(user,s);
    }
    void detail(Player user,Booth b,boolean admin){
        MarketStyle style=p.style(b);if(style==null){p.tell(user,"&cThis booth's style is missing. Contact staff.");return;}
        boolean manager=user.getUniqueId().equals(b.owner);
        Screen s=screen("Booth "+b.id.substring(0,8));
        put(s,4,style.icon,"&f"+style.name,"&7Booth: "+b.id+"\n&7Owner: "+name(b.owner)+"\n&7Status: "+b.status()+"\n&7Access: "+b.access+"\n&7Remaining: "+timeLeft(b),"noop");
        if(b.owner==null&&!b.suspended&&!b.resetting){
            String restriction=style.permission.isBlank()?"None":style.permission;
            put(s,20,Material.EMERALD,"&aRent booth","&7Cost: $"+money(style.price)+"\n&7Duration: "+style.durationHours+" hours\n&7Permission: "+restriction,"rentconfirm:"+b.id);
        }else {
            put(s,19,Material.ENDER_PEARL,"&bTeleport to booth","&7Only works when access allows entry","tp:"+b.id);
            put(s,20,Material.BOOK,"&fMarket Statistics","&7Visits: "+b.visits+"\n&7Unique visitors: "+b.uniqueVisitors+"\n&7Sales: "+sales(b),"stats:"+b.id);
            if(manager||admin){
                put(s,21,Material.CLOCK,"&aRenew Rental","&7Cost: $"+money(style.renewalPrice),"renew:"+b.id);
                put(s,22,Material.PAINTING,"&eWall Colors",null,"walls:"+b.id);
                put(s,23,Material.OAK_DOOR,"&eAccess Mode","&7Current: "+b.access+"\n&7Click to cycle Public / Private / Locked","access:"+b.id);
                put(s,24,Material.PLAYER_HEAD,"&bTrusted Members","&7Add or remove trusted players","members:"+b.id);
                put(s,25,Material.BARRIER,"&cBanned Players","&7Ban players from physically entering","bans:"+b.id);
            }
        }
        if(admin&&user.hasPermission("skyboxymarket.admin")){
            put(s,37,Material.COMMAND_BLOCK,"&cManage as Admin","&7Assign, terminate, suspend, extend, offsets","edit:"+b.id);
        }
        controls(s,null,null,admin?"adminbooths:0":"player");show(user,s);
    }
    private static String name(UUID uuid){if(uuid==null)return "None";String name=Bukkit.getOfflinePlayer(uuid).getName();return name==null?uuid.toString().substring(0,8):name;}
    private static String timeLeft(Booth b){long secs=Math.max(0,(b.expiry-System.currentTimeMillis())/1000);return secs/86400+"d "+(secs%86400)/3600+"h";}
    private static String money(double val){return String.format(Locale.US,"%,.2f",val);}
    void confirm(Player u,Booth b){
        MarketStyle st=p.style(b);if(st==null)return;
        Screen s=screen("Confirm Rental");
        put(s,22,st.icon,"&f"+st.name,"&7Price: $"+money(st.price)+"\n&7Term: "+st.durationHours+"h","noop");
        put(s,29,Material.LIME_CONCRETE,"&aConfirm purchase","&7Withdraw from your economy balance","rent:"+b.id);
        put(s,33,Material.RED_CONCRETE,"&cCancel","&7No payment taken","detail:"+b.id);show(u,s);
    }
    void walls(Player user,Booth b){
        if(!user.getUniqueId().equals(b.owner)&&!user.hasPermission("skyboxymarket.admin")){p.tell(user,"&cYou do not own this booth.");return;}
        MarketStyle st=p.style(b);if(st==null)return;
        Screen s=screen("Wall Colors");int i=0;
        for(String color:List.of("WHITE","ORANGE","MAGENTA","LIGHT_BLUE","YELLOW","LIME","PINK","GRAY","LIGHT_GRAY","CYAN","PURPLE","BLUE","BROWN","GREEN","RED","BLACK")){
            Material material=Material.matchMaterial(color+"_CONCRETE");
            if(material==null)continue;
            put(s,i++,material,"&f"+color.replace('_',' '),"&7Cost: $"+money(st.wallCost)+"\n&7Current: "+b.wallColor+"\n"+(st.colors.contains(color)?"&aAvailable":"&cDisabled"),"wallcolor:"+b.id+":"+color);
        }
        controls(s,null,null,"detail:"+b.id);show(user,s);
    }
    void stats(Player u,Booth b){
        Screen s=screen("Market Statistics");
        put(s,11,Material.COMPASS,"&eTotal Visits","&f"+b.visits,"noop");
        put(s,13,Material.PLAYER_HEAD,"&eUnique Visitors","&f"+b.uniqueVisitors,"noop");
        put(s,15,Material.GOLD_INGOT,"&eTracked Sales","&f"+sales(b),"noop");
        put(s,29,Material.CLOCK,"&fRental Expiration","&7"+new Date(b.expiry),"noop");
        put(s,33,Material.OAK_SIGN,"&fBooth Style","&7"+b.style,"noop");
        controls(s,null,null,"detail:"+b.id);show(u,s);
    }
    void accessList(Player u,Booth b,boolean banned,int page){
        List<UUID> ids=(banned?b.banned:b.trusted).stream().sorted().toList();
        Screen s=screen(banned?"Banned Players":"Trusted Members");
        for(int i=page*PAGE_SIZE;i<Math.min(ids.size(),(page+1)*PAGE_SIZE);i++){
            UUID id=ids.get(i);
            put(s,i-page*PAGE_SIZE,Material.PLAYER_HEAD,"&f"+name(id),"&7"+id+"\n&cClick to remove","accessremove:"+b.id+":"+(banned?"BAN":"TRUST")+":"+id);
        }
        put(s,48,Material.LIME_DYE,banned?"&aBan Player":"&aAdd Trusted Member","&7Enter online player name or UUID in chat","accessadd:"+b.id+":"+(banned?"BAN":"TRUST"));
        controls(s,page>0?"accesslist:"+b.id+":"+(banned?"BAN":"TRUST")+":"+(page-1):null,
                (page+1)*PAGE_SIZE<ids.size()?"accesslist:"+b.id+":"+(banned?"BAN":"TRUST")+":"+(page+1):null,"detail:"+b.id);show(u,s);
    }
    void recover(Player u,int page){
        Screen s=screen("Recovery Storage");
        try{
            int count=p.db.recoveryCount(u.getUniqueId());
            page=Math.max(0,Math.min(page,Math.max(0,(count-1)/PAGE_SIZE)));s.recoveryPage=page;
            List<MarketDatabase.Recovered> items=p.db.recovered(u.getUniqueId(),page,PAGE_SIZE);
            for(int i=0;i<items.size();i++){
                MarketDatabase.Recovered item=items.get(i);ItemStack clone=item.item().clone();
                ItemMeta meta=clone.getItemMeta();if(meta==null)continue;
                List<String> lore=new ArrayList<>(meta.hasLore()?meta.getLore():List.of());
                lore.add(SkyBoxyMarket.c("&7From booth "+item.booth().substring(0,8)));
                lore.add(SkyBoxyMarket.c("&aClick to claim as many as fit"));
                lore.add(SkyBoxyMarket.c("&7Any remainder stays in recovery"));
                meta.setLore(lore);meta.getPersistentDataContainer().set(actionKey,PersistentDataType.STRING,"claim:"+item.id()+":"+page);
                clone.setItemMeta(meta);s.inventory.setItem(i,clone);
            }
            put(s,47,Material.PAPER,"&eRecovery Items: "+count,"&7Page "+(page+1)+" / "+Math.max(1,(count+44)/45),"noop");
            put(s,51,Material.CHEST,"&aClaim all on page","&7Claims as many items as will fit","claimpage:"+page);
            put(s,50,Material.ENDER_CHEST,"&aClaim all storage","&7Checks every page for items that fit\n&7Leftovers stay safely in recovery","claimall");
            controls(s,page>0?"recover:"+(page-1):null,(page+1)*PAGE_SIZE<count?"recover:"+(page+1):null,"player");
        }catch(Exception ex){p.getLogger().warning("Recovery menu could not load: "+ex);}
        show(u,s);
    }
    void admin(Player u){
        if(!u.hasPermission("skyboxymarket.admin"))return;
        Screen s=screen("Administration");
        put(s,10,Material.CHEST,"&aAll Markets","&7Manage owners, status, and leases","adminbooths:0");
        put(s,12,Material.MAP,"&bScan Schematics","&7Discover all valid booths inside marketmain","scan");
        put(s,14,Material.BOOK,"&eBooth Styles","&7Prices, schematic paths, rank requirements","styles:0");
        put(s,16,Material.IRON_DOOR,"&6Global Permissions","&7Visitors, renters and trusted members","permissions:VISITOR");
        put(s,28,Material.CLOCK,"&eScan status","&7"+p.scanner.progress(),"scanstatus");
        if(p.scanner.running())put(s,30,Material.BARRIER,"&cCancel scan","&7Keeps any booths already registered","scancancel");
        else put(s,30,Material.PAPER,"&eMarket Statistics","&7Booths: "+p.booths.size()+"\n&7Rented: "+p.booths.stream().filter(Booth::rented).count(),"adminstats");
        put(s,32,Material.REDSTONE,"&cReload Configuration","&7Reloads styles and templates; does not clear booths","reload");
        put(s,34,Material.WRITABLE_BOOK,"&bSystem Diagnostics","&7Providers, database health and queues","diagnostics");
        put(s,36,Material.CHEST,"&aBack Up Database","&7Create a consistent SQLite snapshot","backup");
        int paymentReviews=0;try{paymentReviews=p.db.unresolvedPaymentIntentCount();}catch(Exception ex){p.getLogger().warning("Could not count payment reviews: "+ex);}
        put(s,38,paymentReviews>0?Material.GOLD_INGOT:Material.EMERALD,"&6Payment Review: "+paymentReviews,
                "&7Inspect interrupted charges/refunds\n&cVerify the economy ledger before resolving","paymentreviews:0");
        put(s,40,Material.PLAYER_HEAD,"&bSearch Player Booths","&7Find an owner by player name or UUID","ownersearch");
        put(s,49,Material.BARRIER,"&cExit",null,"close");show(u,s);
    }
    void paymentReviews(Player u,int page){
        if(!u.hasPermission("skyboxymarket.admin"))return;
        Screen s=screen("Payment Review");
        try{
            List<MarketDatabase.PaymentIntent> intents=p.db.paymentIntents(page,PAGE_SIZE);
            int count=p.db.unresolvedPaymentIntentCount();
            if(intents.isEmpty())put(s,13,Material.EMERALD,"&aNo payment reviews pending","&7All marketplace payment intents are resolved.","noop");
            for(int i=0;i<intents.size();i++){
                MarketDatabase.PaymentIntent intent=intents.get(i);
                put(s,i,intent.status().equals("REVIEW_REQUIRED")?Material.GOLD_INGOT:Material.CLOCK,
                        "&e"+intent.action()+" $"+money(intent.amount()),
                        "&7State: "+intent.status()+"\n&7Player: "+intent.player()+"\n&7Booth: "+intent.booth()+"\n&7Reference: "+intent.id().substring(0,8),
                        "paymentdetail:"+intent.id());
            }
            put(s,47,Material.PAPER,"&eUnresolved payments: "+count,"&7Page "+(page+1)+" / "+Math.max(1,(count+PAGE_SIZE-1)/PAGE_SIZE),"noop");
            controls(s,page>0?"paymentreviews:"+(page-1):null,(page+1)*PAGE_SIZE<count?"paymentreviews:"+(page+1):null,"admin");
        }catch(Exception ex){p.getLogger().warning("Could not open payment review menu: "+ex);put(s,13,Material.BARRIER,"&cPayment records unavailable","&7Check the server console.","noop");controls(s,null,null,"admin");}
        show(u,s);
    }
    void paymentIntentDetail(Player u,String id){
        if(!u.hasPermission("skyboxymarket.admin"))return;
        Screen s=screen("Payment Intent");
        try{
            MarketDatabase.PaymentIntent intent=p.db.paymentIntent(id);
            if(intent==null){p.tell(u,"&cPayment reference not found.");paymentReviews(u,0);return;}
            put(s,13,Material.GOLD_INGOT,"&e"+intent.action()+" $"+money(intent.amount()),
                    "&7Reference: "+intent.id()+"\n&7State: "+intent.status()+"\n&7Provider: "+intent.provider()+
                    "\n&7Player: "+intent.player()+"\n&7Booth: "+intent.booth()+"\n&7Details: "+(intent.detail().isBlank()?"none":intent.detail()),"noop");
            if(intent.status().equals("REVIEW_REQUIRED")){
                put(s,28,Material.RED_DYE,"&cNo debit confirmed","&7Use only after checking provider history.","paymentresolveconfirm:"+id+":NO_CHARGE_VERIFIED");
                put(s,30,Material.EMERALD,"&aRefund confirmed","&7Use only after the refund is already recorded by the provider.","paymentresolveconfirm:"+id+":REFUND_VERIFIED");
                put(s,32,Material.LIME_DYE,"&aMarketplace action confirmed","&7Use only after verifying the lease/style was applied.","paymentresolveconfirm:"+id+":ACTION_VERIFIED");
            }else put(s,30,Material.CLOCK,"&eAwaiting restart review","&7This intent will be surfaced for staff review on startup.","noop");
            controls(s,null,null,"paymentreviews:0");
        }catch(Exception ex){p.getLogger().warning("Could not read payment intent "+id+": "+ex);p.tell(u,"&cPayment record could not be read.");paymentReviews(u,0);return;}
        show(u,s);
    }
    private void confirmPaymentResolution(Player u,String id,MarketDatabase.PaymentResolution resolution){
        if(!u.hasPermission("skyboxymarket.admin"))return;
        MarketDatabase.PaymentIntent intent;
        try{intent=p.db.paymentIntent(id);}catch(Exception ex){p.getLogger().warning("Could not read payment intent for confirmation: "+ex);paymentReviews(u,0);return;}
        if(intent==null||!intent.status().equals("REVIEW_REQUIRED")){p.tell(u,"&cThis payment is no longer awaiting review.");paymentReviews(u,0);return;}
        Screen s=screen("Confirm Payment Review");
        String outcome=switch(resolution){
            case NO_CHARGE_VERIFIED -> "provider history confirms no debit";
            case REFUND_VERIFIED -> "provider history confirms a completed refund";
            case ACTION_VERIFIED -> "the matching marketplace action is applied";
        };
        put(s,13,Material.GOLD_INGOT,"&eVerify before recording","&7Reference: "+intent.id()+"\n&7Amount: $"+money(intent.amount())+
                "\n&7Record only if "+outcome+".\n&cThis records your verification; it does not contact the economy provider.","noop");
        put(s,30,Material.LIME_CONCRETE,"&aRecord verified outcome",null,"paymentresolve:"+id+":"+resolution.name());
        controls(s,null,null,"paymentdetail:"+id);show(u,s);
    }
    void diagnostics(Player u){
        if(!u.hasPermission("skyboxymarket.admin"))return;
        Screen s=screen("System Diagnostics");
        String integrity="unavailable";int resets=-1,recovery=-1,inFlight=-1,payments=-1;
        try{integrity=p.db.integrityCheck();resets=p.db.resetCount();recovery=p.db.totalRecoveryCount();inFlight=p.db.inFlightClaimCount();payments=p.db.unresolvedPaymentIntentCount();}
        catch(Exception ex){p.getLogger().warning("Could not read marketplace diagnostics: "+ex);}
        boolean worldGuard=Bukkit.getPluginManager().isPluginEnabled("WorldGuard");
        boolean worldEdit=Bukkit.getPluginManager().isPluginEnabled("WorldEdit")||Bukkit.getPluginManager().isPluginEnabled("FastAsyncWorldEdit");
        put(s,10,Material.BOOK,"&eDatabase: "+integrity,"&7Resets pending: "+resets+"\n&7Recovery entries available: "+recovery+"\n&7Claims in flight: "+inFlight+"\n&7Payment reviews: "+payments,"noop");
        put(s,12,Material.MAP,"&eBooths / Styles","&7Registered: "+p.booths.size()+"\n&7Configured styles: "+p.styles.size()+"\n&7Loaded templates: "+p.templates.enabled().size(),"noop");
        put(s,14,Material.ENDER_EYE,"&eProviders","&7WorldGuard: "+state(worldGuard)+"\n&7WorldEdit/FAWE: "+state(worldEdit)+"\n&7Vault economy: "+state(p.economy!=null)+shopStates(),"noop");
        put(s,16,Material.CLOCK,"&eScanner","&7"+p.scanner.progress(),"noop");
        put(s,49,Material.BARRIER,"&cBack",null,"admin");show(u,s);
    }
    private static String state(boolean enabled){return enabled?"&aavailable":"&cnot available";}
    private String shopStates(){StringBuilder out=new StringBuilder();for(ShopProvider s:p.shops.all())out.append("\n&7").append(s.name()).append(": ").append(switch(s.status()){case AVAILABLE->"&aavailable";case ABSENT->"&7absent";case INCOMPATIBLE->"&cincompatible";});return out.toString();}
    // Never show a bare 0 when no shop plugin can report sales.
    private String sales(Booth b){return p.shops.anyAvailable()?"$"+money(b.sales):"unavailable (no compatible shop plugin)";}
    void adminBooths(Player u,int page){
        List<Booth> all=p.booths.stream().sorted(Comparator.comparing((Booth b)->b.style).thenComparing(b->b.id)).toList();
        Screen s=screen("Manage Markets");int start=page*PAGE_SIZE;
        for(int i=start;i<Math.min(start+PAGE_SIZE,all.size());i++){
            Booth b=all.get(i);MarketStyle st=p.style(b);
            put(s,i-start,st==null?Material.CHEST:st.icon,"&f"+(st==null?b.style:st.name),"&7ID: "+b.id.substring(0,8)+"\n&7Owner: "+name(b.owner)+"\n&7Status: "+b.status(),"edit:"+b.id);
        }
        controls(s,page>0?"adminbooths:"+(page-1):null,(page+1)*PAGE_SIZE<all.size()?"adminbooths:"+(page+1):null,"admin");show(u,s);
    }
    void adminOwnerSearch(Player u,UUID owner,int page){
        if(!u.hasPermission("skyboxymarket.admin"))return;
        List<Booth> all=p.booths.stream().filter(booth->owner.equals(booth.owner))
                .sorted(Comparator.comparing((Booth booth)->booth.style).thenComparing(booth->booth.id)).toList();
        Screen s=screen("Owner Booths");int start=page*PAGE_SIZE;
        if(all.isEmpty())put(s,13,Material.BARRIER,"&eNo owned booths","&7Player: "+owner,"noop");
        for(int i=start;i<Math.min(start+PAGE_SIZE,all.size());i++){
            Booth booth=all.get(i);MarketStyle style=p.style(booth);
            put(s,i-start,style==null?Material.CHEST:style.icon,"&f"+(style==null?booth.style:style.name),
                    "&7ID: "+booth.id.substring(0,8)+"\n&7Owner: "+name(owner)+"\n&7Status: "+booth.status(),"edit:"+booth.id);
        }
        controls(s,page>0?"ownersearchresults:"+owner+":"+(page-1):null,
                (page+1)*PAGE_SIZE<all.size()?"ownersearchresults:"+owner+":"+(page+1):null,"admin");show(u,s);
    }
    void edit(Player u,Booth b){
        Screen s=screen("Admin Booth Manager");
        MarketStyle st=p.style(b);
        put(s,4,st==null?Material.CHEST:st.icon,"&f"+b.id.substring(0,8),"&7Owner: "+name(b.owner)+"\n&7Style: "+b.style+"\n&7Status: "+b.status(),"noop");
        put(s,10,Material.PLAYER_HEAD,"&aAssign Owner","&7Enter player name/UUID in chat","assign:"+b.id);
        put(s,12,Material.CLOCK,"&aExtend 7 Days","&7Free admin extension","extend:"+b.id);
        put(s,14,Material.REDSTONE_TORCH,"&eSuspend / Unsuspend","&7Currently: "+b.suspended,"suspend:"+b.id);
        put(s,16,Material.ENDER_PEARL,"&bTeleport","&7To saved TP destination","tp:"+b.id);
        put(s,28,Material.COMPASS,"&bSet TP Offset Here","&7Use current admin location within this booth","setoffset:"+b.id+":teleport");
        put(s,30,Material.END_CRYSTAL,"&bSet Hologram Offset Here","&7Use current admin location within this booth","setoffset:"+b.id+":hologram");
        put(s,32,Material.IRON_DOOR,"&eChange Access","&7Cycles Public, Private, Locked","access:"+b.id);
        put(s,34,Material.TNT,"&cTerminate & Restore","&7Recover items, reset booth, free rental","resetconfirm:"+b.id);
        controls(s,null,null,"adminbooths:0");show(u,s);
    }
    void resetConfirm(Player u,Booth b){
        Screen s=screen("CONFIRM Reset");
        put(s,20,Material.RED_CONCRETE,"&cCONFIRM RESTORE","&7Saves belongings then restores booth\n&cUse only with chunks loaded!","reset:"+b.id);
        put(s,24,Material.LIME_CONCRETE,"&aCancel",null,"edit:"+b.id);show(u,s);
    }
    void permissions(Player u,String role){
        if(!List.of("VISITOR","RENTER","MEMBER").contains(role))role="VISITOR";
        Screen s=screen("Global " + role + " Rules");
        String[] keys={"place","break","containers","doors","buttons","shop-interact","entities","redstone","fluids","pvp"};
        int i=0;
        for(String key:keys){boolean val=p.getConfig().getBoolean("permissions."+role.toLowerCase(Locale.ROOT)+"."+key,false);
            put(s,i++,val?Material.LIME_DYE:Material.GRAY_DYE,(val?"&aALLOW ":"&cDENY ")+key,"&7Click to toggle global "+role.toLowerCase(Locale.ROOT)+" permission","perm:"+role+":"+key);
        }
        put(s,45,Material.LEATHER_BOOTS,"&eVisitor Rules",null,"permissions:VISITOR");
        put(s,46,Material.DIAMOND_BOOTS,"&eRenter Rules",null,"permissions:RENTER");
        put(s,47,Material.IRON_BOOTS,"&eTrusted Member Rules",null,"permissions:MEMBER");
        controls(s,null,null,"admin");show(u,s);
    }
    void styles(Player u,int page){
        List<MarketStyle> all=new ArrayList<>(p.styles.values());Screen s=screen("Booth Styles");int start=page*PAGE_SIZE;
        for(int i=start;i<Math.min(start+PAGE_SIZE,all.size());i++){
            MarketStyle st=all.get(i);
            put(s,i-start,st.icon,st.name,"&7ID: "+st.id+"\n&7Schematic: "+st.schematic+"\n&7Price: $"+money(st.price)+"\n&7Enabled: "+st.enabled,"style:"+st.id);
        }
        put(s,48,Material.EMERALD,"&aNew Style","&7Create in GUI; then upload schematic via FTP","newstyle");
        controls(s,page>0?"styles:"+(page-1):null,(page+1)*PAGE_SIZE<all.size()?"styles:"+(page+1):null,"admin");show(u,s);
    }
    void editStyle(Player u,MarketStyle st){
        Screen s=screen("Style: "+st.id);
        put(s,4,st.icon,st.name,"&7Template: "+st.schematic,"noop");
        put(s,10,Material.EMERALD,"&aPrice: $"+money(st.price),"&7Click, type amount in chat","stylefield:"+st.id+":rental.price");
        put(s,11,Material.GOLD_INGOT,"&eRenewal: $"+money(st.renewalPrice),null,"stylefield:"+st.id+":rental.renewal-price");
        put(s,12,Material.CLOCK,"&eTerm: "+st.durationHours+"h",null,"stylefield:"+st.id+":rental.duration-hours");
        put(s,13,Material.COMPASS,"&eRenewal Term: "+st.renewHours+"h",null,"stylefield:"+st.id+":rental.renewal-hours");
        put(s,14,Material.REDSTONE,"&eCooldown: "+st.cooldownHours+"h",null,"stylefield:"+st.id+":rental.cooldown-hours");
        put(s,15,Material.IRON_DOOR,"&ePermission: "+st.permission,"&7Blank = anyone","stylefield:"+st.id+":rental.required-permission");
        put(s,16,st.enabled?Material.LIME_DYE:Material.GRAY_DYE,"&eEnabled: "+st.enabled,null,"styletoggle:"+st.id);
        put(s,28,Material.PAPER,"&eMax / Player: "+st.maxPerPlayer,null,"stylefield:"+st.id+":rental.max-per-player");
        put(s,29,Material.CLOCK,"&eGrace: "+st.graceHours+"h",null,"stylefield:"+st.id+":rental.grace-hours");
        put(s,30,Material.CLOCK,"&eMax Prepaid: "+st.maxDurationHours+"h",null,"stylefield:"+st.id+":rental.max-duration-hours");
        put(s,31,Material.PAINTING,"&eWall Cost: $"+money(st.wallCost),null,"stylefield:"+st.id+":walls.cost");
        put(s,32,Material.REDSTONE_TORCH,"&eWall Cooldown: "+st.wallCooldown+"s",null,"stylefield:"+st.id+":walls.cooldown-seconds");
        put(s,33,Material.MAP,"&eSchematic: "+st.schematic,"&cEditing schematic of an active style is blocked; configure file before scanning.","stylefield:"+st.id+":schematic");
        put(s,34,Material.NAME_TAG,"&eDisplay Name: "+st.name,null,"stylefield:"+st.id+":name");
        put(s,35,Material.IRON_DOOR,"&6Style Permissions","&7Overrides for visitor, renter and trusted rules","stylepermissions:"+st.id+":VISITOR");
        put(s,36,st.icon,"&eIcon: "+st.icon.name(),"&7Enter a block item material name","stylefield:"+st.id+":icon");
        put(s,37,Material.COMPARATOR,"&ePriority: "+st.priority,"&7Used when more than one style matches","stylefield:"+st.id+":priority");
        put(s,38,Material.PAINTING,"&eWall Colors","&7"+String.join(", ",new TreeSet<>(st.colors)),"stylefield:"+st.id+":walls.colors");
        put(s,39,Material.CHEST,"&aClone Style","&7Creates a disabled copy with matching settings","styleclone:"+st.id);
        put(s,40,Material.BARRIER,"&cRetire Style","&7Disables this style after confirmation","styleretireconfirm:"+st.id);
        put(s,41,Material.GRAY_CONCRETE,"&7Wall Template Material: "+st.wallMaterial,"&7Material marked as recolorable walls","stylefield:"+st.id+":walls.template-material");
        controls(s,null,null,"styles:0");show(u,s);
    }
    void retireStyleConfirm(Player u,MarketStyle st){
        Screen s=screen("Retire style?");
        put(s,20,Material.RED_CONCRETE,"&cCONFIRM RETIRE","&7Disables new rentals for "+st.name+"\n&7Existing booth records are preserved","styleretire:"+st.id);
        put(s,24,Material.LIME_CONCRETE,"&aCancel",null,"style:"+st.id);show(u,s);
    }
    void stylePermissions(Player u,MarketStyle st,String role){
        if(!List.of("VISITOR","RENTER","MEMBER").contains(role))role="VISITOR";
        Screen s=screen(st.name+" "+role+" Rules");
        String[] keys={"place","break","containers","doors","buttons","shop-interact","entities","redstone","fluids","pvp"};
        for(int i=0;i<keys.length;i++){
            String key=keys[i];boolean enabled=MarketPolicy.allowed(p.getConfig(),st.id,MarketRole.valueOf(role),key);
            put(s,i,enabled?Material.LIME_DYE:Material.GRAY_DYE,(enabled?"&aALLOW ":"&cDENY ")+key,
                    "&7Current effective rule\n&7Click to override global setting","styleperm:"+st.id+":"+role+":"+key);
        }
        put(s,28,Material.LEATHER_BOOTS,"&eVisitor rules",null,"stylepermissions:"+st.id+":VISITOR");
        put(s,29,Material.DIAMOND_BOOTS,"&eRenter rules",null,"stylepermissions:"+st.id+":RENTER");
        put(s,30,Material.IRON_BOOTS,"&eTrusted member rules",null,"stylepermissions:"+st.id+":MEMBER");
        controls(s,null,null,"style:"+st.id);show(u,s);
    }
    @EventHandler public void click(InventoryClickEvent e){
        if(!(e.getView().getTopInventory().getHolder() instanceof Screen))return;
        if(!(e.getWhoClicked() instanceof Player u))return;
        e.setCancelled(true);
        if(e.getClickedInventory()!=e.getView().getTopInventory())return;
        ItemStack item=e.getCurrentItem();if(item==null||!item.hasItemMeta())return;
        String raw=item.getItemMeta().getPersistentDataContainer().get(actionKey,PersistentDataType.STRING);
        if(raw==null)return;
        Bukkit.getScheduler().runTask(p,()->route(u,raw));
    }
    @EventHandler public void drag(InventoryDragEvent e){if(e.getView().getTopInventory().getHolder() instanceof Screen)e.setCancelled(true);}
    private void route(Player u,String raw){
        if(!u.isOnline())return;
        String[] v=raw.split(":",-1);String op=v[0];String id=v.length>1?v[1]:"";
        Booth b=p.byId(id);boolean admin=u.hasPermission("skyboxymarket.admin");
        if(Set.of("recover","claim","claimpage","claimall").contains(op)&&!admin&&!u.hasPermission("skyboxymarket.use")){p.tell(u,"&cNo permission.");return;}
        if(Set.of("claim","claimpage").contains(op)&&p.recovery.isClaimingAll(u.getUniqueId())){p.tell(u,"&eRecovery is already in progress.");return;}
        try {
            switch(op){
                case "noop" -> {}
                case "close" -> u.closeInventory();
                case "player" -> player(u,id.isBlank()?0:Integer.parseInt(id));
                case "browse" -> browse(u,Integer.parseInt(id));
                case "detail" -> {if(b!=null)detail(u,b,false);}
                case "rentconfirm" -> {if(b!=null)confirm(u,b);}
                case "rent" -> {if(b!=null){u.closeInventory();p.rentals.rent(u,b);}}
                case "tp" -> {if(b!=null){u.closeInventory();p.rentals.teleport(u,b);}}
                case "mywalls" -> myWalls(u,0);
                case "ownedwalls" -> myWalls(u,Integer.parseInt(id));
                case "mytp" -> myTeleport(u,0);
                case "ownedtp" -> myTeleport(u,Integer.parseInt(id));
                case "walls" -> {if(b!=null)walls(u,b);}
                case "wallcolor" -> {if(b!=null){p.rentals.walls(u,b,v[2]);walls(u,b);}}
                case "stats" -> {if(b!=null&&(u.getUniqueId().equals(b.owner)||admin))stats(u,b);}
                case "renew" -> {if(b!=null){p.rentals.renew(u,b);detail(u,b,false);}}
                case "access" -> {if(b!=null&&(u.getUniqueId().equals(b.owner)||admin)){
                    String previous=b.access;b.access=switch(b.access){case "PUBLIC"->"PRIVATE";case "PRIVATE"->"LOCKED";default->"PUBLIC";};
                    if(!p.save(b)){b.access=previous;p.tell(u,"&cAccess mode was not saved.");detail(u,b,admin);return;}
                    p.db.audit(u.getUniqueId().toString(),"access",b.id+"="+b.access);p.protectionEject(b);detail(u,b,admin);
                }}
                case "members","bans" -> {if(b!=null&&(u.getUniqueId().equals(b.owner)||admin))accessList(u,b,op.equals("bans"),0);}
                case "accesslist" -> {if(b!=null&&(u.getUniqueId().equals(b.owner)||admin))accessList(u,b,"BAN".equals(v[2]),Integer.parseInt(v[3]));}
                case "accessadd" -> {if(b!=null&&(u.getUniqueId().equals(b.owner)||admin))MarketChatInput.prompt(u,"access:"+b.id+":"+v[2],"Enter a player name or UUID (or 'cancel') in chat.");}
                case "accessremove" -> {if(b!=null&&(u.getUniqueId().equals(b.owner)||admin)){
                    boolean trust="TRUST".equals(v[2]);p.db.access(b,UUID.fromString(v[3]),trust,false);accessList(u,b,!trust,0);
                }}
                case "recover" -> recover(u,Integer.parseInt(id));
                case "claim" -> {List<MarketDatabase.Recovered> entries=p.db.recovered(u.getUniqueId(),Integer.parseInt(v[2]),PAGE_SIZE);
                    entries.stream().filter(x->x.id()==Long.parseLong(id)).findFirst().ifPresent(x->p.recovery.claim(u,x.id(),x.item()));recover(u,Integer.parseInt(v[2]));}
                case "claimpage" -> {for(MarketDatabase.Recovered x:p.db.recovered(u.getUniqueId(),Integer.parseInt(id),PAGE_SIZE))p.recovery.claim(u,x.id(),x.item());recover(u,Integer.parseInt(id));}
                case "claimall" -> p.recovery.claimAll(u,()->{
                    if(u.getOpenInventory().getTopInventory().getHolder() instanceof Screen current&&current.recoveryPage>=0)recover(u,current.recoveryPage);
                });
                case "admin" -> {if(admin)admin(u);}
                case "adminbooths" -> {if(admin)adminBooths(u,Integer.parseInt(id));}
                case "ownersearch" -> {if(admin)MarketChatInput.prompt(u,"ownersearch","Enter player name or UUID to find their booths (or 'cancel').");}
                case "ownersearchresults" -> {if(admin&&v.length==3)adminOwnerSearch(u,UUID.fromString(v[1]),Integer.parseInt(v[2]));}
                case "diagnostics" -> {if(admin)diagnostics(u);}
                case "paymentreviews" -> {if(admin)paymentReviews(u,Integer.parseInt(id));}
                case "paymentdetail" -> {if(admin)paymentIntentDetail(u,id);}
                case "paymentresolveconfirm" -> {if(admin&&v.length==3)confirmPaymentResolution(u,id,MarketDatabase.PaymentResolution.valueOf(v[2]));}
                case "paymentresolve" -> {if(admin&&v.length==3){
                    MarketDatabase.PaymentResolution outcome=MarketDatabase.PaymentResolution.valueOf(v[2]);
                    p.db.resolvePaymentIntent(id,outcome,u.getUniqueId());
                    p.tell(u,"&aPayment review recorded as "+outcome+". No economy transaction was issued.");paymentReviews(u,0);
                }}
                case "backup" -> {if(admin){
                    try{p.tell(u,"&aDatabase backup created: "+p.createDatabaseBackup().getName());}
                    catch(Exception ex){p.tell(u,"&cDatabase backup failed. See console.");p.getLogger().severe("Database backup failed: "+ex);}
                    admin(u);
                }}
                case "scan" -> {if(admin){u.closeInventory();p.scanner.scan(u);}}
                case "scanstatus" -> {if(admin){p.tell(u,"&f"+p.scanner.progress());admin(u);}}
                case "scancancel" -> {if(admin){p.scanner.cancel(u);admin(u);}}
                case "adminstats" -> {if(admin){p.tell(u,"&fBooths: "+p.booths.size()+", rented: "+p.booths.stream().filter(Booth::rented).count());admin(u);}}
                case "reload" -> {if(admin){p.reloadConfig();p.reloadStyles();p.holograms.refreshAll();admin(u);}}
                case "edit" -> {if(admin&&b!=null)edit(u,b);}
                case "resetconfirm" -> {if(admin&&b!=null)resetConfirm(u,b);}
                case "reset" -> {if(admin&&b!=null){u.closeInventory();p.recovery.reset(b,u.getUniqueId().toString());p.tell(u,"&fReset operation requested. Inspect console on failure.");}}
                case "assign" -> {if(admin&&b!=null)MarketChatInput.prompt(u,"assign:"+b.id,"Enter player UUID / player name in chat (or cancel).");}
                case "extend" -> {if(admin&&b!=null&&b.owner!=null){long old=b.expiry;b.expiry+=TimeUnit.DAYS.toMillis(7);
                    if(!p.save(b)){b.expiry=old;p.tell(u,"&cExtension was not saved.");}else p.db.audit(u.getUniqueId().toString(),"admin-extend",b.id);edit(u,b);}}
                case "suspend" -> {if(admin&&b!=null){boolean old=b.suspended;b.suspended=!b.suspended;
                    if(!p.save(b)){b.suspended=old;p.tell(u,"&cSuspension setting was not saved.");}else p.protectionEject(b);edit(u,b);}}
                case "setoffset" -> {if(admin&&b!=null){p.offsetFromPlayer(u,b,v[2]);edit(u,b);}}
                case "permissions" -> {if(admin)permissions(u,id);}
                case "perm" -> {if(admin){String path="permissions."+id.toLowerCase(Locale.ROOT)+"."+v[2];p.getConfig().set(path,!p.getConfig().getBoolean(path,false));p.saveConfig();permissions(u,id);}}
                case "styles" -> {if(admin)styles(u,Integer.parseInt(id));}
                case "style" -> {if(admin&&p.styles.containsKey(id))editStyle(u,p.styles.get(id));}
                case "styleretireconfirm" -> {if(admin&&p.styles.containsKey(id))retireStyleConfirm(u,p.styles.get(id));}
                case "styleretire" -> {if(admin&&p.styles.containsKey(id)){
                    if(p.booths.stream().anyMatch(booth->booth.style.equals(id))){p.tell(u,"&cThis style still has registered booths. Retire or reassign those booths first.");editStyle(u,p.styles.get(id));}
                    else {p.getConfig().set("styles."+id+".enabled",false);p.saveConfig();p.reloadStyles();p.db.audit(u.getUniqueId().toString(),"retire-style",id);styles(u,0);}
                }}
                case "styleclone" -> {if(admin&&p.styles.containsKey(id))MarketChatInput.prompt(u,"cloneStyle:"+id,"Type a unique style ID for the disabled copy (or cancel).");}
                case "stylepermissions" -> {if(admin&&p.styles.containsKey(id))stylePermissions(u,p.styles.get(id),v[2]);}
                case "styleperm" -> {if(admin&&p.styles.containsKey(id)){
                    MarketRole role=MarketRole.valueOf(v[2]);String path="styles."+id+".permissions."+role.name().toLowerCase(Locale.ROOT)+"."+v[3];
                    boolean current=MarketPolicy.allowed(p.getConfig(),id,role,v[3]);p.getConfig().set(path,!current);p.saveConfig();
                    stylePermissions(u,p.styles.get(id),v[2]);
                }}
                case "styletoggle" -> {if(admin&&p.styles.containsKey(id)){
                    String path="styles."+id+".enabled";p.getConfig().set(path,!p.styles.get(id).enabled);p.saveConfig();p.reloadStyles();editStyle(u,p.styles.get(id));
                }}
                case "stylefield" -> {if(admin&&p.styles.containsKey(id))MarketChatInput.prompt(u,"stylefield:"+id+":"+v[2],"Enter a new value for "+v[2]+" (or cancel) in chat.");}
                case "newstyle" -> {if(admin)MarketChatInput.prompt(u,"newstyle","Type the new style ID (letters, digits, underscore) in chat.");}
                default -> p.tell(u,"&cUnknown GUI action "+op);
            }
        }catch(Exception ex){p.getLogger().warning("Market GUI error: "+ex);p.tell(u,"&cThat action failed. See server console.");}
    }
}
