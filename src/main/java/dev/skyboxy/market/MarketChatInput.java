package dev.skyboxy.market;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.configuration.ConfigurationSection;
import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Chat is only used for typing values; all configuration navigation happens in inventory GUIs. */
final class MarketChatInput implements Listener {
    private final SkyBoxyMarket p;
    private static final Map<UUID,String> pending=new ConcurrentHashMap<>();
    MarketChatInput(SkyBoxyMarket p){this.p=p;}
    static void prompt(Player user,String action,String text){
        pending.put(user.getUniqueId(),action);user.closeInventory();
        user.sendMessage(SkyBoxyMarket.c("&8[&aMarket&8] &e"+text));
    }
    @EventHandler public void input(AsyncChatEvent e){
        Player user=e.getPlayer();String action=pending.remove(user.getUniqueId());
        if(action==null)return;
        e.setCancelled(true);
        String value=PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Bukkit.getScheduler().runTask(p,()->process(user,action,value));
    }
    private UUID find(String value){
        try{return UUID.fromString(value);}catch(IllegalArgumentException ignored){}
        Player online=Bukkit.getPlayerExact(value);if(online!=null)return online.getUniqueId();
        OfflinePlayer offline=Bukkit.getOfflinePlayer(value);
        return offline.hasPlayedBefore()?offline.getUniqueId():null;
    }
    private void process(Player user,String raw,String text){
        if(text.equalsIgnoreCase("cancel")){p.tell(user,"&7Action cancelled.");return;}
        try{
            String[] a=raw.split(":",-1);
            switch(a[0]){
                case "ownersearch" -> {
                    if(!user.hasPermission("skyboxymarket.admin"))return;
                    UUID owner=find(text);if(owner==null){p.tell(user,"&cUnknown player. Use a full UUID if they never joined.");return;}
                    p.menus.adminOwnerSearch(user,owner,0);
                }
                case "access" -> {
                    Booth b=p.byId(a[1]);if(b==null)return;
                    if(!user.getUniqueId().equals(b.owner)&&!user.hasPermission("skyboxymarket.admin"))return;
                    UUID target=find(text);if(target==null){p.tell(user,"&cUnknown player. Use a full UUID if they never joined.");return;}
                    if(target.equals(b.owner)){p.tell(user,"&cYou cannot ban or add the renter.");return;}
                    boolean trust=a[2].equals("TRUST");
                    p.db.access(b,target,trust,true);
                    if(trust&&b.banned.contains(target))p.db.access(b,target,false,false);
                    if(!trust&&b.trusted.contains(target))p.db.access(b,target,true,false);
                    p.db.audit(user.getUniqueId().toString(),trust?"trust":"ban",b.id+":"+target);
                    p.protectionEject(b);p.menus.accessList(user,b,!trust,0);
                }
                case "assign" -> {
                    if(!user.hasPermission("skyboxymarket.admin"))return;
                    Booth b=p.byId(a[1]);if(b==null)return;
                    if(b.owner!=null||b.resetting){p.tell(user,"&cTerminate the existing lease first to safely recover belongings.");return;}
                    UUID newOwner=find(text);if(newOwner==null){p.tell(user,"&cUnknown player. Enter a UUID.");return;}
                    MarketStyle style=p.style(b);if(style==null)return;
                    String oldLease=b.leaseId;long oldExpiry=b.expiry;
                    b.owner=newOwner;b.leaseId=UUID.randomUUID().toString();b.expiry=System.currentTimeMillis()+style.durationHours*3_600_000L;
                    if(!p.save(b)){b.owner=null;b.leaseId=oldLease;b.expiry=oldExpiry;p.tell(user,"&cOwner assignment was not saved.");return;}
                    p.db.audit(user.getUniqueId().toString(),"admin-assign",b.id+":"+newOwner);p.menus.edit(user,b);
                }
                case "stylefield" -> {
                    if(!user.hasPermission("skyboxymarket.admin"))return;
                    String style=a[1],field=a[2];
                    if(!p.styles.containsKey(style))return;
                    String path="styles."+style+"."+field;
                    Object oldValue=p.getConfig().get(path);
                    Object parsed;
                    if(field.equals("name")||field.equals("rental.required-permission")) parsed=text.equalsIgnoreCase("none")?"":text;
                    else if(field.equals("schematic")){
                        if(p.booths.stream().anyMatch(b->b.style.equals(style))){p.tell(user,"&cCannot replace template while registered booths use this style.");return;}
                        if(!text.matches("[A-Za-z0-9_.-]+\\.schem")||!new File(p.getDataFolder(),"schematics/"+text).isFile()){
                            p.tell(user,"&cUpload an existing .schem filename into plugins/SkyBoxyMarket/schematics first.");return;
                        }
                        parsed=text;
                    }else if(field.equals("icon")){
                        org.bukkit.Material material=org.bukkit.Material.matchMaterial(text);
                        if(material==null||material.isAir()||!material.isItem())throw new IllegalArgumentException("Enter a valid block or item material name");
                        parsed=material.name();
                    }else if(field.equals("walls.template-material")){
                        if(p.booths.stream().anyMatch(b->b.style.equals(style))){p.tell(user,"&cCannot change the wall template material while registered booths use this style.");return;}
                        org.bukkit.Material material=org.bukkit.Material.matchMaterial(text);
                        if(material==null||!material.name().endsWith("_CONCRETE"))throw new IllegalArgumentException("Wall template material must be concrete");
                        parsed=material.name();
                    }else if(field.equals("walls.colors")){
                        List<String> allowed=List.of("WHITE","ORANGE","MAGENTA","LIGHT_BLUE","YELLOW","LIME","PINK","GRAY","LIGHT_GRAY","CYAN","PURPLE","BLUE","BROWN","GREEN","RED","BLACK");
                        List<String> colors=Arrays.stream(text.toUpperCase(Locale.ROOT).split("[, ]+"))
                                .filter(value->!value.isBlank()).distinct().toList();
                        if(colors.isEmpty()||!allowed.containsAll(colors))throw new IllegalArgumentException("Use one or more of the 16 concrete colors separated by commas");
                        parsed=colors;
                    }else if(field.equals("rental.price")||field.equals("rental.renewal-price")||field.equals("walls.cost")){
                        double n=Double.parseDouble(text);if(!Double.isFinite(n)||n<0)throw new IllegalArgumentException("Nonnegative price required");parsed=n;
                    }else if(field.equals("priority")){
                        int n=Integer.parseInt(text);if(Math.abs((long)n)>100000)throw new IllegalArgumentException("Priority must be between -100000 and 100000");parsed=n;
                    }else{
                        int n=Integer.parseInt(text);if(n<0||(field.endsWith("hours")&&n>1000000))throw new IllegalArgumentException("Nonnegative reasonable amount required");parsed=n;
                    }
                    p.getConfig().set(path,parsed);p.saveConfig();p.reloadStyles();p.holograms.refreshAll();
                    if(field.equals("schematic")&&p.templates.get(style)==null){
                        p.getConfig().set(path,oldValue);
                        p.saveConfig();p.reloadStyles();p.tell(user,"&cThe schematic did not load as a valid template; the prior path was restored.");return;
                    }
                    p.db.audit(user.getUniqueId().toString(),"style-setting",style+" "+field+"="+parsed);p.menus.editStyle(user,p.styles.get(style));
                }
                case "cloneStyle" -> {
                    if(!user.hasPermission("skyboxymarket.admin"))return;
                    String source=a[1];if(!p.styles.containsKey(source)||!text.matches("[a-z0-9_]{2,32}")||p.styles.containsKey(text)){
                        p.tell(user,"&cUse a unique style ID with 2-32 lowercase letters/digits/underscore.");return;
                    }
                    ConfigurationSection original=p.getConfig().getConfigurationSection("styles."+source);if(original==null)return;
                    String base="styles."+text+".";
                    for(Map.Entry<String,Object> entry:original.getValues(true).entrySet()){
                        if(entry.getValue() instanceof ConfigurationSection)continue;
                        p.getConfig().set(base+entry.getKey(),entry.getValue());
                    }
                    p.getConfig().set(base+"enabled",false);p.getConfig().set(base+"name",original.getString("name",source)+" &7(Copy)");
                    p.saveConfig();p.reloadStyles();p.db.audit(user.getUniqueId().toString(),"clone-style",source+"->"+text);
                    p.tell(user,"&aCreated disabled style copy "+text+".");p.menus.editStyle(user,p.styles.get(text));
                }
                case "newstyle" -> {
                    if(!user.hasPermission("skyboxymarket.admin"))return;
                    if(!text.matches("[a-z0-9_]{2,32}")||p.styles.containsKey(text)){p.tell(user,"&cUse 2-32 lowercase letters/digits/underscore and a unique ID.");return;}
                    String base="styles."+text+".";
                    var c=p.getConfig();
                    c.set(base+"name","&f"+text);c.set(base+"schematic",text+".schem");
                    c.set(base+"icon","CHEST");c.set(base+"enabled",false);c.set(base+"priority",0);
                    c.set(base+"rental.price",0);c.set(base+"rental.duration-hours",168);
                    c.set(base+"rental.renewal-price",0);c.set(base+"rental.renewal-hours",168);
                    c.set(base+"rental.cooldown-hours",0);c.set(base+"rental.grace-hours",0);
                    c.set(base+"rental.max-per-player",1);c.set(base+"rental.max-duration-hours",672);
                    c.set(base+"rental.required-permission","");
                    c.set(base+"offsets.teleport.x",0);c.set(base+"offsets.teleport.y",1);c.set(base+"offsets.teleport.z",0);
                    c.set(base+"offsets.hologram.x",0);c.set(base+"offsets.hologram.y",1.7);c.set(base+"offsets.hologram.z",0);
                    c.set(base+"walls.template-material","GRAY_CONCRETE");c.set(base+"walls.cost",0);c.set(base+"walls.cooldown-seconds",0);
                    c.set(base+"walls.colors",List.of("WHITE","ORANGE","MAGENTA","LIGHT_BLUE","YELLOW","LIME","PINK","GRAY","LIGHT_GRAY","CYAN","PURPLE","BLUE","BROWN","GREEN","RED","BLACK"));
                    p.saveConfig();p.reloadStyles();p.db.audit(user.getUniqueId().toString(),"new-style",text);
                    p.tell(user,"&aCreated disabled style. Upload "+text+".schem, then enable it in the style GUI.");p.menus.editStyle(user,p.styles.get(text));
                }
            }
        }catch(Exception ex){p.getLogger().warning("Market chat entry failed: "+ex);p.tell(user,"&cInvalid input or database failure: "+ex.getMessage());}
    }
}
