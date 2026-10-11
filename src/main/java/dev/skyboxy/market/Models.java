package dev.skyboxy.market;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import java.util.*;

/** All mutable state is managed on the main server thread. */
final class Booth {
    final String id, world, style;
    final int x, y, z, width, height, length, rotation;
    UUID owner;
    String leaseId;
    long expiry, wallChanged;
    String access = "PUBLIC", wallColor = "GRAY";
    boolean suspended, resetting;
    long visits, uniqueVisitors;
    double sales;
    final Set<UUID> trusted = new HashSet<>(), banned = new HashSet<>();

    Booth(String id, String world, String style, int x, int y, int z, int width, int height, int length, int rotation) {
        this.id = id; this.world = world; this.style = style; this.x = x; this.y = y; this.z = z;
        this.width = width; this.height = height; this.length = length; this.rotation = rotation;
    }
    boolean contains(Location l) {
        return l != null && l.getWorld() != null && world.equals(l.getWorld().getName())
                && l.getBlockX() >= x && l.getBlockX() < x + width
                && l.getBlockY() >= y && l.getBlockY() < y + height
                && l.getBlockZ() >= z && l.getBlockZ() < z + length;
    }
    boolean overlaps(Booth b) {
        return world.equals(b.world) && x < b.x+b.width && x+width > b.x
                && y < b.y+b.height && y+height > b.y && z < b.z+b.length && z+length > b.z;
    }
    boolean rented() { return owner != null && !resetting; }
    boolean expired(long now) { return owner != null && expiry > 0 && now >= expiry; }
    Location center() {
        World w = Bukkit.getWorld(world);
        return w == null ? null : new Location(w, x+(width/2.0), y+1.0, z+(length/2.0));
    }
    String status() { return resetting ? "RESETTING" : suspended ? "SUSPENDED" : owner != null ? "RENTED" : "AVAILABLE"; }
}

record Offset(double x, double y, double z, float yaw) {
    static Offset from(ConfigurationSection section, double defY) {
        if (section == null) return new Offset(0, defY, 0, 0);
        return new Offset(section.getDouble("x"), section.getDouble("y",defY),section.getDouble("z"),(float)section.getDouble("yaw"));
    }
}

final class MarketStyle {
    final String id, name, schematic, permission, wallMaterial;
    final Material icon;
    final boolean enabled;
    final int priority, durationHours, renewHours, cooldownHours, graceHours, maxPerPlayer, maxDurationHours, wallCooldown;
    final double price, renewalPrice, wallCost;
    final Offset teleport, hologram;
    final Set<String> colors;
    MarketStyle(String id, ConfigurationSection s) {
        this.id=id;
        this.name=s.getString("name",id);
        this.schematic=s.getString("schematic",id+".schem");
        this.icon=Optional.ofNullable(Material.matchMaterial(s.getString("icon","CHEST"))).orElse(Material.CHEST);
        this.enabled=s.getBoolean("enabled",true);
        this.priority=s.getInt("priority",0);
        ConfigurationSection r=s.getConfigurationSection("rental");
        if(r==null) throw new IllegalArgumentException("Missing rental section for "+id);
        price=r.getDouble("price",0); durationHours=r.getInt("duration-hours",168);
        renewalPrice=r.getDouble("renewal-price",price); renewHours=r.getInt("renewal-hours",durationHours);
        cooldownHours=r.getInt("cooldown-hours",0); graceHours=r.getInt("grace-hours",0);
        maxPerPlayer=r.getInt("max-per-player",1);maxDurationHours=r.getInt("max-duration-hours",672);
        permission=r.getString("required-permission","");
        ConfigurationSection offsets=s.getConfigurationSection("offsets");
        teleport=Offset.from(offsets==null?null:offsets.getConfigurationSection("teleport"),1);
        hologram=Offset.from(offsets==null?null:offsets.getConfigurationSection("hologram"),1.7);
        ConfigurationSection walls=s.getConfigurationSection("walls");
        wallMaterial=walls==null?"GRAY_CONCRETE":walls.getString("template-material","GRAY_CONCRETE");
        wallCost=walls==null?0:walls.getDouble("cost",0);
        wallCooldown=walls==null?0:walls.getInt("cooldown-seconds",0);
        colors=new HashSet<>((walls==null?List.<String>of("GRAY"):walls.getStringList("colors")).stream().map(v->v.toUpperCase(Locale.ROOT)).toList());
        if(!id.matches("[a-z0-9_]{2,32}"))throw new IllegalArgumentException("Style ID must be 2-32 lowercase letters/digits/underscore");
        if(!schematic.matches("[A-Za-z0-9_.-]+\\.schem"))throw new IllegalArgumentException("Schematic must be a safe .schem filename");
        if(!Double.isFinite(price)||price<0||!Double.isFinite(renewalPrice)||renewalPrice<0||!Double.isFinite(wallCost)||wallCost<0)
            throw new IllegalArgumentException("Rental and wall prices must be finite and nonnegative");
        if(durationHours<=0||renewHours<=0||cooldownHours<0||graceHours<0||maxPerPlayer<=0||maxDurationHours<durationHours)
            throw new IllegalArgumentException("Rental duration, limits, cooldown, or grace settings are invalid");
        if(wallCooldown<0)throw new IllegalArgumentException("Wall cooldown must be nonnegative");
        Material wall=Material.matchMaterial(wallMaterial);
        if(wall==null||!wall.name().endsWith("_CONCRETE")||wall.name().endsWith("_CONCRETE_POWDER"))
            throw new IllegalArgumentException("Wall template material must be concrete");
        List<String> allowedColors=List.of("WHITE","ORANGE","MAGENTA","LIGHT_BLUE","YELLOW","LIME","PINK","GRAY","LIGHT_GRAY","CYAN","PURPLE","BLUE","BROWN","GREEN","RED","BLACK");
        if(colors.isEmpty()||!allowedColors.containsAll(colors))throw new IllegalArgumentException("Wall colors must be valid concrete colors");
        if(!Double.isFinite(teleport.x())||!Double.isFinite(teleport.y())||!Double.isFinite(teleport.z())||!Float.isFinite(teleport.yaw())
                ||!Double.isFinite(hologram.x())||!Double.isFinite(hologram.y())||!Double.isFinite(hologram.z()))
            throw new IllegalArgumentException("Teleport and hologram offsets must be finite");
    }
    boolean isWall(Material mat) { return mat.name().equals(wallMaterial); }
    Location offset(Booth b, Offset o) {
        World w=Bukkit.getWorld(b.world);
        if(w==null)return null;
        // Offset is relative to the center of the ROTATED bounding box, so 0,0 is stable.
        double dx=o.x(),dz=o.z();
        double rx=switch (b.rotation) {case 1 -> -dz;case 2 -> -dx;case 3 -> dz;default -> dx;};
        double rz=switch (b.rotation) {case 1 -> dx;case 2 -> -dz;case 3 -> -dx;default -> dz;};
        return new Location(w,b.x+b.width/2.0+rx,b.y+o.y(),b.z+b.length/2.0+rz, o.yaw()+b.rotation*90,0);
    }
}

enum MarketRole { ADMIN, RENTER, MEMBER, VISITOR }
