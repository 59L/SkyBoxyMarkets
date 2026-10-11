package dev.skyboxy.market;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class ShopProvidersTest {
    @TempDir Path directory;

    private static final class Fake implements ShopProvider {
        final String name;final Status status;final boolean claims;final boolean failDetach;final List<String> detached;
        Fake(String name,Status status,boolean claims,boolean failDetach,List<String> detached){
            this.name=name;this.status=status;this.claims=claims;this.failDetach=failDetach;this.detached=detached;
        }
        @Override public String name(){return name;}
        @Override public Status status(){return status;}
        @Override public boolean shopAt(Location location){return claims;}
        @Override public void detach(Booth booth)throws Exception{
            if(failDetach)throw new IllegalStateException(name+" shop still present");
            detached.add(name);
        }
    }

    private static Booth ownedBooth(){
        Booth booth=new Booth("booth","world","normal",1,2,3,11,11,11,0);
        booth.owner=UUID.randomUUID();booth.leaseId="lease-1";
        return booth;
    }

    @Test
    void firstAvailableClaimingProviderOwnsTheBlock(){
        List<String> log=new ArrayList<>();
        Fake incompatible=new Fake("broken",ShopProvider.Status.INCOMPATIBLE,true,false,log);
        Fake absent=new Fake("absent",ShopProvider.Status.ABSENT,true,false,log);
        Fake first=new Fake("first",ShopProvider.Status.AVAILABLE,true,false,log);
        Fake second=new Fake("second",ShopProvider.Status.AVAILABLE,true,false,log);
        Location at=new Location(null,0,0,0);

        assertSame(first,new ShopProviders(List.of(incompatible,absent,first,second)).ownerOf(at));
        assertSame(second,new ShopProviders(List.of(second,first)).ownerOf(at));
        assertNull(new ShopProviders(List.of(incompatible,absent)).ownerOf(at));
        assertFalse(new ShopProviders(List.of()).shopAt(at));
        assertFalse(new ShopProviders(List.of(incompatible,absent)).anyAvailable());
    }

    @Test
    void detachFailureStopsResetBeforeLaterProvidersAndKeepsJournal()throws Exception{
        try(MarketDatabase database=new MarketDatabase(directory.resolve("detach.db").toFile())){
            Booth booth=ownedBooth();database.save(booth);database.beginReset(booth);
            List<String> log=new ArrayList<>();
            ShopProviders shops=new ShopProviders(List.of(
                    new Fake("ok",ShopProvider.Status.AVAILABLE,false,false,log),
                    new Fake("stuck",ShopProvider.Status.AVAILABLE,false,true,log),
                    new Fake("later",ShopProvider.Status.AVAILABLE,false,false,log),
                    new Fake("absent",ShopProvider.Status.ABSENT,false,true,log)));

            assertThrows(IllegalStateException.class,()->shops.detach(booth));
            assertEquals(List.of("ok"),log);
            assertEquals(1,database.resets().size(),"reset journal must survive a failed detach");
        }
    }

    @Test
    void saleIsCountedOncePerProviderKeyAcrossRestart()throws Exception{
        Path file=directory.resolve("sales.db");
        try(MarketDatabase database=new MarketDatabase(file.toFile())){
            Booth booth=ownedBooth();database.save(booth);
            assertTrue(ShopProviders.recordSale(database,booth,"ChestShop","tx-1",12.5));
            assertFalse(ShopProviders.recordSale(database,booth,"ChestShop","tx-1",12.5));
            assertTrue(ShopProviders.recordSale(database,booth,"QuickShop-Hikari","tx-1",2.5));
            assertEquals(15.0,booth.sales);
        }
        try(MarketDatabase database=new MarketDatabase(file.toFile())){
            Booth booth=database.loadBooths().getFirst();
            assertEquals(15.0,booth.sales);
            assertFalse(ShopProviders.recordSale(database,booth,"ChestShop","tx-1",12.5));
            assertEquals(15.0,database.loadBooths().getFirst().sales);
        }
    }

    @Test
    void invalidSalesAreIgnored()throws Exception{
        try(MarketDatabase database=new MarketDatabase(directory.resolve("invalid.db").toFile())){
            Booth unowned=new Booth("free","world","normal",1,2,3,11,11,11,0);database.save(unowned);
            Booth owned=ownedBooth();database.save(owned);
            assertFalse(ShopProviders.recordSale(database,null,"p","k0",1));
            assertFalse(ShopProviders.recordSale(database,unowned,"p","k1",1));
            assertFalse(ShopProviders.recordSale(database,owned,"p","k2",-1));
            assertFalse(ShopProviders.recordSale(database,owned,"p","k3",Double.NaN));
            assertFalse(ShopProviders.recordSale(database,owned,"p","k4",Double.POSITIVE_INFINITY));
            assertEquals(0.0,owned.sales);
        }
    }
}
