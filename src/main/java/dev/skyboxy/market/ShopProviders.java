package dev.skyboxy.market;

import org.bukkit.Location;
import java.sql.SQLException;
import java.util.List;

/** One optional shop plugin integration. Implementations fail closed: anything but AVAILABLE does nothing. */
interface ShopProvider {
    enum Status { AVAILABLE, ABSENT, INCOMPATIBLE }
    String name();
    Status status();
    boolean shopAt(Location location);
    /** Removes this provider's shops inside the booth. Throws while any shop remains, which keeps the reset journaled and the booth locked. */
    void detach(Booth booth) throws Exception;
}

/** Registered shop providers. Registration order is precedence: a block belongs to the first available provider that claims it. */
final class ShopProviders {
    private final List<ShopProvider> providers;
    ShopProviders(List<ShopProvider> providers){this.providers=List.copyOf(providers);}
    List<ShopProvider> all(){return providers;}
    boolean anyAvailable(){return providers.stream().anyMatch(s->s.status()==ShopProvider.Status.AVAILABLE);}
    ShopProvider ownerOf(Location location){
        for(ShopProvider s:providers)if(s.status()==ShopProvider.Status.AVAILABLE&&s.shopAt(location))return s;
        return null;
    }
    boolean shopAt(Location location){return ownerOf(location)!=null;}
    /** Detaches every available provider; the first failure propagates and stops the reset before world changes. */
    void detach(Booth booth)throws Exception{
        for(ShopProvider s:providers)if(s.status()==ShopProvider.Status.AVAILABLE)s.detach(booth);
    }
    /** Shared sale accounting. The (provider,key) pair is stored durably, so a replayed sale is counted once. */
    static boolean recordSale(MarketDatabase db,Booth booth,String provider,String key,double amount)throws SQLException{
        if(booth==null||booth.owner==null||!Double.isFinite(amount)||amount<0)return false;
        return db.recordShopSale(booth,provider,key,amount);
    }
}
