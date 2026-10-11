package dev.skyboxy.market;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import java.util.*;
import java.util.concurrent.TimeUnit;

final class MarketRentals {
    private enum ChargeOutcome { CHARGED, DECLINED, REVIEW_REQUIRED }
    private final SkyBoxyMarket p;
    private final Set<String> paidActionsInFlight=new HashSet<>();
    private final Map<String,Long> lastPaidActions=new HashMap<>();
    MarketRentals(SkyBoxyMarket p){this.p=p;}
    private String paidActionKey(Player user,Booth booth,String action){return user.getUniqueId()+":"+booth.id+":"+action;}
    private boolean beginPaidAction(String key,Player user){
        long now=System.currentTimeMillis(),last=lastPaidActions.getOrDefault(key,0L);
        if(paidActionsInFlight.contains(key)||now-last<1500){p.tell(user,"&ePlease wait before repeating that paid action.");return false;}
        paidActionsInFlight.add(key);return true;
    }
    private void endPaidAction(String key,boolean successful){
        paidActionsInFlight.remove(key);if(successful)lastPaidActions.put(key,System.currentTimeMillis());
        if(lastPaidActions.size()>10000){long cutoff=System.currentTimeMillis()-60_000;lastPaidActions.entrySet().removeIf(entry->entry.getValue()<cutoff);}
    }
    private String beginPayment(Player player,Booth booth,String action,double amount) throws Exception {
        String provider=p.economy==null?"Unavailable":p.economy.getName();
        return p.db.createPaymentIntent(player.getUniqueId(),booth.id,action,amount,provider);
    }
    private boolean recordCharge(String intent,Player player){
        try{p.db.transitionPaymentIntent(intent,"PREPARED","CHARGED","Economy provider accepted withdrawal");return true;}
        catch(Exception ex){p.getLogger().severe("Payment intent "+intent+" may have been charged but could not record the provider result: "+ex);
            p.tell(player,"&cPayment state needs staff review. Keep this reference: "+intent);return false;}
    }
    private ChargeOutcome chargeAndJournal(Player player,String intent,double amount){
        final boolean accepted;
        try{accepted=p.charge(player,amount);}
        catch(RuntimeException ex){
            try{p.db.transitionPaymentIntent(intent,"PREPARED","REVIEW_REQUIRED","Economy provider threw during withdrawal; result is ambiguous");}
            catch(Exception journalFailure){ex.addSuppressed(journalFailure);}
            p.getLogger().severe("Economy withdrawal has an ambiguous result for payment intent "+intent+": "+ex);
            p.tell(player,"&cPayment state needs staff review. Do not retry yet. Reference: "+intent);return ChargeOutcome.REVIEW_REQUIRED;
        }
        if(!accepted){recordDecline(intent);return ChargeOutcome.DECLINED;}
        return recordCharge(intent,player)?ChargeOutcome.CHARGED:ChargeOutcome.REVIEW_REQUIRED;
    }
    private void recordDecline(String intent){
        try{p.db.transitionPaymentIntent(intent,"PREPARED","DECLINED","Economy provider did not accept withdrawal");}
        catch(Exception ex){p.getLogger().severe("Could not record declined payment intent "+intent+": "+ex);}
    }
    private boolean compensate(Player player,String intent,double amount){
        try{p.db.transitionPaymentIntent(intent,"CHARGED","REFUND_PENDING","Marketplace database operation failed; refund is being attempted");}
        catch(Exception ex){p.getLogger().severe("Refund was not attempted because payment intent "+intent+" could not be durably marked: "+ex);
            p.tell(player,"&cPayment needs staff review; no automatic refund was attempted. Reference: "+intent);return false;}
        final boolean refunded;
        try{refunded=p.refund(player,amount);}
        catch(RuntimeException ex){
            try{p.db.transitionPaymentIntent(intent,"REFUND_PENDING","REVIEW_REQUIRED","Economy provider threw during refund; result is ambiguous");}
            catch(Exception journalFailure){ex.addSuppressed(journalFailure);}
            p.getLogger().severe("Economy refund has an ambiguous result for payment intent "+intent+": "+ex);
            p.tell(player,"&cRefund state needs staff review. Reference: "+intent);return false;
        }
        if(!refunded){
            try{p.db.transitionPaymentIntent(intent,"REFUND_PENDING","REVIEW_REQUIRED","Economy provider rejected the refund");}
            catch(Exception ex){p.getLogger().severe("Could not record failed refund for payment intent "+intent+": "+ex);}
            p.tell(player,"&cRefund needs staff review. Reference: "+intent);return false;
        }
        try{p.db.transitionPaymentIntent(intent,"REFUND_PENDING","REFUNDED","Economy provider accepted compensation");return true;}
        catch(Exception ex){p.getLogger().severe("Refund succeeded but its result could not be recorded for payment intent "+intent+": "+ex);
            p.tell(player,"&cRefund was sent, but its record needs staff review. Reference: "+intent);return false;}
    }
    void rent(Player player,Booth b){
        MarketStyle s=p.style(b);long now=System.currentTimeMillis();
        if(s==null||!s.enabled||p.templates.get(s.id)==null||b.rented()||b.suspended||b.resetting){p.tell(player,"&cBooth is not available.");return;}
        if(!s.permission.isBlank()&&!player.hasPermission(s.permission)){p.tell(player,"&cYou do not have the required rank/permission.");return;}
        if(p.boothLimit(player.getUniqueId())>=p.getConfig().getInt("market.max-total-booths-per-player",1)){
            p.tell(player,"&cYou have reached your total booth limit.");return;
        }
        if(p.own(player.getUniqueId()).stream().filter(booth->booth.style.equals(b.style)).count()>=s.maxPerPlayer){p.tell(player,"&cYou have reached your style limit.");return;}
        try{
            long until=p.db.cooldown(player.getUniqueId(),b.style);
            if(until>now){p.tell(player,"&cYou can rent this type again in "+Math.max(1,TimeUnit.MILLISECONDS.toMinutes(until-now))+" minutes.");return;}
            String action=paidActionKey(player,b,"rent");if(!beginPaidAction(action,player))return;
            String intent;
            try{intent=beginPayment(player,b,"RENT",s.price);}
            catch(Exception ex){endPaidAction(action,false);p.getLogger().severe("Could not prepare rent payment intent: "+ex);p.tell(player,"&cCould not prepare payment. No charge was attempted.");return;}
            ChargeOutcome charge=chargeAndJournal(player,intent,s.price);
            if(charge!=ChargeOutcome.CHARGED){endPaidAction(action,false);if(charge==ChargeOutcome.DECLINED)p.tell(player,"&cInsufficient funds or no Vault economy.");return;}
            try{
                p.db.commitRental(b,player.getUniqueId(),UUID.randomUUID().toString(),now+TimeUnit.HOURS.toMillis(s.durationHours),
                        now+TimeUnit.HOURS.toMillis(s.cooldownHours),intent);
            }catch(Exception ex){
                boolean refunded=compensate(player,intent,s.price);endPaidAction(action,false);
                p.getLogger().severe("Rent transaction failed: "+ex);
                if(refunded)p.tell(player,"&cCould not save rental. Payment was refunded.");return;
            }
            endPaidAction(action,true);p.holograms.refresh(b);
            p.tell(player,"&aYou rented "+s.name+"&a! Use /market to manage it.");teleport(player,b);
        }catch(Exception ex){p.getLogger().severe("Rent failed: "+ex);}
    }
    void renew(Player user,Booth b){
        if(!user.getUniqueId().equals(b.owner)||b.resetting)return;
        MarketStyle s=p.style(b);if(s==null)return;
        long now=System.currentTimeMillis();
        long duration=TimeUnit.HOURS.toMillis(s.renewHours);
        long next=Math.max(now,b.expiry)+duration;
        if(next-now>TimeUnit.HOURS.toMillis(s.maxDurationHours)){p.tell(user,"&cMaximum prepaid duration reached.");return;}
        String action=paidActionKey(user,b,"renew");if(!beginPaidAction(action,user))return;
        String intent;
        try{intent=beginPayment(user,b,"RENEW",s.renewalPrice);}
        catch(Exception ex){endPaidAction(action,false);p.getLogger().severe("Could not prepare renewal payment intent: "+ex);p.tell(user,"&cCould not prepare payment. No charge was attempted.");return;}
        ChargeOutcome charge=chargeAndJournal(user,intent,s.renewalPrice);
        if(charge!=ChargeOutcome.CHARGED){endPaidAction(action,false);if(charge==ChargeOutcome.DECLINED)p.tell(user,"&cInsufficient funds.");return;}
        try{p.db.commitRenewal(b,next,user.getUniqueId(),intent);}
        catch(Exception ex){
            boolean refunded=compensate(user,intent,s.renewalPrice);endPaidAction(action,false);p.getLogger().severe("Renewal transaction failed: "+ex);
            if(refunded)p.tell(user,"&cCould not save renewal. Payment was refunded.");return;
        }
        endPaidAction(action,true);p.holograms.refresh(b);p.tell(user,"&aRental extended.");
    }
    void walls(Player user,Booth b,String color){
        MarketStyle s=p.style(b);if(s==null||!user.getUniqueId().equals(b.owner)||b.resetting||b.suspended)return;
        if(!s.colors.contains(color)){p.tell(user,"&cThis color is disabled for this booth style.");return;}
        if(System.currentTimeMillis()-b.wallChanged<1000L*s.wallCooldown){p.tell(user,"&cWall-change cooldown active.");return;}
        Material mat=Material.matchMaterial(color+"_CONCRETE");
        if(mat==null){p.tell(user,"&cNot a valid concrete color.");return;}
        if(color.equalsIgnoreCase(b.wallColor)){p.tell(user,"&7Walls already use that color.");return;}
        String action=paidActionKey(user,b,"walls");if(!beginPaidAction(action,user))return;
        String intent;
        try{intent=beginPayment(user,b,"WALLS",s.wallCost);}
        catch(Exception ex){endPaidAction(action,false);p.getLogger().severe("Could not prepare wall payment intent: "+ex);p.tell(user,"&cCould not prepare payment. No charge was attempted.");return;}
        ChargeOutcome charge=chargeAndJournal(user,intent,s.wallCost);
        if(charge!=ChargeOutcome.CHARGED){endPaidAction(action,false);if(charge==ChargeOutcome.DECLINED)p.tell(user,"&cYou cannot afford this color change.");return;}
        String old=b.wallColor;long last=b.wallChanged;Material oldMaterial=Material.matchMaterial(old+"_CONCRETE");
        try{
            StyleTemplates.Template template=p.templates.get(b.style);if(template==null)throw new IllegalStateException("Template missing");
            template.recolor(b,mat);p.db.commitWallChange(b,color,System.currentTimeMillis(),user.getUniqueId(),intent);
            endPaidAction(action,true);p.tell(user,"&aWalls recolored to "+color+".");
        }catch(Exception ex){
            b.wallColor=old;b.wallChanged=last;
            try{StyleTemplates.Template template=p.templates.get(b.style);if(template!=null&&oldMaterial!=null)template.recolor(b,oldMaterial);}catch(Exception rollback){ex.addSuppressed(rollback);}
            boolean refunded=compensate(user,intent,s.wallCost);endPaidAction(action,false);p.getLogger().severe("Wall recolor failed: "+ex);
            if(refunded)p.tell(user,"&cWall update failed. Payment was refunded.");
        }
    }
    void expire(){
        long now=System.currentTimeMillis();
        for(Booth b:new ArrayList<>(p.booths)){
            MarketStyle s=p.style(b);
            if(s!=null&&b.owner!=null&&!b.resetting&&now>=b.expiry+TimeUnit.HOURS.toMillis(s.graceHours)){
                p.recovery.reset(b,"EXPIRED");
            }
        }
        if(p.db!=null)p.recovery.resumeResets();
    }
    void teleport(Player player,Booth b){
        MarketStyle s=p.style(b);if(s==null)return;
        Location l=s.offset(b,s.teleport);
        if(l==null){p.tell(player,"&cShop world unavailable.");return;}
        Location safe=findSafe(l,b);
        if(safe==null){p.tell(player,"&cNo safe teleport spot. Ask staff to set a teleport offset.");return;}
        // If the booth is private, only eligible players may teleport into it.
        if(!p.role(player,b).equals(MarketRole.ADMIN)&&!canEnter(player,b)){p.tell(player,"&cShop is locked or you are banned.");return;}
        player.teleportAsync(safe);
    }
    boolean canEnter(Player player,Booth b){
        return MarketPolicy.canEnter(player.getUniqueId(),b.owner,b.trusted,b.banned,b.access,b.suspended,b.resetting,
                player.hasPermission("skyboxymarket.bypass"));
    }
    Location findSafe(Location desired,Booth b){
        if(desired.getWorld()==null)return null;
        for(int d=0;d<Math.min(b.height,10);d++){
            Location spot=desired.clone().add(0,d,0);
            if(!b.contains(spot))continue;
            if(spot.getBlock().isPassable()&&spot.clone().add(0,1,0).getBlock().isPassable()
                    &&spot.clone().add(0,-1,0).getBlock().getType().isSolid())return spot;
        }
        return null;
    }
}
